package com.tradingbot.strategy.bollingerha.indicator;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.bollingerha.model.BollingerBandResult;
import com.tradingbot.strategy.bollingerha.model.HeikinAshiCandle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BollingerHaCalculatorTest {

    @Test
    void testHeikinAshiTransformation() {
        List<Candle> regularCandles =
                List.of(
                        new Candle(
                                "NSE:NIFTY26OCT25950CE",
                                "1",
                                Instant.now(),
                                new BigDecimal("100"),
                                new BigDecimal("110"),
                                new BigDecimal("95"),
                                new BigDecimal("105"),
                                1000L),
                        new Candle(
                                "NSE:NIFTY26OCT25950CE",
                                "1",
                                Instant.now().plusSeconds(60),
                                new BigDecimal("105"),
                                new BigDecimal("115"),
                                new BigDecimal("102"),
                                new BigDecimal("112"),
                                1500L));

        List<HeikinAshiCandle> haCandles =
                BollingerHaCalculator.calculateHeikinAshi(regularCandles);
        assertEquals(2, haCandles.size());

        // Candle 0: HA_Close = (100+110+95+105)/4 = 102.50, HA_Open = (100+105)/2 = 102.50
        assertEquals(new BigDecimal("102.50"), haCandles.get(0).close());
        assertEquals(new BigDecimal("102.50"), haCandles.get(0).open());

        // Candle 1: HA_Open = (102.5 + 102.5)/2 = 102.50, HA_Close = (105+115+102+112)/4 = 108.50
        assertEquals(new BigDecimal("102.50"), haCandles.get(1).open());
        assertEquals(new BigDecimal("108.50"), haCandles.get(1).close());
        assertTrue(haCandles.get(1).isGreen());
    }

    @Test
    void testDojiIsNotGreen() {
        Instant t = Instant.parse("2026-10-04T04:00:00Z");
        // O = H = L = C makes HA_Close == HA_Open, i.e. a doji.
        List<Candle> flat =
                List.of(
                        new Candle(
                                "NSE:NIFTY26OCT25950CE",
                                "1",
                                t,
                                new BigDecimal("100"),
                                new BigDecimal("100"),
                                new BigDecimal("100"),
                                new BigDecimal("100"),
                                1000L),
                        new Candle(
                                "NSE:NIFTY26OCT25950CE",
                                "1",
                                t.plusSeconds(60),
                                new BigDecimal("100"),
                                new BigDecimal("100"),
                                new BigDecimal("100"),
                                new BigDecimal("100"),
                                1200L));

        List<HeikinAshiCandle> ha = BollingerHaCalculator.calculateHeikinAshi(flat);

        assertEquals(2, ha.size());
        assertEquals(0, ha.get(0).close().compareTo(ha.get(0).open()));
        assertFalse(ha.get(0).isGreen(), "a doji must not read as a green reversal");
        assertEquals(0, ha.get(1).close().compareTo(ha.get(1).open()));
        assertFalse(ha.get(1).isGreen());
    }

    @Test
    void testBollingerBandsOnHeikinAshi() {
        List<HeikinAshiCandle> haSeries = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < 25; i++) {
            BigDecimal price = new BigDecimal(100 + i);
            haSeries.add(
                    new HeikinAshiCandle(
                            now.plusSeconds(i * 60),
                            price,
                            price.add(BigDecimal.ONE),
                            price.subtract(BigDecimal.ONE),
                            price,
                            true,
                            1000L));
        }

        BollingerBandResult bb = BollingerHaCalculator.calculateBollingerBands(haSeries, 20, 2.0);
        assertNotNull(bb);
        assertTrue(bb.upper().compareTo(bb.middle()) > 0);
        assertTrue(bb.middle().compareTo(bb.lower()) > 0);
    }
}
