package com.tradingbot.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/** Immutable trading signal emitted by strategy engines. */
public record TradeSignal(
        String signalId,
        String strategyId,
        String underlyingSymbol,
        String tradingSymbol,
        SignalAction action,
        BigDecimal price,
        BigDecimal stopLoss,
        BigDecimal targetPrice,
        int baseQuantity,
        String reason,
        Instant timestamp,
        Map<String, Object> metadata) {

    public static TradeSignal of(
            String strategyId,
            String underlyingSymbol,
            String tradingSymbol,
            SignalAction action,
            BigDecimal price,
            BigDecimal stopLoss,
            BigDecimal targetPrice,
            int baseQuantity,
            String reason,
            Map<String, Object> metadata) {
        String id = "SIG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return new TradeSignal(
                id,
                strategyId,
                underlyingSymbol,
                tradingSymbol,
                action,
                price,
                stopLoss,
                targetPrice,
                baseQuantity,
                reason,
                Instant.now(),
                metadata != null ? Collections.unmodifiableMap(metadata) : Collections.emptyMap());
    }

    public static TradeSignal of(
            String strategyId,
            String symbol,
            SignalAction action,
            BigDecimal price,
            BigDecimal stopLoss,
            BigDecimal targetPrice,
            int quantity,
            String reason) {
        return of(
                strategyId,
                symbol,
                symbol,
                action,
                price,
                stopLoss,
                targetPrice,
                quantity,
                reason,
                Collections.emptyMap());
    }

    public static TradeSignal of(
            String strategyId,
            String symbol,
            SignalAction action,
            BigDecimal price,
            BigDecimal stopLoss,
            BigDecimal targetPrice,
            int quantity,
            String reason,
            Map<String, Object> metadata) {
        return of(
                strategyId,
                symbol,
                symbol,
                action,
                price,
                stopLoss,
                targetPrice,
                quantity,
                reason,
                metadata);
    }

    public static TradeSignal hold(String strategyId, String symbol, String reason) {
        return new TradeSignal(
                "SIG-HOLD",
                strategyId,
                symbol,
                symbol,
                SignalAction.HOLD,
                BigDecimal.ZERO,
                null,
                null,
                0,
                reason,
                Instant.now(),
                Collections.emptyMap());
    }

    public boolean isActionable() {
        return action != null && action != SignalAction.HOLD;
    }
}
