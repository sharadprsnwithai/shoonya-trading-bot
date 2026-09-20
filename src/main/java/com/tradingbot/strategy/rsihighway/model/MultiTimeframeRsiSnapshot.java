package com.tradingbot.strategy.rsihighway.model;

import java.time.Instant;
import java.util.Optional;

/**
 * Immutable snapshot encapsulating multi-timeframe RSI values, ATR, and detected price action
 * pattern.
 */
public record MultiTimeframeRsiSnapshot(
        String symbol,
        double monthlyRsi,
        double weeklyRsi,
        double dailyRsi,
        double dailyAtr,
        double currentPrice,
        double signalCandleHigh,
        double signalCandleLow,
        Optional<PriceActionPattern> pattern,
        boolean isHighwayCandidate, // Monthly RSI >= 60 && Weekly RSI >= 60
        boolean isDailySetupValid, // Daily RSI bounce/cross + PA pattern
        Instant timestamp) {}
