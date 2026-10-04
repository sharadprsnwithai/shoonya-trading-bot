package com.tradingbot.strategy.bollingerha.service;

import com.tradingbot.bus.ReactiveSignalEventBus;
import com.tradingbot.model.Candle;
import com.tradingbot.persistence.BollingerHaStateStore;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import com.tradingbot.strategy.bollingerha.config.BollingerHaProperties;
import com.tradingbot.strategy.bollingerha.indicator.BollingerHaCalculator;
import com.tradingbot.strategy.bollingerha.model.BollingerBandResult;
import com.tradingbot.strategy.bollingerha.model.BollingerHaDailyState;
import com.tradingbot.strategy.bollingerha.model.BollingerHaPosition;
import com.tradingbot.strategy.bollingerha.model.BollingerHaSetupState;
import com.tradingbot.strategy.bollingerha.model.CompletedCandleEvent;
import com.tradingbot.strategy.bollingerha.model.HeikinAshiCandle;
import com.tradingbot.util.StockFnoRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Core engine for the 1-Minute Bollinger Bands & Heikin-Ashi Intraday Option Buying Strategy.
 * Evaluates entry signals, manages 1:2 partial targets, trailing Cost SL, and daily trade limits.
 */
@Service
public class BollingerHaIntradayEngine {

