package com.tradingbot.model.strategy;

import java.math.BigDecimal;

/** Encapsulates the multi-timeframe trend metrics across Weekly, Daily, and Hourly horizons. */
public record MtfTrendStatus(
        String symbol,
        double currentPrice,
        boolean weeklyUptrend,
        double weeklyEma20,
        double weeklySuperTrend,
        boolean dailyUptrend,
        double dailyEma50,
        double dailySuperTrend,
        double dailyRsi,
        boolean hourlyUptrend,
        double hourlyEma50,
        double hourlySuperTrend,
        double hourlyAdx,
        boolean isFullConfluence,
        BigDecimal atmCallStrike,
        double trailingStopLoss,
        boolean isFreshHourlyTrigger) {

    public String getTrendSummary() {
        return String.format(
                "W:%s | D:%s | H:%s",
                weeklyUptrend ? "🟢" : "🔴",
                dailyUptrend ? "🟢" : "🔴",
                hourlyUptrend ? "🟢" : "🔴");
    }
}
