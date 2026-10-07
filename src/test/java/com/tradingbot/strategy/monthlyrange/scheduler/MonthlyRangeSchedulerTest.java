package com.tradingbot.strategy.monthlyrange.scheduler;

import static org.mockito.Mockito.*;

import com.tradingbot.strategy.monthlyrange.config.MonthlyRangeProperties;
import com.tradingbot.strategy.monthlyrange.service.MonthlyRangeService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MonthlyRangeSchedulerTest {

    private MonthlyRangeService service;
    private MonthlyRangeProperties properties;
    private MonthlyRangeScheduler scheduler;

    @BeforeEach
    void setUp() {
        service = mock(MonthlyRangeService.class);
        properties = new MonthlyRangeProperties();
        properties.setEnabled(true);
        scheduler = new MonthlyRangeScheduler(properties, service);
    }

    @Test
    @DisplayName(
            "Should execute scheduled post-expiry routine when date is last Wednesday of month")
    void testExecuteOnLastWednesday() {
        LocalDate lastWedOct2026 = LocalDate.of(2026, 10, 28);
        scheduler.executeRoutineForDate(lastWedOct2026);

        verify(service, times(1)).generateMonthlyReport();
    }

    @Test
    @DisplayName("Should skip execution if date is not the last Wednesday of month")
    void testSkipIfNotLastWednesday() {
        LocalDate midWedOct2026 = LocalDate.of(2026, 10, 14);
        scheduler.executeRoutineForDate(midWedOct2026);

        verify(service, never()).generateMonthlyReport();
    }

    @Test
    @DisplayName("Should skip execution if strategy is disabled in properties")
    void testSkipIfDisabled() {
        properties.setEnabled(false);
        LocalDate lastWedOct2026 = LocalDate.of(2026, 10, 28);
        scheduler.executeRoutineForDate(lastWedOct2026);

        verify(service, never()).generateMonthlyReport();
    }
}
