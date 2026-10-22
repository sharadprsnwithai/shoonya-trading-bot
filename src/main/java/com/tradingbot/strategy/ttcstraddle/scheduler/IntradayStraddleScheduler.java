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

/** Clock-Driven Scheduler for the 10:00 AM Intraday Straddle Strategy with 30% Stop Loss. */
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

    /** 09:45:00 IST: Buy 20-strikes OTM protection wings. */
    @Scheduled(cron = "0 45 9 ? * MON-THU", zone = "Asia/Kolkata")
    public void scheduledHedgeEntry() {
        if (!properties.isSchedulerEnabled() || !properties.isEnabled()) {
            return;
        }
        log.info("[STRADDLE-SCHEDULER] 09:45 IST: Triggering 20-OTM protection hedge entry.");
        straddleService.executeHedgeWingEntry(LocalTime.now(IST));
    }

    /** 10:00:00 IST: Sell ATM Straddle with 30% Stop-Loss. */
    @Scheduled(cron = "0 0 10 ? * MON-THU", zone = "Asia/Kolkata")
    public void scheduledStraddleEntry() {
        if (!properties.isSchedulerEnabled() || !properties.isEnabled()) {
            return;
        }
        log.info("[STRADDLE-SCHEDULER] 10:00 IST: Triggering 10:00 AM ATM straddle entry.");
        straddleService.executeStraddleEntry(LocalTime.now(IST));
    }

    /** 15:00:00 IST: Square off surviving short leg and hedges (flat EOD). */
    @Scheduled(cron = "0 0 15 ? * MON-THU", zone = "Asia/Kolkata")
    public void scheduledFinalHardExit() {
        if (!properties.isSchedulerEnabled() || !properties.isEnabled()) {
            return;
        }
        log.info("[STRADDLE-SCHEDULER] 15:00 IST: Final EOD Hard Square-Off triggered.");
        straddleService.executeHardSquareOff("15:00 IST Scheduled EOD Square-Off");
    }

    /** 10-second ticker loop to monitor 30% Stop Loss breaches between 10:00 and 15:00. */
    @Scheduled(fixedRate = 10000)
    public void scheduledStopLossCheck() {
        if (!properties.isSchedulerEnabled() || !properties.isEnabled()) {
            return;
        }
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(10, 0)) || now.isAfter(LocalTime.of(15, 0))) {
            return;
        }
        try {
            straddleService.evaluateStopLossBreaches(now);
        } catch (Exception e) {
            log.error("[STRADDLE-SCHEDULER] Error during stop-loss check: {}", e.getMessage(), e);
        }
    }
}
