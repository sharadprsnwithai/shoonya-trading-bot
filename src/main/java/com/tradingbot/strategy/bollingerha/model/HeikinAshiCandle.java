package com.tradingbot.strategy.bollingerha.model;

import java.math.BigDecimal;
import java.time.Instant;

/** Immutable representation of a Heikin-Ashi transformed candle. */
public record HeikinAshiCandle(
        Instant timestamp,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        boolean isGreen,
        long volume) {

    public boolean isRed() {
        return !isGreen;
    }
}
