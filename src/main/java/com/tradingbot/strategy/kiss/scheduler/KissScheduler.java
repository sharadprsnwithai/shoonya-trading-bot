package com.tradingbot.strategy.kiss.scheduler;

import com.tradingbot.strategy.kiss.config.KissStrategyConfig;
import com.tradingbot.strategy.kiss.service.KissSwingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly cron scheduler for the KISS Strategy. Triggers regular hourly scans for NSE equities & MCX
 * commodities during trading hours.
 */
@Component
public class KissScheduler {

    private static final Logger log = LoggerFactory.getLogger(KissScheduler.class);

    private final KissStrategyConfig config;
    private final KissSwingService swingService;

    public KissScheduler(KissStrategyConfig config, KissSwingService swingService) {
        this.config = config;
        this.swingService = swingService;
    }

    /** Hourly scan at the 15-minute mark of every market hour (09:15 - 15:15 IST). */
    @Scheduled(
            cron = "${trading-bot.strategy.kiss.nse-hourly-cron:0 15 9-15 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void runNseHourlyScan() {
        if (!config.isEnabled()) return;
        log.info("Triggering Scheduled KISS Strategy NSE Hourly Scan...");
        swingService.scanAndExecute();
    }

    /** Commodity evening hourly scans (16:00 - 23:00 IST). */
    @Scheduled(
            cron = "${trading-bot.strategy.kiss.mcx-hourly-cron:0 0 16-23 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void runMcxEveningScan() {
        if (!config.isEnabled()) return;
        log.info("Triggering Scheduled KISS Strategy MCX Evening Scan...");
        swingService.scanAndExecute();
    }

    /** Friday 15:15 IST weekend risk check. */
    @Scheduled(cron = "0 15 15 ? * FRI", zone = "Asia/Kolkata")
    public void runFridayWeekendExitCheck() {
        if (!config.isEnabled() || !config.isEnableWeekendExit()) return;
        log.info("Triggering Scheduled KISS Friday Weekend Exit Check...");
        swingService.manageOpenPositions();
    }
}
