package com.tradingbot.strategy.monthlyrange.model;

import java.time.Instant;
import java.util.List;

/** Aggregated batch report containing forecasts for all configured symbols. */
public record MonthlyRangeReport(
        Instant generatedAt,
        String cycle,
        List<MonthlyRangeForecast> forecasts,
        String summaryMessage) {}
