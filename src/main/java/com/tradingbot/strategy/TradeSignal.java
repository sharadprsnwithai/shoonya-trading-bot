package com.tradingbot.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/** Immutable trading signal emitted by an Intraday Strategy. */
public record TradeSignal(
        String strategyId,
        String symbol,
        SignalAction action,
        BigDecimal price,
        BigDecimal stopLoss,
        BigDecimal targetPrice,
        int quantity,
        String reason,
        Instant timestamp,
        Map<String, Object> metadata) {
    public static TradeSignal hold(String strategyId, String symbol, String reason) {
        return new TradeSignal(
                strategyId,
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

    public static TradeSignal of(
            String strategyId,
            String symbol,
            SignalAction action,
            BigDecimal price,
            BigDecimal stopLoss,
            BigDecimal targetPrice,
            int quantity,
            String reason) {
        return new TradeSignal(
                strategyId,
                symbol,
                action,
                price,
                stopLoss,
                targetPrice,
                quantity,
                reason,
                Instant.now(),
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
        return new TradeSignal(
                strategyId,
                symbol,
                action,
                price,
                stopLoss,
                targetPrice,
                quantity,
                reason,
                Instant.now(),
                metadata != null ? metadata : Collections.emptyMap());
    }

    public boolean isActionable() {
        return action != null && action != SignalAction.HOLD;
    }
}
