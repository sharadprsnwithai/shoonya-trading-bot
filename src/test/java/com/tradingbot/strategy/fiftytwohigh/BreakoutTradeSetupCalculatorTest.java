package com.tradingbot.strategy.fiftytwohigh;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.fiftytwohigh.model.BreakoutTradeSetup;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the pure trade-ready filter / strike derivation. */
class BreakoutTradeSetupCalculatorTest {

    // 04:00Z == 09:30 IST, so the IST calendar date matches the day offset.
    private static final Instant BASE = Instant.parse("2025-01-01T04:00:00Z");

    private static final double STOCK_START = 100;
    private static final double NIFTY_START = 1000;

    /** Rising (or falling, with a negative increment) daily series of {@code n} bars. */
    private static List<Candle> series(int n, double start, double inc) {
        List<Candle> out = new ArrayList<>();
        for (int d = 0; d < n; d++) {
            double c = start + inc * d;
            out.add(
                    Candle.ofDaily(
                            "TEST",
                            BASE.plus(d, ChronoUnit.DAYS),
                            BigDecimal.valueOf(c - 1),
                            BigDecimal.valueOf(c + 1),
                            BigDecimal.valueOf(c - 2),
                            BigDecimal.valueOf(c),
                            10_000L));
        }
        return out;
    }

    /** Returns a copy of {@code bars} with the latest bar's volume replaced. */
    private static List<Candle> withLatestVolume(List<Candle> bars, long vol) {
        List<Candle> copy = new ArrayList<>(bars);
        Candle last = copy.get(copy.size() - 1);
        copy.set(
                copy.size() - 1,
                new Candle(
                        last.symbol(),
                        last.timeframe(),
                        last.timestamp(),
                        last.open(),
                        last.high(),
                        last.low(),
                        last.close(),
                        vol));
        return copy;
    }

    private static BreakoutTradeSetupCalculator calc() {
        return new BreakoutTradeSetupCalculator(1.3, 4, 8);
    }

    private static List<Candle> passingStock() {
        // 200 bars rising 100..299; latest volume spiked to 30k (2.73x its 20d avg).
        return withLatestVolume(series(200, STOCK_START, 1), 30_000L);
    }

    private static List<Candle> passingNifty() {
        return series(200, NIFTY_START, 1);
    }

    @Test
    void allFiltersPass_yieldsTradeReadyAndDebitSpreadStrikes() {
        BreakoutTradeSetup s = calc().evaluate("TEST", passingStock(), passingNifty(), 100);

        assertTrue(s.tradeReady());
        assertTrue(s.volumeConfirmed());
        assertTrue(s.above200dma());
        assertTrue(s.momentumPositive());
        assertTrue(s.relativeStrengthPositive());
        assertTrue(s.marketUptrend());

        // 30000 / mean(19x10000 + 30000) = 30000/11000 = 2.73x
        assertEquals(new BigDecimal("2.73"), s.volumeRatio());
        // 20d momentum = (299-279)/279*100 = 7.17%
        assertEquals(new BigDecimal("7.17"), s.momentum20dPct());
        // NIFTY 20d momentum = (1199-1179)/1179*100 = 1.70%; RS = 7.17-1.70 = 5.47%
        assertEquals(new BigDecimal("5.47"), s.relativeStrengthPct());

        // Spot 299 -> step 5 (>=200 tier); ATM=300, buy ATM+4=320, sell ATM+8=340.
        assertEquals(new BigDecimal("300.00"), s.atmStrike());
        assertEquals(new BigDecimal("320.00"), s.buyStrike());
        assertEquals(new BigDecimal("340.00"), s.sellStrike());
        assertEquals(100, s.lotSize());
    }

    @Test
    void lowVolume_failsOnlyTheVolumeGate() {
        // Everything passes except participation: latest volume stays at the 10k baseline (1.0x).
        BreakoutTradeSetup s =
                calc().evaluate("TEST", series(200, STOCK_START, 1), passingNifty(), 1);

        assertFalse(s.volumeConfirmed());
        assertEquals(new BigDecimal("1.00"), s.volumeRatio());
        assertTrue(s.above200dma());
        assertTrue(s.momentumPositive());
        assertTrue(s.relativeStrengthPositive());
        assertTrue(s.marketUptrend());
        assertFalse(s.tradeReady());
    }

    @Test
    void niftyBelowIts200dma_failsOnlyTheMarketRegimeGate() {
        // NIFTY falling (1000..801) so it sits under its 200-DMA; its negative momentum only
        // widens the stock's relative strength.
        BreakoutTradeSetup s =
                calc().evaluate("TEST", passingStock(), series(200, NIFTY_START, -1), 1);

        assertFalse(s.marketUptrend());
        assertTrue(s.relativeStrengthPositive());
        assertTrue(s.volumeConfirmed());
        assertTrue(s.above200dma());
        assertFalse(s.tradeReady());
    }

    @Test
    void missingNiftyData_failsTheMarketRegimeGate() {
        BreakoutTradeSetup s = calc().evaluate("TEST", passingStock(), null, 1);

        assertFalse(s.marketUptrend());
        assertFalse(s.relativeStrengthPositive()); // no NIFTY momentum => RS null
        assertNull(s.relativeStrengthPct());
        assertFalse(s.tradeReady());
    }

    @Test
    void thinStockHistory_failsThe200dmaTrendGate() {
        // 50 bars: enough for momentum/volume but not the 200-DMA.
        BreakoutTradeSetup s =
                calc().evaluate(
                                "TEST",
                                withLatestVolume(series(50, STOCK_START, 1), 30_000L),
                                passingNifty(),
                                1);

        assertFalse(s.above200dma());
        assertTrue(s.momentumPositive());
        assertTrue(s.volumeConfirmed());
        assertFalse(s.tradeReady());
    }

    @Test
    void noUsableData_yieldsNotReady() {
        BreakoutTradeSetup s = calc().evaluate("TEST", List.of(), passingNifty(), 1);

        assertFalse(s.tradeReady());
        assertNull(s.spotRef());
        assertNull(s.buyStrike());
        assertFalse(s.above200dma());
        assertFalse(s.marketUptrend());
    }

    @Test
    void strikesFollowNsePriceTiers_highPrice() {
        // Spot 5199 -> 100-pt step (>=5000 tier): ATM=5200, buy=5600, sell=6000.
        List<Candle> stock = withLatestVolume(series(200, 5000, 1), 30_000L);
        BreakoutTradeSetup s = calc().evaluate("TEST", stock, passingNifty(), 1);

        assertEquals(new BigDecimal("5200.00"), s.atmStrike());
        assertEquals(new BigDecimal("5600.00"), s.buyStrike());
        assertEquals(new BigDecimal("6000.00"), s.sellStrike());
    }

    @Test
    void strikesFollowNsePriceTiers_lowPrice() {
        // Spot 199.5 -> 2.5-pt step (<200 tier): ATM=200, buy=210, sell=220.
        List<Candle> stock = withLatestVolume(series(200, 100, 0.5), 30_000L);
        BreakoutTradeSetup s = calc().evaluate("TEST", stock, passingNifty(), 1);

        assertEquals(new BigDecimal("200.00"), s.atmStrike());
        assertEquals(new BigDecimal("210.00"), s.buyStrike());
        assertEquals(new BigDecimal("220.00"), s.sellStrike());
    }
}
