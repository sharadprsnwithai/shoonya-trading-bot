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
import com.tradingbot.model.strategy.StockQuoteSnapshot;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.NiftySectorRegistry;
import com.tradingbot.util.StockFnoRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
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
    public static final LocalTime TIME_EVALUATION_START = LocalTime.of(9, 30);
    public static final LocalTime TIME_ENTRY_CUTOFF = LocalTime.of(13, 0);
    public static final LocalTime TIME_HARD_EXIT = LocalTime.of(15, 15);

    private final ShoonyaMarketDataService marketDataService;
    private final TechnicalAnalysisService taService;
    private final TelegramService telegramService;
    private final ShoonyaConfig config;
    private final LowestVolumeReversalScanner scanner;

    private java.time.Clock clock = java.time.Clock.system(IST);

    @Value("${trading-bot.strategy.lowest-volume.enabled:true}")
    private boolean enabled = true;

    @Value("${trading-bot.strategy.lowest-volume.paper-capital:1000000.0}")
    private double paperCapital = 1000000.0;

    @Value("${trading-bot.strategy.lowest-volume.risk-per-trade-percent:1.0}")
    private double riskPerTradePercent = 1.0;

    @Value("${trading-bot.strategy.lowest-volume.max-concurrent-trades:5}")
    private int maxConcurrentTrades = 5;

    @Value("${trading-bot.strategy.lowest-volume.lots:2}")
    private int defaultLots = 2;

    @Value("${trading-bot.strategy.lowest-volume.telegram-alerts:true}")
    private boolean telegramAlerts = true;

    @Value("${trading-bot.strategy.lowest-volume.telegram-armed-alerts:true}")
    private boolean telegramArmedAlerts = true;

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
            @Autowired(required = false) LowestVolumeReversalScanner scanner) {
        this.marketDataService = marketDataService;
        this.taService = taService;
        this.telegramService = telegramService;
        this.config = config;
        this.scanner = (scanner != null) ? scanner : new LowestVolumeReversalScanner();
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
            // 1. Fetch Nifty 50 constituents quotes
            List<StockQuoteSnapshot> niftyQuotes = fetchNifty50Quotes();
            LowestVolumeDirection sentiment = scanner.evaluateMarketSentiment(niftyQuotes);
            this.niftyBullish = (sentiment == LowestVolumeDirection.LONG);

            // 2. Fetch Quotes for the 11 Sectors
            Map<String, List<StockQuoteSnapshot>> sectorQuotes = fetchSectorQuotes();
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
            } else {
                currentTopLosers.clear();
                currentTopLosers.addAll(candidateStocks);
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

            if (isOppositeCandle && c.volume() < rollingLowest) {
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
                rollingLowest = c.volume();
                setup.setDayLowestVolume(rollingLowest);
            } else if (c.volume() < rollingLowest) {
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
                    || setup.getTradeAttempts() >= 2) {
                continue;
            }

            try {
                List<Candle> candles = marketDataService.fetch5MinCandles(symbol, 1);
                if (candles == null || candles.size() < 3) continue;

                LowestVolumeSetup evaluated =
                        evaluateCandleSequence(symbol, setup.getDirection(), candles);

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

        // 1. Check Armed Triggers
        for (Map.Entry<String, LowestVolumeSetup> entry : activeSetups.entrySet()) {
            String symbol = entry.getKey();
            LowestVolumeSetup setup = entry.getValue();

            if (setup.getState() == LowestVolumeSetupState.TRIGGER_ARMED
                    && setup.getTradeAttempts() < 2
                    && openPositions.size() < maxConcurrentTrades) {
                checkSpotTriggerBreach(symbol, setup);
            }
        }

        // 2. Check Open Positions for Spot SL & 1:4 Target
        evaluateOpenPositions(nowTime);
    }

    /**
     * Checks if the live spot price has breached the armed trigger level to enter ATM option trade.
     */
    private void checkSpotTriggerBreach(String symbol, LowestVolumeSetup setup) {
        try {
            double spotLtp = fetchLiveSpotPrice(symbol);
            if (spotLtp <= 0) return;

            BigDecimal spotPrice = BigDecimal.valueOf(spotLtp);
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
                executeOptionEntry(symbol, setup, spotPrice);
            }
        } catch (Exception e) {
            log.error(
                    "[LVR] Error checking spot trigger breach for {}: {}", symbol, e.getMessage());
        }
    }

    /** Executes ATM Option buying upon spot trigger breach. */
    public synchronized LowestVolumePaperPosition executeOptionEntry(
            String symbol, LowestVolumeSetup setup, BigDecimal spotPrice) {
        setup.recordTradeAttempt();
        setup.transitionTo(
                LowestVolumeSetupState.IN_POSITION, "Trigger breached at spot " + spotPrice);

        StockFnoRegistry.InstrumentInfo fno = StockFnoRegistry.get(symbol);
        int lotSize = (fno != null) ? fno.lotSize() : 100;
        BigDecimal strikeStep = (fno != null) ? fno.strikeStep() : BigDecimal.valueOf(10);

        BigDecimal atmStrike = resolveAtmStrike(spotPrice, strikeStep);
        String optType = (setup.getDirection() == LowestVolumeDirection.SHORT) ? "PE" : "CE";
        String optSymbol = symbol + " ATM " + atmStrike + optType;

        double optLtp = fetchOptionLtp(symbol, optType, atmStrike);
        BigDecimal entryPremium = BigDecimal.valueOf(optLtp > 0 ? optLtp : 20.0);

        int totalQty = defaultLots * lotSize;
        BigDecimal plannedRisk =
                spotPrice
                        .subtract(setup.getStopLossPrice())
                        .abs()
                        .multiply(BigDecimal.valueOf(totalQty));

        String tradeId = "LVR-" + tradeCounter.getAndIncrement();

        LowestVolumePaperPosition position =
                new LowestVolumePaperPosition(
                        tradeId,
                        symbol,
                        optType,
                        optSymbol,
                        atmStrike,
                        lotSize,
                        defaultLots,
                        setup.getDirection(),
                        entryPremium,
                        spotPrice,
                        setup.getStopLossPrice(),
                        setup.getTarget1Price(),
                        totalQty,
                        plannedRisk,
                        Instant.now());

        openPositions.put(symbol, position);

        log.info(
                "[LVR] ENTRY EXECUTED: {} | TradeId={} | Option={} | EntryPrem={} | SpotEntry={} | SpotSL={} | SpotTarget1={}",
                symbol,
                tradeId,
                optSymbol,
                entryPremium,
                spotPrice,
                setup.getStopLossPrice(),
                setup.getTarget1Price());

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
                            setup.getTarget1Price().doubleValue()));
        }

        return position;
    }

    /**
     * Evaluates open positions for Spot SL, 1:4 Target Partial Exit, Cost SL, and 10 EMA Trailing.
     */
    public synchronized void evaluateOpenPositions(LocalTime nowTime) {
        if (openPositions.isEmpty()) return;

        for (Map.Entry<String, LowestVolumePaperPosition> entry : openPositions.entrySet()) {
            String symbol = entry.getKey();
            LowestVolumePaperPosition pos = entry.getValue();

            try {
                double spotLtp = fetchLiveSpotPrice(symbol);
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
                    pos.close(optionPremium, "SPOT_SL_HIT", Instant.now());
                    openPositions.remove(symbol);
                    tradeHistory.add(pos);

                    LowestVolumeSetup setup = activeSetups.get(symbol);
                    if (setup != null) {
                        if (setup.getTradeAttempts() < 2) {
                            setup.resetToScanning();
                        } else {
                            setup.transitionTo(
                                    LowestVolumeSetupState.CLOSED_SL, "Max 2 attempts reached");
                            exhaustedSymbols.add(symbol);
                        }
                    }

                    log.info(
                            "[LVR] SL Hit for {}: Closed at Premium={}, Spot={}",
                            symbol,
                            optionPremium,
                            spotPrice);
                    if (telegramAlerts && telegramService != null) {
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
                    continue;
                }

                // 2. Check 1:4 Target Partial Booking on Spot
                if (!pos.isPartialBooked()) {
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
                        pos.executePartialBook(optionPremium, Instant.now());
                        LowestVolumeSetup setup = activeSetups.get(symbol);
                        if (setup != null) {
                            setup.transitionTo(
                                    LowestVolumeSetupState.PARTIAL_BOOKED,
                                    "1:4 RR reached at spot " + spotPrice);
                        }

                        log.info(
                                "[LVR] 1:4 Target Hit for {}: Booked 50% at Premium={}, Cost SL Armed at Spot {}",
                                symbol, optionPremium, pos.getStockEntryPrice());

                        if (telegramAlerts && telegramService != null) {
                            telegramService.sendTextMessage(
                                    String.format(
                                            "🎯 *LVR 1:4 Target Reached (50%% Booked)*\n"
                                                    + "• Symbol: *%s*\n"
                                                    + "• Booked Premium: `₹%.2f` (Partial P&L: `₹%.2f`)\n"
                                                    + "• Spot: `₹%.2f` (Target: `₹%.2f`)\n"
                                                    + "• SL on remaining lots moved to Cost: `₹%.2f`",
                                            symbol,
                                            optionPremium.doubleValue(),
                                            pos.getPartialPnl().doubleValue(),
                                            spotPrice.doubleValue(),
                                            pos.getTarget1StockPrice().doubleValue(),
                                            pos.getStockEntryPrice().doubleValue()));
                        }
                    }
                }

                // 3. Trailing Exit on Runner Lots (Post 50% Booking) via 10 EMA
                if (pos.isPartialBooked() && !pos.isClosed()) {
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

    /** 15:15 IST Hard EOD Square-Off. */
    public synchronized void executeHardExit(LocalTime nowTime) {
        if (openPositions.isEmpty()) return;

        log.info("[LVR] 15:15 IST Hard EOD Square-off reached. Closing all open positions.");
        for (Map.Entry<String, LowestVolumePaperPosition> entry : openPositions.entrySet()) {
            String symbol = entry.getKey();
            LowestVolumePaperPosition pos = entry.getValue();

            double spotLtp = fetchLiveSpotPrice(symbol);
            BigDecimal spotPrice =
                    BigDecimal.valueOf(
                            spotLtp > 0 ? spotLtp : pos.getStockEntryPrice().doubleValue());
            BigDecimal optionPremium = estimateOptionPremium(pos, spotPrice);

            pos.close(optionPremium, "EOD_1515_HARD_EXIT", Instant.now());
            tradeHistory.add(pos);

            LowestVolumeSetup setup = activeSetups.get(symbol);
            if (setup != null) {
                setup.transitionTo(LowestVolumeSetupState.CLOSED_TRAIL_EXIT, "15:15 EOD Exit");
            }

            if (telegramAlerts && telegramService != null) {
                telegramService.sendTextMessage(
                        String.format(
                                "🏁 *LVR 15:15 IST Hard EOD Exit*\n"
                                        + "• Symbol: *%s*\n"
                                        + "• Exit Premium: `₹%.2f` (Total P&L: `₹%.2f`)\n"
                                        + "• Reason: Market Close Square-Off",
                                symbol,
                                optionPremium.doubleValue(),
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
        log.info("[LVR] Daily state reset complete.");
    }

    private BigDecimal resolveAtmStrike(BigDecimal spotPrice, BigDecimal strikeStep) {
        if (strikeStep.compareTo(BigDecimal.ZERO) <= 0) return spotPrice;
        BigDecimal divided = spotPrice.divide(strikeStep, 0, RoundingMode.HALF_UP);
        return divided.multiply(strikeStep);
    }

    private double fetchLiveSpotPrice(String symbol) {
        if (marketDataService == null) return 0.0;
        var info = StockFnoRegistry.get(symbol);
        String token =
                (info != null && info.token() != null)
                        ? info.token()
                        : marketDataService.resolveToken(symbol);
        String exchange = (info != null && info.exchange() != null) ? info.exchange() : "NSE";
        JsonNode node = marketDataService.fetchQuote(exchange, token);
        if (node != null && node.has("lp")) {
            return node.get("lp").asDouble(0.0);
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
        if (marketDataService == null) return 0.0;
        return 0.0; // In live trading, option chain service resolves exact contract LTP
    }

    private List<StockQuoteSnapshot> fetchNifty50Quotes() {
        if (marketDataService == null) return Collections.emptyList();
        List<StockQuoteSnapshot> list = Collections.synchronizedList(new ArrayList<>());
        NiftySectorRegistry.NIFTY_50_CONSTITUENTS.parallelStream()
                .forEach(
                        sym -> {
                            try {
                                var info = StockFnoRegistry.get(sym);
                                String token =
                                        (info != null && info.token() != null)
                                                ? info.token()
                                                : marketDataService.resolveToken(sym);
                                if (token == null || token.isBlank()) return;
                                String exchange =
                                        (info != null && info.exchange() != null)
                                                ? info.exchange()
                                                : "NSE";
                                JsonNode quote = marketDataService.fetchQuote(exchange, token);
                                if (quote != null && quote.has("lp") && quote.has("c")) {
                                    double lp = quote.get("lp").asDouble(0.0);
                                    double c = quote.get("c").asDouble(0.0);
                                    double o = quote.has("o") ? quote.get("o").asDouble(lp) : lp;
                                    if (c > 0) {
                                        double pct = (lp - c) / c * 100.0;
                                        list.add(new StockQuoteSnapshot(sym, lp, c, o, pct));
                                    }
                                }
                            } catch (Exception e) {
                                log.debug(
                                        "[LVR] Error fetching Nifty 50 quote for {}: {}",
                                        sym,
                                        e.getMessage());
                            }
                        });
        return list;
    }

    private Map<String, List<StockQuoteSnapshot>> fetchSectorQuotes() {
        if (marketDataService == null) return Collections.emptyMap();
        Map<String, List<StockQuoteSnapshot>> map = new ConcurrentHashMap<>();
        NiftySectorRegistry.getSectorConstituents().entrySet().parallelStream()
                .forEach(
                        entry -> {
                            String sector = entry.getKey();
                            List<StockQuoteSnapshot> quotes = new ArrayList<>();
                            for (String sym : entry.getValue()) {
                                try {
                                    var info = StockFnoRegistry.get(sym);
                                    String token =
                                            (info != null && info.token() != null)
                                                    ? info.token()
                                                    : marketDataService.resolveToken(sym);
                                    if (token == null || token.isBlank()) continue;
                                    String exchange =
                                            (info != null && info.exchange() != null)
                                                    ? info.exchange()
                                                    : "NSE";
                                    JsonNode q = marketDataService.fetchQuote(exchange, token);
                                    if (q != null && q.has("lp") && q.has("c")) {
                                        double lp = q.get("lp").asDouble(0.0);
                                        double c = q.get("c").asDouble(0.0);
                                        double o = q.has("o") ? q.get("o").asDouble(lp) : lp;
                                        if (c > 0) {
                                            double pct = (lp - c) / c * 100.0;
                                            quotes.add(new StockQuoteSnapshot(sym, lp, c, o, pct));
                                        }
                                    }
                                } catch (Exception e) {
                                    log.debug(
                                            "[LVR] Error fetching sector quote for {}: {}",
                                            sym,
                                            e.getMessage());
                                }
                            }
                            if (!quotes.isEmpty()) {
                                map.put(sector, quotes);
                            }
                        });
        return map;
    }

    // Getters / Setters for Controller, Scheduler & Tests
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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

    public boolean isNiftyBullish() {
        return niftyBullish;
    }

    public boolean isUniverseScanCompletedToday() {
        return universeScanCompletedToday;
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
