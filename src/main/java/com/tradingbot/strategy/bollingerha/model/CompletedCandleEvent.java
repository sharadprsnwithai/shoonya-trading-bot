package com.tradingbot.strategy.bollingerha.model;

import com.tradingbot.model.Candle;
import java.time.Instant;

/** Event fired when a 1-minute candle completes for an option contract. */
public record CompletedCandleEvent(
        String token,
        String symbol,
        String optionType, // "CE" or "PE"
        Candle candle,
        Instant completedAt) {}
