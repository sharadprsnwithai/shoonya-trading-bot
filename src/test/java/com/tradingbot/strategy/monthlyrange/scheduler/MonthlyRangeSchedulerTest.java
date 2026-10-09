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
            "Should execute scheduled post-expiry routine when date is first session post-expiry (Oct 28)")
    void testExecuteOnPostExpirySession() {
        LocalDate postExpiryOct2026 = LocalDate.of(2026, 10, 28);
        scheduler.executeRoutineForDate(postExpiryOct2026);

        verify(service, times(1)).generateMonthlyReport();
    }

    @Test
    @DisplayName(
            "Should execute scheduled post-expiry routine when month ends on Tuesday (June 30 -> July 1)")
    void testExecuteOnMonthRolloverPostExpirySession() {
        LocalDate postExpiryJuly1_2026 = LocalDate.of(2026, 7, 1);
        scheduler.executeRoutineForDate(postExpiryJuly1_2026);

        verify(service, times(1)).generateMonthlyReport();
    }

    @Test
    @DisplayName("Should skip execution if date is not post-expiry session")
    void testSkipIfNotPostExpirySession() {
        LocalDate midWedOct2026 = LocalDate.of(2026, 10, 14);
        scheduler.executeRoutineForDate(midWedOct2026);

        verify(service, never()).generateMonthlyReport();
    }

    @Test
    @DisplayName("Should skip execution if strategy is disabled in properties")
    void testSkipIfDisabled() {
        properties.setEnabled(false);
        LocalDate postExpiryOct2026 = LocalDate.of(2026, 10, 28);
        scheduler.executeRoutineForDate(postExpiryOct2026);

        verify(service, never()).generateMonthlyReport();
    }
}
