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
        if (candles == null
                || candles.isEmpty()
                || period <= 0
                || multiplier < 0
                || Double.isNaN(multiplier)) {
            return List.of();
        }

        int n = candles.size();
        List<HeikinAshiCandle> haSeries = calculateHeikinAshi(candles);
        if (haSeries.isEmpty()) {
            return List.of();
        }
        List<BollingerBandSnapshot> result = new ArrayList<>(n);

        double[] haCloses = new double[n];
        for (int i = 0; i < n; i++) {
            BigDecimal c = haSeries.get(i).close();
            haCloses[i] = c != null ? c.doubleValue() : 0.0;
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
                double std = Math.sqrt(Math.max(0.0, varSum / period));

                double u = mean + multiplier * std;
                double l = mean - multiplier * std;

                if (!Double.isNaN(mean) && !Double.isInfinite(mean)) {
                    sma = BigDecimal.valueOf(mean).setScale(2, RoundingMode.HALF_UP);
                }
                if (!Double.isNaN(u) && !Double.isInfinite(u)) {
                    upper = BigDecimal.valueOf(u).setScale(2, RoundingMode.HALF_UP);
                }
                if (!Double.isNaN(l) && !Double.isInfinite(l)) {
                    lower = BigDecimal.valueOf(l).setScale(2, RoundingMode.HALF_UP);
                }
                if (Math.abs(mean) > 1e-9 && !Double.isNaN(u) && !Double.isNaN(l)) {
                    double bw = (u - l) / mean;
                    if (!Double.isNaN(bw) && !Double.isInfinite(bw)) {
                        bandwidth = BigDecimal.valueOf(bw).setScale(4, RoundingMode.HALF_UP);
                    } else {
                        bandwidth = BigDecimal.ZERO;
                    }
                } else {
                    bandwidth = BigDecimal.ZERO;
                }
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
        double fOpen = first.open() != null ? first.open().doubleValue() : 0.0;
        double fHigh = first.high() != null ? first.high().doubleValue() : fOpen;
        double fLow = first.low() != null ? first.low().doubleValue() : fOpen;
        double fClose = first.close() != null ? first.close().doubleValue() : fOpen;

        double haClose0 = (fOpen + fHigh + fLow + fClose) / 4.0;
        double haOpen0 = (fOpen + fClose) / 2.0;
        double haHigh0 = Math.max(fHigh, Math.max(haOpen0, haClose0));
        double haLow0 = Math.min(fLow, Math.min(haOpen0, haClose0));

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
            double cOpen = curr.open() != null ? curr.open().doubleValue() : prevHaClose;
            double cHigh = curr.high() != null ? curr.high().doubleValue() : cOpen;
            double cLow = curr.low() != null ? curr.low().doubleValue() : cOpen;
            double cClose = curr.close() != null ? curr.close().doubleValue() : cOpen;

            double currHaClose = (cOpen + cHigh + cLow + cClose) / 4.0;
            double currHaOpen = (prevHaOpen + prevHaClose) / 2.0;
            double currHaHigh = Math.max(cHigh, Math.max(currHaOpen, currHaClose));
            double currHaLow = Math.min(cLow, Math.min(currHaOpen, currHaClose));

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
