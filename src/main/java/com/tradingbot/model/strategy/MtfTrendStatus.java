package com.tradingbot.model.strategy;

import java.math.BigDecimal;

/**
 * Encapsulates the multi-timeframe trend metrics across Weekly, Daily, and Hourly horizons for both
 * Gainer (Uptrend / Bullish) and Loser (Downtrend / Bearish) setups.
 */
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
        boolean isFreshHourlyTrigger,
        boolean isBearishConfluence,
        boolean isFreshHourlyBearishTrigger,
        BigDecimal atmPutStrike) {

    /** Backwards-compatible constructor for 17 parameters. */
    public MtfTrendStatus(
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
        this(
                symbol,
                currentPrice,
                weeklyUptrend,
                weeklyEma20,
                weeklySuperTrend,
                dailyUptrend,
                dailyEma50,
                dailySuperTrend,
                dailyRsi,
                hourlyUptrend,
                hourlyEma50,
                hourlySuperTrend,
                hourlyAdx,
                isFullConfluence,
                atmCallStrike,
                trailingStopLoss,
                isFreshHourlyTrigger,
                false,
                false,
                atmCallStrike);
    }

    public String getTrendSummary() {
        return String.format(
                "W:%s | D:%s | H:%s",
                weeklyUptrend ? "🟢" : "🔴",
                dailyUptrend ? "🟢" : "🔴",
                hourlyUptrend ? "🟢" : "🔴");
    }

    public boolean isUptrendConfluence() {
        return isFullConfluence;
    }

    public boolean isDowntrendConfluence() {
        return isBearishConfluence;
    }
}
