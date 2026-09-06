package com.tradingbot.indicator;

import com.tictactec.ta.lib.Core;
import com.tictactec.ta.lib.MInteger;
import com.tictactec.ta.lib.RetCode;
import com.tradingbot.model.Candle;
import com.tradingbot.model.indicator.IndicatorResponse;
import com.tradingbot.model.indicator.IndicatorSnapshot;
import com.tradingbot.model.indicator.RsiResult;
import com.tradingbot.model.indicator.SuperTrendResult;
import com.tradingbot.model.indicator.VwapResult;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

/** Technical Analysis Service providing SuperTrend, RSI (TA-Lib), and VWAP calculations. */
@Service
public class TechnicalAnalysisService {

    private static final Core TA_LIB = new Core();
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    /** Computes technical indicators (SuperTrend, RSI, VWAP) for a series of candles. */
    public IndicatorResponse analyze(
            String symbol,
            String interval,
            List<Candle> candles,
            int atrPeriod,
            double multiplier,
            int rsiPeriod) {
        if (candles == null || candles.isEmpty()) {
            return IndicatorResponse.of(symbol, interval, List.of());
        }

        int size = candles.size();
        double[] high = new double[size];
        double[] low = new double[size];
        double[] close = new double[size];
        long[] volume = new long[size];

        for (int i = 0; i < size; i++) {
            Candle c = candles.get(i);
            high[i] = c.high().doubleValue();
            low[i] = c.low().doubleValue();
            close[i] = c.close().doubleValue();
            volume[i] = c.volume();
        }

        // 1. Calculate SuperTrend series
        SuperTrendResult[] stSeries =
                calculateSuperTrendSeries(high, low, close, atrPeriod, multiplier);

        // 2. Calculate RSI series
        double[] rsiSeries = calculateRsiSeries(close, rsiPeriod);

        // 3. Calculate VWAP series (with intraday day-boundary reset)
        double[] vwapSeries = calculateVwapSeries(candles);

        // 4. Combine into snapshots
        List<IndicatorSnapshot> snapshots = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Candle c = candles.get(i);
            SuperTrendResult st = stSeries[i];
            double rsiVal = rsiSeries[i];
            RsiResult rsiRes = RsiResult.of(rsiVal);
            double vwapVal = vwapSeries[i];
            VwapResult vwapRes = VwapResult.of(vwapVal, c.close().doubleValue());

            Double stVal = (st != null && !Double.isNaN(st.value())) ? st.value() : null;
            String stSignal = (st != null && !Double.isNaN(st.value())) ? st.trend() : "NEUTRAL";

            Double rsiOut = !Double.isNaN(rsiRes.value()) ? rsiRes.value() : null;
            String rsiSignal = rsiRes.signal();

            Double vwapOut = !Double.isNaN(vwapRes.vwap()) ? vwapRes.vwap() : null;
            Double vwapDiff = !Double.isNaN(vwapRes.vwap()) ? vwapRes.difference() : null;

            snapshots.add(
                    new IndicatorSnapshot(
                            c.timestamp(),
                            c.formattedTime(),
                            c.open().doubleValue(),
                            c.high().doubleValue(),
                            c.low().doubleValue(),
                            c.close().doubleValue(),
                            c.volume(),
                            stVal,
                            stSignal,
                            rsiOut,
                            rsiSignal,
                            vwapOut,
                            vwapDiff));
        }

