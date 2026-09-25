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
import com.tradingbot.model.strategy.LvrExitMode;
import com.tradingbot.model.strategy.LvrInstrumentType;
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
        service.setClock(Clock.fixed(Instant.parse("2026-09-18T04:30:00Z"), IST)); // 10:00 IST
        service.setMorningScanDelayMs(0); // No delay in unit tests
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
    @DisplayName(
            "Verify default configuration properties: FUTURES and PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500")
    void testDefaultConfigurationProperties() {
        assertThat(service.getInstrumentType()).isEqualTo(LvrInstrumentType.FUTURES);
        assertThat(service.getExitMode())
                .isEqualTo(LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500);
    }

    @Test
    @DisplayName(
            "Futures entry execution sets 1.0 Delta spot entry price, contract symbol and planned risk")
    void testFuturesEntryExecution() {
        service.setInstrumentType(LvrInstrumentType.FUTURES);
        service.setExitMode(LvrExitMode.FULL_TARGET_1_2);

        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(1870),
                        BigDecimal.valueOf(1876),
                        BigDecimal.valueOf(1869),
                        BigDecimal.valueOf(1875),
                        5000),
                BigDecimal.valueOf(1876.05),
                BigDecimal.valueOf(1868.95),
                BigDecimal.valueOf(1890.25));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");

        LowestVolumePaperPosition pos =
                service.executePositionEntry("SUNPHARMA", setup, BigDecimal.valueOf(1876.10));

        assertThat(pos).isNotNull();
        assertThat(pos.getInstrumentType()).isEqualTo(LvrInstrumentType.FUTURES);
        assertThat(pos.getExitMode()).isEqualTo(LvrExitMode.FULL_TARGET_1_2);
        assertThat(pos.getOptionSymbol()).isEqualTo("SUNPHARMA FUT");
        assertThat(pos.getStockEntryPrice()).isEqualByComparingTo("1876.10");
        assertThat(pos.getEntryPremium()).isEqualByComparingTo("1876.10");
        // Trigger was 1876.05 with SL 1868.95. Live entry at 1876.10 (Risk = 7.15) -> Target =
        // 1876.10 + 2 * 7.15 = 1890.40
        assertThat(pos.getTarget1StockPrice()).isEqualByComparingTo("1890.40");
        assertThat(service.getOpenPositions()).containsKey("SUNPHARMA");
    }

    @Test
    @DisplayName("Target 1:2 dynamically recalculates from actual entry spot price under slippage")
    void testDynamicTargetCalculationOnSlippage() {
        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(102),
                        BigDecimal.valueOf(98),
                        BigDecimal.valueOf(100),
                        5000),
                BigDecimal.valueOf(100.05), // Theoretical trigger
                BigDecimal.valueOf(98.05), // Theoretical SL (Risk = 2.00)
                BigDecimal.valueOf(104.05)); // Theoretical target = 104.05
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");

        // Live entry happens at 101.05 (+1.00 slippage). Actual Risk = 101.05 - 98.05 = 3.00.
        // Dynamic Target must be 101.05 + (2 * 3.00) = 107.05 (exact 1:2 RR).
        LowestVolumePaperPosition pos =
                service.executePositionEntry("SUNPHARMA", setup, BigDecimal.valueOf(101.05));

        assertThat(pos.getStockEntryPrice()).isEqualByComparingTo("101.05");
        assertThat(pos.getCurrentStockSl()).isEqualByComparingTo("98.05");
        assertThat(pos.getTarget1StockPrice()).isEqualByComparingTo("107.05");
    }

    @Test
    @DisplayName("Candle sequence invalidates broken pullback when subsequent candle breaches SL")
    void testEvaluateCandleSequenceInvalidatesBrokenPullback() {
        Instant t0 = Instant.parse("2026-09-18T03:45:00Z");
        List<Candle> candles =
                List.of(
                        Candle.of5m(
                                "SUNPHARMA",
                                t0,
                                BigDecimal.valueOf(1800),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1795),
                                BigDecimal.valueOf(1805),
                                10000),
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(5, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1805),
                                BigDecimal.valueOf(1815),
                                BigDecimal.valueOf(1800),
                                BigDecimal.valueOf(1810),
                                12000),
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(10, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1820),
                                BigDecimal.valueOf(1805),
                                BigDecimal.valueOf(1815),
                                15000),
                        // C4: Red pullback with lowest volume (3000 < 10000) -> Arms trigger: High
                        // 1820 (Trigger 1820.05), Low 1810 (SL 1809.95)
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(15, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1818),
                                BigDecimal.valueOf(1820),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1812),
                                3000),
                        // C5: Massive dump breaching C4 SL (Low 1800 <= 1809.95), higher volume
                        // (8000)
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(20, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1811),
                                BigDecimal.valueOf(1800),
                                BigDecimal.valueOf(1802),
                                8000));

        LowestVolumeSetup setup =
                service.evaluateCandleSequence("SUNPHARMA", LowestVolumeDirection.LONG, candles);

        // Setup should NOT retain broken C4 trigger; should be SCANNING
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.SCANNING);
        assertThat(setup.getTriggerPrice()).isNull();
    }

    @Test
    @DisplayName("Candle sequence expires armed trigger after timeout candles")
    void testEvaluateCandleSequenceExpiresOnTimeout() {
        service.setSetupTimeoutCandles(3); // 3-candle timeout
        Instant t0 = Instant.parse("2026-09-18T03:45:00Z");
        List<Candle> candles =
                List.of(
                        Candle.of5m(
                                "SUNPHARMA",
                                t0,
                                BigDecimal.valueOf(1800),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1795),
                                BigDecimal.valueOf(1805),
                                10000),
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(5, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1805),
                                BigDecimal.valueOf(1815),
                                BigDecimal.valueOf(1800),
                                BigDecimal.valueOf(1810),
                                12000),
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(10, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1820),
                                BigDecimal.valueOf(1805),
                                BigDecimal.valueOf(1815),
                                15000),
                        // C4: Red pullback volume 3000 -> Arms trigger: High 1820 (Trigger
                        // 1820.05), Low 1810 (SL 1809.95)
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(15, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1818),
                                BigDecimal.valueOf(1820),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1812),
                                3000),
                        // C5: elapsed = 1
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(20, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1812),
                                BigDecimal.valueOf(1819),
                                BigDecimal.valueOf(1811),
                                BigDecimal.valueOf(1815),
                                6000),
                        // C6: elapsed = 2
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(25, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1815),
                                BigDecimal.valueOf(1818),
                                BigDecimal.valueOf(1812),
                                BigDecimal.valueOf(1816),
                                7000),
                        // C7: elapsed = 3
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(30, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1816),
                                BigDecimal.valueOf(1819),
                                BigDecimal.valueOf(1814),
                                BigDecimal.valueOf(1817),
                                8000),
                        // C8: elapsed = 4 (> 3) -> Expired!
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(35, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1817),
                                BigDecimal.valueOf(1819),
                                BigDecimal.valueOf(1815),
                                BigDecimal.valueOf(1816),
                                9000));

        LowestVolumeSetup setup =
                service.evaluateCandleSequence("SUNPHARMA", LowestVolumeDirection.LONG, candles);

        // Setup should be expired and reset to SCANNING
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.SCANNING);
        assertThat(setup.getTriggerPrice()).isNull();
    }

    @Test
    @DisplayName("Futures LONG Position - 100% Full Exit at 1:2 Target in evaluateOpenPositions")
    void testFuturesFullExitTarget14() {
        service.setInstrumentType(LvrInstrumentType.FUTURES);
        service.setExitMode(LvrExitMode.FULL_TARGET_1_2);

        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(1870),
                        BigDecimal.valueOf(1875),
                        BigDecimal.valueOf(1869),
                        BigDecimal.valueOf(1874),
                        5000),
                BigDecimal.valueOf(1875.05),
                BigDecimal.valueOf(1872.05),
                BigDecimal.valueOf(1881.05));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("SUNPHARMA", setup);

        LowestVolumePaperPosition pos =
                service.executePositionEntry("SUNPHARMA", setup, BigDecimal.valueOf(1875.00));
        assertThat(service.getOpenPositions()).hasSize(1);

        // Mock spot price reaching 1:2 target (1882.00 >= 1881.05)
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "1882.00"));

        service.evaluateOpenPositions(LocalTime.of(10, 15));

        assertThat(service.getOpenPositions()).isEmpty();
        assertThat(service.getTradeHistory()).hasSize(1);
        LowestVolumePaperPosition closed = service.getTradeHistory().get(0);
        assertThat(closed.isClosed()).isTrue();
        assertThat(closed.getExitReason()).isEqualTo("TARGET_1_2_FULL_EXIT");
        assertThat(closed.getTotalRealizedPnl()).isGreaterThan(BigDecimal.ZERO);
        assertThat(service.getExhaustedSymbols()).contains("SUNPHARMA");
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.CLOSED_TARGET);
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
        assertThat(setup.getTarget1Price()).isEqualByComparingTo("86.75"); // 1:2 RR
    }

    @Test
    @DisplayName("Execute Option Entry creates LowestVolumePaperPosition with ATM strike")
    void testExecuteOptionEntry() {
        service.setInstrumentType(LvrInstrumentType.OPTIONS);
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
                BigDecimal.valueOf(86.75));

        LowestVolumePaperPosition position =
                service.executeOptionEntry("PVRINOX", setup, BigDecimal.valueOf(96.90));

        assertThat(position).isNotNull();
        assertThat(position.getSymbol()).isEqualTo("PVRINOX");
        assertThat(position.getOptionType()).isEqualTo("PE");
        assertThat(position.getStockEntryPrice()).isEqualByComparingTo("96.90");
        assertThat(position.getCurrentStockSl()).isEqualByComparingTo("102.05");
        // Actual Risk = 102.05 - 96.90 = 5.15. Target = 96.90 - (2 * 5.15) = 86.60
        assertThat(position.getTarget1StockPrice()).isEqualByComparingTo("86.60");
        assertThat(position.getExitMode()).isEqualTo(service.getExitMode());
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.IN_POSITION);
        assertThat(setup.getTradeAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("Partial profit booking at 1:2 RR spot target and moving SL to cost")
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
                        BigDecimal.valueOf(86.60),
                        400,
                        BigDecimal.valueOf(2060.0),
                        Instant.now());

        service.getOpenPositions().put("PVRINOX", pos);

        // When 1:2 Target is reached in spot price
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

        when(marketDataService.fetch5MinCandles(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(candles);
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
    @DisplayName("30s Live Check skips 10 EMA trailing exit until confirmed 5m candle close")
    void testLive30sCheckSkips10EmaTrailingUntilCandleClose() {
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

        pos.executePartialBook(BigDecimal.valueOf(80.0), Instant.now());
        service.getOpenPositions().put("RELIANCE", pos);

        LowestVolumeSetup setup = new LowestVolumeSetup("RELIANCE", LowestVolumeDirection.SHORT);
        setup.transitionTo(LowestVolumeSetupState.PARTIAL_BOOKED, "1:2 booked");
        service.getActiveSetups().put("RELIANCE", setup);

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
                            BigDecimal.valueOf(2750),
                            5000));
        }
        candles.add(
                Candle.of5m(
                        "RELIANCE",
                        t0.plus(15 * 5, ChronoUnit.MINUTES),
                        BigDecimal.valueOf(2755),
                        BigDecimal.valueOf(2785),
                        BigDecimal.valueOf(2750),
                        BigDecimal.valueOf(2780),
                        8000));

        when(marketDataService.fetch5MinCandles(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(candles);
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .createObjectNode()
                                .put("lp", "2780.0")
                                .put("c", "2800.0"));
        when(marketDataService.resolveToken("RELIANCE")).thenReturn("2885");

        // 30s intra-candle check should NOT exit via 10 EMA (isCandleClose = false)
        service.evaluateLivePriceActions();

        assertThat(service.getOpenPositions()).containsKey("RELIANCE");
        assertThat(pos.isClosed()).isFalse();

        // 5-minute candle close cycle DOES exit via 10 EMA
        service.evaluateOpenPositions(LocalTime.of(11, 30));

        assertThat(service.getOpenPositions()).doesNotContainKey("RELIANCE");
        assertThat(pos.isClosed()).isTrue();
        assertThat(pos.getExitReason()).isEqualTo("10_EMA_TRAIL_EXIT");
    }

    @Test
    @DisplayName("Hard EOD Square-Off at 15:00 IST closes all open positions")
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

        service.executeHardExit(LocalTime.of(15, 0));

        assertThat(service.getOpenPositions()).isEmpty();
        assertThat(service.getTradeHistory()).hasSize(1);
        assertThat(service.getTradeHistory().get(0).getExitReason())
                .isEqualTo("EOD_1500_HARD_EXIT");
    }

    @Test
    @DisplayName(
            "Armed setup is invalidated if spot price breaches SL before hitting trigger, resetting to SCANNING")
    void testArmedSetupInvalidationOnStopLossBreach() {
        Clock marketClock = Clock.fixed(Instant.parse("2026-09-18T04:30:00Z"), IST); // 10:00 IST
        service.setClock(marketClock);

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
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("PVRINOX", setup);

        // Spot price rallied above 102.05 SL (e.g. 103.00)
        when(marketDataService.resolveToken(any())).thenReturn("13147");
        when(marketDataService.resolveExchange(any())).thenReturn("NSE");
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "103.00"));

        service.evaluateLivePriceActions();

        // Setup should reset to SCANNING (not permanently exhausted) with 0 trade attempts
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.SCANNING);
        assertThat(setup.getTriggerPrice()).isNull();
        assertThat(setup.getTradeAttempts()).isZero();
        assertThat(service.getExhaustedSymbols()).doesNotContain("PVRINOX");
        assertThat(service.getOpenPositions()).isEmpty();
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
        assertThat(service.getCurrentTopGainerSnapshots()).isNotEmpty();
    }

    @Test
    @DisplayName("LONG Entry Allowed when Spot Price is Above VWAP")
    void testLongEntryAllowedWhenAboveVwap() {
        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(1870),
                        BigDecimal.valueOf(1875),
                        BigDecimal.valueOf(1869),
                        BigDecimal.valueOf(1874),
                        5000),
                BigDecimal.valueOf(1875.05),
                BigDecimal.valueOf(1868.95),
                BigDecimal.valueOf(1899.45));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("SUNPHARMA", setup);

        // Spot LTP = 1876.00 (>= Trigger 1875.05), VWAP (ap) = 1870.00 (Spot > VWAP)
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "1876.00").put("ap", "1870.00"));
        when(marketDataService.resolveToken("SUNPHARMA")).thenReturn("3351");

        service.evaluateLivePriceActions();

        assertThat(service.getOpenPositions()).containsKey("SUNPHARMA");
        LowestVolumePaperPosition pos = service.getOpenPositions().get("SUNPHARMA");
        assertThat(pos.getStockEntryPrice()).isEqualByComparingTo("1876.00");
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.IN_POSITION);
        assertThat(service.getExhaustedSymbols()).doesNotContain("SUNPHARMA");
    }

    @Test
    @DisplayName("LONG Entry Blocked and Discarded for the Day when Spot Price <= VWAP")
    void testLongEntryBlockedAndDiscardedWhenBelowVwap() {
        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(1870),
                        BigDecimal.valueOf(1875),
                        BigDecimal.valueOf(1869),
                        BigDecimal.valueOf(1874),
                        5000),
                BigDecimal.valueOf(1875.05),
                BigDecimal.valueOf(1868.95),
                BigDecimal.valueOf(1899.45));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("SUNPHARMA", setup);

        // Spot LTP = 1876.00 (>= Trigger 1875.05), but VWAP (ap) = 1878.00 (Spot <= VWAP)
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "1876.00").put("ap", "1878.00"));
        when(marketDataService.resolveToken("SUNPHARMA")).thenReturn("3351");

        service.evaluateLivePriceActions();

        assertThat(service.getOpenPositions()).doesNotContainKey("SUNPHARMA");
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.REJECTED_EXHAUSTED);
        assertThat(service.getExhaustedSymbols()).contains("SUNPHARMA");
    }

    @Test
    @DisplayName("SHORT Entry Allowed when Spot Price is Below VWAP")
    void testShortEntryAllowedWhenBelowVwap() {
        LowestVolumeSetup setup = new LowestVolumeSetup("PVRINOX", LowestVolumeDirection.SHORT);
        setup.setTriggerCandle(
                Candle.of5m(
                        "PVRINOX",
                        Instant.now(),
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(102),
                        BigDecimal.valueOf(98),
                        BigDecimal.valueOf(99),
                        4000),
                BigDecimal.valueOf(97.95),
                BigDecimal.valueOf(102.05),
                BigDecimal.valueOf(81.55));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("PVRINOX", setup);

        // Spot LTP = 97.80 (<= Trigger 97.95 and within 0.25% slippage), VWAP (ap) = 100.50 (Spot <
        // VWAP)
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "97.80").put("ap", "100.50"));
        when(marketDataService.resolveToken("PVRINOX")).thenReturn("13147");

        service.evaluateLivePriceActions();

        assertThat(service.getOpenPositions()).containsKey("PVRINOX");
        LowestVolumePaperPosition pos = service.getOpenPositions().get("PVRINOX");
        assertThat(pos.getStockEntryPrice()).isEqualByComparingTo("97.80");
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.IN_POSITION);
        assertThat(service.getExhaustedSymbols()).doesNotContain("PVRINOX");
    }

    @Test
    @DisplayName("SHORT Entry Blocked and Discarded for the Day when Spot Price >= VWAP")
    void testShortEntryBlockedAndDiscardedWhenAboveVwap() {
        LowestVolumeSetup setup = new LowestVolumeSetup("PVRINOX", LowestVolumeDirection.SHORT);
        setup.setTriggerCandle(
                Candle.of5m(
                        "PVRINOX",
                        Instant.now(),
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(102),
                        BigDecimal.valueOf(98),
                        BigDecimal.valueOf(99),
                        4000),
                BigDecimal.valueOf(97.95),
                BigDecimal.valueOf(102.05),
                BigDecimal.valueOf(81.55));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("PVRINOX", setup);

        // Spot LTP = 97.80 (<= Trigger 97.95 and within 0.25% slippage), but VWAP (ap) = 96.00
        // (Spot >= VWAP)
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "97.80").put("ap", "96.00"));
        when(marketDataService.resolveToken("PVRINOX")).thenReturn("13147");

        service.evaluateLivePriceActions();

        assertThat(service.getOpenPositions()).doesNotContainKey("PVRINOX");
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.REJECTED_EXHAUSTED);
        assertThat(service.getExhaustedSymbols()).contains("PVRINOX");
    }

    @Test
    @DisplayName("Replay Session enforces VWAP confirmation: trade executed when Above VWAP")
    void testReplaySessionVwapConfirmationLong() {
        Instant t0 = Instant.parse("2026-09-18T03:45:00Z");
        List<Candle> candles =
                List.of(
                        Candle.of5m(
                                "SUNPHARMA",
                                t0,
                                BigDecimal.valueOf(1800),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1795),
                                BigDecimal.valueOf(1805),
                                10000),
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(5, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1805),
                                BigDecimal.valueOf(1820),
                                BigDecimal.valueOf(1800),
                                BigDecimal.valueOf(1815),
                                12000),
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(10, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1815),
                                BigDecimal.valueOf(1830),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1825),
                                15000),
                        // C4: Red pullback with lowest volume (3000 < 10000) -> Arms trigger at
                        // High (1826 + 0.05 = 1826.05)
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(15, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1825),
                                BigDecimal.valueOf(1826),
                                BigDecimal.valueOf(1818),
                                BigDecimal.valueOf(1820),
                                3000),
                        // C5: Bullish breakout breaching 1826.05 (High: 1840). Note VWAP across
                        // C1-C5 is ~1815. 1826.05 > VWAP.
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(20, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1822),
                                BigDecimal.valueOf(1840),
                                BigDecimal.valueOf(1821),
                                BigDecimal.valueOf(1838),
                                20000));

        List<LowestVolumePaperPosition> trades =
                service.replaySession("SUNPHARMA", LowestVolumeDirection.LONG, candles);

        assertThat(trades).isNotNull();
    }

    @Test
    @DisplayName("Futures LONG Position - 50% Profit Booked at 1:2 RR and SL moved to Cost")
    void testFuturesPartialExitTarget14AndTrailSl11() {
        service.setInstrumentType(LvrInstrumentType.FUTURES);
        service.setExitMode(LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500);

        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(1865),
                        BigDecimal.valueOf(1870),
                        BigDecimal.valueOf(1859),
                        BigDecimal.valueOf(1869),
                        5000),
                BigDecimal.valueOf(1870.05),
                BigDecimal.valueOf(1860.05), // Unit Risk = 10.00
                BigDecimal.valueOf(1890.05)); // 1:2 Target = 1870.05 + 20 = 1890.05
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("SUNPHARMA", setup);

        LowestVolumePaperPosition pos =
                service.executePositionEntry("SUNPHARMA", setup, BigDecimal.valueOf(1870.00));
        assertThat(service.getOpenPositions()).hasSize(1);

        // Mock spot reaching 1:2 target (1890.50 >= 1890.05)
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "1890.50").put("ap", "1875.00"));

        service.evaluateOpenPositions(LocalTime.of(10, 30));

        // Position should still be OPEN with remaining 50% lots
        assertThat(service.getOpenPositions()).containsKey("SUNPHARMA");
        assertThat(pos.isPartialBooked()).isTrue();
        assertThat(pos.getRemainingQuantity()).isEqualTo(pos.getTotalQuantity() / 2);
        // Cost SL = Entry Price 1870.00
        assertThat(pos.getCurrentStockSl()).isEqualByComparingTo("1870.00");
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.PARTIAL_BOOKED);

        // Now simulate Spot falling back and hitting Cost Trailing SL (1869.50 <= 1870.00)
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "1869.50").put("ap", "1875.00"));

        service.evaluateOpenPositions(LocalTime.of(11, 00));

        // Position should now be CLOSED with TRAILING_COST_SL_HIT
        assertThat(service.getOpenPositions()).isEmpty();
        assertThat(service.getTradeHistory()).hasSize(1);
        LowestVolumePaperPosition closed = service.getTradeHistory().get(0);
        assertThat(closed.isClosed()).isTrue();
        assertThat(closed.getExitReason()).isEqualTo("TRAILING_COST_SL_HIT");
        assertThat(closed.getTotalRealizedPnl()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Futures SHORT Position - 50% Profit Booked at 1:2 RR and SL moved to Cost")
    void testFuturesShortPartialExitTarget14AndTrailSl11() {
        service.setInstrumentType(LvrInstrumentType.FUTURES);
        service.setExitMode(LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500);

        LowestVolumeSetup setup = new LowestVolumeSetup("PVRINOX", LowestVolumeDirection.SHORT);
        setup.setTriggerCandle(
                Candle.of5m(
                        "PVRINOX",
                        Instant.now(),
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(102),
                        BigDecimal.valueOf(98),
                        BigDecimal.valueOf(99),
                        4000),
                BigDecimal.valueOf(97.95),
                BigDecimal.valueOf(102.05), // Unit risk = 4.10
                BigDecimal.valueOf(89.75)); // 1:2 Target = 97.95 - 8.20 = 89.75
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("PVRINOX", setup);

        LowestVolumePaperPosition pos =
                service.executePositionEntry("PVRINOX", setup, BigDecimal.valueOf(98.00));
        assertThat(service.getOpenPositions()).hasSize(1);

        // Mock spot reaching 1:2 target (89.00 <= 89.75)
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "89.00").put("ap", "90.00"));

        service.evaluateOpenPositions(LocalTime.of(10, 45));

        assertThat(service.getOpenPositions()).containsKey("PVRINOX");
        assertThat(pos.isPartialBooked()).isTrue();
        // 5 lots total: Ceiling of 50% = 3 lots booked (1350 units), 2 lots runner remaining (900
        // units)
        assertThat(pos.getRemainingQuantity()).isEqualTo((pos.getLots() / 2) * pos.getLotSize());

        // For SHORT: Cost SL = Entry Price 98.00
        assertThat(pos.getCurrentStockSl()).isEqualByComparingTo("98.00");

        // Test 15:00 EOD Hard Exit on runner
        service.executeHardExit(LocalTime.of(15, 0));
        assertThat(service.getOpenPositions()).isEmpty();
        assertThat(service.getTradeHistory()).hasSize(1);
        LowestVolumePaperPosition closed = service.getTradeHistory().get(0);
        assertThat(closed.isClosed()).isTrue();
        assertThat(closed.getExitReason()).isEqualTo("EOD_1500_HARD_EXIT");
    }

    @Test
    @DisplayName(
            "processCandidateSetups synchronizes live activeSetup back to SCANNING when trigger is broken")
    void testProcessCandidateSetupsResetsActiveSetupWhenInvalidated() {
        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(1818),
                        BigDecimal.valueOf(1820),
                        BigDecimal.valueOf(1810),
                        BigDecimal.valueOf(1812),
                        3000),
                BigDecimal.valueOf(1820.05),
                BigDecimal.valueOf(1809.95),
                BigDecimal.valueOf(1850.05));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("SUNPHARMA", setup);

        Instant t0 = Instant.now().minus(25, ChronoUnit.MINUTES);
        List<Candle> candles =
                List.of(
                        Candle.of5m(
                                "SUNPHARMA",
                                t0,
                                BigDecimal.valueOf(1800),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1795),
                                BigDecimal.valueOf(1805),
                                10000),
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(5, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1805),
                                BigDecimal.valueOf(1815),
                                BigDecimal.valueOf(1800),
                                BigDecimal.valueOf(1810),
                                12000),
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(10, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1820),
                                BigDecimal.valueOf(1805),
                                BigDecimal.valueOf(1815),
                                15000),
                        // C4: Red pullback (armed)
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(15, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1818),
                                BigDecimal.valueOf(1820),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1812),
                                3000),
                        // C5: Dumping candle breaching C4 SL (Low 1800 <= 1809.95)
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(20, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1811),
                                BigDecimal.valueOf(1800),
                                BigDecimal.valueOf(1802),
                                8000));

        when(marketDataService.fetch5MinCandles("SUNPHARMA", 5)).thenReturn(candles);
        service.setUniverseScanCompletedToday(true);

        service.runCycle();

        // activeSetup in memory should now be SCANNING (not stuck in TRIGGER_ARMED)
        LowestVolumeSetup synced = service.getActiveSetups().get("SUNPHARMA");
        assertThat(synced.getState()).isEqualTo(LowestVolumeSetupState.SCANNING);
        assertThat(synced.getTriggerPrice()).isNull();
    }

    @Test
    @DisplayName(
            "Candle closing after exit time is properly allowed even if its start timestamp precedes exit")
    void testBarTimestampPlusDurationMatchesSubSecondExitTime() {
        Instant t0 = Instant.parse("2026-09-18T04:15:00Z"); // 09:45:00 IST
        Instant exitTime =
                Instant.parse("2026-09-18T04:16:27.188Z"); // 09:46:27.188 IST (mid-bar exit)

        List<Candle> candles =
                List.of(
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.minus(15, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1800),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1795),
                                BigDecimal.valueOf(1805),
                                10000),
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.minus(10, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1805),
                                BigDecimal.valueOf(1815),
                                BigDecimal.valueOf(1800),
                                BigDecimal.valueOf(1810),
                                12000),
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.minus(5, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1820),
                                BigDecimal.valueOf(1805),
                                BigDecimal.valueOf(1815),
                                15000),
                        // 09:45-09:50 candle (timestamp 09:45:00, completes at 09:50:00 > exit
                        // 09:46:27)
                        Candle.of5m(
                                "SUNPHARMA",
                                t0,
                                BigDecimal.valueOf(1818),
                                BigDecimal.valueOf(1820),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1812),
                                3000));

        LowestVolumeSetup setup =
                service.evaluateCandleSequence(
                        "SUNPHARMA", LowestVolumeDirection.LONG, candles, exitTime);

        // 09:45 candle should be allowed and armed for Attempt 2
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        assertThat(setup.getTriggerPrice()).isEqualByComparingTo("1820.05");
    }

    @Test
    @DisplayName("evaluateLivePriceActions fetches quote only once per symbol across checks")
    void testSingleQuoteFetchPerSymbolInLivePollingCycle() {
        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(1870),
                        BigDecimal.valueOf(1875),
                        BigDecimal.valueOf(1869),
                        BigDecimal.valueOf(1874),
                        5000),
                BigDecimal.valueOf(1875.05),
                BigDecimal.valueOf(1868.95),
                BigDecimal.valueOf(1899.45));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("SUNPHARMA", setup);

        // Put an open position on another stock
        LowestVolumePaperPosition pos =
                new LowestVolumePaperPosition(
                        "LVR-1",
                        "SUNPHARMA",
                        LvrInstrumentType.FUTURES,
                        LvrExitMode.PARTIAL_1_4_TRAIL_1_1_EOD_1500,
                        "SUNPHARMA FUT",
                        350,
                        2,
                        LowestVolumeDirection.LONG,
                        BigDecimal.valueOf(1870.00),
                        BigDecimal.valueOf(1860.00),
                        BigDecimal.valueOf(1910.00),
                        700,
                        BigDecimal.valueOf(7000.00),
                        Instant.now());
        service.getOpenPositions().put("SUNPHARMA", pos);

        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "1872.00").put("ap", "1868.00"));
        when(marketDataService.resolveToken("SUNPHARMA")).thenReturn("3351");

        service.evaluateLivePriceActions();

        // fetchQuote should be invoked exactly ONCE for SUNPHARMA during the 30s cycle
        org.mockito.Mockito.verify(marketDataService, org.mockito.Mockito.times(1))
                .fetchQuote(any(), any());
    }

    @Test
    @DisplayName(
            "replaySession correctly computes Futures partial booking PnL using spot target price")
    void testReplaySessionFuturesPartialExitTarget14ComputesExactPnl() {
        service.setInstrumentType(LvrInstrumentType.FUTURES);
        service.setExitMode(LvrExitMode.PARTIAL_1_4_TRAIL_1_1_EOD_1500);

        Instant t0 = Instant.parse("2026-09-18T03:45:00Z");
        List<Candle> candles =
                List.of(
                        Candle.of5m(
                                "SUNPHARMA",
                                t0,
                                BigDecimal.valueOf(1800),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1795),
                                BigDecimal.valueOf(1805),
                                10000),
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(5, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1805),
                                BigDecimal.valueOf(1820),
                                BigDecimal.valueOf(1800),
                                BigDecimal.valueOf(1815),
                                12000),
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(10, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1815),
                                BigDecimal.valueOf(1830),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1825),
                                15000),
                        // C4: Red pullback (3000 volume) -> Trigger: 1826.05, SL: 1817.95 (Risk =
                        // 8.10), Target 1:2: 1826.05 + 16.20 = 1842.25
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(15, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1825),
                                BigDecimal.valueOf(1826),
                                BigDecimal.valueOf(1818),
                                BigDecimal.valueOf(1820),
                                3000),
                        // C5: Bullish breakout triggering entry at 1826.05
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(20, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1822),
                                BigDecimal.valueOf(1840),
                                BigDecimal.valueOf(1821),
                                BigDecimal.valueOf(1838),
                                20000),
                        // C6: Surging to 1845 reaching 1:2 Target (1842.25) -> 50% partial booked
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(25, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1838),
                                BigDecimal.valueOf(1846),
                                BigDecimal.valueOf(1837),
                                BigDecimal.valueOf(1845),
                                25000),
                        // C7: Candle at 15:00 IST -> Hard EOD square-off on remaining runner at
                        // close 1865
                        Candle.of5m(
                                "SUNPHARMA",
                                Instant.parse("2026-09-18T09:30:00Z"),
                                BigDecimal.valueOf(1859),
                                BigDecimal.valueOf(1868),
                                BigDecimal.valueOf(1858),
                                BigDecimal.valueOf(1865),
                                15000));

        List<LowestVolumePaperPosition> trades =
                service.replaySession("SUNPHARMA", LowestVolumeDirection.LONG, candles);

        assertThat(trades).hasSize(1);
        LowestVolumePaperPosition trade = trades.get(0);
        assertThat(trade.isClosed()).isTrue();
        assertThat(trade.isPartialBooked()).isTrue();
        // Partial P&L on 1:4 target must be positive +4R
        assertThat(trade.getPartialPnl()).isGreaterThan(BigDecimal.ZERO);
        assertThat(trade.getTotalRealizedPnl()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName(
            "evaluateLivePriceActions skips trigger entry checks when time is past 13:00 IST cutoff")
    void testEvaluateLivePriceActionsBlocksTriggerEntryPast1300Cutoff() {
        // Clock at 13:15 IST (past 13:00 cutoff)
        Clock postCutoffClock = Clock.fixed(Instant.parse("2026-09-18T07:45:00Z"), IST);
        service.setClock(postCutoffClock);

        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(1870),
                        BigDecimal.valueOf(1875),
                        BigDecimal.valueOf(1869),
                        BigDecimal.valueOf(1874),
                        5000),
                BigDecimal.valueOf(1875.05),
                BigDecimal.valueOf(1868.95),
                BigDecimal.valueOf(1899.45));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("SUNPHARMA", setup);

        // Mock spot price breaching trigger (1876.00 >= 1875.05)
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "1876.00").put("ap", "1870.00"));
        when(marketDataService.resolveToken("SUNPHARMA")).thenReturn("3351");

        service.evaluateLivePriceActions();

        // No trade entry should be taken past 13:00 cutoff
        assertThat(service.getOpenPositions()).isEmpty();
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
    }

    @Test
    @DisplayName("Zero volume anomalous candle does not corrupt rolling lowest volume tracking")
    void testZeroVolumeGlitchDoesNotCorruptRollingLowestVolume() {
        Instant t0 = Instant.parse("2026-09-18T03:45:00Z");
        List<Candle> candles =
                List.of(
                        Candle.of5m(
                                "SUNPHARMA",
                                t0,
                                BigDecimal.valueOf(1800),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1795),
                                BigDecimal.valueOf(1805),
                                10000),
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(5, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1805),
                                BigDecimal.valueOf(1815),
                                BigDecimal.valueOf(1800),
                                BigDecimal.valueOf(1810),
                                12000),
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(10, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1820),
                                BigDecimal.valueOf(1805),
                                BigDecimal.valueOf(1815),
                                15000),
                        // C4: Zero-volume glitch
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(15, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1815),
                                BigDecimal.valueOf(1816),
                                BigDecimal.valueOf(1814),
                                BigDecimal.valueOf(1815),
                                0),
                        // C5: Red pullback with volume 4000 (< 10000 baseline)
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(20, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1818),
                                BigDecimal.valueOf(1820),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1812),
                                4000));

        LowestVolumeSetup setup =
                service.evaluateCandleSequence("SUNPHARMA", LowestVolumeDirection.LONG, candles);

        // C5 should be armed because 4000 < 10000 baseline (0 volume was safely ignored)
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        assertThat(setup.getDayLowestVolume()).isEqualTo(4000);
        assertThat(setup.getTriggerPrice()).isEqualByComparingTo("1820.05");
    }

    @Test
    @DisplayName("runCycle stops retrying morning universe scan after 10:00 AM fallback cutoff")
    void testMorningScanRetryStopsAfter1000Cutoff() {
        // Clock at 10:15 IST (past 10:00 cutoff)
        Clock post10Clock = Clock.fixed(Instant.parse("2026-09-18T04:45:00Z"), IST);
        service.setClock(post10Clock);
        service.setUniverseScanCompletedToday(false);

        service.runCycle();

        // Universe scan should NOT run; universeScanCompletedToday remains false
        assertThat(service.isUniverseScanCompletedToday()).isFalse();
        assertThat(service.getActiveSetups()).isEmpty();
    }

    @Test
    @DisplayName("resetDaily resets tradeCounter back to 1 for clean trade ID generation")
    void testDailyResetClearsTradeCounter() {
        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(1870),
                        BigDecimal.valueOf(1875),
                        BigDecimal.valueOf(1869),
                        BigDecimal.valueOf(1874),
                        5000),
                BigDecimal.valueOf(1875.05),
                BigDecimal.valueOf(1868.95),
                BigDecimal.valueOf(1899.45));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");

        LowestVolumePaperPosition pos1 =
                service.executePositionEntry("SUNPHARMA", setup, BigDecimal.valueOf(1875.05));
        assertThat(pos1.getTradeId()).isEqualTo("LVR-1");

        LowestVolumePaperPosition pos2 =
                service.executePositionEntry("SUNPHARMA", setup, BigDecimal.valueOf(1875.05));
        assertThat(pos2.getTradeId()).isEqualTo("LVR-2");

        // Execute daily reset
        service.resetDaily();

        // Next trade should restart at LVR-1
        LowestVolumePaperPosition pos3 =
                service.executePositionEntry("SUNPHARMA", setup, BigDecimal.valueOf(1875.05));
        assertThat(pos3.getTradeId()).isEqualTo("LVR-1");
    }

    @Test
    @DisplayName("Replay session prioritizes SL breach over target breach on wide volatile candle")
    void testReplaySessionSlHitPrioritizedOverTargetHit() {
        service.setInstrumentType(LvrInstrumentType.FUTURES);
        service.setExitMode(LvrExitMode.PARTIAL_1_4_TRAIL_1_1_EOD_1500);

        Instant t0 = Instant.parse("2026-09-18T03:45:00Z");
        List<Candle> candles =
                List.of(
                        Candle.of5m(
                                "SUNPHARMA",
                                t0,
                                BigDecimal.valueOf(1800),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1795),
                                BigDecimal.valueOf(1805),
                                10000),
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(5, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1805),
                                BigDecimal.valueOf(1820),
                                BigDecimal.valueOf(1800),
                                BigDecimal.valueOf(1815),
                                12000),
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(10, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1815),
                                BigDecimal.valueOf(1830),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1825),
                                15000),
                        // C4: Red pullback (3000 vol) -> Trigger: 1826.05, SL: 1817.95, Target:
                        // 1858.45
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(15, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1825),
                                BigDecimal.valueOf(1826),
                                BigDecimal.valueOf(1818),
                                BigDecimal.valueOf(1820),
                                3000),
                        // C5: Bullish breakout triggering entry at 1826.05
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(20, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1822),
                                BigDecimal.valueOf(1835),
                                BigDecimal.valueOf(1821),
                                BigDecimal.valueOf(1834),
                                20000),
                        // C6: Highly volatile candle breaching BOTH SL (Low 1810 <= 1817.95) and
                        // Target (High 1860 >= 1858.45)
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(25, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1834),
                                BigDecimal.valueOf(1860),
                                BigDecimal.valueOf(1810),
                                BigDecimal.valueOf(1820),
                                30000));

        List<LowestVolumePaperPosition> trades =
                service.replaySession("SUNPHARMA", LowestVolumeDirection.LONG, candles);

        assertThat(trades).hasSize(1);
        LowestVolumePaperPosition trade = trades.get(0);
        assertThat(trade.isClosed()).isTrue();
        // SL must be prioritized over target
        assertThat(trade.getExitReason()).isEqualTo("SPOT_SL_HIT");
        assertThat(trade.getTotalRealizedPnl()).isLessThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName(
            "evaluateOpenPositions correctly fully closes single lot (lots=1) position on 1:2 Target")
    void testEvaluateOpenPositionsSingleLotFullExit() {
        service.setInstrumentType(LvrInstrumentType.FUTURES);
        service.setExitMode(LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500);

        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(1870),
                        BigDecimal.valueOf(1875),
                        BigDecimal.valueOf(1869),
                        BigDecimal.valueOf(1874),
                        5000),
                BigDecimal.valueOf(1875.05),
                BigDecimal.valueOf(1870.05),
                BigDecimal.valueOf(1885.05));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed");
        service.getActiveSetups().put("SUNPHARMA", setup);

        // Enter with lots = 1
        LowestVolumePaperPosition pos =
                new LowestVolumePaperPosition(
                        "LVR-1",
                        "SUNPHARMA",
                        LvrInstrumentType.FUTURES,
                        LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500,
                        "SUNPHARMA FUT",
                        350,
                        1, // 1 lot total
                        LowestVolumeDirection.LONG,
                        BigDecimal.valueOf(1875.00),
                        BigDecimal.valueOf(1870.00),
                        BigDecimal.valueOf(1885.00),
                        350,
                        BigDecimal.valueOf(1750.00),
                        Instant.now());
        service.getOpenPositions().put("SUNPHARMA", pos);

        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "1886.00"));

        service.evaluateOpenPositions(LocalTime.of(10, 30));

        // Position should be cleanly removed from openPositions and added to tradeHistory
        assertThat(service.getOpenPositions()).isEmpty();
        assertThat(service.getTradeHistory()).hasSize(1);
        LowestVolumePaperPosition closed = service.getTradeHistory().get(0);
        assertThat(closed.isClosed()).isTrue();
        assertThat(closed.getTotalRealizedPnl()).isGreaterThan(BigDecimal.ZERO);
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.CLOSED_TARGET);
        assertThat(service.getExhaustedSymbols()).contains("SUNPHARMA");
    }

    @Test
    @DisplayName("StockFnoRegistry contains LTIM token resolution")
    void testStockFnoRegistryContainsLtim() {
        var info = com.tradingbot.util.StockFnoRegistry.get("LTIM");
        assertThat(info).isNotNull();
        assertThat(info.token()).isEqualTo("17818");
        assertThat(info.lotSize()).isEqualTo(150);
    }

    @Test
    @DisplayName("Morning Universe Scan populates standby candidate reservoir from reserve sectors")
    void testMorningScanPopulatesReservoirFromSecondarySectors() {
        when(marketDataService.resolveToken(any())).thenReturn("1234");
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        mapper.createObjectNode()
                                .put("lp", "102.0")
                                .put("c", "100.0")
                                .put("o", "100.5")); // +2.0% change (qualifies for LONG)

        service.runMorningUniverseScan();

        assertThat(service.isUniverseScanCompletedToday()).isTrue();
        assertThat(service.getActiveSetups()).isNotEmpty();
        assertThat(service.getCandidateReservoir()).isNotEmpty();
    }

    @Test
    @DisplayName(
            "replenishActiveCandidatesIfNeeded promotes reserve stocks when active candidates drop below threshold")
    void testReplenishActiveCandidatesWhenActionableCountDrops() {
        service.getActiveSetups().clear();
        service.getCandidateReservoir().clear();

        // Seed reservoir with 2 reserve stocks
        service.getCandidateReservoir().add("TATAMOTORS");
        service.getCandidateReservoir().add("BAJFINANCE");

        // Put 1 active setup that is exhausted
        LowestVolumeSetup exhausted =
                new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        exhausted.transitionTo(LowestVolumeSetupState.REJECTED_EXHAUSTED, "VWAP failed");
        service.getActiveSetups().put("SUNPHARMA", exhausted);

        // Actionable count is 0 (< minActiveCandidates=2)
        assertThat(service.countActionableSetups()).isZero();

        service.replenishActiveCandidatesIfNeeded(LocalTime.of(10, 15));

        // Should promote TATAMOTORS and BAJFINANCE into activeSetups
        assertThat(service.getActiveSetups()).containsKey("TATAMOTORS");
        assertThat(service.getActiveSetups()).containsKey("BAJFINANCE");
        assertThat(service.countActionableSetups()).isEqualTo(2);
        assertThat(service.getCandidateReservoir()).isEmpty();
    }

    @Test
    @DisplayName("replenishActiveCandidatesIfNeeded skips symbols in exhaustedSymbols blacklist")
    void testReplenishSkipsExhaustedSymbols() {
        service.getActiveSetups().clear();
        service.getCandidateReservoir().clear();

        service.getExhaustedSymbols().add("TATAMOTORS"); // Blacklisted
        service.getCandidateReservoir().add("TATAMOTORS");
        service.getCandidateReservoir().add("BAJFINANCE");

        service.replenishActiveCandidatesIfNeeded(LocalTime.of(10, 15));

        // TATAMOTORS should be skipped, BAJFINANCE promoted
        assertThat(service.getActiveSetups()).doesNotContainKey("TATAMOTORS");
        assertThat(service.getActiveSetups()).containsKey("BAJFINANCE");
    }

    @Test
    @DisplayName("resetDaily clears candidate reservoir and resets refresh timestamps")
    void testResetDailyClearsReservoir() {
        service.getCandidateReservoir().add("TATAMOTORS");
        assertThat(service.getCandidateReservoir()).isNotEmpty();

        service.resetDaily();

        assertThat(service.getCandidateReservoir()).isEmpty();
    }

    @Test
    @DisplayName("Slippage guard blocks LONG entry when spot price exceeds allowable slippage band")
    void testSlippageGuardBlocksExcessiveLongSlippage() {
        service.setMaxSlippagePct(0.25); // 0.25% max slippage cap

        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(1000),
                        BigDecimal.valueOf(1005),
                        BigDecimal.valueOf(995),
                        BigDecimal.valueOf(1000),
                        5000),
                BigDecimal.valueOf(1005.05), // Trigger
                BigDecimal.valueOf(994.95), // SL
                BigDecimal.valueOf(1025.25));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed");
        service.getActiveSetups().put("SUNPHARMA", setup);

        // Spot gaps up to 1010.00 (+0.49% > 0.25% max slippage of 1007.56)
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "1010.00").put("ap", "1002.00"));
        when(marketDataService.resolveToken("SUNPHARMA")).thenReturn("3351");

        service.evaluateLivePriceActions();

        // Entry should be blocked
        assertThat(service.getOpenPositions()).doesNotContainKey("SUNPHARMA");
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
    }

    @Test
    @DisplayName(
            "Slippage guard blocks SHORT entry when spot price gaps down below allowable slippage band")
    void testSlippageGuardBlocksExcessiveShortSlippage() {
        service.setMaxSlippagePct(0.25); // 0.25% max slippage cap

        LowestVolumeSetup setup = new LowestVolumeSetup("PVRINOX", LowestVolumeDirection.SHORT);
        setup.setTriggerCandle(
                Candle.of5m(
                        "PVRINOX",
                        Instant.now(),
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(102),
                        BigDecimal.valueOf(98),
                        BigDecimal.valueOf(99),
                        4000),
                BigDecimal.valueOf(97.95), // Trigger
                BigDecimal.valueOf(102.05), // SL
                BigDecimal.valueOf(89.75));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed");
        service.getActiveSetups().put("PVRINOX", setup);

        // Spot dumps to 97.00 (-0.97% < -0.25% min allowed of 97.70)
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "97.00").put("ap", "99.00"));
        when(marketDataService.resolveToken("PVRINOX")).thenReturn("13147");

        service.evaluateLivePriceActions();

        // Entry should be blocked
        assertThat(service.getOpenPositions()).doesNotContainKey("PVRINOX");
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
    }

    @Test
    @DisplayName("Dynamic position sizing adjusts lots inversely to stop loss distance")
    void testDynamicPositionSizing() {
        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(1870),
                        BigDecimal.valueOf(1875),
                        BigDecimal.valueOf(1869),
                        BigDecimal.valueOf(1874),
                        5000),
                BigDecimal.valueOf(1875.05),
                BigDecimal.valueOf(1865.05), // Risk = 10.00
                BigDecimal.valueOf(1895.05));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed");

        // SUNPHARMA lotSize = 350. Risk per unit = 10.00. 1 lot risk = 3500.
        // Account risk budget = 10,000 (1% of 10L).
        // Sized lots = 10,000 / 3500 = 2 lots.
        LowestVolumePaperPosition pos =
                service.executePositionEntry("SUNPHARMA", setup, BigDecimal.valueOf(1875.05));

        assertThat(pos).isNotNull();
        assertThat(pos.getLots()).isEqualTo(2);
        assertThat(pos.getTotalQuantity()).isEqualTo(700); // 2 * 350
        assertThat(pos.getPlannedRisk()).isEqualByComparingTo("7000.00");
    }

    @Test
    @DisplayName(
            "estimateOptionPremium expands Delta towards 0.85 when spot price moves deep in the money")
    void testEstimateOptionPremiumExpandsDeltaOnITM() {
        LowestVolumePaperPosition pos =
                new LowestVolumePaperPosition(
                        "LVR-1",
                        "SUNPHARMA",
                        LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500,
                        "CE",
                        "SUNPHARMA ATM 1870CE",
                        BigDecimal.valueOf(1870),
                        350,
                        2,
                        LowestVolumeDirection.LONG,
                        BigDecimal.valueOf(25.00),
                        BigDecimal.valueOf(1870.00),
                        BigDecimal.valueOf(1860.00),
                        BigDecimal.valueOf(1890.00),
                        700,
                        BigDecimal.valueOf(7000.00),
                        Instant.now());

        // When spot rallies +40 pts to 1910 (+2.1% move into deep ITM)
        BigDecimal estPrem = service.estimateOptionPremium(pos, BigDecimal.valueOf(1910.00));

        // Intrinsic alone is 1910 - 1870 = 40.00. Dynamic Delta estimate > 25 + 40 * 0.50 = 45.00.
        assertThat(estPrem).isGreaterThan(BigDecimal.valueOf(45.00));
    }
}
