package com.tradingbot.model.indicator;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Result representing Volume Weighted Average Price (VWAP) calculation at a specific bar. */
public record VwapResult(double vwap, double difference) {
    public static VwapResult of(double vwap, double closePrice) {
        if (Double.isNaN(vwap)) {
            return new VwapResult(Double.NaN, 0.0);
        }
        double roundedVwap =
                BigDecimal.valueOf(vwap).setScale(2, RoundingMode.HALF_UP).doubleValue();
        double diff =
                BigDecimal.valueOf(closePrice - vwap)
                        .setScale(2, RoundingMode.HALF_UP)
                        .doubleValue();
        return new VwapResult(roundedVwap, diff);
    }
}
