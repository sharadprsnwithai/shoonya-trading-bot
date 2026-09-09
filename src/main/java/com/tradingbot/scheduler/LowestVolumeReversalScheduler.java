package com.tradingbot.scheduler;

import com.tradingbot.service.LowestVolumeReversalService;
import java.time.LocalTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 5-Minute Candle Scheduler for the Lowest Volume Reversal & Continuation Strategy. Runs every 5
 * minutes on candle close (09:25 - 15:05 IST) to evaluate setups, arm orders, manage paper
 * positions, and execute the 15:00 hard square-off.
 */
@Service
public class LowestVolumeReversalScheduler {

    private static final Logger log = LoggerFactory.getLogger(LowestVolumeReversalScheduler.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LowestVolumeReversalService strategyService;

    @Value("${trading-bot.strategy.lowest-volume.scheduler-enabled:true}")
    private boolean schedulerEnabled = true;

    @Autowired
    public LowestVolumeReversalScheduler(LowestVolumeReversalService strategyService) {
        this.strategyService = strategyService;
    }

    /** Daily reset at 09:15 IST (market open) every Monday through Friday. */
    @Scheduled(cron = "0 15 9 ? * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledDailyReset() {
        log.info("[LVR-SCHEDULER] Market Open (09:15 IST). Executing daily strategy state reset.");
        strategyService.resetDaily();
    }

    /**
     * Morning Universe Scan at 09:25 IST every trading weekday. Identifies Top 10 Gainers & Losers
     * from the F&O universe, fixes this list for the day, seeds initial setups, and dispatches the
     * daily Telegram report once.
     */
    @Scheduled(
            cron = "${trading-bot.strategy.lowest-volume.scanner-cron:0 25 9 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void scheduledMorningUniverseScan() {
        if (!schedulerEnabled) {
            log.debug("[LVR-SCHEDULER] Scheduler is disabled in configuration.");
            return;
        }

        try {
            log.info(
                    "[LVR-SCHEDULER] 09:25 IST: Executing morning universe scan to fix daily watchlist...");
            strategyService.runMorningUniverseScan();
        } catch (Exception e) {
            log.error(
                    "[LVR-SCHEDULER] Exception during morning universe scan: {}",
                    e.getMessage(),
                    e);
        }
    }

    /** Runs every 5 minutes from 09:25 to 15:05 IST on trading weekdays. */
    @Scheduled(
            cron = "${trading-bot.strategy.lowest-volume.cron:0 */5 9-15 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void scheduledCandleCycle() {
        if (!schedulerEnabled) {
            log.debug("[LVR-SCHEDULER] Scheduler is disabled in configuration.");
            return;
        }

        LocalTime now = LocalTime.now(IST);
        // Do not trade before 09:25 or after 15:05
        if (now.isBefore(LocalTime.of(9, 25)) || now.isAfter(LocalTime.of(15, 5))) {
            return;
        }

        try {
            log.info("[LVR-SCHEDULER] Triggering 5-minute strategy cycle at {} IST...", now);
            strategyService.runCycle();
        } catch (Exception e) {
            log.error("[LVR-SCHEDULER] Exception during strategy cycle: {}", e.getMessage(), e);
        }
    }

    /**
     * 30-second live price check for armed triggers and open position SL/Target1. Runs every 30
     * seconds during market hours to catch breaches between 5-minute candle closes.
     */
    @Scheduled(fixedRate = 30000)
    public void scheduledLivePriceCheck() {
        if (!schedulerEnabled) {
            return;
        }

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(9, 25)) || now.isAfter(LocalTime.of(15, 0))) {
            return;
        }

        try {
            strategyService.evaluateLivePriceActions();
        } catch (Exception e) {
            log.error("[LVR-SCHEDULER] Exception during live price check: {}", e.getMessage(), e);
        }
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }
}
