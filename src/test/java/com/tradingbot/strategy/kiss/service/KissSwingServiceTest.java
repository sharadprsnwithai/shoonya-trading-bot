package com.tradingbot.strategy.kiss.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.marketdata.YahooFinanceService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.kiss.config.KissStrategyConfig;
import com.tradingbot.strategy.kiss.indicator.KissIndicatorService;
import com.tradingbot.strategy.kiss.model.KissPosition;
import com.tradingbot.strategy.kiss.model.KissSignal;
import com.tradingbot.strategy.kiss.model.KissSignalType;
import com.tradingbot.strategy.kiss.model.KissSnapshot;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KissSwingServiceTest {

    private KissStrategyConfig config;
    private KissIndicatorService indicatorService;
    private HistoricalOhlcCacheService ohlcCacheService;
    private YahooFinanceService yahooFinanceService;
    private TelegramService telegramService;
    private ObjectMapper objectMapper;
    private KissSwingService kissSwingService;

    @TempDir java.nio.file.Path tempDir;

    @BeforeEach
    void setUp() {
        config = new KissStrategyConfig();
        config.setStateFilePath(tempDir.resolve("kiss_state.json").toString());
        config.setScanDelayMs(0);
        config.setPaperCapital(500000.0);

        indicatorService = mock(KissIndicatorService.class);
        ohlcCacheService = mock(HistoricalOhlcCacheService.class);
        yahooFinanceService = mock(YahooFinanceService.class);
        telegramService = mock(TelegramService.class);
        objectMapper = new ObjectMapper().findAndRegisterModules();

        kissSwingService =
                new KissSwingService(
                        config,
                        indicatorService,
                        ohlcCacheService,
                        yahooFinanceService,
                        telegramService,
                        objectMapper);
        kissSwingService.init();
    }

    private List<Candle> createDummyCandles(int count, double closePrice) {
        List<Candle> candles = new ArrayList<>();
        Instant now = Instant.parse("2026-10-05T09:15:00Z");
        for (int i = 0; i < count; i++) {
            candles.add(
                    new Candle(
                            "CRUDEOIL",
                            "60",
                            now.plusSeconds(i * 3600),
                            BigDecimal.valueOf(closePrice - 5),
                            BigDecimal.valueOf(closePrice + 10),
                            BigDecimal.valueOf(closePrice - 10),
                            BigDecimal.valueOf(closePrice),
                            1000));
        }
        return candles;
    }

    @Test
    void testBullishSignalGenerationAndPositionOpening() {
        List<Candle> hourly = createDummyCandles(65, 6000.0);
        List<Candle> daily = createDummyCandles(35, 6000.0);

        when(yahooFinanceService.fetchHourlyCandles(eq("CRUDEOIL"), anyInt())).thenReturn(hourly);
        when(ohlcCacheService.getDailyCandles("CRUDEOIL")).thenReturn(daily);

        KissSnapshot snapshot =
                new KissSnapshot(
                        "CRUDEOIL",
                        6000.0,
                        true,
                        5980.0,
                        6020.0,
                        5970.0,
                        6015.0,
                        5950.0,
                        5900.0,
                        5940.0,
                        true,
                        15.0,
                        10.0,
                        5.0,
                        false,
                        true, // isBullishSetup
                        false,
                        5900.0, // suggested SL
                        6300.0, // suggested Target
                        Instant.now());

        when(indicatorService.computeSnapshot(eq("CRUDEOIL"), any(), any())).thenReturn(snapshot);

        KissSignal signal = kissSwingService.evaluateSymbol("CRUDEOIL");
        assertNotNull(signal);
        assertEquals(KissSignalType.BUY_SIGNAL, signal.signalType());
        assertEquals(6000.0, signal.entryPrice());
        assertEquals(5900.0, signal.stopLoss());
        assertEquals(6300.0, signal.targetPrice());
    }

    @Test
    void testManagePositionsTargetHit() {
        KissPosition pos =
                new KissPosition(
                        "CRUDEOIL",
                        KissSignalType.BUY_SIGNAL,
                        6000.0,
                        5900.0,
                        6300.0,
                        100,
                        100,
                        Instant.now());

        kissSwingService.getState().getPositions().put("CRUDEOIL", pos);

        List<Candle> hourly = createDummyCandles(65, 6350.0); // Target reached!
        when(yahooFinanceService.fetchHourlyCandles(eq("CRUDEOIL"), anyInt())).thenReturn(hourly);

        kissSwingService.manageOpenPositions();

        assertFalse(kissSwingService.getState().getPositions().containsKey("CRUDEOIL"));
        assertEquals(1, kissSwingService.getState().getClosedPositions().size());
        KissPosition closed = kissSwingService.getState().getClosedPositions().get(0);
        assertEquals("Target 1:3 Hit (+Profit)", closed.getExitReason());
        assertEquals(35000.0, closed.getRealizedPnl(), 0.01);
        verify(telegramService, atLeastOnce()).sendTextMessage(anyString());
    }

    @Test
    void testManagePositionsStopLossHit() {
        KissPosition pos =
                new KissPosition(
                        "CRUDEOIL",
                        KissSignalType.BUY_SIGNAL,
                        6000.0,
                        5900.0,
                        6300.0,
                        100,
                        100,
                        Instant.now());

        kissSwingService.getState().getPositions().put("CRUDEOIL", pos);

        List<Candle> hourly = createDummyCandles(65, 5850.0); // SL breached!
        when(yahooFinanceService.fetchHourlyCandles(eq("CRUDEOIL"), anyInt())).thenReturn(hourly);

        kissSwingService.manageOpenPositions();

        assertFalse(kissSwingService.getState().getPositions().containsKey("CRUDEOIL"));
        assertEquals(1, kissSwingService.getState().getClosedPositions().size());
        KissPosition closed = kissSwingService.getState().getClosedPositions().get(0);
        assertEquals("Stop Loss Breached", closed.getExitReason());
        assertEquals(-15000.0, closed.getRealizedPnl(), 0.01);
    }
}
