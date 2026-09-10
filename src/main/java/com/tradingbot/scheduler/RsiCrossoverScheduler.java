package com.tradingbot.scheduler;

import com.tradingbot.service.RsiCrossoverStrategyService;
import java.time.LocalTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 5-Minute Scheduler for the NIFTY RSI Crossover Option Buying Strategy.
 *
 * <p>Executes every 5 minutes from 09:45:10 to 15:00:10 IST to evaluate entry/exit crossover conditions.
 * Executes mandatory auto-square-off at 15:05:10 IST and daily state reset at 09:15:00 IST.
 */
@Service
public class RsiCrossoverScheduler {

    private static final Logger log = LoggerFactory.getLogger(RsiCrossoverScheduler.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final RsiCrossoverStrategyService strategyService;

    @Value("${trading-bot.strategy.rsi-crossover.scheduler-enabled:true}")
    private boolean schedulerEnabled = true;

    @Autowired
    public RsiCrossoverScheduler(RsiCrossoverStrategyService strategyService) {
        this.strategyService = strategyService;
    }

    /** Daily reset at 09:15:00 IST (market open) Monday through Friday. */
    @Scheduled(cron = "0 15 9 ? * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledDailyReset() {
        log.info("[RSI-SCHEDULER] Market Open (09:15 IST). Executing daily strategy state reset.");
        strategyService.resetDaily();
    }

    /**
     * Runs every 5 minutes from 09:45:10 to 15:00:10 IST on trading weekdays.
     * Offset by +10s to ensure broker candle publishing latency is accounted for.
     */
    @Scheduled(
            cron = "${trading-bot.strategy.rsi-crossover.cron:10 */5 9-15 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void scheduledEvaluationCycle() {
        if (!schedulerEnabled) {
            log.debug("[RSI-SCHEDULER] Scheduler is disabled in configuration.");
            return;
        }

        LocalTime now = LocalTime.now(IST);
        // Only run within the active evaluation window: 09:45 to 15:00 IST
        if (now.isBefore(LocalTime.of(9, 45)) || now.isAfter(LocalTime.of(15, 0, 30))) {
            log.debug("[RSI-SCHEDULER] Outside active evaluation window (09:45 - 15:00 IST). Skipping cycle at {}", now);
            return;
        }

        try {
            log.info("[RSI-SCHEDULER] Executing 5-minute RSI Crossover evaluation cycle at {} IST...", now);
            strategyService.runCycle();
        } catch (Exception e) {
            log.error("[RSI-SCHEDULER] Exception during RSI Crossover evaluation cycle: {}", e.getMessage(), e);
        }
    }

    /** Mandatory EOD Square-Off at 15:05:10 IST. */
    @Scheduled(
            cron = "${trading-bot.strategy.rsi-crossover.square-off-cron:10 5 15 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void scheduledEodSquareOff() {
        if (!schedulerEnabled) {
            return;
        }

        try {
            log.info("[RSI-SCHEDULER] 15:05:10 IST: Triggering mandatory EOD square-off for any open positions...");
            strategyService.executeSquareOff("MANDATORY_EOD_SQUARE_OFF");
        } catch (Exception e) {
            log.error("[RSI-SCHEDULER] Exception during mandatory square-off: {}", e.getMessage(), e);
        }
    }

    public void setSchedulerEnabled(boolean enabled) {
        this.schedulerEnabled = enabled;
    }
}
