package com.tradingbot.strategy.car;

import com.tradingbot.model.Candle;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Week-boundary helpers for the CAR weekly routine.
 *
 * <p>The routine may legitimately run on any day (Sunday scheduler, Sunday startup, weekday
 * REST/Telegram trigger), so "the active week" and "the last completed week" must be derived from
 * the calendar rather than assumed to be a Sunday.
 */
public final class CarWeekCalendar {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private CarWeekCalendar() {}

    /** Returns the Monday of the week the strategy is currently trading. */
    public static LocalDate activeWeekStart(LocalDate today) {
        if (today == null) {
            return null;
        }
        DayOfWeek dow = today.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            // On a weekend the routine arms triggers for the week that is about to start.
            return today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        }
        // Monday-Friday the strategy is trading the week that is already running.
        return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** Shorthand for {@link #activeWeekStart(LocalDate)} using the current IST date. */
    public static LocalDate activeWeekStart() {
        return activeWeekStart(LocalDate.now(IST));
    }

    /**
     * Returns the last {@code sessions} candles belonging to the week that finished immediately
     * before {@code weekStart}, i.e. the calendar week the trigger price is derived from.
     *
     * <p>Unlike a naive "last 5 candles" slice this stays correct on short (4-session) holiday
     * weeks and on weekday runs, where the trailing candles would otherwise belong to the week that
     * is still in progress.
     *
     * @param dailyCandles chronological daily candles
     * @param weekStart Monday of the active week
     * @return up to the last 5 sessions before {@code weekStart}, never null
     */
    public static List<Candle> previousCompletedWeek(
            List<Candle> dailyCandles, LocalDate weekStart) {
        if (dailyCandles == null || dailyCandles.isEmpty()) {
            return List.of();
        }
        Instant boundary =
                weekStart != null
                        ? weekStart.atStartOfDay(IST).toInstant()
                        : Instant.ofEpochMilli(Long.MAX_VALUE);

        List<Candle> before = new ArrayList<>();
        for (Candle c : dailyCandles) {
            if (c != null && c.timestamp() != null && !c.timestamp().isAfter(boundary)) {
                before.add(c);
            }
        }
        if (before.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, before.size() - 5);
        return List.copyOf(before.subList(start, before.size()));
    }

    /**
     * Returns true when {@code candle} falls inside the calendar week starting at {@code
     * weekStart}.
     */
    public static boolean isWithinWeek(Candle candle, LocalDate weekStart) {
        if (candle == null || candle.timestamp() == null || weekStart == null) {
            return false;
        }
        Instant start = weekStart.atStartOfDay(IST).toInstant();
        Instant end = weekStart.plusDays(7).atStartOfDay(IST).toInstant();
        return !candle.timestamp().isBefore(start) && candle.timestamp().isBefore(end);
    }
}
