package com.tradingbot.scheduler;

import com.tradingbot.execution.ExecutionManager;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.execution.ActiveSpreadPosition;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import com.tradingbot.strategy.impl.PivotSuperTrendOptionSellingStrategy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Production Scheduler for Pivot SuperTrend Option Selling Strategy.
 *
 * <p>Operations: 1. 09:15:00 IST (Mon-Fri): Pre-market / Day open state reset (clears daily trade
 * counters, pivots, positions). 2. 09:20:05 - 15:10:05 IST (Mon-Fri, every 5m): Evaluates completed
 * 5m candle, emits Telegram alert on signal, and optionally auto-executes trade via
 * ExecutionManager (Live or Paper). 3. 15:14:00 IST (Mon-Fri): Mandatory intraday auto square-off
 * for any open position before market close.
 */
@Component
public class PivotSuperTrendScheduler {

    private static final Logger log = LoggerFactory.getLogger(PivotSuperTrendScheduler.class);
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final LocalTime MANDATORY_SQUARE_OFF_TIME = LocalTime.of(15, 14);

    private final PivotSuperTrendOptionSellingStrategy strategy;
    private final ShoonyaMarketDataService marketDataService;
    private final ExecutionManager executionManager;

    @Value("${trading-bot.strategy.pivot-supertrend.scheduler-enabled:true}")
    private boolean schedulerEnabled = true;

    @Value("${trading-bot.strategy.pivot-supertrend.auto-execute:false}")
    private boolean autoExecute = false;

    @Value("${trading-bot.strategy.pivot-supertrend.lots:1}")
    private int lots = 1;

    @Value("${trading-bot.strategy.pivot-supertrend.lot-size:65}")
    private int lotSize = 65;

    @Value("${trading-bot.strategy.pivot-supertrend.lot-quantity:65}")
    private int lotQuantity = 65;

    @Value("${trading-bot.strategy.pivot-supertrend.buy-hedge:true}")
    private boolean buyHedge = true;

    private String activeTradeId = null;

    @Autowired
    public PivotSuperTrendScheduler(
            PivotSuperTrendOptionSellingStrategy strategy,
            ShoonyaMarketDataService marketDataService,
            ExecutionManager executionManager) {
        this.strategy = strategy;
        this.marketDataService = marketDataService;
        this.executionManager = executionManager;
    }

    /** Daily 09:15 IST Market Open Strategy Reset (Monday through Friday). */
    @Scheduled(cron = "0 15 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduleDailyReset() {
        if (!schedulerEnabled) {
            log.debug("[SCHEDULER] Daily 09:15 IST reset skipped (scheduler disabled)");
            return;
        }
        log.info("==================================================================");
        log.info("[SCHEDULER] 09:15 IST Market Open: Resetting Pivot SuperTrend daily state");
        log.info("==================================================================");
        strategy.onResetDaily();
        this.activeTradeId = null;
    }

