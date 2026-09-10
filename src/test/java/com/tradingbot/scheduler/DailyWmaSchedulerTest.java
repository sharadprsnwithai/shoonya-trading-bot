package com.tradingbot.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.tradingbot.service.DailyWmaStrategyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DailyWmaSchedulerTest {

    private DailyWmaStrategyService strategyService;
    private DailyWmaScheduler scheduler;

    @BeforeEach
    void setUp() {
        strategyService = mock(DailyWmaStrategyService.class);
        scheduler = new DailyWmaScheduler(strategyService, true);
    }

    @Test
    void testScheduledDailyEvaluationCycle() {
        scheduler.scheduledDailyEvaluationCycle();
        verify(strategyService).evaluateDailyCycle();
    }

    @Test
    void testScheduledStopLossMonitor() {
        scheduler.scheduledStopLossMonitor();
        verify(strategyService).monitorStopLoss();
    }

    @Test
    void testScheduledExpirySquareOff() {
        scheduler.scheduledExpirySquareOff();
        verify(strategyService).executeExpirySquareOff("EXPIRY_DAY_SQUARE_OFF");
    }
}
