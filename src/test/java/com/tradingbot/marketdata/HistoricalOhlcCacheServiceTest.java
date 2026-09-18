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

    @TempDir
    File tempDir;

    private YahooFinanceService yahooService;
    private HistoricalOhlcCacheService cacheService;
    private File cacheFile;

    @BeforeEach
    void setUp() {
        yahooService = mock(YahooFinanceService.class);
        cacheFile = new File(tempDir, "historical_ohlc.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        cacheService = new HistoricalOhlcCacheService(yahooService, mapper, cacheFile.getAbsolutePath());
    }

    @Test
    void testSyncAndPersistSymbol() {
        Candle c1 = new Candle("TCS", "D", Instant.parse("2026-09-17T03:45:00Z"), BigDecimal.valueOf(3500), BigDecimal.valueOf(3550), BigDecimal.valueOf(3480), BigDecimal.valueOf(3540), 100000L);
        when(yahooService.fetchDailyCandles("TCS", 2)).thenReturn(List.of(c1));

        cacheService.syncSymbol("TCS", 2);
        cacheService.saveToFile();

        List<Candle> daily = cacheService.getDailyCandles("TCS");
        assertNotNull(daily);
        assertEquals(1, daily.size());
        assertEquals("TCS", daily.get(0).symbol());
        assertTrue(cacheFile.exists());

        // Reload from file in a fresh instance
        HistoricalOhlcCacheService freshService = new HistoricalOhlcCacheService(yahooService, new ObjectMapper().findAndRegisterModules(), cacheFile.getAbsolutePath());
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
            dailies.add(new Candle("RELIANCE", "D", start.plusSeconds(i * 86400L),
                    BigDecimal.valueOf(2500 + i), BigDecimal.valueOf(2550 + i), BigDecimal.valueOf(2490 + i), BigDecimal.valueOf(2520 + i), 50000L));
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
}
