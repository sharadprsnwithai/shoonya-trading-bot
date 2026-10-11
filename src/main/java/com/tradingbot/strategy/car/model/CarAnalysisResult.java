package com.tradingbot.strategy.car.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Result of Cumulative Average Reversal (CAR) analysis for an equity stock.
 *
 * <p>The anchor is the day with the highest intraday high in the lookback window ({@code
 * fiftyTwoWeekAnchorHigh}). The cumulative average streak is measured from that anchor day forward
 * on closing prices.
 */
public record CarAnalysisResult(
        String symbol,
        boolean isCarPositive,
        int consecutivePositiveDays,
        /** Highest intraday high of the anchor candle — the day the 52-week high was set. */
        BigDecimal fiftyTwoWeekAnchorHigh,
        LocalDate fiftyTwoWeekHighDate,
        BigDecimal latestClose,
        BigDecimal latestCumulativeAverage,
        int daysSinceAnchor,
        BigDecimal lastWeekHigh,
        /**
         * The minimum number of day-to-day CumAvg increases required for the signal to fire. For a
         * 10-session window this is 9 (Mahesh Kaushik SUMPRODUCT rule). Exposed so callers can
         * display "streak N / required N" without duplicating the off-by-one logic.
         */
        int requiredIncreases) {

    /**
     * 8-arg constructor kept for backward compatibility; defaults {@code lastWeekHigh =
     * latestClose}.
     */
    public CarAnalysisResult(
            String symbol,
            boolean isCarPositive,
            int consecutivePositiveDays,
            BigDecimal fiftyTwoWeekAnchorHigh,
            LocalDate fiftyTwoWeekHighDate,
            BigDecimal latestClose,
            BigDecimal latestCumulativeAverage,
            int daysSinceAnchor) {
        this(
                symbol,
                isCarPositive,
                consecutivePositiveDays,
                fiftyTwoWeekAnchorHigh,
                fiftyTwoWeekHighDate,
                latestClose,
                latestCumulativeAverage,
                daysSinceAnchor,
                latestClose,
                0);
    }
}
