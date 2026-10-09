package com.tradingbot.strategy.commodity.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tradingbot.strategy.commodity.config.CommodityVwapProperties;
import com.tradingbot.strategy.commodity.service.CommodityVwapStrategyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommodityVwapSchedulerTest {

    private CommodityVwapProperties properties;
    private CommodityVwapStrategyService strategyService;
    private CommodityVwapScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new CommodityVwapProperties();
        strategyService = mock(CommodityVwapStrategyService.class);
        scheduler = new CommodityVwapScheduler(properties, strategyService);
    }

    @Test
    @DisplayName("Should invoke 1:30 PM bias check when strategy enabled")
    void testScheduled130PmBiasCheck() {
        scheduler.scheduled130PmBiasCheck();
        verify(strategyService).evaluateDailyBias();
    }

    @Test
    @DisplayName("Should invoke 15m cycle evaluation when strategy enabled")
    void testScheduled15MinCycle() {
        scheduler.scheduled15MinCycle();
        verify(strategyService).evaluateStrategyCycle();
    }

    @Test
    @DisplayName("Should invoke 23:15 EOD square off when strategy enabled")
    void testScheduledEodSquareOff() {
        scheduler.scheduledEodSquareOff();
        verify(strategyService).squareOffAllPositions("EOD_SQUARE_OFF");
    }

    @Test
    @DisplayName("Should skip scheduled tasks when strategy disabled")
    void testDisabledStrategy() {
        properties.setEnabled(false);

        scheduler.scheduled130PmBiasCheck();
        scheduler.scheduled15MinCycle();
        scheduler.scheduledEodSquareOff();

        verify(strategyService, never()).evaluateDailyBias();
        verify(strategyService, never()).evaluateStrategyCycle();
        verify(strategyService, never()).squareOffAllPositions("EOD_SQUARE_OFF");
    }
}
