package com.tradingbot.marketdata.repository;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.model.Candle;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SqliteBackupServiceTest {

    @TempDir File tempDir;

    private SqliteHistoricalOhlcRepository repository;
    private SqliteBackupService backupService;
    private File dbFile;
    private File backupDir;

    @BeforeEach
    void setUp() {
        dbFile = new File(tempDir, "trading_bot.db");
        backupDir = new File(tempDir, "backups");
        backupDir.mkdirs();

        repository = new SqliteHistoricalOhlcRepository(dbFile.getAbsolutePath());
        repository.init();

        backupService =
                new SqliteBackupService(dbFile.getAbsolutePath(), backupDir.getAbsolutePath(), 3);
        backupService.setClock(
                Clock.fixed(Instant.parse("2026-09-20T10:00:00Z"), ZoneId.of("Asia/Kolkata")));
    }

    @Test
    void testCreateDailyBackupSuccess() {
        // Insert sample candles
        Candle c1 =
                new Candle(
                        "RELIANCE",
                        "D",
                        Instant.parse("2026-09-18T09:15:00Z"),
                        BigDecimal.valueOf(1300.0),
                        BigDecimal.valueOf(1320.0),
                        BigDecimal.valueOf(1295.0),
                        BigDecimal.valueOf(1310.0),
                        500000L);
        repository.batchUpsertCandles("RELIANCE", "D", List.of(c1));

        Optional<File> backup = backupService.createDailyBackup();
        assertTrue(backup.isPresent());
        File backupFile = backup.get();
        assertTrue(backupFile.exists());
        assertTrue(backupFile.getName().contains("2026-09-20"));
        assertTrue(backupService.verifyIntegrity(backupFile));

        // Latest copy should also exist
        File latestCopy = new File(backupDir, "trading_bot_backup_latest.db");
        assertTrue(latestCopy.exists());
        assertTrue(backupService.verifyIntegrity(latestCopy));
    }

    @Test
    void testPruneOldBackupsRetention() throws Exception {
        // Create 5 dummy dated backup files
        for (int i = 1; i <= 5; i++) {
            File dummy = new File(backupDir, "trading_bot_backup_2026-09-0" + i + ".db");
            dummy.createNewFile();
            dummy.setLastModified(1000L * i);
        }

        // With retention = 3, pruning should delete the 2 oldest
        int pruned = backupService.pruneOldBackups();
        assertEquals(2, pruned);
        assertEquals(3, backupService.getAvailableBackups().size());
    }

    @Test
    void testCorruptionDetectionAndAutomatedRecovery() throws Exception {
        // 1. Put valid data in repo and take a valid backup
        Candle c1 =
                new Candle(
                        "INFY",
                        "D",
                        Instant.parse("2026-09-18T09:15:00Z"),
                        BigDecimal.valueOf(1500.0),
                        BigDecimal.valueOf(1520.0),
                        BigDecimal.valueOf(1490.0),
                        BigDecimal.valueOf(1510.0),
                        200000L);
        repository.batchUpsertCandles("INFY", "D", List.of(c1));
        Optional<File> backup = backupService.createDailyBackup();
        assertTrue(backup.isPresent());

        // 2. Corrupt the active database file deliberately
        try (FileOutputStream fos = new FileOutputStream(dbFile)) {
            fos.write("CORRUPTED JUNK HEADER NOT A VALID SQLITE FILE".getBytes());
        }

        assertFalse(backupService.verifyIntegrity(dbFile));

        // 3. Trigger check and recovery
        boolean recovered = backupService.checkAndRecoverIfCorrupted();
        assertTrue(recovered, "Should recover successfully from latest backup");

        // Verify recovered file is healthy and contains original data
        assertTrue(backupService.verifyIntegrity(dbFile));
        List<Candle> retrieved = repository.getCandles("INFY", "D");
        assertEquals(1, retrieved.size());
        assertEquals("INFY", retrieved.get(0).symbol());
        assertEquals(1500.0, retrieved.get(0).open().doubleValue(), 0.001);
    }
}
