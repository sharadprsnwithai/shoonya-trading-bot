package com.tradingbot.strategy.car;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.strategy.car.config.CarWeeklyProperties;
import com.tradingbot.strategy.car.gtt.GttExecutionGateway;
import com.tradingbot.strategy.car.model.CarHolding;
import com.tradingbot.strategy.car.model.CarPortfolioState;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CarStrategyEndToEndIntegrationTest {

    private CarWeeklyGttService service;
    private CarCalculator calculator;
    private CarWeeklyTriggerGenerator triggerGenerator;
    private HistoricalOhlcCacheService ohlcService;
    private GttExecutionGateway gttGateway;
    private TelegramService telegramService;

    @BeforeEach
    void setUp() {
        calculator = new CarCalculator(10, 252);
        triggerGenerator = new CarWeeklyTriggerGenerator(new BigDecimal("0.10"));
        ohlcService = mock(HistoricalOhlcCacheService.class);
        gttGateway = mock(GttExecutionGateway.class);
        telegramService = mock(TelegramService.class);

        CarWeeklyProperties props = new CarWeeklyProperties();
        props.setTotalCapital(1000000.0);
        props.setNumParts(40);
        props.setProfitTargetPct(6.28);
        props.setStateFilePath("data/test_car_state.json");

        com.tradingbot.strategy.car.config.CarExecutionGate gate =
                mock(com.tradingbot.strategy.car.config.CarExecutionGate.class);
        when(gate.isLiveWithLogging()).thenReturn(true);
        when(gttGateway.getBrokerName()).thenReturn("ZERODHA");

        service =
                new CarWeeklyGttService(
                        props,
                        calculator,
                        triggerGenerator,
                        ohlcService,
                        List.of(gttGateway),
                        telegramService,
                        gate);
    }

    @Test
    void testFullMultiWeekAccumulationTargetExitAndCompoundingCycle() {
        CarPortfolioState state = service.getPortfolioState();
        assertEquals(new BigDecimal("25000.00"), state.getUnitSize());
        assertEquals(40, state.getAvailableUnits());

        // Week 1: Buy Unit 1 for RELIANCE (50 shares @ 500.0)
        state.addFill("RELIANCE", 50, new BigDecimal("500.00"));
        CarHolding h1 = state.getHolding("RELIANCE");
        assertNotNull(h1);
        assertEquals(50, h1.totalQuantity());
        assertEquals(new BigDecimal("500.00"), h1.averageBuyPrice());
        assertEquals(new BigDecimal("531.40"), h1.targetPrice());

        // Week 2: Pullback happens -> Buy Unit 2 for RELIANCE (55 shares @ 460.0)
        state.addFill("RELIANCE", 55, new BigDecimal("460.00"));
        CarHolding h2 = state.getHolding("RELIANCE");
        assertEquals(105, h2.totalQuantity());
        assertEquals(new BigDecimal("479.05"), h2.averageBuyPrice());
        assertEquals(new BigDecimal("509.13"), h2.targetPrice());

        // Week 3: Target Hit at 509.13 -> Exit full position and realize profit
        BigDecimal profit = state.closeHoldingAtTarget("RELIANCE", new BigDecimal("509.13"));
        assertTrue(profit.compareTo(BigDecimal.ZERO) > 0);
        assertNull(state.getHolding("RELIANCE"));

        // Verify capital compounded and UNIT size expanded
        assertTrue(state.getTotalCapital().compareTo(new BigDecimal("1000000.0")) > 0);
        assertTrue(state.getUnitSize().compareTo(new BigDecimal("25000.00")) > 0);
    }

    @Test
    void testVideoWorkedExamplesGailAndRelaxoSignals() {
        java.time.Instant t0 = java.time.Instant.parse("2025-01-01T04:00:00Z");
        java.time.LocalDate asOf = java.time.LocalDate.of(2025, 1, 26);

        // 1. GAIL: 52W High of 234.34 at Day 0, then 15 days down, then 9 consecutive rising days
        // (10 session tail)
        java.util.List<com.tradingbot.model.Candle> gailCandles = new java.util.ArrayList<>();
        gailCandles.add(
                com.tradingbot.model.Candle.ofDaily(
                        "GAIL",
                        t0,
                        new BigDecimal("180"),
                        new BigDecimal("234.34"), // 52W High
                        new BigDecimal("178"),
                        new BigDecimal("185.00"),
                        100000));
        for (int i = 1; i <= 15; i++) {
            BigDecimal c = BigDecimal.valueOf(185 - i * 2);
            gailCandles.add(
                    com.tradingbot.model.Candle.ofDaily(
                            "GAIL",
                            t0.plus(i, java.time.temporal.ChronoUnit.DAYS),
                            c,
                            c.add(BigDecimal.ONE),
                            c.subtract(BigDecimal.ONE),
                            c,
                            50000));
        }
        for (int i = 16; i <= 24; i++) {
            BigDecimal c = BigDecimal.valueOf(172 + (i - 15) * 2);
            gailCandles.add(
                    com.tradingbot.model.Candle.ofDaily(
                            "GAIL",
                            t0.plus(i, java.time.temporal.ChronoUnit.DAYS),
                            c,
                            c.add(BigDecimal.ONE),
                            c.subtract(BigDecimal.ONE),
                            c,
                            60000));
        }
        com.tradingbot.strategy.car.model.CarAnalysisResult gailRes =
                calculator.analyze("GAIL", gailCandles, asOf);
        assertTrue(
                gailRes.isCarPositive(), "GAIL with 9 consecutive increases must be CAR-positive");
        assertEquals(new BigDecimal("234.34"), gailRes.fiftyTwoWeekAnchorHigh());

        // 2. RELAXO: 52W High at Day 0, then continuous drop without reversal
        java.util.List<com.tradingbot.model.Candle> relaxoCandles = new java.util.ArrayList<>();
        relaxoCandles.add(
                com.tradingbot.model.Candle.ofDaily(
                        "RELAXO",
                        t0,
                        new BigDecimal("490"),
                        new BigDecimal("500"), // 52W High
                        new BigDecimal("485"),
                        new BigDecimal("498.30"),
                        100000));
        for (int i = 1; i <= 20; i++) {
            BigDecimal c = BigDecimal.valueOf(498.30 - i * 3);
            relaxoCandles.add(
                    com.tradingbot.model.Candle.ofDaily(
                            "RELAXO",
                            t0.plus(i, java.time.temporal.ChronoUnit.DAYS),
                            c,
                            c.add(BigDecimal.ONE),
                            c.subtract(BigDecimal.ONE),
                            c,
                            50000));
        }
        com.tradingbot.strategy.car.model.CarAnalysisResult relaxoRes =
                calculator.analyze("RELAXO", relaxoCandles, asOf);
        assertFalse(
                relaxoRes.isCarPositive(),
                "RELAXO continuously falling must be AVOID/HOLD (not positive)");
        assertEquals(0, relaxoRes.consecutivePositiveDays());
    }
}
