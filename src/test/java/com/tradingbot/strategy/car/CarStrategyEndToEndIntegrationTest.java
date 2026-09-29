package com.tradingbot.strategy.car;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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

        service =
                new CarWeeklyGttService(
                        props,
                        calculator,
                        triggerGenerator,
                        ohlcService,
                        List.of(gttGateway),
                        telegramService);
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
}
