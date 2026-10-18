package com.tradingbot.strategy.fiftytwohigh;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.fiftytwohigh.FiftyTwoHighCalculator.Outcome;
import com.tradingbot.strategy.fiftytwohigh.FiftyTwoHighCalculator.Status;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FiftyTwoHighCalculatorTest {

    // 04:00Z == 09:30 IST, so the IST calendar date matches the day offset.
    private static final Instant BASE = Instant.parse("2025-01-01T04:00:00Z");

    private static Candle bar(int day, String o, String h, String l, String c) {
        return Candle.ofDaily(
                "TEST",
                BASE.plus(day, ChronoUnit.DAYS),
                new BigDecimal(o),
                new BigDecimal(h),
                new BigDecimal(l),
                new BigDecimal(c),
                10_000);
    }

    private static FiftyTwoHighCalculator calc(int minPrior, double minPct, boolean requireClose) {
        return new FiftyTwoHighCalculator(364, minPrior, minPct, requireClose);
    }

    @Test
    void detectsFreshBreakoutWhenLatestHighCrossesPrior52wHigh() {
        // Prior rising highs 90,92,95,98,100 (max 100 on day 4); latest day 5 spikes to 110.
        List<Candle> bars = new ArrayList<>();
        bars.add(bar(0, "89", "90", "88", "89"));
        bars.add(bar(1, "91", "92", "90", "91"));
        bars.add(bar(2, "93", "95", "92", "94"));
        bars.add(bar(3, "96", "98", "95", "97"));
        bars.add(bar(4, "98", "100", "97", "99"));
        bars.add(bar(5, "101", "110", "100", "108"));

        Outcome o = calc(5, 0.0, false).evaluate("TEST", bars, "TestSector", 100);

        assertEquals(Status.BREAKOUT, o.status());
        assertNotNull(o.breakout());
        assertEquals(new BigDecimal("100.00"), o.breakout().prior52wHigh());
        assertEquals(new BigDecimal("110.00"), o.breakout().latestHigh());
        assertEquals(new BigDecimal("10.00"), o.breakout().breakoutPct());
        assertTrue(o.breakout().closeConfirmed()); // close 108 > prior high 100
        assertEquals(1, o.breakout().sessionsSincePriorHigh()); // day4 -> day5
        assertEquals("TestSector", o.breakout().sector());
        assertEquals(100, o.breakout().lotSize());
    }

    @Test
    void noBreakoutWhenLatestHighEqualsPriorHigh() {
        // Strict cross required: equal high (100) is not a breakout.
        List<Candle> bars = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            bars.add(bar(i, "99", "100", "98", "99"));
        }
        bars.add(bar(5, "99", "100", "98", "99")); // high == prior 52w high

        Outcome o = calc(5, 0.0, false).evaluate("TEST", bars, null, 100);
        assertEquals(Status.NO_BREAKOUT, o.status());
        assertNull(o.breakout());
    }

    @Test
    void noBreakoutWhenLatestStaysBelowPriorHigh() {
        List<Candle> bars = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            bars.add(bar(i, "95", "100", "94", "98"));
        }
        bars.add(bar(5, "97", "105", "96", "101")); // 105 < prior 52w high? no: 105>100
        // Make the prior the true max instead.
        bars.set(5, bar(5, "90", "99", "89", "93")); // high 99 < prior 100

        Outcome o = calc(5, 0.0, false).evaluate("TEST", bars, null, 100);
        assertEquals(Status.NO_BREAKOUT, o.status());
    }

    @Test
    void latestCandleIsExcludedFromPriorLookbackSoANewHighQualifies() {
        // The latest candle carries the all-time high; the prior window (before it) tops at 100.
        // Because the latest candle is excluded from the prior lookback, 110 is a genuine fresh
        // cross.
        List<Candle> bars = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            bars.add(bar(i, "95", "100", "94", "98"));
        }
        bars.add(bar(5, "101", "110", "100", "109"));

        Outcome o = calc(5, 0.0, false).evaluate("TEST", bars, null, 100);
        assertEquals(Status.BREAKOUT, o.status());
        assertEquals(new BigDecimal("100.00"), o.breakout().prior52wHigh());
    }

    @Test
    void insufficientHistoryWhenTooFewPriorSessions() {
        // Only 3 prior candles but minPriorSessions = 5.
        List<Candle> bars = new ArrayList<>();
        bars.add(bar(0, "95", "100", "94", "98"));
        bars.add(bar(1, "96", "101", "95", "99"));
        bars.add(bar(2, "97", "102", "96", "100"));
        bars.add(bar(3, "103", "110", "102", "108"));

        Outcome o = calc(5, 0.0, false).evaluate("TEST", bars, null, 100);
        assertEquals(Status.INSUFFICIENT_HISTORY, o.status());
    }

    @Test
    void closeOnlyCrossIsReportedWhenCloseConfirmationDisabled() {
        // High crosses (110 > 100) but close finishes below the prior high (95).
        List<Candle> bars = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            bars.add(bar(i, "95", "100", "94", "98"));
        }
        bars.add(bar(5, "99", "110", "94", "95"));

        Outcome lenient = calc(5, 0.0, false).evaluate("TEST", bars, null, 100);
        assertEquals(Status.BREAKOUT, lenient.status());
        assertFalse(lenient.breakout().closeConfirmed());
    }

    @Test
    void closeOnlyCrossIsRejectedWhenCloseConfirmationRequired() {
        List<Candle> bars = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            bars.add(bar(i, "95", "100", "94", "98"));
        }
        bars.add(bar(5, "99", "110", "94", "95")); // close 95 < prior 100

        Outcome strict = calc(5, 0.0, true).evaluate("TEST", bars, null, 100);
        assertEquals(Status.NO_BREAKOUT, strict.status());
    }

    @Test
    void minBreakoutPctThresholdFiltersSmallCrosses() {
        // Latest high 105 -> +5.00% over prior 100.
        List<Candle> bars = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            bars.add(bar(i, "95", "100", "94", "98"));
        }
        bars.add(bar(5, "101", "105", "100", "104"));

        assertEquals(
                Status.NO_BREAKOUT, calc(5, 6.0, false).evaluate("TEST", bars, null, 100).status());
        assertEquals(
                Status.BREAKOUT, calc(5, 4.0, false).evaluate("TEST", bars, null, 100).status());
    }

    @Test
    void noDataForNullEmptyOrSingleBar() {
        assertEquals(
                Status.NO_DATA, calc(5, 0.0, false).evaluate("TEST", null, null, 100).status());
        assertEquals(
                Status.NO_DATA,
                calc(5, 0.0, false).evaluate("TEST", List.of(), null, 100).status());
        assertEquals(
                Status.NO_DATA,
                calc(5, 0.0, false)
                        .evaluate("TEST", List.of(bar(0, "1", "2", "1", "2")), null, 100)
                        .status());
    }
}
