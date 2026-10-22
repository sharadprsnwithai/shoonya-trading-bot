package com.tradingbot.strategy.ttcstraddle.scheduler;

import com.tradingbot.strategy.ttcstraddle.config.IntradayStraddleProperties;
import com.tradingbot.strategy.ttcstraddle.service.IntradayStraddleService;
import java.time.LocalTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Clock-Driven Scheduler for the 1 & 0 NS 916 TTC Double-Session Intraday Straddle Strategy. */
@Service
public class IntradayStraddleScheduler {

    private static final Logger log = LoggerFactory.getLogger(IntradayStraddleScheduler.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final IntradayStraddleService straddleService;
    private final IntradayStraddleProperties properties;

    @Autowired
    public IntradayStraddleScheduler(
            IntradayStraddleService straddleService, IntradayStraddleProperties properties) {
        this.straddleService = straddleService;
        this.properties = properties;
    }

    /** 09:15 IST Market Open Daily Reset. */
    @Scheduled(cron = "0 15 9 ? * MON-THU", zone = "Asia/Kolkata")
    public void scheduledDailyReset() {
        if (!properties.isSchedulerEnabled() || !properties.isEnabled()) {
            return;
        }
        log.info("[STRADDLE-SCHEDULER] 09:15 Market Open. Executing daily straddle state reset.");
        straddleService.resetDaily();
    }

    /** 09:16:00 IST: Buy protection wings. */
    @Scheduled(cron = "0 16 9 ? * MON-THU", zone = "Asia/Kolkata")
    public void scheduledHedgeEntry() {
        if (!properties.isSchedulerEnabled() || !properties.isEnabled()) {
            return;
        }
        log.info("[STRADDLE-SCHEDULER] 09:16 IST: Triggering protection hedge entry.");
        straddleService.executeHedgeWingEntry(LocalTime.now(IST));
    }

    /** 09:22:00 IST: Sell morning ATM straddle with 50% SL. */
    @Scheduled(cron = "0 22 9 ? * MON-THU", zone = "Asia/Kolkata")
    public void scheduledMorningStraddleEntry() {
        if (!properties.isSchedulerEnabled() || !properties.isEnabled()) {
            return;
        }
        log.info("[STRADDLE-SCHEDULER] 09:22 IST: Triggering morning ATM straddle entry.");
        straddleService.executeMorningStraddleEntry(LocalTime.now(IST));
    }

    /** 10:16 IST (Nifty) / 10:30 IST (Sensex): Square off morning short legs. */
    @Scheduled(cron = "0 16,30 10 ? * MON-THU", zone = "Asia/Kolkata")
    public void scheduledMorningStraddleExit() {
        if (!properties.isSchedulerEnabled() || !properties.isEnabled()) {
            return;
        }
        LocalTime now = LocalTime.now(IST);
        log.info("[STRADDLE-SCHEDULER] {} IST: Triggering morning short straddle square-off.", now);
        straddleService.executeMorningStraddleExit(now);
    }

    /** 14:17:00 IST: Sell fresh afternoon ATM straddle with 50% SL. */
    @Scheduled(cron = "0 17 14 ? * MON-THU", zone = "Asia/Kolkata")
    public void scheduledAfternoonStraddleEntry() {
        if (!properties.isSchedulerEnabled() || !properties.isEnabled()) {
            return;
        }
        log.info("[STRADDLE-SCHEDULER] 14:17 IST: Triggering afternoon ATM straddle entry.");
        straddleService.executeAfternoonStraddleEntry(LocalTime.now(IST));
    }

    /** 15:35:00 IST: Final hard square-off of all 4 open legs (flat EOD). */
    @Scheduled(cron = "0 35 15 ? * MON-THU", zone = "Asia/Kolkata")
    public void scheduledFinalHardExit() {
        if (!properties.isSchedulerEnabled() || !properties.isEnabled()) {
            return;
        }
        log.info("[STRADDLE-SCHEDULER] 15:35 IST: Final EOD Hard Square-Off triggered.");
        straddleService.executeHardSquareOff("15:35 IST Scheduled EOD Square-Off");
    }

    /** 10-second ticker loop to monitor 50% Stop Loss breaches. */
    @Scheduled(fixedRate = 10000)
    public void scheduledStopLossCheck() {
        if (!properties.isSchedulerEnabled() || !properties.isEnabled()) {
            return;
        }
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(9, 22)) || now.isAfter(LocalTime.of(15, 35))) {
            return;
        }
        try {
            straddleService.evaluateStopLossBreaches(now);
        } catch (Exception e) {
            log.error("[STRADDLE-SCHEDULER] Error during stop-loss check: {}", e.getMessage(), e);
        }
    }
}
