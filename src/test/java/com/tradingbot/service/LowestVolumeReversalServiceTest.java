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
    @DisplayName("Verify default configuration properties: FUTURES and PARTIAL_1_4_TRAIL_1_1_EOD_1500")
    void testDefaultConfigurationProperties() {
        assertThat(service.getInstrumentType()).isEqualTo(LvrInstrumentType.FUTURES);
        assertThat(service.getExitMode()).isEqualTo(LvrExitMode.PARTIAL_1_4_TRAIL_1_1_EOD_1500);
    }

    @Test
    @DisplayName(
            "Futures entry execution sets 1.0 Delta spot entry price, contract symbol and planned risk")
    void testFuturesEntryExecution() {
        service.setInstrumentType(LvrInstrumentType.FUTURES);
        service.setExitMode(LvrExitMode.FULL_TARGET_1_4);

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
                BigDecimal.valueOf(1904.45));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");

        LowestVolumePaperPosition pos =
                service.executePositionEntry("SUNPHARMA", setup, BigDecimal.valueOf(1876.10));

        assertThat(pos).isNotNull();
        assertThat(pos.getInstrumentType()).isEqualTo(LvrInstrumentType.FUTURES);
        assertThat(pos.getExitMode()).isEqualTo(LvrExitMode.FULL_TARGET_1_4);
        assertThat(pos.getOptionSymbol()).isEqualTo("SUNPHARMA FUT");
        assertThat(pos.getStockEntryPrice()).isEqualByComparingTo("1876.10");
        assertThat(pos.getEntryPremium()).isEqualByComparingTo("1876.10");
        // Trigger was 1876.05 with SL 1868.95. Live entry at 1876.10 (Risk = 7.15) -> Target = 1876.10 + 4 * 7.15 = 1904.70
        assertThat(pos.getTarget1StockPrice()).isEqualByComparingTo("1904.70");
        assertThat(service.getOpenPositions()).containsKey("SUNPHARMA");
    }

    @Test
    @DisplayName("Target 1:4 dynamically recalculates from actual entry spot price under slippage")
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
                BigDecimal.valueOf(98.05),  // Theoretical SL (Risk = 2.00)
                BigDecimal.valueOf(108.05)); // Theoretical target = 108.05
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");

        // Live entry happens at 101.05 (+1.00 slippage). Actual Risk = 101.05 - 98.05 = 3.00.
        // Dynamic Target must be 101.05 + (4 * 3.00) = 113.05 (exact 1:4 RR).
        LowestVolumePaperPosition pos =
                service.executePositionEntry("SUNPHARMA", setup, BigDecimal.valueOf(101.05));

        assertThat(pos.getStockEntryPrice()).isEqualByComparingTo("101.05");
        assertThat(pos.getCurrentStockSl()).isEqualByComparingTo("98.05");
        assertThat(pos.getTarget1StockPrice()).isEqualByComparingTo("113.05");
    }

    @Test
    @DisplayName("Candle sequence invalidates broken pullback when subsequent candle breaches SL")
    void testEvaluateCandleSequenceInvalidatesBrokenPullback() {
        Instant t0 = Instant.parse("2026-09-18T03:45:00Z");
        List<Candle> candles =
                List.of(
                        Candle.of5m("SUNPHARMA", t0, BigDecimal.valueOf(1800), BigDecimal.valueOf(1810), BigDecimal.valueOf(1795), BigDecimal.valueOf(1805), 10000),
                        Candle.of5m("SUNPHARMA", t0.plus(5, ChronoUnit.MINUTES), BigDecimal.valueOf(1805), BigDecimal.valueOf(1815), BigDecimal.valueOf(1800), BigDecimal.valueOf(1810), 12000),
                        Candle.of5m("SUNPHARMA", t0.plus(10, ChronoUnit.MINUTES), BigDecimal.valueOf(1810), BigDecimal.valueOf(1820), BigDecimal.valueOf(1805), BigDecimal.valueOf(1815), 15000),
                        // C4: Red pullback with lowest volume (3000 < 10000) -> Arms trigger: High 1820 (Trigger 1820.05), Low 1810 (SL 1809.95)
                        Candle.of5m("SUNPHARMA", t0.plus(15, ChronoUnit.MINUTES), BigDecimal.valueOf(1818), BigDecimal.valueOf(1820), BigDecimal.valueOf(1810), BigDecimal.valueOf(1812), 3000),
                        // C5: Massive dump breaching C4 SL (Low 1800 <= 1809.95), higher volume (8000)
                        Candle.of5m("SUNPHARMA", t0.plus(20, ChronoUnit.MINUTES), BigDecimal.valueOf(1810), BigDecimal.valueOf(1811), BigDecimal.valueOf(1800), BigDecimal.valueOf(1802), 8000));

        LowestVolumeSetup setup = service.evaluateCandleSequence("SUNPHARMA", LowestVolumeDirection.LONG, candles);

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
                        Candle.of5m("SUNPHARMA", t0, BigDecimal.valueOf(1800), BigDecimal.valueOf(1810), BigDecimal.valueOf(1795), BigDecimal.valueOf(1805), 10000),
                        Candle.of5m("SUNPHARMA", t0.plus(5, ChronoUnit.MINUTES), BigDecimal.valueOf(1805), BigDecimal.valueOf(1815), BigDecimal.valueOf(1800), BigDecimal.valueOf(1810), 12000),
                        Candle.of5m("SUNPHARMA", t0.plus(10, ChronoUnit.MINUTES), BigDecimal.valueOf(1810), BigDecimal.valueOf(1820), BigDecimal.valueOf(1805), BigDecimal.valueOf(1815), 15000),
                        // C4: Red pullback volume 3000 -> Arms trigger: High 1820 (Trigger 1820.05), Low 1810 (SL 1809.95)
                        Candle.of5m("SUNPHARMA", t0.plus(15, ChronoUnit.MINUTES), BigDecimal.valueOf(1818), BigDecimal.valueOf(1820), BigDecimal.valueOf(1810), BigDecimal.valueOf(1812), 3000),
                        // C5: elapsed = 1
                        Candle.of5m("SUNPHARMA", t0.plus(20, ChronoUnit.MINUTES), BigDecimal.valueOf(1812), BigDecimal.valueOf(1819), BigDecimal.valueOf(1811), BigDecimal.valueOf(1815), 6000),
                        // C6: elapsed = 2
                        Candle.of5m("SUNPHARMA", t0.plus(25, ChronoUnit.MINUTES), BigDecimal.valueOf(1815), BigDecimal.valueOf(1818), BigDecimal.valueOf(1812), BigDecimal.valueOf(1816), 7000),
                        // C7: elapsed = 3
                        Candle.of5m("SUNPHARMA", t0.plus(30, ChronoUnit.MINUTES), BigDecimal.valueOf(1816), BigDecimal.valueOf(1819), BigDecimal.valueOf(1814), BigDecimal.valueOf(1817), 8000),
                        // C8: elapsed = 4 (> 3) -> Expired!
                        Candle.of5m("SUNPHARMA", t0.plus(35, ChronoUnit.MINUTES), BigDecimal.valueOf(1817), BigDecimal.valueOf(1819), BigDecimal.valueOf(1815), BigDecimal.valueOf(1816), 9000));

        LowestVolumeSetup setup = service.evaluateCandleSequence("SUNPHARMA", LowestVolumeDirection.LONG, candles);

        // Setup should be expired and reset to SCANNING
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.SCANNING);
        assertThat(setup.getTriggerPrice()).isNull();
    }

    @Test
    @DisplayName("Futures LONG Position - 100% Full Exit at 1:4 Target in evaluateOpenPositions")
    void testFuturesFullExitTarget14() {
        service.setInstrumentType(LvrInstrumentType.FUTURES);
        service.setExitMode(LvrExitMode.FULL_TARGET_1_4);

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
                BigDecimal.valueOf(1887.05));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("SUNPHARMA", setup);

        LowestVolumePaperPosition pos =
                service.executePositionEntry("SUNPHARMA", setup, BigDecimal.valueOf(1875.00));
        assertThat(service.getOpenPositions()).hasSize(1);

        // Mock spot price reaching 1:4 target (1888.00 >= 1887.05)
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "1888.00"));

        service.evaluateOpenPositions(LocalTime.of(10, 15));

        assertThat(service.getOpenPositions()).isEmpty();
        assertThat(service.getTradeHistory()).hasSize(1);
        LowestVolumePaperPosition closed = service.getTradeHistory().get(0);
        assertThat(closed.isClosed()).isTrue();
        assertThat(closed.getExitReason()).isEqualTo("TARGET_1_4_FULL_EXIT");
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
        assertThat(setup.getTarget1Price()).isEqualByComparingTo("76.55"); // 1:4 RR
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
                BigDecimal.valueOf(76.55));

        LowestVolumePaperPosition position =
                service.executeOptionEntry("PVRINOX", setup, BigDecimal.valueOf(96.90));

        assertThat(position).isNotNull();
        assertThat(position.getSymbol()).isEqualTo("PVRINOX");
        assertThat(position.getOptionType()).isEqualTo("PE");
        assertThat(position.getStockEntryPrice()).isEqualByComparingTo("96.90");
        assertThat(position.getCurrentStockSl()).isEqualByComparingTo("102.05");
        // Actual Risk = 102.05 - 96.90 = 5.15. Target = 96.90 - (4 * 5.15) = 76.30
        assertThat(position.getTarget1StockPrice()).isEqualByComparingTo("76.30");
        assertThat(position.getExitMode()).isEqualTo(service.getExitMode());
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
    @DisplayName("Armed setup is invalidated if spot price breaches SL before hitting trigger, resetting to SCANNING")
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

        // Spot LTP = 97.50 (<= Trigger 97.95), VWAP (ap) = 100.50 (Spot < VWAP)
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "97.50").put("ap", "100.50"));
        when(marketDataService.resolveToken("PVRINOX")).thenReturn("13147");

        service.evaluateLivePriceActions();

        assertThat(service.getOpenPositions()).containsKey("PVRINOX");
        LowestVolumePaperPosition pos = service.getOpenPositions().get("PVRINOX");
        assertThat(pos.getStockEntryPrice()).isEqualByComparingTo("97.50");
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

        // Spot LTP = 97.50 (<= Trigger 97.95), but VWAP (ap) = 96.00 (Spot >= VWAP)
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "97.50").put("ap", "96.00"));
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
                        // C4: Red pullback with lowest volume (3000 < 10000) -> Arms trigger at High (1826 + 0.05 = 1826.05)
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(15, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1825),
                                BigDecimal.valueOf(1826),
                                BigDecimal.valueOf(1818),
                                BigDecimal.valueOf(1820),
                                3000),
                        // C5: Bullish breakout breaching 1826.05 (High: 1840). Note VWAP across C1-C5 is ~1815. 1826.05 > VWAP.
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
    @DisplayName("Futures LONG Position - 50% Profit Booked at 1:4 RR and SL moved to 1:1")
    void testFuturesPartialExitTarget14AndTrailSl11() {
        service.setInstrumentType(LvrInstrumentType.FUTURES);
        service.setExitMode(LvrExitMode.PARTIAL_1_4_TRAIL_1_1_EOD_1500);

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
                BigDecimal.valueOf(1910.05)); // 1:4 Target = 1870.05 + 40 = 1910.05
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("SUNPHARMA", setup);

        LowestVolumePaperPosition pos =
                service.executePositionEntry("SUNPHARMA", setup, BigDecimal.valueOf(1870.00));
        assertThat(service.getOpenPositions()).hasSize(1);

        // Mock spot reaching 1:4 target (1910.50 >= 1910.05)
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "1910.50").put("ap", "1875.00"));

        service.evaluateOpenPositions(LocalTime.of(10, 30));

        // Position should still be OPEN with remaining 50% lots
        assertThat(service.getOpenPositions()).containsKey("SUNPHARMA");
        assertThat(pos.isPartialBooked()).isTrue();
        assertThat(pos.getRemainingQuantity()).isEqualTo(pos.getTotalQuantity() / 2);
        // Initial SL was 1860.05 (Risk = 9.95). 1:1 SL = 1870.00 + 9.95 = 1879.95
        BigDecimal unitRisk = pos.getStockEntryPrice().subtract(pos.getInitialStockSl()).abs();
        BigDecimal expected11Sl = pos.getStockEntryPrice().add(unitRisk);
        assertThat(pos.getCurrentStockSl()).isEqualByComparingTo(expected11Sl);
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.PARTIAL_BOOKED);

        // Now simulate Spot falling back and hitting 1:1 Trailing SL
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", expected11Sl.subtract(BigDecimal.valueOf(0.50)).toString()).put("ap", "1875.00"));

        service.evaluateOpenPositions(LocalTime.of(11, 00));

        // Position should now be CLOSED with TRAILING_SL_1_1_HIT
        assertThat(service.getOpenPositions()).isEmpty();
        assertThat(service.getTradeHistory()).hasSize(1);
        LowestVolumePaperPosition closed = service.getTradeHistory().get(0);
        assertThat(closed.isClosed()).isTrue();
        assertThat(closed.getExitReason()).isEqualTo("TRAILING_SL_1_1_HIT");
        assertThat(closed.getTotalRealizedPnl()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Futures SHORT Position - 50% Profit Booked at 1:4 RR and SL moved to 1:1")
    void testFuturesShortPartialExitTarget14AndTrailSl11() {
        service.setInstrumentType(LvrInstrumentType.FUTURES);
        service.setExitMode(LvrExitMode.PARTIAL_1_4_TRAIL_1_1_EOD_1500);

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
                BigDecimal.valueOf(81.55)); // 1:4 Target = 97.95 - 16.40 = 81.55
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("PVRINOX", setup);

        LowestVolumePaperPosition pos =
                service.executePositionEntry("PVRINOX", setup, BigDecimal.valueOf(98.00));
        assertThat(service.getOpenPositions()).hasSize(1);

        // Mock spot reaching 1:4 target (81.00 <= 81.55)
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "81.00").put("ap", "90.00"));

        service.evaluateOpenPositions(LocalTime.of(10, 45));

        assertThat(service.getOpenPositions()).containsKey("PVRINOX");
        assertThat(pos.isPartialBooked()).isTrue();
        assertThat(pos.getRemainingQuantity()).isEqualTo(pos.getTotalQuantity() / 2);

        // For SHORT: 1:1 SL = Entry (98.00) - UnitRisk (4.05) = 93.95
        BigDecimal unitRisk = pos.getStockEntryPrice().subtract(pos.getInitialStockSl()).abs();
        BigDecimal expected11Sl = pos.getStockEntryPrice().subtract(unitRisk);
        assertThat(pos.getCurrentStockSl()).isEqualByComparingTo(expected11Sl);

        // Test 15:00 EOD Hard Exit on runner
        service.executeHardExit(LocalTime.of(15, 0));
        assertThat(service.getOpenPositions()).isEmpty();
        assertThat(service.getTradeHistory()).hasSize(1);
        LowestVolumePaperPosition closed = service.getTradeHistory().get(0);
        assertThat(closed.isClosed()).isTrue();
        assertThat(closed.getExitReason()).isEqualTo("EOD_1500_HARD_EXIT");
    }

    @Test
    @DisplayName("processCandidateSetups synchronizes live activeSetup back to SCANNING when trigger is broken")
    void testProcessCandidateSetupsResetsActiveSetupWhenInvalidated() {
        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m("SUNPHARMA", Instant.now(), BigDecimal.valueOf(1818), BigDecimal.valueOf(1820), BigDecimal.valueOf(1810), BigDecimal.valueOf(1812), 3000),
                BigDecimal.valueOf(1820.05),
                BigDecimal.valueOf(1809.95),
                BigDecimal.valueOf(1850.05));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("SUNPHARMA", setup);

        Instant t0 = Instant.now().minus(25, ChronoUnit.MINUTES);
        List<Candle> candles =
                List.of(
                        Candle.of5m("SUNPHARMA", t0, BigDecimal.valueOf(1800), BigDecimal.valueOf(1810), BigDecimal.valueOf(1795), BigDecimal.valueOf(1805), 10000),
                        Candle.of5m("SUNPHARMA", t0.plus(5, ChronoUnit.MINUTES), BigDecimal.valueOf(1805), BigDecimal.valueOf(1815), BigDecimal.valueOf(1800), BigDecimal.valueOf(1810), 12000),
                        Candle.of5m("SUNPHARMA", t0.plus(10, ChronoUnit.MINUTES), BigDecimal.valueOf(1810), BigDecimal.valueOf(1820), BigDecimal.valueOf(1805), BigDecimal.valueOf(1815), 15000),
                        // C4: Red pullback (armed)
                        Candle.of5m("SUNPHARMA", t0.plus(15, ChronoUnit.MINUTES), BigDecimal.valueOf(1818), BigDecimal.valueOf(1820), BigDecimal.valueOf(1810), BigDecimal.valueOf(1812), 3000),
                        // C5: Dumping candle breaching C4 SL (Low 1800 <= 1809.95)
                        Candle.of5m("SUNPHARMA", t0.plus(20, ChronoUnit.MINUTES), BigDecimal.valueOf(1810), BigDecimal.valueOf(1811), BigDecimal.valueOf(1800), BigDecimal.valueOf(1802), 8000));

        when(marketDataService.fetch5MinCandles("SUNPHARMA", 5)).thenReturn(candles);
        service.setUniverseScanCompletedToday(true);

        service.runCycle();

        // activeSetup in memory should now be SCANNING (not stuck in TRIGGER_ARMED)
        LowestVolumeSetup synced = service.getActiveSetups().get("SUNPHARMA");
        assertThat(synced.getState()).isEqualTo(LowestVolumeSetupState.SCANNING);
        assertThat(synced.getTriggerPrice()).isNull();
    }

    @Test
    @DisplayName("Candle closing after exit time is properly allowed even if its start timestamp precedes exit")
    void testBarTimestampPlusDurationMatchesSubSecondExitTime() {
        Instant t0 = Instant.parse("2026-09-18T04:15:00Z"); // 09:45:00 IST
        Instant exitTime = Instant.parse("2026-09-18T04:16:27.188Z"); // 09:46:27.188 IST (mid-bar exit)

        List<Candle> candles =
                List.of(
                        Candle.of5m("SUNPHARMA", t0.minus(15, ChronoUnit.MINUTES), BigDecimal.valueOf(1800), BigDecimal.valueOf(1810), BigDecimal.valueOf(1795), BigDecimal.valueOf(1805), 10000),
                        Candle.of5m("SUNPHARMA", t0.minus(10, ChronoUnit.MINUTES), BigDecimal.valueOf(1805), BigDecimal.valueOf(1815), BigDecimal.valueOf(1800), BigDecimal.valueOf(1810), 12000),
                        Candle.of5m("SUNPHARMA", t0.minus(5, ChronoUnit.MINUTES), BigDecimal.valueOf(1810), BigDecimal.valueOf(1820), BigDecimal.valueOf(1805), BigDecimal.valueOf(1815), 15000),
                        // 09:45-09:50 candle (timestamp 09:45:00, completes at 09:50:00 > exit 09:46:27)
                        Candle.of5m("SUNPHARMA", t0, BigDecimal.valueOf(1818), BigDecimal.valueOf(1820), BigDecimal.valueOf(1810), BigDecimal.valueOf(1812), 3000));

        LowestVolumeSetup setup = service.evaluateCandleSequence("SUNPHARMA", LowestVolumeDirection.LONG, candles, exitTime);

        // 09:45 candle should be allowed and armed for Attempt 2
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        assertThat(setup.getTriggerPrice()).isEqualByComparingTo("1820.05");
    }

    @Test
    @DisplayName("evaluateLivePriceActions fetches quote only once per symbol across checks")
    void testSingleQuoteFetchPerSymbolInLivePollingCycle() {
        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m("SUNPHARMA", Instant.now(), BigDecimal.valueOf(1870), BigDecimal.valueOf(1875), BigDecimal.valueOf(1869), BigDecimal.valueOf(1874), 5000),
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
    @DisplayName("replaySession correctly computes Futures partial booking PnL using spot target price")
    void testReplaySessionFuturesPartialExitTarget14ComputesExactPnl() {
        service.setInstrumentType(LvrInstrumentType.FUTURES);
        service.setExitMode(LvrExitMode.PARTIAL_1_4_TRAIL_1_1_EOD_1500);

        Instant t0 = Instant.parse("2026-09-18T03:45:00Z");
        List<Candle> candles =
                List.of(
                        Candle.of5m("SUNPHARMA", t0, BigDecimal.valueOf(1800), BigDecimal.valueOf(1810), BigDecimal.valueOf(1795), BigDecimal.valueOf(1805), 10000),
                        Candle.of5m("SUNPHARMA", t0.plus(5, ChronoUnit.MINUTES), BigDecimal.valueOf(1805), BigDecimal.valueOf(1820), BigDecimal.valueOf(1800), BigDecimal.valueOf(1815), 12000),
                        Candle.of5m("SUNPHARMA", t0.plus(10, ChronoUnit.MINUTES), BigDecimal.valueOf(1815), BigDecimal.valueOf(1830), BigDecimal.valueOf(1810), BigDecimal.valueOf(1825), 15000),
                        // C4: Red pullback (3000 volume) -> Trigger: 1826.05, SL: 1817.95 (Risk = 8.10), Target 1:4: 1826.05 + 32.40 = 1858.45
                        Candle.of5m("SUNPHARMA", t0.plus(15, ChronoUnit.MINUTES), BigDecimal.valueOf(1825), BigDecimal.valueOf(1826), BigDecimal.valueOf(1818), BigDecimal.valueOf(1820), 3000),
                        // C5: Bullish breakout triggering entry at 1826.05
                        Candle.of5m("SUNPHARMA", t0.plus(20, ChronoUnit.MINUTES), BigDecimal.valueOf(1822), BigDecimal.valueOf(1840), BigDecimal.valueOf(1821), BigDecimal.valueOf(1838), 20000),
                        // C6: Surging to 1860 reaching 1:4 Target (1858.45) -> 50% partial booked
                        Candle.of5m("SUNPHARMA", t0.plus(25, ChronoUnit.MINUTES), BigDecimal.valueOf(1838), BigDecimal.valueOf(1860), BigDecimal.valueOf(1837), BigDecimal.valueOf(1859), 25000),
                        // C7: Candle at 15:00 IST -> Hard EOD square-off on remaining runner at close 1865
                        Candle.of5m("SUNPHARMA", Instant.parse("2026-09-18T09:30:00Z"), BigDecimal.valueOf(1859), BigDecimal.valueOf(1868), BigDecimal.valueOf(1858), BigDecimal.valueOf(1865), 15000));

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
    @DisplayName("evaluateLivePriceActions skips trigger entry checks when time is past 13:00 IST cutoff")
    void testEvaluateLivePriceActionsBlocksTriggerEntryPast1300Cutoff() {
        // Clock at 13:15 IST (past 13:00 cutoff)
        Clock postCutoffClock = Clock.fixed(Instant.parse("2026-09-18T07:45:00Z"), IST);
        service.setClock(postCutoffClock);

        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m("SUNPHARMA", Instant.now(), BigDecimal.valueOf(1870), BigDecimal.valueOf(1875), BigDecimal.valueOf(1869), BigDecimal.valueOf(1874), 5000),
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
                        Candle.of5m("SUNPHARMA", t0, BigDecimal.valueOf(1800), BigDecimal.valueOf(1810), BigDecimal.valueOf(1795), BigDecimal.valueOf(1805), 10000),
                        Candle.of5m("SUNPHARMA", t0.plus(5, ChronoUnit.MINUTES), BigDecimal.valueOf(1805), BigDecimal.valueOf(1815), BigDecimal.valueOf(1800), BigDecimal.valueOf(1810), 12000),
                        Candle.of5m("SUNPHARMA", t0.plus(10, ChronoUnit.MINUTES), BigDecimal.valueOf(1810), BigDecimal.valueOf(1820), BigDecimal.valueOf(1805), BigDecimal.valueOf(1815), 15000),
                        // C4: Zero-volume glitch
                        Candle.of5m("SUNPHARMA", t0.plus(15, ChronoUnit.MINUTES), BigDecimal.valueOf(1815), BigDecimal.valueOf(1816), BigDecimal.valueOf(1814), BigDecimal.valueOf(1815), 0),
                        // C5: Red pullback with volume 4000 (< 10000 baseline)
                        Candle.of5m("SUNPHARMA", t0.plus(20, ChronoUnit.MINUTES), BigDecimal.valueOf(1818), BigDecimal.valueOf(1820), BigDecimal.valueOf(1810), BigDecimal.valueOf(1812), 4000));

        LowestVolumeSetup setup = service.evaluateCandleSequence("SUNPHARMA", LowestVolumeDirection.LONG, candles);

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
                Candle.of5m("SUNPHARMA", Instant.now(), BigDecimal.valueOf(1870), BigDecimal.valueOf(1875), BigDecimal.valueOf(1869), BigDecimal.valueOf(1874), 5000),
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
}
