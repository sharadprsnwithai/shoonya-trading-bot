package com.tradingbot.positional.indicator;

import java.math.BigDecimal;
import java.time.Instant;

/** Represents a Heikin-Ashi candlestick. */
public record HeikinAshiCandle(
        Instant timestamp,
        String formattedTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume) {}
