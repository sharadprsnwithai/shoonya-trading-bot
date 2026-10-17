package com.tradingbot.strategy.monthlyrange.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.tradingbot.strategy.monthlyrange.config.MonthlyRangeProperties;
import com.tradingbot.strategy.monthlyrange.service.GarchRangeService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GarchRangeSchedulerTest {

    private GarchRangeService service;
    private MonthlyRangeProperties properties;
    private GarchRangeScheduler scheduler;

    @BeforeEach
    void setUp() {
        service = mock(GarchRangeService.class);
        properties = new MonthlyRangeProperties();
        properties.setWeeklyRangeEnabled(true);
        scheduler = new GarchRangeScheduler(properties, service);
    }

    @Test
    @DisplayName("runs on the Wednesday deployment session following a Tuesday expiry")
    void testRunsOnWednesday() {
        scheduler.executeRoutineForDate(LocalDate.of(2026, 10, 7));

        verify(service, times(1)).generateWeeklyReport();
    }

    @Test
    @DisplayName("skips when the feature is disabled")
    void testSkipsWhenDisabled() {
        properties.setWeeklyRangeEnabled(false);

        scheduler.executeRoutineForDate(LocalDate.of(2026, 10, 7));

        verify(service, never()).generateWeeklyReport();
    }

    @Test
    @DisplayName("skips the Tuesday expiry session itself — there is no forward window left")
    void testSkipsExpirySession() {
        scheduler.executeRoutineForDate(LocalDate.of(2026, 10, 13));

        verify(service, never()).generateWeeklyReport();
    }

    @Test
    @DisplayName("skips non-deployment weekdays")
    void testSkipsMidweekDays() {
        scheduler.executeRoutineForDate(LocalDate.of(2026, 10, 8)); // Thursday
        scheduler.executeRoutineForDate(LocalDate.of(2026, 10, 9)); // Friday
        scheduler.executeRoutineForDate(LocalDate.of(2026, 10, 12)); // Monday

        verify(service, never()).generateWeeklyReport();
    }

    @Test
    @DisplayName("skips weekends rather than deferring to the next session")
    void testSkipsWeekend() {
        scheduler.executeRoutineForDate(LocalDate.of(2026, 10, 10)); // Saturday

        verify(service, never()).generateWeeklyReport();
    }

    @Test
    @DisplayName("skips an exchange holiday even when it falls on the deployment weekday")
    void testSkipsHolidayWednesday() {
        scheduler.executeRoutineForDate(LocalDate.of(2025, 2, 26)); // Maha Shivratri, a Wednesday

        verify(service, never()).generateWeeklyReport();
    }

    @Test
    @DisplayName("still runs the day after a Tuesday expiry that rolled to Monday on a holiday")
    void testRunsDayAfterRolledExpiry() {
        // Dussehra moves Tuesday 20 Oct 2026 to Monday 19 Oct; Wednesday 21st is still a session.
        scheduler.executeRoutineForDate(LocalDate.of(2026, 10, 21));

        verify(service, times(1)).generateWeeklyReport();
    }

    @Test
    @DisplayName("a throwing report does not propagate out of the scheduled job")
    void testExceptionIsContained() {
        org.mockito.Mockito.doThrow(new IllegalStateException("feed down"))
                .when(service)
                .generateWeeklyReport();

        scheduler.executeRoutineForDate(LocalDate.of(2026, 10, 7));

        verify(service, times(1)).generateWeeklyReport();
    }

    @Test
    @DisplayName("the deployment weekday is configurable and defaults to Wednesday")
    void testDeploymentDayIsConfigurable() {
        properties.setWeeklyDeploymentDayOfWeek("THURSDAY");

        scheduler.executeRoutineForDate(LocalDate.of(2026, 10, 8)); // Thursday
        scheduler.executeRoutineForDate(LocalDate.of(2026, 10, 7)); // Wednesday

        verify(service, times(1)).generateWeeklyReport();
    }

    @Test
    @DisplayName("an unparseable deployment weekday falls back to Wednesday rather than failing")
    void testUnparseableDeploymentDayFallsBack() {
        properties.setWeeklyDeploymentDayOfWeek("not-a-day");

        scheduler.executeRoutineForDate(LocalDate.of(2026, 10, 7));

        verify(service, times(1)).generateWeeklyReport();
    }
}
