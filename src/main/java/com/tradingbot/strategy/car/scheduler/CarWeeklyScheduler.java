package com.tradingbot.strategy.car.scheduler;

import com.tradingbot.strategy.car.CarWeeklyGttService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class CarWeeklyScheduler {

    private static final Logger log = LoggerFactory.getLogger(CarWeeklyScheduler.class);
    private final CarWeeklyGttService carWeeklyService;

    public CarWeeklyScheduler(CarWeeklyGttService carWeeklyService) {
        this.carWeeklyService = carWeeklyService;
    }

    /** Executes every Sunday at 10:00 AM IST. */
    @Scheduled(
            cron = "${trading-bot.strategy.car-weekly.sunday-cron:0 0 10 ? * SUN}",
            zone = "Asia/Kolkata")
    public void scheduledSundayRoutine() {
        log.info(
                "[CAR-SCHEDULER] Sunday 10:00 AM IST reached. Executing CAR Weekly GTT"
                        + " routine...");
        try {
            carWeeklyService.runSundayWeeklyRoutine();
        } catch (Exception e) {
            log.error("[CAR-SCHEDULER] Exception in Sunday routine: {}", e.getMessage(), e);
        }
    }
}
