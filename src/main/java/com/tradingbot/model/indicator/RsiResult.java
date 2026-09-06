package com.tradingbot.model.indicator;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Result representing RSI calculation at a specific bar. */
public record RsiResult(double value, String signal) {
    public static RsiResult of(double value) {
        if (Double.isNaN(value)) {
            return new RsiResult(Double.NaN, "NEUTRAL");
        }
        double roundedVal =
                BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
        String signal;
        if (roundedVal >= 70.0) {
            signal = "OVERBOUGHT";
        } else if (roundedVal <= 30.0) {
            signal = "OVERSOLD";
        } else {
            signal = "NEUTRAL";
        }
        return new RsiResult(roundedVal, signal);
    }
}
