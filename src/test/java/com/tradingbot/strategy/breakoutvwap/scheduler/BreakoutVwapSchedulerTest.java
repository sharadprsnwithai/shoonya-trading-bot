package com.tradingbot.strategy.breakoutvwap.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tradingbot.strategy.breakoutvwap.config.BvsProperties;
import com.tradingbot.strategy.breakoutvwap.service.BreakoutVwapService;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Cron/toggle contract for the BVS scheduler — constructor-instantiated, no Spring context. */
class BreakoutVwapSchedulerTest {

    private BreakoutVwapService service;
    private BvsProperties properties;
    private BreakoutVwapScheduler scheduler;

    @BeforeEach
    void setUp() {
        service = mock(BreakoutVwapService.class);
        properties = new BvsProperties();
        scheduler = new BreakoutVwapScheduler(service, properties);
    }

    private void fireAll() {
        scheduler.scheduledOiSpurtPrefetch();
        scheduler.scheduledScan();
        scheduler.scheduledCycle();
        scheduler.scheduledTick();
        scheduler.scheduledHardExit();
        scheduler.scheduledHardExitRetry();
    }

    @Test
    @DisplayName("enabled scheduler delegates every job exactly once per trigger")
    void scheduledMethodsDelegateWhenEnabled() {
        fireAll();

        verify(service).prefetchOiSpurts();
        verify(service).runScan();
        verify(service).runCycle();
        verify(service).evaluateLivePriceActions();
        verify(service, times(2)).executeHardExit(LocalTime.of(15, 0));
    }

    @Test
    @DisplayName("scheduler-enabled=false suppresses every job")
    void schedulerDisabledSuppressesAllJobs() {
        properties.setSchedulerEnabled(false);

        fireAll();

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("enabled=false suppresses every job")
    void strategyDisabledSuppressesAllJobs() {
        properties.setEnabled(false);

        fireAll();

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("both hard-exit jobs pass 15:00")
    void hardExitUsesFifteenHundred() {
        scheduler.scheduledHardExit();
        scheduler.scheduledHardExitRetry();

        verify(service, times(2)).executeHardExit(LocalTime.of(15, 0));
    }
}
