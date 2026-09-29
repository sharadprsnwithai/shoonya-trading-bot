package com.tradingbot.strategy.car;

import static org.junit.jupiter.api.Assertions.*;

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

        List<BigDecimal> cumAverages = calculator.calculateCumulativeAverages(closes);
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

        // 10 consecutive rising days where close (e.g. 700 to 880) is above previous CumAvg (~550-600) but below 52W high (1000)
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
        assertEquals(new BigDecimal("1000.0"), result.fiftyTwoWeekHighClose());
    }
}
