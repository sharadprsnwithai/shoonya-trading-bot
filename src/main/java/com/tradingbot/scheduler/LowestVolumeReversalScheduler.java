package com.tradingbot.scheduler;

import com.tradingbot.kite.auth.KiteAuthService;
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
 * minutes on candle close (09:25 - 11:05 IST) to evaluate setups, arm orders, manage paper
 * positions, and track trailing exits.
 */
@Service
public class LowestVolumeReversalScheduler {

    private static final Logger log = LoggerFactory.getLogger(LowestVolumeReversalScheduler.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LowestVolumeReversalService strategyService;
    private final KiteAuthService kiteAuthService;

    @Value("${trading-bot.strategy.lowest-volume.scheduler-enabled:true}")
    private volatile boolean schedulerEnabled = true;

    @Autowired
    public LowestVolumeReversalScheduler(
            LowestVolumeReversalService strategyService,
            @Autowired(required = false) KiteAuthService kiteAuthService) {
        this.strategyService = strategyService;
        this.kiteAuthService = kiteAuthService;
    }

    /** Daily reset at 09:15 IST (market open) every Monday through Friday. */
    @Scheduled(cron = "0 15 9 ? * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledDailyReset() {
        if (!schedulerEnabled) {
            log.debug("[LVR-SCHEDULER] Scheduler is disabled. Skipping scheduled daily reset.");
            return;
        }
        log.info("[LVR-SCHEDULER] Market Open (09:15 IST). Executing daily strategy state reset.");
        strategyService.resetDaily();
        if (kiteAuthService != null) {
            try {
                log.info(
                        "[LVR-SCHEDULER] Ensuring Zerodha Kite session is active and pre-warmed for today's market session...");
                kiteAuthService.ensureActiveSession();
            } catch (Exception e) {
                log.warn("[LVR-SCHEDULER] Kite session pre-warm notice: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 09:25:00 IST phase-2 candidate fetch: pulls NSE's live OI spurts feed and stages the ranked
     * underlyings BEFORE the 09:25:20 candle cycle runs the morning universe scan — the scan then
     * merges them into the watchlist (two-phase selection). Fetch failures log a WARN and the day
     * continues sector-only; the job never throws.
     */
    @Scheduled(
            cron = "${trading-bot.strategy.lowest-volume.nse-oi.cron:0 25 9 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void scheduledNseOiSpurtAugmentation() {
        if (!schedulerEnabled) {
            log.debug("[LVR-SCHEDULER] Scheduler is disabled in configuration.");
            return;
        }
        try {
            log.info("[LVR-SCHEDULER] Triggering 09:25 NSE OI spurts augmentation...");
            strategyService.runNseOiSpurtAugmentation();
        } catch (Exception e) {
            log.error(
                    "[LVR-SCHEDULER] Exception during NSE OI spurts augmentation: {}",
                    e.getMessage(),
                    e);
        }
    }

    /**
     * Runs every 5 minutes from 09:25:20 to 15:15:20 IST on trading weekdays. (20s offset for
     * broker candle aggregation latency). Automatically runs morning universe scan on first 09:25
     * cycle.
     */
    @Scheduled(
            cron = "${trading-bot.strategy.lowest-volume.cron:20 */5 9-15 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void scheduledCandleCycle() {
        if (!schedulerEnabled) {
            log.debug("[LVR-SCHEDULER] Scheduler is disabled in configuration.");
            return;
        }

        LocalTime now = LocalTime.now(IST);
        // Do not scan or evaluate outside market hours (09:25 - 15:05)
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

    /**
     * Dedicated 15:00 IST Market Hard Exit on trading weekdays to ensure all open intraday
     * paper/live positions are strictly squared off before market close.
     */
    @Scheduled(cron = "0 0 15 ? * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledHardExit() {
        runHardExit(LocalTime.of(15, 0));
    }

    /**
     * Retry window for the 15:00 square-off.
     *
     * <p>{@code executeHardExit} deliberately leaves a position open when it cannot price it, so a
     * single attempt at 15:00:00 was all that stood between an unquoted position and being left
     * open overnight at the broker. Retrying over the next four minutes re-quotes each one on a
     * fresh attempt; positions that closed already are skipped, so an already-clean book costs
     * nothing. All four attempts land well inside market hours (15:30 IST).
     */
    @Scheduled(cron = "0 1-4 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledHardExitRetry() {
        runHardExit(LocalTime.now(IST));
    }

    /**
     * Runs the market square-off and swallows failures so the scheduler thread survives.
     *
     * @param triggeredAt the local time the square-off was triggered, for the log line
     */
    private void runHardExit(LocalTime triggeredAt) {
        if (!schedulerEnabled) {
            return;
        }
        log.info(
                "[LVR-SCHEDULER] Market Hard Exit triggered at {}. Executing position closures.",
                triggeredAt);
        try {
            strategyService.executeHardExit(triggeredAt);
        } catch (Exception e) {
            log.error(
                    "[LVR-SCHEDULER] Exception during hard exit at {}: {}",
                    triggeredAt,
                    e.getMessage(),
                    e);
        }
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }
}
