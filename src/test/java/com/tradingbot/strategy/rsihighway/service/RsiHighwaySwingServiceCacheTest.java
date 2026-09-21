package com.tradingbot.strategy.rsihighway.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.rsihighway.config.RsiHighwayConfig;
import com.tradingbot.strategy.rsihighway.indicator.MultiTimeframeRsiService;
import com.tradingbot.telegram.TelegramService;
import java.io.File;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class RsiHighwaySwingServiceCacheTest {

    @TempDir File tempDir;

    private HistoricalOhlcCacheService cacheService;
    private ShoonyaMarketDataService marketDataService;
    private MultiTimeframeRsiService multiTimeframeRsiService;
    private RsiHighwayMarketBreadthService breadthService;
    private RsiHighwayExecutionService executionService;
    private TelegramService telegramService;
    private RsiHighwayConfig config;
    private RsiHighwaySwingService swingService;

    @BeforeEach
    void setUp() {
        cacheService = mock(HistoricalOhlcCacheService.class);
        marketDataService = mock(ShoonyaMarketDataService.class);
        multiTimeframeRsiService = mock(MultiTimeframeRsiService.class);
        breadthService = mock(RsiHighwayMarketBreadthService.class);
        executionService = mock(RsiHighwayExecutionService.class);
        telegramService = mock(TelegramService.class);
        config = new RsiHighwayConfig();
        config.setStateFilePath(new File(tempDir, "state.json").getAbsolutePath());

        swingService =
                new RsiHighwaySwingService(
                        marketDataService,
                        cacheService,
                        multiTimeframeRsiService,
                        breadthService,
                        executionService,
                        telegramService,
                        config,
                        new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void testEodScanUsesOhlcCacheServiceWithoutBrokerCalls() {
        List<Candle> dummyCandles = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            dummyCandles.add(
                    new Candle(
                            "RELIANCE",
                            "D",
                            Instant.now().minusSeconds(86400L * (50 - i)),
                            BigDecimal.valueOf(2500 + i),
                            BigDecimal.valueOf(2520 + i),
                            BigDecimal.valueOf(2490 + i),
                            BigDecimal.valueOf(2510 + i),
                            500000L));
        }

        when(cacheService.getDailyCandles("RELIANCE")).thenReturn(dummyCandles);
        when(cacheService.getDailyCandles("NIFTY 50")).thenReturn(dummyCandles);

        swingService.evaluateEodScanForSymbols(List.of("RELIANCE"));

        // Verify it retrieved from cacheService
        verify(cacheService, atLeastOnce()).getDailyCandles("RELIANCE");
        // Verify it did not call ShoonyaMarketDataService
        verify(marketDataService, never()).fetchDailyCandles(eq("RELIANCE"), anyInt());
    }
}
