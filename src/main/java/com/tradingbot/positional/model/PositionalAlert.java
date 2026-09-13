package com.tradingbot.positional.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Represents an active Alert Candle waiting for an entry trigger or invalidation.
 *
 * @param alertDate The date on which the alert candle closed inside the Bollinger Band.
 * @param direction "BUY" (for Long CE) or "SELL" (for Short PE).
 * @param highPrice The Normal candle High ($H_{alert}$).
 * @param lowPrice The Normal candle Low ($L_{alert}$).
 * @param bandValue The Bollinger Band value at alert time.
 * @param createdAt The timestamp when alert was detected.
 */
public record PositionalAlert(
        @JsonProperty("alertDate") LocalDate alertDate,
        @JsonProperty("direction") String direction,
        @JsonProperty("highPrice") BigDecimal highPrice,
        @JsonProperty("lowPrice") BigDecimal lowPrice,
        @JsonProperty("bandValue") BigDecimal bandValue,
        @JsonProperty("createdAt") Instant createdAt) {}
