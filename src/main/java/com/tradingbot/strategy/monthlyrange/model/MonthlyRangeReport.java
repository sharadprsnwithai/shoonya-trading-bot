package com.tradingbot.strategy.monthlyrange.model;

import java.time.Instant;
import java.util.List;

/** Aggregated batch report containing forecasts for all configured symbols and top rankings. */
public record MonthlyRangeReport(
        Instant generatedAt,
        String cycle,
        List<MonthlyRangeForecast> forecasts,
        String summaryMessage,
        MonthlyUniverseRanking ranking) {

    public MonthlyRangeReport {
        forecasts = forecasts != null ? List.copyOf(forecasts) : List.of();
    }

    public MonthlyRangeReport(
            Instant generatedAt,
            String cycle,
            List<MonthlyRangeForecast> forecasts,
            String summaryMessage) {
        this(generatedAt, cycle, forecasts, summaryMessage, null);
    }
}
