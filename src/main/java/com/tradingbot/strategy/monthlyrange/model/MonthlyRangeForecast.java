package com.tradingbot.strategy.monthlyrange.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Monthly Range Forecast and option strike boundaries for a single symbol.
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
        Instant forecastTime) {}
