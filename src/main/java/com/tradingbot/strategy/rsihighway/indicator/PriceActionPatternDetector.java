package com.tradingbot.strategy.rsihighway.indicator;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.rsihighway.model.PriceActionPattern;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Detector for candlestick confirmation patterns and RSI 50 bounce/crossover dynamics. */
@Component
public class PriceActionPatternDetector {

    /**
     * Detects if the latest completed candle satisfies one of the supported bullish price action
     * patterns.
     *
     * @param candles Chronological list of daily candles (must contain at least 2 candles)
     * @param dailyAtr 14-period daily ATR value for range expansion checks
     * @return Optional containing detected PriceActionPattern or empty
     */
    public Optional<PriceActionPattern> detectPattern(List<Candle> candles, double dailyAtr) {
        if (candles == null || candles.size() < 2) {
            return Optional.empty();
        }

        int size = candles.size();
        Candle curr = candles.get(size - 1);
        Candle prev = candles.get(size - 2);

        double currOpen = curr.open().doubleValue();
        double currClose = curr.close().doubleValue();
        double currHigh = curr.high().doubleValue();
        double currLow = curr.low().doubleValue();

        double prevOpen = prev.open().doubleValue();
        double prevClose = prev.close().doubleValue();

        double body = Math.abs(currClose - currOpen);
        double range = currHigh - currLow;
        boolean isGreen = currClose > currOpen;

        // 1. Bullish Engulfing
        if (isGreen && prevClose < prevOpen) {
            if (currOpen <= prevClose + 0.001 && currClose > prevOpen) {
                return Optional.of(PriceActionPattern.BULLISH_ENGULFING);
            }
        }

        // 2. Hammer / Bullish Pinbar
        if (range > 0) {
            double lowerWick = Math.min(currOpen, currClose) - currLow;
            double upperWick = currHigh - Math.max(currOpen, currClose);
            double effectiveBody = Math.max(body, 0.01);

            if (lowerWick >= 2.0 * effectiveBody && upperWick <= 0.6 * effectiveBody) {
                return Optional.of(PriceActionPattern.HAMMER);
            }
        }

        // 3. Strong Momentum Expansion Green Candle (Marubozu / Expansion)
        if (isGreen && dailyAtr > 0 && range >= 1.2 * dailyAtr) {
            double positionInRange = (currClose - currLow) / range;
            if (positionInRange >= 0.75) {
                return Optional.of(PriceActionPattern.MOMENTUM_EXPANSION);
            }
        }

        // 4. Horizontal Consolidation Breakout (past 5 candles)
        if (size >= 6 && isGreen) {
            double maxPriorHigh = 0.0;
            for (int i = size - 6; i < size - 1; i++) {
                maxPriorHigh = Math.max(maxPriorHigh, candles.get(i).high().doubleValue());
            }
            if (currClose > maxPriorHigh) {
                return Optional.of(PriceActionPattern.HORIZONTAL_BREAKOUT);
            }
        }

        return Optional.empty();
    }

    /**
     * Checks if the recent Daily RSI series exhibits an RSI 50 bounce or a fresh RSI 50 crossover.
     *
     * @param rsiSeries Chronological list of Daily RSI(14) values
     * @return true if an RSI 50 bounce or crossover is confirmed
     */
    public boolean isRsi50BounceOrCross(List<Double> rsiSeries) {
        return isRsi50BounceOrCross(rsiSeries, 48.0, 55.0, 50.0, 65.0);
    }

    /**
     * Checks if the recent Daily RSI series exhibits an RSI 50 bounce or crossover with custom
     * bounds.
     *
     * @param rsiSeries Chronological list of Daily RSI(14) values
     * @param lowerBound Lower bound of the pullback dip zone (e.g. 48.0)
     * @param upperBound Upper bound of the pullback dip zone (e.g. 55.0)
     * @param minTrigger Minimum RSI level to trigger (e.g. 50.0)
     * @param maxUpperLimit Maximum allowed RSI on entry to avoid overbought chase (e.g. 65.0)
     * @return true if an RSI bounce or crossover is confirmed
     */
    public boolean isRsi50BounceOrCross(
            List<Double> rsiSeries,
            double lowerBound,
            double upperBound,
            double minTrigger,
            double maxUpperLimit) {
        if (rsiSeries == null || rsiSeries.size() < 2) {
            return false;
        }

        int size = rsiSeries.size();
        double currRsi = rsiSeries.get(size - 1);
        double prevRsi = rsiSeries.get(size - 2);

        if (Double.isNaN(currRsi) || Double.isNaN(prevRsi)) {
            return false;
        }

        // Must not be heavily overbought on entry (capped at maxUpperLimit)
        if (currRsi > maxUpperLimit) {
            return false;
        }

        // Setup A: RSI 50 Pullback & Bounce
        // Previous RSI dipped into [lowerBound, upperBound] and current RSI turns up and is >=
        // minTrigger
        if (prevRsi >= lowerBound
                && prevRsi <= upperBound
                && currRsi > prevRsi
                && currRsi >= minTrigger) {
            return true;
        }

        // Setup B: RSI 50 Fresh Crossover
        // Previous RSI was < minTrigger and current RSI crossed above minTrigger
        if (prevRsi < minTrigger && currRsi >= minTrigger && currRsi > prevRsi) {
            return true;
        }

        // 2-bar lag bounce check (e.g. dipped 2 bars ago)
        if (size >= 3) {
            double prevPrevRsi = rsiSeries.get(size - 3);
            if (!Double.isNaN(prevPrevRsi)
                    && prevPrevRsi >= lowerBound
                    && prevPrevRsi <= upperBound
                    && currRsi >= minTrigger
                    && currRsi > prevRsi) {
                return true;
            }
        }

        return false;
    }
}
