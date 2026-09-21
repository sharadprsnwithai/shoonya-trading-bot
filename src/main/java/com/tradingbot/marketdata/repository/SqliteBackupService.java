package com.tradingbot.marketdata.repository;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Manages atomic SQLite daily backups, retention rotation, database integrity verification, and
 * automated fallback/restoration in case of database corruption.
 */
@Service
public class SqliteBackupService {

    private static final Logger log = LoggerFactory.getLogger(SqliteBackupService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter BACKUP_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final String dbPath;
    private final String backupDir;
    private final int retentionDays;
    private Clock clock = Clock.system(IST);

    @Autowired
    public SqliteBackupService(
            @Value("${trading-bot.ohlc.sqlite-db-path:data/trading_bot.db}") String dbPath,
            @Value("${trading-bot.ohlc.backup-dir:data/backups}") String backupDir,
            @Value("${trading-bot.ohlc.backup-retention-days:7}") int retentionDays) {
        this.dbPath = dbPath;
        this.backupDir = backupDir;
        this.retentionDays = retentionDays;
    }

    @PostConstruct
    public void init() {
        ensureBackupDirExists();
        checkAndRecoverIfCorrupted();
    }

    /** Ensures the backup directory exists on disk. */
    public void ensureBackupDirExists() {
        File dir = new File(backupDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    /**
     * Checks the integrity of the active database. If corrupted, attempts automated recovery from
     * the latest valid daily backup.
     */
    public synchronized boolean checkAndRecoverIfCorrupted() {
        File dbFile = new File(dbPath);
        if (!dbFile.exists() || dbFile.length() == 0) {
            log.info("[SQLITE-BACKUP] Main database file {} does not exist yet.", dbPath);
            return true;
        }

        if (verifyIntegrity(dbFile)) {
            log.debug("[SQLITE-BACKUP] Database integrity check passed for {}", dbPath);
            return true;
        }

        log.error(
                "[SQLITE-BACKUP] CRITICAL: SQLite database at {} failed integrity check! Initiating automated recovery...",
                dbPath);

        // Quarantine corrupted file
        File quarantineFile =
                new File(
                        dbFile.getParentFile(),
                        dbFile.getName() + ".corrupt." + System.currentTimeMillis());
        try {
            Files.move(
                    dbFile.toPath(), quarantineFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log.warn(
                    "[SQLITE-BACKUP] Quarantined corrupted database to {}",
                    quarantineFile.getAbsolutePath());
        } catch (IOException e) {
            log.error(
                    "[SQLITE-BACKUP] Failed to quarantine corrupt database file: {}",
                    e.getMessage());
        }

        // Attempt restore from newest valid backup
        boolean restored = restoreFromLatestBackup();
        if (restored) {
            log.info(
                    "[SQLITE-BACKUP] Successfully restored database from backup after corruption detection.");
            return true;
        } else {
            log.error(
                    "[SQLITE-BACKUP] Failed to restore from backup! Starting with a clean empty database.");
            return false;
        }
    }

    /**
     * Verifies SQLite file integrity using PRAGMA quick_check.
     *
     * @param targetDb Target SQLite file
     * @return true if database is healthy, false otherwise
     */
    public boolean verifyIntegrity(File targetDb) {
        if (targetDb == null || !targetDb.exists() || targetDb.length() == 0) {
            return false;
        }
        String url = "jdbc:sqlite:" + targetDb.getAbsolutePath().replace('\\', '/');
        try (Connection conn = DriverManager.getConnection(url);
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("PRAGMA quick_check;")) {
            if (rs.next()) {
                String result = rs.getString(1);
                return "ok".equalsIgnoreCase(result);
            }
        } catch (Exception e) {
            log.warn(
                    "[SQLITE-BACKUP] Integrity check failed on {}: {}",
                    targetDb.getName(),
                    e.getMessage());
            return false;
        }
        return false;
    }

    /**
     * Creates an atomic online backup of the active SQLite database using VACUUM INTO.
     *
     * @return Optional containing the created backup file, or empty if backup failed
     */
    public synchronized Optional<File> createDailyBackup() {
        File dbFile = new File(dbPath);
        if (!dbFile.exists() || dbFile.length() == 0) {
            log.info(
                    "[SQLITE-BACKUP] Skipping backup: main database {} is empty or missing.",
                    dbPath);
            return Optional.empty();
        }

        ensureBackupDirExists();
        String dateSuffix = LocalDate.now(clock).format(BACKUP_DATE_FMT);
        File targetFile = new File(backupDir, "trading_bot_backup_" + dateSuffix + ".db");
        File tempBackupFile =
                new File(
                        backupDir,
                        "trading_bot_backup_"
                                + dateSuffix
                                + ".tmp."
                                + System.currentTimeMillis()
                                + ".db");

        // Execute atomic SQLite VACUUM INTO
        String sourceUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath().replace('\\', '/');
        String vacuumPath = tempBackupFile.getAbsolutePath().replace('\\', '/');

        try (Connection conn = DriverManager.getConnection(sourceUrl);
                Statement stmt = conn.createStatement()) {
            // Checkpoint WAL first to flush pending transactions
            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE);");
            stmt.execute("VACUUM INTO '" + vacuumPath + "';");
        } catch (SQLException e) {
            log.error(
                    "[SQLITE-BACKUP] Error executing VACUUM INTO for backup: {}",
                    e.getMessage(),
                    e);
            if (tempBackupFile.exists()) {
                tempBackupFile.delete();
            }
            return Optional.empty();
        }

        // Verify backup integrity before promoting
        if (!verifyIntegrity(tempBackupFile)) {
            log.error(
                    "[SQLITE-BACKUP] Generated backup {} failed integrity check!",
                    tempBackupFile.getName());
            tempBackupFile.delete();
            return Optional.empty();
        }

        try {
            Files.move(
                    tempBackupFile.toPath(),
                    targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            log.info(
                    "[SQLITE-BACKUP] Successfully created daily SQLite backup at {} (size: {} KB)",
                    targetFile.getAbsolutePath(),
                    targetFile.length() / 1024);

            // Also maintain latest symlink/copy for convenience
            File latestCopy = new File(backupDir, "trading_bot_backup_latest.db");
            Files.copy(
                    targetFile.toPath(), latestCopy.toPath(), StandardCopyOption.REPLACE_EXISTING);

            // Prune old backups exceeding retention limit
            pruneOldBackups();
            return Optional.of(targetFile);
        } catch (IOException e) {
            log.error("[SQLITE-BACKUP] Failed to promote temp backup file: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Restores the main database from the latest valid backup file.
     *
     * @return true if restoration succeeded, false otherwise
     */
    public synchronized boolean restoreFromLatestBackup() {
        List<File> backups = getAvailableBackups();
        for (File backup : backups) {
            if (backup.getName().contains("latest")) {
                continue; // Prefer dated backups
            }
            if (verifyIntegrity(backup)) {
                log.info(
                        "[SQLITE-BACKUP] Restoring active database from valid backup: {}",
                        backup.getAbsolutePath());
                try {
                    File mainDb = new File(dbPath);
                    if (mainDb.getParentFile() != null && !mainDb.getParentFile().exists()) {
                        mainDb.getParentFile().mkdirs();
                    }
                    Files.copy(
                            backup.toPath(), mainDb.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    log.info("[SQLITE-BACKUP] Restoration complete from {}", backup.getName());
                    return true;
                } catch (IOException e) {
                    log.error(
                            "[SQLITE-BACKUP] Failed copying backup to {}: {}",
                            dbPath,
                            e.getMessage());
                }
            } else {
                log.warn(
                        "[SQLITE-BACKUP] Skipping corrupt/invalid backup file {}",
                        backup.getName());
            }
        }
        return false;
    }

    /** Returns all available backup files sorted from newest to oldest. */
    public List<File> getAvailableBackups() {
        File dir = new File(backupDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return Collections.emptyList();
        }

        File[] files =
                dir.listFiles(
                        (d, name) ->
                                name.startsWith("trading_bot_backup_") && name.endsWith(".db"));
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }

        return Arrays.stream(files)
                .sorted(Comparator.comparingLong(File::lastModified).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Deletes backups older than the configured retention days.
     *
     * @return Count of deleted backup files
     */
    public int pruneOldBackups() {
        List<File> backups =
                getAvailableBackups().stream()
                        .filter(f -> !f.getName().contains("latest"))
                        .collect(Collectors.toList());

        if (backups.size() <= retentionDays) {
            return 0;
        }

        int deletedCount = 0;
        for (int i = retentionDays; i < backups.size(); i++) {
            File oldBackup = backups.get(i);
            if (oldBackup.delete()) {
                deletedCount++;
                log.info("[SQLITE-BACKUP] Pruned expired backup: {}", oldBackup.getName());
            }
        }
        return deletedCount;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }

    public String getDbPath() {
        return dbPath;
    }

    public String getBackupDir() {
        return backupDir;
    }

    public int getRetentionDays() {
        return retentionDays;
    }
}