    private static final Logger log = LoggerFactory.getLogger(BollingerHaIntradayEngine.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final String STRATEGY_ID = "BOLLINGER_HA_1M";
    public static final int NIFTY_LOT_SIZE = 65;

    /** Cap on the spot EMA sample buffer (one sample/minute ≈ 375/day; this is headroom). */
    private static final int MAX_SPOT_HISTORY = 10000;

    private final BollingerHaProperties properties;
    private final ReactiveSignalEventBus eventBus;
    private final com.tradingbot.telegram.TelegramService telegramService;
    private final Path stateFilePath;

    private final Map<String, BollingerHaSetupState> setupStates = new ConcurrentHashMap<>();
    private final Map<String, List<Candle>> candleHistory = new ConcurrentHashMap<>();
    private final List<BigDecimal> spotPriceHistory =
            Collections.synchronizedList(new ArrayList<>());
    private BigDecimal latestSpotPrice;
    private BollingerHaDailyState dailyState;
    private BollingerHaPosition activePosition;

    /** Position recovered from a previous-day snapshot; flattened on the first live event. */
    private BollingerHaPosition pendingOrphanExit;

    public BollingerHaIntradayEngine(
            BollingerHaProperties properties, ReactiveSignalEventBus eventBus) {
        this(properties, eventBus, null);
    }

    @Autowired
    public BollingerHaIntradayEngine(
            BollingerHaProperties properties,
            ReactiveSignalEventBus eventBus,
            @Autowired(required = false) com.tradingbot.telegram.TelegramService telegramService) {
        this.properties = properties;
        this.eventBus = eventBus;
        this.telegramService = telegramService;
        this.stateFilePath = Path.of(properties.getStateFilePath());
        this.dailyState = new BollingerHaDailyState(LocalDate.now(IST));
        restorePersistedState();
    }

    /**
     * D4: reloads today's state (and an open position) from disk so a restart mid-session resumes
     * rather than forgetting the trade. A snapshot from a previous trading day is not restored —
     * its open position is queued for a forced exit so an orphaned broker position cannot survive a
     * restart.
     */
    private void restorePersistedState() {
        BollingerHaStateStore.State snapshot = BollingerHaStateStore.load(stateFilePath);
        if (snapshot == null || snapshot.tradeDate == null) {
            return;
        }
        LocalDate today = LocalDate.now(IST);

        if (snapshot.tradeDate.equals(today)) {
            List<BollingerHaPosition> positions = new ArrayList<>();
            if (snapshot.positions != null) {
                snapshot.positions.forEach(p -> positions.add(restorePosition(p)));
            }
            dailyState = new BollingerHaDailyState(today);
            dailyState.restore(
                    snapshot.tradeCount, snapshot.realizedPnl, snapshot.locked, positions);
            if (snapshot.openPosition != null && !snapshot.openPosition.closed) {
                activePosition = restorePosition(snapshot.openPosition);
                log.info(
                        "[BOLLINGER-HA] Restored open position {} ({} qty) from {} state file.",
                        activePosition.getSymbol(),
                        activePosition.getRemainingQuantity(),
                        today);
            }
            log.info(
                    "[BOLLINGER-HA] Restored state for {}: trades={} realizedPnl={} locked={}",
                    today,
                    dailyState.getTradeCount(),
                    dailyState.getRealizedPnl(),
                    dailyState.isLocked());
            return;
        }

        log.warn(
                "[BOLLINGER-HA] State file is from {} (not today); starting a fresh day.",
                snapshot.tradeDate);
        if (snapshot.openPosition != null && !snapshot.openPosition.closed) {
            pendingOrphanExit = restorePosition(snapshot.openPosition);
            log.warn(
                    "[BOLLINGER-HA] Orphaned position {} from {} will be squared off on the next"
                            + " live event.",
                    pendingOrphanExit.getSymbol(),
                    snapshot.tradeDate);
            // Deliberately NOT persisted here: the orphan stays on disk so a crash before the
            // forced exit is emitted does not lose it. persistState() runs once it is published.
        }
    }

    private void emitPendingOrphanExit() {
        if (pendingOrphanExit == null) {
            return;
        }
        BollingerHaPosition orphan = pendingOrphanExit;
        pendingOrphanExit = null;

        Map<String, Object> meta = new HashMap<>();
        meta.put("instrumentType", "OPTION");
        meta.put("optionType", orphan.getOptionType());
        meta.put("positionSide", "LONG");
        meta.put("exitReason", "Orphaned position — forced exit at startup");
        TradeSignal signal =
                TradeSignal.of(
                        STRATEGY_ID,
                        properties.getUnderlying(),
                        orphan.getSymbol(),
                        SignalAction.SQUARE_OFF,
                        orphan.getStopLoss(),
                        orphan.getStopLoss(),
                        orphan.getTargetPrice(),
                        orphan.getRemainingQuantity(),
                        "Orphaned position from a previous session — forced exit at startup",
                        meta);
        if (publishSignal(signal)) {
            log.warn(
                    "[BOLLINGER-HA] Emitted forced exit for orphaned position {} ({} qty).",
                    orphan.getSymbol(),
                    orphan.getRemainingQuantity());
            persistState();
        } else {
            // Keep it queued — the next live event retries the forced exit.
            pendingOrphanExit = orphan;
        }
    }

    /** D6: publishes first — the bus contract requires the caller to roll back on {@code false}. */
    private boolean publishSignal(TradeSignal signal) {
        boolean accepted = eventBus.publish(signal);
        if (!accepted) {
            log.error(
                    "[BOLLINGER-HA] Signal {} REJECTED by the event bus — rolling back the"
                            + " engine mutation for {}.",
                    signal.signalId(),
                    signal.tradingSymbol());
        }
        return accepted;
    }

    /**
     * Registers and initializes the tracking state for a Call or Put strike.
     *
     * @param optionType "CE" or "PE"
     * @param token Instrument token
     * @param symbol Trading symbol
     * @param strike Strike price
     */
    public synchronized void initStrike(
            String optionType, String token, String symbol, BigDecimal strike) {
        String optType = optionType.toUpperCase();
        BollingerHaSetupState state = new BollingerHaSetupState(optType, token, symbol, strike);
        setupStates.put(optType, state);
        candleHistory.put(token, Collections.synchronizedList(new ArrayList<>()));
        log.info(
                "[BOLLINGER-HA] Initialized strike tracking for {} -> {} ({})",
                optType,
                symbol,
                token);
    }

    /**
     * Processes a completed 1-minute candle.
     *
     * @param event Completed candle event
     */
    public synchronized void onCandleCompleted(CompletedCandleEvent event) {
        if (!properties.isEnabled() || event == null || event.candle() == null) {
            return;
        }

        checkDailyRollover(event.completedAt());
        emitPendingOrphanExit();
        if (enforceAutoSquareOff(event.completedAt())) {
            return;
        }

        String token = event.token();
        List<Candle> history =
                candleHistory.computeIfAbsent(
                        token, k -> Collections.synchronizedList(new ArrayList<>()));
        history.add(event.candle());

        // 1. Manage Active Position (Target 1 / Cost SL / Stop Loss)
        if (activePosition != null && !activePosition.isClosed()) {
            if (activePosition.getToken().equals(token)) {
                manageActivePositionOnCandle(event.candle());
            }
            return; // Only 1 active trade allowed at a time
        }

        // 2. If no position, evaluate entry conditions
        if (canEnterNewTrade(event.completedAt())) {
            evaluateEntrySignal(event);
        }
    }

    /**
     * D9: tick-driven management path. Runs the same handlers as the candle backstop so a target or
     * stop is acted on the moment the price touches it rather than at the next bar close.
     *
     * @param token instrument token of the tick
     * @param ltp last traded price
     * @param timestamp tick time
     */
    public synchronized void onTick(String token, BigDecimal ltp, Instant timestamp) {
        if (!properties.isEnabled() || token == null || ltp == null) {
            return;
        }
        emitPendingOrphanExit();
        if (enforceAutoSquareOff(timestamp)) {
            return;
        }
        if (activePosition == null
                || activePosition.isClosed()
                || !activePosition.getToken().equals(token)) {
            return;
        }
        if (!activePosition.isTargetHit() && ltp.compareTo(activePosition.getTargetPrice()) >= 0) {
            handleTargetHit(ltp, timestamp);
        }
        if (activePosition != null
                && !activePosition.isClosed()
                && ltp.compareTo(activePosition.getStopLoss()) <= 0) {
            handleStopLossHit(ltp, timestamp);
        }
    }

    /**
     * D3: hard intraday exit safety net, driven by {@code autoSquareOffTime}. Called from both the
     * candle and tick paths (and by the scheduler's per-minute poller) so a missed cron or a
     * restart after the cutoff still flattens the position.
     *
     * @return {@code true} when a square-off was executed, so callers stop processing the event
     */
    public synchronized boolean enforceAutoSquareOff(Instant at) {
        if (activePosition == null || activePosition.isClosed()) {
            return false;
        }
        LocalTime cutoff = LocalTime.parse(properties.getAutoSquareOffTime());
        LocalTime now = (at != null ? at : Instant.now()).atZone(IST).toLocalTime();
        if (now.isBefore(cutoff)) {
            return false;
        }
        log.warn(
                "[BOLLINGER-HA] Auto square-off window reached ({} >= {}). Flattening {}.",
                now,
                cutoff,
                activePosition.getSymbol());
        squareOffAll("Intraday Auto Square-Off at " + cutoff + " IST");
        return true;
    }

    /**
     * D9: candle backstop with gap-aware ordering. A candle that <em>opens</em> beyond the stop was
     * stopped before anything else printed, so that is checked first; only then does the target get
     * its chance, and finally a stop touched intrabar.
     */
    private void manageActivePositionOnCandle(Candle candle) {
        BigDecimal stopLoss = activePosition.getStopLoss();

        if (candle.open().compareTo(stopLoss) <= 0) {
            handleStopLossHit(candle.open().min(candle.close()), candle.timestamp());
            return;
        }

        if (!activePosition.isTargetHit()
                && candle.high().compareTo(activePosition.getTargetPrice()) >= 0) {
            handleTargetHit(candle.high(), candle.timestamp());
        }

        if (activePosition != null
                && !activePosition.isClosed()
                && candle.low().compareTo(activePosition.getStopLoss()) <= 0) {
            handleStopLossHit(candle.close(), candle.timestamp());
        }
    }

    /**
     * D4/C4: books the first target. The exit quantity is floored to whole lots; a position smaller
     * than two lots cannot be split, so it emits a stop-trail instead of a partial exit.
     *
     * @param triggerPrice price that touched the target
     * @param at event time
     * @return {@code true} when the target was handled (published and applied)
     */
    private boolean handleTargetHit(BigDecimal triggerPrice, Instant at) {
        if (activePosition == null || activePosition.isClosed() || activePosition.isTargetHit()) {
            return false;
        }

        BigDecimal entry = activePosition.getEntryPrice();
        BigDecimal target = activePosition.getTargetPrice();
        int totalQuantity = activePosition.getTotalQuantity();
        int lotSize = resolveLotSize();
        int exitQuantity = alignedPartialQuantity(totalQuantity, lotSize);

        if (exitQuantity <= 0) {
            Map<String, Object> meta = baseMeta();
            meta.put("positionSide", "LONG");
            meta.put("remainingQuantity", totalQuantity);
            TradeSignal updateSl =
                    TradeSignal.of(
                            STRATEGY_ID,
                            properties.getUnderlying(),
                            activePosition.getSymbol(),
                            SignalAction.UPDATE_STOP_LOSS,
                            entry,
                            entry,
                            target,
                            totalQuantity,
                            "Target 1 Reached - Single-Lot Position Trailed To Cost",
                            meta);
            if (!publishSignal(updateSl)) {
                return false;
            }
            activePosition.setTargetHit(true);
            activePosition.setCostSlActive(true);
            activePosition.setStopLoss(entry);
            log.info(
                    "[BOLLINGER-HA] Target 1 hit for {} at {} — single lot ({} qty, lot {})"
                            + " cannot be split; trailing stop to cost {}.",
                    activePosition.getSymbol(),
                    triggerPrice,
                    totalQuantity,
                    lotSize,
                    entry);
            persistState();
            return true;
        }

        int remainingQuantity = totalQuantity - exitQuantity;
        Map<String, Object> meta = baseMeta();
        meta.put("targetPrice", target);
        meta.put("stopLoss", entry);
        meta.put("positionSide", "LONG");
        meta.put("remainingQuantity", remainingQuantity);

        TradeSignal partialExit =
                TradeSignal.of(
                        STRATEGY_ID,
                        properties.getUnderlying(),
                        activePosition.getSymbol(),
                        SignalAction.PARTIAL_EXIT_LONG,
                        target,
                        entry,
                        target,
                        exitQuantity,
                        "Target 1 (1:2) Reached - Book Partial Profit",
                        meta);
        if (!publishSignal(partialExit)) {
            return false;
        }

        activePosition.setTargetHit(true);
        activePosition.setCostSlActive(true);
        activePosition.setStopLoss(entry); // Cost SL on the runner
        activePosition.setRemainingQuantity(remainingQuantity);

        BigDecimal partialPnl = target.subtract(entry).multiply(BigDecimal.valueOf(exitQuantity));
        dailyState.addRealizedPnl(partialPnl);

        log.info(
                "[BOLLINGER-HA] Target 1 hit for {} at {}. Booked {} of {} qty, runner {} at cost"
                        + " SL {} (booked PnL +{}).",
                activePosition.getSymbol(),
                triggerPrice,
                exitQuantity,
                totalQuantity,
                remainingQuantity,
                entry,
                partialPnl);

        if (telegramService != null) {
            String msg =
                    String.format(
                            "🎯 *[BOLLINGER HA] TARGET 1 (1:2 R:R) HIT!*\n"
                                    + "📈 *Symbol:* `%s`\n"
                                    + "💰 *Booked Qty:* %d @ ₹%s (+₹%s PnL)\n"
                                    + "🛡️ *Cost SL Activated:* ₹%s on remaining %d Qty (Risk-Free Runner)",
                            activePosition.getSymbol(),
                            exitQuantity,
                            target,
                            partialPnl,
                            entry,
                            remainingQuantity);
            telegramService.sendAlert(msg);
        }
        persistState();
        return true;
    }

    /**
     * D8: closes the remainder on a stop touch.
     *
     * @param observedPrice worst price seen at/through the stop (close for a candle, ltp for a
     *     tick)
     * @param at event time
     * @return {@code true} when the position was closed
     */
    private boolean handleStopLossHit(BigDecimal observedPrice, Instant at) {
        if (activePosition == null || activePosition.isClosed()) {
            return false;
        }

        BigDecimal stopLoss = activePosition.getStopLoss();
        // When the print closed beyond the stop we were away from the screen — book the worse of
        // the stop and the observed price instead of pretending the stop filled at its trigger.
        BigDecimal exitPrice =
                observedPrice != null && observedPrice.compareTo(stopLoss) < 0
                        ? observedPrice
                        : stopLoss;
        int exitQuantity = activePosition.getRemainingQuantity();
        String reason = activePosition.isCostSlActive() ? "Cost SL Hit" : "Initial SL Hit";

        Map<String, Object> meta = baseMeta();
        meta.put("exitReason", reason);
        meta.put("positionSide", "LONG");
        meta.put("remainingQuantity", exitQuantity);

        TradeSignal exitSig =
                TradeSignal.of(
                        STRATEGY_ID,
                        properties.getUnderlying(),
                        activePosition.getSymbol(),
                        SignalAction.EXIT_LONG,
                        exitPrice,
                        stopLoss,
                        activePosition.getTargetPrice(),
                        exitQuantity,
                        reason,
                        meta);
        if (!publishSignal(exitSig)) {
            return false;
        }

        activePosition.setClosed(true);
        activePosition.setExitPrice(exitPrice);
        activePosition.setExitTime(at != null ? at : Instant.now());
        activePosition.setExitReason(reason);

        BigDecimal tradePnl =
                exitPrice
                        .subtract(activePosition.getEntryPrice())
                        .multiply(BigDecimal.valueOf(exitQuantity));
        dailyState.addRealizedPnl(tradePnl);

        log.info(
                "[BOLLINGER-HA] Stop hit for {} at {} (exit {}, reason {}). Exiting {} qty,"
                        + " PnL {}.",
                activePosition.getSymbol(),
                stopLoss,
                exitPrice,
                reason,
                exitQuantity,
                tradePnl);

        if (telegramService != null) {
            String msg =
                    String.format(
                            "🔴 *[BOLLINGER HA] POSITION CLOSED*\n"
                                    + "📉 *Symbol:* `%s`\n"
                                    + "🛑 *Exit Price:* ₹%s\n"
                                    + "💰 *Trade PnL:* ₹%s\n"
                                    + "📝 *Reason:* %s\n"
                                    + "🔢 *Trades Today:* %d / %d",
                            activePosition.getSymbol(),
                            exitPrice,
                            tradePnl,
                            reason,
                            dailyState.getTradeCount(),
                            properties.getMaxDailyTrades());
            telegramService.sendAlert(msg);
        }

        String stoppedOptionType = activePosition.getOptionType();
        activePosition = null;
        persistState();

        // Check if we can look for trade #2 on the opposite strike
        if (dailyState.getTradeCount() < properties.getMaxDailyTrades()) {
            String oppositeType = "CE".equalsIgnoreCase(stoppedOptionType) ? "PE" : "CE";
            BollingerHaSetupState oppositeSetup = setupStates.get(oppositeType);
            if (oppositeSetup != null) {
                // Setup states freeze while a position is open — clearing the opposite leg's stale
                // touch is what re-arms it; this is NOT an arm of the same leg.
                oppositeSetup.resetTouch();
                log.info(
                        "[BOLLINGER-HA] Stop-out recorded. Opposite contract {} re-armed and"
                                + " ready for the next valid setup.",
                        oppositeType);
            }
        } else {
            dailyState.setLocked(true);
            persistState();
            log.info(
                    "[BOLLINGER-HA] Reached maximum daily trades ({}). Locking strategy.",
                    properties.getMaxDailyTrades());
        }
        return true;
    }

    private Map<String, Object> baseMeta() {
        Map<String, Object> meta = new HashMap<>();
        meta.put("instrumentType", "OPTION");
        return meta;
    }

    /** Contract lot size from the registry, falling back to the NIFTY lot. */
    private int resolveLotSize() {
        int lotSize = StockFnoRegistry.getLotSize(properties.getUnderlying());
        return lotSize > 1 ? lotSize : NIFTY_LOT_SIZE;
    }

    /**
     * Half the position floored to whole lots.
     *
     * @return the exit quantity, or {@code 0} when the position cannot be split (fewer than two
     *     lots, or a floor that would leave nothing behind)
     */
    static int alignedPartialQuantity(int totalQuantity, int lotSize) {
        if (lotSize <= 1 || totalQuantity < 2 * lotSize) {
            return 0;
        }
        int aligned = (totalQuantity / 2 / lotSize) * lotSize;
        if (aligned < lotSize || aligned >= totalQuantity) {
            return 0;
        }
        return aligned;
    }

    /** Last traded close for the active contract, used to price an end-of-day square-off. */
    private BigDecimal lastCloseForActivePosition() {
        if (activePosition == null) {
            return null;
        }
        List<Candle> history = candleHistory.get(activePosition.getToken());
        if (history == null || history.isEmpty()) {
            return null;
        }
        return history.get(history.size() - 1).close();
    }

    private void evaluateEntrySignal(CompletedCandleEvent event) {
        String optionType = event.optionType().toUpperCase();
        BollingerHaSetupState setupState = setupStates.get(optionType);
        if (setupState == null) {
            return;
        }

        List<Candle> history = candleHistory.get(event.token());
        if (history == null || history.size() < properties.getBbPeriod()) {
            return;
        }

        // Calculate Heikin-Ashi and Bollinger Bands (20, 2)
        List<HeikinAshiCandle> haCandles = BollingerHaCalculator.calculateHeikinAshi(history);
        BollingerBandResult bb =
                BollingerHaCalculator.calculateBollingerBands(
                        haCandles, properties.getBbPeriod(), properties.getBbStdDev());

        if (bb == null || haCandles.isEmpty()) {
            return;
        }

        HeikinAshiCandle latestHa = haCandles.get(haCandles.size() - 1);

        // 1. Check if latest HA candle touches/pierces Lower Bollinger Band
        if (latestHa.low().compareTo(bb.lower()) <= 0) {
            setupState.recordLowerBandTouch();
            log.info(
                    "[BOLLINGER-HA] Lower band touched for {} (HA Low: {}, BB Lower: {})",
                    setupState.getSymbol(),
                    latestHa.low(),
                    bb.lower());
            if (!latestHa.isGreen()) {
                return;
            }
        } else {
            setupState.incrementCandleAge();
        }

        // 2. Check for Green HA Reversal Candle following Lower Band touch
        if (setupState.isTouchedLowerBand() && latestHa.isGreen()) {
            // Trend Filter Alignment (Nifty 20-EMA)
            if (properties.isTrendFilterEnabled()
                    && latestSpotPrice != null
                    && spotPriceHistory.size() >= properties.getTrendEmaPeriod()) {
                BigDecimal spotEma =
                        BollingerHaCalculator.calculateEma(
                                spotPriceHistory, properties.getTrendEmaPeriod());
                if (spotEma != null) {
                    if ("CE".equalsIgnoreCase(optionType)
                            && latestSpotPrice.compareTo(spotEma) < 0) {
                        log.info(
                                "[BOLLINGER-HA] ⛔ CE Signal Filtered Out: Nifty Spot ({}) < 20 EMA ({})",
                                latestSpotPrice,
                                spotEma);
                        return;
                    } else if ("PE".equalsIgnoreCase(optionType)
                            && latestSpotPrice.compareTo(spotEma) > 0) {
                        log.info(
                                "[BOLLINGER-HA] ⛔ PE Signal Filtered Out: Nifty Spot ({}) > 20 EMA ({})",
                                latestSpotPrice,
                                spotEma);
                        return;
                    }
                }
            }

            BigDecimal entryPrice =
                    latestHa.high()
                            .add(properties.getBufferPoints())
                            .setScale(2, RoundingMode.HALF_UP);
            BigDecimal stopLoss =
                    latestHa.low()
                            .subtract(properties.getBufferPoints())
                            .setScale(2, RoundingMode.HALF_UP);
            BigDecimal riskPoints = entryPrice.subtract(stopLoss);

            // Validate Risk Filter: minSlPoints <= riskPoints <= maxSlPoints
            if (riskPoints.compareTo(properties.getMinSlPoints()) < 0
                    || riskPoints.compareTo(properties.getMaxSlPoints()) > 0) {
                log.warn(
                        "[BOLLINGER-HA] Signal rejected due to risk filter: Risk {} pts (Min: {}, Max: {}) for {}",
                        riskPoints,
                        properties.getMinSlPoints(),
                        properties.getMaxSlPoints(),
                        setupState.getSymbol());
                setupState.resetTouch();
                return;
            }

            BigDecimal targetPrice =
                    entryPrice
                            .add(riskPoints.multiply(properties.getRiskRewardRatio()))
                            .setScale(2, RoundingMode.HALF_UP);

            int totalQuantity = calculatePositionQuantity(riskPoints);

            String positionId = "BHA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            BollingerHaPosition candidate =
                    new BollingerHaPosition(
                            positionId,
                            setupState.getSymbol(),
                            setupState.getToken(),
                            optionType,
                            entryPrice,
                            stopLoss,
                            targetPrice,
                            totalQuantity,
                            event.completedAt());

            Map<String, Object> meta = baseMeta();
            meta.put("optionType", optionType);
            meta.put("positionSide", "LONG");
            meta.put("strike", setupState.getStrike());
            meta.put("riskPoints", riskPoints);
            meta.put("targetPrice", targetPrice);
            meta.put("tradeNumber", dailyState.getTradeCount() + 1);

            TradeSignal signal =
                    TradeSignal.of(
                            STRATEGY_ID,
                            properties.getUnderlying(),
                            setupState.getSymbol(),
                            SignalAction.ENTRY_LONG,
                            entryPrice,
                            stopLoss,
                            targetPrice,
                            totalQuantity,
                            String.format(
                                    "1m Bollinger HA Bounce (Risk: %s pts, T1: %s)",
                                    riskPoints, targetPrice),
                            meta);

            // D6: publish before mutating — a rejected signal must not leave a phantom position
            // or burn one of the daily trade slots.
            if (!publishSignal(signal)) {
                return;
            }

            activePosition = candidate;
            dailyState.recordNewTrade(activePosition);
            setupState.resetTouch();
            persistState();

            log.info(
                    "[BOLLINGER-HA] 🚀 ENTRY SIGNAL GENERATED: {} | Entry={} | SL={} | T1={} | Qty={} (Trade #{})",
                    setupState.getSymbol(),
                    entryPrice,
                    stopLoss,
                    targetPrice,
                    totalQuantity,
                    dailyState.getTradeCount());

            if (telegramService != null) {
                String msg =
                        String.format(
                                "🚀 *[BOLLINGER HA] ENTRY SIGNAL TRIGGERED*\n"
                                        + "🎯 *Symbol:* `%s`\n"
                                        + "📈 *Action:* BUY (%d Qty)\n"
                                        + "💰 *Entry Price:* ₹%s\n"
                                        + "🛑 *Stop Loss:* ₹%s (Risk: %s pts)\n"
                                        + "🎯 *Target 1 (1:2):* ₹%s\n"
                                        + "📊 *Trade Number:* #%d / %d Today",
                                setupState.getSymbol(),
                                totalQuantity,
                                entryPrice,
                                stopLoss,
                                riskPoints,
                                targetPrice,
                                dailyState.getTradeCount(),
                                properties.getMaxDailyTrades());
                telegramService.sendAlert(msg);
            }
        }
    }

    public synchronized void updateSpotPrice(BigDecimal spotPrice) {
        if (spotPrice == null) {
            return;
        }
        this.latestSpotPrice = spotPrice;
        this.spotPriceHistory.add(spotPrice);
        // The touchline ticks far faster than the EMA period implies — trim so a long session
        // cannot grow the sample buffer without bound (and keep O(period) EMA cost stable).
        if (spotPriceHistory.size() > MAX_SPOT_HISTORY) {
            spotPriceHistory.subList(0, spotPriceHistory.size() - MAX_SPOT_HISTORY / 2).clear();
        }
    }

    private int calculatePositionQuantity(BigDecimal riskPoints) {
        int lotSize = resolveLotSize();
        if ("FIXED_AMOUNT".equalsIgnoreCase(properties.getSizingMode())) {
            BigDecimal riskPerLot = riskPoints.multiply(BigDecimal.valueOf(lotSize));
            if (riskPerLot.compareTo(BigDecimal.ZERO) <= 0) {
                return Math.max(1, properties.getDefaultLots()) * lotSize;
            }
            int calculatedLots =
                    properties
                            .getFixedRiskAmount()
                            .divide(riskPerLot, 0, RoundingMode.FLOOR)
                            .intValue();
            return Math.max(1, calculatedLots) * lotSize;
        }

        return Math.max(1, properties.getDefaultLots()) * lotSize;
    }

    private boolean canEnterNewTrade(Instant timestamp) {
        if (dailyState.isLocked() || dailyState.getTradeCount() >= properties.getMaxDailyTrades()) {
            return false;
        }

        ZonedDateTime istTime = (timestamp != null ? timestamp : Instant.now()).atZone(IST);
        LocalTime time = istTime.toLocalTime();

        LocalTime start = LocalTime.parse(properties.getEntryWindowStart());
        LocalTime cutoff = LocalTime.parse(properties.getEntryWindowCutoff());

        return !time.isBefore(start) && !time.isAfter(cutoff);
    }

    private void checkDailyRollover(Instant timestamp) {
        LocalDate today = (timestamp != null ? timestamp : Instant.now()).atZone(IST).toLocalDate();
        if (dailyState.getTradeDate() == null || dailyState.getTradeDate().equals(today)) {
            return;
        }

        // D7: never drop an open position silently — flatten it (and its PnL) before the reset.
        if (activePosition != null && !activePosition.isClosed()) {
            log.warn(
                    "[BOLLINGER-HA] Position {} still open at the day change — forcing an exit.",
                    activePosition.getSymbol());
            squareOffAll("Daily rollover — forced exit of open position");
        }
        if (dailyState.getRealizedPnl() != null
                && dailyState.getRealizedPnl().compareTo(BigDecimal.ZERO) != 0) {
            log.info(
                    "[BOLLINGER-HA] Archiving {} realized PnL for {}.",
                    dailyState.getRealizedPnl(),
                    dailyState.getTradeDate());
        }

        log.info("[BOLLINGER-HA] Daily rollover: Resetting daily state for {}", today);
        dailyState.reset(today);
        candleHistory.clear();
        // The EMA must not carry yesterday's spot path into today's trend filter.
        spotPriceHistory.clear();
        latestSpotPrice = null;
        setupStates.values().forEach(BollingerHaSetupState::resetTouch);
        activePosition = null;
        persistState();
    }

    /**
     * Squares off any open position at market, booked against the last known close of the contract.
     *
     * @param reason human-readable reason recorded on the position and the signal
     */
    public synchronized void squareOffAll(String reason) {
        if (activePosition == null || activePosition.isClosed()) {
            return;
        }

        String exitReason = reason != null ? reason : "Manual Square-off";
        BigDecimal stopLoss = activePosition.getStopLoss();
        BigDecimal lastClose = lastCloseForActivePosition();
        BigDecimal exitPrice = lastClose != null ? lastClose : stopLoss;
        int exitQuantity = activePosition.getRemainingQuantity();

        Map<String, Object> meta = baseMeta();
        meta.put("exitReason", exitReason);
        meta.put("positionSide", "LONG");
        meta.put("remainingQuantity", exitQuantity);

        TradeSignal sqOffSig =
                TradeSignal.of(
                        STRATEGY_ID,
                        properties.getUnderlying(),
                        activePosition.getSymbol(),
                        SignalAction.SQUARE_OFF,
                        exitPrice,
                        stopLoss,
                        activePosition.getTargetPrice(),
                        exitQuantity,
                        exitReason,
                        meta);
        // D6: publish first — if the bus rejects it, the position stays open and is retried.
        if (!publishSignal(sqOffSig)) {
            return;
        }

        activePosition.setClosed(true);
        activePosition.setExitPrice(exitPrice);
        activePosition.setExitTime(Instant.now());
        activePosition.setExitReason(exitReason);

        BigDecimal tradePnl =
                exitPrice
                        .subtract(activePosition.getEntryPrice())
                        .multiply(BigDecimal.valueOf(exitQuantity));
        dailyState.addRealizedPnl(tradePnl);

        log.info(
                "[BOLLINGER-HA] Square-off executed for {} at {} ({} qty). Reason: {}. PnL {}.",
                activePosition.getSymbol(),
                exitPrice,
                exitQuantity,
                exitReason,
                tradePnl);

        if (telegramService != null) {
            String msg =
                    String.format(
                            "⏱️ *[BOLLINGER HA] POSITION SQUARED OFF*\n"
                                    + "📈 *Symbol:* `%s`\n"
                                    + "🛑 *Exit Price:* ₹%s\n"
                                    + "💰 *Trade PnL:* ₹%s\n"
                                    + "📝 *Reason:* %s",
                            activePosition.getSymbol(), exitPrice, tradePnl, exitReason);
            telegramService.sendAlert(msg);
        }

        activePosition = null;
        persistState();
    }

    /** Live daily state — used by the scheduler summary and the controller's reset endpoint. */
    public synchronized BollingerHaDailyState getDailyState() {
        return dailyState;
    }

    /**
     * D10: REST-driven daily reset. Refuses while a position is open so a reset can never erase the
     * record of a trade that is still at risk.
     *
     * @return {@code true} when the day was reset, {@code false} when a position blocks it
     */
    public synchronized boolean resetDailyState() {
        if (activePosition != null && !activePosition.isClosed()) {
            log.warn(
                    "[BOLLINGER-HA] Reset refused — {} is still open.", activePosition.getSymbol());
            return false;
        }
        dailyState.reset(LocalDate.now(IST));
        persistState();
        log.info("[BOLLINGER-HA] Daily state reset via API.");
        return true;
    }

    /**
     * Defensive copy of the open position so callers reading /status can never mutate engine state.
     *
     * @return the live position, or {@code null} when flat (including after a
     *     closed-but-uncollected position)
     */
    public synchronized BollingerHaPosition getActivePosition() {
        if (activePosition == null || activePosition.isClosed()) {
            return null;
        }
        return restorePosition(snapshot(activePosition));
    }

    public synchronized Map<String, BollingerHaSetupState> getSetupStates() {
        return Collections.unmodifiableMap(setupStates);
    }

    // --- D4: JSON state persistence ---

    /**
     * Writes the current day's state to {@code stateFilePath}; failures are logged, never thrown.
     */
    private void persistState() {
        try {
            BollingerHaStateStore.State state = new BollingerHaStateStore.State();
            state.tradeDate =
                    dailyState.getTradeDate() != null
                            ? dailyState.getTradeDate()
                            : LocalDate.now(IST);
            state.savedAt = Instant.now();
            state.tradeCount = dailyState.getTradeCount();
            state.realizedPnl = dailyState.getRealizedPnl();
            state.locked = dailyState.isLocked();
            List<BollingerHaStateStore.PositionSnapshot> positions = new ArrayList<>();
            for (BollingerHaPosition position : dailyState.getDailyPositions()) {
                positions.add(snapshot(position));
            }
            state.positions = positions;
            state.openPosition =
                    activePosition != null && !activePosition.isClosed()
                            ? snapshot(activePosition)
                            : null;
            BollingerHaStateStore.save(stateFilePath, state);
        } catch (Exception e) {
            log.warn(
                    "[BOLLINGER-HA] Failed to persist state to {}: {}",
                    stateFilePath,
                    e.getMessage());
        }
    }

    private static BollingerHaStateStore.PositionSnapshot snapshot(BollingerHaPosition position) {
        BollingerHaStateStore.PositionSnapshot s = new BollingerHaStateStore.PositionSnapshot();
        s.positionId = position.getPositionId();
        s.symbol = position.getSymbol();
        s.token = position.getToken();
        s.optionType = position.getOptionType();
        s.entryPrice = position.getEntryPrice();
        s.stopLoss = position.getStopLoss();
        s.targetPrice = position.getTargetPrice();
        s.exitPrice = position.getExitPrice();
        s.totalQuantity = position.getTotalQuantity();
        s.remainingQuantity = position.getRemainingQuantity();
        s.entryTime = position.getEntryTime();
        s.exitTime = position.getExitTime();
        s.targetHit = position.isTargetHit();
        s.costSlActive = position.isCostSlActive();
        s.closed = position.isClosed();
        s.exitReason = position.getExitReason();
        return s;
    }

    /** Returns {@code null} when the snapshot is unusable rather than a half-built position. */
    private static BollingerHaPosition restorePosition(BollingerHaStateStore.PositionSnapshot s) {
        if (s == null || s.symbol == null || s.entryPrice == null || s.entryTime == null) {
            return null;
        }
        BollingerHaPosition position =
                new BollingerHaPosition(
                        s.positionId != null ? s.positionId : "BHA-RESTORED",
                        s.symbol,
                        s.token,
                        s.optionType,
                        s.entryPrice,
                        s.stopLoss,
                        s.targetPrice,
                        s.totalQuantity,
                        s.entryTime);
        position.setRemainingQuantity(
                s.remainingQuantity > 0 ? s.remainingQuantity : s.totalQuantity);
        position.setTargetHit(s.targetHit);
        position.setCostSlActive(s.costSlActive);
        position.setClosed(s.closed);
        position.setExitPrice(s.exitPrice);
        position.setExitTime(s.exitTime);
        position.setExitReason(s.exitReason);
        return position;
    }
}
