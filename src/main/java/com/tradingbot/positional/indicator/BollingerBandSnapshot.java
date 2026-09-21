package com.tradingbot.positional.indicator;

import java.math.BigDecimal;
import java.time.Instant;

/** Snapshot combining normal candle, Heikin-Ashi values, and Bollinger Bands (20, 2). */
public record BollingerBandSnapshot(
        Instant timestamp,
        String formattedDate,
        BigDecimal normalOpen,
        BigDecimal normalHigh,
        BigDecimal normalLow,
        BigDecimal normalClose,
        BigDecimal haOpen,
        BigDecimal haHigh,
        BigDecimal haLow,
        BigDecimal haClose,
        BigDecimal sma20,
        BigDecimal bbUpper,
        BigDecimal bbLower,
        BigDecimal bandwidth) {}
