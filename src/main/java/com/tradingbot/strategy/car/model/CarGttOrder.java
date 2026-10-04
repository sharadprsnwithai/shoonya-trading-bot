package com.tradingbot.strategy.car.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record CarGttOrder(
        String gttId,
        String broker,
        String symbol,
        GttOrderType type,
        BigDecimal triggerPrice,
        BigDecimal limitPrice,
        int quantity,
        GttStatus status,
        LocalDate weekStartDate,
        Instant createdAt) {

    /** Returns a copy of this order carrying the broker-side id returned by the gateway. */
    public CarGttOrder withId(String id) {
        return new CarGttOrder(
                id,
                broker,
                symbol,
                type,
                triggerPrice,
                limitPrice,
                quantity,
                status,
                weekStartDate,
                createdAt);
    }
}
