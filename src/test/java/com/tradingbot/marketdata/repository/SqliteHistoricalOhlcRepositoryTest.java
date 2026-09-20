package com.tradingbot.marketdata.repository;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.marketdata.model.SymbolOhlcBundle;
import com.tradingbot.model.Candle;
import java.io.File;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SqliteHistoricalOhlcRepositoryTest {

    @TempDir File tempDir;

    private SqliteHistoricalOhlcRepository repository;
    private File dbFile;

    @BeforeEach
    void setUp() {
        dbFile = new File(tempDir, "test_trading_bot.db");
        repository = new SqliteHistoricalOhlcRepository(dbFile.getAbsolutePath());
        repository.init();
    }

    @Test
    void testInitSchemaAndUpsertCandles() {
        Candle c1 =
                new Candle(
                        "RELIANCE",
                        "D",
                        Instant.parse("2026-09-17T09:15:00Z"),
                        BigDecimal.valueOf(1300.0),
                        BigDecimal.valueOf(1320.0),
                        BigDecimal.valueOf(1295.0),
                        BigDecimal.valueOf(1310.0),
                        500000L);

        Candle c2 =
                new Candle(
                        "RELIANCE",
                        "D",
                        Instant.parse("2026-09-18T09:15:00Z"),
                        BigDecimal.valueOf(1310.0),
                        BigDecimal.valueOf(1335.0),
                        BigDecimal.valueOf(1305.0),
                        BigDecimal.valueOf(1330.0),
                        600000L);

        repository.batchUpsertCandles("RELIANCE", "D", List.of(c1, c2));

        List<Candle> retrieved = repository.getCandles("RELIANCE", "D");
        assertEquals(2, retrieved.size());
        assertEquals("RELIANCE", retrieved.get(0).symbol());
        assertEquals("D", retrieved.get(0).timeframe());
        assertEquals(1300.0, retrieved.get(0).open().doubleValue(), 0.001);
        assertEquals(1330.0, retrieved.get(1).close().doubleValue(), 0.001);
        assertEquals(600000L, retrieved.get(1).volume());
    }

    @Test
    void testUpsertDeduplication() {
        Candle c1 =
                new Candle(
                        "TCS",
                        "D",
                        Instant.parse("2026-09-18T09:15:00Z"),
                        BigDecimal.valueOf(3500.0),
                        BigDecimal.valueOf(3550.0),
                        BigDecimal.valueOf(3480.0),
                        BigDecimal.valueOf(3520.0),
                        100000L);

        Candle updatedC1 =
                new Candle(
                        "TCS",
                        "D",
                        Instant.parse("2026-09-18T09:15:00Z"),
                        BigDecimal.valueOf(3500.0),
                        BigDecimal.valueOf(3560.0),
                        BigDecimal.valueOf(3480.0),
                        BigDecimal.valueOf(3545.0),
                        120000L);

        repository.batchUpsertCandles("TCS", "D", List.of(c1));
        repository.batchUpsertCandles("TCS", "D", List.of(updatedC1));

        List<Candle> result = repository.getCandles("TCS", "D");
        assertEquals(1, result.size());
        assertEquals(3545.0, result.get(0).close().doubleValue(), 0.001);
        assertEquals(120000L, result.get(0).volume());
    }

    @Test
    void testSaveAndGetSymbolBundle() {
        Candle d1 =
                new Candle(
                        "INFY",
                        "D",
                        Instant.parse("2026-09-18T09:15:00Z"),
                        BigDecimal.valueOf(1500.0),
                        BigDecimal.valueOf(1520.0),
                        BigDecimal.valueOf(1490.0),
                        BigDecimal.valueOf(1510.0),
                        200000L);
        Candle w1 =
                new Candle(
                        "INFY",
                        "W",
                        Instant.parse("2026-09-14T09:15:00Z"),
                        BigDecimal.valueOf(1480.0),
                        BigDecimal.valueOf(1530.0),
                        BigDecimal.valueOf(1470.0),
                        BigDecimal.valueOf(1510.0),
                        1000000L);

        SymbolOhlcBundle bundle = new SymbolOhlcBundle(List.of(d1), List.of(w1), List.of());
        repository.saveSymbolBundle("INFY", bundle);

        SymbolOhlcBundle retrieved = repository.getSymbolBundle("INFY");
        assertNotNull(retrieved);
        assertEquals(1, retrieved.daily().size());
        assertEquals(1, retrieved.weekly().size());
        assertTrue(retrieved.monthly().isEmpty());
    }

    @Test
    void testGetAllCachedSymbolsAndCount() {
        Candle c1 =
                new Candle(
                        "SBIN",
                        "D",
                        Instant.parse("2026-09-18T09:15:00Z"),
                        BigDecimal.valueOf(800.0),
                        BigDecimal.valueOf(810.0),
                        BigDecimal.valueOf(795.0),
                        BigDecimal.valueOf(805.0),
                        300000L);
        Candle c2 =
                new Candle(
                        "HDFCBANK",
                        "D",
                        Instant.parse("2026-09-18T09:15:00Z"),
                        BigDecimal.valueOf(1600.0),
                        BigDecimal.valueOf(1620.0),
                        BigDecimal.valueOf(1590.0),
                        BigDecimal.valueOf(1610.0),
                        400000L);

        repository.batchUpsertCandles("SBIN", "D", List.of(c1));
        repository.batchUpsertCandles("HDFCBANK", "D", List.of(c2));

        Set<String> symbols = repository.getAllCachedSymbols();
        assertTrue(symbols.contains("SBIN"));
        assertTrue(symbols.contains("HDFCBANK"));
        assertEquals(2, repository.getSymbolCount());
    }

    @Test
    void testMetadata() {
        repository.setMetadata("LAST_SYNC_TIME", "2026-09-18T20:00:00Z");
        assertEquals("2026-09-18T20:00:00Z", repository.getMetadata("LAST_SYNC_TIME"));
        assertNull(repository.getMetadata("NON_EXISTENT_KEY"));
    }

    @Test
    void testGetLatestCandlesLimit() {
        for (int i = 1; i <= 10; i++) {
            Candle c =
                    new Candle(
                            "LT",
                            "5m",
                            Instant.ofEpochSecond(1700000000L + (i * 300)),
                            BigDecimal.valueOf(3000 + i),
                            BigDecimal.valueOf(3010 + i),
                            BigDecimal.valueOf(2990 + i),
                            BigDecimal.valueOf(3005 + i),
                            1000L * i);
            repository.batchUpsertCandles("LT", "5m", List.of(c));
        }

        List<Candle> latest3 = repository.getLatestCandles("LT", "5m", 3);
        assertEquals(3, latest3.size());
        assertEquals(3013.0, latest3.get(0).close().doubleValue(), 0.001);
        assertEquals(3014.0, latest3.get(1).close().doubleValue(), 0.001);
        assertEquals(3015.0, latest3.get(2).close().doubleValue(), 0.001);
    }

    @Test
    void testTimeframeAliasRetrieval() {
        Candle weeklyCandle =
                new Candle(
                        "WIPRO",
                        "1W",
                        Instant.parse("2026-09-14T09:15:00Z"),
                        BigDecimal.valueOf(500.0),
                        BigDecimal.valueOf(520.0),
                        BigDecimal.valueOf(490.0),
                        BigDecimal.valueOf(510.0),
                        800000L);

        repository.batchUpsertCandles("WIPRO", "1W", List.of(weeklyCandle));

        // Both "W" and "1W" query aliases must successfully retrieve the candle
        List<Candle> byW = repository.getCandles("WIPRO", "W");
        List<Candle> by1W = repository.getCandles("WIPRO", "1W");

        assertEquals(1, byW.size());
        assertEquals(1, by1W.size());
        assertEquals(510.0, byW.get(0).close().doubleValue(), 0.001);
        assertEquals(510.0, by1W.get(0).close().doubleValue(), 0.001);
    }
}
