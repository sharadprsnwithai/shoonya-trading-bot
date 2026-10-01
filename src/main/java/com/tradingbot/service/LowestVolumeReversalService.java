package com.tradingbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.LowestVolumePaperPosition;
import com.tradingbot.model.strategy.LowestVolumeSectorState;
import com.tradingbot.model.strategy.LowestVolumeSetup;
import com.tradingbot.model.strategy.LowestVolumeSetupState;
import com.tradingbot.model.strategy.LvrExitMode;
import com.tradingbot.model.strategy.LvrInstrumentType;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.NiftySectorRegistry;
import com.tradingbot.util.StockFnoRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Lowest Volume Reversal & Continuation (LVR) Strategy Engine based on Kushal Varshney's 5m
 * Intraday Framework. 1. 09:25 IST: Market Sentiment (NIFTY 50 Adv/Dec) -> 11 NSE Sector ranking ->
 * F&O candidate stocks. 2. 09:30 - 13:00 IST: 5-minute candle engine. Ignored C1-C3 baseline,
 * opposite-color volume dry-up triggers. 3. Execution: Stock Futures Buying (LONG) and Selling
 * (SHORT). 4. Risk Management: 1:2 RR Target (50% partial profit booking), Cost SL floor on runner,
 * 5m 10 EMA dynamic trailing exit, 15:00 IST Hard EOD square-off.
 */
@Service
public class LowestVolumeReversalService {

