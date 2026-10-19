package com.tradingbot.strategy.monthlyrange.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated multi-symbol multi-horizon range report produced by the weekly range routine.
 *
 * @param generatedAt when the report was produced
 * @param referenceSession last completed session the forecasts are struck from
 * @param expirySession contractual expiry the horizon runs through
 * @param horizonSessions trading sessions covered by the horizon
 * @param forecasts per-symbol forecasts
 * @param summary one line human readable completion summary
 */
public record GarchRangeReport(
        Instant generatedAt,
        LocalDate referenceSession,
        LocalDate expirySession,
        int horizonSessions,
        List<GarchRangeForecast> forecasts,
        String summary) {

    public GarchRangeReport {
        forecasts = forecasts == null ? null : new ArrayList<>(forecasts);
    }

    @Override
    public List<GarchRangeForecast> forecasts() {
        return forecasts == null ? null : new ArrayList<>(forecasts);
    }
}
