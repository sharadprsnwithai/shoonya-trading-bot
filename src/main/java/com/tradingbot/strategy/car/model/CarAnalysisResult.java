package com.tradingbot.strategy.car.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Result of Cumulative Average Reversal (CAR) analysis for an equity stock. */
public record CarAnalysisResult(
        String symbol,
        boolean isCarPositive,
        int consecutivePositiveDays,
        BigDecimal fiftyTwoWeekHighClose,
        LocalDate fiftyTwoWeekHighDate,
        BigDecimal latestClose,
        BigDecimal latestCumulativeAverage,
        int daysSinceAnchor,
        BigDecimal lastWeekHigh) {

    public CarAnalysisResult(
            String symbol,
            boolean isCarPositive,
            int consecutivePositiveDays,
            BigDecimal fiftyTwoWeekHighClose,
            LocalDate fiftyTwoWeekHighDate,
            BigDecimal latestClose,
            BigDecimal latestCumulativeAverage,
            int daysSinceAnchor) {
        this(
                symbol,
                isCarPositive,
                consecutivePositiveDays,
                fiftyTwoWeekHighClose,
                fiftyTwoWeekHighDate,
                latestClose,
                latestCumulativeAverage,
                daysSinceAnchor,
                latestClose);
    }
}
