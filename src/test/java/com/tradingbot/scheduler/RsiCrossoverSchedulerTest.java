package com.tradingbot.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.tradingbot.service.RsiCrossoverStrategyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RsiCrossoverSchedulerTest {

    private RsiCrossoverStrategyService strategyService;
    private RsiCrossoverScheduler scheduler;

    @BeforeEach
    void setUp() {
        strategyService = mock(RsiCrossoverStrategyService.class);
        scheduler = new RsiCrossoverScheduler(strategyService);
    }

    @Test
    void testScheduledDailyResetCallsService() {
        scheduler.scheduledDailyReset();
        verify(strategyService).resetDaily();
    }

    @Test
    void testScheduledEodSquareOffCallsService() {
        scheduler.scheduledEodSquareOff();
        verify(strategyService).executeSquareOff("MANDATORY_EOD_SQUARE_OFF");
    }

    @Test
    void testDisabledSchedulerSkipsExecution() {
        scheduler.setSchedulerEnabled(false);
        scheduler.scheduledEvaluationCycle();
        scheduler.scheduledEodSquareOff();

        verify(strategyService, never()).runCycle();
        verify(strategyService, never()).executeSquareOff("MANDATORY_EOD_SQUARE_OFF");
    }
}
