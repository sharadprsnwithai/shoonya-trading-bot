package com.tradingbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
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
        service.setPdhPdlFilterEnabled(false);
        // M9: the sector gate now fails closed on NO_DATA (unit-test quote mocks carry no
        // breadth data); gate-specific tests re-enable it or call the check directly.
        service.setSectorMomentumFilterEnabled(false);
    }

    @Test
    @DisplayName("Double entry race is prevented and does not overwrite position")
    void testDoubleEntryRacePrevented() {
        LowestVolumeSetup setup = new LowestVolumeSetup("RELIANCE", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "RELIANCE",
                        Instant.now(),
                        BigDecimal.valueOf(2500),
                        BigDecimal.valueOf(2510),
                        BigDecimal.valueOf(2495),
                        BigDecimal.valueOf(2505),
                        1000),
                BigDecimal.valueOf(2510.05),
                BigDecimal.valueOf(2494.95),
                BigDecimal.valueOf(2540.25));

        service.getActiveSetups().put("RELIANCE", setup);

        LowestVolumePaperPosition pos1 =
                service.executePositionEntry("RELIANCE", setup, BigDecimal.valueOf(2510.05));
        assertThat(pos1).isNotNull();
        assertThat(service.getOpenPositions()).containsKey("RELIANCE");
        assertThat(setup.getTradeAttempts()).isEqualTo(1);

        // Second call while in position
        LowestVolumePaperPosition pos2 =
                service.executePositionEntry("RELIANCE", setup, BigDecimal.valueOf(2511.00));
        assertThat(pos2).isSameAs(pos1);
        assertThat(setup.getTradeAttempts()).isEqualTo(1); // Not incremented again
    }

    @Test
    @DisplayName("resetDaily exits open positions before clearing daily state")
    void testResetDailyExitsOpenPositions() {
        LowestVolumeSetup setup = new LowestVolumeSetup("TCS", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "TCS",
                        Instant.now(),
                        BigDecimal.valueOf(3500),
                        BigDecimal.valueOf(3510),
                        BigDecimal.valueOf(3490),
                        BigDecimal.valueOf(3505),
                        500),
                BigDecimal.valueOf(3510.05),
                BigDecimal.valueOf(3489.95),
                BigDecimal.valueOf(3550.25));
        service.getActiveSetups().put("TCS", setup);
        service.executePositionEntry("TCS", setup, BigDecimal.valueOf(3510.05));
        assertThat(service.getOpenPositions()).containsKey("TCS");

        service.resetDaily();
        assertThat(service.getOpenPositions()).isEmpty();
        assertThat(service.getActiveSetups()).isEmpty();
    }

    @Test
    @DisplayName("Morning scan with <35 quotes fails safe and does not mark scan completed today")
    void testMorningScanWithLowCoverageRetries() {
        // Return only 10 quotes for universe
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .createObjectNode()
                                .put("lp", "100.0")
                                .put("c", "95.0"));

        service.runMorningUniverseScan();
        // Since coverage is insufficient or quotes empty, scan remains incomplete for retry
        assertThat(service.isUniverseScanCompletedToday()).isFalse();
    }

    @Test
    @DisplayName("Circuit breaker trips on aggregate floating loss plus realized loss")
    void testCircuitBreakerTripsOnUnrealizedLoss() {
        service.setMaxDailyLoss(5000.0);

        // Open a LONG futures position that has moved down
        LowestVolumePaperPosition pos =
                new LowestVolumePaperPosition(
                        "LVR-1",
                        "RELIANCE",
                        LvrInstrumentType.FUTURES,
                        LvrExitMode.FULL_TARGET_1_2,
                        "RELIANCE FUT",
                        250,
                        2,
                        LowestVolumeDirection.LONG,
                        BigDecimal.valueOf(2500.0),
                        BigDecimal.valueOf(2480.0),
                        BigDecimal.valueOf(2540.0),
                        500,
                        BigDecimal.valueOf(10000.0),
                        Instant.now());
        service.getOpenPositions().put("RELIANCE", pos);

        // Spot LTP is 2485 (15 pts loss on 500 qty = -7500 loss, which exceeds -5000 maxDailyLoss)
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .createObjectNode()
                                .put("lp", "2485.0"));

        assertThat(service.isDailyCircuitBreakerTripped()).isTrue();

        // Attempting to enter another position should be blocked
        LowestVolumeSetup setup2 = new LowestVolumeSetup("TCS", LowestVolumeDirection.LONG);
        setup2.setTriggerCandle(
                Candle.of5m(
                        "TCS",
                        Instant.now(),
                        BigDecimal.valueOf(3500),
                        BigDecimal.valueOf(3510),
                        BigDecimal.valueOf(3490),
                        BigDecimal.valueOf(3505),
                        500),
                BigDecimal.valueOf(3510.05),
                BigDecimal.valueOf(3489.95),
                BigDecimal.valueOf(3550.25));
        setup2.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed");

        LowestVolumePaperPosition pos2 =
                service.executePositionEntry("TCS", setup2, BigDecimal.valueOf(3510.05));
        assertThat(pos2).isNull();
    }

    @Test
    @DisplayName(
            "Option entry is skipped when live option quote is unavailable (no fabricated premium)")
    void testOptionEntryAbortedWhenOptionLtpUnavailable() {
        service.setInstrumentType(LvrInstrumentType.OPTIONS);

        LowestVolumeSetup setup = new LowestVolumeSetup("INFY", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "INFY",
                        Instant.now(),
                        BigDecimal.valueOf(1500),
                        BigDecimal.valueOf(1510),
                        BigDecimal.valueOf(1495),
                        BigDecimal.valueOf(1505),
                        500),
                BigDecimal.valueOf(1510.05),
                BigDecimal.valueOf(1494.95),
                BigDecimal.valueOf(1540.25));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed");

        // mock fetchQuote returning 0 / null for option token
        when(marketDataService.fetchQuote(any(), any())).thenReturn(null);

        LowestVolumePaperPosition pos =
                service.executePositionEntry("INFY", setup, BigDecimal.valueOf(1510.05));
        assertThat(pos).isNull();
        assertThat(service.getOpenPositions()).doesNotContainKey("INFY");
    }

    @Test
    @DisplayName("resolveAtmStrike never returns strike 0 even for small spot prices")
    void testResolveAtmStrikeGuardsAgainstZero() {
        BigDecimal strike =
                service.resolveAtmStrike(BigDecimal.valueOf(3.0), BigDecimal.valueOf(10.0));
        assertThat(strike).isEqualByComparingTo(BigDecimal.valueOf(10.0));

        strike = service.resolveAtmStrike(BigDecimal.valueOf(1234.50), BigDecimal.valueOf(50.0));
        assertThat(strike).isEqualByComparingTo(BigDecimal.valueOf(1250.0));
    }

    @Test
    @DisplayName("roundToTick rounds prices strictly to 0.05 increments")
    void testRoundToTickIncrements() {
        BigDecimal tick = LowestVolumeReversalService.roundToTick(BigDecimal.valueOf(1234.239040));
        assertThat(tick).isEqualByComparingTo(BigDecimal.valueOf(1234.25));

        tick = LowestVolumeReversalService.roundToTick(BigDecimal.valueOf(1234.21));
        assertThat(tick).isEqualByComparingTo(BigDecimal.valueOf(1234.20));
    }

    @Test
    @DisplayName("C1 Fix: Setup must NOT invalidate prior to entry when session day low is below SL but current spot is healthy")
    void testSetupDoesNotInvalidateOnSessionDayLow() {
        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(1870),
                        BigDecimal.valueOf(1875),
                        BigDecimal.valueOf(1865),
                        BigDecimal.valueOf(1872),
                        5000),
                BigDecimal.valueOf(1875.05),
                BigDecimal.valueOf(1864.95), // SL at 1864.95
                BigDecimal.valueOf(1895.25));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("SUNPHARMA", setup);

        // Quote where session low 'l' is 1850.00 (from 09:15 AM open), but current spot 'lp' is 1872.00 (healthy)
        ObjectMapper mapper = new ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        mapper.createObjectNode()
                                .put("lp", "1872.00")
                                .put("h", "1874.00")
                                .put("l", "1850.00"));
        when(marketDataService.resolveToken("SUNPHARMA")).thenReturn("3351");

        service.evaluateLivePriceActions();

        // Setup must STAY ARMED and NOT be reset to SCANNING
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        assertThat(setup.getTriggerPrice()).isEqualByComparingTo("1875.05");
    }

    @Test
    @DisplayName("C2 Fix: Trigger breach must NOT fire when current spot is below trigger, even if session day high was above trigger")
    void testTriggerDoesNotFireOnStaleDayHigh() {
        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setPdh(BigDecimal.valueOf(1870.00));
        setup.setPdl(BigDecimal.valueOf(1850.00));
        setup.setLatestVwap(1870.00);
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(1870),
                        BigDecimal.valueOf(1875),
                        BigDecimal.valueOf(1865),
                        BigDecimal.valueOf(1872),
                        5000),
                BigDecimal.valueOf(1875.05),
                BigDecimal.valueOf(1864.95),
                BigDecimal.valueOf(1895.25));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("SUNPHARMA", setup);

        // Quote where session high 'h' is 1880.00 (from 09:15 AM spike), but current spot 'lp' is 1872.00 (below trigger 1875.05)
        ObjectMapper mapper = new ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        mapper.createObjectNode()
                                .put("lp", "1872.00")
                                .put("ap", "1870.00")
                                .put("h", "1880.00")
                                .put("l", "1868.00"));
        when(marketDataService.resolveToken("SUNPHARMA")).thenReturn("3351");

        service.evaluateLivePriceActions();

        // No trade should be entered since live spot (1872) hasn't breached trigger (1875.05)
        assertThat(service.getOpenPositions()).doesNotContainKey("SUNPHARMA");
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
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
    @DisplayName("Publishes TradeSignal to SignalPublisher on Entry and Hard Exit")
    void testSignalPublishedOnEntryAndExit() {
        com.tradingbot.bus.SignalPublisher mockPublisher =
                mock(com.tradingbot.bus.SignalPublisher.class);
        // H11: a rejected publish rolls the entry back — the happy-path test must accept it.
        when(mockPublisher.publish(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        service =
                new LowestVolumeReversalService(
                        marketDataService, taService, null, config, null, null, mockPublisher);
        service.setClock(Clock.fixed(Instant.parse("2026-09-18T04:30:00Z"), IST)); // 10:00 IST

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

        // Verify Entry Signal published
        org.mockito.Mockito.verify(mockPublisher, org.mockito.Mockito.times(1))
                .publish(
                        org.mockito.ArgumentMatchers.argThat(
                                sig ->
                                        sig.underlyingSymbol().equals("SUNPHARMA")
                                                && "LOWEST_VOLUME_REVERSAL".equals(sig.strategyId())
                                                && sig.action()
                                                        == com.tradingbot.strategy.SignalAction
                                                                .ENTRY_LONG));

        // Test 15:00 EOD Hard Exit Signal published
        service.executeHardExit(LocalTime.of(15, 0));
        org.mockito.Mockito.verify(mockPublisher, org.mockito.Mockito.times(1))
                .publish(
                        org.mockito.ArgumentMatchers.argThat(
                                sig ->
                                        sig.underlyingSymbol().equals("SUNPHARMA")
                                                && sig.action()
                                                        == com.tradingbot.strategy.SignalAction
                                                                .EXIT_LONG));
    }

    @Test
    @DisplayName("H4 Fix: Open positions must be managed for SL exit even when strategy enabled is false")
    void testOpenPositionsManagedWhenStrategyDisabled() {
        service.setEnabled(false); // Operator toggled off strategy
        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.transitionTo(LowestVolumeSetupState.IN_POSITION, "In position");

        LowestVolumePaperPosition pos =
                new LowestVolumePaperPosition(
                        "LVR-1",
                        "SUNPHARMA",
                        LvrInstrumentType.FUTURES,
                        LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500,
                        "SUNPHARMA FUT",
                        100,
                        1,
                        LowestVolumeDirection.LONG,
                        BigDecimal.valueOf(1870.00),
                        BigDecimal.valueOf(1860.00),
                        BigDecimal.valueOf(1890.00),
                        100,
                        BigDecimal.valueOf(1000.00),
                        Instant.now());
        service.getOpenPositions().put("SUNPHARMA", pos);

        // Spot dropped below SL (1855 < 1860)
        ObjectMapper mapper = new ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "1855.00").put("ap", "1865.00"));
        when(marketDataService.resolveToken("SUNPHARMA")).thenReturn("3351");

        service.evaluateLivePriceActions();

        // Position must be exited via SL even though enabled is false
        assertThat(pos.isClosed()).isTrue();
        assertThat(pos.getExitReason()).isEqualTo("SPOT_SL_HIT");
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
                                BigDecimal.valueOf(1820),
                                BigDecimal.valueOf(1813),
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
        when(marketDataService.resolveToken(any())).thenReturn("12345");
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .createObjectNode()
                                .put("lp", "5.50"));

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
        service.setClock(Clock.fixed(Instant.parse("2026-09-18T06:00:00Z"), IST)); // 11:30 IST
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
        Instant t0 = Instant.parse("2026-09-18T03:45:00Z");
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
        service.setClock(Clock.fixed(Instant.parse("2026-09-18T06:00:00Z"), IST)); // 11:30 IST
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

        Instant t0 = Instant.parse("2026-09-18T03:45:00Z");
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

        // H12: the square-off needs a real quote — a missing quote now defers the exit instead
        // of fabricating a zero-loss fill from the entry price.
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .createObjectNode()
                                .put("lp", "520.0")
                                .put("c", "515.0"));

        service.executeHardExit(LocalTime.of(15, 0));

        assertThat(service.getOpenPositions()).isEmpty();
        assertThat(service.getTradeHistory()).hasSize(1);
        assertThat(service.getTradeHistory().get(0).getExitReason())
                .isEqualTo("EOD_1500_HARD_EXIT");
    }

    @Test
    @DisplayName("H12 Fix: hard exit with no quote keeps the position instead of fabricating a fill")
    void testHardEodExitKeepsPositionWhenQuoteUnavailable() {
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
        // No fetchQuote mock → quote unavailable.

        service.executeHardExit(LocalTime.of(15, 0));

        assertThat(service.getOpenPositions()).containsKey("SUNPHARMA");
        assertThat(pos.isClosed()).isFalse();
        assertThat(service.getTradeHistory()).isEmpty();
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

        // Spot LTP = 97.90 (<= Trigger 97.95 and within 0.12% slippage), VWAP (ap) = 100.50 (Spot <
        // VWAP)
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "97.90").put("ap", "100.50"));
        when(marketDataService.resolveToken("PVRINOX")).thenReturn("13147");

        service.evaluateLivePriceActions();

        assertThat(service.getOpenPositions()).containsKey("PVRINOX");
        LowestVolumePaperPosition pos = service.getOpenPositions().get("PVRINOX");
        assertThat(pos.getStockEntryPrice()).isEqualByComparingTo("97.90");
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

        // Spot LTP = 97.90 (<= Trigger 97.95 and within 0.12% slippage), but VWAP (ap) = 96.00
        // (Spot >= VWAP)
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "97.90").put("ap", "96.00"));
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

    private List<Candle> replayFixtureCandles() {
        Instant t0 = Instant.parse("2026-09-18T03:45:00Z");
        return List.of(
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
                Candle.of5m(
                        "SUNPHARMA",
                        t0.plus(15, ChronoUnit.MINUTES),
                        BigDecimal.valueOf(1825),
                        BigDecimal.valueOf(1826),
                        BigDecimal.valueOf(1818),
                        BigDecimal.valueOf(1820),
                        3000),
                Candle.of5m(
                        "SUNPHARMA",
                        t0.plus(20, ChronoUnit.MINUTES),
                        BigDecimal.valueOf(1822),
                        BigDecimal.valueOf(1840),
                        BigDecimal.valueOf(1821),
                        BigDecimal.valueOf(1838),
                        20000));
    }

    @Test
    @DisplayName("L6: replaySession honors the shared breaker gate — no trades when tripped")
    void testReplaySessionBlockedWhenCircuitBreakerTripped() {
        service.setMaxDailyLoss(10000.0);
        Instant testInstant = Instant.parse("2026-09-18T04:30:00Z");
        LowestVolumePaperPosition lossPos =
                new LowestVolumePaperPosition(
                        "LVR-1",
                        "PERSISTENT",
                        LvrInstrumentType.FUTURES,
                        LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500,
                        "PERSISTENT FUT",
                        100,
                        2,
                        LowestVolumeDirection.LONG,
                        BigDecimal.valueOf(5340.00),
                        BigDecimal.valueOf(5320.00),
                        BigDecimal.valueOf(5380.00),
                        200,
                        BigDecimal.valueOf(4000.00),
                        testInstant);
        lossPos.close(BigDecimal.valueOf(5280.00), "SPOT_SL_HIT", testInstant);
        service.getTradeHistory().add(lossPos);
        assertThat(service.isDailyCircuitBreakerTripped()).isTrue();

        List<LowestVolumePaperPosition> trades =
                service.replaySession("SUNPHARMA", LowestVolumeDirection.LONG, replayFixtureCandles());

        assertThat(trades).isEmpty();
    }

    @Test
    @DisplayName("L6: replaySession honors the shared budget gate — no trades when planned risk exceeds budget")
    void testReplaySessionBlockedWhenPlannedRiskExceedsBudget() {
        // SUNPHARMA futures: unitRisk 10 × (2 lots × 350) = ₹7,000 planned risk vs ₹1 budget.
        service.setMaxDailyLoss(1.0);

        List<LowestVolumePaperPosition> trades =
                service.replaySession("SUNPHARMA", LowestVolumeDirection.LONG, replayFixtureCandles());

        assertThat(trades).isEmpty();
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
        service.setClock(Clock.fixed(Instant.parse("2026-09-18T04:30:00Z"), IST)); // 10:00 IST
        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        Instant t0 = Instant.parse("2026-09-18T04:05:00Z"); // 09:35 IST
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        t0.plus(15, ChronoUnit.MINUTES),
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
                        LvrExitMode.PARTIAL_1_2_TRAIL_COST_EOD_1500,
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
        service.setExitMode(LvrExitMode.PARTIAL_1_2_TRAIL_COST_EOD_1500);

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
        service = new LowestVolumeReversalService(marketDataService, taService, null, config, null);
        service.setClock(Clock.fixed(Instant.parse("2026-09-18T04:30:00Z"), IST));
        // M2: raise the daily budget — this test is about trade-ID sequencing, not the
        // pre-trade risk budget (TATASTEEL's 5500 lot would exceed the default ₹15k limit).
        service.setMaxDailyLoss(1_000_000.0);

        LowestVolumeSetup setup1 = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup1.setTriggerCandle(
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

        LowestVolumeSetup setup2 = new LowestVolumeSetup("TATASTEEL", LowestVolumeDirection.LONG);
        setup2.setTriggerCandle(
                Candle.of5m(
                        "TATASTEEL",
                        Instant.now(),
                        BigDecimal.valueOf(150),
                        BigDecimal.valueOf(155),
                        BigDecimal.valueOf(149),
                        BigDecimal.valueOf(154),
                        5000),
                BigDecimal.valueOf(155.05),
                BigDecimal.valueOf(148.95),
                BigDecimal.valueOf(167.25));

        LowestVolumePaperPosition pos1 =
                service.executePositionEntry("SUNPHARMA", setup1, BigDecimal.valueOf(1875.05));
        assertThat(pos1.getTradeId()).isEqualTo("LVR-1");

        LowestVolumePaperPosition pos2 =
                service.executePositionEntry("TATASTEEL", setup2, BigDecimal.valueOf(155.05));
        assertThat(pos2.getTradeId()).isEqualTo("LVR-2");

        // Execute daily reset
        service.resetDaily();

        // Next trade should restart at LVR-1
        LowestVolumeSetup setup3 = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup3.setTriggerCandle(
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

        LowestVolumePaperPosition pos3 =
                service.executePositionEntry("SUNPHARMA", setup3, BigDecimal.valueOf(1875.05));
        assertThat(pos3.getTradeId()).isEqualTo("LVR-1");
    }

    @Test
    @DisplayName("Replay session prioritizes SL breach over target breach on wide volatile candle")
    void testReplaySessionSlHitPrioritizedOverTargetHit() {
        service.setInstrumentType(LvrInstrumentType.FUTURES);
        service.setExitMode(LvrExitMode.PARTIAL_1_2_TRAIL_COST_EOD_1500);

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
    @DisplayName("Default position sizing uses fixed 2 lots when dynamic sizing is disabled")
    void testFixedDefaultLots() {
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
                BigDecimal.valueOf(1873.05), // Risk = 2.00
                BigDecimal.valueOf(1879.05));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed");

        // Dynamic sizing is false by default. Lots must strictly be fixed default (2 lots)
        LowestVolumePaperPosition pos =
                service.executePositionEntry("SUNPHARMA", setup, BigDecimal.valueOf(1875.05));

        assertThat(pos).isNotNull();
        assertThat(pos.getLots()).isEqualTo(2);
        assertThat(pos.getTotalQuantity()).isEqualTo(700); // 2 * 350
    }

    @Test
    @DisplayName(
            "Dynamic position sizing adjusts lots inversely to stop loss distance when enabled")
    void testDynamicPositionSizing() {
        service.setDynamicPositionSizing(true);
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

    @Test
    @DisplayName("Single attempt mode immediately exhausts symbol on initial SL hit")
    void testSingleAttemptExhaustsSymbolOnSl() {
        service.setMaxAttemptsPerSymbol(1);
        LowestVolumeSetup setup = new LowestVolumeSetup("PERSISTENT", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "PERSISTENT",
                        Instant.now(),
                        BigDecimal.valueOf(5330),
                        BigDecimal.valueOf(5340),
                        BigDecimal.valueOf(5320),
                        BigDecimal.valueOf(5335),
                        1000),
                BigDecimal.valueOf(5340.05),
                BigDecimal.valueOf(5320.00),
                BigDecimal.valueOf(5380.15));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed");
        service.getActiveSetups().put("PERSISTENT", setup);

        LowestVolumePaperPosition pos =
                service.executePositionEntry("PERSISTENT", setup, BigDecimal.valueOf(5340.05));
        service.getOpenPositions().put("PERSISTENT", pos);

        // Spot hits SL at 5315.00
        service.evaluateOpenPositions(
                java.time.LocalTime.of(10, 0),
                Map.of("PERSISTENT", new ObjectMapper().createObjectNode().put("lp", "5315.00")),
                false);

        assertThat(service.getOpenPositions()).doesNotContainKey("PERSISTENT");
        assertThat(service.getExhaustedSymbols()).contains("PERSISTENT");
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.CLOSED_SL);
    }

    @Test
    @DisplayName("Daily circuit breaker trips when cumulative realized loss exceeds limit")
    void testDailyCircuitBreakerTrips() {
        service.setMaxDailyLoss(10000.0);
        assertThat(service.isDailyCircuitBreakerTripped()).isFalse();

        // Create a closed losing position with -12,000 loss
        Instant testInstant = Instant.parse("2026-09-18T04:30:00Z");
        LowestVolumePaperPosition lossPos =
                new LowestVolumePaperPosition(
                        "LVR-1",
                        "PERSISTENT",
                        LvrInstrumentType.FUTURES,
                        LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500,
                        "PERSISTENT FUT",
                        100,
                        2,
                        LowestVolumeDirection.LONG,
                        BigDecimal.valueOf(5340.00),
                        BigDecimal.valueOf(5320.00),
                        BigDecimal.valueOf(5380.00),
                        200,
                        BigDecimal.valueOf(4000.00),
                        testInstant);
        lossPos.close(BigDecimal.valueOf(5280.00), "SPOT_SL_HIT", testInstant);
        service.getTradeHistory().add(lossPos);

        assertThat(service.calculateTodayRealizedPnl()).isLessThan(-10000.0);
        assertThat(service.isDailyCircuitBreakerTripped()).isTrue();
    }

    @Test
    @DisplayName("Live sector alignment blocks LONG entry when parent sector turns negative")
    void testLiveSectorAlignmentBlocksLongWhenSectorNegative() {
        ObjectMapper mapper = new ObjectMapper();
        // COFORGE belongs to NIFTY IT. Constituents: TCS, INFY, WIPRO, COFORGE, LTIM, PERSISTENT,
        // etc.
        // Mock quotes to simulate NIFTY IT constituents being negative (lp < c)
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        mapper.createObjectNode().put("lp", "98.00").put("c", "100.00")); // -2.0%

        boolean aligned = service.checkLiveSectorAlignment("COFORGE", LowestVolumeDirection.LONG);
        assertThat(aligned).isFalse();
    }

    @Test
    @DisplayName("Live sector alignment allows LONG entry when parent sector remains positive")
    void testLiveSectorAlignmentAllowsLongWhenSectorPositive() {
        ObjectMapper mapper = new ObjectMapper();
        // Mock quotes to simulate NIFTY IT constituents being positive (lp > c)
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        mapper.createObjectNode().put("lp", "102.50").put("c", "100.00")); // +2.5%

        boolean aligned = service.checkLiveSectorAlignment("COFORGE", LowestVolumeDirection.LONG);
        assertThat(aligned).isTrue();
    }

    @Test
    @DisplayName("Opening 15m Range Filter rejects LONG entry when spot is inside 15m range")
    void testOpening15mRangeFilterRejectsInsidePrice() {
        service.setMaxSlippagePct(2.0);
        service.setOpening15mRangeFilterEnabled(true);
        service.setPdhPdlFilterEnabled(false);
        service.setSectorMomentumFilterEnabled(false);
        ObjectMapper mapper = new ObjectMapper();
        LowestVolumeSetup setup = new LowestVolumeSetup("RELIANCE", LowestVolumeDirection.LONG);
        setup.setFirst15MinHigh(BigDecimal.valueOf(2520.00));
        setup.setFirst15MinLow(BigDecimal.valueOf(2490.00));
        setup.setTriggerCandle(
                Candle.of5m(
                        "RELIANCE",
                        Instant.now(),
                        BigDecimal.valueOf(2500),
                        BigDecimal.valueOf(2510),
                        BigDecimal.valueOf(2495),
                        BigDecimal.valueOf(2505),
                        1000),
                BigDecimal.valueOf(2510.05),
                BigDecimal.valueOf(2494.95),
                BigDecimal.valueOf(2540.25));
        setup.setLatestVwap(2500.00); // VWAP confirmed (> 2500)
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed");
        service.getActiveSetups().put("RELIANCE", setup);

        // Spot breached trigger at 2515.00, but is INSIDE 15m range [2490 - 2520] (<= 2520.00 high)
        service.checkSpotTriggerBreach(
                "RELIANCE",
                setup,
                mapper.createObjectNode().put("lp", "2515.00").put("ap", "2500.00"));

        assertThat(service.getOpenPositions()).doesNotContainKey("RELIANCE");
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.REJECTED_EXHAUSTED);
        assertThat(service.getExhaustedSymbols()).contains("RELIANCE");
    }

    @Test
    @DisplayName("Opening 15m Range Filter allows LONG entry when spot breaks out of 15m range")
    void testOpening15mRangeFilterAllowsBreakoutPrice() {
        service.setMaxSlippagePct(2.0);
        service.setOpening15mRangeFilterEnabled(true);
        service.setPdhPdlFilterEnabled(false);
        service.setSectorMomentumFilterEnabled(false);
        ObjectMapper mapper = new ObjectMapper();

        LowestVolumeSetup setup = new LowestVolumeSetup("RELIANCE", LowestVolumeDirection.LONG);
        setup.setFirst15MinHigh(BigDecimal.valueOf(2520.00));
        setup.setFirst15MinLow(BigDecimal.valueOf(2490.00));
        setup.setTriggerCandle(
                Candle.of5m(
                        "RELIANCE",
                        Instant.now(),
                        BigDecimal.valueOf(2510),
                        BigDecimal.valueOf(2522),
                        BigDecimal.valueOf(2505),
                        BigDecimal.valueOf(2520),
                        1000),
                BigDecimal.valueOf(2521.05),
                BigDecimal.valueOf(2494.95),
                BigDecimal.valueOf(2560.25));
        setup.setLatestVwap(2500.00); // VWAP confirmed
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed");
        service.getActiveSetups().put("RELIANCE", setup);

        // Spot is 2522.00 (> 2520.00 15m High and >= 2521.05 trigger) -> Confirmed breakout!
        service.checkSpotTriggerBreach(
                "RELIANCE",
                setup,
                mapper.createObjectNode().put("lp", "2522.00").put("ap", "2500.00"));

        assertThat(service.getOpenPositions()).containsKey("RELIANCE");
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.IN_POSITION);
    }

    @Test
    @DisplayName("Should reject LONG trade when spot price is below or equal to PDH (trapped in range)")
    void testRejectLongTradeWhenInsidePdhPdlRange() {
        service.setMaxSlippagePct(2.0);
        service.setPdhPdlFilterEnabled(true);
        service.setOpening15mRangeFilterEnabled(false);
        service.setSectorMomentumFilterEnabled(false);

        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setPdh(BigDecimal.valueOf(1880.00));
        setup.setPdl(BigDecimal.valueOf(1850.00));
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(1870),
                        BigDecimal.valueOf(1875),
                        BigDecimal.valueOf(1868),
                        BigDecimal.valueOf(1872),
                        5000),
                BigDecimal.valueOf(1875.05),
                BigDecimal.valueOf(1867.95),
                BigDecimal.valueOf(1889.25));
        setup.setLatestVwap(1870.00);
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("SUNPHARMA", setup);

        // LTP = 1876.00 (Breached trigger 1875.05, above VWAP 1870, but <= PDH 1880.00)
        ObjectMapper mapper = new ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "1876.00").put("ap", "1870.00"));
        when(marketDataService.resolveToken("SUNPHARMA")).thenReturn("3351");

        service.evaluateLivePriceActions();

        assertThat(service.getOpenPositions()).doesNotContainKey("SUNPHARMA");
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.REJECTED_EXHAUSTED);
        assertThat(service.getExhaustedSymbols()).contains("SUNPHARMA");
    }

    @Test
    @DisplayName("Should execute LONG trade when spot price is strictly above PDH")
    void testAllowLongTradeWhenAbovePdh() {
        service.setMaxSlippagePct(2.0);
        service.setPdhPdlFilterEnabled(true);
        service.setOpening15mRangeFilterEnabled(false);
        service.setSectorMomentumFilterEnabled(false);

        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setPdh(BigDecimal.valueOf(1870.00));
        setup.setPdl(BigDecimal.valueOf(1850.00));
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(1870),
                        BigDecimal.valueOf(1875),
                        BigDecimal.valueOf(1868),
                        BigDecimal.valueOf(1872),
                        5000),
                BigDecimal.valueOf(1875.05),
                BigDecimal.valueOf(1867.95),
                BigDecimal.valueOf(1889.25));
        setup.setLatestVwap(1870.00);
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("SUNPHARMA", setup);

        // LTP = 1876.00 (Breached trigger 1875.05, above VWAP 1870, and strictly above PDH 1870.00)
        ObjectMapper mapper = new ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "1876.00").put("ap", "1870.00"));
        when(marketDataService.resolveToken("SUNPHARMA")).thenReturn("3351");

        service.evaluateLivePriceActions();

        assertThat(service.getOpenPositions()).containsKey("SUNPHARMA");
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.IN_POSITION);
    }

    @Test
    @DisplayName("2.2 Fix: Fresh-wick does NOT trigger entry if live spot has retreated below trigger price")
    void testFreshWickDoesNotTriggerOnRetreatingPrice() {
        service.setMaxSlippagePct(2.0);
        service.setPdhPdlFilterEnabled(true);
        service.setOpening15mRangeFilterEnabled(false);
        service.setSectorMomentumFilterEnabled(false);

        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(1870),
                        BigDecimal.valueOf(1875),
                        BigDecimal.valueOf(1865),
                        BigDecimal.valueOf(1872),
                        5000),
                BigDecimal.valueOf(1875.05),
                BigDecimal.valueOf(1864.95),
                BigDecimal.valueOf(1895.25));
        setup.setSessionHighAtArming(1874.00);
        setup.setLatestVwap(1870.00);
        setup.setPdh(BigDecimal.valueOf(1870.00));
        setup.setPdl(BigDecimal.valueOf(1850.00));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("SUNPHARMA", setup);

        // Session high advanced to 1876.00 (touched trigger 1875.05), but spot has retreated to 1872.00 (below trigger)
        ObjectMapper mapper = new ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        mapper.createObjectNode()
                                .put("lp", "1872.00")
                                .put("ap", "1870.00")
                                .put("h", "1876.00")
                                .put("l", "1868.00"));
        when(marketDataService.resolveToken("SUNPHARMA")).thenReturn("3351");

        service.evaluateLivePriceActions();

        // Entry must be skipped while spot is retreating below trigger
        assertThat(service.getOpenPositions()).doesNotContainKey("SUNPHARMA");
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
    }

    @Test
    @DisplayName("Should reject SHORT trade when spot price is above or equal to PDL (trapped in range)")
    void testRejectShortTradeWhenInsidePdhPdlRange() {
        service.setMaxSlippagePct(2.0);
        service.setPdhPdlFilterEnabled(true);
        service.setOpening15mRangeFilterEnabled(false);
        service.setSectorMomentumFilterEnabled(false);

        LowestVolumeSetup setup = new LowestVolumeSetup("PVRINOX", LowestVolumeDirection.SHORT);
        setup.setPdh(BigDecimal.valueOf(1050.00));
        setup.setPdl(BigDecimal.valueOf(1000.00));
        setup.setTriggerCandle(
                Candle.of5m(
                        "PVRINOX",
                        Instant.now(),
                        BigDecimal.valueOf(1020),
                        BigDecimal.valueOf(1025),
                        BigDecimal.valueOf(1015),
                        BigDecimal.valueOf(1018),
                        5000),
                BigDecimal.valueOf(1014.95),
                BigDecimal.valueOf(1025.05),
                BigDecimal.valueOf(994.75));
        setup.setLatestVwap(1020.00);
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("PVRINOX", setup);

        // LTP = 1010.00 (Breached trigger 1014.95, below VWAP 1020, but >= PDL 1000.00 - trapped!)
        ObjectMapper mapper = new ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "1010.00").put("ap", "1020.00"));
        when(marketDataService.resolveToken("PVRINOX")).thenReturn("13147");

        service.evaluateLivePriceActions();

        assertThat(service.getOpenPositions()).doesNotContainKey("PVRINOX");
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.REJECTED_EXHAUSTED);
        assertThat(service.getExhaustedSymbols()).contains("PVRINOX");
    }

    @Test
    @DisplayName("H7 Fix: PDH/PDL filter must fail closed and reject trade if PDH is null after fetch attempts")
    void testPdhPdlFailsClosedWhenPdhNull() {
        service.setMaxSlippagePct(2.0);
        service.setPdhPdlFilterEnabled(true);
        service.setOpening15mRangeFilterEnabled(false);
        service.setSectorMomentumFilterEnabled(false);

        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(1870),
                        BigDecimal.valueOf(1875),
                        BigDecimal.valueOf(1868),
                        BigDecimal.valueOf(1872),
                        5000),
                BigDecimal.valueOf(1875.05),
                BigDecimal.valueOf(1867.95),
                BigDecimal.valueOf(1889.25));
        setup.setLatestVwap(1870.00);
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        // setup.getPdh() remains null
        service.getActiveSetups().put("SUNPHARMA", setup);

        ObjectMapper mapper = new ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "1876.00").put("ap", "1870.00"));
        when(marketDataService.resolveToken("SUNPHARMA")).thenReturn("3351");
        when(marketDataService.fetchDailyCandles("SUNPHARMA", 5)).thenReturn(java.util.Collections.emptyList());

        service.evaluateLivePriceActions();
        // N5: first failed lazy fetch only retries — the SECOND attempt exhausts (fail-closed).
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        service.evaluateLivePriceActions();

        assertThat(service.getOpenPositions()).doesNotContainKey("SUNPHARMA");
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.REJECTED_EXHAUSTED);
        assertThat(service.getExhaustedSymbols()).contains("SUNPHARMA");
    }

    @Test
    @DisplayName(
            "N1 Fix: fresh wick past the arming baseline triggers; a stale session high never does")
    void testFreshWickRequiresSessionExtremeToAdvanceAfterArming() {
        service.setMaxSlippagePct(2.0);
        service.setOpening15mRangeFilterEnabled(false);
        service.setPdhPdlFilterEnabled(false);
        service.setSectorMomentumFilterEnabled(false);

        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setLatestVwap(1870.00);
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(1870),
                        BigDecimal.valueOf(1875),
                        BigDecimal.valueOf(1865),
                        BigDecimal.valueOf(1872),
                        5000),
                BigDecimal.valueOf(1875.05),
                BigDecimal.valueOf(1864.95),
                BigDecimal.valueOf(1895.25));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("SUNPHARMA", setup);
        when(marketDataService.resolveToken("SUNPHARMA")).thenReturn("3351");

        ObjectMapper mapper = new ObjectMapper();
        // Tick 1: first tick after arming captures the baseline session high (1874).
        var armTick =
                mapper.createObjectNode()
                        .put("lp", "1872.00")
                        .put("ap", "1870.00")
                        .put("h", "1874.00")
                        .put("l", "1866.00");
        // Tick 2: session high did NOT advance — stale relative to baseline → no trigger.
        var staleTick =
                mapper.createObjectNode()
                        .put("lp", "1872.00")
                        .put("ap", "1870.00")
                        .put("h", "1874.00")
                        .put("l", "1866.00");
        // Tick 3: session high ADVANCED past baseline (1880 ≥ trigger) while LTP pulled back
        // below the trigger — a genuine fresh wick breach.
        var freshTick =
                mapper.createObjectNode()
                        .put("lp", "1872.00")
                        .put("ap", "1870.00")
                        .put("h", "1880.00")
                        .put("l", "1866.00");
        when(marketDataService.fetchQuote(any(), any())).thenReturn(armTick, staleTick, freshTick);

        service.evaluateLivePriceActions();
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        assertThat(service.getOpenPositions()).doesNotContainKey("SUNPHARMA");

        service.evaluateLivePriceActions();
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        assertThat(service.getOpenPositions()).doesNotContainKey("SUNPHARMA");

        service.evaluateLivePriceActions();
        assertThat(service.getOpenPositions()).containsKey("SUNPHARMA");
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.IN_POSITION);
    }

    @Test
    @DisplayName(
            "N2 Fix: OI_SPURTS scan fails loudly when no quote carries OI — no silent fallback")
    void testOiSpurtsScanFailsLoudlyWhenOiMissing() {
        service.setScannerMode("OI_SPURTS");
        when(marketDataService.resolveToken(any())).thenReturn("1234");
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        new ObjectMapper().createObjectNode().put("lp", "102.0").put("c", "100.0"));

        service.runMorningUniverseScan();

        assertThat(service.isUniverseScanCompletedToday()).isFalse();
        assertThat(service.getActiveSetups()).isEmpty();
        assertThat(service.getCandidateReservoir()).isEmpty();
    }

    @Test
    @DisplayName("N2 Fix: OI_SPURTS scan selects top candidates when quotes carry real oi/poi")
    void testOiSpurtsScanSelectsCandidatesWithOiData() {
        service.setScannerMode("OI_SPURTS");
        when(marketDataService.resolveToken(any())).thenReturn("1234");
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        new ObjectMapper()
                                .createObjectNode()
                                .put("lp", "102.0")
                                .put("c", "100.0")
                                .put("oi", 110000)
                                .put("poi", 100000));

        service.runMorningUniverseScan();

        assertThat(service.isUniverseScanCompletedToday()).isTrue();
        assertThat(service.getActiveSetups()).hasSize(5);
        assertThat(service.getActiveSetups().values())
                .allSatisfy(
                        s -> {
                            assertThat(s.getOiChangePct()).isNotNull();
                            assertThat(s.getOiChangePct()).isCloseTo(10.0, org.assertj.core.data.Offset.offset(0.01));
                        });
    }

    @Test
    @DisplayName("N6/H10 Fix: neutral-breadth scan stands down for the day and refuses entries")
    void testStandDownAfterNeutralSentimentRefusesEntries() {
        when(marketDataService.resolveToken(any())).thenReturn("1234");
        // Flat quotes (lp == c) → 0% advance breadth across the universe → sentiment NONE.
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        new ObjectMapper().createObjectNode().put("lp", "100.0").put("c", "100.0"));

        service.runMorningUniverseScan();

        assertThat(service.isUniverseScanCompletedToday()).isTrue();
        assertThat(service.isStandDownToday()).isTrue();
        assertThat(service.getActiveSetups()).isEmpty();
        assertThat(service.getCandidateReservoir()).isEmpty();

        // N6: even a fully armed trigger cannot enter while standing down.
        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(1870),
                        BigDecimal.valueOf(1875),
                        BigDecimal.valueOf(1865),
                        BigDecimal.valueOf(1872),
                        5000),
                BigDecimal.valueOf(1875.05),
                BigDecimal.valueOf(1864.95),
                BigDecimal.valueOf(1895.25));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("SUNPHARMA", setup);

        assertThat(service.executePositionEntry("SUNPHARMA", setup, BigDecimal.valueOf(1876.00)))
                .isNull();
        assertThat(service.getOpenPositions()).doesNotContainKey("SUNPHARMA");

        // H10: stand-down is sticky — the reservoir is never promoted back in.
        service.getCandidateReservoir().add("TATAMOTORS");
        service.replenishActiveCandidatesIfNeeded(LocalTime.of(10, 15));
        assertThat(service.getActiveSetups()).doesNotContainKey("TATAMOTORS");
    }

    @Test
    @DisplayName(
            "M9 Fix: sector NO_DATA defers on a cooldown and fails closed after bounded retries")
    void testSectorGateNoDataDefersThenExhausts() {
        service.setSectorMomentumFilterEnabled(true);
        service.setMaxSlippagePct(2.0);
        service.setOpening15mRangeFilterEnabled(false);
        service.setPdhPdlFilterEnabled(false);

        LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
        setup.setLatestVwap(1870.00);
        setup.setTriggerCandle(
                Candle.of5m(
                        "SUNPHARMA",
                        Instant.now(),
                        BigDecimal.valueOf(1870),
                        BigDecimal.valueOf(1875),
                        BigDecimal.valueOf(1865),
                        BigDecimal.valueOf(1872),
                        5000),
                BigDecimal.valueOf(1875.05),
                BigDecimal.valueOf(1864.95),
                BigDecimal.valueOf(1895.25));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("SUNPHARMA", setup);
        when(marketDataService.resolveToken(any())).thenReturn("3351");
        // LTP present but no previous close 'c' → every sector constituent yields no usable
        // breadth data → SectorGate.NO_DATA.
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(new ObjectMapper().createObjectNode().put("lp", "1876.00"));

        Instant base = Instant.parse("2026-09-18T04:30:00Z");
        // Attempts 1..5: deferred on the 60s cooldown, never entering, never silently allowed.
        for (int i = 1; i <= 5; i++) {
            service.setClock(
                    Clock.fixed(base.plus(i * 70L, ChronoUnit.SECONDS), IST));
            service.evaluateLivePriceActions();
            assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
            assertThat(service.getOpenPositions()).doesNotContainKey("SUNPHARMA");
        }

        // Attempt 6: bounded retries exhausted → fail closed.
        service.setClock(Clock.fixed(base.plus(6 * 70L, ChronoUnit.SECONDS), IST));
        service.evaluateLivePriceActions();
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.REJECTED_EXHAUSTED);
        assertThat(service.getExhaustedSymbols()).contains("SUNPHARMA");
        assertThat(service.getOpenPositions()).doesNotContainKey("SUNPHARMA");
    }

    @Test
    @DisplayName(
            "M2 Fix: circuit breaker latches after tripping even when P&L recovers; only an explicit reset clears it")
    void testCircuitBreakerLatchesAfterTrip() {
        service.setMaxDailyLoss(10000.0);
        Instant testInstant = Instant.parse("2026-09-18T04:30:00Z");
        LowestVolumePaperPosition lossPos =
                new LowestVolumePaperPosition(
                        "LVR-1",
                        "PERSISTENT",
                        LvrInstrumentType.FUTURES,
                        LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500,
                        "PERSISTENT FUT",
                        100,
                        2,
                        LowestVolumeDirection.LONG,
                        BigDecimal.valueOf(5340.00),
                        BigDecimal.valueOf(5320.00),
                        BigDecimal.valueOf(5380.00),
                        200,
                        BigDecimal.valueOf(4000.00),
                        testInstant);
        lossPos.close(BigDecimal.valueOf(5280.00), "SPOT_SL_HIT", testInstant);
        service.getTradeHistory().add(lossPos);
        assertThat(service.isDailyCircuitBreakerTripped()).isTrue();

        // P&L recovers above water — the breaker must STAY tripped for the day (latched).
        service.getTradeHistory().clear();
        LowestVolumePaperPosition winPos =
                new LowestVolumePaperPosition(
                        "LVR-2",
                        "PERSISTENT",
                        LvrInstrumentType.FUTURES,
                        LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500,
                        "PERSISTENT FUT",
                        100,
                        2,
                        LowestVolumeDirection.LONG,
                        BigDecimal.valueOf(5340.00),
                        BigDecimal.valueOf(5320.00),
                        BigDecimal.valueOf(5380.00),
                        200,
                        BigDecimal.valueOf(4000.00),
                        testInstant);
        winPos.close(BigDecimal.valueOf(5440.00), "TARGET_1_2_FULL_EXIT", testInstant);
        service.getTradeHistory().add(winPos);
        assertThat(service.calculateTodayRealizedPnl()).isPositive();
        assertThat(service.isDailyCircuitBreakerTripped()).isTrue();

        // Only an explicit reset clears the latch.
        service.resetDaily(true);
        assertThat(service.isDailyCircuitBreakerTripped()).isFalse();
    }

    @Test
    @DisplayName(
            "M10 Fix: persisted state round-trips — open position, history and exhausted symbols survive a restart")
    void testPersistedStateRoundTrip() throws Exception {
        java.nio.file.Path stateFile =
                java.nio.file.Files.createTempDirectory("lvr-m10").resolve("lvr-state.json");
        service.setStateFilePath(stateFile.toString());
        service.setStatePersistenceEnabled(true);

        LowestVolumeSetup setup = new LowestVolumeSetup("RELIANCE", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "RELIANCE",
                        Instant.now(),
                        BigDecimal.valueOf(2500),
                        BigDecimal.valueOf(2510),
                        BigDecimal.valueOf(2495),
                        BigDecimal.valueOf(2505),
                        1000),
                BigDecimal.valueOf(2510.05),
                BigDecimal.valueOf(2494.95),
                BigDecimal.valueOf(2540.25));
        LowestVolumePaperPosition pos =
                service.executePositionEntry("RELIANCE", setup, BigDecimal.valueOf(2510.05));
        assertThat(pos).isNotNull();

        Instant testInstant = Instant.parse("2026-09-18T04:30:00Z");
        LowestVolumePaperPosition closedPos =
                new LowestVolumePaperPosition(
                        "LVR-99",
                        "TCS",
                        LvrInstrumentType.FUTURES,
                        LvrExitMode.FULL_TARGET_1_2,
                        "TCS FUT",
                        80,
                        2,
                        LowestVolumeDirection.LONG,
                        BigDecimal.valueOf(4000.00),
                        BigDecimal.valueOf(3980.00),
                        BigDecimal.valueOf(4060.00),
                        160,
                        BigDecimal.valueOf(3200.00),
                        testInstant);
        closedPos.close(BigDecimal.valueOf(4040.00), "SPOT_SL_HIT", testInstant);
        service.getTradeHistory().add(closedPos);
        service.getExhaustedSymbols().add("TATAMOTORS");
        service.persistState();
        assertThat(java.nio.file.Files.exists(stateFile)).isTrue();

        LowestVolumeReversalService restored =
                new LowestVolumeReversalService(marketDataService, taService, null, config, null);
        restored.setClock(Clock.fixed(Instant.parse("2026-09-18T04:30:00Z"), IST));
        restored.setStateFilePath(stateFile.toString());
        restored.setStatePersistenceEnabled(true);
        restored.restorePersistedState();

        assertThat(restored.getOpenPositions()).containsKey("RELIANCE");
        LowestVolumePaperPosition rp = restored.getOpenPositions().get("RELIANCE");
        assertThat(rp.getStockEntryPrice()).isEqualByComparingTo(pos.getStockEntryPrice());
        assertThat(rp.getCurrentStockSl()).isEqualByComparingTo(pos.getCurrentStockSl());
        assertThat(rp.getRemainingQuantity()).isEqualTo(pos.getRemainingQuantity());
        assertThat(rp.isClosed()).isFalse();
        assertThat(restored.getTradeHistory()).hasSize(1);
        assertThat(restored.getTradeHistory().get(0).getSymbol()).isEqualTo("TCS");
        assertThat(restored.getExhaustedSymbols()).contains("TATAMOTORS");
    }

    @Test
    @DisplayName(
            "H6 Fix: resetDaily snapshots positions/history to disk BEFORE clearing — nothing is silently lost")
    void testResetDailySnapshotsStateBeforeClearing() throws Exception {
        java.nio.file.Path stateFile =
                java.nio.file.Files.createTempDirectory("lvr-h6").resolve("lvr-state.json");
        service.setStateFilePath(stateFile.toString());

        LowestVolumeSetup setup = new LowestVolumeSetup("RELIANCE", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                Candle.of5m(
                        "RELIANCE",
                        Instant.now(),
                        BigDecimal.valueOf(2500),
                        BigDecimal.valueOf(2510),
                        BigDecimal.valueOf(2495),
                        BigDecimal.valueOf(2505),
                        1000),
                BigDecimal.valueOf(2510.05),
                BigDecimal.valueOf(2494.95),
                BigDecimal.valueOf(2540.25));
        LowestVolumePaperPosition pos =
                service.executePositionEntry("RELIANCE", setup, BigDecimal.valueOf(2510.05));
        assertThat(pos).isNotNull();
        service.getExhaustedSymbols().add("TATAMOTORS");

        service.resetDaily(true);

        assertThat(service.getOpenPositions()).isEmpty();
        assertThat(java.nio.file.Files.exists(stateFile)).isTrue();
        com.tradingbot.persistence.LvrStateStore.DailyState state =
                com.tradingbot.persistence.LvrStateStore.load(stateFile);
        assertThat(state).isNotNull();
        assertThat(state.openPositions.size() + state.tradeHistory.size()).isEqualTo(1);
        assertThat(state.exhaustedSymbols).contains("TATAMOTORS");
    }

    @Test
    @DisplayName("H7 Fix: replenished setups get PDH/PDL initialized instead of failing closed forever")
    void testReplenishedSetupGetsPdhPdl() {
        service.setPdhPdlFilterEnabled(true);
        service.getActiveSetups().clear();
        service.getCandidateReservoir().clear();
        service.getCandidateReservoir().add("TATAMOTORS");
        service.getCandidateReservoir().add("BAJFINANCE");

        Candle prevDay =
                Candle.of5m(
                        "TATAMOTORS",
                        Instant.parse("2026-09-17T00:00:00Z"),
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(105),
                        BigDecimal.valueOf(99),
                        BigDecimal.valueOf(104),
                        1000);
        when(marketDataService.fetchDailyCandles(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(prevDay));

        service.replenishActiveCandidatesIfNeeded(LocalTime.of(10, 15));

        LowestVolumeSetup tata = service.getActiveSetups().get("TATAMOTORS");
        assertThat(tata).isNotNull();
        assertThat(tata.getPdh()).isEqualByComparingTo(BigDecimal.valueOf(105));
        assertThat(tata.getPdl()).isEqualByComparingTo(BigDecimal.valueOf(99));
    }
}
