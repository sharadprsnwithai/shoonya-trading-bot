package com.tradingbot.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tradingbot.model.strategy.MtfTrendStatus;
import com.tradingbot.service.MultiTimeframeScannerService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HourlyMtfScannerSchedulerTest {

    private MultiTimeframeScannerService scannerService;
    private HourlyMtfScannerScheduler scheduler;

    @BeforeEach
    void setUp() {
        scannerService = mock(MultiTimeframeScannerService.class);
        scheduler = new HourlyMtfScannerScheduler(scannerService);
    }

    @Test
    void testRunScanAndNotifyTriggersWorkflowAndAlerts() {
        MtfTrendStatus s1 =
                new MtfTrendStatus(
                        "COFORGE",
                        1950.0,
                        true,
                        1800.0,
                        1850.0,
                        true,
                        1900.0,
                        1880.0,
                        62.0,
                        true,
                        1920.0,
                        1910.0,
                        28.0,
                        true,
                        BigDecimal.valueOf(1950),
                        1910.0,
                        false);
        MtfTrendStatus s2 =
                new MtfTrendStatus(
                        "TATASTEEL",
                        140.0,
                        false,
                        150.0,
                        148.0,
                        false,
                        146.0,
                        145.0,
                        42.0,
                        false,
                        144.0,
                        143.0,
                        25.0,
                        false,
                        BigDecimal.valueOf(140),
                        143.0,
                        false,
                        true,
                        true,
                        BigDecimal.valueOf(140));

        List<MtfTrendStatus> all = List.of(s1, s2);
        List<MtfTrendStatus> uptrend = List.of(s1);
        List<MtfTrendStatus> downtrend = List.of(s2);

        when(scannerService.scanAllNifty200()).thenReturn(all);
        when(scannerService.getConfluenceUptrendStocks(all)).thenReturn(uptrend);
        when(scannerService.getConfluenceDowntrendStocks(all)).thenReturn(downtrend);
        when(scannerService.sendTelegramReport(anyList(), anyList(), anyInt())).thenReturn(true);

        List<MtfTrendStatus> result = scheduler.runScanAndNotify();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).symbol()).isEqualTo("COFORGE");
        assertThat(result.get(1).symbol()).isEqualTo("TATASTEEL");
        verify(scannerService).sendTelegramReport(uptrend, downtrend, 2);
    }

    @Test
    void testSchedulerDisabledFlag() {
        scheduler.setEnabled(false);
        assertThat(scheduler.isEnabled()).isFalse();
        List<MtfTrendStatus> result = scheduler.runScheduledScan();
        assertThat(result).isEmpty();
    }

    @Test
    void testMarketHoursBoundaryCheck() {
        // Market open is 09:15, close is 15:30:59
        assertThat(scheduler.isWithinMarketHours(java.time.LocalTime.of(9, 14, 59))).isFalse();
        assertThat(scheduler.isWithinMarketHours(java.time.LocalTime.of(9, 15, 0))).isTrue();
        assertThat(scheduler.isWithinMarketHours(java.time.LocalTime.of(10, 15, 0))).isTrue();
        assertThat(scheduler.isWithinMarketHours(java.time.LocalTime.of(15, 15, 0))).isTrue();
        assertThat(scheduler.isWithinMarketHours(java.time.LocalTime.of(15, 30, 0))).isTrue();
        assertThat(scheduler.isWithinMarketHours(java.time.LocalTime.of(15, 30, 59))).isTrue();
        assertThat(scheduler.isWithinMarketHours(java.time.LocalTime.of(15, 31, 0))).isFalse();
        assertThat(scheduler.isWithinMarketHours(java.time.LocalTime.of(18, 0, 0))).isFalse();
    }

    @Test
    void testCronExpressionFiresHourlyOnTradingDays() {
        org.springframework.scheduling.support.CronExpression cron =
                org.springframework.scheduling.support.CronExpression.parse(
                        "0 15 10-15 ? * MON-FRI");
        java.time.ZoneId ist = java.time.ZoneId.of("Asia/Kolkata");

        // Monday 2026-09-07 09:00 IST (pre-market)
        java.time.ZonedDateTime mondayMorning =
                java.time.ZonedDateTime.of(2026, 9, 7, 9, 0, 0, 0, ist);

        java.time.ZonedDateTime t1 = cron.next(mondayMorning);
        assertThat(t1.getHour()).isEqualTo(10);
        assertThat(t1.getMinute()).isEqualTo(15);

        java.time.ZonedDateTime t2 = cron.next(t1);
        assertThat(t2.getHour()).isEqualTo(11);
        assertThat(t2.getMinute()).isEqualTo(15);

        java.time.ZonedDateTime t3 = cron.next(t2);
        assertThat(t3.getHour()).isEqualTo(12);

        java.time.ZonedDateTime t4 = cron.next(t3);
        assertThat(t4.getHour()).isEqualTo(13);

        java.time.ZonedDateTime t5 = cron.next(t4);
        assertThat(t5.getHour()).isEqualTo(14);

        java.time.ZonedDateTime t6 = cron.next(t5);
        assertThat(t6.getHour()).isEqualTo(15);
        assertThat(t6.getMinute()).isEqualTo(15);

        // Next from Monday 15:15 IST should jump to Tuesday 10:15 IST
        java.time.ZonedDateTime tuesday1 = cron.next(t6);
        assertThat(tuesday1.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.TUESDAY);
        assertThat(tuesday1.getHour()).isEqualTo(10);
        assertThat(tuesday1.getMinute()).isEqualTo(15);

        // Friday afternoon to Monday (skipping Saturday and Sunday)
        java.time.ZonedDateTime fridayAfternoon =
                java.time.ZonedDateTime.of(2026, 9, 11, 15, 15, 0, 0, ist);
        java.time.ZonedDateTime nextAfterFriday = cron.next(fridayAfternoon);
        assertThat(nextAfterFriday.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
        assertThat(nextAfterFriday.getHour()).isEqualTo(10);
        assertThat(nextAfterFriday.getMinute()).isEqualTo(15);
    }
}
