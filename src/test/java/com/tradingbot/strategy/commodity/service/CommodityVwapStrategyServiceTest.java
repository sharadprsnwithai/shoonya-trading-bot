package com.tradingbot.strategy.commodity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.strategy.TradeSignal;
import com.tradingbot.strategy.commodity.config.CommodityVwapProperties;
import com.tradingbot.strategy.commodity.feed.CommodityQuoteFeed;
import com.tradingbot.strategy.commodity.feed.MutableClock;
import com.tradingbot.strategy.commodity.model.CommodityBias;
import com.tradingbot.strategy.commodity.model.CommoditySetup;
import com.tradingbot.strategy.commodity.model.CommoditySetupState;
import com.tradingbot.strategy.commodity.model.CommodityTradePosition;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommodityVwapStrategyServiceTest {

    private CommodityQuoteFeed quoteFeed;
    private TechnicalAnalysisService taService;
    private TelegramService telegramService;
    private com.tradingbot.bus.SignalPublisher signalPublisher;
    private CommodityVwapProperties properties;
    private CommodityVwapStrategyService service;

    @BeforeEach
    void setUp() {
        quoteFeed = mock(CommodityQuoteFeed.class);
        taService = mock(TechnicalAnalysisService.class);
        telegramService = mock(TelegramService.class);
        signalPublisher = mock(com.tradingbot.bus.SignalPublisher.class);
        when(signalPublisher.publish(any())).thenReturn(true);
        properties = new CommodityVwapProperties();
        properties.setSymbols(List.of("CRUDEOIL", "GOLD", "SILVER"));

        service =
                new CommodityVwapStrategyService(
                        properties,
                        quoteFeed,
                        taService,
                        telegramService,
                        Clock.system(ZoneId.of("Asia/Kolkata")),
                        signalPublisher);
    }

    @Test
    @DisplayName("Should evaluate PCR and determine BULLISH, BEARISH, and NEUTRAL bias")
    void testEvaluateDailyBias() {
        // Bullish PCR for CRUDEOIL: Put OI = 13000, Call OI = 10000 -> PCR = 1.30 >= 1.15
        OptionChainResponse crudeChain = createMockChain("CRUDEOIL", 13000, 10000);
        when(quoteFeed.optionChain("CRUDEOIL")).thenReturn(crudeChain);

        // Bearish PCR for GOLD: Put OI = 7000, Call OI = 10000 -> PCR = 0.70 <= 0.85
        OptionChainResponse goldChain = createMockChain("GOLD", 7000, 10000);
        when(quoteFeed.optionChain("GOLD")).thenReturn(goldChain);

        // Neutral PCR for SILVER: Put OI = 10000, Call OI = 10000 -> PCR = 1.00 (between 0.85 and
        // 1.15)
        OptionChainResponse silverChain = createMockChain("SILVER", 10000, 10000);
        when(quoteFeed.optionChain("SILVER")).thenReturn(silverChain);

        service.evaluateDailyBias();

        CommoditySetup crudeSetup = service.getSetup("CRUDEOIL");
        assertThat(crudeSetup.getBias()).isEqualTo(CommodityBias.BULLISH);
        assertThat(crudeSetup.getPcr()).isEqualTo(1.30);
        assertThat(crudeSetup.getState()).isEqualTo(CommoditySetupState.BIAS_IDENTIFIED);

        CommoditySetup goldSetup = service.getSetup("GOLD");
        assertThat(goldSetup.getBias()).isEqualTo(CommodityBias.BEARISH);
        assertThat(goldSetup.getPcr()).isEqualTo(0.70);
        assertThat(goldSetup.getState()).isEqualTo(CommoditySetupState.BIAS_IDENTIFIED);

        CommoditySetup silverSetup = service.getSetup("SILVER");
        assertThat(silverSetup.getBias()).isEqualTo(CommodityBias.NEUTRAL);
        assertThat(silverSetup.getPcr()).isEqualTo(1.00);
        assertThat(silverSetup.getState()).isEqualTo(CommoditySetupState.SKIPPED);

        verify(telegramService).sendTextMessage(contains("MCX Commodity Directional Bias"));
    }

    @Test
    @DisplayName("Should arm ARMED_LONG when Bullish and 15m candle crosses above VWAP")
    void testBullishVwapCrossover() {
        CommoditySetup setup = service.getSetup("CRUDEOIL");
        setup.updateBias(CommodityBias.BULLISH, 1.25);

        // Previous candle closed below VWAP (close=5480, vwap=5500)
        // Current candle crosses and closes above VWAP (close=5520, high=5530, vwap=5505)
        List<Candle> candles =
                List.of(
                        createCandle("CRUDEOIL", 5450, 5490, 5440, 5480, 100),
                        createCandle("CRUDEOIL", 5480, 5530, 5475, 5520, 200));

        when(quoteFeed.candles15m(eq("CRUDEOIL"), anyInt())).thenReturn(candles);
        when(taService.calculateVwapSeries(anyList())).thenReturn(new double[] {5500.0, 5505.0});

        service.evaluateSymbolCycle("CRUDEOIL", LocalTime.of(16, 0));

        assertThat(setup.getState()).isEqualTo(CommoditySetupState.ARMED_LONG);
        assertThat(setup.getTriggerHigh()).isEqualByComparingTo(new BigDecimal("5530.00"));
        assertThat(setup.getVwapAtSetup()).isEqualByComparingTo(new BigDecimal("5505.00"));
    }

    @Test
    @DisplayName("Should arm ARMED_SHORT when Bearish and 15m candle crosses below VWAP")
    void testBearishVwapCrossover() {
        CommoditySetup setup = service.getSetup("GOLD");
        setup.updateBias(CommodityBias.BEARISH, 0.75);

        // Previous candle closed above VWAP (close=75200, vwap=75100)
        // Current candle crosses and closes below VWAP (close=75050, low=75000, vwap=75100)
        List<Candle> candles =
                List.of(
                        createCandle("GOLD", 75000, 75250, 74950, 75200, 100),
                        createCandle("GOLD", 75200, 75210, 75000, 75050, 200));

        when(quoteFeed.candles15m(eq("GOLD"), anyInt())).thenReturn(candles);
        when(taService.calculateVwapSeries(anyList())).thenReturn(new double[] {75100.0, 75100.0});

        service.evaluateSymbolCycle("GOLD", LocalTime.of(16, 15));

        assertThat(setup.getState()).isEqualTo(CommoditySetupState.ARMED_SHORT);
        assertThat(setup.getTriggerLow()).isEqualByComparingTo(new BigDecimal("75000.00"));
        assertThat(setup.getVwapAtSetup()).isEqualByComparingTo(new BigDecimal("75100.00"));
    }

    @Test
    @DisplayName("Should execute LONG trade entry on trigger high breakout with 1:2 RR")
    void testLongTradeEntryAndExitOnTarget() {
        CommoditySetup setup = service.getSetup("CRUDEOIL");
        setup.updateBias(CommodityBias.BULLISH, 1.30);
        setup.armLong(
                new BigDecimal("5530.00"),
                new BigDecimal("5500.00"),
                Instant.now()); // Risk = 30 pts

        when(quoteFeed.candles15m(eq("CRUDEOIL"), anyInt())).thenReturn(Collections.emptyList());

        // 1. Live quote breaks Trigger High (LTP = 5532 >= 5530)
        AtomicReference<BigDecimal> ltp = new AtomicReference<>(new BigDecimal("5532.00"));
        when(quoteFeed.liveLtp(anyString())).thenAnswer(inv -> ltp.get());

        service.evaluateSymbolCycle("CRUDEOIL", LocalTime.of(16, 30));

        assertThat(setup.getState()).isEqualTo(CommoditySetupState.IN_TRADE);
        CommodityTradePosition pos = setup.getActivePosition();
        assertThat(pos).isNotNull();
        assertThat(pos.side()).isEqualTo("LONG");
        assertThat(pos.entryPrice()).isEqualByComparingTo(new BigDecimal("5530.00"));
        assertThat(pos.stopLoss()).isEqualByComparingTo(new BigDecimal("5500.00")); // VWAP
        assertThat(pos.targetPrice())
                .isEqualByComparingTo(new BigDecimal("5605.00")); // 5530 + 2.5*30

        // 2. Next check: Target is reached (LTP = 5610 >= 5605) -> 50% partial booked, SL moved to
        // Cost
        ltp.set(new BigDecimal("5610.00"));
        service.evaluateSymbolCycle("CRUDEOIL", LocalTime.of(15, 0));

        assertThat(setup.getState()).isEqualTo(CommoditySetupState.IN_TRADE);
        assertThat(setup.getActivePosition().isPartialBooked()).isTrue();
        assertThat(setup.getActivePosition().isClosed()).isFalse();
        assertThat(setup.getActivePosition().currentStopLoss())
                .isEqualByComparingTo(new BigDecimal("5530.00")); // Moved to Cost

        // 3. Next check: Runner dynamic trailing SL hit (LTP = 5525 <= 5530)
        ltp.set(new BigDecimal("5525.00"));
        service.evaluateSymbolCycle("CRUDEOIL", LocalTime.of(15, 15));

        assertThat(setup.getState()).isEqualTo(CommoditySetupState.COMPLETED);
        assertThat(setup.getActivePosition().isClosed()).isTrue();
        assertThat(setup.getActivePosition().exitReason()).isEqualTo("COST_BREAKEVEN_EXIT");
        assertThat(setup.getTradesToday()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should execute SHORT trade entry on trigger low breakout and exit on Stop Loss")
    void testShortTradeEntryAndExitOnStopLoss() {
        CommoditySetup setup = service.getSetup("GOLD");
        setup.updateBias(CommodityBias.BEARISH, 0.75);
        setup.armShort(
                new BigDecimal("75000.00"),
                new BigDecimal("75300.00"),
                Instant.now()); // Risk = 300 pts

        when(quoteFeed.candles15m(eq("GOLD"), anyInt())).thenReturn(Collections.emptyList());

        // 1. Live quote breaks Trigger Low (LTP = 74990 <= 75000)
        AtomicReference<BigDecimal> ltp = new AtomicReference<>(new BigDecimal("74990.00"));
        when(quoteFeed.liveLtp(anyString())).thenAnswer(inv -> ltp.get());

        service.evaluateSymbolCycle("GOLD", LocalTime.of(16, 30));

        assertThat(setup.getState()).isEqualTo(CommoditySetupState.IN_TRADE);
        CommodityTradePosition pos = setup.getActivePosition();
        assertThat(pos).isNotNull();
        assertThat(pos.side()).isEqualTo("SHORT");
        assertThat(pos.entryPrice()).isEqualByComparingTo(new BigDecimal("75000.00"));
        assertThat(pos.stopLoss()).isEqualByComparingTo(new BigDecimal("75300.00"));
        assertThat(pos.targetPrice())
                .isEqualByComparingTo(new BigDecimal("74250.00")); // 75000 - 2.5*300

        // 2. Stop Loss is hit (LTP = 75310 >= 75300)
        ltp.set(new BigDecimal("75310.00"));
        service.evaluateSymbolCycle("GOLD", LocalTime.of(15, 0));

        assertThat(setup.getState()).isEqualTo(CommoditySetupState.COMPLETED);
        assertThat(setup.getActivePosition().isClosed()).isTrue();
        assertThat(setup.getActivePosition().exitReason()).isEqualTo("STOP_LOSS_HIT");
        assertThat(setup.getTradesToday()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should enforce daily maximum trades and skip new setups if already completed")
    void testMaxTradesConstraint() {
        CommoditySetup setup = service.getSetup("SILVER");
        setup.updateBias(CommodityBias.BULLISH, 1.30);
        setup.setTradesToday(1);
        setup.setState(CommoditySetupState.COMPLETED);

        service.evaluateSymbolCycle("SILVER", LocalTime.of(16, 0));

        // State remains COMPLETED and does not re-arm
        assertThat(setup.getState()).isEqualTo(CommoditySetupState.COMPLETED);
        verify(quoteFeed, never()).candles15m(eq("SILVER"), anyInt());
    }

    @Test
    @DisplayName("Should square off all open positions at 23:15 EOD")
    void testEodSquareOff() {
        CommoditySetup setup = service.getSetup("CRUDEOIL");
        setup.updateBias(CommodityBias.BULLISH, 1.30);
        CommodityTradePosition pos =
                CommodityTradePosition.createLong(
                        "CRUDEOILM",
                        new BigDecimal("5500.00"),
                        new BigDecimal("5450.00"),
                        new BigDecimal("2.0"),
                        10,
                        Instant.now());
        setup.enterTrade(pos);

        AtomicReference<BigDecimal> ltp = new AtomicReference<>(new BigDecimal("5540.00"));
        when(quoteFeed.liveLtp(anyString())).thenAnswer(inv -> ltp.get());

        service.squareOffAllPositions("EOD_SQUARE_OFF");

        assertThat(setup.getState()).isEqualTo(CommoditySetupState.COMPLETED);
        assertThat(setup.getActivePosition().isClosed()).isTrue();
        assertThat(setup.getActivePosition().exitReason()).isEqualTo("EOD_SQUARE_OFF");
        assertThat(setup.getActivePosition().exitPrice())
                .isEqualByComparingTo(new BigDecimal("5540.00"));
    }

    @Test
    @DisplayName("DST-aware blackout: EDT/EST macro windows and Wednesday EIA for crude only")
    void testDstAwareMacroAndEiaBlackouts() {
        java.time.DayOfWeek wed = java.time.DayOfWeek.WEDNESDAY;
        java.time.DayOfWeek thu = java.time.DayOfWeek.THURSDAY;

        // Summer EDT (2026-07-01 is a Wednesday): 08:30 ET -> 18:00 IST, window 17:30-18:30
        java.time.LocalDate summerWed = java.time.LocalDate.of(2026, 7, 1);
        assertThat(service.isMacroNewsBlackout(summerWed, LocalTime.of(18, 5), wed, "SILVER"))
                .isTrue();
        assertThat(service.isMacroNewsBlackout(summerWed, LocalTime.of(17, 20), wed, "SILVER"))
                .isFalse();
        assertThat(service.isMacroNewsBlackout(summerWed, LocalTime.of(18, 40), wed, "SILVER"))
                .isFalse();

        // EIA Wednesday 10:30 ET -> 20:00 IST, window 19:30-20:30, crude symbols only
        assertThat(service.isMacroNewsBlackout(summerWed, LocalTime.of(20, 5), wed, "CRUDEOIL"))
                .isTrue();
        assertThat(service.isMacroNewsBlackout(summerWed, LocalTime.of(20, 5), wed, "SILVER"))
                .isFalse();
        assertThat(service.isMacroNewsBlackout(summerWed, LocalTime.of(20, 5), thu, "CRUDEOIL"))
                .isFalse();

        // Winter EST (2026-01-15 is a Thursday): 08:30 ET -> 19:00 IST, window 18:30-19:30.
        // 18:05 IST must be FREE in winter (the old hardcoded 18:00-18:15 window wrongly blocked
        // it).
        java.time.LocalDate winterThu = java.time.LocalDate.of(2026, 1, 15);
        assertThat(service.isMacroNewsBlackout(winterThu, LocalTime.of(19, 0), thu, "SILVER"))
                .isTrue();
        assertThat(service.isMacroNewsBlackout(winterThu, LocalTime.of(18, 5), thu, "SILVER"))
                .isFalse();
        assertThat(service.isMacroNewsBlackout(winterThu, LocalTime.of(19, 40), thu, "SILVER"))
                .isFalse();

        // Winter EST Wednesday EIA: 10:30 ET -> 21:00 IST, window 20:30-21:30
        java.time.LocalDate winterWed = java.time.LocalDate.of(2026, 1, 14);
        assertThat(service.isMacroNewsBlackout(winterWed, LocalTime.of(21, 0), wed, "CRUDEOIL"))
                .isTrue();
        assertThat(service.isMacroNewsBlackout(winterWed, LocalTime.of(20, 0), wed, "CRUDEOIL"))
                .isFalse();

        // DST boundary 2026: US DST starts Sunday 2026-03-08. Sat 03-07 is EST (19:00 macro);
        // Mon 03-09 is EDT (18:00 macro).
        assertThat(
                        service.isMacroNewsBlackout(
                                java.time.LocalDate.of(2026, 3, 7),
                                LocalTime.of(18, 5),
                                java.time.DayOfWeek.SATURDAY,
                                "SILVER"))
                .isFalse();
        assertThat(
                        service.isMacroNewsBlackout(
                                java.time.LocalDate.of(2026, 3, 7),
                                LocalTime.of(19, 0),
                                java.time.DayOfWeek.SATURDAY,
                                "SILVER"))
                .isTrue();
        assertThat(
                        service.isMacroNewsBlackout(
                                java.time.LocalDate.of(2026, 3, 9),
                                LocalTime.of(18, 0),
                                java.time.DayOfWeek.MONDAY,
                                "SILVER"))
                .isTrue();
        assertThat(
                        service.isMacroNewsBlackout(
                                java.time.LocalDate.of(2026, 3, 9),
                                LocalTime.of(19, 0),
                                java.time.DayOfWeek.MONDAY,
                                "SILVER"))
                .isFalse();
    }

    @Test
    @DisplayName("DTE gate: bias skipped for near-expiry contract, evaluated for safe contract")
    void testDteGateSkipsNearExpiryContract() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        MutableClock clock =
                new MutableClock(
                        LocalDate.of(2026, 10, 9).atTime(13, 30).atZone(ist).toInstant(), ist);
        service =
                new CommodityVwapStrategyService(
                        properties, quoteFeed, taService, telegramService, clock, signalPublisher);

        when(quoteFeed.contractExpiry("CRUDEOIL")).thenReturn(LocalDate.of(2026, 10, 12)); // 3 DTE
        when(quoteFeed.contractExpiry("GOLD")).thenReturn(LocalDate.of(2026, 10, 25)); // 16 DTE
        when(quoteFeed.optionChain("CRUDEOIL"))
                .thenReturn(createMockChain("CRUDEOIL", 13000, 10000));
        when(quoteFeed.optionChain("GOLD")).thenReturn(createMockChain("GOLD", 13000, 10000));

        service.evaluateDailyBias();

        assertThat(service.getSetup("CRUDEOIL").getState()).isEqualTo(CommoditySetupState.SKIPPED);
        assertThat(service.getSetup("CRUDEOIL").getBias()).isEqualTo(CommodityBias.NEUTRAL);
        verify(quoteFeed, never()).optionChain("CRUDEOIL");

        assertThat(service.getSetup("GOLD").getState())
                .isEqualTo(CommoditySetupState.BIAS_IDENTIFIED);
        assertThat(service.getSetup("GOLD").getBias()).isEqualTo(CommodityBias.BULLISH);
    }

    @Test
    @DisplayName("Volume gate: thin arming bar blocks arm; average-volume bar arms")
    void testVolumeConfirmationGate() {
        CommoditySetup setup = service.getSetup("CRUDEOIL");
        setup.updateBias(CommodityBias.BULLISH, 1.25);

        List<Candle> lowVolumeBars = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            lowVolumeBars.add(createCandle("CRUDEOIL", 5450, 5490, 5440, 5480, 100));
        }
        lowVolumeBars.add(createCandle("CRUDEOIL", 5480, 5530, 5475, 5520, 10)); // ratio 0.1
        when(quoteFeed.candles15m(eq("CRUDEOIL"), anyInt())).thenReturn(lowVolumeBars);
        double[] flatVwap = new double[11];
        java.util.Arrays.fill(flatVwap, 5500.0);
        flatVwap[10] = 5505.0;
        when(taService.calculateVwapSeries(anyList())).thenReturn(flatVwap);

        service.evaluateSymbolCycle("CRUDEOIL", LocalTime.of(16, 0));
        assertThat(setup.getState()).isEqualTo(CommoditySetupState.BIAS_IDENTIFIED); // not armed

        List<Candle> healthyVolumeBars = new java.util.ArrayList<>(lowVolumeBars.subList(0, 10));
        healthyVolumeBars.add(createCandle("CRUDEOIL", 5480, 5530, 5475, 5520, 150)); // ratio 1.5
        when(quoteFeed.candles15m(eq("CRUDEOIL"), anyInt())).thenReturn(healthyVolumeBars);

        service.evaluateSymbolCycle("CRUDEOIL", LocalTime.of(16, 15));
        assertThat(setup.getState()).isEqualTo(CommoditySetupState.ARMED_LONG);
    }

    @Test
    @DisplayName("PCR z-score: flat bullish regime vetoes a repeated signal after minSamples")
    void testPcrZScoreVetoesFlatRegimeSignal() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        MutableClock clock =
                new MutableClock(
                        LocalDate.of(2026, 10, 1).atTime(13, 30).atZone(ist).toInstant(), ist);
        properties.setSymbols(List.of("CRUDEOIL"));
        service =
                new CommodityVwapStrategyService(
                        properties, quoteFeed, taService, telegramService, clock, signalPublisher);

        when(quoteFeed.optionChain("CRUDEOIL"))
                .thenReturn(createMockChain("CRUDEOIL", 13000, 10000));

        for (int day = 0; day < 10; day++) {
            clock.setInstant(
                    LocalDate.of(2026, 10, 1 + day).atTime(13, 30).atZone(ist).toInstant());
            service.evaluateDailyBias();
            assertThat(service.getSetup("CRUDEOIL").getBias()).isEqualTo(CommodityBias.BULLISH);
        }

        clock.setInstant(LocalDate.of(2026, 10, 11).atTime(13, 30).atZone(ist).toInstant());
        service.evaluateDailyBias();
        assertThat(service.getSetup("CRUDEOIL").getBias()).isEqualTo(CommodityBias.NEUTRAL);
        assertThat(service.getSetup("CRUDEOIL").getState()).isEqualTo(CommoditySetupState.SKIPPED);
    }

    @Test
    @DisplayName("PCR z-score: fresh breakout above a quiet regime is confirmed BULLISH")
    void testPcrZScoreConfirmsRegimeBreakout() {
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        MutableClock clock =
                new MutableClock(
                        LocalDate.of(2026, 10, 1).atTime(13, 30).atZone(ist).toInstant(), ist);
        properties.setSymbols(List.of("CRUDEOIL"));
        service =
                new CommodityVwapStrategyService(
                        properties, quoteFeed, taService, telegramService, clock, signalPublisher);

        when(quoteFeed.optionChain("CRUDEOIL"))
                .thenReturn(createMockChain("CRUDEOIL", 10500, 10000));

        for (int day = 0; day < 10; day++) {
            clock.setInstant(
                    LocalDate.of(2026, 10, 1 + day).atTime(13, 30).atZone(ist).toInstant());
            service.evaluateDailyBias();
        }

        when(quoteFeed.optionChain("CRUDEOIL"))
                .thenReturn(createMockChain("CRUDEOIL", 14000, 10000));
        clock.setInstant(LocalDate.of(2026, 10, 11).atTime(13, 30).atZone(ist).toInstant());
        service.evaluateDailyBias();
        assertThat(service.getSetup("CRUDEOIL").getBias()).isEqualTo(CommodityBias.BULLISH);
        assertThat(service.getSetup("CRUDEOIL").getState())
                .isEqualTo(CommoditySetupState.BIAS_IDENTIFIED);
    }

    @Test
    @DisplayName("Cost floor: micro-risk entry below min-risk-to-cost ratio is skipped")
    void testMinRiskCostFloorSkipsEntry() {
        CommoditySetup setup = service.getSetup("CRUDEOIL");
        setup.updateBias(CommodityBias.BULLISH, 1.30);
        // Risk of only 1 pt x 10 qty = Rs10 full risk vs ~Rs161 round-trip cost -> below 0.8x floor
        setup.armLong(new BigDecimal("5530.00"), new BigDecimal("5529.00"), Instant.now());

        AtomicReference<BigDecimal> ltp = new AtomicReference<>(new BigDecimal("5532.00"));
        when(quoteFeed.liveLtp(anyString())).thenAnswer(inv -> ltp.get());

        service.evaluateSymbolCycle("CRUDEOIL", LocalTime.of(16, 30));

        assertThat(setup.getState()).isEqualTo(CommoditySetupState.ARMED_LONG);
        assertThat(setup.getActivePosition()).isNull();
        verify(signalPublisher, never())
                .publish(
                        argThat(
                                s ->
                                        s != null
                                                && com.tradingbot.strategy.SignalAction.ENTRY_LONG
                                                        == s.action()));
    }

    @Test
    @DisplayName("Bus rejection: ENTRY signal declined by bus rolls back to ARMED (no position)")
    void testEntrySignalRejectedRollsBackToArmed() {
        when(signalPublisher.publish(any())).thenReturn(false);

        CommoditySetup setup = service.getSetup("CRUDEOIL");
        setup.updateBias(CommodityBias.BULLISH, 1.30);
        setup.armLong(new BigDecimal("5530.00"), new BigDecimal("5500.00"), Instant.now());

        AtomicReference<BigDecimal> ltp = new AtomicReference<>(new BigDecimal("5532.00"));
        when(quoteFeed.liveLtp(anyString())).thenAnswer(inv -> ltp.get());

        service.evaluateSymbolCycle("CRUDEOIL", LocalTime.of(16, 30));

        assertThat(setup.getState()).isEqualTo(CommoditySetupState.ARMED_LONG);
        assertThat(setup.getActivePosition()).isNull();
        verify(telegramService, never()).sendTextMessage(contains("TRADE ENTERED"));
    }

    @Test
    @DisplayName("Bus wiring: entry, partial book and exit publish COMMODITY_VWAP signals on MCX")
    void testEntryPartialAndExitSignalsPublish() {
        CommoditySetup setup = service.getSetup("CRUDEOIL");
        setup.updateBias(CommodityBias.BULLISH, 1.30);
        setup.armLong(new BigDecimal("5530.00"), new BigDecimal("5500.00"), Instant.now());

        when(quoteFeed.candles15m(eq("CRUDEOIL"), anyInt())).thenReturn(Collections.emptyList());
        AtomicReference<BigDecimal> ltp = new AtomicReference<>(new BigDecimal("5532.00"));
        when(quoteFeed.liveLtp(anyString())).thenAnswer(inv -> ltp.get());

        service.evaluateSymbolCycle("CRUDEOIL", LocalTime.of(16, 30));
        assertThat(setup.getState()).isEqualTo(CommoditySetupState.IN_TRADE);

        // Target hit -> 50% partial book
        ltp.set(new BigDecimal("5610.00"));
        service.evaluateSymbolCycle("CRUDEOIL", LocalTime.of(15, 0));

        // Runner cost-breakeven exit
        ltp.set(new BigDecimal("5525.00"));
        service.evaluateSymbolCycle("CRUDEOIL", LocalTime.of(15, 15));
        assertThat(setup.getState()).isEqualTo(CommoditySetupState.COMPLETED);

        org.mockito.ArgumentCaptor<TradeSignal> captor =
                org.mockito.ArgumentCaptor.forClass(TradeSignal.class);
        verify(signalPublisher, org.mockito.Mockito.atLeast(3)).publish(captor.capture());
        List<com.tradingbot.strategy.SignalAction> actions =
                captor.getAllValues().stream().map(TradeSignal::action).toList();
        assertThat(actions)
                .containsExactly(
                        com.tradingbot.strategy.SignalAction.ENTRY_LONG,
                        com.tradingbot.strategy.SignalAction.PARTIAL_EXIT_LONG,
                        com.tradingbot.strategy.SignalAction.EXIT_LONG);
        TradeSignal entry = captor.getAllValues().get(0);
        assertThat(entry.strategyId()).isEqualTo(CommodityVwapStrategyService.STRATEGY_ID);
        assertThat(entry.metadata()).containsEntry("exchange", "MCX");
        assertThat(entry.metadata()).containsEntry("instrumentType", "FUTURES");
        TradeSignal partial = captor.getAllValues().get(1);
        assertThat(partial.metadata()).containsEntry("remainingQuantity", 5); // 50% of lot 10
    }

    @Test
    @DisplayName("manageActiveTrades: manages open positions only, never arms or enters")
    void testManageActiveTradesManagesOnlyOpenPositions() {
        CommoditySetup inTrade = service.getSetup("CRUDEOIL");
        CommodityTradePosition pos =
                CommodityTradePosition.createLong(
                        "CRUDEOILM",
                        new BigDecimal("5500.00"),
                        new BigDecimal("5450.00"),
                        new BigDecimal("2.5"),
                        10,
                        Instant.now());
        inTrade.enterTrade(pos);

        CommoditySetup armed = service.getSetup("GOLD");
        armed.updateBias(CommodityBias.BULLISH, 1.30);
        armed.armLong(new BigDecimal("75000.00"), new BigDecimal("74900.00"), Instant.now());

        AtomicReference<BigDecimal> ltp = new AtomicReference<>(new BigDecimal("5630.00"));
        when(quoteFeed.liveLtp(anyString())).thenAnswer(inv -> ltp.get());

        service.manageActiveTrades();

        assertThat(inTrade.getActivePosition().isPartialBooked()).isTrue();
        assertThat(armed.getState()).isEqualTo(CommoditySetupState.ARMED_LONG);
        assertThat(armed.getActivePosition()).isNull();
    }

    @Test
    @DisplayName(
            "Kill-switch: daily loss circuit breaker halts new entries and latches until reset")
    void testDailyLossCircuitBreakerHaltsEntries() {
        // SILVERM SL hit: (93000-95000) x 5 qty = -10,000 gross, net of costs exceeds Rs8,000 limit
        CommoditySetup silver = service.getSetup("SILVER");
        CommodityTradePosition pos =
                CommodityTradePosition.createLong(
                        "SILVERM",
                        new BigDecimal("95000.00"),
                        new BigDecimal("94000.00"),
                        new BigDecimal("2.5"),
                        5,
                        Instant.now());
        silver.enterTrade(pos);
        AtomicReference<BigDecimal> ltp = new AtomicReference<>(new BigDecimal("93000.00"));
        when(quoteFeed.liveLtp(anyString())).thenAnswer(inv -> ltp.get());
        service.manageActiveTrades();
        assertThat(silver.getActivePosition().isClosed()).isTrue();
        assertThat(service.calculateTodayRealizedNetPnl()).isLessThan(-8000.0);

        assertThat(service.isDailyCircuitBreakerTripped()).isTrue();
        assertThat(service.isKillSwitchActive()).isTrue();
        verify(telegramService, times(1)).sendTextMessage(contains("Circuit Breaker"));

        // Armed CRUDEOIL breakout must NOT enter while breaker is tripped
        CommoditySetup crude = service.getSetup("CRUDEOIL");
        crude.updateBias(CommodityBias.BULLISH, 1.30);
        crude.armLong(new BigDecimal("5530.00"), new BigDecimal("5500.00"), Instant.now());
        ltp.set(new BigDecimal("5532.00"));
        when(quoteFeed.candles15m(eq("CRUDEOIL"), anyInt())).thenReturn(Collections.emptyList());
        service.evaluateSymbolCycle("CRUDEOIL", LocalTime.of(16, 30));

        assertThat(crude.getState()).isEqualTo(CommoditySetupState.ARMED_LONG);
        assertThat(crude.getActivePosition()).isNull();
        verify(signalPublisher, never())
                .publish(
                        argThat(
                                s ->
                                        s != null
                                                && com.tradingbot.strategy.SignalAction.ENTRY_LONG
                                                        == s.action()));

        // Re-trip check must not send a second breaker alert
        assertThat(service.isDailyCircuitBreakerTripped()).isTrue();
        verify(telegramService, times(1)).sendTextMessage(contains("Circuit Breaker"));

        // Session reset clears the latch and allows entries again
        service.resetSession(true);
        assertThat(service.isKillSwitchActive()).isFalse();
        assertThat(service.isDailyCircuitBreakerTripped()).isFalse();

        crude.updateBias(CommodityBias.BULLISH, 1.30);
        crude.armLong(new BigDecimal("5530.00"), new BigDecimal("5500.00"), Instant.now());
        service.evaluateSymbolCycle("CRUDEOIL", LocalTime.of(16, 30));
        assertThat(crude.getState()).isEqualTo(CommoditySetupState.IN_TRADE);
    }

    @Test
    @DisplayName("Kill-switch: two losing trades halt new entries via loss-count guard")
    void testDailyLossCountHaltStopsEntries() {
        properties.setMaxDailyLossInr(0.0); // isolate loss-count switch from the rupee breaker
        properties.setMaxDailyLosses(2);

        // Two CRUDEOILM SL hits, each ~-Rs737 net (well under the disabled Rs breaker)
        for (int i = 0; i < 2; i++) {
            CommoditySetup crude = service.getSetup("CRUDEOIL");
            CommodityTradePosition pos =
                    CommodityTradePosition.createLong(
                            "CRUDEOILM",
                            new BigDecimal("5500.00"),
                            new BigDecimal("5450.00"),
                            new BigDecimal("2.5"),
                            10,
                            Instant.now());
            crude.enterTrade(pos);
            AtomicReference<BigDecimal> ltp = new AtomicReference<>(new BigDecimal("5400.00"));
            when(quoteFeed.liveLtp(anyString())).thenAnswer(inv -> ltp.get());
            service.manageActiveTrades();
        }
        assertThat(service.countTodayLosses()).isEqualTo(2);
        assertThat(service.isDailyLossCountHaltActive()).isTrue();
        assertThat(service.isKillSwitchActive()).isTrue();
        verify(telegramService, times(1)).sendTextMessage(contains("Loss-Count Halt"));

        // GOLD armed breakout must NOT enter while the loss-count halt is active
        CommoditySetup gold = service.getSetup("GOLD");
        gold.updateBias(CommodityBias.BULLISH, 1.30);
        gold.armLong(new BigDecimal("75000.00"), new BigDecimal("74900.00"), Instant.now());
        AtomicReference<BigDecimal> ltp = new AtomicReference<>(new BigDecimal("75010.00"));
        when(quoteFeed.liveLtp(anyString())).thenAnswer(inv -> ltp.get());
        when(quoteFeed.candles15m(eq("GOLD"), anyInt())).thenReturn(Collections.emptyList());
        service.evaluateSymbolCycle("GOLD", LocalTime.of(16, 30));

        assertThat(gold.getState()).isEqualTo(CommoditySetupState.ARMED_LONG);
        assertThat(gold.getActivePosition()).isNull();

        service.resetSession(true);
        assertThat(service.isKillSwitchActive()).isFalse();
    }

    @Test
    @DisplayName("Kill-switch: profitable day keeps both switches inactive")
    void testWinningDayDoesNotTripKillSwitches() {
        CommoditySetup crude = service.getSetup("CRUDEOIL");
        CommodityTradePosition pos =
                CommodityTradePosition.createLong(
                        "CRUDEOILM",
                        new BigDecimal("5500.00"),
                        new BigDecimal("5450.00"),
                        new BigDecimal("2.5"),
                        10,
                        Instant.now());
        crude.enterTrade(pos);
        // Target full exit at 5625 (1:2.5) -> +1,250 gross winner
        AtomicReference<BigDecimal> ltp = new AtomicReference<>(new BigDecimal("5625.00"));
        when(quoteFeed.liveLtp(anyString())).thenAnswer(inv -> ltp.get());
        when(quoteFeed.candles15m(eq("CRUDEOIL"), anyInt())).thenReturn(Collections.emptyList());
        service.manageActiveTrades(); // partial book at target
        ltp.set(new BigDecimal("5500.00")); // runner exits at cost breakeven
        service.manageActiveTrades();

        assertThat(service.countTodayLosses()).isZero();
        assertThat(service.isDailyCircuitBreakerTripped()).isFalse();
        assertThat(service.isDailyLossCountHaltActive()).isFalse();
        assertThat(service.isKillSwitchActive()).isFalse();
    }

    private OptionChainResponse createMockChain(String underlying, long putOi, long callOi) {
        double pcr = callOi > 0 ? (double) putOi / callOi : 0.0;
        double roundedPcr = Math.round(pcr * 100.0) / 100.0;
        return new OptionChainResponse(
                underlying,
                BigDecimal.valueOf(5000),
                BigDecimal.valueOf(5000),
                underlying + "-FUT",
                5,
                callOi,
                putOi,
                roundedPcr,
                Collections.emptyList());
    }

    private Candle createCandle(
            String symbol, double open, double high, double low, double close, long volume) {
        return new Candle(
                symbol,
                "15",
                Instant.now(),
                BigDecimal.valueOf(open),
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(close),
                volume);
    }
}
