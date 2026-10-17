package com.tradingbot.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Set;

/**
 * Utility for Indian National Stock Exchange (NSE) trading calendar, holiday detection, monthly
 * derivatives expiry calculation, and first trading day resolution.
 */
public final class NseTradingCalendarUtil {

    public static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    /**
     * Standard NSE Trading Holidays (Configurable / Extensible). Includes standard historical and
     * projected 2025/2026 NSE capital & F&O market holidays.
     */
    private static final Set<LocalDate> NSE_HOLIDAYS =
            Set.of(
                    // 2025 Holidays
                    LocalDate.of(2025, 1, 26), // Republic Day
                    LocalDate.of(2025, 2, 26), // Maha Shivratri
                    LocalDate.of(2025, 3, 14), // Holi
                    LocalDate.of(2025, 3, 31), // Id-Ul-Fitr
                    LocalDate.of(2025, 4, 10), // Mahavir Jayanti
                    LocalDate.of(2025, 4, 14), // Dr. Ambedkar Jayanti
                    LocalDate.of(2025, 4, 18), // Good Friday
                    LocalDate.of(2025, 5, 1), // Maharashtra Day
                    LocalDate.of(2025, 8, 15), // Independence Day
                    LocalDate.of(2025, 8, 27), // Ganesh Chaturthi
                    LocalDate.of(2025, 10, 2), // Mahatma Gandhi Jayanti
                    LocalDate.of(2025, 10, 21), // Dussehra
                    LocalDate.of(2025, 11, 1), // Diwali Laxmi Pujan
                    LocalDate.of(2025, 11, 5), // Gurunanak Jayanti
                    LocalDate.of(2025, 12, 25), // Christmas
                    // 2026 Holidays
                    LocalDate.of(2026, 1, 26), // Republic Day
                    LocalDate.of(2026, 2, 16), // Maha Shivratri
                    LocalDate.of(2026, 3, 3), // Holi
                    LocalDate.of(2026, 3, 20), // Id-Ul-Fitr
                    LocalDate.of(2026, 3, 31), // Mahavir Jayanti
                    LocalDate.of(2026, 4, 3), // Good Friday
                    LocalDate.of(2026, 4, 14), // Dr. Ambedkar Jayanti
                    LocalDate.of(2026, 5, 1), // Maharashtra Day
                    LocalDate.of(2026, 8, 15), // Independence Day
                    LocalDate.of(2026, 9, 14), // Ganesh Chaturthi
                    LocalDate.of(2026, 10, 2), // Mahatma Gandhi Jayanti
                    LocalDate.of(2026, 10, 20), // Dussehra
                    LocalDate.of(2026, 11, 8), // Diwali Laxmi Pujan
                    LocalDate.of(2026, 11, 24), // Gurunanak Jayanti
                    LocalDate.of(2026, 12, 25) // Christmas
                    );

    private NseTradingCalendarUtil() {
        // Utility class
    }

    /**
     * Determines whether the specified date is an active NSE trading day (skips Saturdays, Sundays,
     * and official exchange holidays).
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
     * @param year the calendar year
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
     * Calculates the monthly Nifty options expiry date (Last Thursday of the month, the
     * pre-September-2025 convention). If the last Thursday is an exchange holiday, rolls backward
     * to the preceding trading day (Wednesday).
     *
     * <p>Prefer {@link #getMonthlyExpiryTuesday(int, int)} for current NSE equity derivatives:
     * since contracts expiring on/after 01-Sep-2025 the NSE monthly expiry is the last
     * <b>Tuesday</b> (NSE circular NSE/FAOP/68747).
     *
     * @param year the calendar year
     * @param month the calendar month (1-12)
     * @return the monthly expiry date
     */
    public static LocalDate getMonthlyExpiryThursday(int year, int month) {
        return getMonthlyExpiry(year, month, DayOfWeek.THURSDAY);
    }

