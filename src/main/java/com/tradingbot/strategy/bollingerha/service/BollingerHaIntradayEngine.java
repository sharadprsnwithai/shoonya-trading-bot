package com.tradingbot.strategy.bollingerha.service;

import com.tradingbot.bus.ReactiveSignalEventBus;
import com.tradingbot.model.Candle;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
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

    private final BollingerHaProperties properties;
    private final ReactiveSignalEventBus eventBus;
    private final com.tradingbot.telegram.TelegramService telegramService;

    private final Map<String, BollingerHaSetupState> setupStates = new ConcurrentHashMap<>();
    private final Map<String, List<Candle>> candleHistory = new ConcurrentHashMap<>();
    private final List<BigDecimal> spotPriceHistory =
            Collections.synchronizedList(new ArrayList<>());
    private BigDecimal latestSpotPrice;
    private BollingerHaDailyState dailyState;
    private BollingerHaPosition activePosition;

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
        this.dailyState = new BollingerHaDailyState(LocalDate.now(IST));
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

        String token = event.token();
        List<Candle> history =
                candleHistory.computeIfAbsent(
                        token, k -> Collections.synchronizedList(new ArrayList<>()));
        history.add(event.candle());

        // 1. Manage Active Position (Target 1 / Cost SL / Stop Loss)
        if (activePosition != null && !activePosition.isClosed()) {
            if (activePosition.getToken().equals(token)) {
                manageActivePosition(event.candle());
            }
            return; // Only 1 active trade allowed at a time
        }

        // 2. If no position, evaluate entry conditions
        if (canEnterNewTrade(event.completedAt())) {
            evaluateEntrySignal(event);
        }
    }

    private void manageActivePosition(Candle candle) {
        BigDecimal ltpHigh = candle.high();
        BigDecimal ltpLow = candle.low();

        // Check Target 1 hit (1:2 R:R) -> Partial 50% exit & move SL to Entry (CSL)
        if (!activePosition.isTargetHit()
                && ltpHigh.compareTo(activePosition.getTargetPrice()) >= 0) {
            int exitQty = Math.max(1, activePosition.getTotalQuantity() / 2);
            int remainingQty = activePosition.getTotalQuantity() - exitQty;

            activePosition.setTargetHit(true);
            activePosition.setCostSlActive(true);
            activePosition.setStopLoss(activePosition.getEntryPrice()); // Cost SL
            activePosition.setRemainingQuantity(remainingQty);

            log.info(
                    "[BOLLINGER-HA] Target 1 Hit for {} at {}! Booking 50% ({} qty) and setting Cost SL at {}",
                    activePosition.getSymbol(), ltpHigh, exitQty, activePosition.getEntryPrice());

            Map<String, Object> meta = new HashMap<>();
            meta.put("instrumentType", "OPTION");
            meta.put("targetPrice", activePosition.getTargetPrice());
            meta.put("stopLoss", activePosition.getEntryPrice());

            // Emit Partial Exit Signal
            TradeSignal partialExit =
                    TradeSignal.of(
                            STRATEGY_ID,
                            properties.getUnderlying(),
                            activePosition.getSymbol(),
                            SignalAction.PARTIAL_EXIT_LONG,
                            activePosition.getTargetPrice(),
                            activePosition.getEntryPrice(),
                            activePosition.getTargetPrice(),
                            exitQty,
                            "Target 1 (1:2) Reached - Book 50% Profit",
                            meta);
            eventBus.publish(partialExit);

            // Emit Stop Loss update signal
            TradeSignal updateSl =
                    TradeSignal.of(
                            STRATEGY_ID,
                            properties.getUnderlying(),
                            activePosition.getSymbol(),
                            SignalAction.UPDATE_STOP_LOSS,
                            activePosition.getEntryPrice(),
                            activePosition.getEntryPrice(),
                            activePosition.getTargetPrice(),
                            remainingQty,
                            "Trailed Stop Loss to Cost (Entry Price)",
                            meta);
            eventBus.publish(updateSl);

            if (telegramService != null) {
                BigDecimal partialPnl =
                        activePosition
                                .getTargetPrice()
                                .subtract(activePosition.getEntryPrice())
                                .multiply(BigDecimal.valueOf(exitQty));
                String msg =
                        String.format(
                                "🎯 *[BOLLINGER HA] TARGET 1 (1:2 R:R) HIT!*\n"
                                        + "📈 *Symbol:* `%s`\n"
                                        + "💰 *Booked 50%% Qty:* %d @ ₹%s (+₹%s PnL)\n"
                                        + "🛡️ *Cost SL Activated:* ₹%s on remaining %d Qty (Risk-Free Runner)",
                                activePosition.getSymbol(),
                                exitQty,
                                activePosition.getTargetPrice(),
                                partialPnl,
                                activePosition.getEntryPrice(),
                                remainingQty);
                telegramService.sendAlert(msg);
            }
        }

        // Check Stop Loss hit
        if (ltpLow.compareTo(activePosition.getStopLoss()) <= 0) {
            activePosition.setClosed(true);
            activePosition.setExitPrice(activePosition.getStopLoss());
            activePosition.setExitTime(candle.timestamp());
            activePosition.setExitReason(
                    activePosition.isCostSlActive() ? "Cost SL Hit" : "Initial SL Hit");

            int exitQty = activePosition.getRemainingQuantity();
            log.info(
                    "[BOLLINGER-HA] Stop Loss Hit for {} at {}! Exiting remaining {} qty. Reason: {}",
                    activePosition.getSymbol(),
                    activePosition.getStopLoss(),
                    exitQty,
                    activePosition.getExitReason());

            Map<String, Object> meta = new HashMap<>();
            meta.put("instrumentType", "OPTION");
            meta.put("exitReason", activePosition.getExitReason());

            TradeSignal exitSig =
                    TradeSignal.of(
                            STRATEGY_ID,
                            properties.getUnderlying(),
                            activePosition.getSymbol(),
                            SignalAction.EXIT_LONG,
                            activePosition.getStopLoss(),
                            activePosition.getStopLoss(),
                            activePosition.getTargetPrice(),
                            exitQty,
                            activePosition.getExitReason(),
                            meta);
            eventBus.publish(exitSig);

            // Calculate PnL
            BigDecimal tradePnl =
                    activePosition
                            .getExitPrice()
                            .subtract(activePosition.getEntryPrice())
                            .multiply(BigDecimal.valueOf(exitQty));
            dailyState.addRealizedPnl(tradePnl);

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
                                activePosition.getExitPrice(),
                                tradePnl,
                                activePosition.getExitReason(),
                                dailyState.getTradeCount(),
                                properties.getMaxDailyTrades());
                telegramService.sendAlert(msg);
            }

            String stoppedOptionType = activePosition.getOptionType();
            activePosition = null;

            // Check if we can look for trade #2 on opposite strike
            if (dailyState.getTradeCount() < properties.getMaxDailyTrades()) {
                String oppositeType = "CE".equalsIgnoreCase(stoppedOptionType) ? "PE" : "CE";
                BollingerHaSetupState oppositeSetup = setupStates.get(oppositeType);
                if (oppositeSetup != null) {
                    oppositeSetup.resetTouch();
                    log.info(
                            "[BOLLINGER-HA] Trade #1 stopped out. Armed for Trade #2 on opposite contract: {}",
                            oppositeType);
                }
            } else {
                dailyState.setLocked(true);
                log.info(
                        "[BOLLINGER-HA] Reached maximum daily trades ({}). Locking strategy.",
                        properties.getMaxDailyTrades());
            }
        }
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
            activePosition =
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

            dailyState.recordNewTrade(activePosition);
            setupState.resetTouch();

            log.info(
                    "[BOLLINGER-HA] 🚀 ENTRY SIGNAL GENERATED: {} | Entry={} | SL={} | T1={} | Qty={} (Trade #{})",
                    setupState.getSymbol(),
                    entryPrice,
                    stopLoss,
                    targetPrice,
                    totalQuantity,
                    dailyState.getTradeCount());

            Map<String, Object> meta = new HashMap<>();
            meta.put("instrumentType", "OPTION");
            meta.put("optionType", optionType);
            meta.put("strike", setupState.getStrike());
            meta.put("riskPoints", riskPoints);
            meta.put("targetPrice", targetPrice);
            meta.put("tradeNumber", dailyState.getTradeCount());

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

            eventBus.publish(signal);

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
        if (spotPrice != null) {
            this.latestSpotPrice = spotPrice;
            this.spotPriceHistory.add(spotPrice);
        }
    }

    private int calculatePositionQuantity(BigDecimal riskPoints) {
        if ("FIXED_AMOUNT".equalsIgnoreCase(properties.getSizingMode())) {
            BigDecimal riskPerLot = riskPoints.multiply(BigDecimal.valueOf(NIFTY_LOT_SIZE));
            if (riskPerLot.compareTo(BigDecimal.ZERO) <= 0) {
                return properties.getDefaultLots() * NIFTY_LOT_SIZE;
            }
            int calculatedLots =
                    properties
                            .getFixedRiskAmount()
                            .divide(riskPerLot, 0, RoundingMode.FLOOR)
                            .intValue();
            return Math.max(1, calculatedLots) * NIFTY_LOT_SIZE;
        }

        return Math.max(1, properties.getDefaultLots()) * NIFTY_LOT_SIZE;
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
        if (dailyState.getTradeDate() == null || !dailyState.getTradeDate().equals(today)) {
            log.info("[BOLLINGER-HA] Daily rollover: Resetting daily state for {}", today);
            dailyState.reset(today);
            candleHistory.clear();
            setupStates.values().forEach(BollingerHaSetupState::resetTouch);
            activePosition = null;
        }
    }

    /** Manually squares off any open position. */
    public synchronized void squareOffAll(String reason) {
        if (activePosition != null && !activePosition.isClosed()) {
            activePosition.setClosed(true);
            activePosition.setExitReason(reason != null ? reason : "Manual Square-off");
            log.info(
                    "[BOLLINGER-HA] Square-off executed for {}. Reason: {}",
                    activePosition.getSymbol(),
                    activePosition.getExitReason());

            Map<String, Object> meta = new HashMap<>();
            meta.put("instrumentType", "OPTION");
            meta.put("exitReason", activePosition.getExitReason());

            TradeSignal sqOffSig =
                    TradeSignal.of(
                            STRATEGY_ID,
                            properties.getUnderlying(),
                            activePosition.getSymbol(),
                            SignalAction.SQUARE_OFF,
                            activePosition.getStopLoss(),
                            activePosition.getStopLoss(),
                            activePosition.getTargetPrice(),
                            activePosition.getRemainingQuantity(),
                            activePosition.getExitReason(),
                            meta);
            eventBus.publish(sqOffSig);

            if (telegramService != null) {
                String msg =
                        String.format(
                                "⏱️ *[BOLLINGER HA] POSITION SQUARED OFF*\n"
                                        + "📈 *Symbol:* `%s`\n"
                                        + "📝 *Reason:* %s",
                                activePosition.getSymbol(), activePosition.getExitReason());
                telegramService.sendAlert(msg);
            }

            activePosition = null;
        }
    }

    public BollingerHaDailyState getDailyState() {
        return dailyState;
    }

    public BollingerHaPosition getActivePosition() {
        return activePosition;
    }

    public Map<String, BollingerHaSetupState> getSetupStates() {
        return Collections.unmodifiableMap(setupStates);
    }
}
