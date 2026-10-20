package com.tradingbot.strategy.monthlyrange.model;

import java.math.BigDecimal;

/**
 * 1x2x1 Broken-Wing Butterfly (Ratio Spread) recommendation: Buy 1x 4OTM, Sell 2x 8OTM, Buy 1x
 * 10OTM in CE (if Bullish) or PE (if Bearish).
 *
 * @param bias directional market bias (BULLISH, BEARISH, NEUTRAL)
 * @param strategyName descriptive strategy label
 * @param atmStrike rounded ATM reference strike
 * @param wing1Strike long wing 1 (4 OTM) strike
 * @param wing1Lots quantity of lots to buy for wing 1 (normally 1)
 * @param sellBodyStrike short body (8 OTM) strike
 * @param sellBodyLots quantity of lots to sell for body (normally 2)
 * @param wing2Strike long wing 2 (10 OTM) strike
 * @param wing2Lots quantity of lots to buy for wing 2 (normally 1)
 * @param optionType CE for bullish, PE for bearish, null for neutral
 * @param actionable true if conviction meets or exceeds actionable threshold
 * @param reason summary rationale or non-actionable explanation
 */
public record BrokenWingButterflyRecommendation(
        MarketBias bias,
        String strategyName,
        BigDecimal atmStrike,
        BigDecimal wing1Strike,
        int wing1Lots,
        BigDecimal sellBodyStrike,
        int sellBodyLots,
        BigDecimal wing2Strike,
        int wing2Lots,
        String optionType,
        boolean actionable,
        String reason) {}