    /**
     * Calculates the monthly stock options expiry date (Last Tuesday of the month). If the last
     * Tuesday is an exchange holiday, rolls backward to the preceding trading day.
     *
     * @param year the calendar year
     * @param month the calendar month (1-12)
     * @return the monthly Tuesday expiry date
     */
    public static LocalDate getMonthlyExpiryTuesday(int year, int month) {
        return getMonthlyExpiry(year, month, DayOfWeek.TUESDAY);
    }

    /**
     * Calculates the monthly derivatives expiry on the supplied contractual weekday, rolling
     * backward over exchange holidays and weekends to the preceding trading day.
     *
     * <p>NSE rule: when the expiry day falls on a trading holiday, expiry is the previous trading
     * day. Weekday conventions: <b>Tuesday</b> for NSE equity derivatives since September 2025
     * (NSE/FAOP/68747), <b>Thursday</b> for BSE Sensex.
     *
     * @param year the calendar year
     * @param month the calendar month (1-12)
     * @param expiryDay the contractual expiry weekday (must be a weekday, not Sat/Sun)
     * @return the monthly expiry date
     */
    public static LocalDate getMonthlyExpiry(int year, int month, DayOfWeek expiryDay) {
        if (expiryDay == null) {
            expiryDay = DayOfWeek.TUESDAY;
        }
        LocalDate lastDayOfMonth = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
        LocalDate current = lastDayOfMonth;

        // Find the last occurrence of the contractual expiry weekday in the month
        while (current.getDayOfWeek() != expiryDay) {
            current = current.minusDays(1);
        }

        // If that day is a trading holiday (or weekend), roll backward to a trading day
        while (!isTradingDay(current)) {
            current = current.minusDays(1);
        }
        return current;
    }

    /**
     * Calculates the weekly derivatives expiry on the supplied contractual weekday: the next such
     * weekday on or after {@code referenceDate}, rolled backward over holidays to the preceding
     * trading day. If that rollback would place expiry before {@code referenceDate}, steps to the
     * following week.
     *
     * @param referenceDate the date from which to resolve (typically "today")
     * @param expiryDay the contractual expiry weekday (Tuesday for NSE, Thursday for BSE Sensex)
     * @return the weekly expiry date, never before {@code referenceDate}
     */
    public static LocalDate getWeeklyExpiry(LocalDate referenceDate, DayOfWeek expiryDay) {
        if (referenceDate == null) {
            return null;
        }
        if (expiryDay == null) {
            expiryDay = DayOfWeek.TUESDAY;
        }
        LocalDate candidate = referenceDate.with(TemporalAdjusters.nextOrSame(expiryDay));
        while (!isTradingDay(candidate)) {
            candidate = candidate.minusDays(1);
        }
        // A holiday rollback can move expiry before the reference date; step to the next week.
        if (candidate.isBefore(referenceDate)) {
            return getWeeklyExpiry(referenceDate.plusWeeks(1), expiryDay);
        }
        return candidate;
    }

    /**
     * Checks whether the given date is the monthly NSE equity-derivatives expiry session (last
     * Tuesday of its month, holiday-rolled backward to the preceding trading day).
     *
     * @param date the date to evaluate
     * @return true when the date is that month's monthly expiry session
     */
    public static boolean isMonthlyExpiryDay(LocalDate date) {
        if (date == null || !isTradingDay(date)) {
            return false;
        }
        return date.equals(getMonthlyExpiryTuesday(date.getYear(), date.getMonthValue()));
    }

