package com.tradingbot.util;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class HeikinAshiUtilTest {

    private static final Instant BASE = Instant.parse("2025-01-01T04:00:00Z");

    private static Candle bar(int day, String o, String h, String l, String c) {
        return Candle.ofDaily(
                "NIFTY 50",
                BASE.plus(day, ChronoUnit.DAYS),
                new BigDecimal(o),
                new BigDecimal(h),
                new BigDecimal(l),
                new BigDecimal(c),
                1000);
    }

    @Test
    void computesHeikinAshiValuesForWorkedExample() {
        // Standard bars; HA math verified by hand.
        List<Candle> ha =
                HeikinAshiUtil.toHeikinAshi(
                        List.of(
                                bar(0, "10", "12", "9", "11"),
                                bar(1, "11", "13", "10", "12"),
                                bar(2, "12", "14", "11", "13")));

        assertEquals(3, ha.size());

        // Bar 0: HA O = (10+11)/2 = 10.5; HA C = (10+12+9+11)/4 = 10.5 => flat.
        assertEquals(new BigDecimal("10.50"), ha.get(0).open());
        assertEquals(new BigDecimal("12.00"), ha.get(0).high());
        assertEquals(new BigDecimal("9.00"), ha.get(0).low());
        assertEquals(new BigDecimal("10.50"), ha.get(0).close());

        // Bar 1: HA O = (10.5+10.5)/2 = 10.5; HA C = 11.5 => green.
        assertEquals(new BigDecimal("10.50"), ha.get(1).open());
        assertEquals(new BigDecimal("13.00"), ha.get(1).high());
        assertEquals(new BigDecimal("10.00"), ha.get(1).low());
        assertEquals(new BigDecimal("11.50"), ha.get(1).close());

        // Bar 2: HA O = (10.5+11.5)/2 = 11.0; HA C = 12.5 => green.
        assertEquals(new BigDecimal("11.00"), ha.get(2).open());
        assertEquals(new BigDecimal("14.00"), ha.get(2).high());
        assertEquals(new BigDecimal("11.00"), ha.get(2).low());
        assertEquals(new BigDecimal("12.50"), ha.get(2).close());
    }

    @Test
    void classifiesColorAndStreak() {
        List<Candle> ha =
                HeikinAshiUtil.toHeikinAshi(
                        List.of(
                                bar(0, "10", "12", "9", "11"),
                                bar(1, "11", "13", "10", "12"),
                                bar(2, "12", "14", "11", "13")));

        assertFalse(HeikinAshiUtil.isGreen(ha.get(0))); // flat
        assertFalse(HeikinAshiUtil.isRed(ha.get(0)));
        assertTrue(HeikinAshiUtil.isGreen(ha.get(1)));
        assertTrue(HeikinAshiUtil.isGreen(ha.get(2)));
        // Streak counts trailing green bars (bar0 flat breaks it): 2.
        assertEquals(2, HeikinAshiUtil.trendStreak(ha));
    }

    @Test
    void redBarResetsStreak() {
        List<Candle> ha =
                HeikinAshiUtil.toHeikinAshi(
                        List.of(
                                bar(0, "10", "12", "9", "11"),
                                bar(1, "11", "13", "10", "12"),
                                bar(2, "12", "14", "11", "13"),
                                // HA O = (11.0+12.5)/2 = 11.75; HA C = (12+13+9+10)/4 = 11.0 =>
                                // red.
                                bar(3, "12", "13", "9", "10")));

        assertTrue(HeikinAshiUtil.isRed(ha.get(3)));
        assertEquals(new BigDecimal("11.75"), ha.get(3).open());
        assertEquals(new BigDecimal("11.00"), ha.get(3).close());
        // Only the trailing red bar: streak 1.
        assertEquals(1, HeikinAshiUtil.trendStreak(ha));
    }

    @Test
    void emptyInputYieldsEmptyAndZeroStreak() {
        assertTrue(HeikinAshiUtil.toHeikinAshi(null).isEmpty());
        assertTrue(HeikinAshiUtil.toHeikinAshi(List.of()).isEmpty());
        assertEquals(0, HeikinAshiUtil.trendStreak(null));
        assertEquals(0, HeikinAshiUtil.trendStreak(List.of()));
    }
}
