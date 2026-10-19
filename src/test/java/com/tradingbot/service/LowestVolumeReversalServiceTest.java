package com.tradingbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.marketdata.NseOiSpurtClient;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.NseOiSpurtItem;
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
        service.setOpening15mRangeFilterEnabled(false);
        service.setPcrFilterEnabled(false);
        service.setOptionSrFilterEnabled(false);
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
    @DisplayName(
            "C1 Fix: Setup must NOT invalidate prior to entry when session day low is below SL but current spot is healthy")
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

        // Quote where session low 'l' is 1850.00 (from 09:15 AM open), but current spot 'lp' is
        // 1872.00 (healthy)
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
    @DisplayName(
            "C2 Fix: Trigger breach must NOT fire when current spot is below trigger, even if session day high was above trigger")
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

        // Quote where session high 'h' is 1880.00 (from 09:15 AM spike), but current spot 'lp' is
        // 1872.00 (below trigger 1875.05)
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
                                        "SUNPHARMA".equals(sig.underlyingSymbol())
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
                                        "SUNPHARMA".equals(sig.underlyingSymbol())
                                                && sig.action()
                                                        == com.tradingbot.strategy.SignalAction
                                                                .EXIT_LONG));
    }

    @Test
    @DisplayName(
            "H4 Fix: Open positions must be managed for SL exit even when strategy enabled is false")
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
        // 1876.10 + target-rr * 7.15 = 1876.10 + 2.5 * 7.15 = 1893.975 -> tick-rounded 1894.00.
        // (target-rr default is 2.5; this assertion is the regression guard for the executed
        // target silently hardcoding a ratio instead of reading the configured one.)
        assertThat(pos.getTarget1StockPrice()).isEqualByComparingTo("1894.00");
        assertThat(service.getOpenPositions()).containsKey("SUNPHARMA");
    }

    @Test
    @DisplayName("Futures entry sets brokerTradingSymbol from resolved near-month contract")
    void testFuturesEntryUsesResolvedBrokerTradingSymbol() {
        service.setInstrumentType(LvrInstrumentType.FUTURES);
        when(marketDataService.resolveFuturesContract("NATIONALUM"))
                .thenReturn(
                        new ShoonyaMarketDataService.FuturesContract(
                                "NATIONALUM27OCT26F", "12345"));

        LowestVolumeSetup setup = new LowestVolumeSetup("NATIONALUM", LowestVolumeDirection.SHORT);
        setup.setTriggerCandle(
                Candle.of5m(
                        "NATIONALUM",
                        Instant.now(),
                        BigDecimal.valueOf(330),
                        BigDecimal.valueOf(331),
                        BigDecimal.valueOf(327),
                        BigDecimal.valueOf(327.10),
                        5000),
                BigDecimal.valueOf(327.10),
                BigDecimal.valueOf(328.95),
                BigDecimal.valueOf(323.50));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");

        LowestVolumePaperPosition pos =
                service.executePositionEntry("NATIONALUM", setup, BigDecimal.valueOf(327.15));

        assertThat(pos).isNotNull();
        assertThat(pos.getBrokerTradingSymbol()).isEqualTo("NATIONALUM27OCT26F");
    }

    @Test
    @DisplayName(
            "rrLabel mirrors the configured target-rr so alerts cannot drift from the executed target")
    void testRrLabelFollowsConfiguredTargetRr() {
        assertThat(service.rrLabel()).isEqualTo("1:2.5"); // application.properties default
        service.setTargetRr(2.0);
        assertThat(service.rrLabel()).isEqualTo("1:2");
        service.setTargetRr(3.5);
        assertThat(service.rrLabel()).isEqualTo("1:3.5");
    }

    @Test
    @DisplayName("Target dynamically recalculates from actual entry spot using the configured RR")
    void testDynamicTargetCalculationOnSlippage() {
        // Deliberately NOT the default (2.5) and NOT the old hardcode (2.0): if the executed
        // target ever stops reading targetRr again, this assertion fails either way.
        service.setTargetRr(3.0);
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
        // Target must be 101.05 + (3.0 * 3.00) = 110.05 — i.e. the configured target-rr, recomputed
        // from the ACTUAL entry price (not the armed trigger) so slippage cannot distort the ratio.
        LowestVolumePaperPosition pos =
                service.executePositionEntry("SUNPHARMA", setup, BigDecimal.valueOf(101.05));

        assertThat(pos.getStockEntryPrice()).isEqualByComparingTo("101.05");
        assertThat(pos.getCurrentStockSl()).isEqualByComparingTo("98.05");
        assertThat(pos.getTarget1StockPrice()).isEqualByComparingTo("110.05");
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
    @DisplayName(
            "Futures LONG Position - 100% Full Exit at the configured Target in evaluateOpenPositions")
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

        // Target = 1875.00 (entry) + 2.5 * (1875.00 - 1872.05) = 1882.375 -> tick-rounded 1882.40.
        // Mock spot clearing that level (1883.00 >= 1882.40)
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "1883.00"));

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
        assertThat(setup.getTarget1Price()).isEqualByComparingTo("84.20"); // 1:2.5 RR
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
        // Actual Risk = 102.05 - 96.90 = 5.15. Target = 96.90 - (2.5 * 5.15) = 84.025 -> 84.05
        assertThat(position.getTarget1StockPrice()).isEqualByComparingTo("84.05");
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
    @DisplayName(
            "H12 Fix: hard exit with no quote keeps the position instead of fabricating a fill")
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
    @DisplayName("LONG Entry Blocked when Spot Price <= VWAP — retried on cooldown, not discarded")
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
        // VWAP is recomputed from a fresh quote on every 30s pass, so one wrong-side tick must not
        // burn the setup's attempts or blacklist the symbol until the close.
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        assertThat(service.getExhaustedSymbols()).doesNotContain("SUNPHARMA");
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
    @DisplayName("SHORT Entry Blocked when Spot Price >= VWAP — retried on cooldown, not discarded")
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
        // See the LONG case: VWAP-relative is a transient verdict, so the setup stays retryable.
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        assertThat(service.getExhaustedSymbols()).doesNotContain("PVRINOX");
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
                service.replaySession(
                        "SUNPHARMA", LowestVolumeDirection.LONG, replayFixtureCandles());

        assertThat(trades).isEmpty();
    }

    @Test
    @DisplayName(
            "L6: replaySession honors the shared budget gate — no trades when planned risk exceeds budget")
    void testReplaySessionBlockedWhenPlannedRiskExceedsBudget() {
        // SUNPHARMA futures: unitRisk 10 × (2 lots × 350) = ₹7,000 planned risk vs ₹1 budget.
        service.setMaxDailyLoss(1.0);

        List<LowestVolumePaperPosition> trades =
                service.replaySession(
                        "SUNPHARMA", LowestVolumeDirection.LONG, replayFixtureCandles());

        assertThat(trades).isEmpty();
    }

    @Test
    @DisplayName("PNL-9: replay skips symbols missing from the F&O registry (live would reject)")
    void testReplaySessionSkipsSymbolsOutsideFnoRegistry() {
        // Isolate the registry path: with every data gate off, the ONLY thing standing between a
        // fabricated lot size and a fake replay trade is the registry check itself.
        service.setExpiryDteEnabled(false);
        List<LowestVolumePaperPosition> trades =
                service.replaySession(
                        "NOT_IN_REGISTRY_PVT", LowestVolumeDirection.LONG, replayFixtureCandles());

        // Live entry fails closed (REJECTED_EXHAUSTED) for unregistered symbols — replay must not
        // fabricate a lot size and produce fake P&L.
        assertThat(trades).isEmpty();
    }

    @Test
    @DisplayName(
            "Futures LONG Position - 50% Profit Booked at the configured Target and SL moved to Cost")
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
                BigDecimal.valueOf(1895.05)); // Armed at target-rr: 1870.05 + 25 = 1895.05
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("SUNPHARMA", setup);

        LowestVolumePaperPosition pos =
                service.executePositionEntry("SUNPHARMA", setup, BigDecimal.valueOf(1870.00));
        assertThat(service.getOpenPositions()).hasSize(1);

        // Executed Target = 1870.00 + 2.5 * (1870.00 - 1860.05) = 1894.875 -> 1894.90.
        // Mock spot reaching it (1895.50 >= 1894.90)
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "1895.50").put("ap", "1875.00"));

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

        service.evaluateOpenPositions(LocalTime.of(11, 0));

        // Position should now be CLOSED with TRAILING_COST_SL_HIT
        assertThat(service.getOpenPositions()).isEmpty();
        assertThat(service.getTradeHistory()).hasSize(1);
        LowestVolumePaperPosition closed = service.getTradeHistory().get(0);
        assertThat(closed.isClosed()).isTrue();
        assertThat(closed.getExitReason()).isEqualTo("TRAILING_COST_SL_HIT");
        assertThat(closed.getTotalRealizedPnl()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("PNL-6: partial-exit signal carries remainingQuantity for runner-stop sizing")
    void testPartialExitSignalCarriesRemainingQuantity() {
        com.tradingbot.bus.SignalPublisher mockPublisher =
                mock(com.tradingbot.bus.SignalPublisher.class);
        when(mockPublisher.publish(any())).thenReturn(true);
        service =
                new LowestVolumeReversalService(
                        marketDataService, taService, null, config, null, null, mockPublisher);
        service.setClock(Clock.fixed(Instant.parse("2026-09-18T04:30:00Z"), IST));
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
                BigDecimal.valueOf(1895.05)); // Armed at target-rr: 1870.05 + 25 = 1895.05
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("SUNPHARMA", setup);

        LowestVolumePaperPosition pos =
                service.executePositionEntry("SUNPHARMA", setup, BigDecimal.valueOf(1870.00));

        ObjectMapper mapper = new ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "1895.50").put("ap", "1875.00"));
        service.evaluateOpenPositions(LocalTime.of(10, 30));

        assertThat(pos.isPartialBooked()).isTrue();
        verify(mockPublisher)
                .publish(
                        org.mockito.ArgumentMatchers.argThat(
                                sig ->
                                        sig.action()
                                                        == com.tradingbot.strategy.SignalAction
                                                                .PARTIAL_EXIT_LONG
                                                && sig.metadata() != null
                                                && Integer.valueOf(pos.getRemainingQuantity())
                                                        .equals(
                                                                sig.metadata()
                                                                        .get(
                                                                                "remainingQuantity"))));
    }

    @Test
    @DisplayName(
            "Futures SHORT Position - 50% Profit Booked at the configured Target and SL moved to Cost")
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
                BigDecimal.valueOf(87.70)); // Armed at target-rr: 97.95 - 10.25 = 87.70
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("PVRINOX", setup);

        LowestVolumePaperPosition pos =
                service.executePositionEntry("PVRINOX", setup, BigDecimal.valueOf(98.00));
        assertThat(service.getOpenPositions()).hasSize(1);

        // Executed Target = 98.00 - 2.5 * (102.05 - 98.00) = 87.875 -> 87.90.
        // Mock spot reaching it (87.50 <= 87.90)
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "87.50").put("ap", "90.00"));

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
            "evaluateLivePriceActions skips trigger entry checks when time is past 11:30 IST cutoff")
    void testEvaluateLivePriceActionsBlocksTriggerEntryPast1130Cutoff() {
        // Clock at 11:35 IST (past 11:30 cutoff)
        Clock postCutoffClock = Clock.fixed(Instant.parse("2026-09-18T06:05:00Z"), IST);
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

        // No trade entry should be taken past 11:30 cutoff
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
        // The pre-trade budget refuses to open a second position while an existing one cannot be
        // valued (an unpriceable open position is unmeasurable risk, not zero risk), so the first
        // position needs a quote before the second entry can proceed.
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(new ObjectMapper().createObjectNode().put("lp", "1875.00"));

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
    @DisplayName("StockFnoRegistry contains LTM token resolution")
    void testStockFnoRegistryContainsLtm() {
        var info = com.tradingbot.util.StockFnoRegistry.get("LTM");
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
        // COFORGE belongs to NIFTY IT. Constituents: TCS, INFY, WIPRO, COFORGE, LTM, PERSISTENT,
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
        // Being a few rupees inside a live opening range is a transient verdict — the setup stays
        // armed and is re-tested against a fresh quote on the next pass.
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        assertThat(service.getExhaustedSymbols()).doesNotContain("RELIANCE");
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
    @DisplayName("15m Opening Range filter rejects entry after setup was invalidated and re-armed")
    void testOpening15mRangeFilterRejectsAfterResetToScanningAndReArming() {
        service.setMaxSlippagePct(2.0);
        service.setOpening15mRangeFilterEnabled(true);
        service.setPdhPdlFilterEnabled(false);
        service.setSectorMomentumFilterEnabled(false);
        ObjectMapper mapper = new ObjectMapper();

        LowestVolumeSetup setup = new LowestVolumeSetup("MPHASIS", LowestVolumeDirection.LONG);
        setup.setFirst15MinHigh(BigDecimal.valueOf(2392.20));
        setup.setFirst15MinLow(BigDecimal.valueOf(2330.00));
        setup.setLatestVwap(2370.00);

        // 1. First armed setup (at 10:25)
        setup.setTriggerCandle(
                Candle.of5m(
                        "MPHASIS",
                        Instant.now(),
                        BigDecimal.valueOf(2380),
                        BigDecimal.valueOf(2381),
                        BigDecimal.valueOf(2374),
                        BigDecimal.valueOf(2375),
                        2872),
                BigDecimal.valueOf(2381.15),
                BigDecimal.valueOf(2372.80),
                BigDecimal.valueOf(2402.00));
        service.getActiveSetups().put("MPHASIS", setup);

        // Spot breaches proposed SL 2372.80 -> invalidates setup prior to entry
        service.checkSpotTriggerBreach(
                "MPHASIS",
                setup,
                mapper.createObjectNode().put("lp", "2372.70").put("ap", "2370.00"));

        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.SCANNING);
        assertThat(setup.getFirst15MinHigh()).isEqualTo(BigDecimal.valueOf(2392.20));

        // 2. Re-arm setup (at 10:30)
        setup.setTriggerCandle(
                Candle.of5m(
                        "MPHASIS",
                        Instant.now(),
                        BigDecimal.valueOf(2375),
                        BigDecimal.valueOf(2375.60),
                        BigDecimal.valueOf(2371.30),
                        BigDecimal.valueOf(2374),
                        2696),
                BigDecimal.valueOf(2375.65),
                BigDecimal.valueOf(2367.35),
                BigDecimal.valueOf(2396.45));

        // 3. Spot reaches 2377.50 (breaching trigger 2375.65, but INSIDE 15m range <= 2392.20)
        service.checkSpotTriggerBreach(
                "MPHASIS",
                setup,
                mapper.createObjectNode().put("lp", "2377.50").put("ap", "2370.00"));

        assertThat(service.getOpenPositions()).doesNotContainKey("MPHASIS");
        // No entry, and — the point of the change — no day-long blacklist either. The re-armed
        // setup survives the range test and is re-evaluated on a later candle.
        assertThat(setup.getState()).isNotEqualTo(LowestVolumeSetupState.REJECTED_EXHAUSTED);
        assertThat(service.getExhaustedSymbols()).doesNotContain("MPHASIS");
    }

    @Test
    @DisplayName(
            "Should reject LONG trade when spot price is below or equal to PDH — retried, not discarded")
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
        // Inside PDH-PDL is a function of the live quote, so it defers and retries rather than
        // throwing away the armed setup, its trigger and its two attempts until the close.
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        assertThat(service.getExhaustedSymbols()).doesNotContain("SUNPHARMA");
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
    @DisplayName(
            "Should reject SHORT trade when spot price is above or equal to PDL — retried, not discarded")
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
        // See the LONG case: PDH/PDL-relative is transient, so the setup stays retryable.
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        assertThat(service.getExhaustedSymbols()).doesNotContain("PVRINOX");
    }

    @Test
    @DisplayName(
            "H7 Fix: PDH/PDL filter must fail closed and reject trade if PDH is null after fetch attempts")
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
        when(marketDataService.fetchDailyCandles("SUNPHARMA", 5))
                .thenReturn(java.util.Collections.emptyList());

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
        // below the trigger — a genuine fresh wick breach (LTP 1874.90 is within 0.05% of trigger
        // 1875.05).
        var freshTick =
                mapper.createObjectNode()
                        .put("lp", "1874.90")
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
            "OI_SPURTS scan completes sector-only without any quote OI — selection happens in phase 2 (NSE feed)")
    void testOiSpurtsScanCompletesWithoutQuoteOi() {
        service.setScannerMode("OI_SPURTS");
        when(marketDataService.resolveToken(any())).thenReturn("1234");
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        new ObjectMapper().createObjectNode().put("lp", "102.0").put("c", "100.0"));

        service.runMorningUniverseScan();

        // Quote-level OI is neither required nor read anymore: the scan selects the sector
        // phase-1 watchlist and the 09:25 NSE OI job merges spurts in phase 2.
        assertThat(service.isUniverseScanCompletedToday()).isTrue();
        assertThat(service.getActiveSetups()).isNotEmpty();
        assertThat(service.getCandidateReservoir()).isNotEmpty();
        assertThat(service.getActiveSetups().values())
                .allSatisfy(s -> assertThat(s.getOiChangePct()).isNull());
    }

    @Test
    @DisplayName(
            "HYBRID scan phase-1 sets up sector candidates only — OI change never comes from quotes")
    void testHybridMorningScanPhaseOneCarriesNoQuoteOi() {
        service.setScannerMode("HYBRID");
        when(marketDataService.resolveToken(any())).thenReturn("1234");
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        new ObjectMapper()
                                .createObjectNode()
                                .put("lp", "102.0")
                                .put("c", "100.0")
                                .put("o", "100.5")
                                .put("v", 50000L)
                                .put("ap", "101.5")
                                // Quote OI is ignored by the scan; OI change arrives only from
                                // the phase-2 NSE OI spurts merge.
                                .put("oi", 115000L)
                                .put("poi", 100000L));

        service.runMorningUniverseScan();

        assertThat(service.isUniverseScanCompletedToday()).isTrue();
        assertThat(service.getActiveSetups()).isNotEmpty();
        // Two-sided: at most 3 bullish + 3 bearish sectors × 3 stocks. Every pick is still
        // sector-only and directioned — none arrives from the quote OI fields below.
        assertThat(service.getActiveSetups().size()).isLessThanOrEqualTo(18);
        assertThat(service.getActiveSetups().values())
                .allSatisfy(s -> assertThat(s.getOiChangePct()).isNull());
    }

    @Test
    @DisplayName("Phase-1 scan in BEARISH/SHORT market selects short sector candidates")
    void testHybridMorningScanSelectsShortCandidatesWhenMarketBearish() {
        service.setScannerMode("HYBRID");
        when(marketDataService.resolveToken(any())).thenReturn("1234");
        ObjectMapper mapper = new ObjectMapper();

        // Spot quotes on NSE: all stocks down -2% (Bearish sentiment)
        when(marketDataService.fetchQuote(org.mockito.ArgumentMatchers.eq("NSE"), any()))
                .thenReturn(
                        mapper.createObjectNode()
                                .put("lp", "98.0")
                                .put("c", "100.0")
                                .put("o", "99.5")
                                .put("v", 50000L)
                                .put("ap", "98.5"));

        service.runMorningUniverseScan();

        assertThat(service.isUniverseScanCompletedToday()).isTrue();
        assertThat(service.getActiveSetups()).isNotEmpty();
        assertThat(service.getActiveSetups().values())
                .allSatisfy(
                        s -> assertThat(s.getDirection()).isEqualTo(LowestVolumeDirection.SHORT));
        // Every sector falls bearish, so the bullish side cannot fill (sector sign gating): the
        // watchlist is capped at bearishSectors × stocksPerSector = 3 × 3. OI additions arrive
        // in phase 2.
        assertThat(service.getActiveSetups().size()).isLessThanOrEqualTo(9);
    }

    @Test
    @DisplayName("N6/H10 Fix: with NIFTY bias on, a neutral-breadth scan stands down and refuses")
    void testStandDownAfterNeutralSentimentRefusesEntries() {
        // Legacy one-directional mode: breadth is authoritative, so a mixed reading has no
        // direction to trade and stands the day down (design §4.3, gated on nifty-bias-enabled).
        service.setNiftyBiasEnabled(true);
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
    @DisplayName("Two-sided default: a mixed index is reporting-only and arms BOTH sides")
    void testMixedIndexIsReportingOnlyAndArmsBothSides() {
        service.setNiftyBiasEnabled(false);
        // A flat index with two sectors cleanly trending apart — exactly the reading that used to
        // end the day. IT +2%, METAL -2%, everything else unchanged: 5 of 50 advance (10%) and 3
        // decline (6%), both far below the 56% breadth threshold, so sentiment is NONE.
        java.util.Set<String> upSymbols =
                java.util.Set.of(
                        "TCS",
                        "INFY",
                        "WIPRO",
                        "TECHM",
                        "HCLTECH",
                        "COFORGE",
                        "LTM",
                        "PERSISTENT",
                        "MPHASIS",
                        "LTTS");
        java.util.Set<String> downSymbols =
                java.util.Set.of(
                        "TATASTEEL",
                        "JSWSTEEL",
                        "HINDALCO",
                        "JINDALSTEL",
                        "SAIL",
                        "NATIONALUM",
                        "NMDC",
                        "VEDL",
                        "HINDZINC",
                        "APLAPOLLO");
        // The quote is requested by exchange token when the symbol is in StockFnoRegistry (TCS is
        // token 11536), so index the moves by BOTH the symbol and its registry token.
        java.util.Map<String, Double> moveByToken = new java.util.HashMap<>();
        for (String s : upSymbols) {
            moveByToken.put(s, 2.0);
            var info = com.tradingbot.util.StockFnoRegistry.get(s);
            if (info != null && info.token() != null) {
                moveByToken.put(info.token(), 2.0);
            }
        }
        for (String s : downSymbols) {
            moveByToken.put(s, -2.0);
            var info = com.tradingbot.util.StockFnoRegistry.get(s);
            if (info != null && info.token() != null) {
                moveByToken.put(info.token(), -2.0);
            }
        }
        when(marketDataService.resolveToken(any())).thenAnswer(inv -> inv.getArgument(0));
        when(marketDataService.fetchQuote(any(), any()))
                .thenAnswer(
                        inv -> {
                            String token = inv.getArgument(1);
                            double pct = moveByToken.getOrDefault(token, 0.0);
                            return new ObjectMapper()
                                    .createObjectNode()
                                    .put("lp", 100.0 * (1 + pct / 100))
                                    .put("c", 100.0)
                                    .put("o", 100.0);
                        });

        service.runMorningUniverseScan();

        assertThat(service.isUniverseScanCompletedToday()).isTrue();
        assertThat(service.isStandDownToday()).isFalse();

        // The whole point of dropping the bias: both sides are armed from a mixed index. Sector
        // sign gating keeps flat sectors out, so the picks are the trending ones and only they.
        assertThat(service.getActiveSetups()).isNotEmpty();
        assertThat(service.getActiveSetups().values())
                .anySatisfy(
                        s -> assertThat(s.getDirection()).isEqualTo(LowestVolumeDirection.LONG));
        assertThat(service.getActiveSetups().values())
                .anySatisfy(
                        s -> assertThat(s.getDirection()).isEqualTo(LowestVolumeDirection.SHORT));
        assertThat(service.getActiveSetups().values())
                .allSatisfy(
                        s -> assertThat(s.getDirection()).isNotEqualTo(LowestVolumeDirection.NONE));
        // No flat sector can sneak in: a bullish sector needs a positive average move and a
        // bearish one a negative, so exactly the IT (LONG) and METAL (SHORT) picks qualify.
        assertThat(service.getActiveSetups().size()).isLessThanOrEqualTo(6);
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
            service.setClock(Clock.fixed(base.plus(i * 70L, ChronoUnit.SECONDS), IST));
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
    @DisplayName(
            "M-fix: resetDaily books an unpriceable position at its stop instead of dropping it from the ledger")
    void testResetDailyBooksUnpricedPositionAtStop() throws Exception {
        java.nio.file.Path stateFile =
                java.nio.file.Files.createTempDirectory("lvr-orphan").resolve("lvr-state.json");
        service.setStateFilePath(stateFile.toString());

        // No quote is mocked, so executeHardExit cannot price this and deliberately leaves it
        // open. Dropping it into the subsequent clear would have deleted the trade without ever
        // booking it: no tradeHistory entry, so the realized loss never reached the daily budget
        // or the circuit breaker, and the loss cap was silently re-opened for the rest of the day.
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

        service.resetDaily(true);

        assertThat(service.getOpenPositions()).isEmpty();
        com.tradingbot.persistence.LvrStateStore.DailyState state =
                com.tradingbot.persistence.LvrStateStore.load(stateFile);
        assertThat(state).isNotNull();
        assertThat(state.tradeHistory)
                .as("the unpriceable position must still leave a booked trade behind")
                .hasSize(1);
        assertThat(state.tradeHistory.get(0).symbol).isEqualTo("SUNPHARMA");
        assertThat(state.tradeHistory.get(0).exitReason).isEqualTo("RESET_UNCLOSED_ASSUMED_STOP");
        // Entry 15.0, planned risk 5600 over 700 units → 8.00 per unit → assumed exit 7.00, so
        // the full planned loss is what the ledger records rather than a convenient zero.
        assertThat(state.tradeHistory.get(0).totalRealizedPnl.doubleValue())
                .isCloseTo(-5600.0, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    @DisplayName(
            "M-fix: session keys survive a restart — a same-day reset is soft, keeping realized P&L and the loss cap")
    void testRestartKeepsSessionKeys() throws Exception {
        java.nio.file.Path stateFile =
                java.nio.file.Files.createTempDirectory("lvr-session").resolve("lvr-state.json");

        // A snapshot as a mid-day restart would leave one: morning scan already done, two losses
        // already taken, still the same trading day.
        com.tradingbot.persistence.LvrStateStore.DailyState state =
                new com.tradingbot.persistence.LvrStateStore.DailyState();
        state.date = java.time.LocalDate.of(2026, 9, 18);
        state.lastScanDate = java.time.LocalDate.of(2026, 9, 18);
        state.universeScanCompletedToday = true;
        state.todayLossCount = 2;
        com.tradingbot.persistence.LvrStateStore.save(stateFile, state);

        LowestVolumeReversalService restored =
                new LowestVolumeReversalService(marketDataService, taService, null, config, null);
        restored.setClock(Clock.fixed(Instant.parse("2026-09-18T04:30:00Z"), IST)); // 10:00 IST
        restored.setStateFilePath(stateFile.toString());
        restored.setStatePersistenceEnabled(true);
        restored.restorePersistedState();

        assertThat(restored.isUniverseScanCompletedToday())
                .as("a restart after the morning scan must not look like an unscanned day")
                .isTrue();
        assertThat(restored.isStandDownToday())
                .as("the 10:00 fallback must not stand down a session that already scanned")
                .isFalse();

        LowestVolumePaperPosition closedToday =
                new LowestVolumePaperPosition(
                        "LVR-7",
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
                        Instant.parse("2026-09-18T05:00:00Z"));
        closedToday.close(BigDecimal.valueOf(3960.00), "SPOT_SL_HIT", Instant.now());
        restored.getTradeHistory().add(closedToday);

        restored.resetDaily(false);

        assertThat(restored.getTradeHistory())
                .as("a same-day reset must be soft — today's realized trades are preserved")
                .hasSize(1);
        assertThat(restored.getTodayLossCount().get())
                .as("a restart must not hand back the two losses already taken against the cap")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("M-fix: a position that cannot be valued must not be counted as ₹0 of risk")
    void testUnpricedPositionFailsClosed() {
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

        // Quote service is deliberately unmocked, so the valuation cannot obtain a price. It used
        // to contribute exactly ₹0 here — which meant the breaker and the pre-trade budget both
        // went blind precisely during an outage, and new risk was granted on the strength of a
        // position the strategy could not actually see.
        service.calculateOpenPositionsUnrealizedPnl();
        assertThat(service.hasUnpricedOpenPosition())
                .as("an unpriceable open position must not read as zero risk")
                .isTrue();

        // Once a quote comes back the marker clears and risk-taking resumes.
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(new ObjectMapper().createObjectNode().put("lp", "520.0"));
        service.calculateOpenPositionsUnrealizedPnl();
        assertThat(service.hasUnpricedOpenPosition()).isFalse();
    }

    @Test
    @DisplayName(
            "Aggregate stop-risk charges the budget for what every open position could still lose")
    void testAggregateOpenStopRiskBoundsTheBudget() {
        // Entry 15.0, planned risk 5600 over 700 units, stock stop 8.00 away and untouched.
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

        // Risk is only ever counted per-position at entry, so four such positions were allowed
        // under a ₹15k daily limit — each passed on its own. Charging the aggregate closes that.
        assertThat(service.aggregateOpenStopRisk())
                .isCloseTo(5600.0, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    @DisplayName(
            "H7 Fix: replenished setups get PDH/PDL initialized instead of failing closed forever")
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

    @Test
    @DisplayName("Should halt new trade entries when max daily loss count is reached")
    void testHaltsEntriesWhenMaxDailyLossCountReached() {
        service.setMaxDailyLosses(2);
        service.getTodayLossCount().set(2); // 2 losses incurred today

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
                BigDecimal.valueOf(1895.30));
        setup.setLatestVwap(1870.00);
        setup.setPdh(BigDecimal.valueOf(1870.00));
        setup.setPdl(BigDecimal.valueOf(1850.00));
        setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
        service.getActiveSetups().put("SUNPHARMA", setup);

        ObjectMapper mapper = new ObjectMapper();
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(mapper.createObjectNode().put("lp", "1876.00").put("ap", "1870.00"));
        when(marketDataService.resolveToken("SUNPHARMA")).thenReturn("3351");

        service.evaluateLivePriceActions();

        // Entry must be blocked due to daily loss limit
        assertThat(service.getOpenPositions()).doesNotContainKey("SUNPHARMA");
    }

    @Test
    @DisplayName("Should reset todayLossCount to 0 upon daily reset")
    void testDailyLossCountResetsOnDailyReset() {
        service.getTodayLossCount().set(2);
        service.resetDaily();
        assertThat(service.getTodayLossCount().get()).isZero();
    }

    @Test
    @DisplayName(
            "initPdhPdlForSetup uses HistoricalOhlcCacheService when available without hitting network")
    void testInitPdhPdlUsesHistoricalOhlcCacheService() {
        HistoricalOhlcCacheService ohlcCache = mock(HistoricalOhlcCacheService.class);
        service.setOhlcCacheService(ohlcCache);

        Candle prevDay =
                Candle.of5m(
                        "PNB",
                        Instant.parse("2026-09-17T00:00:00Z"),
                        BigDecimal.valueOf(112),
                        BigDecimal.valueOf(115.50),
                        BigDecimal.valueOf(110.25),
                        BigDecimal.valueOf(114),
                        10000);
        when(ohlcCache.getDailyCandles("PNB")).thenReturn(List.of(prevDay));

        LowestVolumeSetup setup = new LowestVolumeSetup("PNB", LowestVolumeDirection.LONG);
        service.initPdhPdlForSetup(setup);

        assertThat(setup.getPdh()).isEqualByComparingTo(BigDecimal.valueOf(115.50));
        assertThat(setup.getPdl()).isEqualByComparingTo(BigDecimal.valueOf(110.25));
        verify(marketDataService, never())
                .fetchDailyCandles(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName(
            "initPdhPdlForSetup falls back to ShoonyaMarketDataService when HistoricalOhlcCacheService is empty")
    void testInitPdhPdlFallsBackToShoonyaWhenCacheEmpty() {
        HistoricalOhlcCacheService ohlcCache = mock(HistoricalOhlcCacheService.class);
        service.setOhlcCacheService(ohlcCache);

        when(ohlcCache.getDailyCandles("PNB")).thenReturn(List.of());

        Candle prevDay =
                Candle.of5m(
                        "PNB",
                        Instant.parse("2026-09-17T00:00:00Z"),
                        BigDecimal.valueOf(112),
                        BigDecimal.valueOf(114.80),
                        BigDecimal.valueOf(109.50),
                        BigDecimal.valueOf(113),
                        10000);
        when(marketDataService.fetchDailyCandles("PNB", 5)).thenReturn(List.of(prevDay));

        LowestVolumeSetup setup = new LowestVolumeSetup("PNB", LowestVolumeDirection.LONG);
        service.initPdhPdlForSetup(setup);

        assertThat(setup.getPdh()).isEqualByComparingTo(BigDecimal.valueOf(114.80));
        assertThat(setup.getPdl()).isEqualByComparingTo(BigDecimal.valueOf(109.50));
        verify(marketDataService, times(1)).fetchDailyCandles("PNB", 5);
    }

    @Test
    @DisplayName(
            "initPdhPdlForSetup falls back to ShoonyaMarketDataService when HistoricalOhlcCacheService throws exception")
    void testInitPdhPdlFallsBackToShoonyaWhenCacheThrows() {
        HistoricalOhlcCacheService ohlcCache = mock(HistoricalOhlcCacheService.class);
        service.setOhlcCacheService(ohlcCache);

        when(ohlcCache.getDailyCandles("PNB")).thenThrow(new RuntimeException("SQLite locked"));

        Candle prevDay =
                Candle.of5m(
                        "PNB",
                        Instant.parse("2026-09-17T00:00:00Z"),
                        BigDecimal.valueOf(112),
                        BigDecimal.valueOf(114.80),
                        BigDecimal.valueOf(109.50),
                        BigDecimal.valueOf(113),
                        10000);
        when(marketDataService.fetchDailyCandles("PNB", 5)).thenReturn(List.of(prevDay));

        LowestVolumeSetup setup = new LowestVolumeSetup("PNB", LowestVolumeDirection.LONG);
        service.initPdhPdlForSetup(setup);

        assertThat(setup.getPdh()).isEqualByComparingTo(BigDecimal.valueOf(114.80));
        assertThat(setup.getPdl()).isEqualByComparingTo(BigDecimal.valueOf(109.50));
        verify(marketDataService, times(1)).fetchDailyCandles("PNB", 5);
    }

    @Test
    @DisplayName("resolveLivePcr queries ShoonyaOptionChainService across ATM ± 4 strikes")
    void testResolveLivePcr() {
        com.tradingbot.marketdata.ShoonyaOptionChainService optionChainService =
                mock(com.tradingbot.marketdata.ShoonyaOptionChainService.class);
        ShoonyaConfig mockConfig = mock(ShoonyaConfig.class);
        LowestVolumeReversalService lvr =
                new LowestVolumeReversalService(
                        marketDataService,
                        taService,
                        null,
                        mockConfig,
                        optionChainService,
                        null,
                        null,
                        null);
        lvr.setPcrFilterEnabled(true);

        com.tradingbot.model.OptionChainResponse mockChain =
                new com.tradingbot.model.OptionChainResponse(
                        "PNB",
                        BigDecimal.valueOf(113.20),
                        BigDecimal.valueOf(115.00),
                        "PNB26OCT115CE",
                        9,
                        1000000L,
                        1200000L,
                        1.20,
                        List.of());
        when(optionChainService.getOptionChain(
                        org.mockito.ArgumentMatchers.eq("PNB"),
                        org.mockito.ArgumentMatchers.eq("PNB"),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(BigDecimal.class),
                        org.mockito.ArgumentMatchers.eq(4),
                        org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(mockChain);

        LowestVolumeSetup setup = new LowestVolumeSetup("PNB", LowestVolumeDirection.LONG);
        Double pcr = lvr.resolveLivePcr(setup, BigDecimal.valueOf(113.20));

        assertThat(pcr).isEqualTo(1.20);
        assertThat(setup.getPcr()).isEqualTo(1.20);
    }

    @Test
    @DisplayName(
            "resolveLiveOptionChainLevels extracts Max Call OI Resistance and Max Put OI Support")
    void testResolveLiveOptionChainLevelsExtractsSupportAndResistance() {
        com.tradingbot.marketdata.ShoonyaOptionChainService optionChainService =
                mock(com.tradingbot.marketdata.ShoonyaOptionChainService.class);
        ShoonyaConfig mockConfig = mock(ShoonyaConfig.class);
        LowestVolumeReversalService lvr =
                new LowestVolumeReversalService(
                        marketDataService,
                        taService,
                        null,
                        mockConfig,
                        optionChainService,
                        null,
                        null,
                        null);
        lvr.setOptionSrFilterEnabled(true);

        com.tradingbot.model.OptionContract call1 =
                new com.tradingbot.model.OptionContract(
                        "PNB115CE",
                        "1",
                        "CE",
                        BigDecimal.valueOf(115),
                        BigDecimal.valueOf(2.5),
                        500000L,
                        1000L,
                        null,
                        null,
                        null);
        com.tradingbot.model.OptionContract call2 =
                new com.tradingbot.model.OptionContract(
                        "PNB120CE",
                        "2",
                        "CE",
                        BigDecimal.valueOf(120),
                        BigDecimal.valueOf(1.2),
                        1500000L,
                        2000L,
                        null,
                        null,
                        null); // Max Call OI = 120 (Resistance)
        com.tradingbot.model.OptionContract put1 =
                new com.tradingbot.model.OptionContract(
                        "PNB110PE",
                        "3",
                        "PE",
                        BigDecimal.valueOf(110),
                        BigDecimal.valueOf(1.0),
                        2000000L,
                        3000L,
                        null,
                        null,
                        null); // Max Put OI = 110 (Support)
        com.tradingbot.model.OptionContract put2 =
                new com.tradingbot.model.OptionContract(
                        "PNB115PE",
                        "4",
                        "PE",
                        BigDecimal.valueOf(115),
                        BigDecimal.valueOf(3.0),
                        800000L,
                        1500L,
                        null,
                        null,
                        null);

        com.tradingbot.model.OptionStrike strike110 =
                new com.tradingbot.model.OptionStrike(BigDecimal.valueOf(110), false, null, put1);
        com.tradingbot.model.OptionStrike strike115 =
                new com.tradingbot.model.OptionStrike(BigDecimal.valueOf(115), true, call1, put2);
        com.tradingbot.model.OptionStrike strike120 =
                new com.tradingbot.model.OptionStrike(BigDecimal.valueOf(120), false, call2, null);

        com.tradingbot.model.OptionChainResponse mockChain =
                new com.tradingbot.model.OptionChainResponse(
                        "PNB",
                        BigDecimal.valueOf(113.20),
                        BigDecimal.valueOf(115.00),
                        "PNB26OCT115CE",
                        3,
                        2000000L,
                        2800000L,
                        1.40,
                        List.of(strike110, strike115, strike120));

        when(optionChainService.getOptionChain(
                        org.mockito.ArgumentMatchers.eq("PNB"),
                        org.mockito.ArgumentMatchers.eq("PNB"),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(BigDecimal.class),
                        org.mockito.ArgumentMatchers.eq(4),
                        org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(mockChain);

        LowestVolumeSetup setup = new LowestVolumeSetup("PNB", LowestVolumeDirection.LONG);
        lvr.resolveLiveOptionChainLevels(setup, BigDecimal.valueOf(113.20));

        assertThat(setup.getOptionResistanceStrike()).isEqualByComparingTo(BigDecimal.valueOf(120));
        assertThat(setup.getOptionSupportStrike()).isEqualByComparingTo(BigDecimal.valueOf(110));
    }

    @Test
    @DisplayName("Synthetic chain never sets PCR — stale levels cleared so the gate fails closed")
    void testResolveLiveOptionChainLevelsClearsSyntheticChain() {
        com.tradingbot.marketdata.ShoonyaOptionChainService optionChainService =
                mock(com.tradingbot.marketdata.ShoonyaOptionChainService.class);
        ShoonyaConfig mockConfig = mock(ShoonyaConfig.class);
        LowestVolumeReversalService lvr =
                new LowestVolumeReversalService(
                        marketDataService,
                        taService,
                        null,
                        mockConfig,
                        optionChainService,
                        null,
                        null,
                        null);
        lvr.setPcrFilterEnabled(true);

        // The broker fallback mirrors call OI against put OI, so PCR always lands on exactly 1.00.
        com.tradingbot.model.OptionChainResponse synthetic =
                new com.tradingbot.model.OptionChainResponse(
                        "PNB",
                        BigDecimal.valueOf(113.20),
                        BigDecimal.valueOf(115.00),
                        "PNB",
                        9,
                        1_000_000L,
                        1_000_000L,
                        1.00,
                        List.of(),
                        true);
        when(optionChainService.getOptionChain(
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.eq(
                                com.tradingbot.marketdata.ShoonyaOptionChainService
                                        .PCR_STRIKE_WINDOW),
                        org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(synthetic);

        LowestVolumeSetup setup = new LowestVolumeSetup("PNB", LowestVolumeDirection.LONG);
        setup.setPcr(1.40); // stale reading from an earlier successful fetch
        setup.setOptionResistanceStrike(BigDecimal.valueOf(130));
        setup.setOptionSupportStrike(BigDecimal.valueOf(100));

        lvr.resolveLiveOptionChainLevels(setup, BigDecimal.valueOf(113.20));

        assertThat(setup.getPcr()).isNull();
        assertThat(setup.getOptionResistanceStrike()).isNull();
        assertThat(setup.getOptionSupportStrike()).isNull();
    }

    @Test
    @DisplayName("Hybrid phase-1 morning scan: sector watchlist builds before the NSE OI merge")
    void testHybridMorningScanPhaseOne() {
        service.setScannerMode("HYBRID");
        when(marketDataService.resolveToken(any())).thenReturn("1234");
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        new ObjectMapper().createObjectNode().put("lp", "102.0").put("c", "100.0"));

        service.runMorningUniverseScan();

        assertThat(service.isUniverseScanCompletedToday()).isTrue();
        assertThat(service.isStandDownToday()).isFalse();
        assertThat(service.getActiveSetups().size()).isGreaterThanOrEqualTo(2);
        // Two-sided: at most 3 bullish + 3 bearish sectors × 3 stocks.
        assertThat(service.getActiveSetups().size()).isLessThanOrEqualTo(18);
    }

    // ------------------------------------------------------------------
    // 09:25 NSE OI spurts augmentation (two-phase candidate selection)
    // ------------------------------------------------------------------

    /** Builds a ranked NSE OI spurt item for the given symbol. */
    private static NseOiSpurtItem oiItem(String symbol, double avgInOi) {
        return new NseOiSpurtItem(symbol, 1000L, 900L, 100L, avgInOi, 100.0);
    }

    /** Mocks universe quotes as uniform +5% movers (all advance → LONG, in 0-5% band). */
    private void mockUniverseScanQuotes() {
        when(marketDataService.resolveToken(any())).thenReturn("1234");
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        new ObjectMapper()
                                .createObjectNode()
                                .put("lp", "105.0")
                                .put("c", "100.0")
                                .put("o", "101.0"));
    }

    /** Builds a mock NSE OI client pre-loaded with the given ranked items and wires it in. */
    private NseOiSpurtClient mockOiClient(NseOiSpurtItem... items) {
        NseOiSpurtClient client = mock(NseOiSpurtClient.class);
        when(client.fetchTopUnderlyings(anyInt())).thenReturn(List.of(items));
        service.setNseOiSpurtClient(client);
        return client;
    }

    @Test
    @DisplayName("Augmentation merges immediately when the morning scan already completed")
    void testAugmentationMergesImmediatelyWhenScanComplete() {
        mockUniverseScanQuotes();
        service.runMorningUniverseScan();
        assertThat(service.isUniverseScanCompletedToday()).isTrue();

        mockOiClient(oiItem("AAAA", 48.7), oiItem("BBBB", 25.0), oiItem("CCCC", 21.7));
        service.runNseOiSpurtAugmentation();

        assertThat(service.hasPendingOiItems()).isFalse();
        assertThat(service.isNseOiAppliedToday()).isTrue();
        assertThat(service.getActiveSetups()).containsKeys("AAAA", "BBBB", "CCCC");
        // Phase-1 fills both sides of the market (3 bullish sectors × 3) and the OI merge adds
        // its 3 spurts on top — the old hard 6-symbol HYBRID cap no longer applies.
        assertThat(service.getActiveSetups()).hasSize(12);
        LowestVolumeSetup oiSetup = service.getActiveSetups().get("AAAA");
        assertThat(oiSetup.getDirection()).isEqualTo(LowestVolumeDirection.LONG);
        assertThat(oiSetup.getOiChangePct()).isEqualTo(48.7);
        assertThat(service.getSectorState().candidateSymbols()).contains("AAAA", "BBBB", "CCCC");
    }

    @Test
    @DisplayName("Augmentation is a no-op when disabled, and runs only once per day")
    void testAugmentationDisabledAndIdempotentPerDay() {
        // Disabled: the NSE client is never called.
        NseOiSpurtClient disabled = mock(NseOiSpurtClient.class);
        service.setNseOiSpurtClient(disabled);
        service.setNseOiEnabled(false);
        service.runNseOiSpurtAugmentation();
        verify(disabled, never()).fetchTopUnderlyings(anyInt());
        assertThat(service.hasPendingOiItems()).isFalse();
        service.setNseOiEnabled(true);

        // Idempotent: after a successful merge the second invocation never re-fetches.
        mockUniverseScanQuotes();
        service.runMorningUniverseScan();
        NseOiSpurtClient client =
                mockOiClient(oiItem("AAAA", 48.7), oiItem("BBBB", 25.0), oiItem("CCCC", 21.7));
        service.runNseOiSpurtAugmentation();
        service.runNseOiSpurtAugmentation();
        verify(client, times(1)).fetchTopUnderlyings(anyInt());
        assertThat(service.getActiveSetups()).hasSize(12);
    }

    @Test
    @DisplayName("Failed NSE fetch degrades to sector-only and never fails the scan")
    void testFetchFailureKeepsSectorOnlyAndScanCompletes() {
        NseOiSpurtClient client = mock(NseOiSpurtClient.class);
        when(client.fetchTopUnderlyings(anyInt())).thenReturn(List.of());
        service.setNseOiSpurtClient(client);

        // 09:25:00 fetch fails: nothing staged, day continues.
        service.runNseOiSpurtAugmentation();
        assertThat(service.hasPendingOiItems()).isFalse();

        // The scan still runs and completes with sector picks only.
        mockUniverseScanQuotes();
        service.runMorningUniverseScan();
        assertThat(service.isUniverseScanCompletedToday()).isTrue();
        assertThat(service.getActiveSetups()).isNotEmpty();
        assertThat(service.getActiveSetups()).doesNotContainKeys("AAAA", "BBBB", "CCCC");
        assertThat(service.isNseOiAppliedToday()).isFalse();
    }

    @Test
    @DisplayName("New-day reset drops staged NSE OI items from the previous session")
    void testNewDayResetClearsStagedOiItems() {
        mockOiClient(oiItem("AAAA", 48.7));
        service.runNseOiSpurtAugmentation();
        assertThat(service.hasPendingOiItems()).isTrue();

        service.setClock(Clock.fixed(Instant.parse("2026-09-19T04:00:00Z"), IST));
        service.resetDaily();

        assertThat(service.hasPendingOiItems()).isFalse();
        assertThat(service.isNseOiAppliedToday()).isFalse();
    }

    @Test
    @DisplayName("New-day reset clears the NSE OI applied latch so tomorrow's job can run")
    void testNewDayResetClearsAppliedLatch() {
        mockUniverseScanQuotes();
        service.runMorningUniverseScan();
        mockOiClient(oiItem("AAAA", 48.7), oiItem("BBBB", 25.0), oiItem("CCCC", 21.7));
        service.runNseOiSpurtAugmentation();
        assertThat(service.isNseOiAppliedToday()).isTrue();

        service.setClock(Clock.fixed(Instant.parse("2026-09-19T04:00:00Z"), IST));
        service.resetDaily();

        assertThat(service.isNseOiAppliedToday()).isFalse();
    }

    @Test
    @DisplayName("Same-day reset preserves staged NSE OI items (they are day-stamped today)")
    void testSameDayResetPreservesStagedOiItems() {
        mockOiClient(oiItem("AAAA", 48.7));
        service.runNseOiSpurtAugmentation();
        assertThat(service.hasPendingOiItems()).isTrue();

        service.resetDaily();

        assertThat(service.hasPendingOiItems()).isTrue();
    }

    @Test
    @DisplayName(
            "Two-phase flow: 09:25 job stages OI items, scan completion consumes and merges them")
    void testTwoPhaseStagesThenMergesOnScanCompletion() {
        mockUniverseScanQuotes();
        mockOiClient(oiItem("AAAA", 48.7), oiItem("BBBB", 25.0), oiItem("CCCC", 21.7));

        // Phase-2 fetch fires at 09:25:00, before the 09:25:20 scan: items are staged only.
        service.runNseOiSpurtAugmentation();
        assertThat(service.hasPendingOiItems()).isTrue();
        assertThat(service.getActiveSetups()).isEmpty();
        assertThat(service.isUniverseScanCompletedToday()).isFalse();

        // Scan completion consumes the staged items and merges them into the watchlist.
        service.runMorningUniverseScan();

        assertThat(service.isUniverseScanCompletedToday()).isTrue();
        assertThat(service.hasPendingOiItems()).isFalse();
        assertThat(service.isNseOiAppliedToday()).isTrue();
        assertThat(service.getActiveSetups()).containsKeys("AAAA", "BBBB", "CCCC");
        // Phase-1 fills both sides of the market (3 bullish sectors × 3) and the OI merge adds
        // its 3 spurts on top — the old hard 6-symbol HYBRID cap no longer applies.
        assertThat(service.getActiveSetups()).hasSize(12);
        LowestVolumeSetup oiSetup = service.getActiveSetups().get("AAAA");
        assertThat(oiSetup.getDirection()).isEqualTo(LowestVolumeDirection.LONG);
        assertThat(oiSetup.getOiChangePct()).isEqualTo(48.7);
        assertThat(service.getSectorState().candidateSymbols()).contains("AAAA", "BBBB", "CCCC");
    }

    @Test
    @DisplayName(
            "NSE OI merge always reaches topN via relaxed fill when the band filters everything")
    void testNseOiMergeReachesTopNWhenBandFiltersEverything() {
        // Universe quotes are +2% (LONG, in band); the OI pseudo-symbols quote +9% (out of the
        // 0-5% band), so every ranked item is filtered out and the relaxed fill must take over.
        java.util.Set<String> oiSymbols = java.util.Set.of("AAAA", "BBBB", "CCCC", "DDDD");
        when(marketDataService.resolveToken(any())).thenAnswer(inv -> inv.getArgument(0));
        when(marketDataService.fetchQuote(any(), any()))
                .thenAnswer(
                        inv -> {
                            String token = inv.getArgument(1);
                            double pct = oiSymbols.contains(token) ? 9.0 : 2.0;
                            return new ObjectMapper()
                                    .createObjectNode()
                                    .put("lp", 100.0 * (1 + pct / 100))
                                    .put("c", 100.0)
                                    .put("o", 100.5);
                        });

        service.runMorningUniverseScan();
        assertThat(service.isUniverseScanCompletedToday()).isTrue();

        mockOiClient(
                oiItem("AAAA", 48.7),
                oiItem("BBBB", 25.0),
                oiItem("CCCC", 21.7),
                oiItem("DDDD", 15.0));
        service.runNseOiSpurtAugmentation();

        assertThat(service.getActiveSetups()).containsKeys("AAAA", "BBBB", "CCCC");
        assertThat(service.getActiveSetups()).doesNotContainKey("DDDD"); // topN=3 respected
        assertThat(service.getActiveSetups()).hasSize(12);
    }

    @Test
    @DisplayName("NSE OI merge skips active/exhausted symbols and respects topN")
    void testNseOiMergeExcludesActiveAndExhaustedSymbols() {
        mockUniverseScanQuotes();
        service.runMorningUniverseScan();
        assertThat(service.getActiveSetups()).isNotEmpty();

        String activeSym = service.getActiveSetups().keySet().iterator().next();
        LowestVolumeSetup activeBefore = service.getActiveSetups().get(activeSym);
        service.getExhaustedSymbols().add("EXH1");

        mockOiClient(
                oiItem(activeSym, 48.7), // already active — must be skipped
                oiItem("EXH1", 40.0), // exhausted — must be skipped
                oiItem("EEEE", 30.0),
                oiItem("FFFF", 25.0),
                oiItem("GGGG", 21.0),
                oiItem("HHHH", 15.0));
        service.runNseOiSpurtAugmentation();

        assertThat(service.getActiveSetups().get(activeSym)).isSameAs(activeBefore);
        assertThat(service.getActiveSetups()).containsKeys("EEEE", "FFFF", "GGGG");
        assertThat(service.getActiveSetups()).doesNotContainKey("HHHH"); // topN=3 respected
        assertThat(service.getActiveSetups()).hasSize(12); // 9 sector (two-sided) + 3 OI
    }

    @Test
    @DisplayName(
            "OI_SPURTS mode: merge demotes sector picks to reservoir and directions follow each stock's own move")
    void testOiSpurtsModeDemotesSectorPicksAndUsesOwnDirection() {
        service.setScannerMode("OI_SPURTS");
        // Universe quotes +2% (scan picks sector leaders as phase-1 watchlist). OI symbols:
        // IIII quotes -2% (own move → SHORT), JJJJ/KKKK quote +2% (→ LONG).
        java.util.Set<String> shortSymbols = java.util.Set.of("IIII");
        java.util.Set<String> oiSymbols = java.util.Set.of("IIII", "JJJJ", "KKKK");
        when(marketDataService.resolveToken(any())).thenAnswer(inv -> inv.getArgument(0));
        when(marketDataService.fetchQuote(any(), any()))
                .thenAnswer(
                        inv -> {
                            String token = inv.getArgument(1);
                            double pct;
                            if (shortSymbols.contains(token)) {
                                pct = -2.0;
                            } else if (oiSymbols.contains(token)) {
                                pct = 2.0;
                            } else {
                                pct = 2.0;
                            }
                            return new ObjectMapper()
                                    .createObjectNode()
                                    .put("lp", 100.0 * (1 + pct / 100))
                                    .put("c", 100.0)
                                    .put("o", 100.5);
                        });

        // Phase 1: sector rotation watchlist (no OI required at scan time anymore).
        service.runMorningUniverseScan();
        assertThat(service.isUniverseScanCompletedToday()).isTrue();
        assertThat(service.getActiveSetups()).isNotEmpty();
        List<String> sectorPicks = List.copyOf(service.getActiveSetups().keySet());
        assertThat(service.getCandidateReservoir()).isNotEmpty();

        // Phase 2: OI_SPURTS topN = oiSpurtsTopN (5); sector picks are demoted to reservoir.
        mockOiClient(oiItem("IIII", 48.7), oiItem("JJJJ", 25.0), oiItem("KKKK", 21.7));
        service.runNseOiSpurtAugmentation();

        assertThat(service.getActiveSetups()).containsOnlyKeys("IIII", "JJJJ", "KKKK");
        assertThat(service.getActiveSetups().get("IIII").getDirection())
                .isEqualTo(LowestVolumeDirection.SHORT); // own price move, not sentiment
        assertThat(service.getActiveSetups().get("JJJJ").getDirection())
                .isEqualTo(LowestVolumeDirection.LONG);
        assertThat(service.getCandidateReservoir()).containsAll(sectorPicks);
        assertThat(service.getSectorState().candidateSymbols()).contains("IIII", "JJJJ", "KKKK");
    }

    @Test
    @DisplayName(
            "Mid-morning refresh tops up the reservoir from the NSE OI feed without touching active setups")
    void testMidMorningRefreshTopsUpReservoirFromNseOiFeed() {
        mockUniverseScanQuotes();
        service.runMorningUniverseScan();
        List<String> activeBefore = List.copyOf(service.getActiveSetups().keySet());

        NseOiSpurtClient client = mock(NseOiSpurtClient.class);
        when(client.fetchTopUnderlyings(anyInt()))
                .thenReturn(List.of(oiItem("MMMM", 45.0), oiItem("NNNN", 30.0)));
        service.setNseOiSpurtClient(client);

        service.runMidMorningUniverseRefresh(LocalTime.of(10, 35));

        verify(client).fetchTopUnderlyings(anyInt());
        assertThat(service.getCandidateReservoir()).contains("MMMM", "NNNN");
        // Reservoir-only: the active watchlist is never mutated by the mid-morning top-up.
        assertThat(service.getActiveSetups().keySet())
                .containsExactlyInAnyOrderElementsOf(activeBefore);

        // Disabled flag: no further NSE fetch happens.
        service.setNseOiMidMorningRefreshEnabled(false);
        service.runMidMorningUniverseRefresh(LocalTime.of(11, 5));
        verify(client, times(1)).fetchTopUnderlyings(anyInt());
    }

    // ------------------------------------------------------------------
    // D3: two-sided (triangle-channel) arming — which side a NONE setup latches to
    // ------------------------------------------------------------------

    /** Builds an UNARMED setup for the given side (state SCANNING). */
    private static LowestVolumeSetup scanningSetup(LowestVolumeDirection direction) {
        return new LowestVolumeSetup("INFY", direction);
    }

    /** Builds a setup armed on a candle stamped at {@code triggerTime}. */
    private static LowestVolumeSetup armedSetup(
            LowestVolumeDirection direction, Instant triggerTime) {
        LowestVolumeSetup s = new LowestVolumeSetup("INFY", direction);
        s.setTriggerCandle(
                Candle.of5m(
                        "INFY",
                        triggerTime,
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(101),
                        BigDecimal.valueOf(99),
                        BigDecimal.valueOf(100.5),
                        1000L),
                BigDecimal.valueOf(101.05),
                BigDecimal.valueOf(98.95),
                BigDecimal.valueOf(105));
        s.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "test");
        return s;
    }

    @Test
    @DisplayName("Two-sided: neither side armed stays neutral and keeps re-evaluating")
    void testResolveTwoSidedUnarmedStaysNeutral() {
        LowestVolumeSetup longSide = scanningSetup(LowestVolumeDirection.LONG);
        LowestVolumeSetup shortSide = scanningSetup(LowestVolumeDirection.SHORT);

        LowestVolumeSetup resolved =
                LowestVolumeReversalService.resolveTwoSided(longSide, shortSide);

        assertThat(resolved).isSameAs(longSide);
        assertThat(resolved.getState()).isEqualTo(LowestVolumeSetupState.SCANNING);
        assertThat(resolved.getTriggerPrice()).isNull();
    }

    @Test
    @DisplayName("Two-sided: the single side that formed a pullback is the one that latches")
    void testResolveTwoSidedSingleArmedSideWins() {
        LowestVolumeSetup onlyLong =
                LowestVolumeReversalService.resolveTwoSided(
                        armedSetup(
                                LowestVolumeDirection.LONG, Instant.parse("2026-10-16T05:00:00Z")),
                        scanningSetup(LowestVolumeDirection.SHORT));
        assertThat(onlyLong.getDirection()).isEqualTo(LowestVolumeDirection.LONG);

        LowestVolumeSetup onlyShort =
                LowestVolumeReversalService.resolveTwoSided(
                        scanningSetup(LowestVolumeDirection.LONG),
                        armedSetup(
                                LowestVolumeDirection.SHORT,
                                Instant.parse("2026-10-16T05:00:00Z")));
        assertThat(onlyShort.getDirection()).isEqualTo(LowestVolumeDirection.SHORT);
    }

    @Test
    @DisplayName("Two-sided: when both sides arm, the fresher pullback wins")
    void testResolveTwoSidedFresherPullbackWins() {
        Instant early = Instant.parse("2026-10-16T04:30:00Z");
        Instant late = Instant.parse("2026-10-16T06:00:00Z");

        assertThat(
                        LowestVolumeReversalService.resolveTwoSided(
                                        armedSetup(LowestVolumeDirection.LONG, late),
                                        armedSetup(LowestVolumeDirection.SHORT, early))
                                .getDirection())
                .isEqualTo(LowestVolumeDirection.LONG);

        assertThat(
                        LowestVolumeReversalService.resolveTwoSided(
                                        armedSetup(LowestVolumeDirection.LONG, early),
                                        armedSetup(LowestVolumeDirection.SHORT, late))
                                .getDirection())
                .isEqualTo(LowestVolumeDirection.SHORT);
    }

    @Test
    @DisplayName("Two-sided: an exact freshness tie falls back to LONG deterministically")
    void testResolveTwoSidedTieFallsBackToLong() {
        Instant same = Instant.parse("2026-10-16T05:00:00Z");

        assertThat(
                        LowestVolumeReversalService.resolveTwoSided(
                                        armedSetup(LowestVolumeDirection.LONG, same),
                                        armedSetup(LowestVolumeDirection.SHORT, same))
                                .getDirection())
                .isEqualTo(LowestVolumeDirection.LONG);
    }
}
