package com.tradingbot.strategy.car;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CarWeekCalendarTest {

    @Test
    void testSundayArmsTheWeekThatIsAboutToStart() {
        // A Sunday run must target the Monday that follows, not the Monday that just finished.
        assertEquals(
                LocalDate.of(2026, 10, 5),
                CarWeekCalendar.activeWeekStart(LocalDate.of(2026, 10, 4)));
    }

    @Test
    void testSaturdayAlsoLooksForward() {
        assertEquals(
                LocalDate.of(2026, 10, 5),
                CarWeekCalendar.activeWeekStart(LocalDate.of(2026, 10, 3)));
    }

    @Test
    void testMondayTargetsItself() {
        assertEquals(
                LocalDate.of(2026, 10, 5),
                CarWeekCalendar.activeWeekStart(LocalDate.of(2026, 10, 5)));
    }

    @Test
    void testWeekdayRunTargetsTheWeekAlreadyInProgress() {
        // A manual REST trigger on a Tuesday must use this Monday, not last week's Monday.
        assertEquals(
                LocalDate.of(2026, 9, 28),
                CarWeekCalendar.activeWeekStart(LocalDate.of(2026, 9, 29)));
        assertEquals(
                LocalDate.of(2026, 9, 28),
                CarWeekCalendar.activeWeekStart(LocalDate.of(2026, 10, 2)));
    }

    @Test
    void testPreviousCompletedWeekTakesTheFiveSessionsBeforeTheBoundary() {
        List<Candle> daily = sessionCandles("RELIANCE", LocalDate.of(2026, 9, 14), 15);

        List<Candle> week = CarWeekCalendar.previousCompletedWeek(daily, LocalDate.of(2026, 10, 5));

        assertEquals(5, week.size());
        assertEquals(LocalDate.of(2026, 9, 28), dateOf(week.get(0)));
        assertEquals(LocalDate.of(2026, 10, 2), dateOf(week.get(week.size() - 1)));
    }

    @Test
    void testWeekdayRunExcludesCandlesFromTheWeekStillInProgress() {
        // Run on Tuesday 29 Sep: the 29 Sep candle belongs to the running week and must not leak
        // into the trigger calculation for the week that has not started yet.
        List<Candle> daily = sessionCandles("RELIANCE", LocalDate.of(2026, 9, 14), 10);
        // Append a candle dated in the current (running) week.
        List<Candle> withInprogress = new ArrayList<>(daily);
        withInprogress.add(
                Candle.ofDaily(
                        "RELIANCE",
                        LocalDate.of(2026, 9, 29)
                                .atStartOfDay(java.time.ZoneId.of("Asia/Kolkata"))
                                .toInstant(),
                        new BigDecimal("100"),
                        new BigDecimal("100"),
                        new BigDecimal("100"),
                        new BigDecimal("100"),
                        1000));

        List<Candle> week =
                CarWeekCalendar.previousCompletedWeek(withInprogress, LocalDate.of(2026, 9, 28));

        assertEquals(5, week.size());
        assertEquals(LocalDate.of(2026, 9, 25), dateOf(week.get(week.size() - 1)));
    }

    @Test
    void testShortHolidayWeekReturnsAllAvailableSessions() {
        // Four sessions only (Mon-Thu; Friday was a market holiday).
        List<Candle> daily = sessionCandles("RELIANCE", LocalDate.of(2026, 9, 28), 4);

        List<Candle> week = CarWeekCalendar.previousCompletedWeek(daily, LocalDate.of(2026, 10, 5));

        assertEquals(4, week.size());
        assertEquals(LocalDate.of(2026, 9, 28), dateOf(week.get(0)));
        assertEquals(LocalDate.of(2026, 10, 1), dateOf(week.get(week.size() - 1)));
    }

    @Test
    void testEmptyInputIsSafe() {
        assertTrue(
                CarWeekCalendar.previousCompletedWeek(null, LocalDate.of(2026, 10, 5)).isEmpty());
        assertTrue(
                CarWeekCalendar.previousCompletedWeek(List.of(), LocalDate.of(2026, 10, 5))
                        .isEmpty());
    }

    private static LocalDate dateOf(Candle candle) {
        return LocalDate.ofInstant(candle.timestamp(), java.time.ZoneId.of("Asia/Kolkata"));
    }

    /** Builds {@code count} consecutive weekday sessions starting at {@code start}. */
    private static List<Candle> sessionCandles(String symbol, LocalDate start, int count) {
        List<Candle> candles = new ArrayList<>();
        LocalDate d = start;
        int added = 0;
        while (added < count) {
            if (d.getDayOfWeek().getValue() <= 5) {
                BigDecimal px = new BigDecimal("100").add(BigDecimal.valueOf(added));
                candles.add(
                        Candle.ofDaily(
                                symbol,
                                d.atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant(),
                                px,
                                px.add(BigDecimal.ONE),
                                px.subtract(BigDecimal.ONE),
                                px,
                                1000));
                added++;
            }
            d = d.plus(1, ChronoUnit.DAYS);
        }
        return candles;
    }
}
