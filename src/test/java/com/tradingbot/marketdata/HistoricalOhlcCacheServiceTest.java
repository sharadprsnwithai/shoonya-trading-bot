package com.tradingbot.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.model.Candle;
import java.io.File;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class HistoricalOhlcCacheServiceTest {

    @TempDir File tempDir;

    private YahooFinanceService yahooService;
    private HistoricalOhlcCacheService cacheService;
    private File cacheFile;

    @BeforeEach
    void setUp() {
        yahooService = mock(YahooFinanceService.class);
        cacheFile = new File(tempDir, "historical_ohlc.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        cacheService =
                new HistoricalOhlcCacheService(yahooService, mapper, cacheFile.getAbsolutePath());
    }

    @Test
    void testSyncAndPersistSymbol() {
        Candle c1 =
                new Candle(
                        "TCS",
                        "D",
                        Instant.parse("2026-09-17T03:45:00Z"),
                        BigDecimal.valueOf(3500),
                        BigDecimal.valueOf(3550),
                        BigDecimal.valueOf(3480),
                        BigDecimal.valueOf(3540),
                        100000L);
        when(yahooService.fetchDailyCandles("TCS", 2)).thenReturn(List.of(c1));

        cacheService.syncSymbol("TCS", 2);
        cacheService.saveToFile();

        List<Candle> daily = cacheService.getDailyCandles("TCS");
        assertNotNull(daily);
        assertEquals(1, daily.size());
        assertEquals("TCS", daily.get(0).symbol());
        assertTrue(cacheFile.exists());

        // Reload from file in a fresh instance
        HistoricalOhlcCacheService freshService =
                new HistoricalOhlcCacheService(
                        yahooService,
                        new ObjectMapper().findAndRegisterModules(),
                        cacheFile.getAbsolutePath());
        freshService.loadFromFile();
        List<Candle> loadedDaily = freshService.getDailyCandles("TCS");
        assertEquals(1, loadedDaily.size());
        assertEquals("TCS", loadedDaily.get(0).symbol());
    }

    @Test
    void testResamplingToWeeklyAndMonthlyOnSync() {
        // Create 20 daily candles spanning multiple weeks
        Instant start = Instant.parse("2026-08-03T03:45:00Z");
        List<Candle> dailies = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            dailies.add(
                    new Candle(
                            "RELIANCE",
                            "D",
                            start.plusSeconds(i * 86400L),
                            BigDecimal.valueOf(2500 + i),
                            BigDecimal.valueOf(2550 + i),
                            BigDecimal.valueOf(2490 + i),
                            BigDecimal.valueOf(2520 + i),
                            50000L));
        }
        when(yahooService.fetchDailyCandles("RELIANCE", 2)).thenReturn(dailies);

        cacheService.syncSymbol("RELIANCE", 2);

        List<Candle> weekly = cacheService.getWeeklyCandles("RELIANCE");
        List<Candle> monthly = cacheService.getMonthlyCandles("RELIANCE");

        assertNotNull(weekly);
        assertFalse(weekly.isEmpty(), "Weekly candles should be computed");
        assertNotNull(monthly);
        assertFalse(monthly.isEmpty(), "Monthly candles should be computed");
    }

    @Test
    void testIsSymbolFresh() {
        assertFalse(cacheService.isSymbolFresh("INFY"), "Missing symbol should not be fresh");

        // Fresh candle (today or yesterday)
        Instant recent = Instant.now().minusSeconds(3600 * 12);
        Candle c1 =
                new Candle(
                        "INFY",
                        "D",
                        recent,
                        BigDecimal.valueOf(1500),
                        BigDecimal.valueOf(1520),
                        BigDecimal.valueOf(1490),
                        BigDecimal.valueOf(1510),
                        200000L);
        when(yahooService.fetchDailyCandles("INFY", 2)).thenReturn(List.of(c1));

        cacheService.syncSymbol("INFY", 2);
        assertTrue(cacheService.isSymbolFresh("INFY"), "Symbol with recent candle should be fresh");
    }

    @Test
    void testIsSymbolFreshHolidayTolerance() {
        // Today is Thursday 2026-09-17, Tuesday 2026-09-15 candle should be fresh due to Wednesday
        // holiday tolerance
        java.time.Clock clock =
                java.time.Clock.fixed(
                        Instant.parse("2026-09-17T04:00:00Z"), java.time.ZoneId.of("Asia/Kolkata"));
        cacheService.setClock(clock);

        Instant tuesday = Instant.parse("2026-09-15T10:00:00Z");
        Candle c1 =
                new Candle(
                        "HDFCBANK",
                        "D",
                        tuesday,
                        BigDecimal.valueOf(1600),
                        BigDecimal.valueOf(1620),
                        BigDecimal.valueOf(1590),
                        BigDecimal.valueOf(1610),
                        300000L);
        when(yahooService.fetchDailyCandles("HDFCBANK", 2)).thenReturn(List.of(c1));

        cacheService.syncSymbol("HDFCBANK", 2);
        assertTrue(
                cacheService.isSymbolFresh("HDFCBANK"),
                "Symbol with 2-day old candle during mid-week holiday should be fresh");
    }

    @Test
    void testLazyOnDemandFetchForMissingSymbol() {
        Candle c1 =
                new Candle(
                        "WIPRO",
                        "D",
                        Instant.now(),
                        BigDecimal.valueOf(500),
                        BigDecimal.valueOf(510),
                        BigDecimal.valueOf(495),
                        BigDecimal.valueOf(505),
                        80000L);
        when(yahooService.fetchDailyCandles("WIPRO", 2)).thenReturn(List.of(c1));

        // WIPRO is not initially cached, requesting getDailyCandles should trigger lazy fetch
        List<Candle> daily = cacheService.getDailyCandles("WIPRO");
        assertNotNull(daily);
        assertEquals(1, daily.size());
        assertEquals("WIPRO", daily.get(0).symbol());
        verify(yahooService, times(1)).fetchDailyCandles("WIPRO", 2);
    }
}