    /**
     * Evaluates newly completed 5-minute candle at 5 seconds past every 5-minute mark. Active
     * window: 09:20:05 IST to 15:10:05 IST (Monday through Friday).
     */
    @Scheduled(cron = "5 */5 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduleCandleEvaluation() {
        if (!schedulerEnabled) {
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(IST);
        LocalTime time = now.toLocalTime();

        // Active evaluation window: 09:20:00 to 15:10:30 IST (square-off occurs at 15:14 IST)
        if (time.isBefore(LocalTime.of(9, 20)) || time.isAfter(LocalTime.of(15, 10, 30))) {
            return;
        }

        log.info("[SCHEDULER] Running scheduled 5m evaluation at {} IST...", time);
        evaluateCurrentCandle();
    }

    /**
     * Mandatory 15:14 IST Auto Square-Off (Monday through Friday). Automatically squares off any
     * active intraday position before market close.
     */
    @Scheduled(cron = "0 14 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduleMandatorySquareOff() {
        if (!schedulerEnabled) {
            return;
        }
        log.warn("==================================================================");
        log.warn("[SCHEDULER] 15:14 IST Mandatory Auto Square-Off triggered!");
        log.warn("==================================================================");
        triggerAutoSquareOff("Mandatory 15:14 IST Auto Square-Off");
    }

    /**
     * Evaluates current market candle from Shoonya and processes strategy signal and
     * auto-execution.
     */
    public synchronized TradeSignal evaluateCurrentCandle() {
        try {
            List<Candle> candles =
                    marketDataService.fetchHistoricalCandles("NFO", "68407", "NIFTY50", "5", 2);
            if (candles == null || candles.isEmpty()) {
                log.warn("[SCHEDULER] No 5m candles returned from Shoonya for NIFTY50");
                return TradeSignal.hold(strategy.getId(), "NIFTY50", "No market candles available");
            }

            Candle latest = candles.get(candles.size() - 1);
            List<Candle> history = candles.subList(0, candles.size() - 1);

            TradeSignal signal = strategy.onCandle(latest, history);
            log.info(
                    "[SCHEDULER] 5m Candle [Time: {} | Close: {}] -> Signal: {} | Reason: {}",
                    latest.timestamp().atZone(IST).toLocalTime(),
                    latest.close(),
                    signal.action(),
                    signal.reason());

            if (signal.isActionable()) {
                if (autoExecute) {
                    log.info(
                            "[SCHEDULER-EXEC] Auto-executing actionable signal: {}",
                            signal.action());
                    handleAutoExecution(signal);
                } else {
                    log.info(
                            "[SCHEDULER-ADVISORY] Actionable signal generated: {} | Action: {} (Advisory Mode active: Telegram alert sent, broker auto-order skipped)",
                            signal.symbol(),
                            signal.action());
                }
            }

            return signal;
        } catch (Exception e) {
            log.error("[SCHEDULER] Error evaluating candle: {}", e.getMessage(), e);
            return TradeSignal.hold(
                    strategy.getId(), "NIFTY50", "Evaluation error: " + e.getMessage());
        }
    }

    /** Forces immediate square-off of any active position. */
    public synchronized void triggerAutoSquareOff(String reason) {
        String effectiveReason = reason != null ? reason : "Mandatory 15:14 IST Auto Square-Off";
        if (strategy.isInPosition()) {
            log.info(
                    "[SCHEDULER] Squaring off active strategy position. Reason: {}",
                    effectiveReason);
            strategy.forceSquareOff(effectiveReason);
        }

        if (autoExecute && activeTradeId != null) {
            log.info("[SCHEDULER] Squaring off active ExecutionManager trade: {}", activeTradeId);
            executionManager.closeSpreadPosition(
                    activeTradeId, reason != null ? reason : "Mandatory 15:14 IST Auto Square-Off");
            this.activeTradeId = null;
        }
    }

    private void handleAutoExecution(TradeSignal signal) {
        if (signal.action() == SignalAction.SELL) {
            boolean isShortPe = signal.symbol().contains("PE");
            String optionType = isShortPe ? "PE" : "CE";
            BigDecimal strike = strategy.getAtmStrike();
            if (strike == null || strike.compareTo(BigDecimal.ZERO) <= 0) {
                strike =
                        signal.price()
                                .divide(BigDecimal.valueOf(50), 0, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.valueOf(50));
            }

            log.info(
                    "[SCHEDULER-EXEC] Auto-placing Short {} spread on strike {} (quantity: {}, hedge: {})",
                    optionType,
                    strike,
                    lotQuantity,
                    buyHedge);

            ActiveSpreadPosition pos =
                    executionManager.executeDirectionalOptionSelling(
                            strategy.getId(), "NIFTY", optionType, strike, lotQuantity, buyHedge);
            if (pos != null) {
                this.activeTradeId = pos.tradeId();
                log.info(
                        "[SCHEDULER-EXEC] Position opened successfully: Trade ID {}",
                        pos.tradeId());
            }
        } else if (signal.action() == SignalAction.EXIT_SHORT
                || signal.action() == SignalAction.EXIT_LONG
                || signal.action() == SignalAction.SQUARE_OFF) {
            if (activeTradeId != null) {
                log.info(
                        "[SCHEDULER-EXEC] Auto-closing spread position {} due to: {}",
                        activeTradeId,
                        signal.reason());
                executionManager.closeSpreadPosition(activeTradeId, signal.reason());
                this.activeTradeId = null;
            }
        }
    }

    // Getters and Setters
    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    public boolean isAutoExecute() {
        return autoExecute;
    }

    public void setAutoExecute(boolean autoExecute) {
        this.autoExecute = autoExecute;
    }

    public int getLots() {
        return lots;
    }

    public void setLots(int lots) {
        this.lots = lots;
        this.lotQuantity = lots * this.lotSize;
    }

    public int getLotSize() {
        return lotSize;
    }

    public void setLotSize(int lotSize) {
        this.lotSize = lotSize;
    }

    public int getLotQuantity() {
        return lotQuantity;
    }

    public void setLotQuantity(int lotQuantity) {
        this.lotQuantity = lotQuantity;
    }

    public boolean isBuyHedge() {
        return buyHedge;
    }

    public void setBuyHedge(boolean buyHedge) {
        this.buyHedge = buyHedge;
    }

    public String getActiveTradeId() {
        return activeTradeId;
    }

    public void setActiveTradeId(String activeTradeId) {
        this.activeTradeId = activeTradeId;
    }
}
