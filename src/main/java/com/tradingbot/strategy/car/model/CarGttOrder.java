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
        Instant createdAt) {}
