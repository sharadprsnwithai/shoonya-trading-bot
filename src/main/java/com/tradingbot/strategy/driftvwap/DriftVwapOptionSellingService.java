package com.tradingbot.strategy.driftvwap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.bus.SignalPublisher;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.order.OrderType;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import com.tradingbot.strategy.driftvwap.config.DriftVwapProperties;
import com.tradingbot.strategy.driftvwap.model.DriftDirection;
import com.tradingbot.strategy.driftvwap.model.DriftVwapPosition;
import com.tradingbot.strategy.driftvwap.model.DriftVwapTrendState;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.CandleResamplingUtil;
import com.tradingbot.util.StockFnoRegistry;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Core Strategy Engine for Matio Kanti's Drift VWAP ATM Option Selling Framework on NIFTY 50.
 *
 * <p>1. 09:15 - 10:15 IST: Settlement window (VWAP anchor initialized, no trades).
 * <p>2. 10:15 - 14:55 IST: 15m Drift filter (Close vs VWAP + Rising/Falling VWAP + 1-hr momentum)
 *      + 5m Pullback trigger (1st opposite candle close -> Market Sell ATM option at next bar open).
 * <p>3. Exits: +70% Premium Decay Target, -60% Premium Expansion SL, 15:10 IST EOD Hard Exit.
 */
@Service
public class DriftVwapOptionSellingService {

