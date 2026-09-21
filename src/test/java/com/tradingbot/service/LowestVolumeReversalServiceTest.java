package com.tradingbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.LowestVolumePaperPosition;
import com.tradingbot.model.strategy.LowestVolumeSetup;
import com.tradingbot.model.strategy.LowestVolumeSetupState;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LowestVolumeReversalServiceTest {

    private ShoonyaMarketDataService marketDataService;
    private TechnicalAnalysisService taService;
    private ShoonyaConfig config;
    private LowestVolumeReversalService service;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @BeforeEach
    void setUp() {
        marketDataService = mock(ShoonyaMarketDataService.class);
        taService = new TechnicalAnalysisService();
        config = mock(ShoonyaConfig.class);

        service = new LowestVolumeReversalService(marketDataService, taService, null, config, null);
    }

    @Test
    @DisplayName("Should skip runCycle before 09:15 IST")
    void testPreMarketOpenSkip() {
        Clock preOpenClock = Clock.fixed(Instant.parse("2026-09-18T03:30:00Z"), IST); // 09:00 IST
        service.setClock(preOpenClock);

        service.runCycle();
        assertThat(service.isUniverseScanCompletedToday()).isFalse();
    }

    @Test
    @DisplayName("5m Candle Sequence baseline and trigger arming on lower volume pullback")
    void testEvaluateCandleSequence() {
        Instant t0 = Instant.parse("2026-09-18T03:45:00Z");
        List<Candle> candles =
                List.of(
                        Candle.of5m(
                                "PVRINOX",
                                t0,
                                BigDecimal.valueOf(100),
                                BigDecimal.valueOf(105),
                                BigDecimal.valueOf(98),
                                BigDecimal.valueOf(102),
                                10000),
                        Candle.of5m(
                                "PVRINOX",
                                t0.plus(5, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(102),
                                BigDecimal.valueOf(103),
                                BigDecimal.valueOf(99),
                                BigDecimal.valueOf(100),
                                8000),
                        Candle.of5m(
                                "PVRINOX",
                                t0.plus(10, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(100),
                                BigDecimal.valueOf(101),
                                BigDecimal.valueOf(97),
                                BigDecimal.valueOf(98),
                                6000),
                        Candle.of5m(
                                "PVRINOX",
                                t0.plus(15, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(98),
                                BigDecimal.valueOf(102),
                                BigDecimal.valueOf(97),
                                BigDecimal.valueOf(101),
                                4500));

        LowestVolumeSetup setup =
                service.evaluateCandleSequence("PVRINOX", LowestVolumeDirection.SHORT, candles);

        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        assertThat(setup.getTriggerPrice()).isEqualByComparingTo("96.95");
        assertThat(setup.getStopLossPrice()).isEqualByComparingTo("102.05");
        assertThat(setup.getTarget1Price()).isEqualByComparingTo("76.55"); // 1:4 RR
    }

    @Test
    @DisplayName("Execute Option Entry creates LowestVolumePaperPosition with ATM strike")
    void testExecuteOptionEntry() {
        LowestVolumeSetup setup = new LowestVolumeSetup("PVRINOX", LowestVolumeDirection.SHORT);
        setup.setTriggerCandle(
                Candle.of5m(
                        "PVRINOX",
                        Instant.now(),
                        BigDecimal.valueOf(98),
                        BigDecimal.valueOf(102),
                        BigDecimal.valueOf(97),
                        BigDecimal.valueOf(101),
                        4500),
                BigDecimal.valueOf(96.95),
                BigDecimal.valueOf(102.05),
                BigDecimal.valueOf(76.55));

        LowestVolumePaperPosition position =
                service.executeOptionEntry("PVRINOX", setup, BigDecimal.valueOf(96.90));

        assertThat(position).isNotNull();
        assertThat(position.getSymbol()).isEqualTo("PVRINOX");
        assertThat(position.getOptionType()).isEqualTo("PE");
        assertThat(position.getStockEntryPrice()).isEqualByComparingTo("96.90");
        assertThat(position.getCurrentStockSl()).isEqualByComparingTo("102.05");
        assertThat(position.getTarget1StockPrice()).isEqualByComparingTo("76.55");
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.IN_POSITION);
        assertThat(setup.getTradeAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("Partial profit booking at 1:4 RR spot target and moving SL to cost")
    void testPartialExitAndCostSl() {
        LowestVolumePaperPosition pos =
                new LowestVolumePaperPosition(
                        "LVR-1",
                        "PVRINOX",
                        "PE",
                        "PVRINOX ATM 100PE",
                        BigDecimal.valueOf(100),
                        200,
                        2, // 2 lots = 400 qty
                        LowestVolumeDirection.SHORT,
                        BigDecimal.valueOf(5.0),
                        BigDecimal.valueOf(96.90),
                        BigDecimal.valueOf(102.05),
                        BigDecimal.valueOf(76.55),
                        400,
                        BigDecimal.valueOf(2060.0),
                        Instant.now());

        service.getOpenPositions().put("PVRINOX", pos);

        // When 1:4 Target is reached in spot price
        pos.executePartialBook(BigDecimal.valueOf(12.0), Instant.now());

        assertThat(pos.isPartialBooked()).isTrue();
        assertThat(pos.getRemainingQuantity()).isEqualTo(200); // 1 lot remaining
        assertThat(pos.getPartialPnl()).isEqualByComparingTo("1400.00"); // (12 - 5) * 200
        assertThat(pos.getCurrentStockSl())
                .isEqualByComparingTo("96.90"); // SL moved to Cost/Entry spot price
    }

    @Test
    @DisplayName("Runner 10 EMA Trailing Exit closes remaining lots on EMA breach")
    void testRunner10EmaTrailingExit() {
        LowestVolumePaperPosition pos =
                new LowestVolumePaperPosition(
                        "LVR-1",
                        "RELIANCE",
                        "PE",
                        "RELIANCE ATM 2800PE",
                        BigDecimal.valueOf(2800),
                        250,
                        2,
                        LowestVolumeDirection.SHORT,
                        BigDecimal.valueOf(50.0),
                        BigDecimal.valueOf(2820.0),
                        BigDecimal.valueOf(2840.0),
                        BigDecimal.valueOf(2740.0),
                        500,
                        BigDecimal.valueOf(25000.0),
                        Instant.now());

        // Simulate 1:4 partial book already executed
        pos.executePartialBook(BigDecimal.valueOf(80.0), Instant.now());
        service.getOpenPositions().put("RELIANCE", pos);

        LowestVolumeSetup setup = new LowestVolumeSetup("RELIANCE", LowestVolumeDirection.SHORT);
        setup.transitionTo(LowestVolumeSetupState.PARTIAL_BOOKED, "1:4 booked");
        service.getActiveSetups().put("RELIANCE", setup);

        // Spot LTP is 2750 (still below entry 2820, so cost SL is not breached)
        // But 5m candle closes at 2760, above the 10 EMA (which is around 2750)
        Instant t0 = Instant.now().minus(60, ChronoUnit.MINUTES);
        List<Candle> candles = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            candles.add(
                    Candle.of5m(
                            "RELIANCE",
                            t0.plus(i * 5, ChronoUnit.MINUTES),
                            BigDecimal.valueOf(2760 - i),
                            BigDecimal.valueOf(2765 - i),
                            BigDecimal.valueOf(2745 - i),
                            BigDecimal.valueOf(2750), // steady closes around 2750
                            5000));
        }
        // Latest candle surges and closes at 2780 (well above 10 EMA of 2750)
        candles.add(
                Candle.of5m(
                        "RELIANCE",
                        t0.plus(15 * 5, ChronoUnit.MINUTES),
                        BigDecimal.valueOf(2755),
                        BigDecimal.valueOf(2785),
                        BigDecimal.valueOf(2750),
                        BigDecimal.valueOf(2780),
                        8000));

        when(marketDataService.fetch5MinCandles("RELIANCE", 20)).thenReturn(candles);
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .createObjectNode()
                                .put("lp", "2780.0")
                                .put("c", "2800.0"));
        when(marketDataService.resolveToken("RELIANCE")).thenReturn("2885");

        service.evaluateOpenPositions(LocalTime.of(11, 30));

        assertThat(service.getOpenPositions()).doesNotContainKey("RELIANCE");
        assertThat(service.getTradeHistory()).hasSize(1);
        assertThat(service.getTradeHistory().get(0).getExitReason()).isEqualTo("10_EMA_TRAIL_EXIT");
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.CLOSED_TRAIL_EXIT);
    }

    @Test
    @DisplayName("Hard EOD Square-Off at 15:15 IST closes all open positions")
    void testHardEodExit() {
        LowestVolumePaperPosition pos =
                new LowestVolumePaperPosition(
                        "LVR-1",
                        "SUNPHARMA",
                        "CE",
                        "SUNPHARMA ATM 500CE",
                        BigDecimal.valueOf(500),
                        350,
                        2,
                        LowestVolumeDirection.LONG,
                        BigDecimal.valueOf(15.0),
                        BigDecimal.valueOf(516.0),
                        BigDecimal.valueOf(508.0),
                        BigDecimal.valueOf(548.0),
                        700,
                        BigDecimal.valueOf(5600.0),
                        Instant.now());

        service.getOpenPositions().put("SUNPHARMA", pos);
        assertThat(service.getOpenPositions()).hasSize(1);

        service.executeHardExit(LocalTime.of(15, 15));

        assertThat(service.getOpenPositions()).isEmpty();
        assertThat(service.getTradeHistory()).hasSize(1);
        assertThat(service.getTradeHistory().get(0).getExitReason())
                .isEqualTo("EOD_1515_HARD_EXIT");
    }

    @Test
    @DisplayName("Morning Universe Scan successfully resolves symbols and populates sector state")
    void testRunMorningUniverseScan() {
        when(marketDataService.resolveToken(any())).thenReturn("1234");
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        mapper.createObjectNode()
                                .put("lp", "105.0")
                                .put("c", "100.0")
                                .put("o", "101.0"));

        service.runMorningUniverseScan();

        assertThat(service.isUniverseScanCompletedToday()).isTrue();
        assertThat(service.getSectorState()).isNotNull();
    }
}
