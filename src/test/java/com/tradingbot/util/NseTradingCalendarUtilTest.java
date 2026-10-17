package com.tradingbot.util;

import static org.junit.jupiter.api.Assertions.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NseTradingCalendarUtilTest {

    @Test
    @DisplayName("Weekend detection: Saturday and Sunday should not be trading days")
    void testWeekendDetection() {
        LocalDate saturday = LocalDate.of(2026, 10, 3);
        LocalDate sunday = LocalDate.of(2026, 10, 4);
        LocalDate monday = LocalDate.of(2026, 10, 5);

        assertFalse(
                NseTradingCalendarUtil.isTradingDay(saturday),
                "Saturday should not be trading day");
        assertFalse(
                NseTradingCalendarUtil.isTradingDay(sunday), "Sunday should not be trading day");
        assertTrue(NseTradingCalendarUtil.isTradingDay(monday), "Monday should be trading day");
    }

    @Test
    @DisplayName(
            "NSE Holiday detection: Gandhi Jayanti (Oct 2) and May 1st should be non-trading days")
    void testHolidayDetection() {
        LocalDate gandhiJayanti = LocalDate.of(2026, 10, 2);
        LocalDate maharashtraDay = LocalDate.of(2026, 5, 1);

        assertFalse(
                NseTradingCalendarUtil.isTradingDay(gandhiJayanti),
                "Oct 2 (Gandhi Jayanti) should be holiday");
        assertFalse(
                NseTradingCalendarUtil.isTradingDay(maharashtraDay),
                "May 1 (Maharashtra Day) should be holiday");
    }

    @Test
    @DisplayName(
            "First trading day of month calculation for May 2026 (May 1 is holiday, 2-3 weekend -> May 4)")
    void testFirstTradingDayOfMonthMay2026() {
        LocalDate firstTradingDayMay = NseTradingCalendarUtil.getFirstTradingDayOfMonth(2026, 5);
        assertEquals(LocalDate.of(2026, 5, 4), firstTradingDayMay);
    }

    @Test
    @DisplayName("First trading day of month calculation for October 2026 (Oct 1 is trading day)")
    void testFirstTradingDayOfMonthOct2026() {
        LocalDate firstTradingDayOct = NseTradingCalendarUtil.getFirstTradingDayOfMonth(2026, 10);
        assertEquals(LocalDate.of(2026, 10, 1), firstTradingDayOct);
    }

    @Test
    @DisplayName("Monthly expiry Thursday calculation for October 2026 (Last Thursday is Oct 29)")
    void testMonthlyExpiryThursdayOct2026() {
        LocalDate monthlyExpiryOct = NseTradingCalendarUtil.getMonthlyExpiryThursday(2026, 10);
        assertEquals(LocalDate.of(2026, 10, 29), monthlyExpiryOct);
    }

    @Test
    @DisplayName("Monthly expiry Tuesday calculation for October 2026 (Last Tuesday is Oct 27)")
    void testMonthlyExpiryTuesdayOct2026() {
        LocalDate monthlyExpiryTue = NseTradingCalendarUtil.getMonthlyExpiryTuesday(2026, 10);
        assertEquals(LocalDate.of(2026, 10, 27), monthlyExpiryTue);
    }

    @Test
    @DisplayName("Last Wednesday of month calculation for October 2026 (Oct 28)")
    void testLastWednesdayOfMonthOct2026() {
        LocalDate lastWed = NseTradingCalendarUtil.getLastWednesdayOfMonth(2026, 10);
        assertEquals(LocalDate.of(2026, 10, 28), lastWed);
        assertTrue(NseTradingCalendarUtil.isLastWednesdayOfMonth(LocalDate.of(2026, 10, 28)));
        assertFalse(NseTradingCalendarUtil.isLastWednesdayOfMonth(LocalDate.of(2026, 10, 21)));
    }

    @Test
    @DisplayName("Exact post-expiry session detection across month boundaries and normal months")
    void testPostExpirySessionDetection() {
        // Normal month: October 2026 (Expiry is Tuesday Oct 27 -> Post-expiry is Wednesday Oct 28)
        assertTrue(
                NseTradingCalendarUtil.isFirstTradingDayPostMonthlyTuesdayExpiry(
                        LocalDate.of(2026, 10, 28)));
        assertFalse(
                NseTradingCalendarUtil.isFirstTradingDayPostMonthlyTuesdayExpiry(
                        LocalDate.of(2026, 10, 27)));
        assertFalse(
                NseTradingCalendarUtil.isFirstTradingDayPostMonthlyTuesdayExpiry(
                        LocalDate.of(2026, 10, 29)));

        // Month ending on Tuesday: June 30, 2026 is Tuesday expiry -> Post-expiry is July 1, 2026
        assertTrue(
                NseTradingCalendarUtil.isFirstTradingDayPostMonthlyTuesdayExpiry(
                        LocalDate.of(2026, 7, 1)));
        assertFalse(
                NseTradingCalendarUtil.isFirstTradingDayPostMonthlyTuesdayExpiry(
                        LocalDate.of(2026, 6, 24))); // Premature Wednesday must be false

        // Month ending on Tuesday: March 31, 2026 is Tuesday expiry -> Post-expiry is April 1, 2026
        assertTrue(
                NseTradingCalendarUtil.isFirstTradingDayPostMonthlyTuesdayExpiry(
                        LocalDate.of(2026, 4, 1)));
        assertFalse(
                NseTradingCalendarUtil.isFirstTradingDayPostMonthlyTuesdayExpiry(
                        LocalDate.of(2026, 3, 25))); // Premature Wednesday must be false
    }

    @Test
    @DisplayName("Monthly expiry on a custom weekday honours the holiday rollback")
    void testMonthlyExpiryGenericWeekday() {
        assertEquals(
                LocalDate.of(2026, 10, 27),
                NseTradingCalendarUtil.getMonthlyExpiry(2026, 10, DayOfWeek.TUESDAY));
        assertEquals(
                LocalDate.of(2026, 10, 29),
                NseTradingCalendarUtil.getMonthlyExpiry(2026, 10, DayOfWeek.THURSDAY));
        // Nov 2026's last Tuesday (Nov 24) is Gurunanak Jayanti -> rolls back to Mon Nov 23
        assertEquals(
                LocalDate.of(2026, 11, 23),
                NseTradingCalendarUtil.getMonthlyExpiry(2026, 11, DayOfWeek.TUESDAY));
    }

    @Test
    @DisplayName("Weekly expiry resolves on or after the reference date, rolling over holidays")
    void testWeeklyExpiryResolution() {
        // Plain case: Monday Oct 5, 2026 -> Tuesday Oct 6
        assertEquals(
                LocalDate.of(2026, 10, 6),
                NseTradingCalendarUtil.getWeeklyExpiry(
                        LocalDate.of(2026, 10, 5), DayOfWeek.TUESDAY));
        // Holiday Tuesday (Nov 24, 2026) rolls back to Monday Nov 23
        assertEquals(
                LocalDate.of(2026, 11, 23),
                NseTradingCalendarUtil.getWeeklyExpiry(
                        LocalDate.of(2026, 11, 20), DayOfWeek.TUESDAY));
        // Reference date IS the holiday: rollback would precede it, so step to next week
        assertEquals(
                LocalDate.of(2026, 12, 1),
                NseTradingCalendarUtil.getWeeklyExpiry(
                        LocalDate.of(2026, 11, 24), DayOfWeek.TUESDAY));
    }

    @Test
    @DisplayName("Monthly expiry day detection is true only on the (holiday-rolled) expiry session")
    void testIsMonthlyExpiryDay() {
        assertTrue(NseTradingCalendarUtil.isMonthlyExpiryDay(LocalDate.of(2026, 10, 27)));
        assertFalse(NseTradingCalendarUtil.isMonthlyExpiryDay(LocalDate.of(2026, 10, 28)));
        assertFalse(NseTradingCalendarUtil.isMonthlyExpiryDay(LocalDate.of(2026, 10, 29)));
        // Holiday-rolled expiry is still an expiry session
        assertTrue(NseTradingCalendarUtil.isMonthlyExpiryDay(LocalDate.of(2026, 11, 23)));
        // The contractual weekday that fell on a holiday is not
        assertFalse(NseTradingCalendarUtil.isMonthlyExpiryDay(LocalDate.of(2026, 11, 24)));
        // Non-trading days are never expiry sessions
        assertFalse(NseTradingCalendarUtil.isMonthlyExpiryDay(LocalDate.of(2026, 10, 4)));
        assertFalse(NseTradingCalendarUtil.isMonthlyExpiryDay(null));
    }

    @Test
    @DisplayName("Trading sessions remaining counts exchange sessions only, expiry day = 0")
    void testTradingSessionsRemaining() {
        // Oct 2026 monthly expiry is Tue Oct 27 (last Tuesday, no holiday collision).
        LocalDate expiry = LocalDate.of(2026, 10, 27);
        assertEquals(0, NseTradingCalendarUtil.tradingSessionsRemaining(expiry, expiry));
        assertEquals(
                1,
                NseTradingCalendarUtil.tradingSessionsRemaining(
                        LocalDate.of(2026, 10, 26), expiry));
        assertEquals(
                2,
                NseTradingCalendarUtil.tradingSessionsRemaining(
                        LocalDate.of(2026, 10, 23), expiry));
        // A non-trading reference date is allowed and contributes no session of its own.
        assertEquals(
                2,
                NseTradingCalendarUtil.tradingSessionsRemaining(
                        LocalDate.of(2026, 10, 24), expiry));
        // Weekend and the Dussehra holiday (Tue Oct 20, 2026) are both skipped: Oct 13-27 minus
        // Sat/Sun 17-18 and 24-25, minus the Oct 20 holiday = 10 sessions.
        assertEquals(
                10,
                NseTradingCalendarUtil.tradingSessionsRemaining(
                        LocalDate.of(2026, 10, 12), expiry));
        // Past or equal to expiry reads 0, never negative.
        assertEquals(
                0,
                NseTradingCalendarUtil.tradingSessionsRemaining(
                        LocalDate.of(2026, 10, 28), expiry));
        assertEquals(
                0,
                NseTradingCalendarUtil.tradingSessionsRemaining(
                        expiry, LocalDate.of(2026, 10, 20)));
        assertEquals(0, NseTradingCalendarUtil.tradingSessionsRemaining(expiry, null));
    }

    @Test
    @DisplayName("Trading sessions remaining follows a holiday-rolled (Monday) expiry")
    void testTradingSessionsRemainingWithRolledExpiry() {
        // Nov 2026: the last Tuesday (Nov 24) is Gurunanak Jayanti, so expiry rolls to Mon Nov 23.
        LocalDate rolledExpiry = LocalDate.of(2026, 11, 23);
        assertEquals(
                1,
                NseTradingCalendarUtil.tradingSessionsRemaining(
                        LocalDate.of(2026, 11, 20), rolledExpiry));
        assertEquals(
                2,
                NseTradingCalendarUtil.tradingSessionsRemaining(
                        LocalDate.of(2026, 11, 19), rolledExpiry));
        // Nov 24 (the contractual Tuesday, a holiday) is already past expiry.
        assertEquals(
                0,
                NseTradingCalendarUtil.tradingSessionsRemaining(
                        LocalDate.of(2026, 11, 24), rolledExpiry));
    }
}
