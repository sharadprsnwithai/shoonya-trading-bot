package com.tradingbot.model.indicator;

import java.time.Instant;

/** Combined technical indicator snapshot for a single candle bar. */
public record IndicatorSnapshot(
        Instant timestamp,
        String formattedTime,
        double open,
        double high,
        double low,
        double close,
        long volume,
        Double supertrend,
        String supertrendSignal,
        Double rsi,
        String rsiSignal,
        Double vwap,
        Double vwapDiff) {}
