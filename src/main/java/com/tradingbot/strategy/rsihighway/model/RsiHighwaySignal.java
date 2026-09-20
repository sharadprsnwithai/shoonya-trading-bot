package com.tradingbot.strategy.rsihighway.model;

import java.time.Instant;
import java.util.Optional;

/** Signal record representing an entry or exit instruction produced by the strategy engine. */
public record RsiHighwaySignal(
        String symbol,
        RsiHighwaySignalType signalType,
        double triggerPrice,
        double initialSlPrice,
        int trancheNumber,
        double monthlyRsi,
        double weeklyRsi,
        double dailyRsi,
        double dailyAtr,
        PriceActionPattern pattern,
        String reason,
        Instant generatedAt) {
    public Optional<PriceActionPattern> optionalPattern() {
        return Optional.ofNullable(pattern);
    }
}
