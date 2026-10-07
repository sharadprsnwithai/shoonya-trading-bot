package com.tradingbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.HistoricalOhlcCacheService;
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
import java.nio.file.Path;
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
    public static final LocalTime TIME_ENTRY_CUTOFF = LocalTime.of(11, 30);
    public static final LocalTime TIME_HARD_EXIT = LocalTime.of(15, 0);

    public static final String STRATEGY_ID = "LOWEST_VOLUME_REVERSAL";

    /** N5: bounded lazy fetch attempts before the PDH/PDL gate fails closed permanently. */
    private static final int MAX_PDH_FETCH_ATTEMPTS = 2;

    /** H5: consecutive quote failures before a loud stall alert is emitted for a symbol. */
    private static final int QUOTE_STALL_ALERT_THRESHOLD = 3;

    private final ShoonyaMarketDataService marketDataService;
    private final TechnicalAnalysisService taService;
    private final TelegramService telegramService;
    private final ShoonyaConfig config;
    private final com.tradingbot.marketdata.ShoonyaOptionChainService optionChainService;
    private final LowestVolumeReversalScanner scanner;
    private final com.tradingbot.bus.SignalPublisher signalPublisher;
    private volatile HistoricalOhlcCacheService ohlcCacheService;

    private java.time.Clock clock = java.time.Clock.system(IST);

    @Value("${trading-bot.strategy.lowest-volume.enabled:true}")
    private volatile boolean enabled = true;

    @Value("${trading-bot.strategy.lowest-volume.instrument-type:FUTURES}")
    private volatile LvrInstrumentType instrumentType = LvrInstrumentType.FUTURES;

    @Value("${trading-bot.strategy.lowest-volume.order-type:LMT}")
    private volatile String orderType = "LMT";

    @Value("${trading-bot.strategy.lowest-volume.exit-mode:PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500}")
    private volatile LvrExitMode exitMode = LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500;

    @Value("${trading-bot.strategy.lowest-volume.paper-capital:1000000.0}")
    private volatile double paperCapital = 1000000.0;

    @Value("${trading-bot.strategy.lowest-volume.risk-per-trade-percent:1.0}")
    private volatile double riskPerTradePercent = 1.0;

    @Value("${trading-bot.strategy.lowest-volume.max-concurrent-trades:5}")
    private volatile int maxConcurrentTrades = 5;

    @Value("${trading-bot.strategy.lowest-volume.max-attempts-per-symbol:2}")
    private volatile int maxAttemptsPerSymbol = 2;

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

    @Value("${trading-bot.strategy.lowest-volume.pcr-filter-enabled:true}")
    private volatile boolean pcrFilterEnabled = true;

    @Value("${trading-bot.strategy.lowest-volume.pcr-min-long:0.85}")
    private volatile double pcrMinLong = 0.85;

    @Value("${trading-bot.strategy.lowest-volume.pcr-max-short:1.15}")
    private volatile double pcrMaxShort = 1.15;

    @Value("${trading-bot.strategy.lowest-volume.option-sr-filter-enabled:true}")
    private volatile boolean optionSrFilterEnabled = true;

    @Value("${trading-bot.strategy.lowest-volume.option-sr-buffer-pct:0.0}")
    private volatile double optionSrBufferPct = 0.0;

    @Value("${trading-bot.strategy.lowest-volume.entry-cutoff:11:30}")
    private volatile LocalTime entryCutoffTime = TIME_ENTRY_CUTOFF;

    @Value("${trading-bot.strategy.lowest-volume.pdh-pdl-filter-enabled:true}")
    private volatile boolean pdhPdlFilterEnabled = true;

    @Value("${trading-bot.strategy.lowest-volume.scanner-mode:HYBRID}")
    private volatile String scannerMode = "HYBRID";

    @Value("${trading-bot.strategy.lowest-volume.oi-spurts-top-n:5}")
    private volatile int oiSpurtsTopN = 5;

    @Value("${trading-bot.strategy.lowest-volume.sector-momentum-filter-enabled:true}")
    private volatile boolean sectorMomentumFilterEnabled = true;

    @Value("${trading-bot.strategy.lowest-volume.vande-bharat-enabled:false}")
    private volatile boolean vandeBharatEnabled = false;

    @Value("${trading-bot.strategy.lowest-volume.target-rr:2.5}")
    private volatile double targetRr = 2.5;

    @Value("${trading-bot.strategy.lowest-volume.max-daily-losses:2}")
    private volatile int maxDailyLosses = 2;

    private final AtomicInteger todayLossCount = new AtomicInteger(0);

    /**
     * M11 / {@code lvr_30sec_live_check_spec.md §3}: gates the 30-second live breach check
     * (trigger/SL/target monitoring on live LTP). When disabled the strategy falls back to
     * 5-minute-cycle-only behavior.
     */
    @Value("${trading-bot.strategy.lowest-volume.live-breach-check-enabled:true}")
    private volatile boolean liveBreachCheckEnabled = true;

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
    private volatile boolean standDownToday = false;

    private volatile LowestVolumeSectorState sectorState = LowestVolumeSectorState.empty();
    private volatile boolean niftyBullish = true;
    private volatile boolean universeScanCompletedToday = false;
    private final AtomicInteger tradeCounter = new AtomicInteger(1);
    private final java.util.concurrent.atomic.AtomicBoolean dailyCircuitBreakerAlertSent =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean isScanning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // ---- Bug-fix state (round 2) ----
    /** H5: per-symbol consecutive quote-failure counters for open positions. */
    private final Map<String, Integer> quoteFailureCounts = new ConcurrentHashMap<>();

    /** H5: symbols already alerted about stalled quotes (one alert per symbol per day). */
    private final Set<String> quoteStallAlerted = ConcurrentHashMap.newKeySet();

    /**
     * M1: per-symbol cooldown before a data-dependent gate (slippage/sector/premium) is retried.
     */
    private final Map<String, Instant> gateRetryNotBefore = new ConcurrentHashMap<>();

    /** M1/M9: per-symbol consecutive no-data retries for the sector gate before exhaustion. */
    private final Map<String, Integer> gateRetryCounts = new ConcurrentHashMap<>();

    /** M2: latched daily circuit breaker — once tripped it stays tripped until resetDaily(). */
    private final java.util.concurrent.atomic.AtomicBoolean dailyCircuitBreakerTripped =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** H6: archive of closed trades preserved across intraday resets (never wipe realized P&L). */
    private final List<LowestVolumePaperPosition> tradeArchive =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** H6: realized P&L carried in the archive for today (feeds circuit breaker + /status). */
    private volatile double archivedRealizedPnl = 0.0;

    /** H6: trading day the archive belongs to. */
    private volatile LocalDate archiveDate = null;

    /** L2: cached unrealized P&L refreshed by the 30s tick so /status does no network I/O. */
    private volatile double cachedUnrealizedPnl = 0.0;

    private volatile java.time.Instant lastUnrealizedRefresh = null;

    /** M10: JSON snapshot file for open positions / trade history / daily state. */
    @Value("${trading-bot.strategy.lowest-volume.state-file:./data/lvr-state.json}")
    private volatile String stateFilePath = "./data/lvr-state.json";

    /**
     * M10: enable/disable disk persistence. Safe default OFF for directly-instantiated (test)
     * services; Spring environments turn it on via application.properties / env override.
     */
    @Value("${trading-bot.strategy.lowest-volume.state-persistence-enabled:false}")
    private volatile boolean statePersistenceEnabled = false;

    /** H9/M11: cutoff after which a failed morning scan permanently stands down (now bound). */
    @Value("${trading-bot.strategy.lowest-volume.scanner-fallback-cutoff:10:00}")
    private volatile LocalTime scannerFallbackCutoff = TIME_SCANNER_CUTOFF;

    @Autowired
    public LowestVolumeReversalService(
            ShoonyaMarketDataService marketDataService,
            TechnicalAnalysisService taService,
            TelegramService telegramService,
            ShoonyaConfig config,
            @Autowired(required = false)
                    com.tradingbot.marketdata.ShoonyaOptionChainService optionChainService,
            @Autowired(required = false) LowestVolumeReversalScanner scanner,
            @Autowired(required = false) com.tradingbot.bus.SignalPublisher signalPublisher,
            @Autowired(required = false) HistoricalOhlcCacheService ohlcCacheService) {
        this.marketDataService = marketDataService;
        this.taService = taService;
        this.telegramService = telegramService;
        this.config = config;
        this.optionChainService = optionChainService;
        this.scanner = (scanner != null) ? scanner : new LowestVolumeReversalScanner();
        this.signalPublisher = signalPublisher;
        this.ohlcCacheService = ohlcCacheService;
    }

    // --- M10: disk persistence ---

    /** Builds the current state snapshot for persistence. */
    private com.tradingbot.persistence.LvrStateStore.DailyState buildState() {
        com.tradingbot.persistence.LvrStateStore.DailyState state =
                new com.tradingbot.persistence.LvrStateStore.DailyState();
        state.date = LocalDate.now(clock);
        state.savedAt = Instant.now(clock);
        for (LowestVolumePaperPosition p : openPositions.values()) {
            state.openPositions.add(LowestVolumePaperPosition.snapshotOf(p));
        }
        for (LowestVolumePaperPosition p : tradeHistory) {
            state.tradeHistory.add(LowestVolumePaperPosition.snapshotOf(p));
        }
        state.exhaustedSymbols.addAll(exhaustedSymbols);
        state.archivedRealizedPnl = archivedRealizedPnl;
        state.archiveDate = archiveDate;
        state.circuitBreakerTripped = dailyCircuitBreakerTripped.get();
        state.standDownToday = standDownToday;
        return state;
    }

    /** M10: persists current state; failures are logged, never thrown (best-effort). */
    void persistState() {
        if (!statePersistenceEnabled) return;
        try {
            com.tradingbot.persistence.LvrStateStore.save(Path.of(stateFilePath), buildState());
        } catch (Exception e) {
            log.error("[LVR] Failed to persist state to {}: {}", stateFilePath, e.getMessage());
        }
    }

    /** M10: restores persisted state on startup so a restart never orphans positions. */
    @jakarta.annotation.PostConstruct
    void restorePersistedState() {
        if (!statePersistenceEnabled) return;
        try {
            com.tradingbot.persistence.LvrStateStore.DailyState state =
                    com.tradingbot.persistence.LvrStateStore.load(Path.of(stateFilePath));
            if (state == null) {
                log.info("[LVR] No persisted state found at {} — starting fresh.", stateFilePath);
                return;
            }
            LocalDate today = LocalDate.now(clock);
            if (state.date != null && !state.date.equals(today)) {
                log.warn(
                        "[LVR] Persisted state at {} is from {} (today is {}) — ignoring stale"
                                + " snapshot.",
                        stateFilePath,
                        state.date,
                        today);
                return;
            }
            int restoredOpen = 0;
            int restoredClosed = 0;
            if (state.openPositions != null) {
                for (var snap : state.openPositions) {
                    LowestVolumePaperPosition p = LowestVolumePaperPosition.restoreFrom(snap);
                    if (p != null && !p.isClosed()) {
                        openPositions.putIfAbsent(p.getSymbol(), p);
                        restoredOpen++;
                    }
                }
            }
            if (state.tradeHistory != null) {
                for (var snap : state.tradeHistory) {
                    LowestVolumePaperPosition p = LowestVolumePaperPosition.restoreFrom(snap);
                    if (p != null && p.isClosed()) {
                        tradeHistory.add(p);
                        restoredClosed++;
                    }
                }
            }
            if (state.exhaustedSymbols != null) {
                exhaustedSymbols.addAll(state.exhaustedSymbols);
            }
            archivedRealizedPnl = state.archivedRealizedPnl;
            archiveDate = state.archiveDate;
            if (state.circuitBreakerTripped) {
                dailyCircuitBreakerTripped.set(true);
            }
            standDownToday = state.standDownToday;
            log.info(
                    "[LVR] Restored persisted state: {} open position(s), {} closed trade(s),"
                            + " breakerLatched={}, standDown={} (file: {}).",
                    restoredOpen,
                    restoredClosed,
                    state.circuitBreakerTripped,
                    state.standDownToday,
                    stateFilePath);
        } catch (Exception e) {
            log.error(
                    "[LVR] Failed to restore persisted state from {}: {}",
                    stateFilePath,
                    e.getMessage(),
                    e);
        }
    }

    /** M10: best-effort final snapshot on shutdown. */
    @jakarta.annotation.PreDestroy
    void persistStateOnShutdown() {
        if (openPositions.isEmpty() && tradeHistory.isEmpty()) return;
        log.info("[LVR] Writing final state snapshot on shutdown...");
        persistState();
    }

    public LowestVolumeReversalService(
            ShoonyaMarketDataService marketDataService,
            TechnicalAnalysisService taService,
            TelegramService telegramService,
            ShoonyaConfig config,
            com.tradingbot.marketdata.ShoonyaOptionChainService optionChainService,
            LowestVolumeReversalScanner scanner,
            com.tradingbot.bus.SignalPublisher signalPublisher) {
        this(
                marketDataService,
                taService,
                telegramService,
                config,
                optionChainService,
                scanner,
                signalPublisher,
                null);
    }

    public LowestVolumeReversalService(
            ShoonyaMarketDataService marketDataService,
            TechnicalAnalysisService taService,
            TelegramService telegramService,
            ShoonyaConfig config,
            LowestVolumeReversalScanner scanner) {
        this(marketDataService, taService, telegramService, config, null, scanner, null, null);
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

            LocalTime nowTime = LocalTime.now(clock);

            if (!nowTime.isBefore(TIME_HARD_EXIT)) {
                executeHardExit(nowTime);
                return;
            }

            // N4: open positions are evaluated exactly once per cycle here (candle-close pass).
            // Later guards return early without re-evaluating, so each position costs one quote
            // fetch per cycle instead of two.
            if (!openPositions.isEmpty()) {
                evaluateOpenPositions(nowTime);
            }

            if (!enabled) {
                log.debug("[LVR] Strategy is currently disabled for new entries.");
                return;
            }

            if (nowTime.isBefore(TIME_SESSION_START)) {
                log.debug("[LVR] Before market open (09:15 IST). Standing by.");
                return;
            }

            if (nowTime.isBefore(TIME_SCANNER_START)) {
                log.info("[LVR] 09:15 - 09:25 IST: Pre-scanner settlement window. No trades.");
                return;
            }

            if (!universeScanCompletedToday) {
                if (nowTime.isAfter(scannerFallbackCutoff)) {
                    // H9: past the fallback cutoff with no candidates — stand down for the day
                    // (sticky, H10) and alert once instead of logging every cycle.
                    if (!standDownToday) {
                        standDownToday = true;
                        log.warn(
                                "[LVR] Past {} fallback cutoff. No valid morning candidates found"
                                        + " today. Standing down for the day.",
                                scannerFallbackCutoff);
                        if (telegramAlerts && telegramService != null) {
                            telegramService.sendTextMessage(
                                    "⚠️ *LVR Stand-Down*\n• Reason: Morning scan produced no"
                                            + " candidates past "
                                            + scannerFallbackCutoff
                                            + "\n• Status: *Standing down for the day*");
                        }
                    }
                    return;
                }
                log.info("[LVR] Triggering 09:25 AM morning sentiment & sector scan...");
                runMorningUniverseScan();
                if (!universeScanCompletedToday) {
                    log.info("[LVR] Morning scan pending valid sector candidates. Will retry.");
                    return;
                }
            }

            if (nowTime.isAfter(entryCutoffTime)) {
                log.info(
                        "[LVR] Past {} cutoff. Skipping new setups; managing open positions only.",
                        entryCutoffTime);
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
            // N4: no second evaluateOpenPositions here — positions were evaluated once above;
            // the 30s tick covers intra-cycle exits for any position opened this cycle.
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

            // If OI_SPURTS mode is active, select top institutional F&O stocks directly
            if ("OI_SPURTS".equalsIgnoreCase(scannerMode) && scanner != null) {
                boolean oiDataAvailable =
                        universeQuotes.values().stream().anyMatch(q -> q.openInterest() > 0);
                if (!oiDataAvailable) {
                    // H8: OI data missing entirely — fail the scan loudly instead of silently
                    // running a different selection mode than the operator configured.
                    log.error(
                            "[LVR] OI_SPURTS mode active but no quote carried open interest"
                                    + " (`oi` missing/zero across the universe). Scan failed;"
                                    + " will retry next cycle. Sector Rotation fallback disabled.");
                    this.universeScanCompletedToday = false;
                    if (telegramAlerts && telegramService != null) {
                        telegramService.sendTextMessage(
                                "🔴 *LVR OI_SPURTS Scan Failed*\n"
                                        + "• Reason: No open-interest data in GetQuotes response\n"
                                        + "• Status: *Retrying next cycle (fallback disabled)*");
                    }
                    return;
                }
                List<String> oiCandidates = scanner.scanOiSpurts(universeQuotes, oiSpurtsTopN);
                if (!oiCandidates.isEmpty()) {
                    activeSetups.clear();
                    for (String sym : oiCandidates) {
                        StockQuoteSnapshot snap = universeQuotes.get(sym);
                        LowestVolumeDirection symDir =
                                (snap != null && snap.pctChange() < 0)
                                        ? LowestVolumeDirection.SHORT
                                        : LowestVolumeDirection.LONG;
                        LowestVolumeSetup s = new LowestVolumeSetup(sym, symDir);
                        if (snap != null) {
                            s.setOiChangePct(snap.oiPctChange());
                        }
                        initPdhPdlForSetup(s);
                        activeSetups.put(sym, s);
                    }

                    this.universeScanCompletedToday = true;
                    this.lastScanDate = LocalDate.now(clock);

                    log.info(
                            "[LVR] Morning OI Spurts Scan complete. Top {} candidates: {}",
                            oiCandidates.size(),
                            oiCandidates);

                    if (telegramAlerts && telegramService != null) {
                        telegramService.sendTextMessage(
                                String.format(
                                        "📊 *LVR 09:25 AM OI Spurts Morning Scan*\n"
                                                + "• Candidates (%d): `%s`\n"
                                                + "• Setup Mode: *5m LVR & Vande Bharat*",
                                        oiCandidates.size(), String.join(", ", oiCandidates)));
                    }
                    return;
                } else {
                    log.warn(
                            "[LVR] OI Spurts returned 0 candidates (OI data present). Falling back to Sector Rotation scan.");
                    if (telegramAlerts && telegramService != null) {
                        telegramService.sendTextMessage(
                                "ℹ️ *LVR OI Spurts: no qualifying spurt*\n"
                                        + "• OI data present but no symbol crossed the threshold\n"
                                        + "• Falling back to *Sector Rotation* selection this cycle");
                    }
                }
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
                this.standDownToday = true;
                this.lastScanDate = LocalDate.now(clock);
                // N6: a stand-down decision must also drop any setups carried over from an
                // earlier scan pass so nothing can still be armed/traded after standing down.
                activeSetups.clear();
                candidateReservoir.clear();
                exhaustedSymbols.clear();
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

            List<String> finalActiveList = new ArrayList<>();
            List<String> oiCandidates = Collections.emptyList();

            if ("HYBRID".equalsIgnoreCase(scannerMode) && scanner != null) {
                try {
                    oiCandidates = scanner.scanOiSpurts(universeQuotes, sentiment, 3);
                } catch (Exception e) {
                    log.warn("[LVR] OI Spurts scan failed during Hybrid scan: {}", e.getMessage());
                }

                // 1. Add top 3 sector candidates
                int sectorTake = Math.min(3, candidateStocks.size());
                for (int k = 0; k < sectorTake; k++) {
                    finalActiveList.add(candidateStocks.get(k));
                }

                // 2. Add top 3 OI spurt candidates (if not already included)
                for (String oiSym : oiCandidates) {
                    if (!finalActiveList.contains(oiSym) && finalActiveList.size() < 6) {
                        finalActiveList.add(oiSym);
                    }
                }

                // 3. If OI spurts yielded < 3 stocks, fill remaining slots from sector candidates
                for (String secSym : candidateStocks) {
                    if (!finalActiveList.contains(secSym) && finalActiveList.size() < 3) {
                        finalActiveList.add(secSym);
                    }
                }
            } else {
                finalActiveList.addAll(candidateStocks);
            }

            this.sectorState =
                    new LowestVolumeSectorState(
                            (int) niftyQuotes.stream().filter(q -> q.pctChange() > 0).count(),
                            (int) niftyQuotes.stream().filter(q -> q.pctChange() < 0).count(),
                            sentiment,
                            winningSector.sectorName(),
                            winningSector.pctChange(),
                            finalActiveList);

            log.info(
                    "[LVR] Morning Scan Result (Mode={}): Sentiment={}, Winning Sector={} ({}%), Sector Candidates={}, OI Candidates={}, Final Active={}",
                    scannerMode,
                    sentiment,
                    winningSector.sectorName(),
                    winningSector.pctChange(),
                    candidateStocks,
                    oiCandidates,
                    finalActiveList);

            // Populate active setups
            activeSetups.clear();
            for (String symbol : finalActiveList) {
                LowestVolumeSetup setup = new LowestVolumeSetup(symbol, sentiment);
                StockQuoteSnapshot snap = universeQuotes.get(symbol);
                if (snap != null) {
                    setup.setOiChangePct(snap.oiPctChange());
                }
                initPdhPdlForSetup(setup);
                activeSetups.put(symbol, setup);
            }

            Set<String> candidateSet = new java.util.HashSet<>(finalActiveList);

            currentTopGainers.clear();
            currentTopLosers.clear();
            currentTopGainerSnapshots.clear();
            currentTopLoserSnapshots.clear();

            if (sentiment == LowestVolumeDirection.LONG) {
                currentTopGainers.addAll(finalActiveList);
                currentTopGainerSnapshots.addAll(
                        winningSectorStockQuotes.stream()
                                .filter(q -> candidateSet.contains(q.symbol()))
                                .toList());
            } else {
                currentTopLosers.addAll(finalActiveList);
                currentTopLoserSnapshots.addAll(
                        winningSectorStockQuotes.stream()
                                .filter(q -> candidateSet.contains(q.symbol()))
                                .toList());
            }

            // Populate Candidate Reservoir from remaining OI spurts and reserve leading sectors
            candidateReservoir.clear();
            for (String oiSym : oiCandidates) {
                if (!activeSetups.containsKey(oiSym)
                        && !exhaustedSymbols.contains(oiSym)
                        && !candidateReservoir.contains(oiSym)) {
                    candidateReservoir.add(oiSym);
                }
            }
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
                if ("HYBRID".equalsIgnoreCase(scannerMode) && !oiCandidates.isEmpty()) {
                    List<String> sectorLeaders = candidateStocks.stream().limit(3).toList();
                    telegramService.sendTextMessage(
                            String.format(
                                    "📊 *LVR 09:25 AM Morning Scan (Hybrid Mode)*\n"
                                            + "• Sentiment: *%s*\n"
                                            + "• Winning Sector: *%s* (%+.2f%%)\n"
                                            + "• Sector Leaders (%d): `%s`\n"
                                            + "• OI Spurt Leaders (%d): `%s`\n"
                                            + "• Active Watchlist (%d): `%s`\n"
                                            + "• Standby Reservoir: %d stocks",
                                    sentiment,
                                    winningSector.sectorName(),
                                    winningSector.pctChange(),
                                    sectorLeaders.size(),
                                    String.join(", ", sectorLeaders),
                                    oiCandidates.size(),
                                    String.join(", ", oiCandidates),
                                    finalActiveList.size(),
                                    String.join(", ", finalActiveList),
                                    candidateReservoir.size()));
                } else {
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
                                                risk.multiply(BigDecimal.valueOf(targetRr))));
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
                                                risk.multiply(BigDecimal.valueOf(targetRr))));
                    }

                    setup.setTriggerCandle(c, triggerPrc, slPrc, target1Prc);
                    setup.setSetupPattern("LVR_VOLUME_PULLBACK");
                }
                rollingLowest = c.volume();
                setup.setDayLowestVolume(rollingLowest);
            } else if (c.volume() > 0 && c.volume() < rollingLowest) {
                rollingLowest = c.volume();
                setup.setDayLowestVolume(rollingLowest);
            }

            // 3. Setup 3: Vande Bharat (Inside Bar Entry)
            if (i >= 1) {
                Candle prev = candles.get(i - 1);
                boolean isVandeBharat = false;
                BigDecimal vbTriggerPrc = null;
                BigDecimal vbSlPrc = null;
                BigDecimal vbTarget1Prc = null;

                if (direction == LowestVolumeDirection.LONG) {
                    boolean motherGreen = prev.isGreen();
                    boolean childRed = c.isRed();
                    boolean isInside =
                            (c.high().compareTo(prev.high()) <= 0
                                    && c.low().compareTo(prev.low()) >= 0);

                    if (motherGreen && childRed && isInside) {
                        isVandeBharat = true;
                        vbTriggerPrc = roundToTick(prev.high().add(BigDecimal.valueOf(0.05)));
                        BigDecimal rawSl = roundToTick(c.low().subtract(BigDecimal.valueOf(0.05)));
                        BigDecimal minRisk =
                                vbTriggerPrc.multiply(BigDecimal.valueOf(minStopLossPct / 100.0));
                        BigDecimal risk = vbTriggerPrc.subtract(rawSl).max(minRisk);
                        vbSlPrc = roundToTick(vbTriggerPrc.subtract(risk));
                        vbTarget1Prc =
                                roundToTick(
                                        vbTriggerPrc.add(
                                                risk.multiply(BigDecimal.valueOf(targetRr))));
                    }
                } else if (direction == LowestVolumeDirection.SHORT) {
                    boolean motherRed = prev.isRed();
                    boolean childGreen = c.isGreen();
                    boolean isInside =
                            (c.high().compareTo(prev.high()) <= 0
                                    && c.low().compareTo(prev.low()) >= 0);

                    if (motherRed && childGreen && isInside) {
                        isVandeBharat = true;
                        vbTriggerPrc = roundToTick(prev.low().subtract(BigDecimal.valueOf(0.05)));
                        BigDecimal rawSl = roundToTick(c.high().add(BigDecimal.valueOf(0.05)));
                        BigDecimal minRisk =
                                vbTriggerPrc.multiply(BigDecimal.valueOf(minStopLossPct / 100.0));
                        BigDecimal risk = rawSl.subtract(vbTriggerPrc).max(minRisk);
                        vbSlPrc = roundToTick(vbTriggerPrc.add(risk));
                        vbTarget1Prc =
                                roundToTick(
                                        vbTriggerPrc.subtract(
                                                risk.multiply(BigDecimal.valueOf(targetRr))));
                    }
                }

                if (isVandeBharat) {
                    // M5: Vande Bharat must not override an armed LVR trigger in the same pass.
                    boolean allowVb =
                            setup.getState() != LowestVolumeSetupState.TRIGGER_ARMED
                                    || "VANDE_BHARAT_INSIDE_BAR".equals(setup.getSetupPattern());
                    // M5: volume floor — the inside bar must be QUIETER than its mother candle
                    // and never a volume spike versus the day's rolling low, so random inside
                    // bars can't arm (mirrors the LVR volume dry-up gate).
                    boolean vbVolumeFloor =
                            c.volume() > 0
                                    && c.volume() <= prev.volume()
                                    && (rollingLowest == Long.MAX_VALUE
                                            || c.volume() <= (long) (rollingLowest * 2.0));
                    boolean candleAllowed =
                            (filterAfter == null
                                    || (c.timestamp() != null
                                            && c.timestamp()
                                                    .plusSeconds(300)
                                                    .isAfter(filterAfter)));
                    if (vandeBharatEnabled && allowVb && vbVolumeFloor && candleAllowed) {
                        setup.setTriggerCandle(c, vbTriggerPrc, vbSlPrc, vbTarget1Prc);
                        setup.setSetupPattern("VANDE_BHARAT_INSIDE_BAR");
                    }
                }
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
                        // M7: re-read state immediately before mutating — an entry may have
                        // landed on another thread since the guard above.
                        LowestVolumeSetupState preMutState = setup.getState();
                        if (preMutState == LowestVolumeSetupState.IN_POSITION
                                || preMutState == LowestVolumeSetupState.PARTIAL_BOOKED
                                || openPositions.containsKey(symbol)) {
                            continue;
                        }
                        // M4: setTriggerCandle no longer zeroes the armed-timeout counter on a
                        // trail (only a fresh arm does), so re-anchoring can't extend a setup
                        // forever. Every armed candle counts below, trail or not.
                        setup.setTriggerCandle(
                                evaluated.getTriggerCandle(),
                                evaluated.getTriggerPrice(),
                                evaluated.getStopLossPrice(),
                                evaluated.getTarget1Price());
                    }
                    setup.incrementArmedTimeout();
                    if (setupTimeoutCandles > 0
                            && setup.getArmedCandlesElapsed() >= setupTimeoutCandles) {
                        log.info(
                                "[LVR] Setup for {} expired after {} armed candles. Resetting to SCANNING.",
                                symbol,
                                setup.getArmedCandlesElapsed());
                        // M7: only expire if the setup is still armed (state may have moved).
                        if (setup.getState() == LowestVolumeSetupState.TRIGGER_ARMED) {
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
        if (marketDataService == null) return;
        if (!liveBreachCheckEnabled) {
            // Spec §3: disabled → 5-minute-cycle-only behavior.
            log.debug("[LVR] Live 30s breach check disabled — skipping evaluateLivePriceActions.");
            return;
        }

        LocalTime nowTime = LocalTime.now(clock);
        if (nowTime.isBefore(TIME_SCANNER_START) || !nowTime.isBefore(TIME_HARD_EXIT)) return;

        Map<String, JsonNode> liveQuoteCache = new HashMap<>();

        // 1. Check Armed Triggers (Only allowed strictly before entry cutoff, if strategy
        // enabled, circuit breaker not tripped, and max daily losses not reached)
        if (enabled
                && nowTime.isBefore(entryCutoffTime)
                && !isDailyCircuitBreakerTripped(liveQuoteCache)
                && (maxDailyLosses <= 0 || todayLossCount.get() < maxDailyLosses)) {
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
        // Runs unconditionally even if enabled == false to protect open trades
        if (!openPositions.isEmpty()) {
            evaluateOpenPositions(nowTime, liveQuoteCache, false);
        }
    }

    /**
     * Checks if the live spot price has breached the armed trigger level to enter ATM option trade.
     */
    void checkSpotTriggerBreach(String symbol, LowestVolumeSetup setup) {
        checkSpotTriggerBreach(symbol, setup, null);
    }

    void checkSpotTriggerBreach(String symbol, LowestVolumeSetup setup, JsonNode quoteNode) {
        try {
            // M1: data-dependent gate failures retry on a cooldown instead of re-running the
            // full filter stack (and its quote fetches) every 30 seconds.
            Instant notBefore = gateRetryNotBefore.get(symbol);
            if (notBefore != null && Instant.now(clock).isBefore(notBefore)) {
                return;
            }
            if (quoteNode == null) {
                quoteNode = fetchLiveQuoteNode(symbol);
            }
            double spotLtp =
                    (quoteNode != null && quoteNode.has("lp"))
                            ? quoteNode.get("lp").asDouble(0.0)
                            : 0.0;
            if (spotLtp <= 0) {
                // H5: never skip a breach check silently.
                log.warn(
                        "[LVR] No usable LTP for {} while trigger armed (quote fetch failed)."
                                + " Breach check skipped for this tick.",
                        symbol);
                return;
            }

            BigDecimal spotPrice = BigDecimal.valueOf(spotLtp);

            // Invalidate setup if live spot breaches the proposed stop loss before hitting the
            // entry trigger
            if (setup.getStopLossPrice() != null) {
                boolean slBreached = false;
                if (setup.getDirection() == LowestVolumeDirection.SHORT) {
                    if (spotPrice.compareTo(setup.getStopLossPrice()) >= 0) {
                        slBreached = true;
                    }
                } else if (setup.getDirection() == LowestVolumeDirection.LONG) {
                    if (spotPrice.compareTo(setup.getStopLossPrice()) <= 0) {
                        slBreached = true;
                    }
                }

                if (slBreached) {
                    log.info(
                            "[LVR] Setup for {} invalidated prior to entry: Live spot {} breached proposed SL {}. Resetting to SCANNING.",
                            symbol,
                            spotPrice,
                            setup.getStopLossPrice());
                    setup.resetToScanning();
                    return;
                }
            }

            // Invalidate setup if live spot already reached/passed 1:2 target before entry
            if (setup.getTarget1Price() != null) {
                boolean targetPassed = false;
                if (setup.getDirection() == LowestVolumeDirection.SHORT) {
                    if (spotPrice.compareTo(setup.getTarget1Price()) <= 0) {
                        targetPassed = true;
                    }
                } else if (setup.getDirection() == LowestVolumeDirection.LONG) {
                    if (spotPrice.compareTo(setup.getTarget1Price()) >= 0) {
                        targetPassed = true;
                    }
                }

                if (targetPassed) {
                    log.info(
                            "[LVR] Setup for {} expired prior to entry: Live spot {} already passed Target1 {}. Resetting to SCANNING.",
                            symbol,
                            spotPrice,
                            setup.getTarget1Price());
                    setup.resetToScanning();
                    return;
                }
            }

            // N1: freshness baseline — the session high/low observed on the first tick after
            // arming. A session extreme from BEFORE arming can never fire an entry; only an
            // extreme that ADVANCES past this baseline after arming counts as a fresh wick
            // (restores the 30s spec's wick-breach detection without the stale-high bug).
            if (setup.getSessionHighAtArming() == null
                    && quoteNode.has("h")
                    && quoteNode.get("h").asDouble(0.0) > 0) {
                setup.setSessionHighAtArming(quoteNode.get("h").asDouble(0.0));
            }
            if (setup.getSessionLowAtArming() == null
                    && quoteNode.has("l")
                    && quoteNode.get("l").asDouble(0.0) > 0) {
                setup.setSessionLowAtArming(quoteNode.get("l").asDouble(0.0));
            }

            boolean triggered = false;

            if (setup.getDirection() == LowestVolumeDirection.SHORT) {
                if (spotPrice.compareTo(setup.getTriggerPrice()) <= 0) {
                    triggered = true;
                } else if (quoteNode.has("l") && setup.getSessionLowAtArming() != null) {
                    double sessionLow = quoteNode.get("l").asDouble(0.0);
                    double baselineLow = setup.getSessionLowAtArming();
                    // Fresh wick: the session LOW advanced below its arming baseline AND
                    // reached the trigger, even though LTP has since bounced back above it.
                    if (sessionLow > 0
                            && sessionLow < baselineLow
                            && sessionLow <= setup.getTriggerPrice().doubleValue()) {
                        triggered = true;
                        log.info(
                                "[LVR] Fresh session-low wick breach for {} (low {} advanced past"
                                        + " arming baseline {} and reached trigger {}).",
                                symbol,
                                sessionLow,
                                baselineLow,
                                setup.getTriggerPrice());
                    }
                }
            } else if (setup.getDirection() == LowestVolumeDirection.LONG) {
                if (spotPrice.compareTo(setup.getTriggerPrice()) >= 0) {
                    triggered = true;
                } else if (quoteNode.has("h") && setup.getSessionHighAtArming() != null) {
                    double sessionHigh = quoteNode.get("h").asDouble(0.0);
                    double baselineHigh = setup.getSessionHighAtArming();
                    // Fresh wick: session HIGH advanced past its arming baseline and reached
                    // the trigger while LTP sits below it (pullback after the breach).
                    if (sessionHigh > 0
                            && sessionHigh > baselineHigh
                            && sessionHigh >= setup.getTriggerPrice().doubleValue()) {
                        triggered = true;
                        log.info(
                                "[LVR] Fresh session-high wick breach for {} (high {} advanced"
                                        + " past arming baseline {} and reached trigger {}).",
                                symbol,
                                sessionHigh,
                                baselineHigh,
                                setup.getTriggerPrice());
                    }
                }
            }

            if (triggered) {
                // L6: all pre-entry price gates (slippage, VWAP, 15m range, PDH/PDL) are
                // evaluated by the SHARED entry gate below — the same function replaySession
                // uses — so live and replay can never diverge on a gate decision.

                // Resolve VWAP first (data collection only; the decision belongs to the gate).
                double liveVwap = resolveLiveVwap(setup, quoteNode);

                // Opening 15-Minute Range Breakout values are consumed by the gate.

                // PDH / PDL: N5 transient-fetch retry stays here (it is data plumbing, not a
                // decision); the fail-closed exhaustion for permanently-missing values and the
                // breakout confirmation are gate decisions.
                if (pdhPdlFilterEnabled) {
                    if (setup.getPdh() == null || setup.getPdl() == null) {
                        // N5: a single transient daily-candle failure must not permanently kill
                        // the setup — retry the lazy fetch once more (next tick) before the
                        // fail-closed exhaustion.
                        int attempt = setup.recordPdhFetchAttempt();
                        initPdhPdlForSetup(setup);
                        if ((setup.getPdh() == null || setup.getPdl() == null)
                                && attempt < MAX_PDH_FETCH_ATTEMPTS) {
                            log.warn(
                                    "[LVR] PDH/PDL unavailable for {} (attempt {}/{}). Retrying"
                                            + " on next tick before fail-closed exhaustion.",
                                    symbol,
                                    attempt,
                                    MAX_PDH_FETCH_ATTEMPTS);
                            return;
                        }
                    }
                }

                resolveLiveOptionChainLevels(setup, spotPrice);

                EntryGateInput gateInput = new EntryGateInput();
                gateInput.symbol = symbol;
                gateInput.direction = setup.getDirection();
                gateInput.triggerPrice = setup.getTriggerPrice();
                gateInput.decisionPrice = spotPrice;
                gateInput.entryPrice = spotPrice;
                gateInput.maxSlippagePct = maxSlippagePct;
                gateInput.vwapEnabled = vwapConfirmationEnabled;
                gateInput.vwap = (liveVwap > 0.0) ? liveVwap : null;
                gateInput.range15mEnabled = opening15mRangeFilterEnabled;
                gateInput.first15mHigh = setup.getFirst15MinHigh();
                gateInput.first15mLow = setup.getFirst15MinLow();
                gateInput.pdhPdlEnabled = pdhPdlFilterEnabled;
                gateInput.pdh = setup.getPdh();
                gateInput.pdl = setup.getPdl();
                gateInput.pcrEnabled = pcrFilterEnabled;
                gateInput.pcr = setup.getPcr();
                gateInput.pcrMinLong = pcrMinLong;
                gateInput.pcrMaxShort = pcrMaxShort;
                gateInput.optionSrFilterEnabled = optionSrFilterEnabled;
                gateInput.optionResistanceStrike = setup.getOptionResistanceStrike();
                gateInput.optionSupportStrike = setup.getOptionSupportStrike();
                gateInput.optionSrBufferPct = optionSrBufferPct;
                // Session-level gates (breaker/cutoff/attempts/concurrency/budget) are enforced
                // by evaluateLivePriceActions' outer loop and inside executePositionEntry.

                EntryGateDecision gate = evaluateEntryGate(gateInput);
                if (!gate.allowed()) {
                    switch (gate.disposition()) {
                        case RETRY_COOLDOWN -> {
                            log.warn(
                                    "[LVR] Trigger breach for {} skipped: {}. Retrying after"
                                            + " cooldown.",
                                    symbol,
                                    gate.reason());
                            // M1: slippage is price-data dependent → retry with cooldown.
                            gateRetryNotBefore.put(symbol, Instant.now(clock).plusSeconds(60));
                        }
                        case RETRY ->
                                log.warn(
                                        "[LVR] Entry for {} deferred (fail-closed): {}",
                                        symbol,
                                        gate.reason());
                        case EXHAUST -> {
                            log.info(
                                    "[LVR] Setup for {} REJECTED/EXHAUSTED: {}. Stock discarded"
                                            + " for the day.",
                                    symbol,
                                    gate.reason());
                            setup.transitionTo(
                                    LowestVolumeSetupState.REJECTED_EXHAUSTED, gate.reason());
                            exhaustedSymbols.add(symbol);
                            if (telegramAlerts && telegramService != null) {
                                telegramService.sendTextMessage(
                                        String.format(
                                                Locale.US,
                                                "⚠️ *LVR Trade Discarded*\n• Symbol: *%s*\n"
                                                        + "• Reason: %s\n"
                                                        + "• Status: *Stock discarded for the day*",
                                                symbol,
                                                gate.reason()));
                            }
                        }
                        default ->
                                log.warn(
                                        "[LVR] Entry for {} not allowed: {}",
                                        symbol,
                                        gate.reason());
                    }
                    return;
                }

                // Sector Momentum Alignment Check at Entry Time
                if (sectorMomentumFilterEnabled) {
                    SectorGate sectorGate =
                            evaluateLiveSectorAlignment(symbol, setup.getDirection());
                    if (sectorGate == SectorGate.NO_DATA) {
                        // M9: no usable constituent quotes — retry on cooldown, fail closed
                        // after bounded attempts instead of silently allowing.
                        int noDataRetries = gateRetryCounts.merge(symbol, 1, Integer::sum);
                        if (noDataRetries >= MAX_SECTOR_NO_DATA_RETRIES) {
                            log.warn(
                                    "[LVR] Entry for {} BLOCKED: sector data unavailable after {}"
                                            + " retries. Failing closed (REJECTED_EXHAUSTED).",
                                    symbol,
                                    noDataRetries);
                            setup.transitionTo(
                                    LowestVolumeSetupState.REJECTED_EXHAUSTED,
                                    "Sector data unavailable after " + noDataRetries + " retries");
                            exhaustedSymbols.add(symbol);
                            return;
                        }
                        log.warn(
                                "[LVR] Entry for {} deferred: sector quotes unavailable (retry"
                                        + " {}/{}). Retrying after cooldown.",
                                symbol,
                                noDataRetries,
                                MAX_SECTOR_NO_DATA_RETRIES);
                        gateRetryNotBefore.put(symbol, Instant.now(clock).plusSeconds(60));
                        return;
                    }
                    if (sectorGate == SectorGate.MISALIGNED) {
                        log.warn(
                                "[LVR] Entry for {} BLOCKED: Parent sector has flipped or lost momentum opposite to {} direction. Deferring entry (cooldown retry).",
                                symbol,
                                setup.getDirection());
                        // M1: misalignment is quote-data dependent → retry with cooldown.
                        gateRetryNotBefore.put(symbol, Instant.now(clock).plusSeconds(60));
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
        if (standDownToday) {
            // N6: stand-down (post-cutoff or sentiment collapse) must also gate entries.
            log.warn("[LVR] Stand-down active for the day. Skipping entry for {}.", symbol);
            return null;
        }
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
        if (fno == null) {
            // M8: no registry entry → lot size / strike step would be fabricated (100 / 10).
            // Reject loudly and exhaust — the registry is static for the day, a miss is permanent.
            log.error(
                    "[LVR] {} has no F&O registry entry — refusing to fabricate lot size/strike"
                            + " step. REJECTED_EXHAUSTED.",
                    symbol);
            setup.transitionTo(
                    LowestVolumeSetupState.REJECTED_EXHAUSTED, "F&O registry miss for " + symbol);
            exhaustedSymbols.add(symbol);
            return null;
        }
        int lotSize = fno.lotSize();
        BigDecimal strikeStep = fno.strikeStep();

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

        // M2 pre-trade budget: don't open a position whose planned risk alone would breach the
        // daily loss budget (in addition to the tripped-breaker latch which uses live P&L).
        if (maxDailyLoss > 0.0) {
            double totalRiskPnl = calculateTodayRealizedPnl() + getCachedUnrealizedPnl();
            double remainingBudget = maxDailyLoss + totalRiskPnl;
            if (plannedRisk.doubleValue() > remainingBudget) {
                log.warn(
                        "[LVR] Entry for {} REJECTED: planned risk {} exceeds remaining daily"
                                + " loss budget {} (limit {}, current P&L {}).",
                        symbol,
                        String.format(Locale.US, "%.2f", plannedRisk.doubleValue()),
                        String.format(Locale.US, "%.2f", Math.max(0.0, remainingBudget)),
                        String.format(Locale.US, "%.2f", maxDailyLoss),
                        String.format(Locale.US, "%.2f", totalRiskPnl));
                return null;
            }
        }

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
            if (marketDataService != null) {
                var fc = marketDataService.resolveFuturesContract(symbol);
                if (fc != null && fc.tsym() != null && !fc.tsym().isBlank()) {
                    brokerTradingSymbol = fc.tsym();
                }
            }
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
            position.setBrokerTradingSymbol(brokerTradingSymbol);

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

            boolean published =
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
                            Map.of(
                                    "instrumentType", "FUTURES",
                                    "tradeId", tradeId,
                                    // C3: contract reference + protective levels so the consumer
                                    // can place a best-effort broker-side SL/exit order.
                                    "referencePrice", spotPrice,
                                    "brokerStopLossPrice", setup.getStopLossPrice(),
                                    "brokerTargetPrice", actualTarget1));
            if (!published) {
                rollbackEntryAfterPublishFailure(
                        symbol, setup, tradeId, "signal bus rejected FUTURES ENTRY");
                return null;
            }

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
                recordQuoteFailure(symbol, "option LTP at entry");
                log.warn(
                        "[LVR] Option LTP unavailable for {} {} ATM {}. Skipping entry this tick to avoid fabricated premium.",
                        symbol,
                        optType,
                        atmStrike);
                return null;
            }
            recordQuoteSuccess(symbol);
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
            position.setBrokerTradingSymbol(brokerTradingSymbol);

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

            boolean published =
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
                            Map.of(
                                    "instrumentType",
                                    "OPTION",
                                    "spotPrice",
                                    spotPrice,
                                    "tradeId",
                                    tradeId,
                                    // C3: premium-level reference + protective SL/target (ATM
                                    // delta ≈ 0.5 → premium moves ≈ 0.5x the spot move).
                                    "referencePrice",
                                    entryPremium,
                                    "brokerStopLossPrice",
                                    entryPremium
                                            .subtract(unitRisk.multiply(BigDecimal.valueOf(0.50)))
                                            .setScale(2, RoundingMode.HALF_UP),
                                    "brokerTargetPrice",
                                    entryPremium.add(unitRisk).setScale(2, RoundingMode.HALF_UP)));
            if (!published) {
                rollbackEntryAfterPublishFailure(
                        symbol, setup, tradeId, "signal bus rejected OPTION ENTRY");
                return null;
            }

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

        persistState();
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

    /**
     * Evaluates open positions for Spot SL, 1:2 Target Partial Exit, Cost SL, and 10 EMA Trailing.
     *
     * <p>M7: quotes are gathered OUTSIDE the service lock (a slow fetch must not block entries or
     * hard exits), then evaluation + mutation run atomically inside the lock so state cannot change
     * between checks and mutations.
     */
    public void evaluateOpenPositions(
            LocalTime nowTime, Map<String, JsonNode> quoteCache, boolean isCandleClose) {
        if (openPositions.isEmpty()) return;
        Map<String, JsonNode> gathered = new HashMap<>();
        if (quoteCache != null) {
            gathered.putAll(quoteCache);
        }
        for (String symbol : openPositions.keySet()) {
            if (!gathered.containsKey(symbol)) {
                try {
                    JsonNode q = fetchLiveQuoteNode(symbol);
                    if (q != null) {
                        gathered.put(symbol, q);
                    }
                } catch (Exception e) {
                    log.warn(
                            "[LVR] Quote fetch failed for open position {} during evaluation: {}",
                            symbol,
                            e.getMessage());
                }
            }
        }
        evaluateOpenPositionsWithQuotes(nowTime, gathered, isCandleClose);
    }

    private synchronized void evaluateOpenPositionsWithQuotes(
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
                JsonNode quoteNode = (quoteCache != null) ? quoteCache.get(symbol) : null;
                double spotLtp =
                        (quoteNode != null && quoteNode.has("lp"))
                                ? quoteNode.get("lp").asDouble(0.0)
                                : 0.0;
                if (spotLtp <= 0) {
                    // H5: never skip an exit evaluation silently.
                    recordQuoteFailure(symbol, "open-position exit evaluation");
                    continue;
                }
                recordQuoteSuccess(symbol);

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
                    if (!pos.isPartialBooked()) {
                        todayLossCount.incrementAndGet();
                    }

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
                        if (currentExitMode == LvrExitMode.FULL_TARGET_1_2) {
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
                                    Map.of(
                                            "instrumentType",
                                            pos.getInstrumentType().name(),
                                            "orderType",
                                            orderType));

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
                                        Map.of(
                                                "instrumentType",
                                                pos.getInstrumentType().name(),
                                                "orderType",
                                                orderType));

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
                                            "orderType",
                                            orderType,
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
                    Instant nowInst = Instant.now(clock);
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
                                            .filter(
                                                    c ->
                                                            c.timestamp()
                                                                            .plusSeconds(300)
                                                                            .compareTo(nowInst)
                                                                    <= 0)
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
        persistState();
    }

    /** 15:00 IST Hard EOD Square-Off. */
    public synchronized void executeHardExit(LocalTime nowTime) {
        if (openPositions.isEmpty()) return;

        log.info(
                "[LVR] {} Hard EOD Square-off reached. Closing all open positions.",
                nowTime != null ? nowTime : "15:00 IST");
        List<String> symbols = new ArrayList<>(openPositions.keySet());
        int failedExits = 0;
        int closedCount = 0;
        for (String symbol : symbols) {
            LowestVolumePaperPosition pos = openPositions.get(symbol);
            if (pos == null || pos.isClosed()) {
                openPositions.remove(symbol);
                continue;
            }

            try {
                // H12: never fabricate a fill from a failed quote — retry a few times first.
                double spotLtp = 0.0;
                for (int attempt = 1; attempt <= 3 && spotLtp <= 0; attempt++) {
                    spotLtp = fetchLiveSpotPrice(symbol);
                    if (spotLtp <= 0 && attempt < 3) {
                        try {
                            Thread.sleep(250);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                int exitQty =
                        pos.getRemainingQuantity() > 0
                                ? pos.getRemainingQuantity()
                                : pos.getTotalQuantity();
                String brokerSymbol = resolveBrokerTradingSymbol(pos);

                if (spotLtp <= 0) {
                    // H12: quote still unavailable — publish a MKT EXIT so the broker flattens,
                    // but KEEP the paper position open for the next retry cycle instead of
                    // recording a fabricated zero-loss fill.
                    failedExits++;
                    publishSignal(
                            symbol,
                            brokerSymbol,
                            pos.getDirection() == LowestVolumeDirection.LONG
                                    ? com.tradingbot.strategy.SignalAction.EXIT_LONG
                                    : com.tradingbot.strategy.SignalAction.EXIT_SHORT,
                            BigDecimal.ZERO,
                            pos.getCurrentStockSl(),
                            null,
                            exitQty,
                            "EOD_1500_QUOTE_UNAVAILABLE",
                            Map.of(
                                    "instrumentType",
                                    pos.getInstrumentType().name(),
                                    "orderType",
                                    "MKT",
                                    "estimated",
                                    true));
                    log.error(
                            "[LVR] Hard exit for {} deferred: no quote after 3 attempts."
                                    + " MKT EXIT published; paper position kept for retry.",
                            symbol);
                    continue;
                }

                BigDecimal spotPrice = BigDecimal.valueOf(spotLtp);
                BigDecimal optionPremium = estimateOptionPremium(pos, spotPrice);

                BigDecimal exitVal =
                        (pos.getInstrumentType() == LvrInstrumentType.FUTURES)
                                ? spotPrice
                                : optionPremium;

                pos.close(exitVal, "EOD_1500_HARD_EXIT", Instant.now());
                openPositions.remove(symbol);
                tradeHistory.add(pos);
                closedCount++;

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
                // H12: an exception must not drop the position without a record — keep it in
                // openPositions so the next retry cycle (or reset) can close it properly.
                failedExits++;
                log.error(
                        "[LVR] Error during hard exit for {} — position kept for retry: {}",
                        symbol,
                        e.getMessage(),
                        e);
            }
        }
        if (failedExits > 0) {
            log.warn(
                    "[LVR] EOD hard exit pass: {} closed, {} failed (retrying on next cycle).",
                    closedCount,
                    failedExits);
            if (telegramAlerts && telegramService != null) {
                telegramService.sendTextMessage(
                        String.format(
                                Locale.US,
                                "⚠️ *LVR EOD Exit Partially Complete*\n• Closed: `%d`\n"
                                        + "• Failed (retrying): `%d`\n• Failed symbols stay"
                                        + " managed until flattened.",
                                closedCount,
                                failedExits));
            }
        }
        persistState();
    }

    public synchronized void resetDaily() {
        resetDaily(false);
    }

    /**
     * H6: resets daily strategy state.
     *
     * <p>A same-day reset (intraday {@code /reset}, {@code force=false}) is SOFT: scanning state is
     * rebuilt but realized P&L ({@code tradeHistory}), the circuit-breaker latch and the archived
     * carry are preserved — an intraday reset can never erase the day's losses. A new-day reset or
     * {@code force=true} archives everything to disk first, then clears. Either way the full state
     * is snapshotted to JSON (M10) before anything is cleared.
     *
     * @param force {@code true} to perform a full hard reset even on the same day
     */
    public synchronized void resetDaily(boolean force) {
        LocalDate today = LocalDate.now(clock);
        boolean sameDaySoftReset = !force && lastScanDate != null && lastScanDate.equals(today);

        if (!openPositions.isEmpty()) {
            log.warn(
                    "[LVR] resetDaily() called while {} open position(s) exist. Executing hard exit first.",
                    openPositions.size());
            executeHardExit(LocalTime.of(15, 0));
        }

        // M10: snapshot to disk BEFORE clearing anything — nothing is ever silently lost.
        try {
            com.tradingbot.persistence.LvrStateStore.DailyState snapshot = buildState();
            Path statePath = Path.of(stateFilePath);
            com.tradingbot.persistence.LvrStateStore.save(statePath, snapshot);
            com.tradingbot.persistence.LvrStateStore.saveDatedArchive(statePath, snapshot, today);
        } catch (Exception e) {
            log.error("[LVR] Failed to archive daily snapshot: {}", e.getMessage());
        }

        activeSetups.clear();
        openPositions.clear();
        exhaustedSymbols.clear();
        candidateReservoir.clear();
        lastMidMorningRefreshTime = null;
        currentTopGainers.clear();
        currentTopLosers.clear();
        currentTopGainerSnapshots.clear();
        currentTopLoserSnapshots.clear();
        sectorState = LowestVolumeSectorState.empty();
        universeScanCompletedToday = false;
        // H10: the stand-down decision may only be cleared here (explicit reset).
        this.standDownToday = false;

        if (sameDaySoftReset) {
            log.info(
                    "[LVR] Same-day SOFT reset: {} closed trade(s) and breaker latch PRESERVED;"
                            + " scanning state rebuilt (lastScanDate kept to avoid a phantom"
                            + " new-day wipe).",
                    tradeHistory.size());
        } else {
            if (lastScanDate != null && lastScanDate.equals(today) && !tradeHistory.isEmpty()) {
                // Forced intraday wipe of same-day history → carry the sum so the breaker,
                // /status and budget checks keep counting today's realized losses (H6).
                double sameDaySum =
                        tradeHistory.stream()
                                .filter(
                                        p ->
                                                p.getExitTime() != null
                                                        && LocalDate.ofInstant(p.getExitTime(), IST)
                                                                .equals(today))
                                .mapToDouble(p -> p.getTotalRealizedPnl().doubleValue())
                                .sum();
                archivedRealizedPnl += sameDaySum;
                archiveDate = today;
            } else {
                // New day: yesterday's carry belongs to yesterday's (archived) snapshot.
                archivedRealizedPnl = 0.0;
                archiveDate = null;
            }
            tradeHistory.clear();
            lastScanDate = null;
            niftyBullish = true;
            dailyCircuitBreakerAlertSent.set(false);
            dailyCircuitBreakerTripped.set(false);
            todayLossCount.set(0);
            tradeCounter.set(1);
        }

        if (marketDataService != null) {
            marketDataService.prewarmSession();
        }
        persistState();
        log.info(
                "[LVR] Daily state reset complete ({}).",
                sameDaySoftReset ? "soft, P&L preserved" : "hard, P&L archived");
    }

    /**
     * Replenishes active setups from the standby candidate reservoir if actionable candidate count
     * drops below minActiveCandidates (due to SL hits, exhaustion, or invalidation).
     */
    public synchronized void replenishActiveCandidatesIfNeeded(LocalTime nowTime) {
        if (nowTime.isAfter(entryCutoffTime)) return;
        if (standDownToday) {
            // H10: the stand-down decision is sticky — never refill the watchlist after it.
            log.debug("[LVR] Stand-down active; skipping candidate replenishment.");
            return;
        }

        long actionableCount = countActionableSetups();

        if (actionableCount < minActiveCandidates && !candidateReservoir.isEmpty()) {
            LowestVolumeDirection dir =
                    niftyBullish ? LowestVolumeDirection.LONG : LowestVolumeDirection.SHORT;
            List<String> promotedSymbols = new ArrayList<>();

            while (actionableCount < minActiveCandidates && !candidateReservoir.isEmpty()) {
                String nextSymbol = candidateReservoir.remove(0);
                if (!activeSetups.containsKey(nextSymbol)
                        && !exhaustedSymbols.contains(nextSymbol)) {
                    LowestVolumeSetup setup = new LowestVolumeSetup(nextSymbol, dir);
                    initPdhPdlForSetup(setup);
                    activeSetups.put(nextSymbol, setup);
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
        if (marketDataService == null || nowTime.isAfter(entryCutoffTime) || standDownToday) return;
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
            // H10: same coverage guard as the morning scan — never trust sentiment from a
            // tiny quote sample.
            if (niftyQuotes.size() < 35) {
                log.warn(
                        "[LVR] Mid-morning refresh skipped: only {}/35 NIFTY quotes available."
                                + " Keeping morning decision.",
                        niftyQuotes.size());
                return;
            }
            LowestVolumeDirection sentiment =
                    scanner.evaluateMarketSentiment(niftyQuotes, minBreadthPct);
            if (sentiment == LowestVolumeDirection.NONE) {
                // H10: a NONE (choppy) reading must not re-arm candidate promotion in some
                // direction — the morning stand-down/decision is sticky; skip the refresh.
                log.warn(
                        "[LVR] Mid-morning refresh skipped: sentiment NONE (choppy). Keeping"
                                + " morning decision.");
                return;
            }
            this.niftyBullish = (sentiment == LowestVolumeDirection.LONG);

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

            if ("HYBRID".equalsIgnoreCase(scannerMode)
                    || "OI_SPURTS".equalsIgnoreCase(scannerMode)) {
                try {
                    List<String> midOi = scanner.scanOiSpurts(universeQuotes, sentiment, 3);
                    for (String oiSym : midOi) {
                        if (!activeSetups.containsKey(oiSym)
                                && !exhaustedSymbols.contains(oiSym)
                                && !candidateReservoir.contains(oiSym)) {
                            candidateReservoir.add(oiSym);
                        }
                    }
                } catch (Exception ignored) {
                }
            }

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

    /**
     * L6: VWAP value resolution for the shared entry gate (quote "ap" → setup's last known VWAP →
     * candle-based fallback). Data collection only — the confirmation decision belongs to {@link
     * #evaluateEntryGate}.
     */
    private double resolveLiveVwap(LowestVolumeSetup setup, JsonNode quoteNode) {
        double vwap =
                (quoteNode != null && quoteNode.has("ap"))
                        ? quoteNode.get("ap").asDouble(0.0)
                        : 0.0;
        if (vwap <= 0.0 && setup.getLatestVwap() != null) {
            vwap = setup.getLatestVwap();
        }
        if (vwap <= 0.0) {
            vwap = fetchLiveVwap(setup.getSymbol(), quoteNode);
        }
        return vwap;
    }

    /**
     * Resolves Option Chain data (PCR across ATM ± 4 strikes, Max Call OI Resistance strike, Max
     * Put OI Support strike) using ShoonyaOptionChainService.
     */
    public void resolveLiveOptionChainLevels(LowestVolumeSetup setup, BigDecimal spotPrice) {
        if (setup == null || spotPrice == null) return;
        if (!pcrFilterEnabled && !optionSrFilterEnabled) return;
        if (optionChainService == null) return;
        try {
            String sym = setup.getSymbol();
            StockFnoRegistry.InstrumentInfo fno = StockFnoRegistry.get(sym);
            BigDecimal step =
                    (fno != null && fno.strikeStep() != null)
                            ? fno.strikeStep()
                            : BigDecimal.valueOf(10);
            BigDecimal atm = resolveAtmStrike(spotPrice, step);
            com.tradingbot.model.OptionChainResponse chain =
                    optionChainService.getOptionChain(sym, sym, "", atm, 4, true);
            if (chain != null) {
                if (chain.totalCallOi() > 0) {
                    double pcrVal = chain.pcr();
                    setup.setPcr(pcrVal);
                }
                BigDecimal resStrike = chain.maxCallOiStrike();
                BigDecimal supStrike = chain.maxPutOiStrike();
                if (resStrike != null) {
                    setup.setOptionResistanceStrike(resStrike);
                }
                if (supStrike != null) {
                    setup.setOptionSupportStrike(supStrike);
                }
                log.info(
                        "[LVR] Option Chain levels for {} (ATM={}): PCR={}, MaxCallOI Resistance={}, MaxPutOI Support={}",
                        sym,
                        atm,
                        setup.getPcr() != null
                                ? String.format(java.util.Locale.ROOT, "%.2f", setup.getPcr())
                                : "n/a",
                        setup.getOptionResistanceStrike(),
                        setup.getOptionSupportStrike());
            }
        } catch (Exception e) {
            log.warn(
                    "[LVR] Could not resolve Option Chain levels for {}: {}",
                    setup.getSymbol(),
                    e.getMessage());
        }
    }

    /** Backwards-compatible resolveLivePcr helper. */
    public Double resolveLivePcr(LowestVolumeSetup setup, BigDecimal spotPrice) {
        resolveLiveOptionChainLevels(setup, spotPrice);
        return setup != null ? setup.getPcr() : null;
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

        // M3: simple intraday time-decay on the synthetic estimate (live LTP path above wins when
        // available). ATM premium decays ~10% across the ~6.25h session (9:15→15:30), floored so
        // a stale entry never drives the premium to zero.
        try {
            java.time.Duration held =
                    java.time.Duration.between(pos.getEntryTime(), Instant.now(clock));
            double hoursHeld = Math.max(0.0, held.toMinutes() / 60.0);
            double decayFactor = Math.max(0.90, 1.0 - (0.10 * (hoursHeld / 6.25)));
            estPremVal *= decayFactor;
        } catch (Exception ignore) {
            // position entry time missing/unparseable → keep undecayed estimate
        }

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
                    // L5: never fall back to the underlying's equity token — that would fabricate
                    // the option premium from the STOCK's LTP. Exact contract match or nothing.
                    log.debug(
                            "[LVR] Option contract {} not resolvable to a token; no direct"
                                    + " fallback available.",
                            tsym);
                    return 0.0;
                }
                JsonNode q = marketDataService.fetchQuote("NFO", tok);
                if (q != null && q.has("lp")) {
                    double lp = q.get("lp").asDouble(0.0);
                    if (lp > 0.0) return lp;
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
                    long v = quote.has("v") ? quote.get("v").asLong(0L) : 0L;
                    double ap = quote.has("ap") ? quote.get("ap").asDouble(0.0) : 0.0;
                    long oi = quote.path("oi").asLong(0L);
                    // H8/N2: official Shoonya/Noren fields are `oi` (current OI) and `poi`
                    // (previous-day closing OI). The earlier `oio`/`oipct` names do not exist in
                    // GetQuotes, which silently zeroed the OI_SPURTS mode.
                    long prevOi = quote.path("poi").asLong(0L);

                    // If quote is from cash equities segment (oi == 0) and symbol is an F&O
                    // underlying,
                    // enrich with open interest from its near-month futures contract on NFO.
                    if (oi == 0L
                            && prevOi == 0L
                            && (info != null
                                    || StockFnoRegistry.getAllInstruments().containsKey(sym)
                                    || !StockFnoRegistry.isIndex(sym))) {
                        try {
                            String futToken = marketDataService.resolveFuturesToken(sym);
                            if (futToken != null && !futToken.isBlank()) {
                                String futExch = StockFnoRegistry.getSegment(sym);
                                if (futExch == null || futExch.isBlank()) futExch = "NFO";
                                JsonNode futQuote = marketDataService.fetchQuote(futExch, futToken);
                                if (futQuote != null) {
                                    oi = futQuote.path("oi").asLong(0L);
                                    prevOi = futQuote.path("poi").asLong(0L);
                                }
                            }
                        } catch (Exception fex) {
                            log.debug(
                                    "[LVR] Unable to fetch futures OI for {}: {}",
                                    sym,
                                    fex.getMessage());
                        }
                    }

                    if (c > 0) {
                        double pct = (lp - c) / c * 100.0;
                        // 9-arg constructor computes oiPctChange from oi vs prevOi.
                        quoteMap.put(
                                sym, new StockQuoteSnapshot(sym, lp, c, o, pct, v, ap, oi, prevOi));
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

    public boolean isLiveBreachCheckEnabled() {
        return liveBreachCheckEnabled;
    }

    public void setLiveBreachCheckEnabled(boolean liveBreachCheckEnabled) {
        this.liveBreachCheckEnabled = liveBreachCheckEnabled;
    }

    /** M9: tri-state outcome of the live sector gate at entry time. */
    // ===== L6: shared pre-entry gate — used by BOTH the live trigger path and replaySession =====

    /** What the caller should do when the gate denies an entry. */
    public enum EntryGateDisposition {
        /** All evaluated gates passed. */
        ALLOWED,
        /** Transient/unavailable data — retry on the next natural tick (no cooldown). */
        RETRY,
        /** Price-data-dependent rejection — retry after the standard 60s cooldown. */
        RETRY_COOLDOWN,
        /** Permanent rejection — discard the symbol for the day (REJECTED_EXHAUSTED). */
        EXHAUST
    }

    /** Decision produced by {@link #evaluateEntryGate}. */
    public record EntryGateDecision(EntryGateDisposition disposition, String gate, String reason) {
        public boolean allowed() {
            return disposition == EntryGateDisposition.ALLOWED;
        }
    }

    /**
     * L6: inputs for the shared entry gate. Fields left {@code null} (or {@code maxSlippagePct <
     * 0}) are NOT evaluated — the live trigger path leaves session-level gates to the outer loop of
     * {@code evaluateLivePriceActions} and to {@link #executePositionEntry}, while {@link
     * #replaySession} fills everything.
     */
    public static final class EntryGateInput {
        public String symbol;
        public LowestVolumeDirection direction;

        /** Armed trigger price — the reference for the slippage band. */
        public BigDecimal triggerPrice;

        /** Market price the decision gates (VWAP / 15m / PDH) are evaluated against. */
        public BigDecimal decisionPrice;

        /** Price used for the slippage band (live: LTP at touch; replay: entry candle open). */
        public BigDecimal entryPrice;

        /** {@code < 0} disables the slippage gate. */
        public double maxSlippagePct = -1;

        public Boolean vwapEnabled;

        /** {@code null} or {@code <= 0} = unavailable (fail-closed RETRY when enabled). */
        public Double vwap;

        public Boolean range15mEnabled;
        public BigDecimal first15mHigh;
        public BigDecimal first15mLow;
        public Boolean pdhPdlEnabled;
        public BigDecimal pdh;
        public BigDecimal pdl;
        public Boolean pcrEnabled;
        public Double pcr;
        public Double pcrMinLong;
        public Double pcrMaxShort;
        public Boolean optionSrFilterEnabled;
        public BigDecimal optionResistanceStrike;
        public BigDecimal optionSupportStrike;
        public Double optionSrBufferPct;
        public Boolean standDown;
        public Boolean breakerTripped;
        public LocalTime nowTime;
        public LocalTime entryCutoff;
        public Integer tradeAttempts;
        public Integer maxAttempts;
        public Integer openConcurrent;
        public Integer maxConcurrent;
        public Boolean hasRegistryEntry;
        public BigDecimal plannedRisk;
        public Double remainingBudget;
    }

    private static EntryGateDecision deny(
            EntryGateDisposition disposition, String gate, String reason) {
        return new EntryGateDecision(disposition, gate, reason);
    }

    /**
     * L6: THE single pre-entry decision. Pure and stateless — the live trigger path and {@link
     * #replaySession} both evaluate this exact function, so a gate can never pass live and fail
     * replay (or vice versa) for identical inputs. Evaluation order mirrors the live chain:
     * slippage → VWAP → 15m range → PDH/PDL → stand-down → breaker → cutoff → attempts →
     * concurrency → registry → budget.
     */
    public static EntryGateDecision evaluateEntryGate(EntryGateInput in) {
        // 1. Slippage band (price-data dependent → cooldown retry).
        if (in.maxSlippagePct > 0
                && in.triggerPrice != null
                && in.entryPrice != null
                && in.direction != null) {
            boolean excessive;
            String band;
            if (in.direction == LowestVolumeDirection.LONG) {
                BigDecimal maxAllowed =
                        in.triggerPrice.multiply(
                                BigDecimal.valueOf(1.0 + (in.maxSlippagePct / 100.0)));
                excessive = in.entryPrice.compareTo(maxAllowed) > 0;
                band = "max " + String.format(Locale.US, "%.2f", maxAllowed);
            } else {
                BigDecimal minAllowed =
                        in.triggerPrice.multiply(
                                BigDecimal.valueOf(1.0 - (in.maxSlippagePct / 100.0)));
                excessive = in.entryPrice.compareTo(minAllowed) < 0;
                band = "min " + String.format(Locale.US, "%.2f", minAllowed);
            }
            if (excessive) {
                return deny(
                        EntryGateDisposition.RETRY_COOLDOWN,
                        "SLIPPAGE",
                        String.format(
                                Locale.US,
                                "excessive slippage: entry price %s outside %s%% band from"
                                        + " trigger %s (%s)",
                                in.entryPrice,
                                String.format(Locale.US, "%.2f", in.maxSlippagePct),
                                in.triggerPrice,
                                band));
            }
        }

        // 2. VWAP confirmation (unavailable → fail-closed retry; wrong side → exhaust).
        if (Boolean.TRUE.equals(in.vwapEnabled)) {
            if (in.vwap == null || in.vwap <= 0.0) {
                return deny(
                        EntryGateDisposition.RETRY,
                        "VWAP",
                        "VWAP confirmation enabled but no valid VWAP available (fail-closed)");
            }
            if (in.decisionPrice != null && in.direction != null) {
                boolean confirmed;
                if (in.direction == LowestVolumeDirection.LONG) {
                    confirmed = in.decisionPrice.compareTo(BigDecimal.valueOf(in.vwap)) > 0;
                } else {
                    confirmed = in.decisionPrice.compareTo(BigDecimal.valueOf(in.vwap)) < 0;
                }
                if (!confirmed) {
                    return deny(
                            EntryGateDisposition.EXHAUST,
                            "VWAP",
                            String.format(
                                    Locale.US,
                                    "Spot %s failed VWAP %s confirmation for %s direction",
                                    in.decisionPrice,
                                    String.format(Locale.US, "%.2f", in.vwap),
                                    in.direction));
                }
            }
        }

        // 3. Opening 15-minute range breakout.
        if (Boolean.TRUE.equals(in.range15mEnabled)
                && in.first15mHigh != null
                && in.first15mLow != null
                && in.decisionPrice != null
                && in.direction != null) {
            boolean confirmed;
            if (in.direction == LowestVolumeDirection.LONG) {
                confirmed = in.decisionPrice.compareTo(in.first15mHigh) > 0;
            } else {
                confirmed = in.decisionPrice.compareTo(in.first15mLow) < 0;
            }
            if (!confirmed) {
                return deny(
                        EntryGateDisposition.EXHAUST,
                        "15M_RANGE",
                        String.format(
                                Locale.US,
                                "Spot %s inside 15-min range [%s - %s] for %s",
                                in.decisionPrice,
                                in.first15mLow,
                                in.first15mHigh,
                                in.direction));
            }
        }

        // 4. PDH/PDL breakout (fail-closed when values are missing).
        if (Boolean.TRUE.equals(in.pdhPdlEnabled)) {
            if (in.pdh == null || in.pdl == null) {
                return deny(
                        EntryGateDisposition.EXHAUST,
                        "PDH_PDL",
                        "PDH/PDL unavailable (fail-closed)");
            }
            if (in.decisionPrice != null && in.direction != null) {
                boolean confirmed;
                if (in.direction == LowestVolumeDirection.LONG) {
                    confirmed = in.decisionPrice.compareTo(in.pdh) > 0;
                } else {
                    confirmed = in.decisionPrice.compareTo(in.pdl) < 0;
                }
                if (!confirmed) {
                    return deny(
                            EntryGateDisposition.EXHAUST,
                            "PDH_PDL",
                            String.format(
                                    Locale.US,
                                    "Spot %s inside PDH-PDL range [%s - %s] for %s",
                                    in.decisionPrice,
                                    in.pdl,
                                    in.pdh,
                                    in.direction));
                }
            }
        }

        // 5. PCR (Put-Call Ratio ATM ± 4 strikes) Confirmation Gate
        if (Boolean.TRUE.equals(in.pcrEnabled)) {
            if (in.pcr == null || in.pcr <= 0.0) {
                return deny(
                        EntryGateDisposition.RETRY,
                        "PCR",
                        "PCR filter enabled but no valid PCR (ATM ± 4) available (fail-closed)");
            }
            if (in.direction == LowestVolumeDirection.LONG) {
                double minLong = in.pcrMinLong != null ? in.pcrMinLong : 0.85;
                if (in.pcr < minLong) {
                    return deny(
                            EntryGateDisposition.EXHAUST,
                            "PCR",
                            String.format(
                                    Locale.US,
                                    "PCR %.2f (ATM ± 4) below minimum threshold %.2f for LONG",
                                    in.pcr,
                                    minLong));
                }
            } else if (in.direction == LowestVolumeDirection.SHORT) {
                double maxShort = in.pcrMaxShort != null ? in.pcrMaxShort : 1.15;
                if (in.pcr > maxShort) {
                    return deny(
                            EntryGateDisposition.EXHAUST,
                            "PCR",
                            String.format(
                                    Locale.US,
                                    "PCR %.2f (ATM ± 4) above maximum threshold %.2f for SHORT",
                                    in.pcr,
                                    maxShort));
                }
            }
        }

        // 6. Option Chain Support & Resistance Filter (Block LONG near/above Resistance, Block
        // SHORT near/below Support)
        if (Boolean.TRUE.equals(in.optionSrFilterEnabled)
                && in.decisionPrice != null
                && in.direction != null) {
            if (in.direction == LowestVolumeDirection.LONG && in.optionResistanceStrike != null) {
                double buffer = in.optionSrBufferPct != null ? in.optionSrBufferPct : 0.0;
                BigDecimal effectiveResistance =
                        in.optionResistanceStrike.multiply(
                                BigDecimal.valueOf(1.0 - (buffer / 100.0)));
                if (in.decisionPrice.compareTo(effectiveResistance) >= 0) {
                    return deny(
                            EntryGateDisposition.EXHAUST,
                            "OPTION_SR",
                            String.format(
                                    Locale.US,
                                    "Spot %s is at or above Option Resistance (Max Call OI Strike %s)",
                                    in.decisionPrice,
                                    in.optionResistanceStrike));
                }
            } else if (in.direction == LowestVolumeDirection.SHORT
                    && in.optionSupportStrike != null) {
                double buffer = in.optionSrBufferPct != null ? in.optionSrBufferPct : 0.0;
                BigDecimal effectiveSupport =
                        in.optionSupportStrike.multiply(BigDecimal.valueOf(1.0 + (buffer / 100.0)));
                if (in.decisionPrice.compareTo(effectiveSupport) <= 0) {
                    return deny(
                            EntryGateDisposition.EXHAUST,
                            "OPTION_SR",
                            String.format(
                                    Locale.US,
                                    "Spot %s is at or below Option Support (Max Put OI Strike %s)",
                                    in.decisionPrice,
                                    in.optionSupportStrike));
                }
            }
        }

        // 5. Stand-down (post-cutoff or sentiment collapse).
        if (Boolean.TRUE.equals(in.standDown)) {
            return deny(EntryGateDisposition.RETRY, "STAND_DOWN", "stand-down active for the day");
        }

        // 6. Latched daily circuit breaker.
        if (Boolean.TRUE.equals(in.breakerTripped)) {
            return deny(
                    EntryGateDisposition.RETRY,
                    "BREAKER",
                    "daily max loss circuit breaker is active");
        }

        // 7. Entry cutoff window.
        if (in.nowTime != null && in.entryCutoff != null && !in.nowTime.isBefore(in.entryCutoff)) {
            return deny(
                    EntryGateDisposition.RETRY,
                    "CUTOFF",
                    "entry cutoff " + in.entryCutoff + " reached (now " + in.nowTime + ")");
        }

        // 8. Per-symbol attempt budget.
        if (in.tradeAttempts != null
                && in.maxAttempts != null
                && in.tradeAttempts >= in.maxAttempts) {
            return deny(
                    EntryGateDisposition.RETRY,
                    "ATTEMPTS",
                    "max " + in.maxAttempts + " attempt(s) already used");
        }

        // 9. Max concurrent trades.
        if (in.openConcurrent != null
                && in.maxConcurrent != null
                && in.openConcurrent >= in.maxConcurrent) {
            return deny(
                    EntryGateDisposition.RETRY,
                    "CONCURRENCY",
                    "max concurrent trades (" + in.maxConcurrent + ") reached");
        }

        // 10. F&O registry (static for the day — a miss is permanent → exhaust).
        if (Boolean.FALSE.equals(in.hasRegistryEntry)) {
            return deny(
                    EntryGateDisposition.EXHAUST,
                    "REGISTRY",
                    "F&O registry miss for " + in.symbol + " — lot size/strike step unknown");
        }

        // 11. M2 pre-trade daily budget.
        if (in.plannedRisk != null
                && in.remainingBudget != null
                && in.plannedRisk.doubleValue() > in.remainingBudget) {
            return deny(
                    EntryGateDisposition.RETRY,
                    "BUDGET",
                    String.format(
                            Locale.US,
                            "planned risk %s exceeds remaining daily loss budget %s",
                            String.format(Locale.US, "%.2f", in.plannedRisk.doubleValue()),
                            String.format(Locale.US, "%.2f", Math.max(0.0, in.remainingBudget))));
        }

        return new EntryGateDecision(EntryGateDisposition.ALLOWED, "OK", "all gates passed");
    }

    public enum SectorGate {
        /** Sector confirmed aligned with the trade direction — entry may proceed. */
        ALIGNED,
        /** Sector actively opposed the direction — data-driven, retry on cooldown. */
        MISALIGNED,
        /** No usable constituent quotes — retry on cooldown, exhaust after bounded attempts. */
        NO_DATA
    }

    /** M9: bounded consecutive NO_DATA outcomes before the sector gate fails closed. */
    private static final int MAX_SECTOR_NO_DATA_RETRIES = 6;

    /**
     * Re-checks whether the stock's parent sector is still aligned with the strategy direction. For
     * LONG: Sector average % change must be > 0.0% and advances >= declines. For SHORT: Sector
     * average % change must be < 0.0% and declines >= advances.
     *
     * <p>Boolean view: returns false only for a confirmed misalignment; NO_DATA fails open to
     * preserve historical behaviour. The entry gate uses {@link #evaluateLiveSectorAlignment} for
     * the full tri-state policy.
     */
    public boolean checkLiveSectorAlignment(String symbol, LowestVolumeDirection direction) {
        return evaluateLiveSectorAlignment(symbol, direction) != SectorGate.MISALIGNED;
    }

    /** M9: tri-state sector check — aligned, misaligned, or no usable data. */
    public SectorGate evaluateLiveSectorAlignment(String symbol, LowestVolumeDirection direction) {
        if (marketDataService == null) return SectorGate.ALIGNED;

        String sectorName = NiftySectorRegistry.getSectorForSymbol(symbol);
        if (sectorName == null || sectorName.isBlank()) {
            if (sectorState != null
                    && sectorState.topSector() != null
                    && !sectorState.topSector().isBlank()) {
                sectorName = sectorState.topSector();
            } else {
                return SectorGate.ALIGNED;
            }
        }

        List<String> constituents = NiftySectorRegistry.getStocksForSector(sectorName);
        if (constituents == null || constituents.isEmpty()) {
            return SectorGate.ALIGNED;
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
            return SectorGate.NO_DATA;
        }

        double avgSectorPct = totalPctChange / count;
        SectorGate gate;

        if (direction == LowestVolumeDirection.LONG) {
            gate =
                    (avgSectorPct > 0.0 && advances >= declines)
                            ? SectorGate.ALIGNED
                            : SectorGate.MISALIGNED;
        } else {
            gate =
                    (avgSectorPct < 0.0 && declines >= advances)
                            ? SectorGate.ALIGNED
                            : SectorGate.MISALIGNED;
        }

        log.info(
                "[LVR-SECTOR-CHECK] Rechecked {} sector '{}' at entry: AvgChange={}% (Advances={}, Declines={}, Total={}) | Gate={}",
                symbol,
                sectorName,
                String.format(java.util.Locale.US, "%.2f", avgSectorPct),
                advances,
                declines,
                count,
                gate);

        if (gate == SectorGate.MISALIGNED) {
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

        return gate;
    }

    public boolean isVwapConfirmationEnabled() {
        return vwapConfirmationEnabled;
    }

    public void setVwapConfirmationEnabled(boolean vwapConfirmationEnabled) {
        this.vwapConfirmationEnabled = vwapConfirmationEnabled;
    }

    public boolean isVandeBharatEnabled() {
        return vandeBharatEnabled;
    }

    public void setVandeBharatEnabled(boolean vandeBharatEnabled) {
        this.vandeBharatEnabled = vandeBharatEnabled;
    }

    public double getTargetRr() {
        return targetRr;
    }

    public void setTargetRr(double targetRr) {
        this.targetRr = targetRr;
    }

    public int getMaxDailyLosses() {
        return maxDailyLosses;
    }

    public void setMaxDailyLosses(int maxDailyLosses) {
        this.maxDailyLosses = maxDailyLosses;
    }

    public AtomicInteger getTodayLossCount() {
        return todayLossCount;
    }

    public LocalTime getEntryCutoffTime() {
        return entryCutoffTime;
    }

    public void setEntryCutoffTime(LocalTime entryCutoffTime) {
        this.entryCutoffTime = (entryCutoffTime != null) ? entryCutoffTime : TIME_ENTRY_CUTOFF;
    }

    public boolean isOptionSrFilterEnabled() {
        return optionSrFilterEnabled;
    }

    public void setOptionSrFilterEnabled(boolean optionSrFilterEnabled) {
        this.optionSrFilterEnabled = optionSrFilterEnabled;
    }

    public double getOptionSrBufferPct() {
        return optionSrBufferPct;
    }

    public void setOptionSrBufferPct(double optionSrBufferPct) {
        this.optionSrBufferPct = optionSrBufferPct;
    }

    public boolean isPcrFilterEnabled() {
        return pcrFilterEnabled;
    }

    public void setPcrFilterEnabled(boolean pcrFilterEnabled) {
        this.pcrFilterEnabled = pcrFilterEnabled;
    }

    public double getPcrMinLong() {
        return pcrMinLong;
    }

    public void setPcrMinLong(double pcrMinLong) {
        this.pcrMinLong = pcrMinLong;
    }

    public double getPcrMaxShort() {
        return pcrMaxShort;
    }

    public void setPcrMaxShort(double pcrMaxShort) {
        this.pcrMaxShort = pcrMaxShort;
    }

    public boolean isOpening15mRangeFilterEnabled() {
        return opening15mRangeFilterEnabled;
    }

    public void setOpening15mRangeFilterEnabled(boolean opening15mRangeFilterEnabled) {
        this.opening15mRangeFilterEnabled = opening15mRangeFilterEnabled;
    }

    public boolean isPdhPdlFilterEnabled() {
        return pdhPdlFilterEnabled;
    }

    public void setPdhPdlFilterEnabled(boolean pdhPdlFilterEnabled) {
        this.pdhPdlFilterEnabled = pdhPdlFilterEnabled;
    }

    public String getScannerMode() {
        return scannerMode;
    }

    public void setScannerMode(String scannerMode) {
        this.scannerMode = scannerMode;
    }

    public int getOiSpurtsTopN() {
        return oiSpurtsTopN;
    }

    public void setOiSpurtsTopN(int oiSpurtsTopN) {
        this.oiSpurtsTopN = oiSpurtsTopN;
    }

    public void initPdhPdlForSetup(LowestVolumeSetup setup) {
        if (setup == null) return;
        try {
            LocalDate today = LocalDate.now(clock);
            List<Candle> dailyCandles = null;

            // 1. Primary source: in-memory / SQLite cached daily candles from
            // HistoricalOhlcCacheService
            if (ohlcCacheService != null) {
                try {
                    dailyCandles = ohlcCacheService.getDailyCandles(setup.getSymbol());
                } catch (Exception e) {
                    log.warn(
                            "[LVR] Failed to fetch daily candles from OHLC cache for {}: {}",
                            setup.getSymbol(),
                            e.getMessage());
                }
            }

            List<Candle> pastDaily = filterPastDailyCandles(dailyCandles, today);

            // 2. Fallback: ShoonyaMarketDataService if OHLC cache is unavailable or has no valid
            // past daily candles
            if (pastDaily.isEmpty() && marketDataService != null) {
                try {
                    dailyCandles = marketDataService.fetchDailyCandles(setup.getSymbol(), 5);
                    pastDaily = filterPastDailyCandles(dailyCandles, today);
                } catch (Exception e) {
                    log.warn(
                            "[LVR] Failed to fetch daily candles from Shoonya for {}: {}",
                            setup.getSymbol(),
                            e.getMessage());
                }
            }

            if (!pastDaily.isEmpty()) {
                Candle prevDay = pastDaily.get(pastDaily.size() - 1);
                setup.setPdh(prevDay.high());
                setup.setPdl(prevDay.low());
                log.info(
                        "[LVR] Initialized PDH/PDL for {}: PDH={}, PDL={}",
                        setup.getSymbol(),
                        prevDay.high(),
                        prevDay.low());
            } else {
                log.warn(
                        "[LVR] No historical daily candles found to set PDH/PDL for {}",
                        setup.getSymbol());
            }
        } catch (Exception e) {
            log.warn(
                    "[LVR] Could not initialize PDH/PDL for {}: {}",
                    setup.getSymbol(),
                    e.getMessage());
        }
    }

    private List<Candle> filterPastDailyCandles(List<Candle> dailyCandles, LocalDate today) {
        if (dailyCandles == null || dailyCandles.isEmpty()) {
            return Collections.emptyList();
        }
        return dailyCandles.stream()
                .filter(
                        c ->
                                c.timestamp() != null
                                        && LocalDate.ofInstant(c.timestamp(), IST).isBefore(today))
                .toList();
    }

    public HistoricalOhlcCacheService getOhlcCacheService() {
        return ohlcCacheService;
    }

    public void setOhlcCacheService(HistoricalOhlcCacheService ohlcCacheService) {
        this.ohlcCacheService = ohlcCacheService;
    }

    public String getOrderType() {
        return orderType;
    }

    public void setOrderType(String orderType) {
        this.orderType =
                (orderType != null && !orderType.isBlank())
                        ? orderType.trim().toUpperCase()
                        : "LMT";
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
        // H6: carry same-day archived P&L (from a forced intraday hard reset) so the breaker
        // and /status never lose realized losses.
        double archived =
                (archiveDate != null && archiveDate.equals(today)) ? archivedRealizedPnl : 0.0;
        double live =
                tradeHistory.stream()
                        .filter(
                                p ->
                                        p.getExitTime() != null
                                                && LocalDate.ofInstant(p.getExitTime(), IST)
                                                        .equals(today))
                        .mapToDouble(p -> p.getTotalRealizedPnl().doubleValue())
                        .sum();
        return archived + live;
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

    /**
     * L2: unrealized P&L refreshed at most every 30s — used by pre-trade budget checks and status
     * endpoints so they don't re-fetch quotes on every call.
     */
    public double getCachedUnrealizedPnl() {
        java.time.Instant now = Instant.now(clock);
        if (lastUnrealizedRefresh == null || now.isAfter(lastUnrealizedRefresh.plusSeconds(30))) {
            cachedUnrealizedPnl = calculateOpenPositionsUnrealizedPnl(null);
            lastUnrealizedRefresh = now;
        }
        return cachedUnrealizedPnl;
    }

    /**
     * H6: true while the market session is open (09:30–15:00 IST), evaluated on the injected clock
     * so tests can pin it. Endpoint and Telegram guards refuse destructive intraday actions (e.g.
     * {@code /reset}, which flattens open positions) unless explicitly forced.
     */
    public boolean isWithinTradingHours() {
        LocalTime now = LocalTime.now(clock);
        return !now.isBefore(TIME_EVALUATION_START) && now.isBefore(TIME_HARD_EXIT);
    }

    /**
     * H5: records a failed quote fetch for {@code symbol}; after {@link
     * #QUOTE_STALL_ALERT_THRESHOLD} consecutive failures a single loud Telegram alert is emitted
     * (further alerts suppressed until a success resets the counter).
     */
    private void recordQuoteFailure(String symbol, String context) {
        int failures = quoteFailureCounts.merge(symbol, 1, Integer::sum);
        log.warn(
                "[LVR] Quote fetch failed for {} during {} ({} consecutive failures).",
                symbol,
                context,
                failures);
        if (failures >= QUOTE_STALL_ALERT_THRESHOLD && quoteStallAlerted.add(symbol)) {
            if (telegramAlerts && telegramService != null) {
                telegramService.sendTextMessage(
                        String.format(
                                "🔴 *LVR Quote Stall Alert*\n"
                                        + "• Symbol: `%s`\n"
                                        + "• Context: %s\n"
                                        + "• Consecutive failures: %d\n"
                                        + "• Impact: breach checks/exits for this symbol are"
                                        + " being skipped until quotes recover.",
                                symbol, context, failures));
            }
        }
    }

    /** H5: records a successful quote fetch, resetting the failure streak for the symbol. */
    private void recordQuoteSuccess(String symbol) {
        quoteFailureCounts.remove(symbol);
        quoteStallAlerted.remove(symbol);
    }

    public boolean isDailyCircuitBreakerTripped() {
        return isDailyCircuitBreakerTripped(null);
    }

    public boolean isDailyCircuitBreakerTripped(Map<String, JsonNode> quoteCache) {
        if (maxDailyLoss <= 0.0) return false;
        // M2: once tripped, the breaker latches for the rest of the day — a floating recovery
        // must never silently re-enable new entries.
        if (dailyCircuitBreakerTripped.get()) return true;
        double totalRiskPnl =
                calculateTodayRealizedPnl() + calculateOpenPositionsUnrealizedPnl(quoteCache);
        if (totalRiskPnl <= -maxDailyLoss) {
            dailyCircuitBreakerTripped.set(true);
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
        // L6: a gate EXHAUST outcome discards the symbol for the day (mirrors live
        // exhaustedSymbols) and stops setup re-arming for the rest of the session.
        boolean discardedForDay = false;
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
                    if (currentExitMode == LvrExitMode.FULL_TARGET_1_2) {
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
            if (!discardedForDay
                    && candleTime.isBefore(entryCutoffTime)
                    && attempt < maxAttemptsPerSymbol) {
                LowestVolumeSetup evaluated =
                        evaluateCandleSequence(symbol, direction, historicalSubList, lastExitTime);
                if (evaluated.getState() == LowestVolumeSetupState.TRIGGER_ARMED) {
                    activeSetup = evaluated;
                    if (activeSetup.getPdh() == null && pdhPdlFilterEnabled) {
                        initPdhPdlForSetup(activeSetup);
                    }
                    if ((pcrFilterEnabled || optionSrFilterEnabled)
                            && (activeSetup.getPcr() == null
                                    || activeSetup.getOptionResistanceStrike() == null)) {
                        resolveLiveOptionChainLevels(activeSetup, activeSetup.getTriggerPrice());
                    }
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
                    // VWAP value resolution (data collection — the decision belongs to the gate).
                    double replayVwap = 0.0;
                    if (vwapConfirmationEnabled && taService != null) {
                        double[] vwapSeries = taService.calculateVwapSeries(historicalSubList);
                        if (vwapSeries.length > 0) {
                            replayVwap = vwapSeries[vwapSeries.length - 1];
                        }
                    }

                    // Sizing preview — also feeds the budget gate (identical math to the
                    // position construction below and to executePositionEntry's budget check).
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

                    // L6: evaluate the SAME shared entry gate the live trigger path uses —
                    // slippage, VWAP, 15m range, PDH/PDL, stand-down, breaker, cutoff,
                    // attempts, concurrency, registry and M2 budget can never diverge between
                    // live and replay for identical inputs.
                    EntryGateInput gateInput = new EntryGateInput();
                    gateInput.symbol = symbol;
                    gateInput.direction = direction;
                    gateInput.triggerPrice = triggerPrice;
                    // First touch of the trigger ≈ decision price at the live tick.
                    gateInput.decisionPrice = triggerPrice;
                    // Gap analogue: a candle opening beyond the band would have been seen as
                    // an already-slipped LTP by the live path.
                    gateInput.entryPrice = currentCandle.open();
                    gateInput.maxSlippagePct = maxSlippagePct;
                    gateInput.vwapEnabled = vwapConfirmationEnabled;
                    gateInput.vwap = (replayVwap > 0.0) ? replayVwap : null;
                    gateInput.range15mEnabled = opening15mRangeFilterEnabled;
                    gateInput.first15mHigh = activeSetup.getFirst15MinHigh();
                    gateInput.first15mLow = activeSetup.getFirst15MinLow();
                    gateInput.pdhPdlEnabled = pdhPdlFilterEnabled;
                    gateInput.pdh = activeSetup.getPdh();
                    gateInput.pdl = activeSetup.getPdl();
                    gateInput.pcrEnabled = pcrFilterEnabled;
                    gateInput.pcr = activeSetup.getPcr();
                    gateInput.pcrMinLong = pcrMinLong;
                    gateInput.pcrMaxShort = pcrMaxShort;
                    gateInput.optionSrFilterEnabled = optionSrFilterEnabled;
                    gateInput.optionResistanceStrike = activeSetup.getOptionResistanceStrike();
                    gateInput.optionSupportStrike = activeSetup.getOptionSupportStrike();
                    gateInput.optionSrBufferPct = optionSrBufferPct;
                    gateInput.standDown = standDownToday;
                    gateInput.breakerTripped = dailyCircuitBreakerTripped.get();
                    gateInput.nowTime = candleTime;
                    gateInput.entryCutoff = entryCutoffTime;
                    gateInput.tradeAttempts = attempt;
                    gateInput.maxAttempts = maxAttemptsPerSymbol;
                    gateInput.openConcurrent = openPos != null ? 1 : 0;
                    gateInput.maxConcurrent = maxConcurrentTrades;
                    gateInput.hasRegistryEntry = fno != null;
                    gateInput.plannedRisk = plannedRisk;
                    gateInput.remainingBudget =
                            (maxDailyLoss > 0.0)
                                    ? maxDailyLoss
                                            + calculateTodayRealizedPnl()
                                            + getCachedUnrealizedPnl()
                                    : null;

                    EntryGateDecision gate = evaluateEntryGate(gateInput);
                    if (!gate.allowed()) {
                        if (gate.disposition() == EntryGateDisposition.EXHAUST) {
                            // Mirror live: discard for the day.
                            log.info(
                                    "[LVR][REPLAY] {} discarded for the day: [{}] {}",
                                    symbol,
                                    gate.gate(),
                                    gate.reason());
                            discardedForDay = true;
                            activeSetup = null;
                            continue;
                        }
                        // RETRY / RETRY_COOLDOWN: stay armed and skip entry on this candle —
                        // a 5-minute replay candle is already >= live's 60s retry cooldown.
                        log.debug(
                                "[LVR][REPLAY] Entry for {} deferred ({}): {}",
                                symbol,
                                gate.gate(),
                                gate.reason());
                        continue;
                    }

                    attempt++;
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

    /** H10/N6: {@code true} once the strategy has stood down for the rest of the day. */
    public boolean isStandDownToday() {
        return standDownToday;
    }

    /** M10: overrides where the JSON state snapshot is written/read (tests, deployments). */
    public void setStateFilePath(String stateFilePath) {
        this.stateFilePath = stateFilePath;
    }

    public String getStateFilePath() {
        return stateFilePath;
    }

    /** M10: enables/disables JSON snapshot persistence. */
    public void setStatePersistenceEnabled(boolean statePersistenceEnabled) {
        this.statePersistenceEnabled = statePersistenceEnabled;
    }

    public boolean isStatePersistenceEnabled() {
        return statePersistenceEnabled;
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
        if (pos.getBrokerTradingSymbol() != null && !pos.getBrokerTradingSymbol().isBlank()) {
            return pos.getBrokerTradingSymbol();
        }
        if (pos.getInstrumentType() == LvrInstrumentType.FUTURES) {
            if (marketDataService != null) {
                var fc = marketDataService.resolveFuturesContract(pos.getSymbol());
                if (fc != null && fc.tsym() != null && !fc.tsym().isBlank()) {
                    return fc.tsym();
                }
            }
            return StockFnoRegistry.formatFuturesTradingSymbol(pos.getSymbol(), null);
        } else if (pos.getAtmStrike() != null) {
            return StockFnoRegistry.formatTradingSymbol(
                    pos.getSymbol(), null, pos.getAtmStrike(), pos.getOptionType(), false);
        }
        return pos.getContractSymbol();
    }

    /**
     * H11: when the signal bus rejects an ENTRY (full buffer / no subscriber), undo every side
     * effect of the entry so the paper position, trade attempt, and setup state stay consistent
     * with reality — nothing was (or will be) executed at the broker.
     */
    private void rollbackEntryAfterPublishFailure(
            String symbol, LowestVolumeSetup setup, String tradeId, String reason) {
        openPositions.remove(symbol);
        setup.undoTradeAttempt();
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Entry rolled back: " + reason);
        gateRetryNotBefore.put(symbol, Instant.now(clock).plusSeconds(60));
        log.error(
                "[LVR] Entry for {} rolled back ({}) — signal bus rejected the ENTRY."
                        + " Setup re-armed; will retry after cooldown.",
                symbol,
                reason);
        if (telegramAlerts && telegramService != null) {
            telegramService.sendTextMessage(
                    String.format(
                            "🔴 *LVR Entry Rolled Back*\n• Symbol: `%s`\n• TradeId: `%s`\n"
                                    + "• Reason: %s\n• Setup re-armed; retries after cooldown.",
                            symbol, tradeId, reason));
        }
    }

    private boolean publishSignal(
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
            Map<String, Object> enrichedMeta = new HashMap<>();
            if (metadata != null) {
                enrichedMeta.putAll(metadata);
            }
            enrichedMeta.putIfAbsent("instrumentType", instrumentType.name());

            com.tradingbot.strategy.TradeSignal signal =
                    com.tradingbot.strategy.TradeSignal.of(
                            STRATEGY_ID,
                            underlying,
                            contract != null ? contract : underlying,
                            action,
                            price != null ? price : BigDecimal.ZERO,
                            sl,
                            target,
                            quantity,
                            reason,
                            enrichedMeta);
            return signalPublisher.publish(signal);
        }
        // Paper-only mode (no bus wired) — nothing to fail.
        return true;
    }
}
