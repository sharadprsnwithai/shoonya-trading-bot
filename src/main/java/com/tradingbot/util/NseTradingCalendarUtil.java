package com.tradingbot.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

/**
 * Utility for Indian National Stock Exchange (NSE) trading calendar,
 * holiday detection, monthly derivatives expiry calculation, and first trading day resolution.
 */
public final class NseTradingCalendarUtil {

    public static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    /**
     * Standard NSE Trading Holidays (Configurable / Extensible).
     * Includes standard historical and projected 2025/2026 NSE capital & F&O market holidays.
     */
    private static final Set<LocalDate> NSE_HOLIDAYS = Set.of(
            // 2025 Holidays
            LocalDate.of(2025, 1, 26),  // Republic Day
            LocalDate.of(2025, 2, 26),  // Maha Shivratri
            LocalDate.of(2025, 3, 14),  // Holi
            LocalDate.of(2025, 3, 31),  // Id-Ul-Fitr
            LocalDate.of(2025, 4, 10),  // Mahavir Jayanti
            LocalDate.of(2025, 4, 14),  // Dr. Ambedkar Jayanti
            LocalDate.of(2025, 4, 18),  // Good Friday
            LocalDate.of(2025, 5, 1),   // Maharashtra Day
            LocalDate.of(2025, 8, 15),  // Independence Day
            LocalDate.of(2025, 8, 27),  // Ganesh Chaturthi
            LocalDate.of(2025, 10, 2),  // Mahatma Gandhi Jayanti
            LocalDate.of(2025, 10, 21), // Dussehra
            LocalDate.of(2025, 11, 1),  // Diwali Laxmi Pujan
            LocalDate.of(2025, 11, 5),  // Gurunanak Jayanti
            LocalDate.of(2025, 12, 25), // Christmas
            // 2026 Holidays
            LocalDate.of(2026, 1, 26),  // Republic Day
            LocalDate.of(2026, 2, 16),  // Maha Shivratri
            LocalDate.of(2026, 3, 3),   // Holi
            LocalDate.of(2026, 3, 20),  // Id-Ul-Fitr
            LocalDate.of(2026, 3, 31),  // Mahavir Jayanti
            LocalDate.of(2026, 4, 3),   // Good Friday
            LocalDate.of(2026, 4, 14),  // Dr. Ambedkar Jayanti
            LocalDate.of(2026, 5, 1),   // Maharashtra Day
            LocalDate.of(2026, 8, 15),  // Independence Day
            LocalDate.of(2026, 9, 14),  // Ganesh Chaturthi
            LocalDate.of(2026, 10, 2),  // Mahatma Gandhi Jayanti
            LocalDate.of(2026, 10, 20), // Dussehra
            LocalDate.of(2026, 11, 8),  // Diwali Laxmi Pujan
            LocalDate.of(2026, 11, 24), // Gurunanak Jayanti
            LocalDate.of(2026, 12, 25)  // Christmas
    );

    private NseTradingCalendarUtil() {
        // Utility class
    }

    /**
     * Determines whether the specified date is an active NSE trading day
     * (skips Saturdays, Sundays, and official exchange holidays).
     *
     * @param date the date to check
     * @return true if the date is an active trading session, false otherwise
     */
    public static boolean isTradingDay(LocalDate date) {
        if (date == null) {
            return false;
        }
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        return !NSE_HOLIDAYS.contains(date);
    }

    /**
     * Finds the first active trading day of the given year and month.
     *
     * @param year  the calendar year
     * @param month the calendar month (1-12)
     * @return the first active trading date
     */
    public static LocalDate getFirstTradingDayOfMonth(int year, int month) {
        LocalDate current = LocalDate.of(year, month, 1);
        while (!isTradingDay(current)) {
            current = current.plusDays(1);
        }
        return current;
    }

    /**
     * Checks if today in IST timezone is the first active trading day of the current month.
     *
     * @return true if today is the first trading session of this month
     */
    public static boolean isTodayFirstTradingDayOfMonth() {
        return isTodayFirstTradingDayOfMonth(LocalDate.now(IST_ZONE));
    }

    /**
     * Checks if the given date is the first active trading day of its month.
     *
     * @param date the date to evaluate
     * @return true if date is the first trading session of its month
     */
    public static boolean isTodayFirstTradingDayOfMonth(LocalDate date) {
        if (date == null) {
            return false;
        }
        LocalDate firstTradingDay = getFirstTradingDayOfMonth(date.getYear(), date.getMonthValue());
        return date.equals(firstTradingDay);
    }

    /**
     * Calculates the monthly Nifty options expiry date (Last Thursday of the month).
     * If the last Thursday is an exchange holiday, rolls backward to the preceding trading day (Wednesday).
     *
     * @param year  the calendar year
     * @param month the calendar month (1-12)
     * @return the monthly expiry date
     */
    public static LocalDate getMonthlyExpiryThursday(int year, int month) {
        LocalDate lastDayOfMonth = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
        LocalDate current = lastDayOfMonth;
        
        // Find last Thursday of the month
        while (current.getDayOfWeek() != DayOfWeek.THURSDAY) {
            current = current.minusDays(1);
        }

        // If that Thursday is a trading holiday, roll backward to the preceding trading day
        while (!isTradingDay(current)) {
            current = current.minusDays(1);
        }
        return current;
    }
}
