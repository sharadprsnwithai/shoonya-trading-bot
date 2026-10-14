package com.tradingbot.strategy.silverorb.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.tradingbot.strategy.silverorb.service.SilverOrbService;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Gating tests for {@link SilverOrbScheduler}: enable flag, evening window, exception safety. */
class SilverOrbSchedulerTest {

    private SilverOrbService service;
    private SilverOrbScheduler scheduler;

    @BeforeEach
    void setUp() {
        service = mock(SilverOrbService.class);
        scheduler = new SilverOrbScheduler(service, true);
    }

    @Test
    @DisplayName("Cycle runs inside the evening session window")
    void runsInsideWindow() {
        scheduler.triggerCycle(LocalTime.of(18, 20));
        scheduler.triggerCycle(LocalTime.of(21, 0));
        scheduler.triggerCycle(LocalTime.of(23, 5));

        verify(service, times(3)).runCycle();
    }

    @Test
    @DisplayName("Cycle is skipped before 18:00 and after 23:40")
    void skipsOutsideWindow() {
        scheduler.triggerCycle(LocalTime.of(17, 55));
        scheduler.triggerCycle(LocalTime.of(12, 0));
        scheduler.triggerCycle(LocalTime.of(23, 41));

        verify(service, never()).runCycle();
    }

    @Test
    @DisplayName("Boundary times 18:00 and 23:40 are inside the window")
    void boundaryTimesRun() {
        scheduler.triggerCycle(LocalTime.of(18, 0));
        scheduler.triggerCycle(LocalTime.of(23, 40));

        verify(service, times(2)).runCycle();
    }

    @Test
    @DisplayName("Disabled scheduler never triggers the service")
    void disabledNeverRuns() {
        SilverOrbScheduler disabled = new SilverOrbScheduler(service, false);

        disabled.triggerCycle(LocalTime.of(20, 0));

        verify(service, never()).runCycle();
    }

    @Test
    @DisplayName("An exception in the cycle is swallowed so the cron thread survives")
    void exceptionIsSwallowed() {
        doThrow(new RuntimeException("broker down")).when(service).runCycle();

        assertThatCode(
                        () -> {
                            scheduler.triggerCycle(LocalTime.of(19, 0));
                        })
                .doesNotThrowAnyException();

        verify(service, times(1)).runCycle();
    }
}
