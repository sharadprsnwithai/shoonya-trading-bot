package com.tradingbot.strategy.monthlyrange.model;

import java.math.BigDecimal;

/**
 * Actionable option selling strategy recommendation derived from GARCH 1.0σ expected move,
 * directional factor bias, and institutional Open Interest support/resistance walls.
 *
 * @param strategyName human readable strategy name (e.g. "Bull Put Spread", "Bear Call Spread",
 *     "Iron Condor")
 * @param actionSummary specific strike orders (e.g. "Sell PE ₹22,350 | Buy PE ₹22,150")
 * @param shortStrike primary short strike
 * @param longStrike primary protective long strike
 * @param secondShortStrike secondary short strike (for two-sided structures like Iron Condor, or
 *     null)
 * @param secondLongStrike secondary long strike (for two-sided structures like Iron Condor, or
 *     null)
 * @param maxPutOiStrike institutional Put open interest support wall (or null)
 * @param maxCallOiStrike institutional Call open interest resistance wall (or null)
 * @param edgeSummary concise explanation of statistical edge (e.g. "1.0σ floor + Max Put Wall
 *     confluence")
 * @param exitRule trade management and profit-taking rule (e.g. "Book profit at 60% decay")
 */
public record WeeklyStrategyRecommendation(
        String strategyName,
        String actionSummary,
        BigDecimal shortStrike,
        BigDecimal longStrike,
        BigDecimal secondShortStrike,
        BigDecimal secondLongStrike,
        BigDecimal maxPutOiStrike,
        BigDecimal maxCallOiStrike,
        String edgeSummary,
        String exitRule) {}
