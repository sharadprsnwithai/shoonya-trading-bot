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
 * opposite-color volume dry-up triggers, dynamic trailing. 3. Action: ATM Option Buying (PE for
 * Short, CE for Long), Spot-based 1:4 RR partial exit (50% lots), Cost SL, 10 EMA / 15:15 IST
 * trailing.
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

    private java.time.Clock clock = java.time.Clock.system(IST);

    @Value("${trading-bot.strategy.lowest-volume.enabled:true}")
    private boolean enabled = true;

    @Value("${trading-bot.strategy.lowest-volume.instrument-type:FUTURES}")
    private LvrInstrumentType instrumentType = LvrInstrumentType.FUTURES;

    @Value("${trading-bot.strategy.lowest-volume.exit-mode:PARTIAL_1_4_TRAIL_1_1_EOD_1500}")
    private LvrExitMode exitMode = LvrExitMode.PARTIAL_1_4_TRAIL_1_1_EOD_1500;

    @Value("${trading-bot.strategy.lowest-volume.paper-capital:1000000.0}")
    private double paperCapital = 1000000.0;

    @Value("${trading-bot.strategy.lowest-volume.risk-per-trade-percent:1.0}")
    private double riskPerTradePercent = 1.0;

    @Value("${trading-bot.strategy.lowest-volume.max-concurrent-trades:5}")
    private int maxConcurrentTrades = 5;

    @Value("${trading-bot.strategy.lowest-volume.lots:4}")
    private int defaultLots = 4;

    @Value("${trading-bot.strategy.lowest-volume.telegram-alerts:true}")
    private boolean telegramAlerts = true;

    @Value("${trading-bot.strategy.lowest-volume.telegram-armed-alerts:true}")
    private boolean telegramArmedAlerts = true;

    @Value("${trading-bot.strategy.lowest-volume.vwap-confirmation-enabled:true}")
    private boolean vwapConfirmationEnabled = true;

    @Value("${trading-bot.strategy.lowest-volume.setup-timeout-candles:6}")
    private int setupTimeoutCandles = 6;

    private long morningScanDelayMs = 115;

    // State maps
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

    private volatile LowestVolumeSectorState sectorState = LowestVolumeSectorState.empty();
    private volatile boolean niftyBullish = true;
    private volatile boolean universeScanCompletedToday = false;
    private final AtomicInteger tradeCounter = new AtomicInteger(1);

    @Autowired
    public LowestVolumeReversalService(
            ShoonyaMarketDataService marketDataService,
            TechnicalAnalysisService taService,
            TelegramService telegramService,
            ShoonyaConfig config,
            @Autowired(required = false)
                    com.tradingbot.marketdata.ShoonyaOptionChainService optionChainService,
            @Autowired(required = false) LowestVolumeReversalScanner scanner) {
        this.marketDataService = marketDataService;
        this.taService = taService;
        this.telegramService = telegramService;
        this.config = config;
        this.optionChainService = optionChainService;
        this.scanner = (scanner != null) ? scanner : new LowestVolumeReversalScanner();
    }

    public LowestVolumeReversalService(
            ShoonyaMarketDataService marketDataService,
            TechnicalAnalysisService taService,
            TelegramService telegramService,
            ShoonyaConfig config,
            LowestVolumeReversalScanner scanner) {
        this(marketDataService, taService, telegramService, config, null, scanner);
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
            log.info("[LVR] Past 13:00 cutoff. Skipping new setups; managing open positions only.");
            evaluateOpenPositions(nowTime);
            return;
        }

        log.info("[LVR] Executing 5-min strategy cycle at {} IST...", nowTime);
        processCandidateSetups(nowTime);
        evaluateOpenPositions(nowTime);
    }

    /**
     * 09:25 AM Morning Scan: 1. Evaluates NIFTY 50 Adv/Dec sentiment. 2. Ranks 11 NSE Sectors by %
     * change. 3. Selects top winning sector and filters clean F&O candidate stocks.
     */
    public synchronized void runMorningUniverseScan() {
        if (!enabled) return;

        LocalTime nowTime = LocalTime.now(clock);
        log.info("[LVR] Running Morning Sentiment & Sector Scan at {} IST...", nowTime);

        if (marketDataService == null) {
            log.warn("[LVR] MarketDataService not configured (mock/test mode).");
            return;
        }

        try {
            // 1. Fetch Unified Morning Quotes (Deduplicated NIFTY 50 + Sector Constituents, Paced)
            Map<String, StockQuoteSnapshot> universeQuotes = fetchMorningQuotesUnified();
            if (universeQuotes.isEmpty()) {
                log.warn("[LVR] No universe quotes fetched. Morning scan aborted.");
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
            LowestVolumeDirection sentiment = scanner.evaluateMarketSentiment(niftyQuotes);
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
                return;
            }

            LowestVolumeReversalScanner.SectorRankResult topSector = rankedSectors.get(0);
            List<StockQuoteSnapshot> topSectorStockQuotes =
                    sectorQuotes.getOrDefault(topSector.sectorName(), Collections.emptyList());

            List<String> candidateStocks =
                    scanner.filterCandidateStocks(topSectorStockQuotes, sentiment);

            this.sectorState =
                    new LowestVolumeSectorState(
                            (int) niftyQuotes.stream().filter(q -> q.pctChange() > 0).count(),
                            (int) niftyQuotes.stream().filter(q -> q.pctChange() < 0).count(),
                            sentiment,
                            topSector.sectorName(),
                            topSector.pctChange(),
                            candidateStocks);

            log.info(
                    "[LVR] Morning Scan Result: Sentiment={}, Winning Sector={} ({}%), Candidates={}",
                    sentiment, topSector.sectorName(), topSector.pctChange(), candidateStocks);

            // Populate active setups
            activeSetups.clear();
            for (String symbol : candidateStocks) {
                activeSetups.put(symbol, new LowestVolumeSetup(symbol, sentiment));
            }

            if (sentiment == LowestVolumeDirection.LONG) {
                currentTopGainers.clear();
                currentTopGainers.addAll(candidateStocks);
                currentTopGainerSnapshots.clear();
                currentTopGainerSnapshots.addAll(
                        topSectorStockQuotes.stream()
                                .filter(q -> candidateStocks.contains(q.symbol()))
                                .toList());
            } else {
                currentTopLosers.clear();
                currentTopLosers.addAll(candidateStocks);
                currentTopLoserSnapshots.clear();
                currentTopLoserSnapshots.addAll(
                        topSectorStockQuotes.stream()
                                .filter(q -> candidateStocks.contains(q.symbol()))
                                .toList());
            }

            this.universeScanCompletedToday = !candidateStocks.isEmpty();

            if (telegramAlerts && telegramService != null && universeScanCompletedToday) {
                telegramService.sendTextMessage(
                        String.format(
                                "📊 *LVR 09:25 AM Morning Scan*\n"
                                        + "• Sentiment: *%s*\n"
                                        + "• Winning Sector: *%s* (%.2f%%)\n"
                                        + "• Candidates (%d): `%s`\n"
                                        + "• Setup Mode: *5m Lowest Volume Pullback*",
                                sentiment,
                                topSector.sectorName(),
                                topSector.pctChange(),
                                candidateStocks.size(),
                                String.join(", ", candidateStocks)));
            }
        } catch (Exception e) {
            log.error("[LVR] Error during morning universe scan: {}", e.getMessage(), e);
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
        for (int k = 0; k < 3 && k < candles.size(); k++) {
            long v = candles.get(k).volume();
            if (v > 0) {
                baselineLowest = Math.min(baselineLowest, v);
            }
        }
        if (baselineLowest == Long.MAX_VALUE && !candles.isEmpty()) {
            baselineLowest = candles.get(0).volume();
        }
        setup.setDayLowestVolume(baselineLowest);

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
                                && setup.getArmedCandlesElapsed() > setupTimeoutCandles);

                if (slBroken || targetPassed || timedOut) {
                    setup.resetToScanning();
                }
            }

            // 2. Arm or trail trigger if candle is an opposite-color candle with lower volume
            if (isOppositeCandle && c.volume() > 0 && c.volume() < rollingLowest) {
                // Candle is allowed if its 5-minute bar close time (timestamp + 300s) is after the previous exit time
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
                        triggerPrc = c.low().subtract(BigDecimal.valueOf(0.05));
                        slPrc = c.high().add(BigDecimal.valueOf(0.05));
                        BigDecimal risk = slPrc.subtract(triggerPrc);
                        target1Prc =
                                triggerPrc.subtract(risk.multiply(BigDecimal.valueOf(4))); // 1:4 RR
                    } else {
                        triggerPrc = c.high().add(BigDecimal.valueOf(0.05));
                        slPrc = c.low().subtract(BigDecimal.valueOf(0.05));
                        BigDecimal risk = triggerPrc.subtract(slPrc);
                        target1Prc = triggerPrc.add(risk.multiply(BigDecimal.valueOf(4))); // 1:4 RR
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
                    || setup.getTradeAttempts() >= 2
                    || exhaustedSymbols.contains(symbol)) {
                continue;
            }

            try {
                List<Candle> rawCandles = marketDataService.fetch5MinCandles(symbol, 5);
                if (rawCandles == null || rawCandles.isEmpty()) continue;

                LocalDate today = LocalDate.now(IST);
                List<Candle> candles =
                        rawCandles.stream()
                                .filter(c -> LocalDate.ofInstant(c.timestamp(), IST).equals(today))
                                .toList();
                if (candles.isEmpty()) {
                    LocalDate lastDate =
                            LocalDate.ofInstant(
                                    rawCandles.get(rawCandles.size() - 1).timestamp(), IST);
                    candles =
                            rawCandles.stream()
                                    .filter(
                                            c ->
                                                    LocalDate.ofInstant(c.timestamp(), IST)
                                                            .equals(lastDate))
                                    .toList();
                }
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

                if (evaluated.getState() == LowestVolumeSetupState.TRIGGER_ARMED) {
                    boolean newlyArmedOrTrailed =
                            (setup.getTriggerPrice() == null
                                    || setup.getTriggerPrice()
                                                    .compareTo(evaluated.getTriggerPrice())
                                            != 0);
                    setup.setTriggerCandle(
                            evaluated.getTriggerCandle(),
                            evaluated.getTriggerPrice(),
                            evaluated.getStopLossPrice(),
                            evaluated.getTarget1Price());
                    setup.setDayLowestVolume(evaluated.getDayLowestVolume());

                    log.info(
                            "[LVR] Setup ARMED for {}: Dir={}, Trigger={}, SL={}, Target1={}",
                            symbol,
                            setup.getDirection(),
                            setup.getTriggerPrice(),
                            setup.getStopLossPrice(),
                            setup.getTarget1Price());

                    if (telegramAlerts && telegramService != null && newlyArmedOrTrailed) {
                        telegramService.sendTextMessage(
                                String.format(
                                        "⚡ *LVR Setup Armed / Order Trailed*\n"
                                                + "• Symbol: *%s* (%s)\n"
                                                + "• 5m Pullback Vol: `%d` (< day lowest `%d`)\n"
                                                + "• Trigger Price: `₹%.2f`\n"
                                                + "• Spot SL: `₹%.2f` | 1:4 Target: `₹%.2f`",
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
    public synchronized void evaluateLivePriceActions() {
        if (!enabled || marketDataService == null) return;

        LocalTime nowTime = LocalTime.now(clock);
        if (nowTime.isBefore(TIME_SCANNER_START) || !nowTime.isBefore(TIME_HARD_EXIT)) return;

        Map<String, JsonNode> liveQuoteCache = new HashMap<>();

        // 1. Check Armed Triggers (Only allowed strictly before 13:00 IST Entry Cutoff)
        if (nowTime.isBefore(TIME_ENTRY_CUTOFF)) {
            for (Map.Entry<String, LowestVolumeSetup> entry : activeSetups.entrySet()) {
                String symbol = entry.getKey();
                LowestVolumeSetup setup = entry.getValue();

                if (setup.getState() == LowestVolumeSetupState.TRIGGER_ARMED
                        && setup.getTradeAttempts() < 2
                        && openPositions.size() < maxConcurrentTrades) {
                    JsonNode quoteNode =
                            liveQuoteCache.computeIfAbsent(symbol, this::fetchLiveQuoteNode);
                    checkSpotTriggerBreach(symbol, setup, quoteNode);
                }
            }
        }

        // 2. Check Open Positions for Spot SL & 1:4 Target
        evaluateOpenPositions(nowTime, liveQuoteCache);
    }

    /**
     * Checks if the live spot price has breached the armed trigger level to enter ATM option trade.
     */
    private void checkSpotTriggerBreach(String symbol, LowestVolumeSetup setup) {
        checkSpotTriggerBreach(symbol, setup, null);
    }

    private void checkSpotTriggerBreach(
            String symbol, LowestVolumeSetup setup, JsonNode quoteNode) {
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

            // Invalidate setup if spot breaches the proposed stop loss before hitting the entry
            // trigger
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
                if (spotPrice.compareTo(setup.getTriggerPrice()) <= 0) {
                    triggered = true;
                }
            } else if (setup.getDirection() == LowestVolumeDirection.LONG) {
                if (spotPrice.compareTo(setup.getTriggerPrice()) >= 0) {
                    triggered = true;
                }
            }

            if (triggered) {
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
                                            spotPrice.doubleValue(),
                                            vwap,
                                            setup.getDirection()));
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
        setup.recordTradeAttempt();
        setup.transitionTo(
                LowestVolumeSetupState.IN_POSITION, "Trigger breached at spot " + spotPrice);

        StockFnoRegistry.InstrumentInfo fno = StockFnoRegistry.get(symbol);
        int lotSize = (fno != null) ? fno.lotSize() : 100;
        BigDecimal strikeStep = (fno != null) ? fno.strikeStep() : BigDecimal.valueOf(10);
        int totalQty = defaultLots * lotSize;

        BigDecimal unitRisk = spotPrice.subtract(setup.getStopLossPrice()).abs();
        BigDecimal plannedRisk = unitRisk.multiply(BigDecimal.valueOf(totalQty));
        BigDecimal plannedReward =
                unitRisk.multiply(BigDecimal.valueOf(4)).multiply(BigDecimal.valueOf(totalQty));

        // Dynamically compute exact 1:4 Target from actual entry spot price to prevent RR distortion
        BigDecimal actualTarget1;
        if (setup.getDirection() == LowestVolumeDirection.SHORT) {
            actualTarget1 = spotPrice.subtract(unitRisk.multiply(BigDecimal.valueOf(4)));
        } else {
            actualTarget1 = spotPrice.add(unitRisk.multiply(BigDecimal.valueOf(4)));
        }

        String tradeId = "LVR-" + tradeCounter.getAndIncrement();
        LowestVolumePaperPosition position;

        if (instrumentType == LvrInstrumentType.FUTURES) {
            String contractSymbol = symbol + " FUT";
            position =
                    new LowestVolumePaperPosition(
                            tradeId,
                            symbol,
                            LvrInstrumentType.FUTURES,
                            exitMode,
                            contractSymbol,
                            lotSize,
                            defaultLots,
                            setup.getDirection(),
                            spotPrice,
                            setup.getStopLossPrice(),
                            actualTarget1,
                            totalQty,
                            plannedRisk,
                            Instant.now());

            openPositions.put(symbol, position);

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
                                        + "• 1:4 Target Price: `₹%.2f` (Reward: `₹%.2f` /"
                                        + " `+₹%.2f`)",
                                symbol,
                                setup.getDirection(),
                                contractSymbol,
                                spotPrice.doubleValue(),
                                defaultLots,
                                totalQty,
                                setup.getStopLossPrice().doubleValue(),
                                unitRisk.doubleValue(),
                                plannedRisk.doubleValue(),
                                actualTarget1.doubleValue(),
                                unitRisk.multiply(BigDecimal.valueOf(4)).doubleValue(),
                                plannedReward.doubleValue()));
            }
        } else {
            BigDecimal atmStrike = resolveAtmStrike(spotPrice, strikeStep);
            String optType = (setup.getDirection() == LowestVolumeDirection.SHORT) ? "PE" : "CE";
            String optSymbol = symbol + " ATM " + atmStrike + optType;

            double optLtp = fetchOptionLtp(symbol, optType, atmStrike);
            double fallbackPrem = Math.max(0.50, spotPrice.doubleValue() * 0.018);
            BigDecimal entryPremium =
                    BigDecimal.valueOf(optLtp > 0 ? optLtp : fallbackPrem)
                            .setScale(2, RoundingMode.HALF_UP);

            position =
                    new LowestVolumePaperPosition(
                            tradeId,
                            symbol,
                            exitMode,
                            optType,
                            optSymbol,
                            atmStrike,
                            lotSize,
                            defaultLots,
                            setup.getDirection(),
                            entryPremium,
                            spotPrice,
                            setup.getStopLossPrice(),
                            actualTarget1,
                            totalQty,
                            plannedRisk,
                            Instant.now());

            openPositions.put(symbol, position);

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
                                        + "• Spot Target 1 (1:4 RR): `₹%.2f`",
                                symbol,
                                setup.getDirection(),
                                optSymbol,
                                entryPremium.doubleValue(),
                                defaultLots,
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
     * Evaluates open positions for Spot SL, 1:4 Target Partial Exit, Cost SL, and 10 EMA Trailing.
     */
    public synchronized void evaluateOpenPositions(LocalTime nowTime) {
        evaluateOpenPositions(nowTime, Collections.emptyMap());
    }

    public synchronized void evaluateOpenPositions(
            LocalTime nowTime, Map<String, JsonNode> quoteCache) {
        if (openPositions.isEmpty()) return;

        for (Map.Entry<String, LowestVolumePaperPosition> entry : openPositions.entrySet()) {
            String symbol = entry.getKey();
            LowestVolumePaperPosition pos = entry.getValue();

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
                    String slReason =
                            pos.isPartialBooked()
                                    ? (pos.getExitMode() == LvrExitMode.PARTIAL_1_4_TRAIL_1_1_EOD_1500
                                            ? "TRAILING_SL_1_1_HIT"
                                            : "TRAILING_COST_SL_HIT")
                                    : "SPOT_SL_HIT";
                    pos.close(
                            pos.getInstrumentType() == LvrInstrumentType.FUTURES
                                    ? spotPrice
                                    : optionPremium,
                            slReason,
                            Instant.now());
                    openPositions.remove(symbol);
                    tradeHistory.add(pos);

                    LowestVolumeSetup setup = activeSetups.get(symbol);
                    if (setup != null) {
                        if (pos.isPartialBooked()) {
                            setup.transitionTo(
                                    LowestVolumeSetupState.CLOSED_TRAIL_EXIT,
                                    slReason + " at " + spotPrice);
                            exhaustedSymbols.add(symbol);
                        } else if (setup.getTradeAttempts() < 2) {
                            setup.resetToScanning();
                            setup.setLastExitTime(Instant.now());
                        } else {
                            setup.transitionTo(
                                    LowestVolumeSetupState.CLOSED_SL, "Max 2 attempts reached");
                            exhaustedSymbols.add(symbol);
                        }
                    }

                    log.info(
                            "[LVR] SL Hit for {}: Closed at ExitPrice={}, Spot={}, Reason={}",
                            symbol,
                            pos.getInstrumentType() == LvrInstrumentType.FUTURES
                                    ? spotPrice
                                    : optionPremium,
                            spotPrice,
                            slReason);
                    if (telegramAlerts && telegramService != null) {
                        if (pos.isPartialBooked()) {
                            telegramService.sendTextMessage(
                                    String.format(
                                            "🛡️ *LVR 1:1 Trailing Stop Hit (Remaining 50%%"
                                                + " Closed)*\n"
                                                + "• Symbol: *%s* (%s)\n"
                                                + "• Exit Price: `₹%.2f` (1:1 SL was `₹%.2f`)\n"
                                                + "• Runner Realized P&L: `₹%.2f`\n"
                                                + "• Total Realized P&L: `₹%.2f`",
                                            symbol,
                                            pos.getDirection(),
                                            spotPrice.doubleValue(),
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
                                                    ? Math.max(0, 2 - setup.getTradeAttempts())
                                                    : 0));
                        } else {
                            telegramService.sendTextMessage(
                                    String.format(
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

                // 2. Check 1:4 Target on Spot
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
                        if (currentExitMode == LvrExitMode.FULL_TARGET_1_4) {
                            // 100% Full Exit at 1:4 Target
                            if (pos.getInstrumentType() == LvrInstrumentType.FUTURES) {
                                pos.closeFullFutures(
                                        spotPrice, "TARGET_1_4_FULL_EXIT", Instant.now());
                            } else {
                                pos.close(optionPremium, "TARGET_1_4_FULL_EXIT", Instant.now());
                            }
                            openPositions.remove(symbol);
                            tradeHistory.add(pos);

                            LowestVolumeSetup setup = activeSetups.get(symbol);
                            if (setup != null) {
                                setup.transitionTo(
                                        LowestVolumeSetupState.CLOSED_TARGET,
                                        "1:4 Target reached at spot " + spotPrice);
                                exhaustedSymbols.add(symbol);
                            }

                            log.info(
                                    "[LVR] 1:4 Target 100% Full Exit for {}: Closed at Spot={}, Realized PnL={}",
                                    symbol, spotPrice, pos.getTotalRealizedPnl());

                            if (telegramAlerts && telegramService != null) {
                                BigDecimal pts =
                                        (pos.getDirection() == LowestVolumeDirection.LONG)
                                                ? spotPrice.subtract(pos.getStockEntryPrice())
                                                : pos.getStockEntryPrice().subtract(spotPrice);
                                telegramService.sendTextMessage(
                                        String.format(
                                                "🎯 *LVR 1:4 Target Reached (100%% Full Exit)*\n"
                                                        + "• Symbol: *%s* (%s)\n"
                                                        + "• Exit Price: `₹%.2f` (Target was `₹%.2f`)\n"
                                                        + "• Points Captured: `+%.2f pts` (+4.00 R)\n"
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
                            if (setup != null) {
                                setup.transitionTo(
                                        LowestVolumeSetupState.PARTIAL_BOOKED,
                                        "1:4 RR reached at spot " + spotPrice);
                            }

                            log.info(
                                    "[LVR] 1:4 Target Hit for {}: Booked 50% at ExitPrice={}, 1:1 SL Armed at Spot {}",
                                    symbol, partialExitPrice, pos.getCurrentStockSl());

                            if (telegramAlerts && telegramService != null) {
                                telegramService.sendTextMessage(
                                        String.format(
                                                "🎯 *LVR 1:4 Target Reached (50%% Booked)*\n"
                                                        + "• Symbol: *%s* (%s)\n"
                                                        + "• Booked Price: `₹%.2f` (Partial P&L: `₹%.2f`)\n"
                                                        + "• Spot: `₹%.2f` (Target: `₹%.2f`)\n"
                                                        + "• 1:1 SL on remaining lots: `₹%.2f` (+1.00R locked)\n"
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

                // 3. Trailing Exit on Runner Lots (Post 50% Booking) via 10 EMA (Options mode only)
                if (pos.isPartialBooked()
                        && !pos.isClosed()
                        && pos.getExitMode() == LvrExitMode.PARTIAL_RUNNER_10EMA) {
                    List<Candle> candles = marketDataService.fetch5MinCandles(symbol, 20);
                    if (candles != null && candles.size() >= 10) {
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
                            pos.close(optionPremium, "10_EMA_TRAIL_EXIT", Instant.now());
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
                                    "[LVR] 10 EMA Trail Exit triggered for {}: Closed remaining runner at Premium={}, Spot={}, EMA10={}",
                                    symbol,
                                    optionPremium,
                                    spotPrice,
                                    ema10);

                            if (telegramAlerts && telegramService != null) {
                                telegramService.sendTextMessage(
                                        String.format(
                                                "🏃 *LVR 10 EMA Trailing Exit*\n"
                                                        + "• Symbol: *%s*\n"
                                                        + "• Runner Exit Premium: `₹%.2f`\n"
                                                        + "• Total Realized P&L: `₹%.2f`\n"
                                                        + "• Spot Close: `₹%.2f` | 10 EMA: `₹%.2f`",
                                                symbol,
                                                optionPremium.doubleValue(),
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

        log.info("[LVR] 15:00 IST Hard EOD Square-off reached. Closing all open positions.");
        for (Map.Entry<String, LowestVolumePaperPosition> entry : openPositions.entrySet()) {
            String symbol = entry.getKey();
            LowestVolumePaperPosition pos = entry.getValue();

            double spotLtp = fetchLiveSpotPrice(symbol);
            BigDecimal spotPrice =
                    BigDecimal.valueOf(
                            spotLtp > 0 ? spotLtp : pos.getStockEntryPrice().doubleValue());
            BigDecimal optionPremium = estimateOptionPremium(pos, spotPrice);

            BigDecimal exitVal =
                    (pos.getInstrumentType() == LvrInstrumentType.FUTURES)
                            ? spotPrice
                            : optionPremium;
            pos.close(exitVal, "EOD_1500_HARD_EXIT", Instant.now());
            tradeHistory.add(pos);

            LowestVolumeSetup setup = activeSetups.get(symbol);
            if (setup != null) {
                setup.transitionTo(LowestVolumeSetupState.CLOSED_TRAIL_EXIT, "15:00 EOD Exit");
                exhaustedSymbols.add(symbol);
            }

            if (telegramAlerts && telegramService != null) {
                telegramService.sendTextMessage(
                        String.format(
                                "🏁 *LVR 15:00 IST Hard EOD Exit*\n"
                                        + "• Symbol: *%s* (%s)\n"
                                        + "• Exit Price: `₹%.2f`\n"
                                        + "• Runner Realized P&L: `₹%.2f`\n"
                                        + "• Total Realized P&L: `₹%.2f`\n"
                                        + "• Reason: Market Close Square-Off",
                                symbol,
                                pos.getDirection(),
                                exitVal.doubleValue(),
                                pos.getRunnerPnl().doubleValue(),
                                pos.getTotalRealizedPnl().doubleValue()));
            }
        }
        openPositions.clear();
    }

    public synchronized void resetDaily() {
        activeSetups.clear();
        openPositions.clear();
        tradeHistory.clear();
        exhaustedSymbols.clear();
        currentTopGainers.clear();
        currentTopLosers.clear();
        currentTopGainerSnapshots.clear();
        currentTopLoserSnapshots.clear();
        sectorState = LowestVolumeSectorState.empty();
        universeScanCompletedToday = false;
        tradeCounter.set(1);
        log.info("[LVR] Daily state reset complete.");
    }

    private BigDecimal resolveAtmStrike(BigDecimal spotPrice, BigDecimal strikeStep) {
        if (strikeStep.compareTo(BigDecimal.ZERO) <= 0) return spotPrice;
        BigDecimal divided = spotPrice.divide(strikeStep, 0, RoundingMode.HALF_UP);
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
                    LocalDate today = LocalDate.now(IST);
                    List<Candle> candles =
                            rawCandles.stream()
                                    .filter(
                                            c ->
                                                    LocalDate.ofInstant(c.timestamp(), IST)
                                                            .equals(today))
                                    .toList();
                    if (candles.isEmpty()) {
                        candles = rawCandles;
                    }
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

        BigDecimal delta = BigDecimal.valueOf(0.50); // ATM Delta
        BigDecimal spotMove;
        if (pos.getDirection() == LowestVolumeDirection.SHORT) {
            spotMove = entrySpot.subtract(currentSpot); // Profitable when spot falls
        } else {
            spotMove = currentSpot.subtract(entrySpot); // Profitable when spot rises
        }

        BigDecimal estPrem = entryPrem.add(spotMove.multiply(delta));
        if (estPrem.compareTo(BigDecimal.valueOf(0.50)) < 0) {
            estPrem = BigDecimal.valueOf(0.50);
        }
        return estPrem.setScale(2, RoundingMode.HALF_UP);
    }

    private double fetchOptionLtp(String symbol, String optionType, BigDecimal strike) {
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

    public boolean isVwapConfirmationEnabled() {
        return vwapConfirmationEnabled;
    }

    public void setVwapConfirmationEnabled(boolean vwapConfirmationEnabled) {
        this.vwapConfirmationEnabled = vwapConfirmationEnabled;
    }

    public void setTelegramAlerts(boolean telegramAlerts) {
        this.telegramAlerts = telegramAlerts;
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

                // Check Stop Loss breach on spot
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

                // Check Target breach on spot
                boolean targetHit = false;
                if (!openPos.isPartialBooked()) {
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

                if (targetHit) {
                    LvrExitMode currentExitMode =
                            (openPos.getExitMode() != null) ? openPos.getExitMode() : exitMode;
                    if (currentExitMode == LvrExitMode.FULL_TARGET_1_4) {
                        // 100% Full Exit at Target
                        if (openPos.getInstrumentType() == LvrInstrumentType.FUTURES) {
                            openPos.closeFullFutures(
                                    openPos.getTarget1StockPrice(),
                                    "TARGET_1_4_FULL_EXIT",
                                    currentCandle.timestamp());
                        } else {
                            BigDecimal prem =
                                    estimateOptionPremium(openPos, openPos.getTarget1StockPrice());
                            openPos.close(prem, "TARGET_1_4_FULL_EXIT", currentCandle.timestamp());
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
                        if (activeSetup != null) {
                            activeSetup.transitionTo(
                                    LowestVolumeSetupState.PARTIAL_BOOKED, "1:4 RR partial booked");
                        }
                    }
                } else if (slHit) {
                    String slReason =
                            openPos.isPartialBooked()
                                    ? (openPos.getExitMode()
                                                    == LvrExitMode.PARTIAL_1_4_TRAIL_1_1_EOD_1500
                                            ? "TRAILING_SL_1_1_HIT"
                                            : "TRAILING_COST_SL_HIT")
                                    : "SPOT_SL_HIT";
                    if (openPos.getInstrumentType() == LvrInstrumentType.FUTURES) {
                        openPos.close(
                                openPos.getCurrentStockSl(),
                                slReason,
                                currentCandle.timestamp());
                    } else {
                        BigDecimal prem =
                                estimateOptionPremium(openPos, openPos.getCurrentStockSl());
                        openPos.close(prem, slReason, currentCandle.timestamp());
                    }
                    completedTrades.add(openPos);
                    lastExitTime = currentCandle.timestamp();
                    openPos = null;
                    activeSetup = null;
                    if (attempt >= 2) {
                        break; // Exhausted max 2 attempts
                    }
                } else if (openPos.isPartialBooked()
                        && !openPos.isClosed()
                        && openPos.getExitMode() == LvrExitMode.PARTIAL_RUNNER_10EMA
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
                        BigDecimal prem = estimateOptionPremium(openPos, close);
                        openPos.close(prem, "10_EMA_TRAIL_EXIT", currentCandle.timestamp());
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
            if (candleTime.isBefore(TIME_ENTRY_CUTOFF) && attempt < 2) {
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
                    && attempt < 2) {
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

                if (slBreached && !triggered) {
                    activeSetup = null;
                    continue;
                }

                if (triggered) {
                    if (vwapConfirmationEnabled && taService != null) {
                        double[] vwapSeries = taService.calculateVwapSeries(historicalSubList);
                        double currentVwap =
                                (vwapSeries.length > 0)
                                        ? vwapSeries[vwapSeries.length - 1]
                                        : 0.0;
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
                    boolean prevAlerts = telegramAlerts;
                    telegramAlerts = false;
                    openPos = executePositionEntry(symbol, activeSetup, triggerPrice);
                    telegramAlerts = prevAlerts;
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
        if (telegramService != null) {
            telegramService.sendTextMessage("📊 *LVR Sector & Watchlist Report*\n" + sectorState);
        }
    }
}
