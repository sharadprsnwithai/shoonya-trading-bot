package com.tradingbot.strategy.driftvwap.model;

import java.math.BigDecimal;
import java.time.Instant;

/** 15-minute macro trend state containing price vs VWAP, VWAP slope, and 1-hour momentum. */
public record DriftVwapTrendState(
        BigDecimal close15m,
        BigDecimal vwap15m,
        BigDecimal prevVwap15m,
        double momentum1hrPct,
        DriftDirection direction,
        Instant evaluationTime) {

    public static DriftVwapTrendState neutral() {
        return new DriftVwapTrendState(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0.0,
                DriftDirection.NEUTRAL,
                Instant.now());
    }
}
