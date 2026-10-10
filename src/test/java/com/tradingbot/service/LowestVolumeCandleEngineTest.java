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
        // Spot Risk = 102.05 - 96.95 = 5.10. 1:2.5 Target = 96.95 - (2.5 * 5.10) = 84.20
        assertEquals(0, BigDecimal.valueOf(84.20).compareTo(setup.getTarget1Price()));
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
        // Spot Risk = 105.05 - 99.95 = 5.10. 1:2.5 Target = 99.95 - (2.5 * 5.10) = 87.20
        assertEquals(0, BigDecimal.valueOf(87.20).compareTo(setup.getTarget1Price()));
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
        // Spot Risk = 516.05 - 507.95 = 8.10. 1:2.5 Target = 516.05 + (2.5 * 8.10) = 536.30
        assertEquals(0, BigDecimal.valueOf(536.30).compareTo(setup.getTarget1Price()));
        assertEquals(4000L, setup.getDayLowestVolume());
    }

    @Test
    @DisplayName("Should arm LONG setup on Setup 3 Vande Bharat (Mother Green + Inside Red)")
    void testArmLongSetupOnVandeBharatInsideBar() {
        LowestVolumeReversalService service =
                new LowestVolumeReversalService(null, null, null, null, null);
        service.setVandeBharatEnabled(true);

        Instant t0 = Instant.parse("2026-09-18T03:45:00Z"); // 09:15 IST
        List<Candle> candles =
                List.of(
                        // C1, C2, C3 baseline
                        Candle.of5m(
                                "RADICO",
                                t0,
                                BigDecimal.valueOf(1000),
                                BigDecimal.valueOf(1010),
                                BigDecimal.valueOf(995),
                                BigDecimal.valueOf(1008),
                                50000),
                        Candle.of5m(
                                "RADICO",
                                t0.plus(5, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1008),
                                BigDecimal.valueOf(1015),
                                BigDecimal.valueOf(1005),
                                BigDecimal.valueOf(1012),
                                40000),
                        Candle.of5m(
                                "RADICO",
                                t0.plus(10, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1012),
                                BigDecimal.valueOf(1018),
                                BigDecimal.valueOf(1010),
                                BigDecimal.valueOf(1016),
                                35000),
                        // C4 (09:30): Strong Mother Green Candle
                        Candle.of5m(
                                "RADICO",
                                t0.plus(15, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1016),
                                BigDecimal.valueOf(1030),
                                BigDecimal.valueOf(1015),
                                BigDecimal.valueOf(1028),
                                60000),
                        // C5 (09:35): Inside Red Candle (High 1027 <= 1030, Low 1018 >= 1015)
                        Candle.of5m(
                                "RADICO",
                                t0.plus(20, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1026),
                                BigDecimal.valueOf(1027),
                                BigDecimal.valueOf(1018),
                                BigDecimal.valueOf(1020),
                                45000));

        LowestVolumeSetup setup =
                service.evaluateCandleSequence("RADICO", LowestVolumeDirection.LONG, candles);

        assertNotNull(setup);
        assertEquals(LowestVolumeSetupState.TRIGGER_ARMED, setup.getState());
        assertEquals("VANDE_BHARAT_INSIDE_BAR", setup.getSetupPattern());
        assertEquals(
                0,
                BigDecimal.valueOf(1030.05)
                        .compareTo(setup.getTriggerPrice())); // Mother High + 0.05
        assertEquals(
                0,
                BigDecimal.valueOf(1017.95)
                        .compareTo(setup.getStopLossPrice())); // Inside Low - 0.05
        assertEquals(
                0,
                BigDecimal.valueOf(1060.30).compareTo(setup.getTarget1Price())); // 1:2.5 RR Target
    }

    @Test
    @DisplayName("Should arm SHORT setup on Setup 3 Vande Bharat (Mother Red + Inside Green)")
    void testArmShortSetupOnVandeBharatInsideBar() {
        LowestVolumeReversalService service =
                new LowestVolumeReversalService(null, null, null, null, null);
        service.setVandeBharatEnabled(true);

        Instant t0 = Instant.parse("2026-09-18T03:45:00Z");
        List<Candle> candles =
                List.of(
                        Candle.of5m(
                                "VOLTAS",
                                t0,
                                BigDecimal.valueOf(1000),
                                BigDecimal.valueOf(1005),
                                BigDecimal.valueOf(990),
                                BigDecimal.valueOf(992),
                                50000),
                        Candle.of5m(
                                "VOLTAS",
                                t0.plus(5, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(992),
                                BigDecimal.valueOf(995),
                                BigDecimal.valueOf(985),
                                BigDecimal.valueOf(988),
                                40000),
                        Candle.of5m(
                                "VOLTAS",
                                t0.plus(10, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(988),
                                BigDecimal.valueOf(990),
                                BigDecimal.valueOf(980),
                                BigDecimal.valueOf(982),
                                35000),
                        // C4 (09:30): Strong Mother Red Candle
                        Candle.of5m(
                                "VOLTAS",
                                t0.plus(15, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(982),
                                BigDecimal.valueOf(984),
                                BigDecimal.valueOf(965),
                                BigDecimal.valueOf(968),
                                60000),
                        // C5 (09:35): Inside Green Candle (High 980 <= 984, Low 970 >= 965)
                        Candle.of5m(
                                "VOLTAS",
                                t0.plus(20, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(970),
                                BigDecimal.valueOf(980),
                                BigDecimal.valueOf(970),
                                BigDecimal.valueOf(978),
                                45000));

        LowestVolumeSetup setup =
                service.evaluateCandleSequence("VOLTAS", LowestVolumeDirection.SHORT, candles);

        assertNotNull(setup);
        assertEquals(LowestVolumeSetupState.TRIGGER_ARMED, setup.getState());
        assertEquals("VANDE_BHARAT_INSIDE_BAR", setup.getSetupPattern());
        assertEquals(
                0,
                BigDecimal.valueOf(964.95).compareTo(setup.getTriggerPrice())); // Mother Low - 0.05
        assertEquals(
                0,
                BigDecimal.valueOf(980.05)
                        .compareTo(setup.getStopLossPrice())); // Inside High + 0.05
        assertEquals(
                0,
                BigDecimal.valueOf(927.20).compareTo(setup.getTarget1Price())); // 1:2.5 RR Target
    }

    @Test
    @DisplayName(
            "2.1 Fix: Setup 2 LVR low volume pullback takes precedence when candle also satisfies Setup 3 inside bar")
    void testSetup2TakesPrecedenceOverSetup3OnSameBar() {
        LowestVolumeReversalService service =
                new LowestVolumeReversalService(null, null, null, null, null);
        Instant t0 = Instant.parse("2026-09-18T03:45:00Z"); // 09:15 IST
        List<Candle> candles =
                List.of(
                        Candle.of5m(
                                "RADICO",
                                t0,
                                BigDecimal.valueOf(1000),
                                BigDecimal.valueOf(1010),
                                BigDecimal.valueOf(995),
                                BigDecimal.valueOf(1008),
                                50000),
                        Candle.of5m(
                                "RADICO",
                                t0.plus(5, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1008),
                                BigDecimal.valueOf(1015),
                                BigDecimal.valueOf(1005),
                                BigDecimal.valueOf(1012),
                                40000),
                        Candle.of5m(
                                "RADICO",
                                t0.plus(10, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1012),
                                BigDecimal.valueOf(1018),
                                BigDecimal.valueOf(1010),
                                BigDecimal.valueOf(1016),
                                35000), // Day lowest = 35000
                        // C4: Green Mother Candle (H=1030, L=1015)
                        Candle.of5m(
                                "RADICO",
                                t0.plus(15, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1016),
                                BigDecimal.valueOf(1030),
                                BigDecimal.valueOf(1015),
                                BigDecimal.valueOf(1028),
                                60000),
                        // C5: Red Candle inside C4 (H=1025 <= 1030, L=1018 >= 1015) AND Volume =
                        // 20000 (<= dayLowest 35000!)
                        Candle.of5m(
                                "RADICO",
                                t0.plus(20, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1024),
                                BigDecimal.valueOf(1025),
                                BigDecimal.valueOf(1018),
                                BigDecimal.valueOf(1020),
                                20000));

        LowestVolumeSetup setup =
                service.evaluateCandleSequence("RADICO", LowestVolumeDirection.LONG, candles);

        // Setup 2 (LVR) should take precedence with trigger at Pullback High (1025.05), NOT Mother
        // High (1030.05)
        assertEquals("LVR_VOLUME_PULLBACK", setup.getSetupPattern());
        assertEquals(0, BigDecimal.valueOf(1025.05).compareTo(setup.getTriggerPrice()));
    }

    @Test
    @DisplayName(
            "Should skip Setup 3 Vande Bharat when vandeBharatEnabled is false (Pure LVR default)")
    void testVandeBharatDisabledByDefault() {
        LowestVolumeReversalService service =
                new LowestVolumeReversalService(null, null, null, null, null);
        service.setVandeBharatEnabled(false);

        Instant t0 = Instant.parse("2026-09-18T03:45:00Z");
        List<Candle> candles =
                List.of(
                        Candle.of5m(
                                "RADICO",
                                t0,
                                BigDecimal.valueOf(1000),
                                BigDecimal.valueOf(1010),
                                BigDecimal.valueOf(995),
                                BigDecimal.valueOf(1008),
                                50000),
                        Candle.of5m(
                                "RADICO",
                                t0.plus(5, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1008),
                                BigDecimal.valueOf(1015),
                                BigDecimal.valueOf(1005),
                                BigDecimal.valueOf(1012),
                                40000),
                        Candle.of5m(
                                "RADICO",
                                t0.plus(10, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1012),
                                BigDecimal.valueOf(1018),
                                BigDecimal.valueOf(1010),
                                BigDecimal.valueOf(1016),
                                35000),
                        // C4: Mother Green
                        Candle.of5m(
                                "RADICO",
                                t0.plus(15, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1016),
                                BigDecimal.valueOf(1030),
                                BigDecimal.valueOf(1015),
                                BigDecimal.valueOf(1028),
                                60000),
                        // C5: Inside Red (High 1027 <= 1030, Low 1018 >= 1015) with Vol = 45000 (>
                        // day lowest 35000, so LVR does not match)
                        Candle.of5m(
                                "RADICO",
                                t0.plus(20, ChronoUnit.MINUTES),
                                BigDecimal.valueOf(1026),
                                BigDecimal.valueOf(1027),
                                BigDecimal.valueOf(1018),
                                BigDecimal.valueOf(1020),
                                45000));

        LowestVolumeSetup setup =
                service.evaluateCandleSequence("RADICO", LowestVolumeDirection.LONG, candles);

        // When vandeBharatEnabled is false, inside-bar is skipped and setup remains SCANNING
        assertEquals(LowestVolumeSetupState.SCANNING, setup.getState());
        assertNull(setup.getTriggerPrice());
    }

    @Test
    @DisplayName("Should compute Target 1 at 1:2.5 RR with targetRr = 2.5")
    void testTargetCalculationWith2Point5RR() {
        LowestVolumeReversalService service =
                new LowestVolumeReversalService(null, null, null, null, null);
        service.setTargetRr(2.5);

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
                        // C4: Red pullback candle (O=515, H=516, L=508, C=510), Vol = 4000 (< 7000)
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

        // Trigger = 516.05, SL = 507.95, Risk = 8.10. Target 1 (1:2.5 RR) = 516.05 + (2.5 * 8.10) =
        // 536.30
        assertEquals(0, BigDecimal.valueOf(516.05).compareTo(setup.getTriggerPrice()));
        assertEquals(0, BigDecimal.valueOf(507.95).compareTo(setup.getStopLossPrice()));
        assertEquals(0, BigDecimal.valueOf(536.30).compareTo(setup.getTarget1Price()));
    }

    @Test
    @DisplayName("resetToScanning() must preserve session-wide first15MinHigh and first15MinLow")
    void testResetToScanningPreservesOpeningRange() {
        LowestVolumeSetup setup = new LowestVolumeSetup("MPHASIS", LowestVolumeDirection.LONG);
        setup.setFirst15MinHigh(BigDecimal.valueOf(2392.20));
        setup.setFirst15MinLow(BigDecimal.valueOf(2330.00));
        setup.setPdh(BigDecimal.valueOf(2357.30));
        setup.setPdl(BigDecimal.valueOf(2280.80));

        setup.setTriggerCandle(
                Candle.of5m(
                        "MPHASIS",
                        Instant.now(),
                        BigDecimal.valueOf(2375.60),
                        BigDecimal.valueOf(2375.60),
                        BigDecimal.valueOf(2371.30),
                        BigDecimal.valueOf(2374.00),
                        2696),
                BigDecimal.valueOf(2375.65),
                BigDecimal.valueOf(2367.35),
                BigDecimal.valueOf(2396.45));

        assertEquals(LowestVolumeSetupState.TRIGGER_ARMED, setup.getState());
        assertEquals(BigDecimal.valueOf(2375.65), setup.getTriggerPrice());

        // Invalidate setup
        setup.resetToScanning();

        assertEquals(LowestVolumeSetupState.SCANNING, setup.getState());
        assertNull(setup.getTriggerPrice());
        assertNull(setup.getStopLossPrice());
        // Session-wide metrics MUST be preserved
        assertEquals(BigDecimal.valueOf(2392.20), setup.getFirst15MinHigh());
        assertEquals(BigDecimal.valueOf(2330.00), setup.getFirst15MinLow());
        assertEquals(BigDecimal.valueOf(2357.30), setup.getPdh());
        assertEquals(BigDecimal.valueOf(2280.80), setup.getPdl());
    }
}
