package com.tradingbot.strategy.monthlyrange.model;

/** Represents the confidence conviction for the directional tilt. */
public enum ConvictionLevel {
    STRONG,
    MODERATE,
    NEUTRAL;

    /**
     * Resolves the conviction level from a directional probability [0, 100]. A score of 70% bullish
     * (or 30% bullish, meaning 70% bearish) represents a deviation of 20% from 50%.
     *
     * @param probabilityPct the directional probability (bullish or dominant)
     * @param strongThreshold the minimum deviation percentage or threshold for strong conviction
     *     (default: 65.0)
     * @return STRONG if dominant side >= threshold, MODERATE if >= 55.0%, else NEUTRAL
     */
    public static ConvictionLevel fromProbability(double probabilityPct, double strongThreshold) {
        double dominant = Math.max(probabilityPct, 100.0 - probabilityPct);
        if (dominant >= strongThreshold) {
            return STRONG;
        } else if (dominant >= 55.0) {
            return MODERATE;
        }
        return NEUTRAL;
    }
}
