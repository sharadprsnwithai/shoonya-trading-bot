package com.tradingbot.strategy.monthlyrange.model;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Monthly directional probability score computed across macro trend, momentum, GJR-GARCH leverage,
 * and option open interest.
 *
 * @param symbol ticker symbol
 * @param spotPrice reference spot price
 * @param bullishProbabilityPct estimated probability of upward movement [0, 100]
 * @param bearishProbabilityPct estimated probability of downward movement [0, 100]
 * @param bias directional bias (BULLISH, BEARISH, NEUTRAL)
 * @param conviction conviction level (STRONG, MODERATE, NEUTRAL)
 * @param factorScores breakdown of individual component points
 * @param butterflyRecommendation recommended 1x2x1 broken-wing butterfly legs
 */
public record MonthlyDirectionalScore(
        String symbol,
        BigDecimal spotPrice,
        double bullishProbabilityPct,
        double bearishProbabilityPct,
        MarketBias bias,
        ConvictionLevel conviction,
        Map<String, Double> factorScores,
        BrokenWingButterflyRecommendation butterflyRecommendation) {

    public MonthlyDirectionalScore {
        factorScores = factorScores != null ? Map.copyOf(factorScores) : Map.of();
    }
}
