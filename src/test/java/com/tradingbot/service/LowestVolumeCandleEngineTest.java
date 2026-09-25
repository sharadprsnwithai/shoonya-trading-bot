package com.tradingbot.service;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.model.Candle;
import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.LowestVolumeSetup;
import com.tradingbot.model.strategy.LowestVolumeSetupState;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LowestVolumeCandleEngineTest {

    @Test
    @DisplayName("Should ignore first 3 candles for entry and compute baseline dayLowestVolume")
    void testFirst3CandlesBaseline() {
        LowestVolumeReversalService service =
                new LowestVolumeReversalService(null, null, null, null, null);

        Instant t0 = Instant.parse("2026-09-18T03:45:00Z"); // 09:15 IST
        List<Candle> candles =
                List.of(
                        Candle.of5m(
                                "TEST",
                                t0,
                                BigDecimal.valueOf(100),
                                BigDecimal.valueOf(105),
                                BigDecimal.valueOf(98),
                                BigDecimal.valueOf(102),
                                10000),
                        Candle.of5m(
                                "TEST",
                                t0.plus(5, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(102),
                                BigDecimal.valueOf(103),
                                BigDecimal.valueOf(99),
                                BigDecimal.valueOf(100),
                                8000),
                        Candle.of5m(
                                "TEST",
                                t0.plus(10, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(100),
                                BigDecimal.valueOf(101),
                                BigDecimal.valueOf(97),
                                BigDecimal.valueOf(98),
                                6000));

        LowestVolumeSetup setup =
                service.evaluateCandleSequence("TEST", LowestVolumeDirection.SHORT, candles);

        assertNotNull(setup);
        assertEquals(LowestVolumeSetupState.SCANNING, setup.getState());
        assertEquals(6000L, setup.getDayLowestVolume());
        assertNull(setup.getTriggerPrice());
    }

    @Test
    @DisplayName("Should arm SHORT setup on Green candle 4 with volume < baseline lowest")
    void testArmShortSetupOnGreenLowVolume() {
        LowestVolumeReversalService service =
                new LowestVolumeReversalService(null, null, null, null, null);

        Instant t0 = Instant.parse("2026-09-18T03:45:00Z");
        List<Candle> candles =
                List.of(
                        Candle.of5m(
                                "PVRINOX",
                                t0,
                                BigDecimal.valueOf(100),
                                BigDecimal.valueOf(105),
                                BigDecimal.valueOf(98),
                                BigDecimal.valueOf(102),
                                10000),
                        Candle.of5m(
                                "PVRINOX",
                                t0.plus(5, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(102),
                                BigDecimal.valueOf(103),
                                BigDecimal.valueOf(99),
                                BigDecimal.valueOf(100),
                                8000),
                        Candle.of5m(
                                "PVRINOX",
                                t0.plus(10, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(100),
                                BigDecimal.valueOf(101),
                                BigDecimal.valueOf(97),
                                BigDecimal.valueOf(98),
                                6000),
                        // C4: 09:30 Green candle (O=98, H=102, L=97, C=101), Vol = 4500 (< 6000)
                        Candle.of5m(
                                "PVRINOX",
                                t0.plus(15, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(98),
                                BigDecimal.valueOf(102),
                                BigDecimal.valueOf(97),
                                BigDecimal.valueOf(101),
                                4500));

        LowestVolumeSetup setup =
                service.evaluateCandleSequence("PVRINOX", LowestVolumeDirection.SHORT, candles);

        assertNotNull(setup);
        assertEquals(LowestVolumeSetupState.TRIGGER_ARMED, setup.getState());
        assertEquals(0, BigDecimal.valueOf(96.95).compareTo(setup.getTriggerPrice())); // Low - 0.05
        assertEquals(
                0, BigDecimal.valueOf(102.05).compareTo(setup.getStopLossPrice())); // High + 0.05
        // Spot Risk = 102.05 - 96.95 = 5.10. 1:2 Target = 96.95 - (2 * 5.10) = 86.75
        assertEquals(0, BigDecimal.valueOf(86.75).compareTo(setup.getTarget1Price()));
        assertEquals(4500L, setup.getDayLowestVolume());
    }

    @Test
    @DisplayName("Should trail trigger when a subsequent Green candle forms with even lower volume")
    void testTrailOrderOnNewerLowerVolumeGreenCandle() {
        LowestVolumeReversalService service =
                new LowestVolumeReversalService(null, null, null, null, null);

        Instant t0 = Instant.parse("2026-09-18T03:45:00Z");
        List<Candle> candles =
                List.of(
                        Candle.of5m(
                                "PVRINOX",
                                t0,
                                BigDecimal.valueOf(100),
                                BigDecimal.valueOf(105),
                                BigDecimal.valueOf(98),
                                BigDecimal.valueOf(102),
                                10000),
                        Candle.of5m(
                                "PVRINOX",
                                t0.plus(5, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(102),
                                BigDecimal.valueOf(103),
                                BigDecimal.valueOf(99),
                                BigDecimal.valueOf(100),
                                8000),
                        Candle.of5m(
                                "PVRINOX",
                                t0.plus(10, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(100),
                                BigDecimal.valueOf(101),
                                BigDecimal.valueOf(97),
                                BigDecimal.valueOf(98),
                                6000),
                        // C4: Green Vol = 4500 (O=98, H=102, L=97, C=101)
                        Candle.of5m(
                                "PVRINOX",
                                t0.plus(15, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(98),
                                BigDecimal.valueOf(102),
                                BigDecimal.valueOf(97),
                                BigDecimal.valueOf(101),
                                4500),
                        // C5: Higher Green candle without breaking Low (O=101, H=105, L=100,
                        // C=104), Vol = 3000 (< 4500)
                        Candle.of5m(
                                "PVRINOX",
                                t0.plus(20, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(101),
                                BigDecimal.valueOf(105),
                                BigDecimal.valueOf(100),
                                BigDecimal.valueOf(104),
                                3000));

        LowestVolumeSetup setup =
                service.evaluateCandleSequence("PVRINOX", LowestVolumeDirection.SHORT, candles);

        assertNotNull(setup);
        assertEquals(LowestVolumeSetupState.TRIGGER_ARMED, setup.getState());
        assertEquals(0, BigDecimal.valueOf(99.95).compareTo(setup.getTriggerPrice())); // Low - 0.05
        assertEquals(
                0, BigDecimal.valueOf(105.05).compareTo(setup.getStopLossPrice())); // High + 0.05
        // Spot Risk = 105.05 - 99.95 = 5.10. 1:2 Target = 99.95 - (2 * 5.10) = 89.75
        assertEquals(0, BigDecimal.valueOf(89.75).compareTo(setup.getTarget1Price()));
        assertEquals(3000L, setup.getDayLowestVolume());
    }

    @Test
    @DisplayName("Should arm LONG setup on Red candle with volume < dayLowestVolume")
    void testArmLongSetupOnRedLowVolume() {
        LowestVolumeReversalService service =
                new LowestVolumeReversalService(null, null, null, null, null);

        Instant t0 = Instant.parse("2026-09-18T03:45:00Z");
        List<Candle> candles =
                List.of(
                        Candle.of5m(
                                "SUNPHARMA",
                                t0,
                                BigDecimal.valueOf(500),
                                BigDecimal.valueOf(510),
                                BigDecimal.valueOf(498),
                                BigDecimal.valueOf(508),
                                12000),
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(5, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(508),
                                BigDecimal.valueOf(515),
                                BigDecimal.valueOf(505),
                                BigDecimal.valueOf(512),
                                9000),
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(10, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(512),
                                BigDecimal.valueOf(518),
                                BigDecimal.valueOf(510),
                                BigDecimal.valueOf(515),
                                7000),
                        // C4: Red candle (O=515, H=516, L=508, C=510), Vol = 4000 (< 7000)
                        Candle.of5m(
                                "SUNPHARMA",
                                t0.plus(15, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(515),
                                BigDecimal.valueOf(516),
                                BigDecimal.valueOf(508),
                                BigDecimal.valueOf(510),
                                4000));

        LowestVolumeSetup setup =
                service.evaluateCandleSequence("SUNPHARMA", LowestVolumeDirection.LONG, candles);

        assertNotNull(setup);
        assertEquals(LowestVolumeSetupState.TRIGGER_ARMED, setup.getState());
        assertEquals(
                0, BigDecimal.valueOf(516.05).compareTo(setup.getTriggerPrice())); // High + 0.05
        assertEquals(
                0, BigDecimal.valueOf(507.95).compareTo(setup.getStopLossPrice())); // Low - 0.05
        // Spot Risk = 516.05 - 507.95 = 8.10. 1:2 Target = 516.05 + (2 * 8.10) = 532.25
        assertEquals(0, BigDecimal.valueOf(532.25).compareTo(setup.getTarget1Price()));
        assertEquals(4000L, setup.getDayLowestVolume());
    }
}
