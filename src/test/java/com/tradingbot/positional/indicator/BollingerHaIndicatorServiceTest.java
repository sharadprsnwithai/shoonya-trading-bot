package com.tradingbot.positional.indicator;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BollingerHaIndicatorServiceTest {

    private BollingerHaIndicatorService indicatorService;

    @BeforeEach
    void setUp() {
        indicatorService = new BollingerHaIndicatorService();
    }

    @Test
    void testHeikinAshiAndBollingerBandsCalculation() {
        List<Candle> candles = new ArrayList<>();
        double basePrice = 20000.0;
        Instant start = Instant.parse("2025-01-01T09:15:00Z");

        for (int i = 0; i < 30; i++) {
            double open = basePrice + i * 10;
            double high = open + 50;
            double low = open - 30;
            double close = open + 20;
            candles.add(
                    new Candle(
                            "NSE:NIFTY50",
                            "D",
                            start.plusSeconds(i * 86400),
                            BigDecimal.valueOf(open),
                            BigDecimal.valueOf(high),
                            BigDecimal.valueOf(low),
                            BigDecimal.valueOf(close),
                            100000L));
        }

        List<BollingerBandSnapshot> snapshots = indicatorService.calculate(candles, 20, 2.0);

        assertNotNull(snapshots);
        assertEquals(30, snapshots.size());

        // For the first 19 candles, BB values should be null because period is 20
        assertNull(snapshots.get(18).bbUpper());

        // From 20th candle onwards (index 19), BB values should be present
        BollingerBandSnapshot lastSnapshot = snapshots.get(29);
        assertNotNull(lastSnapshot.bbUpper());
        assertNotNull(lastSnapshot.sma20());
        assertNotNull(lastSnapshot.bbLower());

        assertTrue(lastSnapshot.bbUpper().doubleValue() > lastSnapshot.sma20().doubleValue());
        assertTrue(lastSnapshot.sma20().doubleValue() > lastSnapshot.bbLower().doubleValue());

        // Check HA values
        assertNotNull(lastSnapshot.haOpen());
        assertNotNull(lastSnapshot.haHigh());
        assertNotNull(lastSnapshot.haLow());
        assertNotNull(lastSnapshot.haClose());
        assertTrue(lastSnapshot.haHigh().compareTo(lastSnapshot.haLow()) >= 0);
    }

    @Test
    void testInvalidParametersReturnEmpty() {
        List<Candle> candles = List.of(
                new Candle("NSE:NIFTY50", "D", Instant.now(), BigDecimal.valueOf(100), BigDecimal.valueOf(105), BigDecimal.valueOf(95), BigDecimal.valueOf(102), 1000L));

        assertTrue(indicatorService.calculate(null, 20, 2.0).isEmpty());
        assertTrue(indicatorService.calculate(List.of(), 20, 2.0).isEmpty());
        assertTrue(indicatorService.calculate(candles, 0, 2.0).isEmpty());
        assertTrue(indicatorService.calculate(candles, -5, 2.0).isEmpty());
        assertTrue(indicatorService.calculate(candles, 20, -1.0).isEmpty());
        assertTrue(indicatorService.calculate(candles, 20, Double.NaN).isEmpty());
    }

    @Test
    void testCandleWithNullPricesGracefullyHandled() {
        List<Candle> candles = new ArrayList<>();
        Instant start = Instant.parse("2025-01-01T09:15:00Z");
        for (int i = 0; i < 25; i++) {
            candles.add(new Candle("NSE:NIFTY50", "D", start.plusSeconds(i * 86400), null, null, null, null, 1000L));
        }

        List<BollingerBandSnapshot> snapshots = indicatorService.calculate(candles, 20, 2.0);
        assertNotNull(snapshots);
        assertEquals(25, snapshots.size());
    }
}
