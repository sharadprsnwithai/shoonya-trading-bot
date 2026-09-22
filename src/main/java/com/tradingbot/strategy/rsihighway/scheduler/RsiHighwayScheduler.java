package com.tradingbot.strategy.rsihighway.scheduler;

import com.tradingbot.strategy.rsihighway.config.RsiHighwayConfig;
import com.tradingbot.strategy.rsihighway.service.RsiHighwaySwingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * POSIX Cron Schedulers for the RSI Highway Multi-Timeframe Strategy. Triggers the 15:00 IST EOD
 * scan & execution and the 09:30 IST morning plunge health check.
 */
@Component
@ConditionalOnProperty(
        name = "trading-bot.strategy.rsi-highway.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class RsiHighwayScheduler {

    private static final Logger log = LoggerFactory.getLogger(RsiHighwayScheduler.class);

    private final RsiHighwaySwingService swingService;
    private final RsiHighwayConfig config;

    public RsiHighwayScheduler(RsiHighwaySwingService swingService, RsiHighwayConfig config) {
        this.swingService = swingService;
        this.config = config;
    }

    /** Daily 15:00 IST EOD scan & execution window on Indian trading days (Mon-Fri). */
    @Scheduled(
            cron = "${trading-bot.strategy.rsi-highway.eod-scan-cron:0 0 15 * * MON-FRI}",
            zone = "Asia/Kolkata")
    public void runDailyEodScan() {
        log.info("[RSI-HIGHWAY SCHEDULER] Triggered 15:00 IST EOD Strategy Scan");
        try {
            swingService.evaluateEodScan();
        } catch (Exception e) {
            log.error("[RSI-HIGHWAY SCHEDULER] Error during EOD Strategy Scan", e);
        }
    }

    /** Morning 09:30 IST plunge health check on Indian trading days (Mon-Fri). */
    @Scheduled(
            cron = "${trading-bot.strategy.rsi-highway.morning-check-cron:0 30 9 * * MON-FRI}",
            zone = "Asia/Kolkata")
    public void runMorningPlungeCheck() {
        log.info("[RSI-HIGHWAY SCHEDULER] Triggered 09:30 IST Morning Plunge Check");
        try {
            swingService.evaluateMorningPlungeCheck();
        } catch (Exception e) {
            log.error("[RSI-HIGHWAY SCHEDULER] Error during Morning Plunge Check", e);
        }
    }
}
