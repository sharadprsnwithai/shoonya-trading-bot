package com.tradingbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.strategy.OhlvDirection;
import com.tradingbot.model.strategy.OhlvPaperPosition;
import com.tradingbot.model.strategy.OhlvSetup;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.Nifty100Registry;
import com.tradingbot.util.StockFnoRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * OHL (Open=High / Open=Low) + VWAP Crossover Paper-Trading Strategy over the NIFTY 100 universe.
 *
 * <p>Daily lifecycle (IST):
 *
 * <pre>
 * 09:15:00  Daily reset.
 * 09:31:10  Morning scan: for each NIFTY 100 symbol, build the first 15-min candle (09:15-09:30);
 *           qualify if open is within tolerance of the candle high (open=high -> BEARISH/PE) or
 *           low (open=low -> BULLISH/CE). Enforce the volume filter: the 09:15-09:16 one-minute
 *           candle volume must exceed {@code volumeMultiplier} x SMA20 of the previous day's last
 *           20 one-minute volumes. Only F&amp;O-eligible symbols (verified via NFO searchScrip)
 *           are admitted. Qualifying symbols are broadcast to Telegram.
 * 09:35-14:55 Every 5 minutes: invalidate a setup if a 5-min candle breaks the marked high
 *           (BEARISH) or marked low (BULLISH). Compute session-anchored VWAP over today's 5-min
 *           candles; enter when the latest 5-min candle straddles VWAP in the setup's direction
 *           (BEARISH: high>=VWAP &amp;&amp; low&lt;=VWAP, close on low side; BULLISH: high>=VWAP
 *           &amp;&amp; low&lt;=VWAP, close on high side). Open a paper ATM option position and
 *           alert Telegram.
 * 15:00:10  Mandatory square-off of every open position (JIT option premium), Telegram exit alert.
 * </pre>
 *
 * <p>Exits during the day: a 5-min candle closes on the opposite side of VWAP (PE exits when price
 * closes above VWAP; CE exits when price closes below VWAP).
 */
@Service
public class OhlvVwapStrategyService {

