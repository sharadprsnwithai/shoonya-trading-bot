package com.tradingbot.strategy.breakoutvwap.service;

import com.tradingbot.bus.SignalPublisher;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.feed.ShoonyaFeedClient;
import com.tradingbot.model.Candle;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import com.tradingbot.strategy.breakoutvwap.config.BvsProperties;
import com.tradingbot.strategy.breakoutvwap.model.BvsBias;
import com.tradingbot.strategy.breakoutvwap.model.BvsPosition;
import com.tradingbot.strategy.breakoutvwap.model.BvsSetup;
import com.tradingbot.strategy.breakoutvwap.model.BvsSetupState;
import com.tradingbot.strategy.breakoutvwap.model.BvsUniverse;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.NseTradingCalendarUtil;
import com.tradingbot.util.StockFnoRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Breakout VWAP (BVS) strategy engine: dual-level confluence bias (09:30-10:00 session range +
 * previous-day H/L), VWAP-crossing 5m trigger candle, futures entry on the trigger high/low break
 * within the entry window, 50% partial at 1:2.5R moving the stop to breakeven, runner until the
 * 10-EMA close cross, 14:30 entry cutoff, 15:00 hard exit.
 */
@Service
@Slf4j
public class BreakoutVwapService {

    /**
     * Strategy identifier used for TradeSignal routing (config key strategy-modes.BREAKOUT_VWAP).
     */
    public static final String STRATEGY_ID = "BREAKOUT_VWAP";

    private static final int SESSION_RANGE_SLOT_MINUTES = 5;
    private static final int FIVE_MINUTES_SECONDS = 300;
    private static final Duration QUOTE_MAX_AGE = Duration.ofSeconds(45);

    private final BvsProperties properties;
    private final TechnicalAnalysisService technicalAnalysisService;
    private final ShoonyaMarketDataService marketDataService;
    private final BvsUniverseScanner scanner;
    private final ShoonyaFeedClient feedClient;
    private final TelegramService telegramService;
    private final SignalPublisher signalPublisher;

    private final Map<String, ShoonyaMarketDataService.FuturesContract> contractCache =
            new ConcurrentHashMap<>();

    final Map<String, BvsSetup> setups = new LinkedHashMap<>();
    final Map<String, BvsPosition> openPositions = new LinkedHashMap<>();
    final List<BvsPosition> tradeHistory = new ArrayList<>();

    private Clock clock = Clock.system(NseTradingCalendarUtil.IST_ZONE);

