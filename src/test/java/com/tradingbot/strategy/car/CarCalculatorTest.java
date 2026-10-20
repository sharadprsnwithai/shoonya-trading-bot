package com.tradingbot.strategy.car;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.car.model.CarAnalysisResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CarCalculatorTest {

    private CarCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new CarCalculator(10, 252);
    }

    @Test
    void testRelaxoWorkedExampleCumulativeAverageCalculation() {
        // Relaxo video example: closes 448.50, 445.95, 445.60 -> averages 448.50, 447.23, 446.68
        List<BigDecimal> closes =
                List.of(
                        new BigDecimal("448.50"),
                        new BigDecimal("445.95"),
                        new BigDecimal("445.60"));

        List<BigDecimal> cumAverages = calculator.calculateCumulativeAverages(closes, 2);
        assertEquals(3, cumAverages.size());
        assertEquals(new BigDecimal("448.50"), cumAverages.get(0));
        assertEquals(new BigDecimal("447.23"), cumAverages.get(1));
        assertEquals(new BigDecimal("446.68"), cumAverages.get(2));
    }

    @Test
    void testCarPositiveWithStrict10ConsecutiveDays() {
        Instant t0 = Instant.parse("2025-01-01T04:00:00Z");
        List<Candle> candles = new ArrayList<>();

        // 52W High Anchor at day 0 (Close 1000.0)
        candles.add(
                Candle.ofDaily(
                        "RELAXO",
                        t0,
                        new BigDecimal("990"),
                        new BigDecimal("1010"),
                        new BigDecimal("980"),
                        new BigDecimal("1000.0"),
                        50000));

        // Pullback for 20 days (closes 970 down to 400)
        for (int i = 1; i <= 20; i++) {
            BigDecimal c = BigDecimal.valueOf(1000 - i * 30);
            candles.add(
                    Candle.ofDaily(
                            "RELAXO",
                            t0.plus(i, ChronoUnit.DAYS),
                            c,
                            c.add(BigDecimal.ONE),
                            c.subtract(BigDecimal.ONE),
                            c,
                            10000));
        }

        // 10 consecutive rising days where close (e.g. 700 to 880) is above previous CumAvg
        // (~550-600) but below 52W high (1000)
        for (int i = 21; i <= 30; i++) {
            BigDecimal c = BigDecimal.valueOf(700 + (i - 20) * 20); // 720, 740, 760... 900
            candles.add(
                    Candle.ofDaily(
                            "RELAXO",
                            t0.plus(i, ChronoUnit.DAYS),
                            c,
                            c.add(BigDecimal.ONE),
                            c.subtract(BigDecimal.ONE),
                            c,
                            20000));
        }

        CarAnalysisResult result = calculator.analyze("RELAXO", candles);
        assertTrue(result.isCarPositive());
        assertEquals(10, result.consecutivePositiveDays());
        assertEquals(new BigDecimal("1010"), result.fiftyTwoWeekAnchorHigh());
    }

    @Test
    void testAnchorUses52WeekHighestHighInsteadOfHighestClose() {
        Instant t0 = Instant.parse("2025-01-01T04:00:00Z");
        List<Candle> candles = new ArrayList<>();

        // Day 0: High = 1050, Close = 900 (Highest high of the year, but not highest close)
        candles.add(
                Candle.ofDaily(
                        "TEST",
                        t0,
                        new BigDecimal("910"),
                        new BigDecimal("1050"),
                        new BigDecimal("890"),
                        new BigDecimal("900"),
                        50000));

        // Day 1: High = 960, Close = 950 (Highest close of the year, but lower high)
        candles.add(
                Candle.ofDaily(
                        "TEST",
                        t0.plus(1, ChronoUnit.DAYS),
                        new BigDecimal("920"),
                        new BigDecimal("960"),
                        new BigDecimal("910"),
                        new BigDecimal("950"),
                        40000));

        // Subsequent 5 days
        for (int i = 2; i <= 6; i++) {
            candles.add(
                    Candle.ofDaily(
                            "TEST",
                            t0.plus(i, ChronoUnit.DAYS),
                            new BigDecimal("800"),
                            new BigDecimal("850"),
                            new BigDecimal("790"),
                            new BigDecimal("810"),
                            20000));
        }

        CarAnalysisResult result = calculator.analyze("TEST", candles);
        // Anchor date MUST be Day 0 (2025-01-01) corresponding to High 1050, not Day 1 (Close 950)
        assertEquals(java.time.LocalDate.of(2025, 1, 1), result.fiftyTwoWeekHighDate());
        assertEquals(new BigDecimal("1050"), result.fiftyTwoWeekAnchorHigh());
    }

    @Test
    void testCarPositiveTriggersOn9IncreasesAcross10DaysWindow() {
        Instant t0 = Instant.parse("2025-01-01T04:00:00Z");
        List<Candle> candles = new ArrayList<>();

        // Day 0: 52W High Anchor (High 1000, Close 1000)
        candles.add(
                Candle.ofDaily(
                        "TEST",
                        t0,
                        new BigDecimal("990"),
                        new BigDecimal("1000"),
                        new BigDecimal("980"),
                        new BigDecimal("1000"),
                        50000));

        // Pullback for 20 days (Closes 970 down to 400)
        for (int i = 1; i <= 20; i++) {
            BigDecimal c = BigDecimal.valueOf(1000 - i * 30);
            candles.add(
                    Candle.ofDaily(
                            "TEST",
                            t0.plus(i, ChronoUnit.DAYS),
                            c,
                            c.add(BigDecimal.ONE),
                            c.subtract(BigDecimal.ONE),
                            c,
                            10000));
        }

        // Exactly 9 consecutive rising days (Days 21 to 29 = 10 day window, 9 daily increases)
        // Mirrors Mahesh Kaushik's SUMPRODUCT(--(G2:G10 > G3:G11)) = 9
        for (int i = 21; i <= 29; i++) {
            BigDecimal c = BigDecimal.valueOf(700 + (i - 20) * 20); // 720, 740, 760... 880
            candles.add(
                    Candle.ofDaily(
                            "TEST",
                            t0.plus(i, ChronoUnit.DAYS),
                            c,
                            c.add(BigDecimal.ONE),
                            c.subtract(BigDecimal.ONE),
                            c,
                            20000));
        }

        CarAnalysisResult result = calculator.analyze("TEST", candles);
        assertTrue(
                result.isCarPositive(),
                "A 10-day window with 9 daily increases must trigger CAR-positive");
        assertEquals(9, result.consecutivePositiveDays());
    }

    @Test
    void testSubtleFractionalIncrementPreservedWithoutRoundingStreakBreak() {
        Instant t0 = Instant.parse("2025-01-01T04:00:00Z");
        List<Candle> candles = new ArrayList<>();

        // Day 0: Anchor High 3000, Close 3000
        candles.add(
                Candle.ofDaily(
                        "FRAC",
                        t0,
                        new BigDecimal("2990"),
                        new BigDecimal("3000"),
                        new BigDecimal("2980"),
                        new BigDecimal("3000"),
                        10000));

        // 50 days of pullback to build a large denominator
        for (int i = 1; i <= 50; i++) {
            BigDecimal c = new BigDecimal("2000.00");
            candles.add(
                    Candle.ofDaily(
                            "FRAC",
                            t0.plus(i, ChronoUnit.DAYS),
                            c,
                            c.add(BigDecimal.ONE),
                            c.subtract(BigDecimal.ONE),
                            c,
                            5000));
        }

        // 9 days with very slight daily price increments that produce < 0.005 change in cumulative
        // average
        // At day 51, avg is ~2019.6. Adding 2020.1, 2020.2... produces ~0.002 daily average
        // increase.
        for (int i = 51; i <= 59; i++) {
            BigDecimal c = new BigDecimal("2020.00").add(BigDecimal.valueOf((i - 50) * 0.1));
            candles.add(
                    Candle.ofDaily(
                            "FRAC",
                            t0.plus(i, ChronoUnit.DAYS),
                            c,
                            c.add(BigDecimal.ONE),
                            c.subtract(BigDecimal.ONE),
                            c,
                            5000));
        }

        CarAnalysisResult result = calculator.analyze("FRAC", candles);
        assertTrue(
                result.isCarPositive(),
                "Subtle fractional increments must not be wiped by 2-decimal rounding");
        assertEquals(9, result.consecutivePositiveDays());
    }
}
