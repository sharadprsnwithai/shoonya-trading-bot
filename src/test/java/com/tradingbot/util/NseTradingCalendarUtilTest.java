package com.tradingbot.util;

import static org.junit.jupiter.api.Assertions.*;

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
}
