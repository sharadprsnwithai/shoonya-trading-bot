package com.tradingbot.strategy.rsihighway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.rsihighway.config.RsiHighwayConfig;
import com.tradingbot.strategy.rsihighway.indicator.MultiTimeframeRsiService;
import com.tradingbot.strategy.rsihighway.model.MarketBreadthSnapshot;
import com.tradingbot.strategy.rsihighway.model.MultiTimeframeRsiSnapshot;
import com.tradingbot.strategy.rsihighway.model.PriceActionPattern;
import com.tradingbot.strategy.rsihighway.model.RsiHighwayPosition;
import com.tradingbot.strategy.rsihighway.model.RsiHighwaySignalType;
import com.tradingbot.strategy.rsihighway.service.RsiHighwayExecutionService;
import com.tradingbot.strategy.rsihighway.service.RsiHighwayMarketBreadthService;
import com.tradingbot.strategy.rsihighway.service.RsiHighwaySwingService;
import com.tradingbot.telegram.TelegramService;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RsiHighwayIntegrationTest {

    private ShoonyaMarketDataService marketDataService;
    private MultiTimeframeRsiService multiTimeframeRsiService;
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
        breadthService = mock(RsiHighwayMarketBreadthService.class);
        telegramService = mock(TelegramService.class);

        tempStateFile = Files.createTempFile("rsi_e2e_state", ".json").toFile();
        tempStateFile.deleteOnExit();

        config = new RsiHighwayConfig();
        config.setEnabled(true);
        config.setPaperTrading(true);
        config.setPaperCapital(1000000.0);
        config.setRiskPerTradePercent(1.0);
        config.setMaxCapitalPerStockPercent(10.0);
        config.setStateFilePath(tempStateFile.getAbsolutePath());
        config.setMaxConcurrentPositions(5);

        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        executionService = new RsiHighwayExecutionService(null, config);

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
                    "RELIANCE",
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
    void testFullTradeLifecycle_Entry_Pyramid_Exit() {
        // --- STEP 1: DAY 1 INITIAL ENTRY ---
        when(breadthService.evaluateBreadth(any(), any(), anyInt(), anyDouble()))
                .thenReturn(new MarketBreadthSnapshot(true, 500, 30, List.of("RELIANCE"), 0.02, "Regime Open", Instant.now()));

        List<Candle> day1Candles = createDummyDailyCandles(300, 2800.0);
        when(marketDataService.fetchDailyCandles(eq("RELIANCE"), anyInt())).thenReturn(day1Candles);

        MultiTimeframeRsiSnapshot day1Snap = new MultiTimeframeRsiSnapshot(
                "RELIANCE",
                65.0, // Monthly RSI >= 60
                63.0, // Weekly RSI >= 60
                51.0, // Daily RSI ~ 50 bounce
                40.0, // ATR
                2800.0,
                2820.0,
                2760.0, // low
                Optional.of(PriceActionPattern.BULLISH_ENGULFING),
                true,
                true,
                Instant.now()
        );
        when(multiTimeframeRsiService.computeSnapshot(eq("RELIANCE"), any())).thenReturn(day1Snap);

        // Run Day 1 EOD Scan
        swingService.evaluateEodScanForSymbols(List.of("RELIANCE"));

        assertThat(swingService.getActivePositions()).containsKey("RELIANCE");
        RsiHighwayPosition posDay1 = swingService.getActivePositions().get("RELIANCE");
        assertThat(posDay1.getTrancheCount()).isEqualTo(1);
        assertThat(posDay1.getAveragePrice()).isEqualTo(2800.0);
        int day1Qty = posDay1.getTotalQuantity();
        assertThat(day1Qty).isGreaterThan(0);

        // --- STEP 2: DAY 2 INVERTED PYRAMID (TRANCHE 2) ---
        List<Candle> day2Candles = createDummyDailyCandles(301, 2900.0);
        when(marketDataService.fetchDailyCandles(eq("RELIANCE"), anyInt())).thenReturn(day2Candles);

        MultiTimeframeRsiSnapshot day2Snap = new MultiTimeframeRsiSnapshot(
                "RELIANCE",
                67.0,
                64.0,
                53.0, // Daily RSI bounce again
                42.0,
                2900.0,
                2920.0,
                2880.0, // higher swing low
                Optional.of(PriceActionPattern.MOMENTUM_EXPANSION),
                true,
                true,
                Instant.now()
        );
        when(multiTimeframeRsiService.computeSnapshot(eq("RELIANCE"), any())).thenReturn(day2Snap);

        // Run Day 2 EOD Scan
        swingService.evaluateEodScanForSymbols(List.of("RELIANCE"));

        RsiHighwayPosition posDay2 = swingService.getActivePositions().get("RELIANCE");
        assertThat(posDay2.getTrancheCount()).isEqualTo(2);
        assertThat(posDay2.getTotalQuantity()).isGreaterThan(day1Qty);
        assertThat(posDay2.getAveragePrice()).isGreaterThan(2800.0);
        assertThat(posDay2.getCurrentSlPrice()).isEqualTo(2880.0); // Trailed SL

        // --- STEP 3: DAY 3 DAILY RSI < 50 EXIT ---
        List<Candle> day3Candles = createDummyDailyCandles(302, 2850.0);
        when(marketDataService.fetchDailyCandles(eq("RELIANCE"), anyInt())).thenReturn(day3Candles);

        MultiTimeframeRsiSnapshot day3Snap = new MultiTimeframeRsiSnapshot(
                "RELIANCE",
                64.0,
                61.0,
                48.5, // Daily RSI drops < 50
                45.0,
                2850.0,
                2880.0,
                2840.0,
                Optional.empty(),
                false,
                false,
                Instant.now()
        );
        when(multiTimeframeRsiService.computeSnapshot(eq("RELIANCE"), any())).thenReturn(day3Snap);

        // Run Day 3 EOD Scan
        swingService.evaluateEodScanForSymbols(List.of("RELIANCE"));

        // Position should now be closed
        assertThat(swingService.getActivePositions()).doesNotContainKey("RELIANCE");
        assertThat(swingService.getClosedPositions()).hasSize(1);
        assertThat(swingService.getClosedPositions().get(0).getSymbol()).isEqualTo("RELIANCE");

        // --- STEP 4: PERSISTENCE STATE RELOAD VERIFICATION ---
        RsiHighwaySwingService reloadedService = new RsiHighwaySwingService(
                marketDataService,
                multiTimeframeRsiService,
                breadthService,
                executionService,
                telegramService,
                config,
                objectMapper
        );
        reloadedService.init();

        assertThat(reloadedService.getActivePositions()).isEmpty();
        assertThat(reloadedService.getClosedPositions()).hasSize(1);
        assertThat(reloadedService.getClosedPositions().get(0).getSymbol()).isEqualTo("RELIANCE");
    }
}
