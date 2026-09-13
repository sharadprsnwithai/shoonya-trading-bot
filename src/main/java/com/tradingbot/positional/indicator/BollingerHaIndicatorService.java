package com.tradingbot.positional.indicator;

import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** Service computing Heikin-Ashi candlestick series and Bollinger Bands (20, 2) on HA Close. */
@Service
public class BollingerHaIndicatorService {

    /**
     * Computes Heikin-Ashi series and Bollinger Bands.
     *
     * @param candles List of daily OHLC candles in ascending chronological order.
     * @param period Period for Moving Average and Standard Deviation (default 20).
     * @param multiplier Standard deviation multiplier (default 2.0).
     * @return List of BollingerBandSnapshot.
     */
    public List<BollingerBandSnapshot> calculate(
            List<Candle> candles, int period, double multiplier) {
        if (candles == null || candles.isEmpty()) {
            return List.of();
        }

        int n = candles.size();
        List<HeikinAshiCandle> haSeries = calculateHeikinAshi(candles);
        List<BollingerBandSnapshot> result = new ArrayList<>(n);

        double[] haCloses = new double[n];
        for (int i = 0; i < n; i++) {
            haCloses[i] = haSeries.get(i).close().doubleValue();
        }

        for (int i = 0; i < n; i++) {
            Candle norm = candles.get(i);
            HeikinAshiCandle ha = haSeries.get(i);

            BigDecimal sma = null;
            BigDecimal upper = null;
            BigDecimal lower = null;
            BigDecimal bandwidth = null;

            if (i >= period - 1) {
                double sum = 0.0;
                for (int j = i - period + 1; j <= i; j++) {
                    sum += haCloses[j];
                }
                double mean = sum / period;

                double varSum = 0.0;
                for (int j = i - period + 1; j <= i; j++) {
                    varSum += Math.pow(haCloses[j] - mean, 2);
                }
                double std = Math.sqrt(varSum / period);

                double u = mean + multiplier * std;
                double l = mean - multiplier * std;

                sma = BigDecimal.valueOf(mean).setScale(2, RoundingMode.HALF_UP);
                upper = BigDecimal.valueOf(u).setScale(2, RoundingMode.HALF_UP);
                lower = BigDecimal.valueOf(l).setScale(2, RoundingMode.HALF_UP);
                bandwidth =
                        (mean != 0)
                                ? BigDecimal.valueOf((u - l) / mean)
                                        .setScale(4, RoundingMode.HALF_UP)
                                : BigDecimal.ZERO;
            }

            result.add(
                    new BollingerBandSnapshot(
                            norm.timestamp(),
                            norm.formattedTime(),
                            norm.open(),
                            norm.high(),
                            norm.low(),
                            norm.close(),
                            ha.open(),
                            ha.high(),
                            ha.low(),
                            ha.close(),
                            sma,
                            upper,
                            lower,
                            bandwidth));
        }

        return result;
    }

    /** Calculates the Heikin-Ashi candlestick series from normal OHLC candles. */
    public List<HeikinAshiCandle> calculateHeikinAshi(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            return List.of();
        }

        int n = candles.size();
        List<HeikinAshiCandle> haList = new ArrayList<>(n);

        Candle first = candles.get(0);
        double haClose0 =
                (first.open().doubleValue()
                                + first.high().doubleValue()
                                + first.low().doubleValue()
                                + first.close().doubleValue())
                        / 4.0;
        double haOpen0 = (first.open().doubleValue() + first.close().doubleValue()) / 2.0;
        double haHigh0 = Math.max(first.high().doubleValue(), Math.max(haOpen0, haClose0));
        double haLow0 = Math.min(first.low().doubleValue(), Math.min(haOpen0, haClose0));

        haList.add(
                new HeikinAshiCandle(
                        first.timestamp(),
                        first.formattedTime(),
                        BigDecimal.valueOf(haOpen0).setScale(2, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(haHigh0).setScale(2, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(haLow0).setScale(2, RoundingMode.HALF_UP),
                        BigDecimal.valueOf(haClose0).setScale(2, RoundingMode.HALF_UP),
                        first.volume()));

        double prevHaOpen = haOpen0;
        double prevHaClose = haClose0;

        for (int i = 1; i < n; i++) {
            Candle curr = candles.get(i);
            double currHaClose =
                    (curr.open().doubleValue()
                                    + curr.high().doubleValue()
                                    + curr.low().doubleValue()
                                    + curr.close().doubleValue())
                            / 4.0;
            double currHaOpen = (prevHaOpen + prevHaClose) / 2.0;
            double currHaHigh =
                    Math.max(curr.high().doubleValue(), Math.max(currHaOpen, currHaClose));
            double currHaLow =
                    Math.min(curr.low().doubleValue(), Math.min(currHaOpen, currHaClose));

            haList.add(
                    new HeikinAshiCandle(
                            curr.timestamp(),
                            curr.formattedTime(),
                            BigDecimal.valueOf(currHaOpen).setScale(2, RoundingMode.HALF_UP),
                            BigDecimal.valueOf(currHaHigh).setScale(2, RoundingMode.HALF_UP),
                            BigDecimal.valueOf(currHaLow).setScale(2, RoundingMode.HALF_UP),
                            BigDecimal.valueOf(currHaClose).setScale(2, RoundingMode.HALF_UP),
                            curr.volume()));

            prevHaOpen = currHaOpen;
            prevHaClose = currHaClose;
        }

        return haList;
    }
}
