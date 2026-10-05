package com.tradingbot.strategy.driftvwap;

import com.tradingbot.strategy.driftvwap.config.DriftVwapProperties;
import java.time.LocalTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class DriftVwapOptionSellingScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(DriftVwapOptionSellingScheduler.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final DriftVwapOptionSellingService service;
    private final DriftVwapProperties properties;

    @Autowired
    public DriftVwapOptionSellingScheduler(
            DriftVwapOptionSellingService service, DriftVwapProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    /**
     * 5-minute strategy cycle: Runs every 5 minutes on candle close during market hours (09:25 -
     * 15:00 IST).
     */
    @Scheduled(
            cron = "${trading-bot.strategy.drift-vwap.cron:20 */5 9-15 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void scheduled5mCycle() {
        if (!properties.isEnabled()) return;
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(9, 20)) || now.isAfter(LocalTime.of(15, 15))) return;

        try {
            log.info("[DRIFT-VWAP-SCHEDULER] Triggering 5m cycle at {} IST...", now);
            service.runCycle();
        } catch (Exception e) {
            log.error("[DRIFT-VWAP-SCHEDULER] Exception in 5m cycle: {}", e.getMessage(), e);
        }
    }

    /** 30-second live check: Monitors target decay, SL expansion, and EOD square-off. */
    @Scheduled(fixedRate = 30000)
    public void scheduledLivePriceCheck() {
        if (!properties.isEnabled()) return;
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(10, 15)) || now.isAfter(LocalTime.of(15, 15))) return;

        try {
            service.evaluateLivePriceActions();
        } catch (Exception e) {
            log.error("[DRIFT-VWAP-SCHEDULER] Exception in live check: {}", e.getMessage(), e);
        }
    }

    /** Dedicated 15:10 IST EOD Hard Exit. */
    @Scheduled(cron = "0 10 15 ? * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledHardExit() {
        if (!properties.isEnabled()) return;
        log.info(
                "[DRIFT-VWAP-SCHEDULER] 15:10 IST Market Close reached. Executing position square-off.");
        try {
            service.executeHardExit();
        } catch (Exception e) {
            log.error("[DRIFT-VWAP-SCHEDULER] Exception in 15:10 hard exit: {}", e.getMessage(), e);
        }
    }
}
