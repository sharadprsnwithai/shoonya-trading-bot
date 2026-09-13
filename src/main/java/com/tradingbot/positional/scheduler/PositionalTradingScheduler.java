package com.tradingbot.positional.scheduler;

import com.tradingbot.positional.config.PositionalStrategyConfig;
import com.tradingbot.positional.service.BollingerHaPositionalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler for Positional Bollinger Band & Heikin-Ashi Strategy. Runs at 3:00 PM IST on Indian
 * trading days (Monday - Friday).
 */
@Component
@ConditionalOnProperty(
        name = "trading-bot.positional.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PositionalTradingScheduler {

    private static final Logger log = LoggerFactory.getLogger(PositionalTradingScheduler.class);

    private final BollingerHaPositionalService positionalService;
    private final PositionalStrategyConfig config;

    public PositionalTradingScheduler(
            BollingerHaPositionalService positionalService, PositionalStrategyConfig config) {
        this.positionalService = positionalService;
        this.config = config;
    }

    /** Daily trigger at 3:00 PM IST (cron: "0 0 15 * * MON-FRI", Zone: Asia/Kolkata). */
    @Scheduled(cron = "${trading-bot.positional.cron:0 0 15 * * MON-FRI}", zone = "Asia/Kolkata")
    public void runDailyEvaluation() {
        log.info(
                "[POSITIONAL SCHEDULER] Triggered 3:00 PM IST Daily Scan for {}",
                config.getSymbol());
        try {
            positionalService.scanAndEvaluate();
        } catch (Exception e) {
            log.error("[POSITIONAL SCHEDULER] Exception during daily evaluation", e);
        }
    }
}
