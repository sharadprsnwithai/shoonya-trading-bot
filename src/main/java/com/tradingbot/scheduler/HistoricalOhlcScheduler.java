package com.tradingbot.scheduler;

import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.marketdata.repository.SqliteBackupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job to sync historical Daily, Weekly, and Monthly OHLC data from Yahoo Finance at 08:00
 * AM IST on trading days (Monday - Friday) and take automated daily SQLite backups.
 */
@Component
@ConditionalOnProperty(
        name = "trading-bot.ohlc.scheduler-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class HistoricalOhlcScheduler {

    private static final Logger log = LoggerFactory.getLogger(HistoricalOhlcScheduler.class);

    private final HistoricalOhlcCacheService cacheService;
    private final SqliteBackupService backupService;

    @Autowired
    public HistoricalOhlcScheduler(
            HistoricalOhlcCacheService cacheService,
            @Autowired(required = false) SqliteBackupService backupService) {
        this.cacheService = cacheService;
        this.backupService = backupService;
    }

    /**
     * Runs at 08:00 AM IST Monday through Friday to ensure the local OHLC cache is fully fresh
     * before market open.
     */
    @Scheduled(
            cron = "${trading-bot.ohlc.morning-sync-cron:0 0 8 ? * MON-FRI}",
            zone = "Asia/Kolkata")
    public void runMorningOhlcSync() {
        log.info("[OHLC-SCHEDULER] Triggering morning 08:00 AM IST Yahoo Finance OHLC sync...");
        try {
            int count = cacheService.syncAll(false);
            log.info(
                    "[OHLC-SCHEDULER] Morning OHLC sync finished. Total cached symbols: {}", count);
            if (backupService != null) {
                backupService.createDailyBackup();
            }
        } catch (Exception e) {
            log.error("[OHLC-SCHEDULER] Error during morning OHLC sync: {}", e.getMessage(), e);
        }
    }

    /**
     * Runs daily at 23:00 IST to create an atomic defragmented backup of the active SQLite
     * database.
     */
    @Scheduled(cron = "${trading-bot.ohlc.daily-backup-cron:0 0 23 * * ?}", zone = "Asia/Kolkata")
    public void runDailyDatabaseBackup() {
        log.info("[OHLC-SCHEDULER] Triggering scheduled daily SQLite backup (23:00 IST)...");
        if (backupService != null) {
            backupService.createDailyBackup();
        }
    }
}
