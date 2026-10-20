package com.tradingbot.strategy.monthlyrange.model;

/** Represents the directional tilt for an underlying over the monthly horizon. */
public enum MarketBias {
    BULLISH,
    BEARISH,
    NEUTRAL;

    /**
     * Resolves market bias from a percentage probability of upward movement [0, 100].
     *
     * @param bullishProbabilityPct probability of upward move
     * @return BULLISH if > 54.0%, BEARISH if < 46.0%, else NEUTRAL
     */
    public static MarketBias fromBullishProbability(double bullishProbabilityPct) {
        if (bullishProbabilityPct > 54.0) {
            return BULLISH;
        } else if (bullishProbabilityPct < 46.0) {
            return BEARISH;
        }
        return NEUTRAL;
    }
}
