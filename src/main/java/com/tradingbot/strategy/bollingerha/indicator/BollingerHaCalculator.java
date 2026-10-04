package com.tradingbot.strategy.bollingerha.indicator;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.bollingerha.model.BollingerBandResult;
import com.tradingbot.strategy.bollingerha.model.HeikinAshiCandle;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Mathematical utility for Heikin-Ashi transformation and Bollinger Bands computation. */
public final class BollingerHaCalculator {

    private static final BigDecimal TWO = new BigDecimal("2");
    private static final BigDecimal FOUR = new BigDecimal("4");
    private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);

    private BollingerHaCalculator() {}

    /**
     * Converts standard OHLC candles to Heikin-Ashi candles in chronological order.
     *
     * @param regularCandles Chronological list of standard OHLC candles
     * @return Chronological list of Heikin-Ashi candles
     */
    public static List<HeikinAshiCandle> calculateHeikinAshi(List<Candle> regularCandles) {
        if (regularCandles == null || regularCandles.isEmpty()) {
            return List.of();
        }

        List<HeikinAshiCandle> result = new ArrayList<>(regularCandles.size());
        BigDecimal prevHaOpen = null;
        BigDecimal prevHaClose = null;

        for (int i = 0; i < regularCandles.size(); i++) {
            Candle c = regularCandles.get(i);

            // HA_Close = (Open + High + Low + Close) / 4
            BigDecimal haClose =
                    c.open()
                            .add(c.high())
                            .add(c.low())
                            .add(c.close())
                            .divide(FOUR, 2, RoundingMode.HALF_UP);

            // HA_Open = (prevHaOpen + prevHaClose) / 2 (or (Open + Close) / 2 for the first candle)
            BigDecimal haOpen;
            if (i == 0) {
                haOpen = c.open().add(c.close()).divide(TWO, 2, RoundingMode.HALF_UP);
            } else {
                haOpen = prevHaOpen.add(prevHaClose).divide(TWO, 2, RoundingMode.HALF_UP);
            }

            // HA_High = max(High, HA_Open, HA_Close)
            BigDecimal haHigh = c.high().max(haOpen).max(haClose).setScale(2, RoundingMode.HALF_UP);

            // HA_Low = min(Low, HA_Open, HA_Close)
            BigDecimal haLow = c.low().min(haOpen).min(haClose).setScale(2, RoundingMode.HALF_UP);

            // D11: a doji (close == open) is indecision, not a green reversal — the entry rule
            // requires a strictly higher close.
            boolean isGreen = haClose.compareTo(haOpen) > 0;

            result.add(
                    new HeikinAshiCandle(
                            c.timestamp(), haOpen, haHigh, haLow, haClose, isGreen, c.volume()));

            prevHaOpen = haOpen;
            prevHaClose = haClose;
        }

        return result;
    }

    /**
     * Calculates Bollinger Bands on a chronological series of Heikin-Ashi candles.
     *
     * @param haCandles Chronological list of Heikin-Ashi candles
     * @param period Moving average period (e.g. 20)
     * @param stdDevMultiplier Standard deviation multiplier (e.g. 2.0)
     * @return BollingerBandResult or null if fewer than period candles
     */
    public static BollingerBandResult calculateBollingerBands(
            List<HeikinAshiCandle> haCandles, int period, double stdDevMultiplier) {
        if (haCandles == null || haCandles.size() < period || period <= 0) {
            return null;
        }

        int startIdx = haCandles.size() - period;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = startIdx; i < haCandles.size(); i++) {
            sum = sum.add(haCandles.get(i).close());
        }

        BigDecimal periodBd = BigDecimal.valueOf(period);
        BigDecimal sma = sum.divide(periodBd, 4, RoundingMode.HALF_UP);

        BigDecimal varianceSum = BigDecimal.ZERO;
        for (int i = startIdx; i < haCandles.size(); i++) {
            BigDecimal diff = haCandles.get(i).close().subtract(sma);
            varianceSum = varianceSum.add(diff.multiply(diff));
        }

        BigDecimal variance = varianceSum.divide(periodBd, 6, RoundingMode.HALF_UP);
        double stdDevDouble = Math.sqrt(variance.doubleValue());
        BigDecimal stdDev = BigDecimal.valueOf(stdDevDouble * stdDevMultiplier);

        BigDecimal upper = sma.add(stdDev).setScale(2, RoundingMode.HALF_UP);
        BigDecimal lower = sma.subtract(stdDev).setScale(2, RoundingMode.HALF_UP);
        BigDecimal middle = sma.setScale(2, RoundingMode.HALF_UP);
        BigDecimal bandwidth = upper.subtract(lower).setScale(2, RoundingMode.HALF_UP);

        return new BollingerBandResult(upper, middle, lower, bandwidth);
    }

    /**
     * Calculates the Exponential Moving Average (EMA) for a series of values.
     *
     * @param values Chronological list of price values
     * @param period EMA period (e.g. 20)
     * @return Current EMA value or null if values.size() < period
     */
    public static BigDecimal calculateEma(List<BigDecimal> values, int period) {
        if (values == null || values.size() < period || period <= 0) {
            return null;
        }

        // Initial SMA of first 'period' values
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            sum = sum.add(values.get(i));
        }
        BigDecimal currentEma = sum.divide(BigDecimal.valueOf(period), 4, RoundingMode.HALF_UP);

        // Alpha multiplier = 2 / (period + 1)
        double alphaDouble = 2.0 / (period + 1.0);
        BigDecimal alpha = BigDecimal.valueOf(alphaDouble);
        BigDecimal oneMinusAlpha = BigDecimal.ONE.subtract(alpha);

        for (int i = period; i < values.size(); i++) {
            currentEma =
                    values.get(i)
                            .multiply(alpha)
                            .add(currentEma.multiply(oneMinusAlpha))
                            .setScale(4, RoundingMode.HALF_UP);
        }

        return currentEma.setScale(2, RoundingMode.HALF_UP);
    }
}
