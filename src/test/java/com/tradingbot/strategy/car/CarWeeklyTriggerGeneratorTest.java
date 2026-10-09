package com.tradingbot.strategy.car;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CarWeeklyTriggerGeneratorTest {

    private CarWeeklyTriggerGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new CarWeeklyTriggerGenerator(new BigDecimal("0.10"));
    }

    @Test
    void testComputeTriggerAndCeilQuantity() {
        Instant monday = Instant.parse("2026-09-21T04:00:00Z");
        List<Candle> weekCandles =
                List.of(
                        Candle.ofDaily(
                                "TEST_STOCK",
                                monday,
                                new BigDecimal("115"),
                                new BigDecimal("118.90"),
                                new BigDecimal("114"),
                                new BigDecimal("117"),
                                10000), // Mon high 118.90
                        Candle.ofDaily(
                                "TEST_STOCK",
                                monday.plus(1, ChronoUnit.DAYS),
                                new BigDecimal("116"),
                                new BigDecimal("117.50"),
                                new BigDecimal("115"),
                                new BigDecimal("116"),
                                8000),
                        Candle.ofDaily(
                                "TEST_STOCK",
                                monday.plus(2, ChronoUnit.DAYS),
                                new BigDecimal("116"),
                                new BigDecimal("118.20"),
                                new BigDecimal("115"),
                                new BigDecimal("117"),
                                9000),
                        Candle.ofDaily(
                                "TEST_STOCK",
                                monday.plus(3, ChronoUnit.DAYS),
                                new BigDecimal("117"),
                                new BigDecimal("118.50"),
                                new BigDecimal("116"),
                                new BigDecimal("118"),
                                11000),
                        Candle.ofDaily(
                                "TEST_STOCK",
                                monday.plus(4, ChronoUnit.DAYS),
                                new BigDecimal("118"),
                                new BigDecimal("118.70"),
                                new BigDecimal("117"),
                                new BigDecimal("117.5"),
                                9500));

        BigDecimal unitSize =
                new BigDecimal("5000.0"); // Video example: 5000 / 118.90 = 42.05 -> ceil 43
        CarWeeklyTriggerGenerator.TriggerCalculation result =
                generator.calculateTrigger("TEST_STOCK", weekCandles, unitSize);

        assertEquals(new BigDecimal("118.90"), result.triggerPrice());
        assertEquals(new BigDecimal("119.00"), result.limitPrice()); // 118.90 + 0.10 = 119.00
        // Ceil sizing alone (43) would commit 43 x 119.00 = 5117 against a 5000 unit, so the
        // quantity is capped to what one unit can actually pay for: floor(5000 / 119.00) = 42.
        assertEquals(42, result.quantity());
    }

    @Test
    void testQuantityIsCappedSoOneOrderNeverExceedsOneUnit() {
        // trigger 900, limit 900.10, unit 25000 -> ceil(25000/900) = 28 shares = 25,202.80
        // over budget; the cap must bring it to floor(25000/900.10) = 27 shares.
        Instant monday = Instant.parse("2026-09-21T04:00:00Z");
        List<Candle> weekCandles =
                List.of(
                        Candle.ofDaily(
                                "TEST_STOCK",
                                monday,
                                new BigDecimal("880"),
                                new BigDecimal("900"),
                                new BigDecimal("870"),
                                new BigDecimal("895"),
                                10000));

        CarWeeklyTriggerGenerator.TriggerCalculation result =
                generator.calculateTrigger("TEST_STOCK", weekCandles, new BigDecimal("25000.0"));

        assertEquals(new BigDecimal("900.00"), result.triggerPrice());
        assertEquals(new BigDecimal("900.10"), result.limitPrice());
        assertEquals(27, result.quantity());
        assertTrue(
                result.limitPrice()
                                .multiply(BigDecimal.valueOf(result.quantity()))
                                .compareTo(new BigDecimal("25000.0"))
                        <= 0);
    }

    @Test
    void testHighPricedStockAllocatesMinimumOneShare() {
        Instant monday = Instant.parse("2026-09-21T04:00:00Z");
        List<Candle> weekCandles =
                List.of(
                        Candle.ofDaily(
                                "MRF",
                                monday,
                                new BigDecimal("130000"),
                                new BigDecimal("132000"),
                                new BigDecimal("129000"),
                                new BigDecimal("131000"),
                                500));

        BigDecimal unitSize =
                new BigDecimal("25000.0"); // Price 132,000 > UNIT 25,000 -> ceil gives 1 share
        CarWeeklyTriggerGenerator.TriggerCalculation result =
                generator.calculateTrigger("MRF", weekCandles, unitSize);

        assertEquals(new BigDecimal("132000.00"), result.triggerPrice());
        assertEquals(new BigDecimal("132000.10"), result.limitPrice());
        assertEquals(1, result.quantity());
    }
}