    private static final Logger log = LoggerFactory.getLogger(LowestVolumeReversalService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public static final LocalTime TIME_SESSION_START = LocalTime.of(9, 15);
    public static final LocalTime TIME_SCANNER_START = LocalTime.of(9, 25);
    public static final LocalTime TIME_SCANNER_CUTOFF = LocalTime.of(10, 0);
    public static final LocalTime TIME_EVALUATION_START = LocalTime.of(9, 30);
    public static final LocalTime TIME_ENTRY_CUTOFF = LocalTime.of(13, 0);
    public static final LocalTime TIME_HARD_EXIT = LocalTime.of(15, 0);

    private final ShoonyaMarketDataService marketDataService;
    private final TechnicalAnalysisService taService;
    private final TelegramService telegramService;
    private final ShoonyaConfig config;
    private final com.tradingbot.marketdata.ShoonyaOptionChainService optionChainService;
    private final LowestVolumeReversalScanner scanner;
    private final com.tradingbot.bus.SignalPublisher signalPublisher;

    private java.time.Clock clock = java.time.Clock.system(IST);

    @Value("${trading-bot.strategy.lowest-volume.enabled:true}")
    private volatile boolean enabled = true;

    @Value("${trading-bot.strategy.lowest-volume.instrument-type:FUTURES}")
    private volatile LvrInstrumentType instrumentType = LvrInstrumentType.FUTURES;

    @Value("${trading-bot.strategy.lowest-volume.exit-mode:PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500}")
    private volatile LvrExitMode exitMode = LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500;

    @Value("${trading-bot.strategy.lowest-volume.paper-capital:1000000.0}")
    private volatile double paperCapital = 1000000.0;

    @Value("${trading-bot.strategy.lowest-volume.risk-per-trade-percent:1.0}")
    private volatile double riskPerTradePercent = 1.0;

    @Value("${trading-bot.strategy.lowest-volume.max-concurrent-trades:5}")
    private volatile int maxConcurrentTrades = 5;

    @Value("${trading-bot.strategy.lowest-volume.max-attempts-per-symbol:1}")
    private volatile int maxAttemptsPerSymbol = 1;

    @Value("${trading-bot.strategy.lowest-volume.min-breadth-pct:56.0}")
    private volatile double minBreadthPct = 56.0;

    @Value("${trading-bot.strategy.lowest-volume.min-sl-pct:0.35}")
    private volatile double minStopLossPct = 0.35;

    @Value("${trading-bot.strategy.lowest-volume.max-daily-loss:15000.0}")
    private volatile double maxDailyLoss = 15000.0;

    @Value("${trading-bot.strategy.lowest-volume.lots:2}")
    private volatile int defaultLots = 2;

    @Value("${trading-bot.strategy.lowest-volume.dynamic-position-sizing:false}")
    private volatile boolean dynamicPositionSizing = false;

    @Value("${trading-bot.strategy.lowest-volume.telegram-alerts:true}")
    private volatile boolean telegramAlerts = true;

    @Value("${trading-bot.strategy.lowest-volume.telegram-armed-alerts:true}")
    private volatile boolean telegramArmedAlerts = true;

    @Value("${trading-bot.strategy.lowest-volume.vwap-confirmation-enabled:true}")
    private volatile boolean vwapConfirmationEnabled = true;

    @Value("${trading-bot.strategy.lowest-volume.opening-15m-range-filter-enabled:true}")
    private volatile boolean opening15mRangeFilterEnabled = true;

    @Value("${trading-bot.strategy.lowest-volume.sector-momentum-filter-enabled:true}")
    private volatile boolean sectorMomentumFilterEnabled = true;

    @Value("${trading-bot.strategy.lowest-volume.setup-timeout-candles:6}")
    private volatile int setupTimeoutCandles = 6;

    @Value("${trading-bot.strategy.lowest-volume.min-active-candidates:2}")
    private volatile int minActiveCandidates = 2;

    @Value("${trading-bot.strategy.lowest-volume.max-slippage-pct:0.12}")
    private volatile double maxSlippagePct = 0.12;

    private volatile long morningScanDelayMs = 115;

    // State maps
    private final Map<String, LowestVolumeSetup> activeSetups = new ConcurrentHashMap<>();
    private final Map<String, LowestVolumePaperPosition> openPositions = new ConcurrentHashMap<>();
    private final List<LowestVolumePaperPosition> tradeHistory =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Set<String> exhaustedSymbols = ConcurrentHashMap.newKeySet();
    private final List<String> candidateReservoir =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile LocalTime lastMidMorningRefreshTime = null;
    private volatile LocalDate lastScanDate = null;
    private final java.util.concurrent.atomic.AtomicBoolean isCycleRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final List<String> currentTopGainers =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<String> currentTopLosers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<StockQuoteSnapshot> currentTopGainerSnapshots =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<StockQuoteSnapshot> currentTopLoserSnapshots =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private final Map<String, Instant> sectorRejectionAlertCooldown = new ConcurrentHashMap<>();

    private volatile LowestVolumeSectorState sectorState = LowestVolumeSectorState.empty();
    private volatile boolean niftyBullish = true;
    private volatile boolean universeScanCompletedToday = false;
    private final AtomicInteger tradeCounter = new AtomicInteger(1);
    private final java.util.concurrent.atomic.AtomicBoolean dailyCircuitBreakerAlertSent =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean isScanning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Autowired
    public LowestVolumeReversalService(
            ShoonyaMarketDataService marketDataService,
            TechnicalAnalysisService taService,
            TelegramService telegramService,
            ShoonyaConfig config,
            @Autowired(required = false)
                    com.tradingbot.marketdata.ShoonyaOptionChainService optionChainService,
            @Autowired(required = false) LowestVolumeReversalScanner scanner,
            @Autowired(required = false) com.tradingbot.bus.SignalPublisher signalPublisher) {
        this.marketDataService = marketDataService;
        this.taService = taService;
        this.telegramService = telegramService;
        this.config = config;
        this.optionChainService = optionChainService;
        this.scanner = (scanner != null) ? scanner : new LowestVolumeReversalScanner();
        this.signalPublisher = signalPublisher;
    }

    public LowestVolumeReversalService(
            ShoonyaMarketDataService marketDataService,
            TechnicalAnalysisService taService,
            TelegramService telegramService,
            ShoonyaConfig config,
            LowestVolumeReversalScanner scanner) {
        this(marketDataService, taService, telegramService, config, null, scanner, null);
    }

    /**
     * Executes one complete scanning and strategy evaluation cycle. Called every 5 minutes on
     * candle close during market hours.
     */
    public void runCycle() {
        if (!isCycleRunning.compareAndSet(false, true)) {
            log.warn("[LVR] Previous 5-minute cycle still executing. Skipping concurrent run.");
            return;
        }
        try {
            LocalDate today = LocalDate.now(clock);
            if (lastScanDate != null && !today.equals(lastScanDate)) {
                log.info(
                        "[LVR] New trading day detected ({} vs last {}). Resetting daily state.",
                        today,
                        lastScanDate);
                resetDaily();
            }

            if (!enabled) {
                log.debug("[LVR] Strategy is currently disabled.");
                return;
            }

            LocalTime nowTime = LocalTime.now(clock);

            if (nowTime.isBefore(TIME_SESSION_START)) {
                log.debug("[LVR] Before market open (09:15 IST). Standing by.");
                return;
            }

            if (!nowTime.isBefore(TIME_HARD_EXIT)) {
                executeHardExit(nowTime);
                return;
            }

            if (nowTime.isBefore(TIME_SCANNER_START)) {
                log.info("[LVR] 09:15 - 09:25 IST: Pre-scanner settlement window. No trades.");
                return;
            }

            if (!universeScanCompletedToday) {
                if (nowTime.isAfter(TIME_SCANNER_CUTOFF)) {
                    log.info(
                            "[LVR] Past 10:00 AM fallback cutoff. No valid morning candidates found"
                                    + " today. Standing down.");
                    return;
                }
                log.info("[LVR] Triggering 09:25 AM morning sentiment & sector scan...");
                runMorningUniverseScan();
                if (!universeScanCompletedToday) {
                    log.info("[LVR] Morning scan pending valid sector candidates. Will retry.");
                    return;
                }
            }

            if (nowTime.isAfter(TIME_ENTRY_CUTOFF)) {
                log.info(
                        "[LVR] Past 13:00 cutoff. Skipping new setups; managing open positions only.");
                evaluateOpenPositions(nowTime);
                return;
            }

            // Replenish candidate setups from standby reservoir if active candidates dropped below
            // threshold
            replenishActiveCandidatesIfNeeded(nowTime);

            // If 0 actionable setups and past 10:30, trigger mid-morning refresh (paced at max once
            // per
            // 30 mins)
            if (countActionableSetups() == 0
                    && nowTime.isAfter(LocalTime.of(10, 30))
                    && openPositions.size() < maxConcurrentTrades
                    && (lastMidMorningRefreshTime == null
                            || nowTime.isAfter(lastMidMorningRefreshTime.plusMinutes(30)))) {
                runMidMorningUniverseRefresh(nowTime);
            }

            log.info("[LVR] Executing 5-min strategy cycle at {} IST...", nowTime);
            processCandidateSetups(nowTime);
            evaluateOpenPositions(nowTime, null, true);
        } finally {
            isCycleRunning.set(false);
        }
    }

    /**
     * 09:25 AM Morning Scan: 1. Evaluates NIFTY 50 Adv/Dec sentiment. 2. Ranks 11 NSE Sectors by %
     * change. 3. Selects top winning sector and filters clean F&O candidate stocks.
     */
    public void runMorningUniverseScan() {
        if (!enabled) return;
        if (!isScanning.compareAndSet(false, true)) {
            log.info("[LVR] Morning scan already in progress. Skipping concurrent execution.");
            return;
        }

        LocalTime nowTime = LocalTime.now(clock);
        log.info("[LVR] Running Morning Sentiment & Sector Scan at {} IST...", nowTime);

        if (marketDataService == null) {
            log.warn("[LVR] MarketDataService not configured (mock/test mode).");
            isScanning.set(false);
            return;
        }

        try {
            // 1. Fetch Unified Morning Quotes (Deduplicated NIFTY 50 + Sector Constituents, Paced)
            Map<String, StockQuoteSnapshot> universeQuotes = fetchMorningQuotesUnified();
            if (universeQuotes.isEmpty()) {
                log.warn("[LVR] No universe quotes fetched. Morning scan aborted.");
                this.universeScanCompletedToday = false;
                if (telegramAlerts && telegramService != null) {
                    telegramService.sendLvrScanRetryAlert(
                            this.niftyBullish, nowTime.plusMinutes(5), 0);
                }
                return;
            }

            // 2. Evaluate NIFTY 50 Sentiment
            List<StockQuoteSnapshot> niftyQuotes = new ArrayList<>();
            for (String sym : NiftySectorRegistry.NIFTY_50_CONSTITUENTS) {
                StockQuoteSnapshot q = universeQuotes.get(sym);
                if (q != null) {
                    niftyQuotes.add(q);
                }
            }

            if (niftyQuotes.size() < 35) {
                log.warn(
                        "[LVR] Insufficient NIFTY 50 quote coverage ({}/50 quotes received, minimum 35 required). Retrying on next cycle.",
                        niftyQuotes.size());
                this.universeScanCompletedToday = false;
                if (telegramAlerts && telegramService != null) {
                    telegramService.sendLvrScanRetryAlert(
                            this.niftyBullish, nowTime.plusMinutes(5), 0);
                }
                return;
            }

            LowestVolumeDirection sentiment =
                    scanner.evaluateMarketSentiment(niftyQuotes, minBreadthPct);
            if (sentiment == LowestVolumeDirection.NONE) {
                log.info(
                        "[LVR] Market sentiment is NEUTRAL/MIXED (below {}% breadth threshold on {}/50 quotes). Standing down for the day to avoid whipsaws.",
                        minBreadthPct, niftyQuotes.size());
                this.universeScanCompletedToday = true;
                this.lastScanDate = LocalDate.now(clock);
                if (telegramAlerts && telegramService != null) {
                    telegramService.sendTextMessage(
                            String.format(
                                    "⚠️ *LVR Morning Scan: Market Sentiment Neutral/Mixed*\n"
                                            + "• NIFTY 50 Advance/Decline breadth did not meet the %.0f%% directional threshold (%d quotes analyzed).\n"
                                            + "• Status: *Standing down today* to avoid choppy false breakouts.",
                                    minBreadthPct, niftyQuotes.size()));
                }
                return;
            }
            this.niftyBullish = (sentiment == LowestVolumeDirection.LONG);

            // 3. Map sector quotes from the unified map
            Map<String, List<StockQuoteSnapshot>> sectorQuotes = new HashMap<>();
            for (Map.Entry<String, List<String>> entry :
                    NiftySectorRegistry.getSectorConstituents().entrySet()) {
                String sectorName = entry.getKey();
                List<StockQuoteSnapshot> sQuotes = new ArrayList<>();
                for (String sym : entry.getValue()) {
                    StockQuoteSnapshot q = universeQuotes.get(sym);
                    if (q != null) {
                        sQuotes.add(q);
                    }
                }
                if (!sQuotes.isEmpty()) {
                    sectorQuotes.put(sectorName, sQuotes);
                }
            }

            List<LowestVolumeReversalScanner.SectorRankResult> rankedSectors =
                    scanner.rankSectors(sectorQuotes, sentiment);

            if (rankedSectors.isEmpty()) {
                log.warn("[LVR] No sectors ranked from quotes.");
                this.universeScanCompletedToday = false;
                if (telegramAlerts && telegramService != null) {
                    telegramService.sendLvrScanRetryAlert(
                            this.niftyBullish, nowTime.plusMinutes(5), 0);
                }
                return;
            }

            // Iterate through ranked sectors to find the top sector with qualifying candidate
            // stocks
            LowestVolumeReversalScanner.SectorRankResult winningSector = null;
            List<StockQuoteSnapshot> winningSectorStockQuotes = Collections.emptyList();
            List<String> candidateStocks = Collections.emptyList();

            for (LowestVolumeReversalScanner.SectorRankResult sector : rankedSectors) {
                List<StockQuoteSnapshot> sQuotes =
                        sectorQuotes.getOrDefault(sector.sectorName(), Collections.emptyList());
                List<String> candidates = scanner.filterCandidateStocks(sQuotes, sentiment);
                if (!candidates.isEmpty()) {
                    winningSector = sector;
                    winningSectorStockQuotes = sQuotes;
                    candidateStocks = candidates;
                    break;
                }
            }

            if (winningSector == null || candidateStocks.isEmpty()) {
                log.warn("[LVR] No candidate stocks qualified across any sector.");
                this.universeScanCompletedToday = false;
                if (telegramAlerts && telegramService != null) {
                    telegramService.sendLvrScanRetryAlert(
                            this.niftyBullish, nowTime.plusMinutes(5), 0);
                }
                return;
            }

            this.sectorState =
                    new LowestVolumeSectorState(
                            (int) niftyQuotes.stream().filter(q -> q.pctChange() > 0).count(),
                            (int) niftyQuotes.stream().filter(q -> q.pctChange() < 0).count(),
                            sentiment,
                            winningSector.sectorName(),
                            winningSector.pctChange(),
                            candidateStocks);

            log.info(
                    "[LVR] Morning Scan Result: Sentiment={}, Winning Sector={} ({}%), Candidates={}",
                    sentiment,
                    winningSector.sectorName(),
                    winningSector.pctChange(),
                    candidateStocks);

            // Populate active setups
            activeSetups.clear();
            for (String symbol : candidateStocks) {
                activeSetups.put(symbol, new LowestVolumeSetup(symbol, sentiment));
            }

            Set<String> candidateSet = new java.util.HashSet<>(candidateStocks);

            currentTopGainers.clear();
            currentTopLosers.clear();
            currentTopGainerSnapshots.clear();
            currentTopLoserSnapshots.clear();

            if (sentiment == LowestVolumeDirection.LONG) {
                currentTopGainers.addAll(candidateStocks);
                currentTopGainerSnapshots.addAll(
                        winningSectorStockQuotes.stream()
                                .filter(q -> candidateSet.contains(q.symbol()))
                                .toList());
            } else {
                currentTopLosers.addAll(candidateStocks);
                currentTopLoserSnapshots.addAll(
                        winningSectorStockQuotes.stream()
                                .filter(q -> candidateSet.contains(q.symbol()))
                                .toList());
            }

            // Populate Candidate Reservoir from reserve leading sectors
            candidateReservoir.clear();
            for (LowestVolumeReversalScanner.SectorRankResult sector : rankedSectors) {
                if (winningSector != null
                        && sector.sectorName().equals(winningSector.sectorName())) {
                    continue;
                }
                List<StockQuoteSnapshot> sQuotes =
                        sectorQuotes.getOrDefault(sector.sectorName(), Collections.emptyList());
                List<String> reserveCandidates = scanner.filterCandidateStocks(sQuotes, sentiment);
                for (String sym : reserveCandidates) {
                    if (!activeSetups.containsKey(sym)
                            && !exhaustedSymbols.contains(sym)
                            && !candidateReservoir.contains(sym)) {
                        candidateReservoir.add(sym);
                    }
                }
            }
            log.info(
                    "[LVR] Candidate Reservoir populated with {} reserve stocks: {}",
                    candidateReservoir.size(),
                    candidateReservoir);

            this.universeScanCompletedToday = true;
            this.lastScanDate = LocalDate.now(clock);

            if (telegramAlerts && telegramService != null) {
                telegramService.sendTextMessage(
                        String.format(
                                "📊 *LVR 09:25 AM Morning Scan*\n"
                                        + "• Sentiment: *%s*\n"
                                        + "• Winning Sector: *%s* (%.2f%%)\n"
                                        + "• Candidates (%d): `%s`\n"
                                        + "• Setup Mode: *5m Lowest Volume Pullback*",
                                sentiment,
                                winningSector.sectorName(),
                                winningSector.pctChange(),
                                candidateStocks.size(),
                                String.join(", ", candidateStocks)));
            }
        } catch (Exception e) {
            log.error("[LVR] Error during morning universe scan: {}", e.getMessage(), e);
        } finally {
            isScanning.set(false);
        }
    }

    /**
     * Pure 5-minute candle sequence evaluation (Kushal Varshney rules). 1. Ignore Candles 1, 2, 3
     * for entry. 2. dayLowestVolume = min(Vol_C1, Vol_C2, Vol_C3). 3. For C4+: If opposite color
     * candle and Vol < dayLowestVolume -> ARM TRIGGER & DYNAMIC TRAILING.
     */
    public LowestVolumeSetup evaluateCandleSequence(
            String symbol, LowestVolumeDirection direction, List<Candle> candles) {
        return evaluateCandleSequence(symbol, direction, candles, null);
    }

    public LowestVolumeSetup evaluateCandleSequence(
            String symbol,
            LowestVolumeDirection direction,
            List<Candle> candles,
            Instant filterAfter) {
        LowestVolumeSetup setup = new LowestVolumeSetup(symbol, direction);
        if (candles == null || candles.size() < 3) {
            return setup;
        }

        // Baseline volume from first 3 candles (ignoring zero-volume ticks)
        long baselineLowest = Long.MAX_VALUE;
        BigDecimal first15mHigh = null;
        BigDecimal first15mLow = null;

        for (int k = 0; k < 3 && k < candles.size(); k++) {
            Candle c = candles.get(k);
            long v = c.volume();
            if (v > 0) {
                baselineLowest = Math.min(baselineLowest, v);
            }
            if (first15mHigh == null || c.high().compareTo(first15mHigh) > 0) {
                first15mHigh = c.high();
            }
            if (first15mLow == null || c.low().compareTo(first15mLow) < 0) {
                first15mLow = c.low();
            }
        }
        if (baselineLowest == Long.MAX_VALUE && !candles.isEmpty()) {
            baselineLowest = candles.get(0).volume();
        }
        setup.setDayLowestVolume(baselineLowest);
        setup.setFirst15MinHigh(first15mHigh);
        setup.setFirst15MinLow(first15mLow);

        // If fewer than 4 candles, scanning only (no setup on C1-C3)
        if (candles.size() < 4) {
            return setup;
        }

        long rollingLowest = baselineLowest;

        for (int i = 3; i < candles.size(); i++) {
            Candle c = candles.get(i);
            boolean isOppositeCandle =
                    (direction == LowestVolumeDirection.SHORT) ? c.isGreen() : c.isRed();

            // 1. Invalidate or expire currently armed trigger if breached or timed out
            if (setup.getState() == LowestVolumeSetupState.TRIGGER_ARMED) {
                boolean slBroken = false;
                if (direction == LowestVolumeDirection.SHORT) {
                    if (c.high().compareTo(setup.getStopLossPrice()) >= 0) {
                        slBroken = true;
                    }
                } else if (direction == LowestVolumeDirection.LONG) {
                    if (c.low().compareTo(setup.getStopLossPrice()) <= 0) {
                        slBroken = true;
                    }
                }

                boolean targetPassed = false;
                if (setup.getTarget1Price() != null) {
                    if (direction == LowestVolumeDirection.SHORT
                            && c.low().compareTo(setup.getTarget1Price()) <= 0) {
                        targetPassed = true;
                    } else if (direction == LowestVolumeDirection.LONG
                            && c.high().compareTo(setup.getTarget1Price()) >= 0) {
                        targetPassed = true;
                    }
                }

                setup.incrementArmedTimeout();
                boolean timedOut =
                        (setupTimeoutCandles > 0
                                && setup.getArmedCandlesElapsed() >= setupTimeoutCandles);

                if (slBroken || targetPassed || timedOut) {
                    setup.resetToScanning();
                }
            }

            // 2. Arm or trail trigger if candle is an opposite-color candle with lower volume
            if (isOppositeCandle && c.volume() > 0 && c.volume() <= rollingLowest) {
                // Candle is allowed if its 5-minute bar close time (timestamp + 300s) is after the
                // previous exit time
                boolean candleAllowed =
                        (filterAfter == null
                                || (c.timestamp() != null
                                        && c.timestamp().plusSeconds(300).isAfter(filterAfter)));
                if (candleAllowed) {
                    // Armed trigger / Trail trigger
                    BigDecimal triggerPrc;
                    BigDecimal slPrc;
                    BigDecimal target1Prc;

                    if (direction == LowestVolumeDirection.SHORT) {
                        triggerPrc = roundToTick(c.low().subtract(BigDecimal.valueOf(0.05)));
                        BigDecimal rawSl = c.high().add(BigDecimal.valueOf(0.05));
                        BigDecimal minRisk =
                                triggerPrc.multiply(BigDecimal.valueOf(minStopLossPct / 100.0));
                        BigDecimal risk = rawSl.subtract(triggerPrc).max(minRisk);
                        slPrc = roundToTick(triggerPrc.add(risk));
                        target1Prc =
                                roundToTick(
                                        triggerPrc.subtract(
                                                risk.multiply(BigDecimal.valueOf(2)))); // 1:2 RR
                    } else {
                        triggerPrc = roundToTick(c.high().add(BigDecimal.valueOf(0.05)));
                        BigDecimal rawSl = c.low().subtract(BigDecimal.valueOf(0.05));
                        BigDecimal minRisk =
                                triggerPrc.multiply(BigDecimal.valueOf(minStopLossPct / 100.0));
                        BigDecimal risk = triggerPrc.subtract(rawSl).max(minRisk);
                        slPrc = roundToTick(triggerPrc.subtract(risk));
                        target1Prc =
                                roundToTick(
                                        triggerPrc.add(
                                                risk.multiply(BigDecimal.valueOf(2)))); // 1:2 RR
                    }

                    setup.setTriggerCandle(c, triggerPrc, slPrc, target1Prc);
                }
                rollingLowest = c.volume();
                setup.setDayLowestVolume(rollingLowest);
            } else if (c.volume() > 0 && c.volume() < rollingLowest) {
                rollingLowest = c.volume();
                setup.setDayLowestVolume(rollingLowest);
            }
        }

        return setup;
    }

    /** Processes 5-minute candles for all active watchlist candidate stocks. */
    private void processCandidateSetups(LocalTime nowTime) {
        if (marketDataService == null) return;

        for (Map.Entry<String, LowestVolumeSetup> entry : activeSetups.entrySet()) {
            String symbol = entry.getKey();
            LowestVolumeSetup setup = entry.getValue();

            if (setup.getState() == LowestVolumeSetupState.IN_POSITION
                    || setup.getState() == LowestVolumeSetupState.PARTIAL_BOOKED
                    || setup.getState() == LowestVolumeSetupState.CLOSED_TRAIL_EXIT
                    || setup.getState() == LowestVolumeSetupState.CLOSED_TARGET
                    || setup.getState() == LowestVolumeSetupState.CLOSED_SL
                    || setup.getState() == LowestVolumeSetupState.REJECTED_EXHAUSTED
                    || setup.getTradeAttempts() >= maxAttemptsPerSymbol
                    || exhaustedSymbols.contains(symbol)) {
                continue;
            }

            try {
                List<Candle> rawCandles = marketDataService.fetch5MinCandles(symbol, 5);
                if (rawCandles == null || rawCandles.isEmpty()) continue;

                LocalDate today = LocalDate.now(clock);
                Instant nowInst = Instant.now(clock);
                List<Candle> candles =
                        rawCandles.stream()
                                .filter(
                                        c ->
                                                c.timestamp() != null
                                                        && LocalDate.ofInstant(c.timestamp(), IST)
                                                                .equals(today))
                                .filter(c -> c.timestamp().plusSeconds(300).compareTo(nowInst) <= 0)
                                .toList();
                if (candles.size() < 3) continue;

                LowestVolumeSetup evaluated =
                        evaluateCandleSequence(
                                symbol, setup.getDirection(), candles, setup.getLastExitTime());

                if (taService != null && !candles.isEmpty()) {
                    double[] vwapSeries = taService.calculateVwapSeries(candles);
                    if (vwapSeries.length > 0 && !Double.isNaN(vwapSeries[vwapSeries.length - 1])) {
                        setup.setLatestVwap(vwapSeries[vwapSeries.length - 1]);
                    }
                }

                if (setup.getState() == LowestVolumeSetupState.IN_POSITION
                        || setup.getState() == LowestVolumeSetupState.PARTIAL_BOOKED
                        || openPositions.containsKey(symbol)) {
                    continue;
                }

                if (evaluated.getState() == LowestVolumeSetupState.TRIGGER_ARMED) {
                    boolean newlyArmedOrTrailed =
                            (setup.getTriggerPrice() == null
                                    || setup.getTriggerPrice()
                                                    .compareTo(evaluated.getTriggerPrice())
                                            != 0);
                    if (newlyArmedOrTrailed) {
                        setup.setTriggerCandle(
                                evaluated.getTriggerCandle(),
                                evaluated.getTriggerPrice(),
                                evaluated.getStopLossPrice(),
                                evaluated.getTarget1Price());
                    } else {
                        setup.incrementArmedTimeout();
                        if (setupTimeoutCandles > 0
                                && setup.getArmedCandlesElapsed() > setupTimeoutCandles) {
                            log.info(
                                    "[LVR] Setup for {} expired after {} armed candles. Resetting to SCANNING.",
                                    symbol,
                                    setup.getArmedCandlesElapsed());
                            setup.resetToScanning();
                        }
                    }
                    setup.setDayLowestVolume(evaluated.getDayLowestVolume());
                    setup.setFirst15MinHigh(evaluated.getFirst15MinHigh());
                    setup.setFirst15MinLow(evaluated.getFirst15MinLow());

                    if (setup.getState() == LowestVolumeSetupState.TRIGGER_ARMED) {
                        log.info(
                                "[LVR] Setup ARMED for {}: Dir={}, Trigger={}, SL={}, Target1={}",
                                symbol,
                                setup.getDirection(),
                                setup.getTriggerPrice(),
                                setup.getStopLossPrice(),
                                setup.getTarget1Price());

                        if (telegramAlerts
                                && telegramArmedAlerts
                                && telegramService != null
                                && newlyArmedOrTrailed) {
                            telegramService.sendTextMessage(
                                    String.format(
                                            Locale.US,
                                            "⚡ *LVR Setup Armed / Order Trailed*\n"
                                                    + "• Symbol: *%s* (%s)\n"
                                                    + "• 5m Pullback Vol: `%d` (<= day lowest `%d`)\n"
                                                    + "• Trigger Price: `₹%.2f`\n"
                                                    + "• Spot SL: `₹%.2f` | 1:2 Target: `₹%.2f`",
                                            symbol,
                                            setup.getDirection(),
                                            evaluated.getTriggerCandle() != null
                                                    ? evaluated.getTriggerCandle().volume()
                                                    : 0,
                                            evaluated.getDayLowestVolume(),
                                            setup.getTriggerPrice().doubleValue(),
                                            setup.getStopLossPrice().doubleValue(),
                                            setup.getTarget1Price().doubleValue()));
                        }
                    }
                } else if (setup.getState() == LowestVolumeSetupState.TRIGGER_ARMED) {
                    log.info(
                            "[LVR] Armed trigger for {} invalidated or expired on 5m candle close. Resetting setup to SCANNING.",
                            symbol);
                    setup.resetToScanning();
                }
            } catch (Exception e) {
                log.error("[LVR] Error processing 5m candles for {}: {}", symbol, e.getMessage());
            }
        }
    }

    /**
     * 30-second live check: Monitors spot price breaches for Armed Triggers, SL, and 1:4 Target.
     */
    public void evaluateLivePriceActions() {
        if (!enabled || marketDataService == null) return;

        LocalTime nowTime = LocalTime.now(clock);
        if (nowTime.isBefore(TIME_SCANNER_START) || !nowTime.isBefore(TIME_HARD_EXIT)) return;

        Map<String, JsonNode> liveQuoteCache = new HashMap<>();

        // 1. Check Armed Triggers (Only allowed strictly before 13:00 IST Entry Cutoff, if strategy
        // enabled, and if
        // circuit breaker not tripped)
        if (enabled
                && nowTime.isBefore(TIME_ENTRY_CUTOFF)
                && !isDailyCircuitBreakerTripped(liveQuoteCache)) {
            for (Map.Entry<String, LowestVolumeSetup> entry : activeSetups.entrySet()) {
                String symbol = entry.getKey();
                LowestVolumeSetup setup = entry.getValue();

                if (setup.getState() == LowestVolumeSetupState.TRIGGER_ARMED
                        && setup.getTradeAttempts() < maxAttemptsPerSymbol
                        && openPositions.size() < maxConcurrentTrades) {
                    JsonNode quoteNode =
                            liveQuoteCache.computeIfAbsent(symbol, this::fetchLiveQuoteNode);
                    checkSpotTriggerBreach(symbol, setup, quoteNode);
                }
            }
        }

        // 2. Check Open Positions for Spot SL & 1:2 Target (Intra-candle check: isCandleClose =
        // false)
        evaluateOpenPositions(nowTime, liveQuoteCache, false);
    }

    /**
     * Checks if the live spot price has breached the armed trigger level to enter ATM option trade.
     */
    void checkSpotTriggerBreach(String symbol, LowestVolumeSetup setup) {
        checkSpotTriggerBreach(symbol, setup, null);
    }

    void checkSpotTriggerBreach(String symbol, LowestVolumeSetup setup, JsonNode quoteNode) {
        try {
            if (quoteNode == null) {
                quoteNode = fetchLiveQuoteNode(symbol);
            }
            double spotLtp =
                    (quoteNode != null && quoteNode.has("lp"))
                            ? quoteNode.get("lp").asDouble(0.0)
                            : 0.0;
            if (spotLtp <= 0) return;

            BigDecimal spotPrice = BigDecimal.valueOf(spotLtp);
            double highPrc =
                    (quoteNode != null && quoteNode.has("h"))
                            ? quoteNode.get("h").asDouble(0.0)
                            : 0.0;
            double lowPrc =
                    (quoteNode != null && quoteNode.has("l"))
                            ? quoteNode.get("l").asDouble(0.0)
                            : 0.0;

            // Invalidate setup if spot breaches the proposed stop loss before hitting the entry
            // trigger
            if (setup.getStopLossPrice() != null) {
                boolean slBreached = false;
                if (setup.getDirection() == LowestVolumeDirection.SHORT) {
                    if (spotPrice.compareTo(setup.getStopLossPrice()) >= 0
                            || (highPrc > 0.0
                                    && BigDecimal.valueOf(highPrc)
                                                    .compareTo(setup.getStopLossPrice())
                                            >= 0)) {
                        slBreached = true;
                    }
                } else if (setup.getDirection() == LowestVolumeDirection.LONG) {
                    if (spotPrice.compareTo(setup.getStopLossPrice()) <= 0
                            || (lowPrc > 0.0
                                    && BigDecimal.valueOf(lowPrc)
                                                    .compareTo(setup.getStopLossPrice())
                                            <= 0)) {
                        slBreached = true;
                    }
                }

                if (slBreached) {
                    log.info(
                            "[LVR] Setup for {} invalidated prior to entry: Spot {} breached proposed SL {}. Resetting to SCANNING.",
                            symbol,
                            spotPrice,
                            setup.getStopLossPrice());
                    setup.resetToScanning();
                    return;
                }
            }

            // Invalidate setup if spot already reached/passed 1:4 target before entry
            if (setup.getTarget1Price() != null) {
                boolean targetPassed = false;
                if (setup.getDirection() == LowestVolumeDirection.SHORT) {
                    if (spotPrice.compareTo(setup.getTarget1Price()) <= 0
                            || (lowPrc > 0.0
                                    && BigDecimal.valueOf(lowPrc).compareTo(setup.getTarget1Price())
                                            <= 0)) {
                        targetPassed = true;
                    }
                } else if (setup.getDirection() == LowestVolumeDirection.LONG) {
                    if (spotPrice.compareTo(setup.getTarget1Price()) >= 0
                            || (highPrc > 0.0
                                    && BigDecimal.valueOf(highPrc)
                                                    .compareTo(setup.getTarget1Price())
                                            >= 0)) {
                        targetPassed = true;
                    }
                }

                if (targetPassed) {
                    log.info(
                            "[LVR] Setup for {} expired prior to entry: Spot {} already passed Target1 {}. Resetting to SCANNING.",
                            symbol,
                            spotPrice,
                            setup.getTarget1Price());
                    setup.resetToScanning();
                    return;
                }
            }

            boolean triggered = false;

            if (setup.getDirection() == LowestVolumeDirection.SHORT) {
                if (spotPrice.compareTo(setup.getTriggerPrice()) <= 0
                        || (lowPrc > 0.0
                                && BigDecimal.valueOf(lowPrc).compareTo(setup.getTriggerPrice())
                                        <= 0)) {
                    triggered = true;
                }
            } else if (setup.getDirection() == LowestVolumeDirection.LONG) {
                if (spotPrice.compareTo(setup.getTriggerPrice()) >= 0
                        || (highPrc > 0.0
                                && BigDecimal.valueOf(highPrc).compareTo(setup.getTriggerPrice())
                                        >= 0)) {
                    triggered = true;
                }
            }

            if (triggered) {
                // Slippage Guard Check: Reject entry if spot gapped/slipped past allowable
                // tolerance
                if (maxSlippagePct > 0.0) {
                    boolean excessiveSlippage = false;
                    if (setup.getDirection() == LowestVolumeDirection.LONG) {
                        BigDecimal maxAllowed =
                                setup.getTriggerPrice()
                                        .multiply(
                                                BigDecimal.valueOf(1.0 + (maxSlippagePct / 100.0)));
                        if (spotPrice.compareTo(maxAllowed) > 0) {
                            excessiveSlippage = true;
                        }
                    } else if (setup.getDirection() == LowestVolumeDirection.SHORT) {
                        BigDecimal minAllowed =
                                setup.getTriggerPrice()
                                        .multiply(
                                                BigDecimal.valueOf(1.0 - (maxSlippagePct / 100.0)));
                        if (spotPrice.compareTo(minAllowed) < 0) {
                            excessiveSlippage = true;
                        }
                    }

                    if (excessiveSlippage) {
                        log.warn(
                                "[LVR] Trigger breach for {} skipped due to excessive slippage:"
                                        + " Spot {} exceeds {}% threshold from trigger {}.",
                                symbol, spotPrice, maxSlippagePct, setup.getTriggerPrice());
                        return;
                    }
                }

                // VWAP Confirmation Check
                if (vwapConfirmationEnabled) {
                    double vwap =
                            (quoteNode != null && quoteNode.has("ap"))
                                    ? quoteNode.get("ap").asDouble(0.0)
                                    : 0.0;
                    if (vwap <= 0.0 && setup.getLatestVwap() != null) {
                        vwap = setup.getLatestVwap();
                    }
                    if (vwap <= 0.0) {
                        vwap = fetchLiveVwap(symbol, quoteNode);
                    }

                    if (vwap > 0.0) {
                        boolean vwapConfirmed = false;
                        if (setup.getDirection() == LowestVolumeDirection.LONG) {
                            vwapConfirmed = (spotPrice.compareTo(BigDecimal.valueOf(vwap)) > 0);
                        } else if (setup.getDirection() == LowestVolumeDirection.SHORT) {
                            vwapConfirmed = (spotPrice.compareTo(BigDecimal.valueOf(vwap)) < 0);
                        }

                        if (!vwapConfirmed) {
                            log.info(
                                    "[LVR] Setup for {} REJECTED/EXHAUSTED: Spot {} failed VWAP confirmation ({}) for {} direction. Stock discarded for the day.",
                                    symbol,
                                    spotPrice,
                                    vwap,
                                    setup.getDirection());
                            setup.transitionTo(
                                    LowestVolumeSetupState.REJECTED_EXHAUSTED,
                                    String.format(
                                            "Spot %.2f failed VWAP %.2f confirmation for %s",
                                            spotPrice.doubleValue(), vwap, setup.getDirection()));
                            exhaustedSymbols.add(symbol);

                            if (telegramAlerts && telegramService != null) {
                                telegramService.sendTextMessage(
                                        String.format(
                                                "⚠️ *LVR Trade Discarded (VWAP Filter)*\n"
                                                        + "• Symbol: *%s* (%s)\n"
                                                        + "• Spot Price: `₹%.2f`\n"
                                                        + "• VWAP: `₹%.2f`\n"
                                                        + "• Reason: *Spot %s VWAP* (Must be %s"
                                                        + " VWAP)\n"
                                                        + "• Status: *Stock discarded for the day*",
                                                symbol,
                                                setup.getDirection(),
                                                spotPrice.doubleValue(),
                                                vwap,
                                                (setup.getDirection() == LowestVolumeDirection.LONG
                                                        ? "<= "
                                                        : ">= "),
                                                (setup.getDirection() == LowestVolumeDirection.LONG
                                                        ? "Above (>)"
                                                        : "Below (<)")));
                            }
                            return;
                        }
                    } else {
                        log.warn(
                                "[LVR] VWAP confirmation enabled but no valid VWAP available for {}. Skipping entry (fail-closed).",
                                symbol);
                        return;
                    }
                }

                // Opening 15-Minute Range Breakout Filter Check
                if (opening15mRangeFilterEnabled
                        && setup.getFirst15MinHigh() != null
                        && setup.getFirst15MinLow() != null) {
                    boolean rangeConfirmed = false;
                    if (setup.getDirection() == LowestVolumeDirection.LONG) {
                        rangeConfirmed = (spotPrice.compareTo(setup.getFirst15MinHigh()) > 0);
                    } else if (setup.getDirection() == LowestVolumeDirection.SHORT) {
                        rangeConfirmed = (spotPrice.compareTo(setup.getFirst15MinLow()) < 0);
                    }

                    if (!rangeConfirmed) {
                        log.info(
                                "[LVR] Setup for {} REJECTED/EXHAUSTED: Spot {} inside opening 15-min range [{} - {}] for {} direction. Stock discarded for the day.",
                                symbol,
                                spotPrice,
                                setup.getFirst15MinLow(),
                                setup.getFirst15MinHigh(),
                                setup.getDirection());
                        setup.transitionTo(
                                LowestVolumeSetupState.REJECTED_EXHAUSTED,
                                String.format(
                                        "Spot %.2f inside 15-min range [%.2f - %.2f] for %s",
                                        spotPrice.doubleValue(),
                                        setup.getFirst15MinLow().doubleValue(),
                                        setup.getFirst15MinHigh().doubleValue(),
                                        setup.getDirection()));
                        exhaustedSymbols.add(symbol);

                        if (telegramAlerts && telegramService != null) {
                            telegramService.sendTextMessage(
                                    String.format(
                                            "⚠️ *LVR Trade Discarded (15-Min Range Filter)*\n"
                                                    + "• Symbol: *%s* (%s)\n"
                                                    + "• Spot Price: `₹%.2f`\n"
                                                    + "• 15-Min Range: `₹%.2f - ₹%.2f`\n"
                                                    + "• Reason: *Spot trapped inside 15-min range* (Must break %s)\n"
                                                    + "• Status: *Stock discarded for the day*",
                                            symbol,
                                            setup.getDirection(),
                                            spotPrice.doubleValue(),
                                            setup.getFirst15MinLow().doubleValue(),
                                            setup.getFirst15MinHigh().doubleValue(),
                                            (setup.getDirection() == LowestVolumeDirection.LONG
                                                    ? "Above High ₹" + setup.getFirst15MinHigh()
                                                    : "Below Low ₹" + setup.getFirst15MinLow())));
                        }
                        return;
                    }
                }

                // Sector Momentum Alignment Check at Entry Time
                if (sectorMomentumFilterEnabled) {
                    boolean sectorAligned = checkLiveSectorAlignment(symbol, setup.getDirection());
                    if (!sectorAligned) {
                        log.warn(
                                "[LVR] Entry for {} BLOCKED: Parent sector has flipped or lost momentum opposite to {} direction. Skipping entry.",
                                symbol,
                                setup.getDirection());
                        return;
                    }
                }

                executePositionEntry(symbol, setup, spotPrice);
            }
        } catch (Exception e) {
            log.error(
                    "[LVR] Error checking spot trigger breach for {}: {}", symbol, e.getMessage());
        }
    }

    /** Executes entry (Stock Futures or ATM Option buying) upon spot trigger breach. */
    public synchronized LowestVolumePaperPosition executePositionEntry(
            String symbol, LowestVolumeSetup setup, BigDecimal spotPrice) {
        if (setup == null || symbol == null) return null;
        if (isDailyCircuitBreakerTripped()) {
            log.warn(
                    "[LVR] Daily max loss circuit breaker is active. Skipping new entry for {}.",
                    symbol);
            return null;
        }
        if (openPositions.containsKey(symbol)) {
            log.warn("[LVR] Position for {} already open. Skipping duplicate entry.", symbol);
            return openPositions.get(symbol);
        }
        if (setup.getState() != LowestVolumeSetupState.TRIGGER_ARMED) {
            log.warn(
                    "[LVR] Setup for {} is in state {} (not TRIGGER_ARMED). Skipping entry.",
                    symbol,
                    setup.getState());
            return openPositions.get(symbol);
        }
        if (setup.getTradeAttempts() >= maxAttemptsPerSymbol) {
            log.warn(
                    "[LVR] Setup for {} has already reached max attempts ({}). Skipping entry.",
                    symbol,
                    maxAttemptsPerSymbol);
            return null;
        }
        if (openPositions.size() >= maxConcurrentTrades) {
            log.warn(
                    "[LVR] Max concurrent trades ({}) reached. Skipping entry for {}.",
                    maxConcurrentTrades,
                    symbol);
            return null;
        }

        StockFnoRegistry.InstrumentInfo fno = StockFnoRegistry.get(symbol);
        int lotSize = (fno != null) ? fno.lotSize() : 100;
        BigDecimal strikeStep = (fno != null) ? fno.strikeStep() : BigDecimal.valueOf(10);

        BigDecimal unitRisk = spotPrice.subtract(setup.getStopLossPrice()).abs();
        int lots = defaultLots > 0 ? defaultLots : 2;
        if (dynamicPositionSizing && unitRisk.compareTo(BigDecimal.ZERO) > 0 && lotSize > 0) {
            double riskDenominator;
            if (instrumentType == LvrInstrumentType.OPTIONS) {
                riskDenominator = Math.max(0.50, unitRisk.doubleValue() * 0.50) * lotSize;
            } else {
                riskDenominator = unitRisk.doubleValue() * lotSize;
            }
            int sizedLots = (int) (getRiskPerTradeAmount() / riskDenominator);
            lots = Math.max(1, Math.min(sizedLots, defaultLots > 0 ? defaultLots * 2 : 10));
        }
        int totalQty = lots * lotSize;
        BigDecimal plannedRisk;
        if (instrumentType == LvrInstrumentType.OPTIONS) {
            plannedRisk =
                    unitRisk.multiply(BigDecimal.valueOf(0.50))
                            .multiply(BigDecimal.valueOf(totalQty))
                            .setScale(2, RoundingMode.HALF_UP);
        } else {
            plannedRisk =
                    unitRisk.multiply(BigDecimal.valueOf(totalQty))
                            .setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal plannedReward =
                unitRisk.multiply(BigDecimal.valueOf(2)).multiply(BigDecimal.valueOf(totalQty));

        // Dynamically compute exact 1:2 Target from actual entry spot price to prevent RR
        // distortion
        BigDecimal actualTarget1;
        if (setup.getDirection() == LowestVolumeDirection.SHORT) {
            actualTarget1 =
                    roundToTick(spotPrice.subtract(unitRisk.multiply(BigDecimal.valueOf(2))));
        } else {
            actualTarget1 = roundToTick(spotPrice.add(unitRisk.multiply(BigDecimal.valueOf(2))));
        }

        String tradeId = "LVR-" + tradeCounter.getAndIncrement();
        LowestVolumePaperPosition position;

        if (instrumentType == LvrInstrumentType.FUTURES) {
            String contractSymbol = symbol + " FUT";
            String brokerTradingSymbol = StockFnoRegistry.formatFuturesTradingSymbol(symbol, null);
            position =
                    new LowestVolumePaperPosition(
                            tradeId,
                            symbol,
                            LvrInstrumentType.FUTURES,
                            exitMode,
                            contractSymbol,
                            lotSize,
                            lots,
                            setup.getDirection(),
                            spotPrice,
                            setup.getStopLossPrice(),
                            actualTarget1,
                            totalQty,
                            plannedRisk,
                            Instant.now());

            LowestVolumePaperPosition existing = openPositions.putIfAbsent(symbol, position);
            if (existing != null) {
                log.warn(
                        "[LVR] Concurrent position insertion race detected for {}. Skipping.",
                        symbol);
                return existing;
            }
            setup.recordTradeAttempt();
            setup.transitionTo(
                    LowestVolumeSetupState.IN_POSITION, "Trigger breached at spot " + spotPrice);

            publishSignal(
                    symbol,
                    brokerTradingSymbol,
                    setup.getDirection() == LowestVolumeDirection.LONG
                            ? com.tradingbot.strategy.SignalAction.ENTRY_LONG
                            : com.tradingbot.strategy.SignalAction.ENTRY_SHORT,
                    spotPrice,
                    setup.getStopLossPrice(),
                    actualTarget1,
                    totalQty,
                    "LVR Futures Entry Triggered",
                    Map.of("instrumentType", "FUTURES"));

            log.info(
                    "[LVR] FUTURES ENTRY EXECUTED: {} | TradeId={} | Contract={} | SpotEntry={} |"
                            + " SpotSL={} | SpotTarget1={}",
                    symbol,
                    tradeId,
                    contractSymbol,
                    spotPrice,
                    setup.getStopLossPrice(),
                    actualTarget1);

            if (telegramAlerts && telegramService != null) {
                telegramService.sendTextMessage(
                        String.format(
                                "🚀 *LVR Futures Entry Triggered*\n"
                                        + "• Symbol: *%s* (%s)\n"
                                        + "• Contract: `%s`\n"
                                        + "• Entry Price: `₹%.2f` (%d lots / %d qty)\n"
                                        + "• Spot SL: `₹%.2f` (Risk: `₹%.2f` / `₹%.2f`)\n"
                                        + "• 1:2 Target Price: `₹%.2f` (Reward: `₹%.2f` /"
                                        + " `+₹%.2f`)",
                                symbol,
                                setup.getDirection(),
                                contractSymbol,
                                spotPrice.doubleValue(),
                                lots,
                                totalQty,
                                setup.getStopLossPrice().doubleValue(),
                                unitRisk.doubleValue(),
                                plannedRisk.doubleValue(),
                                actualTarget1.doubleValue(),
                                unitRisk.multiply(BigDecimal.valueOf(2)).doubleValue(),
                                plannedReward.doubleValue()));
            }
        } else {
            BigDecimal atmStrike = resolveAtmStrike(spotPrice, strikeStep);
            String optType = (setup.getDirection() == LowestVolumeDirection.SHORT) ? "PE" : "CE";
            String optSymbol = symbol + " ATM " + atmStrike + optType;
            String brokerTradingSymbol =
                    StockFnoRegistry.formatTradingSymbol(symbol, null, atmStrike, optType, false);

            double optLtp = fetchOptionLtp(symbol, optType, atmStrike);
            if (optLtp <= 0.0) {
                log.warn(
                        "[LVR] Option LTP unavailable for {} {} ATM {}. Skipping entry this tick to avoid fabricated premium.",
                        symbol,
                        optType,
                        atmStrike);
                return null;
            }
            BigDecimal entryPremium = BigDecimal.valueOf(optLtp).setScale(2, RoundingMode.HALF_UP);

            position =
                    new LowestVolumePaperPosition(
                            tradeId,
                            symbol,
                            exitMode,
                            optType,
                            optSymbol,
                            atmStrike,
                            lotSize,
                            lots,
                            setup.getDirection(),
                            entryPremium,
                            spotPrice,
                            setup.getStopLossPrice(),
                            actualTarget1,
                            totalQty,
                            plannedRisk,
                            Instant.now());

            LowestVolumePaperPosition existing = openPositions.putIfAbsent(symbol, position);
            if (existing != null) {
                log.warn(
                        "[LVR] Concurrent position insertion race detected for {}. Skipping.",
                        symbol);
                return existing;
            }
            setup.recordTradeAttempt();
            setup.transitionTo(
                    LowestVolumeSetupState.IN_POSITION, "Trigger breached at spot " + spotPrice);

            publishSignal(
                    symbol,
                    brokerTradingSymbol,
                    setup.getDirection() == LowestVolumeDirection.LONG
                            ? com.tradingbot.strategy.SignalAction.ENTRY_LONG
                            : com.tradingbot.strategy.SignalAction.ENTRY_SHORT,
                    entryPremium,
                    setup.getStopLossPrice(),
                    actualTarget1,
                    totalQty,
                    "LVR Option Entry Triggered",
                    Map.of("instrumentType", "OPTION", "spotPrice", spotPrice));

            log.info(
                    "[LVR] OPTION ENTRY EXECUTED: {} | TradeId={} | Option={} | EntryPrem={} |"
                            + " SpotEntry={} | SpotSL={} | SpotTarget1={}",
                    symbol,
                    tradeId,
                    optSymbol,
                    entryPremium,
                    spotPrice,
                    setup.getStopLossPrice(),
                    actualTarget1);

            if (telegramAlerts && telegramService != null) {
                telegramService.sendTextMessage(
                        String.format(
                                "🚀 *LVR Option Entry Triggered*\n"
                                        + "• Symbol: *%s* (%s)\n"
                                        + "• Contract: `%s`\n"
                                        + "• Option LTP: `₹%.2f` (%d lots / %d qty)\n"
                                        + "• Spot Entry: `₹%.2f` | Spot SL: `₹%.2f`\n"
                                        + "• Spot Target 1 (1:2 RR): `₹%.2f`",
                                symbol,
                                setup.getDirection(),
                                optSymbol,
                                entryPremium.doubleValue(),
                                lots,
                                totalQty,
                                spotPrice.doubleValue(),
                                setup.getStopLossPrice().doubleValue(),
                                actualTarget1.doubleValue()));
            }
        }

        return position;
    }

    /** Legacy alias for executePositionEntry. */
    public synchronized LowestVolumePaperPosition executeOptionEntry(
            String symbol, LowestVolumeSetup setup, BigDecimal spotPrice) {
        return executePositionEntry(symbol, setup, spotPrice);
    }

    /**
     * Evaluates open positions for Spot SL, 1:2 Target Partial Exit, Cost SL, and 10 EMA Trailing.
     */
    public void evaluateOpenPositions(LocalTime nowTime) {
        evaluateOpenPositions(nowTime, Collections.emptyMap(), true);
    }

    public void evaluateOpenPositions(LocalTime nowTime, Map<String, JsonNode> quoteCache) {
        evaluateOpenPositions(nowTime, quoteCache, true);
    }

    public synchronized void evaluateOpenPositions(
            LocalTime nowTime, Map<String, JsonNode> quoteCache, boolean isCandleClose) {
        if (openPositions.isEmpty()) return;

        for (Map.Entry<String, LowestVolumePaperPosition> entry : openPositions.entrySet()) {
            String symbol = entry.getKey();
            LowestVolumePaperPosition pos = entry.getValue();
            if (pos == null || pos.isClosed()) {
                openPositions.remove(symbol);
                continue;
            }

            try {
                JsonNode quoteNode =
                        (quoteCache != null && quoteCache.containsKey(symbol))
                                ? quoteCache.get(symbol)
                                : fetchLiveQuoteNode(symbol);
                double spotLtp =
                        (quoteNode != null && quoteNode.has("lp"))
                                ? quoteNode.get("lp").asDouble(0.0)
                                : 0.0;
                if (spotLtp <= 0) continue;

                BigDecimal spotPrice = BigDecimal.valueOf(spotLtp);
                BigDecimal optionPremium = estimateOptionPremium(pos, spotPrice);

                // 1. Check Stop Loss breach on Spot
                boolean slHit = false;
                if (pos.getDirection() == LowestVolumeDirection.SHORT) {
                    if (spotPrice.compareTo(pos.getCurrentStockSl()) >= 0) {
                        slHit = true;
                    }
                } else if (pos.getDirection() == LowestVolumeDirection.LONG) {
                    if (spotPrice.compareTo(pos.getCurrentStockSl()) <= 0) {
                        slHit = true;
                    }
                }

                if (slHit) {
                    if (pos.isClosed()) continue;
                    openPositions.remove(symbol);
                    String slReason =
                            pos.isPartialBooked() ? "TRAILING_COST_SL_HIT" : "SPOT_SL_HIT";
                    int exitQty =
                            pos.getRemainingQuantity() > 0
                                    ? pos.getRemainingQuantity()
                                    : pos.getTotalQuantity();
                    String brokerSymbol = resolveBrokerTradingSymbol(pos);
                    BigDecimal exitPrc =
                            pos.getInstrumentType() == LvrInstrumentType.FUTURES
                                    ? spotPrice
                                    : optionPremium;

                    pos.close(exitPrc, slReason, Instant.now());
                    tradeHistory.add(pos);

                    LowestVolumeSetup setup = activeSetups.get(symbol);
                    if (setup != null) {
                        if (pos.isPartialBooked()) {
                            setup.transitionTo(
                                    LowestVolumeSetupState.CLOSED_TRAIL_EXIT,
                                    slReason + " at " + spotPrice);
                            exhaustedSymbols.add(symbol);
                        } else if (setup.getTradeAttempts() < maxAttemptsPerSymbol) {
                            setup.resetToScanning();
                            setup.setLastExitTime(Instant.now());
                        } else {
                            setup.transitionTo(
                                    LowestVolumeSetupState.CLOSED_SL,
                                    String.format(
                                            "Max %d attempt(s) reached", maxAttemptsPerSymbol));
                            exhaustedSymbols.add(symbol);
                        }
                    }

                    log.info(
                            "[LVR] SL Hit for {}: Closed at ExitPrice={}, Spot={}, Reason={}",
                            symbol,
                            exitPrc,
                            spotPrice,
                            slReason);

                    publishSignal(
                            symbol,
                            brokerSymbol,
                            pos.getDirection() == LowestVolumeDirection.LONG
                                    ? com.tradingbot.strategy.SignalAction.EXIT_LONG
                                    : com.tradingbot.strategy.SignalAction.EXIT_SHORT,
                            exitPrc,
                            pos.getCurrentStockSl(),
                            null,
                            exitQty,
                            slReason,
                            Map.of("instrumentType", pos.getInstrumentType().name()));
                    if (telegramAlerts && telegramService != null) {
                        if (pos.isPartialBooked()) {
                            telegramService.sendTextMessage(
                                    String.format(
                                            Locale.US,
                                            "🛡️ *LVR Cost Trailing Stop Hit (Remaining 50%%"
                                                    + " Closed)*\n"
                                                    + "• Symbol: *%s* (%s)\n"
                                                    + "• Exit Price: `₹%.2f` (Cost SL was `₹%.2f`)\n"
                                                    + "• Runner Realized P&L: `₹%.2f`\n"
                                                    + "• Total Realized P&L: `₹%.2f`",
                                            symbol,
                                            pos.getDirection(),
                                            exitPrc.doubleValue(),
                                            pos.getCurrentStockSl().doubleValue(),
                                            pos.getRunnerPnl().doubleValue(),
                                            pos.getTotalRealizedPnl().doubleValue()));
                        } else if (pos.getInstrumentType() == LvrInstrumentType.FUTURES) {
                            BigDecimal pts =
                                    (pos.getDirection() == LowestVolumeDirection.LONG)
                                            ? spotPrice.subtract(pos.getStockEntryPrice())
                                            : pos.getStockEntryPrice().subtract(spotPrice);
                            telegramService.sendTextMessage(
                                    String.format(
                                            Locale.US,
                                            "🛑 *LVR Stop Loss Hit (100%% Exit)*\n"
                                                    + "• Symbol: *%s* (%s)\n"
                                                    + "• Exit Price: `₹%.2f` (SL was `₹%.2f`)\n"
                                                    + "• Points Captured: `%.2f pts`\n"
                                                    + "• Realized P&L: `₹%.2f`\n"
                                                    + "• Remaining Attempts: `%d`",
                                            symbol,
                                            pos.getDirection(),
                                            spotPrice.doubleValue(),
                                            pos.getCurrentStockSl().doubleValue(),
                                            pts.doubleValue(),
                                            pos.getTotalRealizedPnl().doubleValue(),
                                            setup != null
                                                    ? Math.max(
                                                            0,
                                                            maxAttemptsPerSymbol
                                                                    - setup.getTradeAttempts())
                                                    : 0));
                        } else {
                            telegramService.sendTextMessage(
                                    String.format(
                                            Locale.US,
                                            "🛑 *LVR Stop Loss Hit*\n"
                                                    + "• Symbol: *%s*\n"
                                                    + "• Exit Premium: `₹%.2f` (P&L: `₹%.2f`)\n"
                                                    + "• Spot Exit: `₹%.2f` (SL was `₹%.2f`)",
                                            symbol,
                                            optionPremium.doubleValue(),
                                            pos.getTotalRealizedPnl().doubleValue(),
                                            spotPrice.doubleValue(),
                                            pos.getCurrentStockSl().doubleValue()));
                        }
                    }
                    continue;
                }

                // 2. Check 1:2 Target on Spot
                if (!pos.isPartialBooked() && !pos.isClosed()) {
                    boolean targetHit = false;
                    if (pos.getDirection() == LowestVolumeDirection.SHORT) {
                        if (spotPrice.compareTo(pos.getTarget1StockPrice()) <= 0) {
                            targetHit = true;
                        }
                    } else if (pos.getDirection() == LowestVolumeDirection.LONG) {
                        if (spotPrice.compareTo(pos.getTarget1StockPrice()) >= 0) {
                            targetHit = true;
                        }
                    }

                    if (targetHit) {
                        LvrExitMode currentExitMode =
                                (pos.getExitMode() != null) ? pos.getExitMode() : exitMode;
                        if (currentExitMode == LvrExitMode.FULL_TARGET_1_2
                                || currentExitMode == LvrExitMode.FULL_TARGET_1_4) {
                            // 100% Full Exit at 1:2 Target
                            int exitQty =
                                    pos.getRemainingQuantity() > 0
                                            ? pos.getRemainingQuantity()
                                            : pos.getTotalQuantity();
                            String brokerSymbol = resolveBrokerTradingSymbol(pos);
                            BigDecimal exitPrc =
                                    pos.getInstrumentType() == LvrInstrumentType.FUTURES
                                            ? spotPrice
                                            : optionPremium;

                            if (pos.getInstrumentType() == LvrInstrumentType.FUTURES) {
                                pos.closeFullFutures(
                                        spotPrice, "TARGET_1_2_FULL_EXIT", Instant.now());
                            } else {
                                pos.close(optionPremium, "TARGET_1_2_FULL_EXIT", Instant.now());
                            }
                            openPositions.remove(symbol);
                            tradeHistory.add(pos);

                            LowestVolumeSetup setup = activeSetups.get(symbol);
                            if (setup != null) {
                                setup.transitionTo(
                                        LowestVolumeSetupState.CLOSED_TARGET,
                                        "1:2 Target reached at spot " + spotPrice);
                                exhaustedSymbols.add(symbol);
                            }

                            log.info(
                                    "[LVR] 1:2 Target 100% Full Exit for {}: Closed at Spot={}, Realized PnL={}",
                                    symbol, spotPrice, pos.getTotalRealizedPnl());

                            publishSignal(
                                    symbol,
                                    brokerSymbol,
                                    pos.getDirection() == LowestVolumeDirection.LONG
                                            ? com.tradingbot.strategy.SignalAction.EXIT_LONG
                                            : com.tradingbot.strategy.SignalAction.EXIT_SHORT,
                                    exitPrc,
                                    pos.getCurrentStockSl(),
                                    pos.getTarget1StockPrice(),
                                    exitQty,
                                    "TARGET_1_2_FULL_EXIT",
                                    Map.of("instrumentType", pos.getInstrumentType().name()));

                            if (telegramAlerts && telegramService != null) {
                                BigDecimal pts =
                                        (pos.getDirection() == LowestVolumeDirection.LONG)
                                                ? spotPrice.subtract(pos.getStockEntryPrice())
                                                : pos.getStockEntryPrice().subtract(spotPrice);
                                telegramService.sendTextMessage(
                                        String.format(
                                                "🎯 *LVR 1:2 Target Reached (100%% Full Exit)*\n"
                                                        + "• Symbol: *%s* (%s)\n"
                                                        + "• Exit Price: `₹%.2f` (Target was `₹%.2f`)\n"
                                                        + "• Points Captured: `+%.2f pts` (+2.00 R)\n"
                                                        + "• Realized P&L: `+₹%.2f`",
                                                symbol,
                                                pos.getDirection(),
                                                spotPrice.doubleValue(),
                                                pos.getTarget1StockPrice().doubleValue(),
                                                pts.doubleValue(),
                                                pos.getTotalRealizedPnl().doubleValue()));
                            }
                            continue;
                        } else {
                            // Partial 50% Booking mode
                            BigDecimal partialExitPrice =
                                    pos.getInstrumentType() == LvrInstrumentType.FUTURES
                                            ? spotPrice
                                            : optionPremium;
                            pos.executePartialBook(partialExitPrice, Instant.now());
                            LowestVolumeSetup setup = activeSetups.get(symbol);

                            if (pos.isClosed()) {
                                openPositions.remove(symbol);
                                tradeHistory.add(pos);
                                if (setup != null) {
                                    setup.transitionTo(
                                            LowestVolumeSetupState.CLOSED_TARGET,
                                            "1:2 Target reached at spot " + spotPrice);
                                    exhaustedSymbols.add(symbol);
                                }
                                log.info(
                                        "[LVR] 1:2 Target Full Exit (100% booked) for {}: Closed at Spot={}, Realized PnL={}",
                                        symbol, spotPrice, pos.getTotalRealizedPnl());

                                String brokerSymbol = resolveBrokerTradingSymbol(pos);
                                int closedQty = pos.getTotalQuantity();

                                publishSignal(
                                        symbol,
                                        brokerSymbol,
                                        pos.getDirection() == LowestVolumeDirection.LONG
                                                ? com.tradingbot.strategy.SignalAction.EXIT_LONG
                                                : com.tradingbot.strategy.SignalAction.EXIT_SHORT,
                                        partialExitPrice,
                                        pos.getCurrentStockSl(),
                                        pos.getTarget1StockPrice(),
                                        closedQty,
                                        "TARGET_1_2_FULL_EXIT",
                                        Map.of("instrumentType", pos.getInstrumentType().name()));

                                if (telegramAlerts && telegramService != null) {
                                    BigDecimal pts =
                                            (pos.getDirection() == LowestVolumeDirection.LONG)
                                                    ? spotPrice.subtract(pos.getStockEntryPrice())
                                                    : pos.getStockEntryPrice().subtract(spotPrice);
                                    telegramService.sendTextMessage(
                                            String.format(
                                                    "🎯 *LVR 1:2 Target Reached (Full Exit)*\n"
                                                            + "• Symbol: *%s* (%s)\n"
                                                            + "• Exit Price: `₹%.2f`\n"
                                                            + "• Realized P&L: `+₹%.2f`",
                                                    symbol,
                                                    pos.getDirection(),
                                                    spotPrice.doubleValue(),
                                                    pos.getTotalRealizedPnl().doubleValue()));
                                }
                                continue;
                            }

                            if (setup != null) {
                                setup.transitionTo(
                                        LowestVolumeSetupState.PARTIAL_BOOKED,
                                        "1:2 RR reached at spot " + spotPrice);
                            }

                            log.info(
                                    "[LVR] 1:2 Target Hit for {}: Booked 50% at ExitPrice={}, Cost SL Armed at Spot {}",
                                    symbol, partialExitPrice, pos.getCurrentStockSl());

                            String brokerSymbol = resolveBrokerTradingSymbol(pos);
                            int partialBookQty =
                                    (pos.getLots() > 0 && pos.getLotSize() > 0)
                                            ? ((pos.getLots() + 1) / 2) * pos.getLotSize()
                                            : pos.getTotalQuantity() / 2;

                            publishSignal(
                                    symbol,
                                    brokerSymbol,
                                    pos.getDirection() == LowestVolumeDirection.LONG
                                            ? com.tradingbot.strategy.SignalAction.PARTIAL_EXIT_LONG
                                            : com.tradingbot.strategy.SignalAction
                                                    .PARTIAL_EXIT_SHORT,
                                    partialExitPrice,
                                    pos.getCurrentStockSl(),
                                    pos.getTarget1StockPrice(),
                                    partialBookQty,
                                    "1:2 RR Target 50% Booked",
                                    Map.of(
                                            "instrumentType",
                                            pos.getInstrumentType().name(),
                                            "partialExitRatio",
                                            0.5));

                            if (telegramAlerts && telegramService != null) {
                                telegramService.sendTextMessage(
                                        String.format(
                                                "🎯 *LVR 1:2 Target Reached (50%% Booked)*\n"
                                                        + "• Symbol: *%s* (%s)\n"
                                                        + "• Booked Price: `₹%.2f` (Partial P&L: `₹%.2f`)\n"
                                                        + "• Spot: `₹%.2f` (Target: `₹%.2f`)\n"
                                                        + "• SL on remaining lots: `₹%.2f` (Cost / Breakeven)\n"
                                                        + "• Rest to be closed at: *15:00 IST*",
                                                symbol,
                                                pos.getDirection(),
                                                partialExitPrice.doubleValue(),
                                                pos.getPartialPnl().doubleValue(),
                                                spotPrice.doubleValue(),
                                                pos.getTarget1StockPrice().doubleValue(),
                                                pos.getCurrentStockSl().doubleValue()));
                            }
                        }
                    }
                }

                // 3. Trailing Exit on Runner Lots (Post 50% Booking) via 10 EMA (Confirmed 5m
                // Candle Closes Only)
                if (isCandleClose
                        && pos.isPartialBooked()
                        && !pos.isClosed()
                        && (pos.getExitMode() == LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500
                                || pos.getExitMode() == LvrExitMode.PARTIAL_RUNNER_10EMA)) {
                    List<Candle> rawCandles = marketDataService.fetch5MinCandles(symbol, 2);
                    LocalDate today = LocalDate.now(clock);
                    List<Candle> candles =
                            rawCandles != null
                                    ? rawCandles.stream()
                                            .filter(
                                                    c ->
                                                            c.timestamp() != null
                                                                    && LocalDate.ofInstant(
                                                                                    c.timestamp(),
                                                                                    IST)
                                                                            .equals(today))
                                            .toList()
                                    : Collections.emptyList();
                    if (candles.size() >= 10) {
                        double[] closes =
                                candles.stream()
                                        .mapToDouble(c -> c.close().doubleValue())
                                        .toArray();
                        double[] emaSeries = taService.calculateEmaSeries(closes, 10);
                        double ema10 = emaSeries[emaSeries.length - 1];
                        Candle latestCandle = candles.get(candles.size() - 1);

                        boolean emaTrailExit = false;
                        if (!Double.isNaN(ema10)) {
                            if (pos.getDirection() == LowestVolumeDirection.SHORT
                                    && latestCandle.close().doubleValue() > ema10) {
                                emaTrailExit = true;
                            } else if (pos.getDirection() == LowestVolumeDirection.LONG
                                    && latestCandle.close().doubleValue() < ema10) {
                                emaTrailExit = true;
                            }
                        }

                        if (emaTrailExit) {
                            int exitQty =
                                    pos.getRemainingQuantity() > 0
                                            ? pos.getRemainingQuantity()
                                            : pos.getTotalQuantity();
                            String brokerSymbol = resolveBrokerTradingSymbol(pos);
                            BigDecimal exitVal =
                                    (pos.getInstrumentType() == LvrInstrumentType.FUTURES)
                                            ? latestCandle.close()
                                            : optionPremium;
                            pos.close(exitVal, "10_EMA_TRAIL_EXIT", Instant.now());
                            openPositions.remove(symbol);
                            tradeHistory.add(pos);

                            LowestVolumeSetup setup = activeSetups.get(symbol);
                            if (setup != null) {
                                setup.transitionTo(
                                        LowestVolumeSetupState.CLOSED_TRAIL_EXIT,
                                        String.format(
                                                "10 EMA Trail Exit (Close=%.2f, EMA=%.2f)",
                                                latestCandle.close().doubleValue(), ema10));
                                exhaustedSymbols.add(symbol);
                            }

                            log.info(
                                    "[LVR] 10 EMA Trail Exit triggered for {}: Closed remaining runner at ExitVal={}, Spot={}, EMA10={}",
                                    symbol,
                                    exitVal,
                                    spotPrice,
                                    ema10);

                            publishSignal(
                                    symbol,
                                    brokerSymbol,
                                    pos.getDirection() == LowestVolumeDirection.LONG
                                            ? com.tradingbot.strategy.SignalAction.EXIT_LONG
                                            : com.tradingbot.strategy.SignalAction.EXIT_SHORT,
                                    exitVal,
                                    pos.getCurrentStockSl(),
                                    null,
                                    exitQty,
                                    "10_EMA_TRAIL_EXIT",
                                    Map.of("instrumentType", pos.getInstrumentType().name()));

                            if (telegramAlerts && telegramService != null) {
                                telegramService.sendTextMessage(
                                        String.format(
                                                "🏃 *LVR 10 EMA Trailing Exit*\n"
                                                        + "• Symbol: *%s*\n"
                                                        + "• Runner Exit Price: `₹%.2f`\n"
                                                        + "• Total Realized P&L: `₹%.2f`\n"
                                                        + "• Spot Close: `₹%.2f` | 10 EMA: `₹%.2f`",
                                                symbol,
                                                exitVal.doubleValue(),
                                                pos.getTotalRealizedPnl().doubleValue(),
                                                latestCandle.close().doubleValue(),
                                                ema10));
                            }
                            continue;
                        }
                    }
                }
            } catch (Exception e) {
                log.error(
                        "[LVR] Error evaluating open position for {}: {}", symbol, e.getMessage());
            }
        }
    }

    /** 15:00 IST Hard EOD Square-Off. */
    public synchronized void executeHardExit(LocalTime nowTime) {
        if (openPositions.isEmpty()) return;

        log.info(
                "[LVR] {} Hard EOD Square-off reached. Closing all open positions.",
                nowTime != null ? nowTime : "15:00 IST");
        List<String> symbols = new ArrayList<>(openPositions.keySet());
        for (String symbol : symbols) {
            LowestVolumePaperPosition pos = openPositions.get(symbol);
            if (pos == null || pos.isClosed()) {
                openPositions.remove(symbol);
                continue;
            }

            try {
                double spotLtp = fetchLiveSpotPrice(symbol);
                BigDecimal spotPrice =
                        BigDecimal.valueOf(
                                spotLtp > 0 ? spotLtp : pos.getStockEntryPrice().doubleValue());
                BigDecimal optionPremium = estimateOptionPremium(pos, spotPrice);

                BigDecimal exitVal =
                        (pos.getInstrumentType() == LvrInstrumentType.FUTURES)
                                ? spotPrice
                                : optionPremium;
                int exitQty =
                        pos.getRemainingQuantity() > 0
                                ? pos.getRemainingQuantity()
                                : pos.getTotalQuantity();
                String brokerSymbol = resolveBrokerTradingSymbol(pos);
                pos.close(exitVal, "EOD_1500_HARD_EXIT", Instant.now());
                openPositions.remove(symbol);
                tradeHistory.add(pos);

                publishSignal(
                        symbol,
                        brokerSymbol,
                        pos.getDirection() == LowestVolumeDirection.LONG
                                ? com.tradingbot.strategy.SignalAction.EXIT_LONG
                                : com.tradingbot.strategy.SignalAction.EXIT_SHORT,
                        exitVal,
                        pos.getCurrentStockSl(),
                        null,
                        exitQty,
                        "EOD_1500_HARD_EXIT",
                        Map.of("instrumentType", pos.getInstrumentType().name()));

                LowestVolumeSetup setup = activeSetups.get(symbol);
                if (setup != null) {
                    setup.transitionTo(LowestVolumeSetupState.CLOSED_TRAIL_EXIT, "15:00 EOD Exit");
                    exhaustedSymbols.add(symbol);
                }

                if (telegramAlerts && telegramService != null) {
                    telegramService.sendTextMessage(
                            String.format(
                                    Locale.US,
                                    "🏁 *LVR %s Hard EOD Exit*\n"
                                            + "• Symbol: *%s* (%s)\n"
                                            + "• Exit Price: `₹%.2f`\n"
                                            + "• Runner Realized P&L: `₹%.2f`\n"
                                            + "• Total Realized P&L: `₹%.2f`\n"
                                            + "• Reason: Market Close Square-Off",
                                    nowTime != null ? nowTime : "15:00 IST",
                                    symbol,
                                    pos.getDirection(),
                                    exitVal.doubleValue(),
                                    pos.getRunnerPnl().doubleValue(),
                                    pos.getTotalRealizedPnl().doubleValue()));
                }
            } catch (Exception e) {
                log.error("[LVR] Error during hard exit for {}: {}", symbol, e.getMessage(), e);
                openPositions.remove(symbol);
            }
        }
        openPositions.clear();
    }

    public synchronized void resetDaily() {
        if (!openPositions.isEmpty()) {
            log.warn(
                    "[LVR] resetDaily() called while {} open position(s) exist. Executing hard exit first.",
                    openPositions.size());
            executeHardExit(LocalTime.of(15, 0));
        }
        activeSetups.clear();
        openPositions.clear();
        tradeHistory.clear();
        exhaustedSymbols.clear();
        candidateReservoir.clear();
        lastMidMorningRefreshTime = null;
        lastScanDate = null;
        niftyBullish = true;
        currentTopGainers.clear();
        currentTopLosers.clear();
        currentTopGainerSnapshots.clear();
        currentTopLoserSnapshots.clear();
        sectorState = LowestVolumeSectorState.empty();
        universeScanCompletedToday = false;
        dailyCircuitBreakerAlertSent.set(false);
        tradeCounter.set(1);
        if (marketDataService != null) {
            marketDataService.prewarmSession();
        }
        log.info("[LVR] Daily state reset complete.");
    }

    /**
     * Replenishes active setups from the standby candidate reservoir if actionable candidate count
     * drops below minActiveCandidates (due to SL hits, exhaustion, or invalidation).
     */
    public synchronized void replenishActiveCandidatesIfNeeded(LocalTime nowTime) {
        if (nowTime.isAfter(TIME_ENTRY_CUTOFF)) return;

        long actionableCount = countActionableSetups();

        if (actionableCount < minActiveCandidates && !candidateReservoir.isEmpty()) {
            LowestVolumeDirection dir =
                    niftyBullish ? LowestVolumeDirection.LONG : LowestVolumeDirection.SHORT;
            List<String> promotedSymbols = new ArrayList<>();

            while (actionableCount < minActiveCandidates && !candidateReservoir.isEmpty()) {
                String nextSymbol = candidateReservoir.remove(0);
                if (!activeSetups.containsKey(nextSymbol)
                        && !exhaustedSymbols.contains(nextSymbol)) {
                    activeSetups.put(nextSymbol, new LowestVolumeSetup(nextSymbol, dir));
                    promotedSymbols.add(nextSymbol);
                    actionableCount++;
                }
            }

            if (!promotedSymbols.isEmpty()) {
                log.info(
                        "[LVR] Candidate Watchlist replenished. Promoted {} from reservoir. Active"
                                + " setups: {}",
                        promotedSymbols,
                        activeSetups.size());

                if (telegramAlerts && telegramService != null) {
                    telegramService.sendTextMessage(
                            String.format(
                                    "🔄 *LVR Candidate Watchlist Replenished*\n"
                                            + "• Promoted Stocks (%d): `%s` (from reserve sectors)\n"
                                            + "• Direction: *%s*\n"
                                            + "• Active Watchlist Count: `%d`\n"
                                            + "• Reason: Active candidates dropped below threshold"
                                            + " (%d)",
                                    promotedSymbols.size(),
                                    String.join(", ", promotedSymbols),
                                    dir,
                                    activeSetups.size(),
                                    minActiveCandidates));
                }
            }
        }
    }

    /**
     * Counts currently actionable setups (not exhausted, not permanently closed, <
     * maxAttemptsPerSymbol).
     */
    public long countActionableSetups() {
        return activeSetups.values().stream()
                .filter(s -> !s.isExhaustedOrRejected())
                .filter(s -> !s.isClosed())
                .filter(s -> s.getTradeAttempts() < maxAttemptsPerSymbol)
                .filter(s -> !exhaustedSymbols.contains(s.getSymbol()))
                .count();
    }

    /**
     * Executes a mid-morning scan refresh if all primary and reservoir candidate stocks have been
     * exhausted before 13:00 IST cutoff.
     */
    public void runMidMorningUniverseRefresh(LocalTime nowTime) {
        if (marketDataService == null || nowTime.isAfter(TIME_ENTRY_CUTOFF)) return;
        log.info("[LVR] Triggering Mid-Morning Universe Refresh at {} IST...", nowTime);

        try {
            Map<String, StockQuoteSnapshot> universeQuotes = fetchMorningQuotesUnified();
            if (universeQuotes.isEmpty()) {
                log.warn("[LVR] Mid-morning quote fetch returned empty.");
                return;
            }

            List<StockQuoteSnapshot> niftyQuotes = new ArrayList<>();
            for (String sym : NiftySectorRegistry.NIFTY_50_CONSTITUENTS) {
                StockQuoteSnapshot q = universeQuotes.get(sym);
                if (q != null) niftyQuotes.add(q);
            }
            LowestVolumeDirection sentiment =
                    scanner.evaluateMarketSentiment(niftyQuotes, minBreadthPct);
            if (sentiment == LowestVolumeDirection.NONE) {
                sentiment = niftyBullish ? LowestVolumeDirection.LONG : LowestVolumeDirection.SHORT;
            } else {
                this.niftyBullish = (sentiment == LowestVolumeDirection.LONG);
            }

            Map<String, List<StockQuoteSnapshot>> sectorQuotes = new HashMap<>();
            for (Map.Entry<String, List<String>> entry :
                    NiftySectorRegistry.getSectorConstituents().entrySet()) {
                List<StockQuoteSnapshot> sQuotes = new ArrayList<>();
                for (String sym : entry.getValue()) {
                    StockQuoteSnapshot q = universeQuotes.get(sym);
                    if (q != null) sQuotes.add(q);
                }
                if (!sQuotes.isEmpty()) {
                    sectorQuotes.put(entry.getKey(), sQuotes);
                }
            }

            List<LowestVolumeReversalScanner.SectorRankResult> rankedSectors =
                    scanner.rankSectors(sectorQuotes, sentiment);

            for (LowestVolumeReversalScanner.SectorRankResult sector : rankedSectors) {
                List<StockQuoteSnapshot> sQuotes =
                        sectorQuotes.getOrDefault(sector.sectorName(), Collections.emptyList());
                List<String> candidates = scanner.filterCandidateStocks(sQuotes, sentiment);
                for (String sym : candidates) {
                    if (!activeSetups.containsKey(sym)
                            && !exhaustedSymbols.contains(sym)
                            && !candidateReservoir.contains(sym)) {
                        candidateReservoir.add(sym);
                    }
                }
            }

            this.lastMidMorningRefreshTime = nowTime;
            replenishActiveCandidatesIfNeeded(nowTime);
        } catch (Exception e) {
            log.error("[LVR] Error during mid-morning universe refresh: {}", e.getMessage(), e);
        }
    }

    public List<String> getCandidateReservoir() {
        return candidateReservoir;
    }

    public int getMinActiveCandidates() {
        return minActiveCandidates;
    }

    public void setMinActiveCandidates(int minActiveCandidates) {
        this.minActiveCandidates = minActiveCandidates;
    }

    public static BigDecimal roundToTick(BigDecimal price) {
        if (price == null) return null;
        double rounded = Math.round(price.doubleValue() * 20.0) / 20.0;
        return BigDecimal.valueOf(rounded).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal resolveAtmStrike(BigDecimal spotPrice, BigDecimal strikeStep) {
        if (strikeStep == null || strikeStep.compareTo(BigDecimal.ZERO) <= 0) return spotPrice;
        if (spotPrice == null || spotPrice.compareTo(BigDecimal.ZERO) <= 0) return strikeStep;
        BigDecimal divided = spotPrice.divide(strikeStep, 0, RoundingMode.HALF_UP);
        if (divided.compareTo(BigDecimal.ZERO) == 0) {
            return strikeStep;
        }
        return divided.multiply(strikeStep);
    }

    public JsonNode fetchLiveQuoteNode(String symbol) {
        if (marketDataService == null) return null;
        var info = StockFnoRegistry.get(symbol);
        String token =
                (info != null && info.token() != null)
                        ? info.token()
                        : marketDataService.resolveToken(symbol);
        String exchange = (info != null && info.exchange() != null) ? info.exchange() : "NSE";
        return marketDataService.fetchQuote(exchange, token);
    }

    private double fetchLiveSpotPrice(String symbol) {
        JsonNode node = fetchLiveQuoteNode(symbol);
        if (node != null && node.has("lp")) {
            return node.get("lp").asDouble(0.0);
        }
        return 0.0;
    }

    public double fetchLiveVwap(String symbol) {
        return fetchLiveVwap(symbol, null);
    }

    public double fetchLiveVwap(String symbol, JsonNode quoteNode) {
        if (quoteNode == null) {
            quoteNode = fetchLiveQuoteNode(symbol);
        }
        if (quoteNode != null && quoteNode.has("ap")) {
            double ap = quoteNode.get("ap").asDouble(0.0);
            if (ap > 0.0) {
                return ap;
            }
        }
        // Fallback: calculate from intraday 5m candles
        try {
            if (marketDataService != null && taService != null) {
                List<Candle> rawCandles = marketDataService.fetch5MinCandles(symbol, 1);
                if (rawCandles != null && !rawCandles.isEmpty()) {
                    LocalDate today = LocalDate.now(clock);
                    List<Candle> candles =
                            rawCandles.stream()
                                    .filter(
                                            c ->
                                                    c.timestamp() != null
                                                            && LocalDate.ofInstant(
                                                                            c.timestamp(), IST)
                                                                    .equals(today))
                                    .toList();
                    if (!candles.isEmpty()) {
                        double[] vwapSeries = taService.calculateVwapSeries(candles);
                        if (vwapSeries.length > 0
                                && !Double.isNaN(vwapSeries[vwapSeries.length - 1])) {
                            return vwapSeries[vwapSeries.length - 1];
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug(
                    "[LVR] Error calculating VWAP from candles for {}: {}", symbol, e.getMessage());
        }
        return 0.0;
    }

    public BigDecimal estimateOptionPremium(LowestVolumePaperPosition pos, BigDecimal currentSpot) {
        if (pos == null || currentSpot == null) return BigDecimal.valueOf(20.0);
        BigDecimal entrySpot = pos.getStockEntryPrice();
        BigDecimal entryPrem = pos.getEntryPremium();
        if (entrySpot == null || entryPrem == null || entrySpot.compareTo(BigDecimal.ZERO) <= 0) {
            return entryPrem != null ? entryPrem : BigDecimal.valueOf(20.0);
        }

        // 1. Attempt live option quote fetch first if option metadata is present
        if (pos.getAtmStrike() != null && pos.getOptionType() != null) {
            double liveLtp =
                    fetchOptionLtp(pos.getSymbol(), pos.getOptionType(), pos.getAtmStrike());
            if (liveLtp > 0.0) {
                return BigDecimal.valueOf(liveLtp).setScale(2, RoundingMode.HALF_UP);
            }
        }

        // 2. Greeks-aware Delta & Intrinsic Model (with Delta expansion on deep ITM moves)
        double spotMove;
        if (pos.getDirection() == LowestVolumeDirection.SHORT) {
            spotMove = entrySpot.subtract(currentSpot).doubleValue();
        } else {
            spotMove = currentSpot.subtract(entrySpot).doubleValue();
        }

        double moveRatio = spotMove / entrySpot.doubleValue();
        // Delta expands from 0.50 toward 0.85 as trade moves in favor, compresses toward 0.20 on
        // adverse moves
        double dynamicDelta = Math.max(0.20, Math.min(0.85, 0.50 + (moveRatio * 2.5)));

        // Intrinsic value calculation
        double strike =
                (pos.getAtmStrike() != null)
                        ? pos.getAtmStrike().doubleValue()
                        : entrySpot.doubleValue();
        double intrinsic =
                (pos.getDirection() == LowestVolumeDirection.LONG)
                        ? Math.max(0.0, currentSpot.doubleValue() - strike)
                        : Math.max(0.0, strike - currentSpot.doubleValue());

        double estPremVal =
                Math.max(intrinsic, entryPrem.doubleValue() + (spotMove * dynamicDelta));
        if (estPremVal < 0.50) {
            estPremVal = 0.50;
        }
        return BigDecimal.valueOf(estPremVal).setScale(2, RoundingMode.HALF_UP);
    }

    public double fetchOptionLtp(String symbol, String optionType, BigDecimal strike) {
        if (optionChainService != null) {
            try {
                var chain = optionChainService.getIndexOptionChain(symbol, strike, 2, true);
                if (chain != null && chain.strikes() != null) {
                    for (var s : chain.strikes()) {
                        if (s.strikePrice() != null && s.strikePrice().compareTo(strike) == 0) {
                            var contract = "PE".equalsIgnoreCase(optionType) ? s.put() : s.call();
                            if (contract != null
                                    && contract.ltp() != null
                                    && contract.ltp().doubleValue() > 0) {
                                return contract.ltp().doubleValue();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug(
                        "[LVR] Live option quote fetch failed for {} {} {}: {}",
                        symbol,
                        strike,
                        optionType,
                        e.getMessage());
            }
        }
        // Fallback: Query direct option quote via formatted exchange trading symbol
        try {
            if (marketDataService != null) {
                LocalDate expiry =
                        StockFnoRegistry.calculateTargetExpiry(
                                symbol, LocalDate.now(clock), false, 1);
                String tsym =
                        StockFnoRegistry.formatTradingSymbol(
                                symbol, expiry, strike, optionType, false);
                String tok = marketDataService.resolveToken(tsym);
                if (tok == null || tok.isBlank()) {
                    tok = marketDataService.resolveToken(symbol);
                }
                if (tok != null && !tok.isBlank()) {
                    JsonNode q = marketDataService.fetchQuote("NFO", tok);
                    if (q != null && q.has("lp")) {
                        double lp = q.get("lp").asDouble(0.0);
                        if (lp > 0.0) return lp;
                    }
                }
            }
        } catch (Exception ex) {
            log.debug(
                    "[LVR] Direct option contract lookup failed for {}: {}",
                    symbol,
                    ex.getMessage());
        }
        return 0.0;
    }

    public Map<String, StockQuoteSnapshot> fetchMorningQuotesUnified() {
        if (marketDataService == null) return Collections.emptyMap();
        java.util.Set<String> allSymbols =
                new java.util.LinkedHashSet<>(NiftySectorRegistry.NIFTY_50_CONSTITUENTS);
        for (List<String> constituents : NiftySectorRegistry.getSectorConstituents().values()) {
            allSymbols.addAll(constituents);
        }

        Map<String, StockQuoteSnapshot> quoteMap = new ConcurrentHashMap<>();
        log.info(
                "[LVR] Fetching morning quotes for {} unique universe symbols (delay: {}ms)...",
                allSymbols.size(),
                morningScanDelayMs);

        int count = 0;
        for (String sym : allSymbols) {
            count++;
            try {
                var info = StockFnoRegistry.get(sym);
                String token =
                        (info != null && info.token() != null)
                                ? info.token()
                                : marketDataService.resolveToken(sym);
                if (token == null || token.isBlank()) continue;
                String exchange =
                        (info != null && info.exchange() != null) ? info.exchange() : "NSE";
                JsonNode quote = marketDataService.fetchQuote(exchange, token);
                if (quote != null && quote.has("lp") && quote.has("c")) {
                    double lp = quote.get("lp").asDouble(0.0);
                    double c = quote.get("c").asDouble(0.0);
                    double o = quote.has("o") ? quote.get("o").asDouble(lp) : lp;
                    if (c > 0) {
                        double pct = (lp - c) / c * 100.0;
                        quoteMap.put(sym, new StockQuoteSnapshot(sym, lp, c, o, pct));
                    }
                }

                if (morningScanDelayMs > 0 && count < allSymbols.size()) {
                    try {
                        Thread.sleep(morningScanDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception e) {
                log.debug("[LVR] Error fetching morning quote for {}: {}", sym, e.getMessage());
            }
        }
        log.info(
                "[LVR] Morning quote fetch completed. Successfully fetched {} / {} symbols.",
                quoteMap.size(),
                allSymbols.size());
        return quoteMap;
    }

    public long getMorningScanDelayMs() {
        return morningScanDelayMs;
    }

    public void setMorningScanDelayMs(long morningScanDelayMs) {
        this.morningScanDelayMs = morningScanDelayMs;
    }

    // Getters / Setters for Controller, Scheduler & Tests
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LvrInstrumentType getInstrumentType() {
        return instrumentType;
    }

    public void setInstrumentType(LvrInstrumentType instrumentType) {
        this.instrumentType = instrumentType;
    }

    public LvrExitMode getExitMode() {
        return exitMode;
    }

    public void setExitMode(LvrExitMode exitMode) {
        this.exitMode = exitMode;
    }

    public double getPaperCapital() {
        return paperCapital;
    }

    public double getRiskPerTradePercent() {
        return riskPerTradePercent;
    }

    public double getRiskPerTradeAmount() {
        return paperCapital * (riskPerTradePercent / 100.0);
    }

    public int getMaxConcurrentTrades() {
        return maxConcurrentTrades;
    }

    public Set<String> getExhaustedSymbols() {
        return exhaustedSymbols;
    }

    public int getSetupTimeoutCandles() {
        return setupTimeoutCandles;
    }

    public void setSetupTimeoutCandles(int setupTimeoutCandles) {
        this.setupTimeoutCandles = setupTimeoutCandles;
    }

    public double getMaxSlippagePct() {
        return maxSlippagePct;
    }

    public void setMaxSlippagePct(double maxSlippagePct) {
        this.maxSlippagePct = maxSlippagePct;
    }

    public boolean isSectorMomentumFilterEnabled() {
        return sectorMomentumFilterEnabled;
    }

    public void setSectorMomentumFilterEnabled(boolean sectorMomentumFilterEnabled) {
        this.sectorMomentumFilterEnabled = sectorMomentumFilterEnabled;
    }

    /**
     * Re-checks whether the stock's parent sector is still aligned with the strategy direction. For
     * LONG: Sector average % change must be > 0.0% and advances >= declines. For SHORT: Sector
     * average % change must be < 0.0% and declines >= advances.
     */
    public boolean checkLiveSectorAlignment(String symbol, LowestVolumeDirection direction) {
        if (marketDataService == null) return true;

        String sectorName = NiftySectorRegistry.getSectorForSymbol(symbol);
        if (sectorName == null || sectorName.isBlank()) {
            if (sectorState != null
                    && sectorState.topSector() != null
                    && !sectorState.topSector().isBlank()) {
                sectorName = sectorState.topSector();
            } else {
                return true;
            }
        }

        List<String> constituents = NiftySectorRegistry.getStocksForSector(sectorName);
        if (constituents == null || constituents.isEmpty()) {
            return true;
        }

        double totalPctChange = 0.0;
        int count = 0;
        int advances = 0;
        int declines = 0;

        for (String constituent : constituents) {
            try {
                JsonNode qNode = fetchLiveQuoteNode(constituent);
                if (qNode != null) {
                    double lp = qNode.path("lp").asDouble(0.0);
                    double c = qNode.path("c").asDouble(0.0);
                    if (lp > 0.0 && c > 0.0) {
                        double pct = ((lp - c) / c) * 100.0;
                        totalPctChange += pct;
                        count++;
                        if (pct > 0.0) advances++;
                        else if (pct < 0.0) declines++;
                    }
                }
            } catch (Exception e) {
                log.debug(
                        "[LVR] Error fetching live quote for sector constituent {}: {}",
                        constituent,
                        e.getMessage());
            }
        }

        if (count == 0) {
            return true; // If data unavailable, fail safe and allow
        }

        double avgSectorPct = totalPctChange / count;
        boolean aligned;

        if (direction == LowestVolumeDirection.LONG) {
            aligned = (avgSectorPct > 0.0 && advances >= declines);
        } else {
            aligned = (avgSectorPct < 0.0 && declines >= advances);
        }

        log.info(
                "[LVR-SECTOR-CHECK] Rechecked {} sector '{}' at entry: AvgChange={}% (Advances={}, Declines={}, Total={}) | Aligned={}",
                symbol,
                sectorName,
                String.format(java.util.Locale.US, "%.2f", avgSectorPct),
                advances,
                declines,
                count,
                aligned);

        if (!aligned) {
            Instant lastAlert = sectorRejectionAlertCooldown.get(symbol);
            if (lastAlert == null || Instant.now(clock).isAfter(lastAlert.plusSeconds(300))) {
                sectorRejectionAlertCooldown.put(symbol, Instant.now(clock));
                if (telegramAlerts && telegramService != null) {
                    telegramService.sendTextMessage(
                            String.format(
                                    Locale.US,
                                    "⚠️ *LVR Entry Blocked (Sector Momentum Flipped)*\n"
                                            + "• Symbol: *%s* (Setup: *%s*)\n"
                                            + "• Parent Sector: *%s*\n"
                                            + "• Current Sector %% Change: `%+.2f%%` (Advances: %d, Declines: %d)\n"
                                            + "• Reason: *Sector turned %s*. Entry skipped to prevent fighting sector drag.",
                                    symbol,
                                    direction,
                                    sectorName,
                                    avgSectorPct,
                                    advances,
                                    declines,
                                    direction == LowestVolumeDirection.LONG
                                            ? "BEARISH / RED"
                                            : "BULLISH / GREEN"));
                }
            }
        }

        return aligned;
    }

    public boolean isVwapConfirmationEnabled() {
        return vwapConfirmationEnabled;
    }

    public void setVwapConfirmationEnabled(boolean vwapConfirmationEnabled) {
        this.vwapConfirmationEnabled = vwapConfirmationEnabled;
    }

    public boolean isOpening15mRangeFilterEnabled() {
        return opening15mRangeFilterEnabled;
    }

    public void setOpening15mRangeFilterEnabled(boolean opening15mRangeFilterEnabled) {
        this.opening15mRangeFilterEnabled = opening15mRangeFilterEnabled;
    }

    public int getDefaultLots() {
        return defaultLots;
    }

    public void setDefaultLots(int defaultLots) {
        this.defaultLots = defaultLots;
    }

    public int getMaxAttemptsPerSymbol() {
        return maxAttemptsPerSymbol;
    }

    public void setMaxAttemptsPerSymbol(int maxAttemptsPerSymbol) {
        this.maxAttemptsPerSymbol = maxAttemptsPerSymbol;
    }

    public double getMinBreadthPct() {
        return minBreadthPct;
    }

    public void setMinBreadthPct(double minBreadthPct) {
        this.minBreadthPct = minBreadthPct;
    }

    public double getMinStopLossPct() {
        return minStopLossPct;
    }

    public void setMinStopLossPct(double minStopLossPct) {
        this.minStopLossPct = minStopLossPct;
    }

    public double getMaxDailyLoss() {
        return maxDailyLoss;
    }

    public void setMaxDailyLoss(double maxDailyLoss) {
        this.maxDailyLoss = maxDailyLoss;
    }

    public double calculateTodayRealizedPnl() {
        LocalDate today = LocalDate.now(clock);
        return tradeHistory.stream()
                .filter(
                        p ->
                                p.getExitTime() != null
                                        && LocalDate.ofInstant(p.getExitTime(), IST).equals(today))
                .mapToDouble(p -> p.getTotalRealizedPnl().doubleValue())
                .sum();
    }

    public double calculateOpenPositionsUnrealizedPnl() {
        return calculateOpenPositionsUnrealizedPnl(null);
    }

    public double calculateOpenPositionsUnrealizedPnl(Map<String, JsonNode> quoteCache) {
        if (openPositions.isEmpty()) return 0.0;
        double totalUnrealized = 0.0;
        for (LowestVolumePaperPosition pos : openPositions.values()) {
            if (pos == null || pos.isClosed()) continue;
            try {
                JsonNode qNode =
                        (quoteCache != null && quoteCache.containsKey(pos.getSymbol()))
                                ? quoteCache.get(pos.getSymbol())
                                : (quoteCache != null
                                        ? quoteCache.computeIfAbsent(
                                                pos.getSymbol(), this::fetchLiveQuoteNode)
                                        : fetchLiveQuoteNode(pos.getSymbol()));
                double spotLtp =
                        (qNode != null && qNode.has("lp")) ? qNode.get("lp").asDouble(0.0) : 0.0;
                if (spotLtp <= 0) continue;
                BigDecimal spotPrice = BigDecimal.valueOf(spotLtp);
                BigDecimal exitVal =
                        (pos.getInstrumentType() == LvrInstrumentType.FUTURES)
                                ? spotPrice
                                : estimateOptionPremium(pos, spotPrice);

                BigDecimal priceDiff;
                if (pos.getInstrumentType() == LvrInstrumentType.FUTURES) {
                    priceDiff =
                            (pos.getDirection() == LowestVolumeDirection.LONG)
                                    ? exitVal.subtract(pos.getStockEntryPrice())
                                    : pos.getStockEntryPrice().subtract(exitVal);
                } else {
                    priceDiff = exitVal.subtract(pos.getEntryPremium());
                }
                int remQty =
                        pos.getRemainingQuantity() > 0
                                ? pos.getRemainingQuantity()
                                : pos.getTotalQuantity();
                double pnl = priceDiff.multiply(BigDecimal.valueOf(remQty)).doubleValue();
                totalUnrealized += pnl;
            } catch (Exception e) {
                log.debug(
                        "[LVR] Error calculating unrealized PnL for {}: {}",
                        pos.getSymbol(),
                        e.getMessage());
            }
        }
        return totalUnrealized;
    }

    public double calculateTodayTotalRiskPnl() {
        return calculateTodayRealizedPnl() + calculateOpenPositionsUnrealizedPnl();
    }

    public boolean isDailyCircuitBreakerTripped() {
        return isDailyCircuitBreakerTripped(null);
    }

    public boolean isDailyCircuitBreakerTripped(Map<String, JsonNode> quoteCache) {
        if (maxDailyLoss <= 0.0) return false;
        double totalRiskPnl =
                calculateTodayRealizedPnl() + calculateOpenPositionsUnrealizedPnl(quoteCache);
        if (totalRiskPnl <= -maxDailyLoss) {
            if (dailyCircuitBreakerAlertSent.compareAndSet(false, true)) {
                log.warn(
                        "[LVR] Daily Max Loss Circuit Breaker tripped: Total risk loss (₹{}) reached limit (₹{}). Halting new entries.",
                        String.format(Locale.US, "%.2f", Math.abs(totalRiskPnl)),
                        String.format(Locale.US, "%.2f", maxDailyLoss));
                if (telegramAlerts && telegramService != null) {
                    telegramService.sendTextMessage(
                            String.format(
                                    Locale.US,
                                    "🚨 *LVR Daily Circuit Breaker Activated*\n"
                                            + "• Today's Total Loss (Realized + Floating): `₹%.2f`\n"
                                            + "• Daily Loss Limit: `₹%.2f`\n"
                                            + "• Action: *Halting all new trade entries for the day* to protect capital.",
                                    totalRiskPnl,
                                    maxDailyLoss));
                }
            }
            return true;
        }
        return false;
    }

    public boolean isDynamicPositionSizing() {
        return dynamicPositionSizing;
    }

    public void setDynamicPositionSizing(boolean dynamicPositionSizing) {
        this.dynamicPositionSizing = dynamicPositionSizing;
    }

    public boolean isTelegramAlerts() {
        return telegramAlerts;
    }

    public void setTelegramAlerts(boolean telegramAlerts) {
        this.telegramAlerts = telegramAlerts;
    }

    public boolean isTelegramArmedAlerts() {
        return telegramArmedAlerts;
    }

    public void setTelegramArmedAlerts(boolean telegramArmedAlerts) {
        this.telegramArmedAlerts = telegramArmedAlerts;
    }

    /**
     * Executes a pure session replay of historical 5-minute candles through the exact production
     * strategy rules, state machine, and risk-reward execution engine of
     * LowestVolumeReversalService.
     *
     * @param symbol Trading symbol (e.g., SUNPHARMA)
     * @param direction Setup direction (LONG or SHORT)
     * @param sessionCandles List of 5-minute candles for the intraday session
     * @return List of completed LowestVolumePaperPosition trade records executed by the service
     */
    public List<LowestVolumePaperPosition> replaySession(
            String symbol, LowestVolumeDirection direction, List<Candle> sessionCandles) {
        List<LowestVolumePaperPosition> completedTrades = new ArrayList<>();
        if (sessionCandles == null || sessionCandles.size() < 4) {
            return completedTrades;
        }

        LowestVolumeSetup activeSetup = null;
        LowestVolumePaperPosition openPos = null;
        int attempt = 0;
        Instant lastExitTime = null;

        for (int i = 3; i < sessionCandles.size(); i++) {
            Candle currentCandle = sessionCandles.get(i);
            LocalTime candleTime = LocalTime.ofInstant(currentCandle.timestamp(), IST);
            List<Candle> historicalSubList = sessionCandles.subList(0, i + 1);

            // 1. Manage Open Position against current candle prices
            if (openPos != null && !openPos.isClosed()) {
                BigDecimal high = currentCandle.high();
                BigDecimal low = currentCandle.low();
                BigDecimal close = currentCandle.close();

                // Check Stop Loss breach on spot FIRST (prioritized over target to mirror live
                // execution)
                boolean slHit = false;
                if (openPos.getDirection() == LowestVolumeDirection.SHORT) {
                    if (high.compareTo(openPos.getCurrentStockSl()) >= 0) {
                        slHit = true;
                    }
                } else if (openPos.getDirection() == LowestVolumeDirection.LONG) {
                    if (low.compareTo(openPos.getCurrentStockSl()) <= 0) {
                        slHit = true;
                    }
                }

                // Check Target breach on spot SECOND (only if SL not hit)
                boolean targetHit = false;
                if (!slHit && !openPos.isPartialBooked()) {
                    if (openPos.getDirection() == LowestVolumeDirection.SHORT) {
                        if (low.compareTo(openPos.getTarget1StockPrice()) <= 0) {
                            targetHit = true;
                        }
                    } else if (openPos.getDirection() == LowestVolumeDirection.LONG) {
                        if (high.compareTo(openPos.getTarget1StockPrice()) >= 0) {
                            targetHit = true;
                        }
                    }
                }

                if (slHit) {
                    String slReason =
                            openPos.isPartialBooked() ? "TRAILING_COST_SL_HIT" : "SPOT_SL_HIT";
                    if (openPos.getInstrumentType() == LvrInstrumentType.FUTURES) {
                        openPos.close(
                                openPos.getCurrentStockSl(), slReason, currentCandle.timestamp());
                    } else {
                        BigDecimal prem =
                                estimateOptionPremium(openPos, openPos.getCurrentStockSl());
                        openPos.close(prem, slReason, currentCandle.timestamp());
                    }
                    completedTrades.add(openPos);
                    lastExitTime = currentCandle.timestamp();
                    openPos = null;
                    activeSetup = null;
                    if (attempt >= maxAttemptsPerSymbol) {
                        break; // Exhausted max attempt(s)
                    }
                } else if (targetHit) {
                    LvrExitMode currentExitMode =
                            (openPos.getExitMode() != null) ? openPos.getExitMode() : exitMode;
                    if (currentExitMode == LvrExitMode.FULL_TARGET_1_2
                            || currentExitMode == LvrExitMode.FULL_TARGET_1_4) {
                        // 100% Full Exit at Target
                        if (openPos.getInstrumentType() == LvrInstrumentType.FUTURES) {
                            openPos.closeFullFutures(
                                    openPos.getTarget1StockPrice(),
                                    "TARGET_1_2_FULL_EXIT",
                                    currentCandle.timestamp());
                        } else {
                            BigDecimal prem =
                                    estimateOptionPremium(openPos, openPos.getTarget1StockPrice());
                            openPos.close(prem, "TARGET_1_2_FULL_EXIT", currentCandle.timestamp());
                        }
                        completedTrades.add(openPos);
                        lastExitTime = currentCandle.timestamp();
                        openPos = null;
                        activeSetup = null;
                        break; // Exhausted for the day on target hit
                    } else {
                        // 50% Partial Booking
                        BigDecimal partialExitPrice =
                                (openPos.getInstrumentType() == LvrInstrumentType.FUTURES)
                                        ? openPos.getTarget1StockPrice()
                                        : estimateOptionPremium(
                                                openPos, openPos.getTarget1StockPrice());
                        openPos.executePartialBook(partialExitPrice, currentCandle.timestamp());
                        if (openPos.isClosed()) {
                            completedTrades.add(openPos);
                            lastExitTime = currentCandle.timestamp();
                            openPos = null;
                            activeSetup = null;
                            break;
                        }
                        if (activeSetup != null) {
                            activeSetup.transitionTo(
                                    LowestVolumeSetupState.PARTIAL_BOOKED, "1:2 RR partial booked");
                        }
                    }
                } else if (openPos.isPartialBooked()
                        && !openPos.isClosed()
                        && (openPos.getExitMode()
                                        == LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500
                                || openPos.getExitMode() == LvrExitMode.PARTIAL_RUNNER_10EMA)
                        && taService != null
                        && i >= 10) {
                    // 10 EMA trailing check
                    double[] closes =
                            historicalSubList.stream()
                                    .mapToDouble(c -> c.close().doubleValue())
                                    .toArray();
                    double[] emaSeries = taService.calculateEmaSeries(closes, 10);
                    double ema10 = emaSeries[emaSeries.length - 1];
                    boolean emaTrailExit = false;
                    if (!Double.isNaN(ema10)) {
                        if (openPos.getDirection() == LowestVolumeDirection.SHORT
                                && close.doubleValue() > ema10) {
                            emaTrailExit = true;
                        } else if (openPos.getDirection() == LowestVolumeDirection.LONG
                                && close.doubleValue() < ema10) {
                            emaTrailExit = true;
                        }
                    }
                    if (emaTrailExit) {
                        BigDecimal exitVal =
                                (openPos.getInstrumentType() == LvrInstrumentType.FUTURES)
                                        ? close
                                        : estimateOptionPremium(openPos, close);
                        openPos.close(exitVal, "10_EMA_TRAIL_EXIT", currentCandle.timestamp());
                        completedTrades.add(openPos);
                        lastExitTime = currentCandle.timestamp();
                        openPos = null;
                        activeSetup = null;
                        break;
                    }
                }

                // EOD Hard Exit at 15:00
                if (openPos != null
                        && !openPos.isClosed()
                        && !candleTime.isBefore(TIME_HARD_EXIT)) {
                    if (openPos.getInstrumentType() == LvrInstrumentType.FUTURES) {
                        openPos.close(close, "EOD_1500_HARD_EXIT", currentCandle.timestamp());
                    } else {
                        BigDecimal prem = estimateOptionPremium(openPos, close);
                        openPos.close(prem, "EOD_1500_HARD_EXIT", currentCandle.timestamp());
                    }
                    completedTrades.add(openPos);
                    openPos = null;
                    activeSetup = null;
                    break;
                }

                continue;
            }

            // 2. Setup Evaluation & Trigger Arming / Trailing
            if (candleTime.isBefore(TIME_ENTRY_CUTOFF) && attempt < maxAttemptsPerSymbol) {
                LowestVolumeSetup evaluated =
                        evaluateCandleSequence(symbol, direction, historicalSubList, lastExitTime);
                if (evaluated.getState() == LowestVolumeSetupState.TRIGGER_ARMED) {
                    activeSetup = evaluated;
                }
            }

            // 3. Intra-candle trigger execution check
            if (activeSetup != null
                    && activeSetup.getState() == LowestVolumeSetupState.TRIGGER_ARMED
                    && openPos == null
                    && attempt < maxAttemptsPerSymbol) {
                BigDecimal high = currentCandle.high();
                BigDecimal low = currentCandle.low();
                BigDecimal triggerPrice = activeSetup.getTriggerPrice();
                BigDecimal slPrice = activeSetup.getStopLossPrice();

                // Invalidate if SL breached before trigger
                boolean slBreached = false;
                if (direction == LowestVolumeDirection.SHORT && high.compareTo(slPrice) >= 0) {
                    slBreached = true;
                } else if (direction == LowestVolumeDirection.LONG && low.compareTo(slPrice) <= 0) {
                    slBreached = true;
                }

                boolean triggered = false;
                if (direction == LowestVolumeDirection.SHORT && low.compareTo(triggerPrice) <= 0) {
                    triggered = true;
                } else if (direction == LowestVolumeDirection.LONG
                        && high.compareTo(triggerPrice) >= 0) {
                    triggered = true;
                }

                if (slBreached) {
                    activeSetup = null;
                    continue;
                }

                if (triggered) {
                    if (vwapConfirmationEnabled && taService != null) {
                        double[] vwapSeries = taService.calculateVwapSeries(historicalSubList);
                        double currentVwap =
                                (vwapSeries.length > 0) ? vwapSeries[vwapSeries.length - 1] : 0.0;
                        if (currentVwap > 0.0) {
                            boolean vwapConfirmed = false;
                            if (direction == LowestVolumeDirection.LONG) {
                                vwapConfirmed =
                                        (triggerPrice.compareTo(BigDecimal.valueOf(currentVwap))
                                                > 0);
                            } else if (direction == LowestVolumeDirection.SHORT) {
                                vwapConfirmed =
                                        (triggerPrice.compareTo(BigDecimal.valueOf(currentVwap))
                                                < 0);
                            }

                            if (!vwapConfirmed) {
                                // Discard stock for the day
                                activeSetup = null;
                                break;
                            }
                        }
                    }

                    attempt++;
                    StockFnoRegistry.InstrumentInfo fno = StockFnoRegistry.get(symbol);
                    int lotSize = (fno != null) ? fno.lotSize() : 100;
                    BigDecimal strikeStep =
                            (fno != null) ? fno.strikeStep() : BigDecimal.valueOf(10);
                    BigDecimal unitRisk =
                            triggerPrice.subtract(activeSetup.getStopLossPrice()).abs();
                    int lots = defaultLots > 0 ? defaultLots : 2;
                    int totalQty = lots * lotSize;
                    BigDecimal plannedRisk =
                            (instrumentType == LvrInstrumentType.OPTIONS)
                                    ? unitRisk.multiply(BigDecimal.valueOf(0.50))
                                            .multiply(BigDecimal.valueOf(totalQty))
                                            .setScale(2, RoundingMode.HALF_UP)
                                    : unitRisk.multiply(BigDecimal.valueOf(totalQty))
                                            .setScale(2, RoundingMode.HALF_UP);
                    BigDecimal actualTarget1;
                    if (direction == LowestVolumeDirection.SHORT) {
                        actualTarget1 =
                                roundToTick(
                                        triggerPrice.subtract(
                                                unitRisk.multiply(BigDecimal.valueOf(2))));
                    } else {
                        actualTarget1 =
                                roundToTick(
                                        triggerPrice.add(unitRisk.multiply(BigDecimal.valueOf(2))));
                    }
                    String tradeId = "REPLAY-" + attempt;
                    if (instrumentType == LvrInstrumentType.FUTURES) {
                        openPos =
                                new LowestVolumePaperPosition(
                                        tradeId,
                                        symbol,
                                        LvrInstrumentType.FUTURES,
                                        exitMode,
                                        symbol + " FUT",
                                        lotSize,
                                        lots,
                                        direction,
                                        triggerPrice,
                                        activeSetup.getStopLossPrice(),
                                        actualTarget1,
                                        totalQty,
                                        plannedRisk,
                                        currentCandle.timestamp());
                    } else {
                        BigDecimal atmStrike = resolveAtmStrike(triggerPrice, strikeStep);
                        String optType = (direction == LowestVolumeDirection.SHORT) ? "PE" : "CE";
                        String optSymbol = symbol + " ATM " + atmStrike + optType;
                        BigDecimal entryPrem =
                                BigDecimal.valueOf(
                                                Math.max(0.50, triggerPrice.doubleValue() * 0.018))
                                        .setScale(2, RoundingMode.HALF_UP);
                        openPos =
                                new LowestVolumePaperPosition(
                                        tradeId,
                                        symbol,
                                        exitMode,
                                        optType,
                                        optSymbol,
                                        atmStrike,
                                        lotSize,
                                        lots,
                                        direction,
                                        entryPrem,
                                        triggerPrice,
                                        activeSetup.getStopLossPrice(),
                                        actualTarget1,
                                        totalQty,
                                        plannedRisk,
                                        currentCandle.timestamp());
                    }
                }
            }
        }

        return completedTrades;
    }

    public boolean isNiftyBullish() {
        return niftyBullish;
    }

    public boolean isUniverseScanCompletedToday() {
        return universeScanCompletedToday;
    }

    public void setUniverseScanCompletedToday(boolean universeScanCompletedToday) {
        this.universeScanCompletedToday = universeScanCompletedToday;
    }

    public LowestVolumeSectorState getSectorState() {
        return sectorState;
    }

    public Map<String, LowestVolumeSetup> getActiveSetups() {
        return activeSetups;
    }

    public Map<String, LowestVolumePaperPosition> getOpenPositions() {
        return openPositions;
    }

    public List<LowestVolumePaperPosition> getTradeHistory() {
        return tradeHistory;
    }

    public List<String> getCurrentTopGainers() {
        return currentTopGainers;
    }

    public List<String> getCurrentTopLosers() {
        return currentTopLosers;
    }

    public List<StockQuoteSnapshot> getCurrentTopGainerSnapshots() {
        return currentTopGainerSnapshots;
    }

    public List<StockQuoteSnapshot> getCurrentTopLoserSnapshots() {
        return currentTopLoserSnapshots;
    }

    public void setClock(java.time.Clock clock) {
        this.clock = clock;
    }

    public void sendScanTelegramReport() {
        if (telegramAlerts && telegramService != null) {
            telegramService.sendTextMessage("📊 *LVR Sector & Watchlist Report*\n" + sectorState);
        }
    }

    private String resolveBrokerTradingSymbol(LowestVolumePaperPosition pos) {
        if (pos == null) {
            return "";
        }
        if (pos.getInstrumentType() == LvrInstrumentType.FUTURES) {
            return StockFnoRegistry.formatFuturesTradingSymbol(pos.getSymbol(), null);
        } else if (pos.getAtmStrike() != null) {
            return StockFnoRegistry.formatTradingSymbol(
                    pos.getSymbol(), null, pos.getAtmStrike(), pos.getOptionType(), false);
        }
        return pos.getContractSymbol();
    }

    private void publishSignal(
            String underlying,
            String contract,
            com.tradingbot.strategy.SignalAction action,
            BigDecimal price,
            BigDecimal sl,
            BigDecimal target,
            int quantity,
            String reason,
            Map<String, Object> metadata) {
        if (signalPublisher != null && action != null) {
            com.tradingbot.strategy.TradeSignal signal =
                    com.tradingbot.strategy.TradeSignal.of(
                            "LVR_" + instrumentType,
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
}