    private static final Logger log = LoggerFactory.getLogger(OhlvVwapStrategyService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public static final LocalTime TIME_MARKET_OPEN = LocalTime.of(9, 15);
    public static final LocalTime TIME_SCAN = LocalTime.of(9, 31);
    public static final LocalTime TIME_FIRST_MONITOR = LocalTime.of(9, 35);
    public static final LocalTime TIME_MONITOR_END = LocalTime.of(14, 55);
    public static final LocalTime TIME_SQUARE_OFF = LocalTime.of(15, 0);

    private static final LocalTime SESSION_START = LocalTime.of(9, 15);
    private static final LocalTime SESSION_15MIN_END = LocalTime.of(9, 30);

    /** Entry straddle tolerance for VWAP contact (dollar slack so high/low need not be exact). */
    private static final double VWAP_CONTACT_SLACK = 0.001;

    private final ShoonyaMarketDataService marketDataService;
    private final TechnicalAnalysisService taService;
    private final TelegramService telegramService;

    private Clock clock = Clock.system(IST);

    @Value("${trading-bot.strategy.ohl-vwap.enabled:true}")
    private boolean enabled = true;

    @Value("${trading-bot.strategy.ohl-vwap.scheduler-enabled:true}")
    private boolean schedulerEnabled = true;

    @Value("${trading-bot.strategy.ohl-vwap.ohl-tolerance-percent:0.1}")
    private double ohlTolerancePercent = 0.1;

    @Value("${trading-bot.strategy.ohl-vwap.volume-multiplier:3.0}")
    private double volumeMultiplier = 3.0;

    @Value("${trading-bot.strategy.ohl-vwap.max-concurrent-trades:3}")
    private int maxConcurrentTrades = 3;

    @Value("${trading-bot.strategy.ohl-vwap.telegram-alerts:true}")
    private boolean telegramAlerts = true;

    private final List<OhlvSetup> watchlist = new CopyOnWriteArrayList<>();
    private final List<OhlvPaperPosition> openPositions = new CopyOnWriteArrayList<>();
    private final List<OhlvPaperPosition> closedTrades = new CopyOnWriteArrayList<>();
    private final AtomicBoolean scanExecutedToday = new AtomicBoolean(false);
    private final AtomicInteger executedTradeCount = new AtomicInteger(0);

    @Autowired
    public OhlvVwapStrategyService(
            ShoonyaMarketDataService marketDataService,
            TechnicalAnalysisService taService,
            TelegramService telegramService) {
        this.marketDataService = marketDataService;
        this.taService = taService;
        this.telegramService = telegramService;
    }

    // ----------------------------------------------------------------------
    // Public lifecycle entry points (invoked by OhlvVwapScheduler)
    // ----------------------------------------------------------------------

    /** Resets all daily state. Called at 09:15:00 IST each trading day. */
    public synchronized void resetDaily() {
        log.info("[OHLV] 09:15 IST daily reset: clearing watchlist, positions and trades.");
        watchlist.clear();
        openPositions.clear();
        closedTrades.clear();
        scanExecutedToday.set(false);
        executedTradeCount.set(0);
    }

    /**
     * Morning universe scan at 09:31:10 IST. Scans the NIFTY 100 universe for open=high / open=low
     * stocks with the volume filter, admits F&amp;O-eligible symbols to the watchlist and
     * broadcasts the list to Telegram.
     */
    public synchronized void runMorningScan() {
        if (!enabled) {
            log.debug("[OHLV] Strategy disabled. Skipping morning scan.");
            return;
        }
        if (scanExecutedToday.get()) {
            log.info("[OHLV] Morning scan already executed for today. Skipping.");
            return;
        }

        LocalDate today = LocalDate.now(clock);
        int scanned = 0;
        int ohLQualified = 0;
        int admitted = 0;
        List<String> skippedNonFno = new ArrayList<>();
        List<String> skippedNoData = new ArrayList<>();

        for (String symbol : Nifty100Registry.getAllSymbols()) {
            scanned++;
            try {
                String token = marketDataService.resolveToken(symbol);
                if (token == null || token.isBlank()) {
                    skippedNoData.add(symbol);
                    continue;
                }

                // Fetch ~24h of one-minute candles (yesterday's tail + today's opening bars).
                List<Candle> oneMin =
                        marketDataService.fetchHistoricalCandles("NSE", token, symbol, "1", 1);
                if (oneMin == null || oneMin.isEmpty()) {
                    skippedNoData.add(symbol);
                    continue;
                }

                Candle first15 = aggregateFirst15MinuteCandle(oneMin, today);
                List<Candle> todayCandles = filterByDate(oneMin, today);
                Candle first1Min = findFirstMinuteCandle(todayCandles);
                double prevDaySma20 = prevDayLast20VolumeSma(oneMin, today);

                if (first15 == null || first1Min == null || prevDaySma20 <= 0.0) {
                    skippedNoData.add(symbol);
                    continue;
                }

                OhlvDirection direction = classifyOpeningProfile(first15);
                if (direction == null) {
                    continue; // not open=high nor open=low within tolerance
                }

                ohLQualified++;

                // Volume filter: first 1-min candle volume must exceed multiplier x prev-day SMA20.
                boolean volumeOk = first1Min.volume() > (long) (volumeMultiplier * prevDaySma20);
                if (!volumeOk) {
                    log.debug(
                            "[OHLV] {} passed OHL but volume filter failed: vol={} vs threshold={}",
                            symbol,
                            first1Min.volume(),
                            (long) (volumeMultiplier * prevDaySma20));
                    continue;
                }

                // F&O eligibility verified on the NFO exchange at runtime.
                if (!isFnoEligible(symbol)) {
                    skippedNonFno.add(symbol);
                    continue;
                }

                OhlvSetup setup =
                        new OhlvSetup(
                                symbol,
                                direction,
                                first15.high(),
                                first15.low(),
                                first1Min.volume(),
                                prevDaySma20VolumeRounded(prevDaySma20));
                watchlist.add(setup);
                admitted++;
                log.info(
                        "[OHLV] Admitted {} ({}) markedHigh={} markedLow={} vol={} sma20={}",
                        symbol,
                        direction,
                        first15.high(),
                        first15.low(),
                        first1Min.volume(),
                        String.format("%.0f", prevDaySma20));
            } catch (Exception e) {
                log.warn("[OHLV] Error scanning {}: {}", symbol, e.getMessage());
            }
        }

        scanExecutedToday.set(true);
        log.info(
                "[OHLV] Morning scan complete: scanned={} ohlQualified={} admitted={} skippedFno={} skippedNoData={}",
                scanned,
                ohLQualified,
                admitted,
                skippedNonFno.size(),
                skippedNoData.size());

        if (telegramAlerts) {
            telegramService.sendOhlvWatchlistAlert(watchlist);
        }
    }

    /**
     * 5-minute monitoring cycle (09:35-14:55 IST): invalidate broken setups, evaluate VWAP straddle
     * entries for WATCHLIST setups, and evaluate opposite-side-VWAP exits for open positions.
     */
    public synchronized void runCycle() {
        if (!enabled) {
            return;
        }
        LocalTime now = LocalTime.now(clock);
        if (now.isBefore(TIME_FIRST_MONITOR) || now.isAfter(TIME_MONITOR_END)) {
            log.debug(
                    "[OHLV] Outside monitoring window ({} - {}). Skipping cycle at {}",
                    TIME_FIRST_MONITOR,
                    TIME_MONITOR_END,
                    now);
            return;
        }

        // Per-cycle cache: one 5-min fetch per symbol shared by setup + position evaluation.
        Map<String, List<Candle>> fiveMinCache = new ConcurrentHashMap<>();

        // ---- Invalidate broken setups ----
        for (OhlvSetup setup : watchlist) {
            if (!setup.isMonitored()) {
                continue;
            }
            try {
                List<Candle> fiveMin = fetchToday5Min(setup.getSymbol(), fiveMinCache);
                if (markBroken(setup, fiveMin)) {
                    setup.transitionTo(OhlvSetup.State.INVALIDATED, "MARKED_LEVEL_BROKEN");
                    log.info("[OHLV] {} invalidated: marked level broken", setup.getSymbol());
                }
            } catch (Exception e) {
                log.warn(
                        "[OHLV] Invalidation check failed for {}: {}",
                        setup.getSymbol(),
                        e.getMessage());
            }
        }

        // ---- Evaluate entries for surviving WATCHLIST setups ----
        for (OhlvSetup setup : watchlist) {
            if (!setup.isMonitored()) {
                continue;
            }
            if (openPositions.size() >= maxConcurrentTrades) {
                break;
            }
            try {
                List<Candle> fiveMin = fetchToday5Min(setup.getSymbol(), fiveMinCache);
                double[] vwapSeries = computeVwap(fiveMin);
                if (vwapSeries == null || vwapSeries.length == 0) {
                    continue;
                }
                Candle last = fiveMin.get(fiveMin.size() - 1);
                double vwap = vwapSeries[vwapSeries.length - 1];
                if (vwap <= 0.0) {
                    continue;
                }

                if (isEntryStraddle(last, vwap, setup.getDirection())) {
                    openPaperPosition(setup, last.close().doubleValue(), vwap, fiveMin);
                }
            } catch (Exception e) {
                log.warn(
                        "[OHLV] Entry evaluation failed for {}: {}",
                        setup.getSymbol(),
                        e.getMessage());
            }
        }

        // ---- Evaluate exits for open positions ----
        for (OhlvPaperPosition pos : openPositions) {
            try {
                List<Candle> fiveMin = fetchToday5Min(pos.getSymbol(), fiveMinCache);
                double[] vwapSeries = computeVwap(fiveMin);
                if (vwapSeries == null || vwapSeries.length == 0) {
                    continue;
                }
                Candle last = fiveMin.get(fiveMin.size() - 1);
                double vwap = vwapSeries[vwapSeries.length - 1];
                if (vwap <= 0.0) {
                    continue;
                }
                if (isOppositeSideClose(last, vwap, pos.getDirection())) {
                    closePaperPosition(pos, "VWAP_OPPOSITE_CLOSE", fiveMin);
                }
            } catch (Exception e) {
                log.warn(
                        "[OHLV] Exit evaluation failed for {}: {}",
                        pos.getSymbol(),
                        e.getMessage());
            }
        }
    }

    /**
     * Mandatory square-off at 15:00:10 IST. Closes every open paper position at the current option
     * premium and invalidates any remaining WATCHLIST setups.
     */
    public synchronized void executeSquareOff() {
        if (!enabled) {
            return;
        }
        log.info(
                "[OHLV] 15:00 IST mandatory square-off for {} open position(s).",
                openPositions.size());

        List<OhlvPaperPosition> toClose = new ArrayList<>(openPositions);
        for (OhlvPaperPosition pos : toClose) {
            try {
                closePaperPosition(pos, "MANDATORY_15_00_SQUARE_OFF", null);
            } catch (Exception e) {
                log.warn("[OHLV] Square-off failed for {}: {}", pos.getSymbol(), e.getMessage());
            }
        }

        for (OhlvSetup setup : watchlist) {
            if (setup.isMonitored()) {
                setup.transitionTo(OhlvSetup.State.CLOSED, "NO_ENTRY_AT_15_00");
            }
        }
    }

    // ----------------------------------------------------------------------
    // Qualification helpers
    // ----------------------------------------------------------------------

    /**
     * Aggregates today's first 15 one-minute candles (09:15-09:30) into a single candle.
     *
     * @return aggregated candle or {@code null} if fewer than 2 opening bars exist.
     */
    Candle aggregateFirst15MinuteCandle(List<Candle> oneMin, LocalDate today) {
        List<Candle> opening = new ArrayList<>();
        for (Candle c : oneMin) {
            if (c == null || c.timestamp() == null) {
                continue;
            }
            var zdt = c.timestamp().atZone(IST);
            if (!zdt.toLocalDate().equals(today)) {
                continue;
            }
            LocalTime t = zdt.toLocalTime();
            if (!t.isBefore(SESSION_START) && t.isBefore(SESSION_15MIN_END)) {
                opening.add(c);
            }
        }
        opening.sort(Comparator.comparing(Candle::timestamp));
        if (opening.size() < 2) {
            return null;
        }
        Candle first = opening.get(0);
        Candle last = opening.get(opening.size() - 1);
        BigDecimal high = first.high();
        BigDecimal low = first.low();
        long vol = 0;
        for (Candle c : opening) {
            if (c.high().compareTo(high) > 0) {
                high = c.high();
            }
            if (c.low().compareTo(low) < 0) {
                low = c.low();
            }
            vol += c.volume();
        }
        return new Candle(
                first.symbol(), "15", last.timestamp(), first.open(), high, low, last.close(), vol);
    }

    /**
     * Classifies the first 15-min candle's opening profile: open≈high → BEARISH, open≈low →
     * BULLISH, both within tolerance. Uses a relative tolerance (percent of the high/low).
     */
    OhlvDirection classifyOpeningProfile(Candle first15) {
        if (first15 == null) {
            return null;
        }
        BigDecimal open = first15.open();
        BigDecimal high = first15.high();
        BigDecimal low = first15.low();

        BigDecimal tol = BigDecimal.valueOf(ohlTolerancePercent / 100.0);

        // open=high: (high - open) / high <= tol
        boolean openHigh =
                high.compareTo(BigDecimal.ZERO) > 0
                        && high.subtract(open).divide(high, 6, RoundingMode.HALF_UP).compareTo(tol)
                                <= 0;

        // open=low: (open - low) / open <= tol
        boolean openLow =
                open.compareTo(BigDecimal.ZERO) > 0
                        && open.subtract(low).divide(open, 6, RoundingMode.HALF_UP).compareTo(tol)
                                <= 0;

        if (openHigh && openLow) {
            // Both true only on degenerate bars; prefer whichever side is closer.
            BigDecimal pHigh = high.subtract(open).divide(high, 6, RoundingMode.HALF_UP);
            BigDecimal pLow = open.subtract(low).divide(open, 6, RoundingMode.HALF_UP);
            return pHigh.compareTo(pLow) <= 0 ? OhlvDirection.BEARISH : OhlvDirection.BULLISH;
        }
        if (openHigh) {
            return OhlvDirection.BEARISH;
        }
        if (openLow) {
            return OhlvDirection.BULLISH;
        }
        return null;
    }

    Candle findFirstMinuteCandle(List<Candle> todayCandles) {
        return todayCandles.stream()
                .filter(c -> c != null && c.timestamp() != null)
                .min(Comparator.comparing(Candle::timestamp))
                .orElse(null);
    }

    /**
     * Computes the SMA of the previous trading day's last 20 one-minute candle volumes. If fewer
     * than 20 candles exist, averages whatever is available.
     */
    double prevDayLast20VolumeSma(List<Candle> oneMin, LocalDate today) {
        LocalDate prevDay = today.minusDays(1);
        List<Long> vols = new ArrayList<>();
        for (Candle c : oneMin) {
            if (c == null || c.timestamp() == null) {
                continue;
            }
            if (c.timestamp().atZone(IST).toLocalDate().equals(prevDay)) {
                vols.add(c.volume());
            }
        }
        if (vols.isEmpty()) {
            return 0.0;
        }
        int take = Math.min(20, vols.size());
        List<Long> last20 = vols.subList(vols.size() - take, vols.size());
        long sum = 0;
        for (long v : last20) {
            sum += v;
        }
        return (double) sum / last20.size();
    }

    /**
     * Verifies F&amp;O eligibility at runtime by searching the NFO exchange for the underlying
     * symbol. Non-empty option results indicate the stock trades derivatives on NSE-FO.
     */
    boolean isFnoEligible(String symbol) {
        try {
            JsonNode result = marketDataService.searchScrip("NFO", symbol);
            if (result == null || !result.isArray()) {
                return false;
            }
            for (JsonNode item : result) {
                String tsym = item.path("tsym").asText("");
                if (tsym != null && tsym.contains(symbol.toUpperCase())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("[OHLV] F&O eligibility check failed for {}: {}", symbol, e.getMessage());
            return false;
        }
    }

    // ----------------------------------------------------------------------
    // Monitoring helpers
    // ----------------------------------------------------------------------

    private List<Candle> fetchToday5Min(String symbol, Map<String, List<Candle>> cache) {
        return cache.computeIfAbsent(
                symbol,
                s -> {
                    List<Candle> all = marketDataService.fetch5MinCandles(s, 1);
                    return filterByDate(all, LocalDate.now(clock));
                });
    }

    private double[] computeVwap(List<Candle> fiveMin) {
        if (fiveMin == null || fiveMin.isEmpty()) {
            return null;
        }
        return taService.calculateVwapSeries(fiveMin);
    }

    /**
     * Returns {@code true} when the latest 5-min candle breaks the setup's marked level (BEARISH:
     * high &gt; markedHigh; BULLISH: low &lt; markedLow) — excluding the opening 15 minutes.
     */
    boolean markBroken(OhlvSetup setup, List<Candle> fiveMin) {
        if (fiveMin == null || fiveMin.isEmpty()) {
            return false;
        }
        for (Candle c : fiveMin) {
            var zdt = c.timestamp().atZone(IST);
            LocalTime t = zdt.toLocalTime();
            if (t.isBefore(SESSION_15MIN_END)) {
                continue; // skip bars inside the first 15-minute candle
            }
            if (setup.getDirection() == OhlvDirection.BEARISH) {
                if (c.high().compareTo(setup.getMarkedHigh()) > 0) {
                    return true;
                }
            } else {
                if (c.low().compareTo(setup.getMarkedLow()) < 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Entry straddle: the latest 5-min candle has high &gt;= VWAP and low &lt;= VWAP (price crossed
     * the VWAP during the bar), with the close on the setup's desired side — BEARISH: close below
     * VWAP (cross top→bottom); BULLISH: close above VWAP (cross bottom→top).
     */
    boolean isEntryStraddle(Candle last, double vwap, OhlvDirection direction) {
        if (last == null) {
            return false;
        }
        double high = last.high().doubleValue();
        double low = last.low().doubleValue();
        double close = last.close().doubleValue();
        boolean straddles = high >= vwap - VWAP_CONTACT_SLACK && low <= vwap + VWAP_CONTACT_SLACK;
        if (!straddles) {
            return false;
        }
        if (direction == OhlvDirection.BEARISH) {
            return close <= vwap;
        }
        return close >= vwap;
    }

    /**
     * Exit: the latest 5-min candle closes on the opposite side of VWAP relative to entry — BEARISH
     * (PE) exits when close &gt; VWAP; BULLISH (CE) exits when close &lt; VWAP.
     */
    boolean isOppositeSideClose(Candle last, double vwap, OhlvDirection direction) {
        if (last == null) {
            return false;
        }
        double close = last.close().doubleValue();
        if (direction == OhlvDirection.BEARISH) {
            return close > vwap;
        }
        return close < vwap;
    }

    // ----------------------------------------------------------------------
    // Paper position management
    // ----------------------------------------------------------------------

    private void openPaperPosition(
            OhlvSetup setup, double stockPrice, double vwap, List<Candle> fiveMin) {
        if (openPositions.size() >= maxConcurrentTrades) {
            setup.transitionTo(OhlvSetup.State.INVALIDATED, "MAX_CONCURRENT_TRADES");
            return;
        }
        try {
            BigDecimal atmStrike =
                    StockFnoRegistry.calculateAtmStrike(
                            setup.getSymbol(), BigDecimal.valueOf(stockPrice));
            int lotSize = StockFnoRegistry.getLotSize(setup.getSymbol());
            BigDecimal entryPremium =
                    fetchOptionPremium(setup.getSymbol(), atmStrike, setup.getDirection());

            if (entryPremium == null || entryPremium.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn(
                        "[OHLV] No option premium for {} {} {}; skipping entry.",
                        setup.getSymbol(),
                        atmStrike,
                        setup.getDirection());
                setup.transitionTo(OhlvSetup.State.INVALIDATED, "OPTION_PREMIUM_UNAVAILABLE");
                return;
            }

            String tradeId = "OHLV-" + System.currentTimeMillis();
            OhlvPaperPosition pos =
                    new OhlvPaperPosition(
                            tradeId,
                            setup.getSymbol(),
                            setup.getDirection(),
                            atmStrike,
                            lotSize,
                            entryPremium,
                            Instant.now(clock));

            openPositions.add(pos);
            setup.transitionTo(OhlvSetup.State.ENTERED, "VWAP_STRADDLE");
            executedTradeCount.incrementAndGet();

            log.info(
                    "[OHLV] ENTRY {} {} {} @ premium {} strike {} (vwap {})",
                    setup.getSymbol(),
                    setup.getDirection(),
                    pos.getOptionType(),
                    entryPremium,
                    atmStrike,
                    String.format("%.2f", vwap));

            if (telegramAlerts) {
                telegramService.sendOhlvEntryAlert(pos);
            }
        } catch (Exception e) {
            log.warn(
                    "[OHLV] Failed to open paper position for {}: {}",
                    setup.getSymbol(),
                    e.getMessage());
            setup.transitionTo(OhlvSetup.State.INVALIDATED, "ENTRY_ERROR");
        }
    }

    private void closePaperPosition(OhlvPaperPosition pos, String reason, List<Candle> fiveMin) {
        BigDecimal exitPremium =
                fetchOptionPremium(pos.getSymbol(), pos.getAtmStrike(), pos.getDirection());
        if (exitPremium == null) {
            log.warn(
                    "[OHLV] No exit premium for {}; closing at entry premium (assumed flat).",
                    pos.getSymbol());
            exitPremium = BigDecimal.ZERO;
        }
        pos.close(exitPremium, reason, Instant.now(clock));
        openPositions.remove(pos);
        closedTrades.add(pos);

        log.info(
                "[OHLV] EXIT {} {} premium {} -> {} pnl={} reason={}",
                pos.getSymbol(),
                pos.getOptionType(),
                pos.getEntryPremium(),
                exitPremium,
                pos.getRealizedPnl(),
                reason);

        if (telegramAlerts) {
            telegramService.sendOhlvExitAlert(pos, reason);
        }
    }

    /**
     * Fetches the option premium for an ATM strike on the NFO exchange via SearchScrip + GetQuotes.
     * Returns {@code null} when the contract cannot be located or has no LTP.
     */
    private BigDecimal fetchOptionPremium(
            String symbol, BigDecimal strike, OhlvDirection direction) {
        try {
            String expiry = resolveMonthlyExpiry();
            String optionType = direction == OhlvDirection.BULLISH ? "CE" : "PE";
            String optionSymbol = symbol + expiry + strike.intValue() + optionType;

            JsonNode searchResult = marketDataService.searchScrip("NFO", optionSymbol);
            if (searchResult == null || !searchResult.isArray() || searchResult.isEmpty()) {
                log.warn("[OHLV] Option scrip not found for {} on NFO", optionSymbol);
                return null;
            }
            JsonNode firstMatch = searchResult.get(0);
            String token = firstMatch.path("token").asText(null);
            if (token == null || token.isBlank()) {
                log.warn("[OHLV] No token found for option {}", optionSymbol);
                return null;
            }
            JsonNode quote = marketDataService.fetchQuote("NFO", token);
            if (quote != null) {
                String ltpStr = quote.path("lp").asText(null);
                if (ltpStr != null && !ltpStr.isBlank()) {
                    BigDecimal ltp = new BigDecimal(ltpStr);
                    if (ltp.compareTo(BigDecimal.ZERO) > 0) {
                        return ltp;
                    }
                }
            }
        } catch (Exception e) {
            log.warn(
                    "[OHLV] Failed to fetch option premium for {} {} {}: {}",
                    symbol,
                    strike,
                    direction,
                    e.getMessage());
        }
        return null;
    }

    /** Resolves the monthly expiry suffix (e.g. "25JUL") = last Thursday of the current month. */
    String resolveMonthlyExpiry() {
        LocalDate today = LocalDate.now(clock);
        YearMonth currentMonth = YearMonth.from(today);
        LocalDate lastDay = currentMonth.atEndOfMonth();
        LocalDate lastThursday = lastDay;
        while (lastThursday.getDayOfWeek() != DayOfWeek.THURSDAY) {
            lastThursday = lastThursday.minusDays(1);
        }
        if (today.isAfter(lastThursday)) {
            currentMonth = currentMonth.plusMonths(1);
            lastDay = currentMonth.atEndOfMonth();
            lastThursday = lastDay;
            while (lastThursday.getDayOfWeek() != DayOfWeek.THURSDAY) {
                lastThursday = lastThursday.minusDays(1);
            }
        }
        int year = currentMonth.getYear() % 100;
        String month =
                currentMonth
                        .getMonth()
                        .getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                        .toUpperCase(Locale.ENGLISH);
        return String.format("%02d%s", year, month);
    }

    // ----------------------------------------------------------------------
    // Utilities
    // ----------------------------------------------------------------------

    private List<Candle> filterByDate(List<Candle> candles, LocalDate date) {
        if (candles == null || candles.isEmpty()) {
            return new ArrayList<>();
        }
        List<Candle> out = new ArrayList<>();
        for (Candle c : candles) {
            if (c != null
                    && c.timestamp() != null
                    && c.timestamp().atZone(IST).toLocalDate().equals(date)) {
                out.add(c);
            }
        }
        out.sort(Comparator.comparing(Candle::timestamp));
        return out;
    }

    private double prevDaySma20VolumeRounded(double sma) {
        return Math.round(sma * 100.0) / 100.0;
    }

    // ----------------------------------------------------------------------
    // Getters / Setters (testing + observability)
    // ----------------------------------------------------------------------

    public List<OhlvSetup> getWatchlist() {
        return List.copyOf(watchlist);
    }

    /** Package-private: exposes the live open-positions list (tests). */
    List<OhlvPaperPosition> getOpenPositionsInternal() {
        return openPositions;
    }

    public List<OhlvPaperPosition> getOpenPositions() {
        return List.copyOf(openPositions);
    }

    public List<OhlvPaperPosition> getClosedTrades() {
        return List.copyOf(closedTrades);
    }

    public int getExecutedTradeCount() {
        return executedTradeCount.get();
    }

    public boolean isScanExecutedToday() {
        return scanExecutedToday.get();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public Clock getClock() {
        return clock;
    }

    public void setOhlTolerancePercent(double ohlTolerancePercent) {
        this.ohlTolerancePercent = ohlTolerancePercent;
    }

    public void setVolumeMultiplier(double volumeMultiplier) {
        this.volumeMultiplier = volumeMultiplier;
    }

    public void setMaxConcurrentTrades(int maxConcurrentTrades) {
        this.maxConcurrentTrades = maxConcurrentTrades;
    }

    public void setTelegramAlerts(boolean telegramAlerts) {
        this.telegramAlerts = telegramAlerts;
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    public Duration getActiveWindow() {
        return Duration.between(TIME_FIRST_MONITOR, TIME_MONITOR_END);
    }
}
