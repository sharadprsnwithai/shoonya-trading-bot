package com.tradingbot.strategy.rsihighway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.rsihighway.config.RsiHighwayConfig;
import com.tradingbot.strategy.rsihighway.indicator.MultiTimeframeRsiService;
import com.tradingbot.strategy.rsihighway.indicator.PriceActionPatternDetector;
import com.tradingbot.strategy.rsihighway.model.MarketBreadthSnapshot;
import com.tradingbot.strategy.rsihighway.model.MultiTimeframeRsiSnapshot;
import com.tradingbot.strategy.rsihighway.model.PriceActionPattern;
import com.tradingbot.strategy.rsihighway.model.RsiHighwayPosition;
import com.tradingbot.strategy.rsihighway.model.RsiHighwayTranche;
import com.tradingbot.telegram.TelegramService;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RsiHighwaySwingServiceTest {

    private ShoonyaMarketDataService marketDataService;
    private MultiTimeframeRsiService multiTimeframeRsiService;
    private PriceActionPatternDetector patternDetector;
    private RsiHighwayMarketBreadthService breadthService;
    private RsiHighwayExecutionService executionService;
    private TelegramService telegramService;
    private RsiHighwayConfig config;
    private ObjectMapper objectMapper;
    private RsiHighwaySwingService swingService;
    private File tempStateFile;

    @BeforeEach
    void setUp() throws Exception {
        marketDataService = mock(ShoonyaMarketDataService.class);
        multiTimeframeRsiService = mock(MultiTimeframeRsiService.class);
        patternDetector = mock(PriceActionPatternDetector.class);
        breadthService = mock(RsiHighwayMarketBreadthService.class);
        executionService = mock(RsiHighwayExecutionService.class);
        telegramService = mock(TelegramService.class);

        tempStateFile = Files.createTempFile("rsi_test_state", ".json").toFile();
        tempStateFile.deleteOnExit();

        config = new RsiHighwayConfig();
        config.setEnabled(true);
        config.setPaperTrading(true);
        config.setStateFilePath(tempStateFile.getAbsolutePath());
        config.setMaxConcurrentPositions(5);

        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        swingService = new RsiHighwaySwingService(
                marketDataService,
                multiTimeframeRsiService,
                breadthService,
                executionService,
                telegramService,
                config,
                objectMapper
        );
    }

    private List<Candle> createDummyDailyCandles(int count, double closePrice) {
        List<Candle> list = new ArrayList<>();
        Instant now = Instant.parse("2026-01-01T10:00:00Z");
        for (int i = 0; i < count; i++) {
            list.add(new Candle(
                    "TEST",
                    "D",
                    now.plusSeconds(i * 86400L),
                    BigDecimal.valueOf(closePrice - 10),
                    BigDecimal.valueOf(closePrice + 10),
                    BigDecimal.valueOf(closePrice - 15),
                    BigDecimal.valueOf(closePrice),
                    100000L
            ));
        }
        return list;
    }

    @Test
    void testEvaluateEodScanWhenHighwayIsClosed() {
        when(breadthService.evaluateBreadth(any(), any(), anyInt(), anyDouble()))
                .thenReturn(new MarketBreadthSnapshot(false, 500, 2, List.of(), 0.25, "Drawdown too high", Instant.now()));

        swingService.evaluateEodScan();

        assertThat(swingService.getActivePositions()).isEmpty();
    }

    @Test
    void testEvaluateEodScanGeneratesInitialEntry() {
        when(breadthService.evaluateBreadth(any(), any(), anyInt(), anyDouble()))
                .thenReturn(new MarketBreadthSnapshot(true, 500, 25, List.of("TCS"), 0.05, "Healthy regime", Instant.now()));

        List<Candle> candles = createDummyDailyCandles(300, 3500.0);
        when(marketDataService.fetchDailyCandles(eq("TCS"), anyInt())).thenReturn(candles);

        MultiTimeframeRsiSnapshot rsiSnapshot = new MultiTimeframeRsiSnapshot(
                "TCS",
                65.0, // Monthly
                62.0, // Weekly
                51.5, // Daily
                60.0, // Daily ATR
                3500.0, // currentPrice
                3520.0, // high
                3450.0, // low
                Optional.of(PriceActionPattern.BULLISH_ENGULFING),
                true,
                true,
                Instant.now()
        );
        when(multiTimeframeRsiService.computeSnapshot(eq("TCS"), any())).thenReturn(rsiSnapshot);

        when(executionService.executeEntrySignal(any(), anyDouble()))
                .thenReturn(Optional.of(new RsiHighwayTranche(1, 10, 3500.0, Instant.now(), "ORD_TEST_01")));

        swingService.evaluateEodScanForSymbols(List.of("TCS"));

        assertThat(swingService.getActivePositions()).containsKey("TCS");
        RsiHighwayPosition pos = swingService.getActivePositions().get("TCS");
        assertThat(pos.getTotalQuantity()).isEqualTo(10);
        assertThat(pos.getAveragePrice()).isEqualTo(3500.0);

        // Capital should be deducted: 1,000,000 - (10 * 3500) = 965,000
        assertThat(swingService.getState().getAvailableCapital()).isEqualTo(1000000.0 - 35000.0);
    }

    @Test
    void testEvaluateEodScanExitsWhenDailyRsiDropsBelow50() {
        // Setup existing position
        RsiHighwayPosition existingPos = new RsiHighwayPosition("INFY", "NSE", 1500.0, 1400.0);
        existingPos.addTranche(new RsiHighwayTranche(1, 20, 1500.0, Instant.now(), "ORD_INFY"));
        swingService.getState().getPositions().put("INFY", existingPos);

        when(breadthService.evaluateBreadth(any(), any(), anyInt(), anyDouble()))
                .thenReturn(new MarketBreadthSnapshot(true, 500, 20, List.of(), 0.05, "Healthy", Instant.now()));

        List<Candle> candles = createDummyDailyCandles(300, 1450.0);
        when(marketDataService.fetchDailyCandles(eq("INFY"), anyInt())).thenReturn(candles);

        // Daily RSI drops to 47.0 (< 50.0)
        MultiTimeframeRsiSnapshot rsiSnapshot = new MultiTimeframeRsiSnapshot(
                "INFY",
                62.0,
                58.0,
                47.0,
                30.0,
                1450.0,
                1470.0,
                1440.0,
                Optional.empty(),
                false,
                false,
                Instant.now()
        );
        when(multiTimeframeRsiService.computeSnapshot(eq("INFY"), any())).thenReturn(rsiSnapshot);
        when(executionService.executeExit(any(), anyDouble(), anyString())).thenReturn(true);

        swingService.evaluateEodScanForSymbols(List.of("INFY"));

        assertThat(swingService.getActivePositions()).doesNotContainKey("INFY");
        assertThat(swingService.getState().getClosedPositions()).hasSize(1);
        assertThat(swingService.getState().getClosedPositions().get(0).getSymbol()).isEqualTo("INFY");
    }

    @Test
    void testMorningPlungeCheckEmergencyExit() {
        RsiHighwayPosition existingPos = new RsiHighwayPosition("WIPRO", "NSE", 500.0, 470.0);
        existingPos.addTranche(new RsiHighwayTranche(1, 100, 500.0, Instant.now(), "ORD_WIPRO"));
        swingService.getState().getPositions().put("WIPRO", existingPos);

        List<Candle> candles = createDummyDailyCandles(300, 440.0);
        when(marketDataService.fetchDailyCandles(eq("WIPRO"), anyInt())).thenReturn(candles);

        // Daily RSI plunged to 42.0 (< 45.0 emergency)
        MultiTimeframeRsiSnapshot rsiSnapshot = new MultiTimeframeRsiSnapshot(
                "WIPRO",
                61.0,
                55.0,
                42.0,
                15.0,
                440.0,
                460.0,
                435.0,
                Optional.empty(),
                false,
                false,
                Instant.now()
        );
        when(multiTimeframeRsiService.computeSnapshot(eq("WIPRO"), any())).thenReturn(rsiSnapshot);
        when(executionService.executeExit(any(), anyDouble(), anyString())).thenReturn(true);

        swingService.evaluateMorningPlungeCheck();

        assertThat(swingService.getActivePositions()).doesNotContainKey("WIPRO");
        assertThat(swingService.getState().getClosedPositions()).hasSize(1);
    }

    @Test
    void testPyramidingSkipsSameDayEntry() {
        // Position entered today
        RsiHighwayPosition pos = new RsiHighwayPosition("INFY", "NSE", 1500.0, 1450.0);
        pos.addTranche(new RsiHighwayTranche(1, 10, 1500.0, Instant.now(), "ORD_01"));
        swingService.getState().getPositions().put("INFY", pos);

        when(breadthService.evaluateBreadth(any(), any(), anyInt(), anyDouble()))
                .thenReturn(new MarketBreadthSnapshot(true, 500, 20, List.of(), 0.05, "Healthy", Instant.now()));

        List<Candle> candles = createDummyDailyCandles(300, 1550.0);
        when(marketDataService.fetchDailyCandles(eq("INFY"), anyInt())).thenReturn(candles);

        MultiTimeframeRsiSnapshot snap = new MultiTimeframeRsiSnapshot(
                "INFY", 65.0, 62.0, 52.0, 20.0, 1550.0, 1560.0, 1520.0,
                Optional.of(PriceActionPattern.BULLISH_ENGULFING), true, true, Instant.now()
        );
        when(multiTimeframeRsiService.computeSnapshot(eq("INFY"), any())).thenReturn(snap);

        swingService.evaluateEodScanForSymbols(List.of("INFY"));

        // Since it was entered today, pyramid tranche 2 should NOT be executed
        assertThat(pos.getTrancheCount()).isEqualTo(1);
    }
}
