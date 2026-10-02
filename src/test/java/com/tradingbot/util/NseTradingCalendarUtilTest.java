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
}
