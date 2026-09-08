package com.tradingbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.indicator.SuperTrendResult;
import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.LowestVolumePaperPosition;
import com.tradingbot.model.strategy.LowestVolumeSetup;
import com.tradingbot.model.strategy.LowestVolumeSetupState;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.Nifty200Registry;
import com.tradingbot.util.StockFnoRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Core engine implementing the Intraday Strategy: Lowest Volume Reversal & Continuation. Reference
 * Spec: lowest_volume_reversal_spec.md
 */
@Service
public class LowestVolumeReversalService {

    private static final Logger log = LoggerFactory.getLogger(LowestVolumeReversalService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public static final LocalTime TIME_SESSION_START = LocalTime.of(9, 15);
    public static final LocalTime TIME_SCANNER_START = LocalTime.of(9, 25);
    public static final LocalTime TIME_ENTRY_CUTOFF = LocalTime.of(14, 45);
    public static final LocalTime TIME_HARD_EXIT = LocalTime.of(15, 0);

    private final ShoonyaMarketDataService marketDataService;
    private final TechnicalAnalysisService taService;
    private final TelegramService telegramService;
    private final ShoonyaConfig config;

    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    @Value("${trading-bot.strategy.lowest-volume.enabled:true}")
    private boolean enabled = true;

    @Value("${trading-bot.strategy.lowest-volume.paper-capital:100000.0}")
    private double paperCapital = 100000.0;

    @Value("${trading-bot.strategy.lowest-volume.risk-per-trade-percent:1.0}")
    private double riskPerTradePercent = 1.0;

    @Value("${trading-bot.strategy.lowest-volume.max-concurrent-trades:2}")
    private int maxConcurrentTrades = 2;

    @Value("${trading-bot.strategy.lowest-volume.min-pct-change:1.0}")
    private double minPctChange = 1.0;

    @Value("${trading-bot.strategy.lowest-volume.top-n-stocks:10}")
    private int topNStocks = 10;

    @Value("${trading-bot.strategy.lowest-volume.telegram-alerts:true}")
    private boolean telegramAlerts = true;

    @Value("${trading-bot.strategy.lowest-volume.setup-timeout-candles:12}")
    private int setupTimeoutCandles = 12;

    // Active state maps
    private final Map<String, LowestVolumeSetup> activeSetups = new ConcurrentHashMap<>();
    private final Map<String, LowestVolumePaperPosition> openPositions = new ConcurrentHashMap<>();
    private final List<LowestVolumePaperPosition> tradeHistory =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Set<String> exhaustedSymbols = ConcurrentHashMap.newKeySet();

    private final List<String> currentTopGainers =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<String> currentTopLosers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<StockQuoteSnapshot> currentTopGainerSnapshots =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<StockQuoteSnapshot> currentTopLoserSnapshots =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private boolean niftyBullish = true;
    private volatile boolean universeScanCompletedToday = false;
    private final AtomicInteger tradeCounter = new AtomicInteger(1);

    @Autowired
    public LowestVolumeReversalService(
            ShoonyaMarketDataService marketDataService,
            TechnicalAnalysisService taService,
            TelegramService telegramService,
            ShoonyaConfig config) {
        this.marketDataService = marketDataService;
        this.taService = taService;
        this.telegramService = telegramService;
        this.config = config;
    }

    /**
     * Executes one complete scanning and strategy evaluation cycle. Called every 5 minutes on
     * candle close during market hours.
     */
    public synchronized void runCycle() {
        if (!enabled) {
            log.debug("[LVR] Strategy is currently disabled.");
            return;
        }

        LocalTime nowTime = LocalTime.now(IST);

        // 1. Session Timing Checks
        if (nowTime.isBefore(TIME_SESSION_START)) {
            log.debug("[LVR] Before market open (09:15 IST). Standing by.");
            return;
        }

        // Hard exit at 15:00
        if (!nowTime.isBefore(TIME_HARD_EXIT)) {
            executeHardExit(nowTime);
            return;
        }

        // Between 09:15 and 09:25: Data collection only
        if (nowTime.isBefore(TIME_SCANNER_START)) {
            log.info(
                    "[LVR] 09:15 - 09:25 IST: Pre-scanner data collection window. Setups start at 09:25.");
            return;
        }

        log.info("[LVR] Executing 5-min strategy cycle at {} IST...", nowTime);

        // If morning universe scan was not completed today, run it to lock the daily watchlist
        if (!universeScanCompletedToday) {
            log.info(
                    "[LVR] Morning universe scan not yet completed today. Triggering morning scan now...");
            runMorningUniverseScan();
        } else {
            // Re-evaluate NIFTY 50 Direction to track ongoing trend alignment
            evaluateNiftyDirection();
        }

        // Update and Evaluate Setups strictly for the fixed Watchlist Symbols
        processWatchlistSetups(nowTime);
    }

    /**
     * Scheduled Morning Universe Scan (at 09:25 AM IST). Identifies Top 10 Gainers and Top 10
     * Losers from the F&O universe, fixes this list for the entire trading day, seeds initial
     * setups, and sends the daily Telegram alert once.
     */
    public synchronized void runMorningUniverseScan() {
        if (!enabled) {
            log.debug("[LVR] Strategy is disabled. Skipping morning universe scan.");
            return;
        }

        log.info("[LVR] Running 09:25 AM Morning Universe Scan to fix daily watchlist...");

        // 1. Evaluate NIFTY 50 Direction
        evaluateNiftyDirection();

        // 2. Scan F&O Universe for Top Gainers and Top Losers
        scanUniverse();

        // 3. Fix the list for the day and initialize active setups
        if (niftyBullish) {
            for (String g : currentTopGainers) {
                if (!exhaustedSymbols.contains(g)) {
                    activeSetups.computeIfAbsent(
                            g, k -> new LowestVolumeSetup(k, LowestVolumeDirection.LONG));
                }
            }
        } else {
            for (String l : currentTopLosers) {
                if (!exhaustedSymbols.contains(l)) {
                    activeSetups.computeIfAbsent(
                            l, k -> new LowestVolumeSetup(k, LowestVolumeDirection.SHORT));
                }
            }
        }

        this.universeScanCompletedToday = true;
        log.info(
                "[LVR] Daily watchlist fixed for today: {} Top Gainers {}, {} Top Losers {}. Active Setups: {}. Subsequent cycles will work on this list only.",
                currentTopGainers.size(),
                currentTopGainers,
                currentTopLosers.size(),
                currentTopLosers,
                activeSetups.size());

        // 4. Send Telegram message once a day upon list identification
        if (telegramAlerts) {
            telegramService.sendLvrIdentifiedStocksAlert(
                    currentTopGainerSnapshots,
                    currentTopLoserSnapshots,
                    niftyBullish,
                    activeSetups.size(),
                    openPositions.size());
        }
    }

    /**
     * Evaluates NIFTY 50 trend to determine broader market alignment. Longs allowed only if NIFTY
     * 50 is Bullish (Green). Shorts only if Bearish (Red).
     */
    public void evaluateNiftyDirection() {
        try {
            JsonNode quote = marketDataService.fetchQuote("NSE", "10576");
            if (quote != null && quote.has("lp") && quote.has("o")) {
                double lp = quote.path("lp").asDouble(0.0);
                double o = quote.path("o").asDouble(0.0);
                double c = quote.path("c").asDouble(0.0);
                if (lp > 0 && o > 0) {
                    this.niftyBullish = (lp >= o);
                    log.info(
                            "[LVR] NIFTY 50 Quote: LTP={}, Open={}, PrevClose={} ➔ {}",
                            lp,
                            o,
                            c,
                            niftyBullish
                                    ? "BULLISH (Longs Eligible)"
                                    : "BEARISH (Shorts Eligible)");
                    return;
                }
            }
        } catch (Exception e) {
            log.warn(
                    "[LVR] Failed to fetch NIFTY 50 quote: {}. Defaulting to Bullish.",
                    e.getMessage());
        }
        // Fallback default
        this.niftyBullish = true;
    }

    /**
     * Scans the F&O universe to rank Top Gainers and Top Losers by % change from previous close.
     */
    public void scanUniverse() {
        List<String> universe = getFnoUniverse();
        List<CompletableFuture<StockQuoteSnapshot>> futures = new ArrayList<>();

        for (String sym : universe) {
            futures.add(CompletableFuture.supplyAsync(() -> fetchStockSnapshot(sym), executor));
        }

        List<StockQuoteSnapshot> snapshots = new ArrayList<>();
        for (CompletableFuture<StockQuoteSnapshot> f : futures) {
            try {
                StockQuoteSnapshot s = f.join();
                if (s != null && s.ltp() > 0 && s.prevClose() > 0) {
                    snapshots.add(s);
                }
            } catch (Exception e) {
                log.debug("[LVR] Snapshot fetch error: {}", e.getMessage());
            }
        }

        if (snapshots.isEmpty()) {
            log.warn(
                    "[LVR] No stock snapshots retrieved from universe. Using cached/registry fallbacks.");
            return;
        }

        // Rank Top Gainers (pctChange >= +minPctChange, sorted descending)
        List<StockQuoteSnapshot> gainerSnapshots =
                snapshots.stream()
                        .filter(s -> s.pctChange() >= minPctChange)
                        .sorted(
                                Comparator.comparingDouble(StockQuoteSnapshot::pctChange)
                                        .reversed())
                        .limit(topNStocks)
                        .toList();

        // Rank Top Losers (pctChange <= -minPctChange, sorted ascending)
        List<StockQuoteSnapshot> loserSnapshots =
                snapshots.stream()
                        .filter(s -> s.pctChange() <= -minPctChange)
                        .sorted(Comparator.comparingDouble(StockQuoteSnapshot::pctChange))
                        .limit(topNStocks)
                        .toList();

        currentTopGainerSnapshots.clear();
        currentTopGainerSnapshots.addAll(gainerSnapshots);

        currentTopLoserSnapshots.clear();
        currentTopLoserSnapshots.addAll(loserSnapshots);

        currentTopGainers.clear();
        currentTopGainers.addAll(gainerSnapshots.stream().map(StockQuoteSnapshot::symbol).toList());

        currentTopLosers.clear();
        currentTopLosers.addAll(loserSnapshots.stream().map(StockQuoteSnapshot::symbol).toList());

        log.info(
                "[LVR] Scanner Refresh: Found {} Top Gainers {}, {} Top Losers {}",
                currentTopGainers.size(),
                currentTopGainers,
                currentTopLosers.size(),
                currentTopLosers);
    }

    /** Dispatches an immediate Telegram report with all identified F&O stocks. */
    public void sendScanTelegramReport() {
        if (currentTopGainerSnapshots.isEmpty() && currentTopLoserSnapshots.isEmpty()) {
            scanUniverse();
        }
        if (telegramAlerts) {
            telegramService.sendLvrIdentifiedStocksAlert(
                    currentTopGainerSnapshots,
                    currentTopLoserSnapshots,
                    niftyBullish,
                    activeSetups.size(),
                    openPositions.size());
        }
    }

    /**
     * Processes setups for: 1. Newly qualified Top Gainers/Losers (if Nifty alignment matches) 2.
     * Already tracked setups in PULLBACK_TRACKING, TRIGGER_ARMED, or IN_POSITION
     */
    public void processWatchlistSetups(LocalTime nowTime) {
        // Collect candidate symbols
        Set<String> symbolsToEvaluate = ConcurrentHashMap.newKeySet();

        // Add newly qualifying top gainers (if Nifty is Bullish)
        if (niftyBullish) {
            for (String g : currentTopGainers) {
                if (!exhaustedSymbols.contains(g)) {
                    symbolsToEvaluate.add(g);
                    activeSetups.computeIfAbsent(
                            g, k -> new LowestVolumeSetup(k, LowestVolumeDirection.LONG));
                }
            }
        }

        // Add newly qualifying top losers (if Nifty is Bearish)
        if (!niftyBullish) {
            for (String l : currentTopLosers) {
                if (!exhaustedSymbols.contains(l)) {
                    symbolsToEvaluate.add(l);
                    activeSetups.computeIfAbsent(
                            l, k -> new LowestVolumeSetup(k, LowestVolumeDirection.SHORT));
                }
            }
        }

        // Always retain existing active setups (even if dropped from top 10 mid-setup)
        for (Map.Entry<String, LowestVolumeSetup> entry : activeSetups.entrySet()) {
            LowestVolumeSetup setup = entry.getValue();
            if (setup.getState() != LowestVolumeSetupState.IDLE
                    && setup.getState() != LowestVolumeSetupState.REJECTED_EXHAUSTED
                    && !setup.isClosed()) {
                symbolsToEvaluate.add(entry.getKey());
            }
        }

        // Concurrently evaluate each candidate symbol
        for (String sym : symbolsToEvaluate) {
            try {
                evaluateSymbolSetup(sym, nowTime);
            } catch (Exception e) {
                log.error("[LVR] Error evaluating setup for {}: {}", sym, e.getMessage(), e);
            }
        }

        // Evaluate and manage open positions (Targets, SL, SuperTrend trailing)
        evaluateOpenPositions(nowTime);
    }

    /** Evaluates the 5-minute chart for a specific symbol through the LVR state machine. */
    public void evaluateSymbolSetup(String symbol, LocalTime nowTime) {
        LowestVolumeSetup setup = activeSetups.get(symbol);
        if (setup == null || setup.isExhaustedOrRejected() || setup.isInPosition()) {
            return;
        }

        // Fetch 5-min candles (at least 2 days to have seed candles for ATR)
        List<Candle> candles = marketDataService.fetch5MinCandles(symbol, 2);
        if (candles == null || candles.size() < 16) {
            log.debug(
                    "[LVR] Insufficient 5m candles for {} (count: {})",
                    symbol,
                    (candles != null ? candles.size() : 0));
            return;
        }

        int size = candles.size();
        double[] high = new double[size];
        double[] low = new double[size];
        double[] close = new double[size];
        for (int i = 0; i < size; i++) {
            high[i] = candles.get(i).high().doubleValue();
            low[i] = candles.get(i).low().doubleValue();
            close[i] = candles.get(i).close().doubleValue();
        }

        // Compute 5-min ATR(14)
        double[] atrSeries = taService.calculateAtrSeries(high, low, close, 14);
        double currentAtr = atrSeries[size - 1];
        if (Double.isNaN(currentAtr) || currentAtr <= 0) {
            currentAtr = (high[size - 1] - low[size - 1]);
        }
        setup.setAtr14(currentAtr);

        // Separate candles belonging to today's session (09:15 onwards)
        List<Candle> todayCandles = filterTodayCandles(candles);
        if (todayCandles.isEmpty()) {
            return;
        }

        // 3.5 Exhaustion Disqualifier
        if (checkExhaustion(symbol, todayCandles, setup)) {
            return;
        }

        LowestVolumeDirection dir = setup.getDirection();
        LowestVolumeSetupState state = setup.getState();

        // STATE MACHINE EVALUATION
        switch (state) {
            case SCANNING, LEG_FORMING:
                evaluateInitialLeg(todayCandles, setup, currentAtr);
                break;

            case LEG_CONFIRMED, PULLBACK_TRACKING:
                evaluatePullback(todayCandles, setup, currentAtr);
                break;

            case TRIGGER_ARMED:
                evaluateArmedTrigger(todayCandles, setup, nowTime);
                break;

            default:
                break;
        }
    }

    /**
     * Evaluates the Initial Leg (§3.1): 2-3 consecutive candles in direction whose cumulative move
     * >= 0.5 * ATR(14).
     */
    public void evaluateInitialLeg(List<Candle> todayCandles, LowestVolumeSetup setup, double atr) {
        if (todayCandles.size() < 2) {
            return;
        }

        LowestVolumeDirection dir = setup.getDirection();
        double minMoveThreshold = 0.5 * atr;

        // Check the last 2 or 3 completed candles
        int lastIdx = todayCandles.size() - 1;

        // Test 3-candle leg
        if (todayCandles.size() >= 3) {
            Candle c1 = todayCandles.get(lastIdx - 2);
            Candle c2 = todayCandles.get(lastIdx - 1);
            Candle c3 = todayCandles.get(lastIdx);

            if (isDirectional(c1, dir) && isDirectional(c2, dir) && isDirectional(c3, dir)) {
                BigDecimal move = getCumulativeMove(c1, c3, dir);
                if (move.doubleValue() >= minMoveThreshold) {
                    setup.setInitialLeg(List.of(c1, c2, c3), move, atr);
                    log.info(
                            "[LVR] [{}] 3-Candle Initial Leg Confirmed! Move: ₹{} >= Threshold: ₹{}",
                            setup.getSymbol(),
                            move,
                            minMoveThreshold);
                    return;
                }
            }
        }

        // Test 2-candle leg
        Candle c1 = todayCandles.get(lastIdx - 1);
        Candle c2 = todayCandles.get(lastIdx);
        if (isDirectional(c1, dir) && isDirectional(c2, dir)) {
            BigDecimal move = getCumulativeMove(c1, c2, dir);
            if (move.doubleValue() >= minMoveThreshold) {
                setup.setInitialLeg(List.of(c1, c2), move, atr);
                log.info(
                        "[LVR] [{}] 2-Candle Initial Leg Confirmed! Move: ₹{} >= Threshold: ₹{}",
                        setup.getSymbol(),
                        move,
                        minMoveThreshold);
                return;
            } else {
                setup.transitionTo(
                        LowestVolumeSetupState.LEG_FORMING, "Forming leg, move below 0.5 ATR");
            }
        }
    }

    /**
     * Evaluates Pullback & Lowest Volume Trigger Candle (§3.2, §3.3): Checks opposite-color candles
     * in rolling 10-bar window and applies the Range filter.
     */
    public void evaluatePullback(List<Candle> todayCandles, LowestVolumeSetup setup, double atr) {
        if (todayCandles.isEmpty()) {
            return;
        }

        LowestVolumeDirection dir = setup.getDirection();
        Candle latestCandle = todayCandles.get(todayCandles.size() - 1);

        // If opposite-color candle printed
        if (isOppositeColor(latestCandle, dir)) {
            setup.transitionTo(
                    LowestVolumeSetupState.PULLBACK_TRACKING,
                    "Opposite candle printed in pullback");

            // Look back up to 10 preceding candles to identify the lowest volume candle
            int windowSize = Math.min(10, todayCandles.size());
            List<Candle> window =
                    todayCandles.subList(todayCandles.size() - windowSize, todayCandles.size());

            Candle lowestVolCandle = null;
            long minVolume = Long.MAX_VALUE;

            for (Candle c : window) {
                if (isOppositeColor(c, dir)) {
                    // Tie-break: if equal volume, the more recent candle takes precedence
                    if (c.volume() <= minVolume) {
                        minVolume = c.volume();
                        lowestVolCandle = c;
                    }
                }
            }

            if (lowestVolCandle != null) {
                // §3.3 Range Filter: Reject trigger candle if (high - low) > 1.2 * ATR(14)
                double candleRange =
                        lowestVolCandle.high().subtract(lowestVolCandle.low()).doubleValue();
                double maxAllowedRange = 1.2 * atr;

                if (candleRange > maxAllowedRange) {
                    log.info(
                            "[LVR] [{}] Trigger candle rejected by Range Filter! Range: ₹{} > Max: ₹{}",
                            setup.getSymbol(),
                            candleRange,
                            maxAllowedRange);
                    return;
                }

                // Arm the Trigger!
                BigDecimal tickSize = BigDecimal.valueOf(0.05);
                BigDecimal triggerPrc;
                BigDecimal slPrc;
                BigDecimal target1Prc;

                if (dir == LowestVolumeDirection.LONG) {
                    triggerPrc =
                            lowestVolCandle.high().add(tickSize).setScale(2, RoundingMode.HALF_UP);
                    slPrc =
                            lowestVolCandle
                                    .low()
                                    .subtract(tickSize)
                                    .setScale(2, RoundingMode.HALF_UP);
                    BigDecimal slDistance = triggerPrc.subtract(slPrc);
                    target1Prc =
                            triggerPrc
                                    .add(slDistance.multiply(BigDecimal.valueOf(2)))
                                    .setScale(2, RoundingMode.HALF_UP);
                } else {
                    triggerPrc =
                            lowestVolCandle
                                    .low()
                                    .subtract(tickSize)
                                    .setScale(2, RoundingMode.HALF_UP);
                    slPrc = lowestVolCandle.high().add(tickSize).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal slDistance = slPrc.subtract(triggerPrc);
                    target1Prc =
                            triggerPrc
                                    .subtract(slDistance.multiply(BigDecimal.valueOf(2)))
                                    .setScale(2, RoundingMode.HALF_UP);
                }

                setup.setTriggerCandle(lowestVolCandle, triggerPrc, slPrc, target1Prc);
                log.info(
                        "[LVR] [{}] TRIGGER ARMED! Direction: {}, Trigger: ₹{}, SL: ₹{}, Target1: ₹{}, Vol: {}",
                        setup.getSymbol(),
                        dir,
                        triggerPrc,
                        slPrc,
                        target1Prc,
                        lowestVolCandle.volume());

                int potentialQty = calculatePositionSize(triggerPrc, slPrc);
                if (telegramAlerts) {
                    telegramService.sendLvrSetupArmedAlert(
                            setup, potentialQty, BigDecimal.valueOf(getRiskPerTradeAmount()));
                }
            }
        } else if (isDirectional(latestCandle, dir)) {
            // Same-direction candle prints without a qualifying trigger -> pullback invalidated
            // (§3.2 / §6)
            log.info(
                    "[LVR] [{}] Leg resumed with same-direction candle before trigger armed. Resetting to SCANNING.",
                    setup.getSymbol());
            setup.resetToScanning();
        }
    }

    /**
     * Evaluates Armed Trigger (§3.4): Checks if price breaches trigger price to enter paper trade,
     * or if 6 candles timeout elapsed.
     */
    public void evaluateArmedTrigger(
            List<Candle> todayCandles, LowestVolumeSetup setup, LocalTime nowTime) {
        if (setup.getState() != LowestVolumeSetupState.TRIGGER_ARMED) {
            return;
        }

        // Check entry cutoff (14:45)
        if (nowTime.isAfter(TIME_ENTRY_CUTOFF)) {
            log.info("[LVR] [{}] After 14:45 cutoff. Armed setup cancelled.", setup.getSymbol());
            setup.resetToScanning();
            return;
        }

        // Setup Timeout check: configurable (default 12 candles / 60 min)
        setup.incrementArmedTimeout();
        if (setup.getArmedCandlesElapsed() > setupTimeoutCandles) {
            log.info(
                    "[LVR] [{}] {}-candle ({}m) timeout elapsed without trigger breach. Dropping setup.",
                    setup.getSymbol(),
                    setupTimeoutCandles,
                    setupTimeoutCandles * 5);
            setup.resetToScanning();
            return;
        }

        // Check if concurrent trades cap reached
        if (openPositions.size() >= maxConcurrentTrades) {
            log.debug(
                    "[LVR] [{}] Max concurrent trades ({}) reached. Cannot fill new trade.",
                    setup.getSymbol(),
                    maxConcurrentTrades);
            return;
        }

        Candle latestCandle = todayCandles.get(todayCandles.size() - 1);
        LowestVolumeDirection dir = setup.getDirection();
        BigDecimal triggerPrc = setup.getTriggerPrice();
        BigDecimal slPrc = setup.getStopLossPrice();

        boolean isTriggered = false;
        BigDecimal fillPrice = triggerPrc;

        if (dir == LowestVolumeDirection.LONG) {
            if (latestCandle.high().compareTo(triggerPrc) >= 0) {
                isTriggered = true;
                // Slippage guard: cap fill at 0.15% above trigger
                BigDecimal maxSlippage = triggerPrc.multiply(BigDecimal.valueOf(1.0015));
                fillPrice =
                        latestCandle.close().compareTo(maxSlippage) > 0 ? maxSlippage : triggerPrc;
            }
        } else {
            if (latestCandle.low().compareTo(triggerPrc) <= 0) {
                isTriggered = true;
                // Slippage guard: cap fill at 0.15% below trigger
                BigDecimal maxSlippage = triggerPrc.multiply(BigDecimal.valueOf(0.9985));
                fillPrice =
                        latestCandle.close().compareTo(maxSlippage) < 0 ? maxSlippage : triggerPrc;
            }
        }

        if (isTriggered) {
            executePaperTradeEntry(setup, fillPrice, slPrc);
        }
    }

    /** Executes paper trade entry and creates an active paper position. */
    public synchronized void executePaperTradeEntry(
            LowestVolumeSetup setup, BigDecimal fillPrice, BigDecimal slPrc) {
        String tradeId = "LVR_TRD_" + tradeCounter.getAndIncrement();
        int qty = calculatePositionSize(fillPrice, slPrc);
        BigDecimal rpt =
                BigDecimal.valueOf(getRiskPerTradeAmount()).setScale(2, RoundingMode.HALF_UP);

        LowestVolumePaperPosition pos =
                new LowestVolumePaperPosition(
                        tradeId,
                        setup.getSymbol(),
                        setup.getDirection(),
                        fillPrice,
                        slPrc,
                        setup.getTarget1Price(),
                        qty,
                        rpt,
                        Instant.now());

        openPositions.put(setup.getSymbol(), pos);
        setup.transitionTo(LowestVolumeSetupState.IN_POSITION, "Filled paper trade entry");

        log.info(
                "[LVR] [{}] PAPER TRADE FILLED! ID: {} | {} Qty: {} @ ₹{} | SL: ₹{} | Target1: ₹{}",
                setup.getSymbol(),
                tradeId,
                setup.getDirection(),
                qty,
                fillPrice,
                slPrc,
                setup.getTarget1Price());

        if (telegramAlerts) {
            telegramService.sendLvrTradeEntryAlert(pos, setup);
        }
    }

    /**
     * Evaluates all open paper positions: - Target 1 hit: Book 50% & move SL to Breakeven - Stop
     * Loss hit: Close position - Runner Trailing Exit: 5m SuperTrend(10, 3) flip - Hard Exit at
     * 15:00
     */
    public void evaluateOpenPositions(LocalTime nowTime) {
        for (Map.Entry<String, LowestVolumePaperPosition> entry : openPositions.entrySet()) {
            String symbol = entry.getKey();
            LowestVolumePaperPosition pos = entry.getValue();
            if (pos.isClosed()) {
                continue;
            }

            try {
                List<Candle> candles = marketDataService.fetch5MinCandles(symbol, 2);
                if (candles == null || candles.isEmpty()) {
                    continue;
                }

                Candle latest = candles.get(candles.size() - 1);
                LowestVolumeDirection dir = pos.getDirection();

                // 1. Check Stop Loss Hit
                if (dir == LowestVolumeDirection.LONG
                        && latest.low().compareTo(pos.getCurrentSl()) <= 0) {
                    pos.close(pos.getCurrentSl(), "STOP_LOSS_HIT", Instant.now());
                    finalizeClosedPosition(symbol, pos, "STOP_LOSS_HIT");
                    continue;
                } else if (dir == LowestVolumeDirection.SHORT
                        && latest.high().compareTo(pos.getCurrentSl()) >= 0) {
                    pos.close(pos.getCurrentSl(), "STOP_LOSS_HIT", Instant.now());
                    finalizeClosedPosition(symbol, pos, "STOP_LOSS_HIT");
                    continue;
                }

                // 2. Check Target 1 (1:2 RR) Partial Booking
                if (!pos.isPartialBooked()) {
                    boolean targetHit =
                            (dir == LowestVolumeDirection.LONG)
                                    ? latest.high().compareTo(pos.getTarget1Price()) >= 0
                                    : latest.low().compareTo(pos.getTarget1Price()) <= 0;

                    if (targetHit) {
                        pos.executePartialBook(pos.getTarget1Price(), Instant.now());
                        LowestVolumeSetup setup = activeSetups.get(symbol);
                        if (setup != null) {
                            setup.transitionTo(
                                    LowestVolumeSetupState.PARTIAL_BOOKED,
                                    "Target 1 Hit. 50% booked, SL to BE.");
                        }
                        log.info(
                                "[LVR] [{}] Target 1 (1:2 RR) Hit! Booked 50%, SL moved to Breakeven ₹{}",
                                symbol, pos.getEntryPrice());
                        if (telegramAlerts) {
                            telegramService.sendLvrPartialBookAlert(pos, pos.getPartialPnl());
                        }
                    }
                }

                // 3. Runner Management via 5-min SuperTrend(10, 3)
                if (pos.isPartialBooked()
                        && pos.getRemainingQuantity() > 0
                        && candles.size() >= 15) {
                    evaluateRunnerSuperTrendExit(symbol, pos, candles, latest);
                }

            } catch (Exception e) {
                log.error("[LVR] Error managing position for {}: {}", symbol, e.getMessage(), e);
            }
        }
    }

    private void evaluateRunnerSuperTrendExit(
            String symbol, LowestVolumePaperPosition pos, List<Candle> candles, Candle latest) {
        int size = candles.size();
        double[] high = new double[size];
        double[] low = new double[size];
        double[] close = new double[size];
        for (int i = 0; i < size; i++) {
            high[i] = candles.get(i).high().doubleValue();
            low[i] = candles.get(i).low().doubleValue();
            close[i] = candles.get(i).close().doubleValue();
        }

        SuperTrendResult[] stSeries =
                taService.calculateSuperTrendSeries(high, low, close, 10, 3.0);
        SuperTrendResult latestSt = stSeries[size - 1];

        if (latestSt != null && !Double.isNaN(latestSt.value())) {
            pos.setTrailingSuperTrendValue(
                    BigDecimal.valueOf(latestSt.value()).setScale(2, RoundingMode.HALF_UP));

            // SuperTrend flip against trade
            boolean flippedAgainst =
                    (pos.getDirection() == LowestVolumeDirection.LONG && !latestSt.isBullish())
                            || (pos.getDirection() == LowestVolumeDirection.SHORT
                                    && latestSt.isBullish());

            if (flippedAgainst) {
                String reason =
                        (pos.getDirection() == LowestVolumeDirection.LONG)
                                ? "SUPERTREND_10_3_FLIP_BEARISH"
                                : "SUPERTREND_10_3_FLIP_BULLISH";
                pos.close(latest.close(), reason, Instant.now());
                finalizeClosedPosition(symbol, pos, reason);
                log.info(
                        "[LVR] [{}] Runner exited on SuperTrend flip @ ₹{} (Reason: {})",
                        symbol,
                        latest.close(),
                        reason);
            }
        }
    }

    private synchronized void finalizeClosedPosition(
            String symbol, LowestVolumePaperPosition pos, String reason) {
        openPositions.remove(symbol);
        tradeHistory.add(pos);

        LowestVolumeSetup setup = activeSetups.get(symbol);
        if (setup != null) {
            setup.transitionTo(LowestVolumeSetupState.CLOSED_SL, reason);
        }

        if (telegramAlerts) {
            telegramService.sendLvrTradeExitAlert(pos, reason);
        }
    }

    /** Executes 15:00 IST Hard Exit: Closes all open positions at market price. */
    public synchronized void executeHardExit(LocalTime nowTime) {
        if (openPositions.isEmpty()) {
            return;
        }

        log.info(
                "[LVR] 15:00 Hard Exit Triggered: Closing {} active paper positions...",
                openPositions.size());

        for (Map.Entry<String, LowestVolumePaperPosition> entry :
                new ArrayList<>(openPositions.entrySet())) {
            String symbol = entry.getKey();
            LowestVolumePaperPosition pos = entry.getValue();

            BigDecimal exitPrice = pos.getEntryPrice();
            try {
                List<Candle> candles = marketDataService.fetch5MinCandles(symbol, 1);
                if (candles != null && !candles.isEmpty()) {
                    exitPrice = candles.get(candles.size() - 1).close();
                }
            } catch (Exception ignored) {
            }

            pos.close(exitPrice, "15:00_HARD_EXIT", Instant.now());
            finalizeClosedPosition(symbol, pos, "15:00_HARD_EXIT");
        }
    }

    /** Resets the strategy state for a fresh trading session (called daily at 09:15). */
    public synchronized void resetDaily() {
        log.info(
                "[LVR] Daily reset invoked. Clearing setups, exhausted stocks, and daily counters.");
        universeScanCompletedToday = false;
        activeSetups.clear();
        openPositions.clear();
        exhaustedSymbols.clear();
        currentTopGainers.clear();
        currentTopLosers.clear();
        currentTopGainerSnapshots.clear();
        currentTopLoserSnapshots.clear();
        tradeCounter.set(1);
    }

    // --- Helper Methods ---

    /** Checks Exhaustion Disqualifier (§3.5) */
    public boolean checkExhaustion(
            String symbol, List<Candle> todayCandles, LowestVolumeSetup setup) {
        if (exhaustedSymbols.contains(symbol)) {
            return true;
        }

        if (!todayCandles.isEmpty()) {
            Candle candle1 = todayCandles.get(0);
            double c1Move =
                    Math.abs(candle1.close().subtract(candle1.open()).doubleValue())
                            / candle1.open().doubleValue();
            if (c1Move >= 0.05) { // >= 5% first candle
                exhaustedSymbols.add(symbol);
                setup.transitionTo(
                        LowestVolumeSetupState.REJECTED_EXHAUSTED,
                        "Exhaustion: Candle 1 move >= 5%");
                log.info(
                        "[LVR] [{}] Exhaustion Disqualified! Candle 1 moved {:.2f}% >= 5%",
                        symbol, c1Move * 100);
                return true;
            }

            if (setup.getInitialLegCandles().size() >= 2) {
                Candle legEnd =
                        setup.getInitialLegCandles().get(setup.getInitialLegCandles().size() - 1);
                double legMoveFromOpen =
                        Math.abs(legEnd.close().subtract(candle1.open()).doubleValue())
                                / candle1.open().doubleValue();
                if (legMoveFromOpen >= 0.06) { // >= 6% cumulative from open
                    exhaustedSymbols.add(symbol);
                    setup.transitionTo(
                            LowestVolumeSetupState.REJECTED_EXHAUSTED,
                            "Exhaustion: Leg move >= 6% from day open");
                    log.info(
                            "[LVR] [{}] Exhaustion Disqualified! Cumulative leg move {:.2f}% >= 6%",
                            symbol, legMoveFromOpen * 100);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isDirectional(Candle c, LowestVolumeDirection dir) {
        return (dir == LowestVolumeDirection.LONG)
                ? c.close().compareTo(c.open()) > 0
                : c.close().compareTo(c.open()) < 0;
    }

    private boolean isOppositeColor(Candle c, LowestVolumeDirection dir) {
        return (dir == LowestVolumeDirection.LONG)
                ? c.close().compareTo(c.open()) < 0
                : c.close().compareTo(c.open()) > 0;
    }

    private BigDecimal getCumulativeMove(Candle start, Candle end, LowestVolumeDirection dir) {
        return (dir == LowestVolumeDirection.LONG)
                ? end.close().subtract(start.open())
                : start.open().subtract(end.close());
    }

    private List<Candle> filterTodayCandles(List<Candle> allCandles) {
        if (allCandles == null || allCandles.isEmpty()) {
            return Collections.emptyList();
        }
        var today = java.time.LocalDate.now(IST);
        return allCandles.stream()
                .filter(c -> c.timestamp().atZone(IST).toLocalDate().equals(today))
                .toList();
    }

    private StockQuoteSnapshot fetchStockSnapshot(String symbol) {
        try {
            String token = marketDataService.resolveToken(symbol);
            JsonNode q = marketDataService.fetchQuote("NSE", token);
            if (q != null && q.has("lp") && q.has("c")) {
                double lp = q.path("lp").asDouble(0.0);
                double c = q.path("c").asDouble(0.0);
                double o = q.path("o").asDouble(lp);
                if (lp > 0 && c > 0) {
                    double pct = ((lp - c) / c) * 100.0;
                    return new StockQuoteSnapshot(symbol, lp, c, o, pct);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public int calculatePositionSize(BigDecimal triggerPrc, BigDecimal slPrc) {
        BigDecimal slDistance = triggerPrc.subtract(slPrc).abs();
        if (slDistance.compareTo(BigDecimal.ZERO) <= 0) {
            return 1;
        }
        double rpt = getRiskPerTradeAmount();
        int qty = (int) (rpt / slDistance.doubleValue());
        return Math.max(1, qty);
    }

    public double getRiskPerTradeAmount() {
        return paperCapital * (riskPerTradePercent / 100.0);
    }

    public List<String> getFnoUniverse() {
        List<String> list = new ArrayList<>(Nifty200Registry.getAllSymbols());
        if (list.isEmpty()) {
            list = StockFnoRegistry.getAllSubscribedSymbols();
        }
        return list;
    }

    // --- Getters & Setters ---

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getPaperCapital() {
        return paperCapital;
    }

    public void setPaperCapital(double paperCapital) {
        this.paperCapital = paperCapital;
    }

    public double getRiskPerTradePercent() {
        return riskPerTradePercent;
    }

    public void setRiskPerTradePercent(double riskPerTradePercent) {
        this.riskPerTradePercent = riskPerTradePercent;
    }

    public int getMaxConcurrentTrades() {
        return maxConcurrentTrades;
    }

    public void setMaxConcurrentTrades(int maxConcurrentTrades) {
        this.maxConcurrentTrades = maxConcurrentTrades;
    }

    public boolean isNiftyBullish() {
        return niftyBullish;
    }

    public void setNiftyBullish(boolean niftyBullish) {
        this.niftyBullish = niftyBullish;
    }

    public List<String> getCurrentTopGainers() {
        return List.copyOf(currentTopGainers);
    }

    public List<String> getCurrentTopLosers() {
        return List.copyOf(currentTopLosers);
    }

    public List<StockQuoteSnapshot> getCurrentTopGainerSnapshots() {
        return List.copyOf(currentTopGainerSnapshots);
    }

    public List<StockQuoteSnapshot> getCurrentTopLoserSnapshots() {
        return List.copyOf(currentTopLoserSnapshots);
    }

    public Map<String, LowestVolumeSetup> getActiveSetups() {
        return Collections.unmodifiableMap(activeSetups);
    }

    public Map<String, LowestVolumePaperPosition> getOpenPositions() {
        return Collections.unmodifiableMap(openPositions);
    }

    public List<LowestVolumePaperPosition> getTradeHistory() {
        return Collections.unmodifiableList(tradeHistory);
    }

    public Set<String> getExhaustedSymbols() {
        return Collections.unmodifiableSet(exhaustedSymbols);
    }

    public int getSetupTimeoutCandles() {
        return setupTimeoutCandles;
    }

    public void setSetupTimeoutCandles(int setupTimeoutCandles) {
        this.setupTimeoutCandles = setupTimeoutCandles;
    }

    public boolean isUniverseScanCompletedToday() {
        return universeScanCompletedToday;
    }

    public void setUniverseScanCompletedToday(boolean universeScanCompletedToday) {
        this.universeScanCompletedToday = universeScanCompletedToday;
    }
}
