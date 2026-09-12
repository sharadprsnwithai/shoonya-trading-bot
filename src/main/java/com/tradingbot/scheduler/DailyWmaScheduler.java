package com.tradingbot.scheduler;

import com.tradingbot.service.DailyWmaStrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Scheduled Execution Engine for the 19-Period Daily WMA Positional Option Strategy.
 *
 * <p>Executes daily trend analysis at 09:30:10 IST, continuous 5-minute stop loss monitoring,
 * and mandatory square-off on expiry days at 15:15:10 IST.
 */
@Service
public class DailyWmaScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyWmaScheduler.class);

    private final DailyWmaStrategyService strategyService;
    private final boolean schedulerEnabled;

    @Autowired
    public DailyWmaScheduler(
            DailyWmaStrategyService strategyService,
            @Value("${trading-bot.strategy.daily-wma.scheduler-enabled:true}") boolean schedulerEnabled) {
        this.strategyService = strategyService;
        this.schedulerEnabled = schedulerEnabled;
    }

    /**
     * Daily trend evaluation cycle at 09:30:10 IST on weekdays.
     */
    @Scheduled(
            cron = "${trading-bot.strategy.daily-wma.eval-cron:10 30 9 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void scheduledDailyEvaluationCycle() {
        if (!schedulerEnabled) {
            log.debug("[DAILY-WMA-SCHEDULER] Daily evaluation skipped (scheduler disabled).");
            return;
        }

        try {
            log.info("[DAILY-WMA-SCHEDULER] 09:30:10 IST: Triggering Daily 19-WMA evaluation cycle...");
            strategyService.evaluateDailyCycle();
        } catch (Exception e) {
            log.error("[DAILY-WMA-SCHEDULER] Error during daily evaluation cycle: {}", e.getMessage(), e);
        }
    }

    /**
     * Intraday Stop Loss monitor executed every 5 minutes during market hours.
     */
    @Scheduled(
            cron = "${trading-bot.strategy.daily-wma.sl-cron:10 */5 9-15 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void scheduledStopLossMonitor() {
        if (!schedulerEnabled) {
            return;
        }

        try {
            strategyService.monitorStopLoss();
        } catch (Exception e) {
            log.error("[DAILY-WMA-SCHEDULER] Error during stop-loss monitoring: {}", e.getMessage(), e);
        }
    }

    /**
     * Mandatory Expiry Day Square-Off executed at 15:15:10 IST on weekdays.
     */
    @Scheduled(
            cron = "${trading-bot.strategy.daily-wma.square-off-cron:10 15 15 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void scheduledExpirySquareOff() {
        if (!schedulerEnabled) {
            return;
        }

        try {
            log.info("[DAILY-WMA-SCHEDULER] 15:15:10 IST: Checking expiry square-off...");
            strategyService.executeExpirySquareOff("EXPIRY_DAY_SQUARE_OFF");
        } catch (Exception e) {
            log.error("[DAILY-WMA-SCHEDULER] Error during expiry square-off: {}", e.getMessage(), e);
        }
    }
}