        return IndicatorResponse.of(symbol, interval, snapshots);
    }

    /** Calculates Relative Strength Index (RSI) using TA-Lib across all bars. */
    public double[] calculateRsiSeries(double[] close, int period) {
        int len = close.length;
        double[] result = new double[len];
        Arrays.fill(result, Double.NaN);

        if (close == null || len < period + 1 || period <= 0) {
            return result;
        }

        MInteger outBegIdx = new MInteger();
        MInteger outNBElement = new MInteger();
        double[] output = new double[len];

        RetCode retCode = TA_LIB.rsi(0, len - 1, close, period, outBegIdx, outNBElement, output);
        if (retCode == RetCode.Success && outNBElement.value > 0) {
            int start = outBegIdx.value;
            for (int i = 0; i < outNBElement.value; i++) {
                result[start + i] = output[i];
            }
        }
        return result;
    }

    /** Calculates Relative Strength Index (RSI) for the latest bar. */
    public double calculateLatestRsi(double[] close, int period) {
        double[] series = calculateRsiSeries(close, period);
        for (int i = series.length - 1; i >= 0; i--) {
            if (!Double.isNaN(series[i])) {
                return series[i];
            }
        }
        return Double.NaN;
    }

    /** Calculates SuperTrend indicator series. */
    public SuperTrendResult[] calculateSuperTrendSeries(
            double[] high, double[] low, double[] close, int atrPeriod, double multiplier) {
        int len = close.length;
        SuperTrendResult[] results = new SuperTrendResult[len];

        if (high == null || low == null || close == null || len < atrPeriod + 1 || atrPeriod <= 0) {
            for (int i = 0; i < len; i++) {
                results[i] = SuperTrendResult.of(Double.NaN, Double.NaN, Double.NaN, false);
            }
            return results;
        }

        len = Math.min(len, Math.min(high.length, low.length));

        // 1. Calculate True Range (TR)
        double[] tr = new double[len];
        tr[0] = high[0] - low[0];
        for (int i = 1; i < len; i++) {
            double hl = high[i] - low[i];
            double hc = Math.abs(high[i] - close[i - 1]);
            double lc = Math.abs(low[i] - close[i - 1]);
            tr[i] = Math.max(hl, Math.max(hc, lc));
        }

        // 2. Wilder's Smoothing for ATR
        double[] atr = new double[len];
        double sum = 0.0;
        for (int i = 0; i < atrPeriod; i++) {
            sum += tr[i];
        }
        atr[atrPeriod - 1] = sum / atrPeriod;
        for (int i = atrPeriod; i < len; i++) {
            atr[i] = (atr[i - 1] * (atrPeriod - 1) + tr[i]) / atrPeriod;
        }

        // 3. Basic Bands
        double[] basicUpper = new double[len];
        double[] basicLower = new double[len];
        for (int i = atrPeriod - 1; i < len; i++) {
            double hl2 = (high[i] + low[i]) / 2.0;
            basicUpper[i] = hl2 + multiplier * atr[i];
            basicLower[i] = hl2 - multiplier * atr[i];
        }

        // 4. Final Bands and Trend Persistence
        double[] finalUpper = new double[len];
        double[] finalLower = new double[len];
        double[] superTrend = new double[len];
        boolean[] isBullish = new boolean[len];

        int startIdx = atrPeriod - 1;
        finalUpper[startIdx] = basicUpper[startIdx];
        finalLower[startIdx] = basicLower[startIdx];

        double mid = (high[startIdx] + low[startIdx]) / 2.0;
        if (close[startIdx] <= mid) {
            superTrend[startIdx] = finalUpper[startIdx];
            isBullish[startIdx] = false;
        } else {
            superTrend[startIdx] = finalLower[startIdx];
            isBullish[startIdx] = true;
        }

        for (int i = startIdx + 1; i < len; i++) {
            if (isBullish[i - 1] && finalLower[i - 1] > basicLower[i]) {
                finalLower[i] = finalLower[i - 1];
            } else {
                finalLower[i] = basicLower[i];
            }

            if (!isBullish[i - 1] && finalUpper[i - 1] < basicUpper[i]) {
                finalUpper[i] = finalUpper[i - 1];
            } else {
                finalUpper[i] = basicUpper[i];
            }

            if (!isBullish[i - 1]) {
                if (close[i] > finalUpper[i]) {
                    isBullish[i] = true;
                    superTrend[i] = finalLower[i];
                } else {
                    isBullish[i] = false;
                    superTrend[i] = finalUpper[i];
                }
            } else {
                if (close[i] < finalLower[i]) {
                    isBullish[i] = false;
                    superTrend[i] = finalUpper[i];
                } else {
                    isBullish[i] = true;
                    superTrend[i] = finalLower[i];
                }
            }
        }

        for (int i = 0; i < len; i++) {
            if (i < startIdx) {
                results[i] = SuperTrendResult.of(Double.NaN, Double.NaN, Double.NaN, false);
            } else {
                results[i] =
                        SuperTrendResult.of(
                                superTrend[i], finalUpper[i], finalLower[i], isBullish[i]);
            }
        }

        return results;
    }

    /** Calculates Volume Weighted Average Price (VWAP) series with daily intraday reset. */
    public double[] calculateVwapSeries(List<Candle> candles) {
        int len = candles.size();
        double[] vwap = new double[len];

        double cumulativeTypicalVolume = 0.0;
        double cumulativeVolume = 0.0;
        LocalDate currentDay = null;

        for (int i = 0; i < len; i++) {
            Candle c = candles.get(i);
            LocalDate candleDay = c.timestamp().atZone(IST_ZONE).toLocalDate();

            // Intraday daily reset
            if (currentDay == null || !currentDay.equals(candleDay)) {
                currentDay = candleDay;
                cumulativeTypicalVolume = 0.0;
                cumulativeVolume = 0.0;
            }

            double high = c.high().doubleValue();
            double low = c.low().doubleValue();
            double close = c.close().doubleValue();
            double vol = Math.max(1.0, (double) c.volume());

            double typicalPrice = (high + low + close) / 3.0;
            cumulativeTypicalVolume += (typicalPrice * vol);
            cumulativeVolume += vol;

            vwap[i] = cumulativeTypicalVolume / cumulativeVolume;
        }

        return vwap;
    }
}
