package com.tradingbot.marketdata.repository;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.marketdata.repository.SqliteOptionChainSnapshotRepository.SnapshotRow;
import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Round-trip and retention tests for the post-close option-chain snapshot table. */
public class SqliteOptionChainSnapshotRepositoryTest {

    private static final LocalDate SESSION = LocalDate.of(2026, 10, 14);

    @TempDir File tempDir;

    private SqliteOptionChainSnapshotRepository repository;
    private File dbFile;

    @BeforeEach
    void setUp() {
        dbFile = new File(tempDir, "test_trading_bot.db");
        repository = new SqliteOptionChainSnapshotRepository(dbFile.getAbsolutePath());
        repository.init();
    }

    @Test
    @DisplayName("Init is idempotent: a second init neither fails nor duplicates data")
    void initSchemaIsIdempotent() {
        repository.initSchema();

        repository.save(SESSION, "RELIANCE", Instant.now(), 1.4, false, "{\"pcr\":1.4}");
        repository.init();

        assertEquals(1, repository.countForSession(SESSION));
    }

    @Test
    @DisplayName("A saved snapshot is found again with every column preserved")
    void saveAndFindRoundTrip() {
        Instant capturedAt = Instant.parse("2026-10-14T14:30:00Z");
        String json = "{\"underlying\":\"RELIANCE\",\"pcr\":1.42,\"synthetic\":false}";

        repository.save(SESSION, "RELIANCE", capturedAt, 1.42, false, json);

        Optional<SnapshotRow> found = repository.find(SESSION, "RELIANCE");
        assertTrue(found.isPresent(), "the saved snapshot must be readable");
        SnapshotRow row = found.get();
        assertEquals(SESSION, row.sessionDate());
        assertEquals("RELIANCE", row.symbol());
        assertEquals(capturedAt, row.capturedAt());
        assertEquals(1.42, row.pcr(), 1e-9);
        assertFalse(row.synthetic());
        assertEquals(json, row.chainJson());
    }

    @Test
    @DisplayName("Symbol lookup is normalised: lowercase and padded names hit the same row")
    void symbolLookupIsNormalised() {
        repository.save(SESSION, "RELIANCE", Instant.now(), 1.1, false, "{\"synthetic\":false}");

        assertTrue(
                repository.find(SESSION, "  reliance ").isPresent(),
                "trim + upper-case must resolve the same primary key");
        assertTrue(repository.find(SESSION, "TCS").isEmpty(), "other symbols stay absent");
    }

    @Test
    @DisplayName("A miss returns empty rather than throwing")
    void findMissReturnsEmpty() {
        assertTrue(repository.find(SESSION, "RELIANCE").isEmpty(), "no rows saved yet");
        assertTrue(
                repository.find(SESSION.minusDays(1), "RELIANCE").isEmpty(),
                "a different session must not match");
        assertTrue(repository.find(null, "RELIANCE").isEmpty(), "null session is a miss");
        assertTrue(repository.find(SESSION, null).isEmpty(), "null symbol is a miss");
    }

    @Test
    @DisplayName("Re-capturing the same (session, symbol) replaces the row instead of duplicating")
    void sameKeyIsAnUpsert() {
        repository.save(SESSION, "TCS", Instant.now(), 1.0, false, "{\"pcr\":1.0}");
        repository.save(SESSION, "TCS", Instant.now(), 1.7, false, "{\"pcr\":1.7}");

        assertEquals(1, repository.countForSession(SESSION), "one row per session and symbol");
        assertEquals(1.7, repository.find(SESSION, "TCS").get().pcr(), 1e-9, "last write wins");
    }

    @Test
    @DisplayName("Retention pruning deletes only sessions strictly before the cutoff")
    void deleteSessionDatesBeforePrunesOldRows() {
        repository.save(SESSION.minusDays(45), "RELIANCE", Instant.now(), 1.0, false, "{}");
        repository.save(SESSION.minusDays(10), "RELIANCE", Instant.now(), 1.0, false, "{}");
        repository.save(SESSION, "RELIANCE", Instant.now(), 1.0, false, "{}");

        int pruned = repository.deleteSessionDatesBefore(SESSION.minusDays(30));

        assertEquals(1, pruned, "only the 45-day-old row is beyond retention");
        assertTrue(repository.find(SESSION.minusDays(45), "RELIANCE").isEmpty());
        assertTrue(repository.find(SESSION.minusDays(10), "RELIANCE").isPresent());
        assertTrue(repository.find(SESSION, "RELIANCE").isPresent());
        assertEquals(0, repository.deleteSessionDatesBefore(null), "null cutoff is a no-op");
    }

    @Test
    @DisplayName("Stored session dates come back distinct and ascending")
    void findStoredSessionDatesReturnsDistinctAscending() {
        repository.save(SESSION, "RELIANCE", Instant.now(), 1.0, false, "{}");
        repository.save(SESSION, "TCS", Instant.now(), 1.0, false, "{}");
        repository.save(SESSION.minusDays(1), "TCS", Instant.now(), 1.0, false, "{}");

        List<LocalDate> dates = repository.findStoredSessionDates();

        assertEquals(
                List.of(SESSION.minusDays(1), SESSION),
                dates,
                "one entry per session, oldest first");
    }

    @Test
    @DisplayName("The backing database path is reported for diagnostics")
    void reportsDbPath() {
        assertEquals(dbFile.getAbsolutePath(), repository.getDbPath());
    }
}
