package com.tradingbot.strategy.car.gtt;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Snapshot of a single GTT trigger as reported by the broker.
 *
 * @param triggerId broker-side trigger identifier
 * @param symbol NSE trading symbol of the trigger condition
 * @param transactionType BUY or SELL (from the first order in the trigger)
 * @param triggerValue single trigger price of the condition
 * @param quantity quantity of the first order in the trigger
 * @param status raw broker status string (active, triggered, cancelled, ...)
 * @param createdAt broker-side creation timestamp, used to order duplicates deterministically
 */
public record LiveGttTrigger(
        String triggerId,
        String symbol,
        String transactionType,
        BigDecimal triggerValue,
        int quantity,
        String status,
        Instant createdAt) {}