    private static final Logger log = LoggerFactory.getLogger(DriftVwapOptionSellingService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final String STRATEGY_ID = "DRIFT_VWAP_OPTION_SELLING";

    private final ShoonyaMarketDataService marketDataService;
    private final ShoonyaOptionChainService optionChainService;
    private final TechnicalAnalysisService taService;
    private final SignalPublisher signalPublisher;
    private final TelegramService telegramService;
    private final DriftVwapProperties properties;
    private final ObjectMapper objectMapper =
            new ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private Clock clock = Clock.system(IST);
    private final AtomicBoolean isCycleRunning = new AtomicBoolean(false);
    private volatile DriftVwapPosition openPosition = null;
    private final List<DriftVwapPosition> tradeHistory = new CopyOnWriteArrayList<>();
    private final AtomicInteger todayTradesCount = new AtomicInteger(0);
    private final AtomicInteger todayLossCount = new AtomicInteger(0);
    private final AtomicInteger tradeCounter = new AtomicInteger(1);
    private volatile DriftVwapTrendState latestTrendState = DriftVwapTrendState.neutral();
    private volatile LocalDate lastSessionDate = null;

    @Autowired
    public DriftVwapOptionSellingService(
            @Autowired(required = false) ShoonyaMarketDataService marketDataService,
            @Autowired(required = false) ShoonyaOptionChainService optionChainService,
            TechnicalAnalysisService taService,
            @Autowired(required = false) SignalPublisher signalPublisher,
            @Autowired(required = false) TelegramService telegramService,
            DriftVwapProperties properties) {
        this.marketDataService = marketDataService;
        this.optionChainService = optionChainService;
        this.taService = taService != null ? taService : new TechnicalAnalysisService();
        this.signalPublisher = signalPublisher;
        this.telegramService = telegramService;
        this.properties = properties != null ? properties : new DriftVwapProperties();
    }

    @PostConstruct
    public void init() {
        log.info("[DRIFT-VWAP] Initialized Drift VWAP ATM Option Selling Service.");
    }

    /** Evaluates 15-minute macro trend drift relative to anchored VWAP and 1-hour momentum. */
    public DriftVwapTrendState evaluate15mDrift(List<Candle> candles15m) {
        if (candles15m == null || candles15m.size() < 2) {
            return DriftVwapTrendState.neutral();
        }

        double[] vwapSeries = taService.calculateVwapSeries(candles15m);
        if (vwapSeries.length < 2) {
            return DriftVwapTrendState.neutral();
        }

        int lastIdx = candles15m.size() - 1;
        Candle latestCandle = candles15m.get(lastIdx);
        BigDecimal close15m = latestCandle.close();
        BigDecimal vwap15m = BigDecimal.valueOf(vwapSeries[lastIdx]).setScale(2, RoundingMode.HALF_UP);
        BigDecimal prevVwap15m = BigDecimal.valueOf(vwapSeries[lastIdx - 1]).setScale(2, RoundingMode.HALF_UP);

        // 1-Hour Momentum: 4 fifteen-minute bars back
        double momentum1hrPct = 0.0;
        if (candles15m.size() >= 5) {
            BigDecimal close4BarsAgo = candles15m.get(lastIdx - 4).close();
            if (close4BarsAgo.compareTo(BigDecimal.ZERO) > 0) {
                momentum1hrPct =
                        close15m.subtract(close4BarsAgo)
                                .divide(close4BarsAgo, 4, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(100.0))
                                .doubleValue();
            }
        }

        double minMom = properties.getMinMomentumPct();
        DriftDirection dir = DriftDirection.NEUTRAL;

        if (close15m.compareTo(vwap15m) > 0 && vwap15m.compareTo(prevVwap15m) > 0 && momentum1hrPct >= minMom) {
            dir = DriftDirection.BULLISH_DRIFT;
        } else if (close15m.compareTo(vwap15m) < 0 && vwap15m.compareTo(prevVwap15m) < 0 && momentum1hrPct <= -minMom) {
            dir = DriftDirection.BEARISH_DRIFT;
        }

        this.latestTrendState =
                new DriftVwapTrendState(
                        close15m,
                        vwap15m,
                        prevVwap15m,
                        momentum1hrPct,
                        dir,
                        Instant.now(clock));

        return this.latestTrendState;
    }

    /** Checks if a closed 5-minute candle represents a valid pullback bar towards VWAP. */
    public boolean check5mPullbackTrigger(DriftDirection drift, Candle candle5m) {
        if (drift == null || candle5m == null || drift == DriftDirection.NEUTRAL) {
            return false;
        }
        if (drift == DriftDirection.BULLISH_DRIFT) {
            return candle5m.isRed(); // Red candle pullback during uptrend
        } else if (drift == DriftDirection.BEARISH_DRIFT) {
            return candle5m.isGreen(); // Green candle pullback during downtrend
        }
        return false;
    }

    /** Executes Option Selling entry (Sell ATM Put on Bullish Drift, Sell ATM Call on Bearish Drift). */
    public synchronized DriftVwapPosition executeOptionSellingEntry(BigDecimal spotPrice, DriftDirection direction) {
        if (!properties.isEnabled() || spotPrice == null || direction == DriftDirection.NEUTRAL) {
            return null;
        }
        if (openPosition != null && !openPosition.isClosed()) {
            log.info("[DRIFT-VWAP] Position already open. Max 1 concurrent trade allowed.");
            return null;
        }
        if (todayTradesCount.get() >= properties.getMaxDailyTrades()) {
            log.info("[DRIFT-VWAP] Max daily trades ({}) reached. Standing down.", properties.getMaxDailyTrades());
            return null;
        }
        if (todayLossCount.get() >= properties.getMaxDailyLosses()) {
            log.warn("[DRIFT-VWAP] Daily 2-loss circuit breaker active ({}/{} losses). Halting new entries.",
                    todayLossCount.get(), properties.getMaxDailyLosses());
            return null;
        }

        String symbol = properties.getUnderlying();
        BigDecimal strike = roundStrike(spotPrice, 50);
        String optType = (direction == DriftDirection.BULLISH_DRIFT) ? "PE" : "CE";
        int lotSize = StockFnoRegistry.getLotSize(symbol);
        int lots = properties.getLots() > 0 ? properties.getLots() : 2;
        int totalQty = lots * lotSize;

        LocalDate expiry = StockFnoRegistry.calculateTargetExpiry(symbol, LocalDate.now(clock), true, 1);
        String contractSymbol = StockFnoRegistry.formatTradingSymbol(symbol, expiry, strike, optType, true);

        double livePrem = fetchOptionLtp(symbol, optType, strike);
        if (livePrem <= 0.0) {
            log.warn("[DRIFT-VWAP] Live option LTP unavailable for {} {} {}. Skipping entry.", symbol, optType, strike);
            return null;
        }

        BigDecimal entryPremium = BigDecimal.valueOf(livePrem).setScale(2, RoundingMode.HALF_UP);
        // Target: 70% decay -> targetPrem = entryPrem * (1 - 0.70) = entryPrem * 0.30
        BigDecimal targetPrem =
                roundToTick(entryPremium.multiply(BigDecimal.valueOf(1.0 - (properties.getTargetDecayPct() / 100.0))));
        // Stop Loss: 60% expansion -> slPrem = entryPrem * (1 + 0.60) = entryPrem * 1.60
        BigDecimal slPrem =
                roundToTick(entryPremium.multiply(BigDecimal.valueOf(1.0 + (properties.getSlExpansionPct() / 100.0))));

        BigDecimal plannedRisk =
                slPrem.subtract(entryPremium)
                        .multiply(BigDecimal.valueOf(totalQty))
                        .setScale(2, RoundingMode.HALF_UP);

        String tradeId = "DVWAP-" + tradeCounter.getAndIncrement();
        DriftVwapPosition pos =
                new DriftVwapPosition(
                        tradeId,
                        symbol,
                        optType,
                        contractSymbol,
                        strike,
                        lots,
                        lotSize,
                        direction,
                        spotPrice,
                        entryPremium,
                        slPrem,
                        targetPrem,
                        totalQty,
                        plannedRisk,
                        Instant.now(clock));
        pos.setBrokerTradingSymbol(contractSymbol);

        this.openPosition = pos;
        this.todayTradesCount.incrementAndGet();

        // Publish Signal to Event Bus for Broker Execution (SELL ATM Option)
        publishSignal(
                symbol,
                contractSymbol,
                direction == DriftDirection.BULLISH_DRIFT ? SignalAction.ENTRY_LONG : SignalAction.ENTRY_SHORT,
                entryPremium,
                slPrem,
                targetPrem,
                totalQty,
                "Drift VWAP ATM Option Selling Entry",
                Map.of(
                        "instrumentType", "OPTION",
                        "optionType", optType,
                        "strike", strike,
                        "tradeId", tradeId,
                        "referencePrice", entryPremium,
                        "brokerStopLossPrice", slPrem,
                        "brokerTargetPrice", targetPrem,
                        "exchange", "NFO"));

        log.info(
                "[DRIFT-VWAP] OPTION SELLING ENTRY: TradeId={} | Sold {} ATM {} | EntryPrem=₹{} | TargetPrem=₹{} | SLPrem=₹{} | Qty={}",
                tradeId, optType, strike, entryPremium, targetPrem, slPrem, totalQty);

        if (properties.isTelegramAlerts() && telegramService != null) {
            BigDecimal maxProfit =
                    entryPremium.subtract(targetPrem)
                            .multiply(BigDecimal.valueOf(totalQty))
                            .setScale(2, RoundingMode.HALF_UP);
            telegramService.sendTextMessage(
                    String.format(
                            Locale.US,
                            "🚀 *Drift VWAP Option Selling Entry [PAPER]*\n"
                                    + "• Underlying: *%s* (Spot: `₹%.2f`)\n"
                                    + "• Drift Bias: *%s*\n"
                                    + "• Action: *SELL %s* (ATM %d)\n"
                                    + "• Entry Premium: `₹%.2f` (%d lots / %d qty)\n"
                                    + "• Target Premium: `₹%.2f` (70%% Decay | Potential: `+₹%.2f`)\n"
                                    + "• Stop Loss Premium: `₹%.2f` (+60%% Expansion | Max Risk: `-₹%.2f`)",
                            symbol,
                            spotPrice.doubleValue(),
                            direction,
                            contractSymbol,
                            strike.intValue(),
                            entryPremium.doubleValue(),
                            lots,
                            totalQty,
                            targetPrem.doubleValue(),
                            maxProfit.doubleValue(),
                            slPrem.doubleValue(),
                            plannedRisk.doubleValue()));
        }

        return pos;
    }

    /** 30-Second Live Tick Monitor: Evaluates Target decay, SL expansion, and 15:10 Hard Exit. */
    public synchronized void evaluateLivePriceActions() {
        if (!properties.isEnabled() || openPosition == null || openPosition.isClosed()) {
            return;
        }

        LocalTime nowTime = LocalTime.now(clock);
        DriftVwapPosition pos = openPosition;
        String symbol = pos.getUnderlyingSymbol();

        double livePrem = fetchOptionLtp(symbol, pos.getOptionType(), pos.getStrikePrice());
        BigDecimal currentPremium =
                (livePrem > 0.0) ? BigDecimal.valueOf(livePrem).setScale(2, RoundingMode.HALF_UP) : pos.getEntryPremium();

        // 1. Hard EOD Square-Off at 15:10 IST
        if (!nowTime.isBefore(properties.getHardExitTime())) {
            executeHardExit();
            return;
        }

        // 2. Check Stop Loss Hit (Premium Expands to or above SL)
        if (currentPremium.compareTo(pos.getSlPremium()) >= 0) {
            pos.close(pos.getSlPremium(), "OPTION_SL_EXPANSION", Instant.now(clock));
            tradeHistory.add(pos);
            todayLossCount.incrementAndGet();

            publishSignal(
                    symbol,
                    pos.getBrokerTradingSymbol(),
                    pos.getDirection() == DriftDirection.BULLISH_DRIFT ? SignalAction.EXIT_LONG : SignalAction.EXIT_SHORT,
                    pos.getSlPremium(),
                    null,
                    null,
                    pos.getQuantity(),
                    "OPTION_SL_EXPANSION",
                    Map.of("instrumentType", "OPTION", "exchange", "NFO"));

            log.warn("[DRIFT-VWAP] SL Hit for {}: Closed at SLPrem=₹{} | Loss=₹{}",
                    pos.getContractSymbol(), pos.getSlPremium(), pos.getRealizedPnl());

            if (properties.isTelegramAlerts() && telegramService != null) {
                BigDecimal lossPts = pos.getSlPremium().subtract(pos.getEntryPremium());
                telegramService.sendTextMessage(
                        String.format(
                                Locale.US,
                                "🛑 *Drift VWAP Stop Loss Hit [PAPER]*\n"
                                        + "• Contract: `%s`\n"
                                        + "• Entry Premium: `₹%.2f`\n"
                                        + "• Exit Premium: `₹%.2f` (+60%% Expansion)\n"
                                        + "• Loss Points: `-%.2f pts`\n"
                                        + "• Realized P&L: `-₹%.2f`\n"
                                        + "• Reason: Option SL Expansion",
                                pos.getContractSymbol(),
                                pos.getEntryPremium().doubleValue(),
                                pos.getSlPremium().doubleValue(),
                                lossPts.doubleValue(),
                                pos.getRealizedPnl().abs().doubleValue()));
            }
            return;
        }

        // 3. Check Target Hit (Premium Decays to or below Target)
        if (currentPremium.compareTo(pos.getTargetPremium()) <= 0) {
            pos.close(pos.getTargetPremium(), "OPTION_TARGET_DECAY", Instant.now(clock));
            tradeHistory.add(pos);

            publishSignal(
                    symbol,
                    pos.getBrokerTradingSymbol(),
                    pos.getDirection() == DriftDirection.BULLISH_DRIFT ? SignalAction.EXIT_LONG : SignalAction.EXIT_SHORT,
                    pos.getTargetPremium(),
                    null,
                    null,
                    pos.getQuantity(),
                    "OPTION_TARGET_DECAY",
                    Map.of("instrumentType", "OPTION", "exchange", "NFO"));

            log.info("[DRIFT-VWAP] Target Reached for {}: Closed at TargetPrem=₹{} | Profit=₹{}",
                    pos.getContractSymbol(), pos.getTargetPremium(), pos.getRealizedPnl());

            if (properties.isTelegramAlerts() && telegramService != null) {
                BigDecimal gainPts = pos.getEntryPremium().subtract(pos.getTargetPremium());
                telegramService.sendTextMessage(
                        String.format(
                                Locale.US,
                                "🎯 *Drift VWAP Target Reached [PAPER]*\n"
                                        + "• Contract: `%s`\n"
                                        + "• Entry Premium: `₹%.2f`\n"
                                        + "• Exit Premium: `₹%.2f` (70%% Decay)\n"
                                        + "• Captured Points: `+%.2f pts`\n"
                                        + "• Realized P&L: `+₹%.2f`\n"
                                        + "• Reason: Option Target Decay",
                                pos.getContractSymbol(),
                                pos.getEntryPremium().doubleValue(),
                                pos.getTargetPremium().doubleValue(),
                                gainPts.doubleValue(),
                                pos.getRealizedPnl().doubleValue()));
            }
        }
    }

    /** Executes complete 15:10 IST EOD hard exit. */
    public synchronized void executeHardExit() {
        if (openPosition == null || openPosition.isClosed()) {
            return;
        }
        DriftVwapPosition pos = openPosition;
        double livePrem = fetchOptionLtp(pos.getUnderlyingSymbol(), pos.getOptionType(), pos.getStrikePrice());
        BigDecimal exitPrem =
                (livePrem > 0.0) ? BigDecimal.valueOf(livePrem).setScale(2, RoundingMode.HALF_UP) : pos.getEntryPremium();

        pos.close(exitPrem, "15:10_THETA_EOD_EXIT", Instant.now(clock));
        tradeHistory.add(pos);
        if (pos.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0) {
            todayLossCount.incrementAndGet();
        }

        publishSignal(
                pos.getUnderlyingSymbol(),
                pos.getBrokerTradingSymbol(),
                pos.getDirection() == DriftDirection.BULLISH_DRIFT ? SignalAction.EXIT_LONG : SignalAction.EXIT_SHORT,
                exitPrem,
                null,
                null,
                pos.getQuantity(),
                "15:10_THETA_EOD_EXIT",
                Map.of("instrumentType", "OPTION", "exchange", "NFO"));

        log.info("[DRIFT-VWAP] 15:10 EOD Exit executed for {}: ExitPrem=₹{} | Realized PnL=₹{}",
                pos.getContractSymbol(), exitPrem, pos.getRealizedPnl());

        if (properties.isTelegramAlerts() && telegramService != null) {
            BigDecimal points = pos.getEntryPremium().subtract(exitPrem);
            telegramService.sendTextMessage(
                    String.format(
                            Locale.US,
                            "🏁 *Drift VWAP 15:10 EOD Exit [PAPER]*\n"
                                    + "• Contract: `%s`\n"
                                    + "• Entry Premium: `₹%.2f`\n"
                                    + "• Exit Premium: `₹%.2f`\n"
                                    + "• Points: `%+.2f pts`\n"
                                    + "• Realized P&L: `%+₹%.2f`\n"
                                    + "• Reason: Daily Market Close (Theta Captured)",
                            pos.getContractSymbol(),
                            pos.getEntryPremium().doubleValue(),
                            exitPrem.doubleValue(),
                            points.doubleValue(),
                            pos.getRealizedPnl().doubleValue()));
        }
    }

    /** 5-Minute Strategy Cycle: Evaluates 15m drift and triggers 5m pullback entries. */
    public void runCycle() {
        if (!properties.isEnabled() || marketDataService == null) return;
        if (!isCycleRunning.compareAndSet(false, true)) {
            log.warn("[DRIFT-VWAP] Previous 5m cycle still executing. Skipping concurrent run.");
            return;
        }

        try {
            LocalDate today = LocalDate.now(clock);
            if (lastSessionDate != null && !today.equals(lastSessionDate)) {
                resetDaily();
            }
            this.lastSessionDate = today;

            LocalTime nowTime = LocalTime.now(clock);
            if (!nowTime.isBefore(properties.getHardExitTime())) {
                executeHardExit();
                return;
            }

            // Settlement window 09:15 - 10:15
            if (nowTime.isBefore(properties.getEntryWindowStart())) {
                log.debug("[DRIFT-VWAP] Settlement window (09:15 - 10:15 IST). Standing by.");
                return;
            }

            // Past 14:55 cutoff -> No new entries
            if (!nowTime.isBefore(properties.getEntryWindowCutoff())) {
                log.debug("[DRIFT-VWAP] Past 14:55 cutoff. Managing open positions only.");
                return;
            }

            String symbol = properties.getUnderlying();
            List<Candle> raw5m = marketDataService.fetch5MinCandles(symbol, 2);
            if (raw5m == null || raw5m.isEmpty()) return;

            Instant nowInst = Instant.now(clock);
            List<Candle> today5m =
                    raw5m.stream()
                            .filter(c -> c.timestamp() != null && LocalDate.ofInstant(c.timestamp(), IST).equals(today))
                            .filter(c -> c.timestamp().plusSeconds(300).compareTo(nowInst) <= 0)
                            .toList();
            if (today5m.size() < 12) return; // At least 12 closed 5m bars (= 1 hour of data)

            List<Candle> candles15m = CandleResamplingUtil.resample5MinTo15Min(today5m);
            DriftVwapTrendState trend = evaluate15mDrift(candles15m);

            if (openPosition == null || openPosition.isClosed()) {
                Candle last5m = today5m.get(today5m.size() - 1);
                boolean triggered = check5mPullbackTrigger(trend.direction(), last5m);
                if (triggered) {
                    double spotLtp = last5m.close().doubleValue();
                    executeOptionSellingEntry(BigDecimal.valueOf(spotLtp), trend.direction());
                }
            }
        } catch (Exception e) {
            log.error("[DRIFT-VWAP] Error in 5m cycle: {}", e.getMessage(), e);
        } finally {
            isCycleRunning.set(false);
        }
    }

    public synchronized void resetDaily() {
        if (openPosition != null && !openPosition.isClosed()) {
            executeHardExit();
        }
        openPosition = null;
        tradeHistory.clear();
        todayTradesCount.set(0);
        todayLossCount.set(0);
        latestTrendState = DriftVwapTrendState.neutral();
        lastSessionDate = LocalDate.now(clock);
        log.info("[DRIFT-VWAP] Daily session state reset complete.");
    }

    private double fetchOptionLtp(String symbol, String optionType, BigDecimal strike) {
        if (optionChainService != null) {
            try {
                var chain = optionChainService.getIndexOptionChain(symbol, strike, 2, true);
                if (chain != null && chain.strikes() != null) {
                    for (var s : chain.strikes()) {
                        if (s.strikePrice() != null && s.strikePrice().compareTo(strike) == 0) {
                            var contract = "PE".equalsIgnoreCase(optionType) ? s.put() : s.call();
                            if (contract != null && contract.ltp() != null && contract.ltp().doubleValue() > 0) {
                                return contract.ltp().doubleValue();
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        try {
            if (marketDataService != null) {
                LocalDate expiry = StockFnoRegistry.calculateTargetExpiry(symbol, LocalDate.now(clock), true, 1);
                String tsym = StockFnoRegistry.formatTradingSymbol(symbol, expiry, strike, optionType, true);
                String tok = marketDataService.resolveToken(tsym);
                if (tok != null && !tok.isBlank()) {
                    JsonNode q = marketDataService.fetchQuote("NFO", tok);
                    if (q != null && q.has("lp")) {
                        return q.get("lp").asDouble(0.0);
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0.0;
    }

    private void publishSignal(
            String underlying,
            String contract,
            SignalAction action,
            BigDecimal price,
            BigDecimal sl,
            BigDecimal target,
            int quantity,
            String reason,
            Map<String, Object> metadata) {
        if (signalPublisher != null && action != null) {
            TradeSignal signal =
                    TradeSignal.of(
                            STRATEGY_ID,
                            underlying,
                            contract != null ? contract : underlying,
                            action,
                            price != null ? price : BigDecimal.ZERO,
                            sl,
                            target,
                            quantity,
                            reason,
                            metadata);
            signalPublisher.publish(signal);
        }
    }

    public static BigDecimal roundStrike(BigDecimal spotPrice, int step) {
        if (spotPrice == null || step <= 0) return BigDecimal.valueOf(25000);
        long rounded = Math.round(spotPrice.doubleValue() / step) * step;
        return BigDecimal.valueOf(rounded);
    }

    public static BigDecimal roundToTick(BigDecimal price) {
        if (price == null) return BigDecimal.ZERO;
        return price.divide(BigDecimal.valueOf(0.05), 0, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(0.05))
                .setScale(2, RoundingMode.HALF_UP);
    }

    // Getters and Setters
    public DriftVwapPosition getOpenPosition() {
        return openPosition;
    }

    public List<DriftVwapPosition> getTradeHistory() {
        return Collections.unmodifiableList(tradeHistory);
    }

    public DriftVwapTrendState getLatestTrendState() {
        return latestTrendState;
    }

    public AtomicInteger getTodayTradesCount() {
        return todayTradesCount;
    }

    public AtomicInteger getTodayLossCount() {
        return todayLossCount;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
