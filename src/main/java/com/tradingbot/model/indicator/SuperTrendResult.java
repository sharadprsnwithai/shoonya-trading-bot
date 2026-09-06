package com.tradingbot.model.indicator;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Result representing SuperTrend indicator calculation at a specific bar. */
public record SuperTrendResult(
        double value, double upperBand, double lowerBand, boolean isBullish, String trend) {
    public static SuperTrendResult of(
            double value, double upperBand, double lowerBand, boolean isBullish) {
        double roundedVal =
                Double.isNaN(value)
                        ? Double.NaN
                        : BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
        double roundedUpper =
                Double.isNaN(upperBand)
                        ? Double.NaN
                        : BigDecimal.valueOf(upperBand)
                                .setScale(2, RoundingMode.HALF_UP)
                                .doubleValue();
        double roundedLower =
                Double.isNaN(lowerBand)
                        ? Double.NaN
                        : BigDecimal.valueOf(lowerBand)
                                .setScale(2, RoundingMode.HALF_UP)
                                .doubleValue();
        return new SuperTrendResult(
                roundedVal,
                roundedUpper,
                roundedLower,
                isBullish,
                isBullish ? "BULLISH" : "BEARISH");
    }
}
