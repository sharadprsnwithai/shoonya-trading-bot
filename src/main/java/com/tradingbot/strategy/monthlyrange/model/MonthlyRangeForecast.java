package com.tradingbot.strategy.monthlyrange.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Monthly Range Forecast and option strike boundaries for a single symbol, fused with GARCH,
 * Implied Volatility (IV), Institutional Open Interest (OI) walls, and Directional Probability.
 */
public record MonthlyRangeForecast(
        String symbol,
        BigDecimal spotPrice,
        double monthlyVolPct,
        double annualizedVolPct,
        BigDecimal lower1Sd,
        BigDecimal upper1Sd,
        BigDecimal lower2Sd,
        BigDecimal upper2Sd,
        BigDecimal safePeStrike,
        BigDecimal safeCeStrike,
        BigDecimal strikeStep,
        double hv30AnnualizedPct,
        BigDecimal atr22,
        int horizonTradingDays,
        boolean isEventMonth,
        String eventReason,
        double confidenceMultiplier,
        BigDecimal atmStraddleMove,
        BigDecimal maxCallOiStrike,
        BigDecimal maxPutOiStrike,
        Instant forecastTime,
        MonthlyDirectionalScore directionalScore) {

    public MonthlyRangeForecast(
            String symbol,
            BigDecimal spotPrice,
            double monthlyVolPct,
            double annualizedVolPct,
            BigDecimal lower1Sd,
            BigDecimal upper1Sd,
            BigDecimal lower2Sd,
            BigDecimal upper2Sd,
            BigDecimal safePeStrike,
            BigDecimal safeCeStrike,
            BigDecimal strikeStep,
            double hv30AnnualizedPct,
            BigDecimal atr22,
            int horizonTradingDays,
            boolean isEventMonth,
            String eventReason,
            double confidenceMultiplier,
            BigDecimal atmStraddleMove,
            BigDecimal maxCallOiStrike,
            BigDecimal maxPutOiStrike,
            Instant forecastTime) {
        this(
                symbol,
                spotPrice,
                monthlyVolPct,
                annualizedVolPct,
                lower1Sd,
                upper1Sd,
                lower2Sd,
                upper2Sd,
                safePeStrike,
                safeCeStrike,
                strikeStep,
                hv30AnnualizedPct,
                atr22,
                horizonTradingDays,
                isEventMonth,
                eventReason,
                confidenceMultiplier,
                atmStraddleMove,
                maxCallOiStrike,
                maxPutOiStrike,
                forecastTime,
                null);
    }
}