    @Autowired
    public BreakoutVwapService(
            BvsProperties properties,
            TechnicalAnalysisService technicalAnalysisService,
            ShoonyaMarketDataService marketDataService,
            BvsUniverseScanner scanner,
            ShoonyaFeedClient feedClient,
            TelegramService telegramService,
            @Autowired(required = false) SignalPublisher signalPublisher) {
        this.properties = properties;
        this.technicalAnalysisService = technicalAnalysisService;
        this.marketDataService = marketDataService;
        this.scanner = scanner;
        this.feedClient = feedClient;
        this.telegramService = telegramService;
        this.signalPublisher = signalPublisher;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    // --- lifecycle ---

    /**
     * 09:25 scan: builds the day's 12-stock universe and one {@link BvsSetup} per symbol with the
     * previous day's high/low. An empty universe stands the strategy down for the day.
     */
    public synchronized void runScan() {
        BvsUniverse universe = scanner.scan();
        setups.clear();
        if (universe.allSymbols().isEmpty()) {
            log.warn("BVS stood down: no universe selected today");
            notifyTelegram(
                    "BVS | stood down — no universe selected (ban list or feed unavailable)");
            return;
        }
        LocalDate today = LocalDate.now(clock);
        for (String symbol : universe.allSymbols()) {
            BvsSetup setup = new BvsSetup(symbol);
            applyPreviousDayLevels(symbol, setup, today);
            setups.put(symbol, setup);
        }
        // Telegram per spec rule 26: stand-down, entry, partial, stop move, exit — no scan message.
        log.info("BVS scan complete: {} symbols — {}", setups.size(), setups.keySet());
    }

    private void applyPreviousDayLevels(String symbol, BvsSetup setup, LocalDate today) {
        try {
            List<Candle> daily = marketDataService.fetchDailyCandles(symbol, 10);
            if (daily == null || daily.isEmpty()) {
                log.warn("BVS: no daily candles for {}, PDH/PDL missing", symbol);
                return;
            }
            List<Candle> completed =
                    daily.stream()
                            .filter(
                                    c ->
                                            c.timestamp() != null
                                                    && !LocalDate.ofInstant(
                                                                    c.timestamp(),
                                                                    NseTradingCalendarUtil.IST_ZONE)
                                                            .equals(today))
                            .sorted(Comparator.comparing(Candle::timestamp))
                            .toList();
            if (completed.isEmpty()) {
                log.warn("BVS: no completed sessions for {}, PDH/PDL missing", symbol);
                return;
            }
            Candle prev = completed.get(completed.size() - 1);
            setup.setPdh(prev.high() != null ? prev.high().doubleValue() : null);
            setup.setPdl(prev.low() != null ? prev.low().doubleValue() : null);
        } catch (RuntimeException e) {
            log.warn("BVS: PDH/PDL fetch failed for {} ({})", symbol, e.getMessage());
        }
    }

    /**
     * 5-minute cycle: session range, confluence latch, bias-flip trigger cancellation, stop and
     * runner management, trigger arming. Also squares everything off at/after the hard exit.
     */
    public void runCycle() {
        LocalTime now = LocalTime.now(clock);
        if (!now.isBefore(properties.getHardExit())) {
            executeHardExit(now);
            return;
        }
        for (BvsSetup setup : List.copyOf(setups.values())) {
            try {
                cycleOnSetup(setup);
            } catch (RuntimeException e) {
                log.warn("BVS cycle failed for {} ({})", setup.getSymbol(), e.getMessage());
            }
        }
    }

    private void cycleOnSetup(BvsSetup setup) {
        String symbol = setup.getSymbol();
        List<Candle> candles = marketDataService.fetch5MinCandles(symbol, 5);
        if (candles == null || candles.isEmpty()) {
            log.warn("BVS: no 5m candles for {}", symbol);
            return;
        }
        Candle last = candles.get(candles.size() - 1);
        if (last == null || last.close() == null) {
            return;
        }
        double close = last.close().doubleValue();

        applySessionRange(
                candles, setup, properties.getSessionRangeStart(), properties.getSessionRangeEnd());
        if (setup.getState() == BvsSetupState.AWAITING_RANGE) {
            return;
        }

        BvsBias evaluated = evaluateConfluence(close, setup);
        BvsBias before = setup.getBias();
        BvsBias after = advanceBias(before, evaluated);
        if (after != before) {
            setup.setBias(after);
            if (before == BvsBias.NONE) {
                setup.setState(BvsSetupState.BIAS_ARMED);
                log.info("BVS {} bias latched {}", symbol, after);
            } else if (setup.getState() == BvsSetupState.TRIGGER_ARMED) {
                setup.setTriggerHigh(null);
                setup.setTriggerLow(null);
                setup.setTriggerArmedAt(null);
                setup.setState(BvsSetupState.BIAS_ARMED);
                log.info("BVS {} bias flipped {} — armed trigger cancelled", symbol, after);
            }
        }

        if (manageOpenPosition(setup, last, candles, close)) {
            return;
        }

        if (setup.getBias() != BvsBias.NONE
                && (setup.getState() == BvsSetupState.BIAS_ARMED
                        || setup.getState() == BvsSetupState.TRIGGER_ARMED)) {
            double[] vwapSeries = technicalAnalysisService.calculateVwapSeries(candles);
            double vwap = vwapSeries[vwapSeries.length - 1];
            double high = last.high() != null ? last.high().doubleValue() : close;
            double low = last.low() != null ? last.low().doubleValue() : close;
            Instant closeTime = last.timestamp().plusSeconds(FIVE_MINUTES_SECONDS);
            armTriggerIfEligible(
                    setup,
                    setup.getBias(),
                    close,
                    vwap,
                    high,
                    low,
                    closeTime,
                    properties.getEntryCutoff());
        }
    }

    /**
     * Stop and runner-exit checks for the symbol's open position, on the latest completed candle.
     *
     * @return true when the position was closed this cycle
     */
    private boolean manageOpenPosition(
            BvsSetup setup, Candle last, List<Candle> candles, double close) {
        BvsPosition position = openPositions.get(setup.getSymbol());
        if (position == null) {
            return false;
        }
        boolean shortPos = position.getDirection() == BvsBias.SHORT;
        double probe =
                shortPos
                        ? (last.high() != null ? last.high().doubleValue() : close)
                        : (last.low() != null ? last.low().doubleValue() : close);
        if (isStopHit(position.getDirection(), probe, position.getStopPrice())) {
            executeFullExit(
                    position,
                    position.getStopPrice(),
                    "STOP",
                    shortPos ? SignalAction.EXIT_SHORT : SignalAction.EXIT_LONG);
            return true;
        }
        if (position.isPartialBooked()) {
            double[] closes =
                    candles.stream()
                            .filter(c -> c.close() != null)
                            .mapToDouble(c -> c.close().doubleValue())
                            .toArray();
            if (closes.length >= properties.getEmaPeriod()) {
                double[] ema =
                        technicalAnalysisService.calculateEmaSeries(
                                closes, properties.getEmaPeriod());
                double emaLast = ema[ema.length - 1];
                if (isRunnerExit(position.getDirection(), close, emaLast)) {
                    executeFullExit(
                            position,
                            close,
                            "EMA10_CROSS",
                            shortPos ? SignalAction.EXIT_SHORT : SignalAction.EXIT_LONG);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 30-second tick: entry-window breaches for armed triggers and stop/target checks for open
     * positions, using fresh feed prices. Squares everything off at/after the hard exit.
     */
    public void evaluateLivePriceActions() {
        LocalTime now = LocalTime.now(clock);
        if (!now.isBefore(properties.getHardExit())) {
            executeHardExit(now);
            return;
        }
        if (feedClient == null) {
            return;
        }
        Set<String> watched = new LinkedHashSet<>(setups.keySet());
        watched.addAll(openPositions.keySet());
        if (watched.isEmpty()) {
            return;
        }
        Map<String, StockQuoteSnapshot> quotes = feedClient.snapshots(watched, QUOTE_MAX_AGE);
        if (quotes == null || quotes.isEmpty()) {
            return;
        }
        Instant nowInstant = Instant.now(clock);
        int windowMinutes = properties.getTriggerWindowMinutes();

        for (BvsSetup setup : List.copyOf(setups.values())) {
            if (setup.getState() != BvsSetupState.TRIGGER_ARMED || setup.isEntryConsumed()) {
                continue;
            }
            if (setup.getTriggerHigh() == null
                    || setup.getTriggerLow() == null
                    || setup.getTriggerArmedAt() == null
                    || setup.getBias() == BvsBias.NONE) {
                continue;
            }
            StockQuoteSnapshot quote = quotes.get(setup.getSymbol());
            if (quote == null) {
                continue;
            }
            if (!withinEntryWindow(setup.getTriggerArmedAt(), nowInstant, windowMinutes)) {
                setup.setState(BvsSetupState.BIAS_ARMED);
                setup.setEntryConsumed(true);
                log.debug("BVS {} trigger window expired", setup.getSymbol());
                continue;
            }
            if (openPositions.containsKey(setup.getSymbol())) {
                continue;
            }
            double price = quote.ltp();
            boolean breach =
                    setup.getBias() == BvsBias.LONG
                            ? price >= setup.getTriggerHigh()
                            : price <= setup.getTriggerLow();
            if (breach) {
                executeEntry(setup.getSymbol(), setup, price);
            }
        }

        for (BvsPosition position : List.copyOf(openPositions.values())) {
            StockQuoteSnapshot quote = quotes.get(position.getSymbol());
            if (quote == null) {
                continue;
            }
            double price = quote.ltp();
            if (isStopHit(position.getDirection(), price, position.getStopPrice())) {
                executeFullExit(
                        position,
                        position.getStopPrice(),
                        "STOP",
                        position.getDirection() == BvsBias.SHORT
                                ? SignalAction.EXIT_SHORT
                                : SignalAction.EXIT_LONG);
                continue;
            }
            if (!position.isPartialBooked() && targetTouched(position, price)) {
                executePartial(position, price);
            }
        }
    }

    // --- session range, confluence and trigger decisions ---

    /**
     * Derives the 09:30-10:00 session range from today's 5m candles: high/low of the candles whose
     * start time falls in {@code [rangeStart, rangeEnd)}, taking effect only once every slot in the
     * window has printed. Once complete the range is fixed — later calls are no-ops, so no
     * post-10:00 candle can ever widen it.
     *
     * @param candles5m today's 5-minute candles (any order, may be incomplete)
     * @param setup the setup to populate
     * @param rangeStart inclusive window start, IST
     * @param rangeEnd exclusive window end, IST
     */
    public static void applySessionRange(
            List<Candle> candles5m, BvsSetup setup, LocalTime rangeStart, LocalTime rangeEnd) {
        if (setup == null || setup.getState() != BvsSetupState.AWAITING_RANGE) {
            return;
        }
        if (candles5m == null || candles5m.isEmpty()) {
            return;
        }
        double highValue = -Double.MAX_VALUE;
        double lowValue = Double.MAX_VALUE;
        int count = 0;
        for (Candle candle : candles5m) {
            if (candle == null
                    || candle.timestamp() == null
                    || candle.high() == null
                    || candle.low() == null) {
                continue;
            }
            LocalTime start =
                    LocalTime.ofInstant(candle.timestamp(), NseTradingCalendarUtil.IST_ZONE);
            if (start.isBefore(rangeStart) || !start.isBefore(rangeEnd)) {
                continue;
            }
            count++;
            highValue = Math.max(highValue, candle.high().doubleValue());
            lowValue = Math.min(lowValue, candle.low().doubleValue());
        }
        int expectedSlots =
                (int) Duration.between(rangeStart, rangeEnd).toMinutes()
                        / SESSION_RANGE_SLOT_MINUTES;
        if (count < expectedSlots) {
            return;
        }
        setup.setSessionRangeHigh(highValue);
        setup.setSessionRangeLow(lowValue);
        setup.setState(BvsSetupState.AWAITING_CONFLUENCE);
        log.debug(
                "BVS range complete for {}: high={} low={}",
                setup.getSymbol(),
                highValue,
                lowValue);
    }

    /**
     * Dual-level confluence (spec rule 9): LONG above both highs, SHORT below both lows, NONE
     * otherwise or when any level is missing.
     *
     * @param close candle close price
     * @param setup setup carrying the four levels
     * @return the evaluated bias for this close
     */
    public static BvsBias evaluateConfluence(double close, BvsSetup setup) {
        if (setup == null) {
            return BvsBias.NONE;
        }
        Double rangeHigh = setup.getSessionRangeHigh();
        Double rangeLow = setup.getSessionRangeLow();
        Double pdh = setup.getPdh();
        Double pdl = setup.getPdl();
        if (rangeHigh == null || rangeLow == null || pdh == null || pdl == null) {
            return BvsBias.NONE;
        }
        if (close > Math.max(rangeHigh, pdh)) {
            return BvsBias.LONG;
        }
        if (close < Math.min(rangeLow, pdl)) {
            return BvsBias.SHORT;
        }
        return BvsBias.NONE;
    }

    /**
     * Latch rule (spec rule 10): a latched bias holds until the opposite confluence flips it; NONE
     * never clears it.
     *
     * @param current currently latched bias
     * @param evaluated bias evaluated from the latest close
     * @return the bias after applying the latch
     */
    public static BvsBias advanceBias(BvsBias current, BvsBias evaluated) {
        if (current == null) {
            return evaluated == null ? BvsBias.NONE : evaluated;
        }
        if (evaluated == null || evaluated == BvsBias.NONE || evaluated == current) {
            return current;
        }
        return evaluated;
    }

    /**
     * Trigger test (spec rule 11): a completed 5m candle closing beyond session VWAP in the bias
     * direction.
     *
     * @param bias latched bias
     * @param close candle close
     * @param vwap session VWAP at that candle
     * @return true when the candle qualifies as a trigger candle
     */
    public static boolean isVwapTrigger(BvsBias bias, double close, double vwap) {
        if (bias == BvsBias.LONG) {
            return close > vwap;
        }
        if (bias == BvsBias.SHORT) {
            return close < vwap;
        }
        return false;
    }

    /**
     * Arms the trigger window (spec rules 13/20): eligible states are BIAS_ARMED and TRIGGER_ARMED
     * (an expired window re-arms from the next trigger candle), the candle must be a VWAP cross in
     * the bias direction, and its close must not be past the entry cutoff.
     *
     * @param setup setup to arm
     * @param bias latched bias
     * @param close trigger candle close
     * @param vwap session VWAP at the trigger candle
     * @param candleHigh trigger candle high (entry level for LONG)
     * @param candleLow trigger candle low (entry level for SHORT)
     * @param candleCloseTime close time of the trigger candle — window starts here
     * @param entryCutoff no arming after this IST time
     */
    public static void armTriggerIfEligible(
            BvsSetup setup,
            BvsBias bias,
            double close,
            double vwap,
            double candleHigh,
            double candleLow,
            Instant candleCloseTime,
            LocalTime entryCutoff) {
        if (setup == null || bias == null || bias == BvsBias.NONE || candleCloseTime == null) {
            return;
        }
        BvsSetupState state = setup.getState();
        if (state != BvsSetupState.BIAS_ARMED && state != BvsSetupState.TRIGGER_ARMED) {
            return;
        }
        if (!isVwapTrigger(bias, close, vwap)) {
            return;
        }
        LocalTime closeTime = LocalTime.ofInstant(candleCloseTime, NseTradingCalendarUtil.IST_ZONE);
        if (closeTime.isAfter(entryCutoff)) {
            return;
        }
        setup.setTriggerHigh(candleHigh);
        setup.setTriggerLow(candleLow);
        setup.setTriggerArmedAt(candleCloseTime);
        setup.setEntryConsumed(false);
        setup.setState(BvsSetupState.TRIGGER_ARMED);
    }

    // --- trades ---

    /**
     * Enters a futures position on the trigger-level breach: R is the distance to the opposite
     * trigger extreme, which is also the stop (spec rule 14). Blocked after the entry cutoff and
     * while a position is already open for the symbol.
     *
     * @return the opened position, or null when the entry was skipped
     */
    public synchronized BvsPosition executeEntry(String symbol, BvsSetup setup, double price) {
        LocalTime now = LocalTime.now(clock);
        if (!now.isBefore(properties.getEntryCutoff())) {
            log.debug("BVS skip entry for {} — after entry cutoff", symbol);
            return null;
        }
        if (openPositions.containsKey(symbol)) {
            return null;
        }
        if (setup.getBias() == BvsBias.NONE
                || setup.getTriggerHigh() == null
                || setup.getTriggerLow() == null) {
            return null;
        }
        BvsBias direction = setup.getBias();
        double triggerExtreme =
                direction == BvsBias.LONG ? setup.getTriggerLow() : setup.getTriggerHigh();
        double r = rFor(direction, price, triggerExtreme);
        if (r <= 0) {
            log.warn("BVS skip entry for {}: non-positive R ({})", symbol, r);
            return null;
        }
        int lotSize = Math.max(1, StockFnoRegistry.getLotSize(symbol));
        int lots = sizingLots(r, lotSize);
        int quantity = lots * lotSize;

        ShoonyaMarketDataService.FuturesContract contract = resolveContract(symbol);

        BvsPosition position = new BvsPosition();
        position.setSymbol(symbol);
        position.setBrokerTradingSymbol(contract != null ? contract.tsym() : symbol);
        position.setToken(contract != null ? contract.token() : null);
        position.setDirection(direction);
        position.setEntryPrice(price);
        position.setStopPrice(triggerExtreme);
        position.setRValue(r);
        position.setTargetPrice(targetFor(direction, price, r, properties.getRrPartial()));
        position.setTotalQuantity(quantity);
        position.setRemainingQuantity(quantity);
        position.setPartialBooked(false);
        position.setEntryTime(Instant.now(clock));
        position.setTradeId("BVS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        openPositions.put(symbol, position);

        publishSignal(
                symbol,
                position.getBrokerTradingSymbol(),
                direction == BvsBias.LONG ? SignalAction.ENTRY_LONG : SignalAction.ENTRY_SHORT,
                BigDecimal.valueOf(price),
                BigDecimal.valueOf(triggerExtreme),
                BigDecimal.valueOf(position.getTargetPrice()),
                quantity,
                "BVS trigger break",
                sideMeta(direction));
        notifyTelegram(
                String.format(
                        "BVS | ENTRY %s %s @ %.2f (SL %.2f, target %.2f, qty %d)",
                        direction,
                        symbol,
                        price,
                        triggerExtreme,
                        position.getTargetPrice(),
                        quantity));
        setup.setEntryConsumed(true);
        setup.setState(BvsSetupState.BIAS_ARMED);
        return position;
    }

    private int sizingLots(double r, int lotSize) {
        if (!properties.isDynamicPositionSizing()) {
            return Math.max(1, properties.getLots());
        }
        double maxFromRisk = riskPerTradeAmount() / (r * lotSize);
        double capped = Math.min(maxFromRisk, properties.getLots() * 2.0);
        return (int) Math.max(1, Math.floor(capped));
    }

    private ShoonyaMarketDataService.FuturesContract resolveContract(String symbol) {
        try {
            return contractCache.computeIfAbsent(symbol, marketDataService::resolveFuturesContract);
        } catch (RuntimeException e) {
            log.warn("BVS: futures contract resolution failed for {} ({})", symbol, e.getMessage());
            return null;
        }
    }

    /** Risk budget in rupees: paper capital x risk percent / 100 (mirrors LVR). */
    public double riskPerTradeAmount() {
        return properties.getPaperCapital() * properties.getRiskPerTradePercent() / 100.0;
    }

    /**
     * Books 50% (rounded up) at the 2.5R target and moves the stop to breakeven for the runner
     * (spec rules 16/18): PARTIAL_EXIT first, then UPDATE_STOP_LOSS at entry price.
     */
    private synchronized void executePartial(BvsPosition position, double price) {
        position.setPartialBooked(true);
        int partialQuantity = (position.getTotalQuantity() + 1) / 2;
        int remaining = position.getTotalQuantity() - partialQuantity;
        position.setRemainingQuantity(remaining);
        position.setStopPrice(position.getEntryPrice());

        boolean shortPos = position.getDirection() == BvsBias.SHORT;
        Map<String, Object> partialMeta = sideMeta(position.getDirection());
        partialMeta.put("partialExitRatio", 0.5);
        partialMeta.put("remainingQuantity", remaining);
        publishSignal(
                position.getSymbol(),
                position.getBrokerTradingSymbol(),
                shortPos ? SignalAction.PARTIAL_EXIT_SHORT : SignalAction.PARTIAL_EXIT_LONG,
                BigDecimal.valueOf(price),
                null,
                null,
                partialQuantity,
                "BVS 2.5R partial",
                partialMeta);
        publishSignal(
                position.getSymbol(),
                position.getBrokerTradingSymbol(),
                SignalAction.UPDATE_STOP_LOSS,
                BigDecimal.valueOf(price),
                BigDecimal.valueOf(position.getEntryPrice()),
                null,
                remaining,
                "BVS breakeven stop",
                sideMeta(position.getDirection()));
        notifyTelegram(
                String.format(
                        "BVS | PARTIAL %s @ %.2f — 50%% booked, stop moved to breakeven %.2f",
                        position.getSymbol(), price, position.getEntryPrice()));
    }

    private synchronized void executeFullExit(
            BvsPosition position, double price, String reason, SignalAction action) {
        int quantity = position.getRemainingQuantity();
        openPositions.remove(position.getSymbol());
        tradeHistory.add(position);
        publishSignal(
                position.getSymbol(),
                position.getBrokerTradingSymbol(),
                action,
                BigDecimal.valueOf(price),
                null,
                null,
                quantity,
                reason,
                sideMeta(position.getDirection()));
        notifyTelegram(
                String.format(
                        "BVS | EXIT %s @ %.2f (%s, qty %d)",
                        position.getSymbol(), price, reason, quantity));
    }

    /**
     * Squares off every open position at/after the hard exit (15:00).
     *
     * @param now current IST time; before the hard exit this is a no-op
     */
    public synchronized void executeHardExit(LocalTime now) {
        if (now.isBefore(properties.getHardExit())) {
            return;
        }
        if (openPositions.isEmpty()) {
            return;
        }
        Map<String, StockQuoteSnapshot> quotes =
                feedClient == null
                        ? Map.of()
                        : feedClient.snapshots(openPositions.keySet(), QUOTE_MAX_AGE);
        for (BvsPosition position : List.copyOf(openPositions.values())) {
            StockQuoteSnapshot quote = quotes.get(position.getSymbol());
            double price = quote != null ? quote.ltp() : position.getEntryPrice();
            executeFullExit(position, price, "HARD_EXIT_1500", SignalAction.SQUARE_OFF);
        }
    }

    // --- pure decisions ---

    /**
     * R is the distance from entry to the opposite extreme of the trigger candle (spec rule 14).
     *
     * @param direction trade direction
     * @param entry entry price at the breach
     * @param triggerExtreme the trigger candle's opposite extreme (stop level)
     * @return positive R in price units
     */
    public static double rFor(BvsBias direction, double entry, double triggerExtreme) {
        if (direction == BvsBias.SHORT) {
            return Math.abs(triggerExtreme - entry);
        }
        return Math.abs(entry - triggerExtreme);
    }

    /**
     * Partial target price at entry + direction x rrPartial x R (2.5R by default).
     *
     * @param direction trade direction
     * @param entry entry price
     * @param r R in price units
     * @param rrPartial configured reward-to-risk for the partial
     * @return the price at which 50% is booked
     */
    public static double targetFor(BvsBias direction, double entry, double r, double rrPartial) {
        double offset = r * rrPartial;
        return direction == BvsBias.SHORT ? entry - offset : entry + offset;
    }

    /**
     * Runner exit: the 5m candle closes across the 10 EMA against the bias (spec rule 17).
     *
     * @param direction trade direction
     * @param candleClose candle close
     * @param ema10 EMA value on that candle
     * @return true when the runner must exit
     */
    public static boolean isRunnerExit(BvsBias direction, double candleClose, double ema10) {
        if (direction == BvsBias.LONG) {
            return candleClose < ema10;
        }
        if (direction == BvsBias.SHORT) {
            return candleClose > ema10;
        }
        return false;
    }

    /**
     * Stop check against a live price or candle extreme.
     *
     * @param direction trade direction
     * @param price observed price
     * @param stop stop level
     * @return true when the stop is hit
     */
    public static boolean isStopHit(BvsBias direction, double price, double stop) {
        if (direction == BvsBias.LONG) {
            return price <= stop;
        }
        if (direction == BvsBias.SHORT) {
            return price >= stop;
        }
        return false;
    }

    /**
     * Entry-window check: at most {@code windowMinutes} elapsed since the trigger candle close
     * (spec rule 13).
     *
     * @param triggerArmedAt trigger candle close time
     * @param now current instant
     * @param windowMinutes window length in minutes
     * @return true while the window is still open
     */
    public static boolean withinEntryWindow(
            Instant triggerArmedAt, Instant now, int windowMinutes) {
        if (triggerArmedAt == null || now == null) {
            return false;
        }
        return Duration.between(triggerArmedAt, now).compareTo(Duration.ofMinutes(windowMinutes))
                <= 0;
    }

    private static boolean targetTouched(BvsPosition position, double price) {
        if (position.getDirection() == BvsBias.LONG) {
            return price >= position.getTargetPrice();
        }
        if (position.getDirection() == BvsBias.SHORT) {
            return price <= position.getTargetPrice();
        }
        return false;
    }

    // --- signal / telegram plumbing (mirrors LVR) ---

    private boolean publishSignal(
            String underlying,
            String contract,
            SignalAction action,
            BigDecimal price,
            BigDecimal stopLoss,
            BigDecimal target,
            int quantity,
            String reason,
            Map<String, Object> metadata) {
        if (signalPublisher == null || action == null) {
            return true;
        }
        TradeSignal signal =
                TradeSignal.of(
                        STRATEGY_ID,
                        underlying,
                        contract != null ? contract : underlying,
                        action,
                        price != null ? price : BigDecimal.ZERO,
                        stopLoss,
                        target,
                        quantity,
                        reason,
                        metadata);
        return signalPublisher.publish(signal);
    }

    private static Map<String, Object> sideMeta(BvsBias direction) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("positionSide", direction.name());
        meta.put("exchange", "NFO");
        return meta;
    }

    private void notifyTelegram(String message) {
        if (properties.isTelegramAlerts() && telegramService != null) {
            telegramService.sendTextMessage(message);
        }
    }
}
