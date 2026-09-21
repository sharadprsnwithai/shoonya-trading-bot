package com.tradingbot.model.indicator;

/** Result record containing calculated MACD line, signal line, and histogram values. */
public record MacdResult(double macd, double signal, double hist) {

    public static MacdResult empty() {
        return new MacdResult(Double.NaN, Double.NaN, Double.NaN);
    }

    public boolean isBullishCross() {
        return !Double.isNaN(macd) && !Double.isNaN(signal) && macd > signal;
    }

    public boolean isBearishCross() {
        return !Double.isNaN(macd) && !Double.isNaN(signal) && macd < signal;
    }
}