    /**
     * Counts the exchange trading sessions strictly after {@code from} up to and including {@code
     * through} — the days-to-expiry (DTE) a session taken on {@code from} has in a series expiring
     * on {@code through}. The expiry session itself therefore reads {@code 0}, the preceding
     * trading session {@code 1}, and so on. Weekends and holidays are skipped, so a holiday-rolled
     * expiry (e.g. a Tuesday that moves to Monday) needs no special case. Returns {@code 0} when
     * {@code through} is null or falls on/before {@code from}.
     *
     * @param from the session the DTE is measured on (exclusive)
     * @param through the series expiry date (inclusive)
     * @return trading sessions remaining in the series
     */
    public static int tradingSessionsRemaining(LocalDate from, LocalDate through) {
        if (from == null || through == null || !through.isAfter(from)) {
            return 0;
        }
        int sessions = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(through); d = d.plusDays(1)) {
            if (isTradingDay(d)) {
                sessions++;
            }
        }
        return sessions;
    }

    /**
     * Finds the first active trading day strictly after the specified date.
     *
     * @param date the reference date
     * @return the next active trading session date
     */
    public static LocalDate getFirstTradingDayAfter(LocalDate date) {
        if (date == null) {
            return null;
        }
        LocalDate next = date.plusDays(1);
        while (!isTradingDay(next)) {
            next = next.plusDays(1);
        }
        return next;
    }

    /**
     * Checks if today in IST timezone is the first active trading day immediately following the
     * monthly Tuesday stock options expiry.
     *
     * @return true if today is the post-expiry trading session
     */
    public static boolean isTodayFirstTradingDayPostMonthlyTuesdayExpiry() {
        return isFirstTradingDayPostMonthlyTuesdayExpiry(LocalDate.now(IST_ZONE));
    }

    /**
     * Checks if the specified date is the first active trading day immediately following a monthly
     * Tuesday stock options expiry (handles month-end rollovers, e.g. June 30 -> July 1).
     *
     * @param date the date to check
     * @return true if date is the post-expiry trading session
     */
    public static boolean isFirstTradingDayPostMonthlyTuesdayExpiry(LocalDate date) {
        if (date == null || !isTradingDay(date)) {
            return false;
        }

        // 1. Check current month's expiry
        LocalDate curExpiry = getMonthlyExpiryTuesday(date.getYear(), date.getMonthValue());
        LocalDate postCur = getFirstTradingDayAfter(curExpiry);
        if (date.equals(postCur)) {
            return true;
        }

        // 2. Check previous month's expiry (for month rollovers into 1st/2nd of next month)
        LocalDate prevMonthDate = date.minusMonths(1);
        LocalDate prevExpiry =
                getMonthlyExpiryTuesday(prevMonthDate.getYear(), prevMonthDate.getMonthValue());
        LocalDate postPrev = getFirstTradingDayAfter(prevExpiry);
        return date.equals(postPrev);
    }

    /**
     * Calculates the last Wednesday of the month. If the last Wednesday is an exchange holiday,
     * rolls backward to the preceding trading day so scheduled routines are never skipped.
     *
     * @param year the calendar year
     * @param month the calendar month (1-12)
     * @return the last active post-expiry trading day
     */
    public static LocalDate getLastWednesdayOfMonth(int year, int month) {
        LocalDate lastDayOfMonth = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1);
        LocalDate current = lastDayOfMonth;

        while (current.getDayOfWeek() != DayOfWeek.WEDNESDAY) {
            current = current.minusDays(1);
        }

        while (!isTradingDay(current)) {
            current = current.minusDays(1);
        }
        return current;
    }

    /**
     * Checks if today in IST timezone is the last Wednesday of the current calendar month.
     *
     * @return true if today is the last Wednesday of this month
     */
    public static boolean isTodayLastWednesdayOfMonth() {
        return isLastWednesdayOfMonth(LocalDate.now(IST_ZONE));
    }

    /**
     * Checks if the specified date is the active last Wednesday (or holiday-rolled session) of its
     * calendar month.
     *
     * @param date the date to check
     * @return true if date is the last Wednesday session of the month
     */
    public static boolean isLastWednesdayOfMonth(LocalDate date) {
        if (date == null || !isTradingDay(date)) {
            return false;
        }
        LocalDate lastWed = getLastWednesdayOfMonth(date.getYear(), date.getMonthValue());
        return date.equals(lastWed);
    }
}
