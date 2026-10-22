package com.tradingbot.strategy.ttcstraddle.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.tradingbot.strategy.ttcstraddle.config.IntradayStraddleProperties;
import com.tradingbot.strategy.ttcstraddle.service.IntradayStraddleService;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IntradayStraddleSchedulerTest {

    private IntradayStraddleService service;
    private IntradayStraddleProperties properties;
    private IntradayStraddleScheduler scheduler;

    @BeforeEach
    void setUp() {
        service = mock(IntradayStraddleService.class);
        properties = new IntradayStraddleProperties();
        scheduler = new IntradayStraddleScheduler(service, properties);
    }

    @Test
    @DisplayName(
            "Scheduler delegates daily reset, 09:16 hedge entry, 10:00 straddle entry, and 15:00 hard exit")
    void testSchedulerLifecycleDelegation() {
        scheduler.scheduledDailyReset();
        verify(service, times(1)).resetDaily();

        scheduler.scheduledHedgeEntry();
        verify(service, times(1)).executeHedgeWingEntry(any(LocalTime.class));

        scheduler.scheduledStraddleEntry();
        verify(service, times(1)).executeStraddleEntry(any(LocalTime.class));

        scheduler.scheduledFinalHardExit();
        verify(service, times(1)).executeHardSquareOff(any(String.class));
    }
}
