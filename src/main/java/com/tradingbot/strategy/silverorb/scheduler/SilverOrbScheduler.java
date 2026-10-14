package com.tradingbot.strategy.silverorb.scheduler;

import com.tradingbot.strategy.silverorb.service.SilverOrbService;
import java.time.LocalTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 5-minute candle scheduler for the Silver ORB evening-session strategy. Fires at second 20 of
 * every 5-minute mark from 18:00 to 23:55 IST on weekdays (the +20s offset lets the broker's 5m
 * candle land before we fetch it); the service itself gates on closed bars and strategy times.
 */
@Service
public class SilverOrbScheduler {

    private static final Logger log = LoggerFactory.getLogger(SilverOrbScheduler.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final SilverOrbService strategyService;
    private final boolean schedulerEnabled;

    /** Wires the scheduler with the strategy service and the enabled flag from configuration. */
    @Autowired
    public SilverOrbScheduler(
            SilverOrbService strategyService,
            @Value("${trading-bot.strategy.silver-orb.scheduler-enabled:true}")
                    boolean schedulerEnabled) {
        this.strategyService = strategyService;
        this.schedulerEnabled = schedulerEnabled;
    }

    /** One 5-minute evaluation cycle, guarded to the evening-session window. */
    @Scheduled(
            cron = "${trading-bot.strategy.silver-orb.cron:20 */5 18-23 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void scheduledCandleCycle() {
        triggerCycle(LocalTime.now(IST));
    }

    /**
     * Gated cycle trigger: skips when the scheduler is disabled or the clock sits outside the
     * evening-session window, and swallows broker exceptions so a bad cycle never kills the cron
     * thread.
     *
     * @param now current IST wall-clock time
     */
    void triggerCycle(LocalTime now) {
        if (!schedulerEnabled) {
            log.debug("[SILVER-ORB-SCHEDULER] Scheduler is disabled in configuration.");
            return;
        }
        // Evening session only: first useful bar close is 18:30 (ORB window), last is the 23:05
        // square-off bar; allow slack for late candles.
        if (now.isBefore(LocalTime.of(18, 0)) || now.isAfter(LocalTime.of(23, 40))) {
            return;
        }
        try {
            log.debug("[SILVER-ORB-SCHEDULER] Triggering cycle at {} IST...", now);
            strategyService.runCycle();
        } catch (Exception e) {
            log.error("[SILVER-ORB-SCHEDULER] Exception during cycle: {}", e.getMessage(), e);
        }
    }
}
