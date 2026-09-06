package com.tradingbot.model.execution;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Tracks an active or closed Option Selling Spread Position (Short Leg + Protective Hedge Leg +
 * Broker-level SL-L Order).
 */
public record ActiveSpreadPosition(
        String tradeId,
        String strategyId,
        String underlying,
        String optionType,
        BigDecimal strikePrice,
        String shortSymbol,
        String shortOrderId,
        BigDecimal shortEntryPremium,
        String hedgeSymbol,
        String hedgeOrderId,
        BigDecimal hedgeEntryPremium,
        String slOrderId,
        BigDecimal slTriggerPrice,
        BigDecimal slLimitPrice,
        int quantity,
        ExecutionMode mode,
        Instant entryTime,
        boolean isClosed,
        Instant exitTime,
        BigDecimal shortExitPremium,
        BigDecimal hedgeExitPremium,
        BigDecimal realizedPnl,
        String exitReason) {
    public static ActiveSpreadPosition open(
            String tradeId,
            String strategyId,
            String underlying,
            String optionType,
            BigDecimal strikePrice,
            String shortSymbol,
            String shortOrderId,
            BigDecimal shortEntryPremium,
            String hedgeSymbol,
            String hedgeOrderId,
            BigDecimal hedgeEntryPremium,
            String slOrderId,
            BigDecimal slTriggerPrice,
            BigDecimal slLimitPrice,
            int quantity,
            ExecutionMode mode) {
        return new ActiveSpreadPosition(
                tradeId,
                strategyId,
                underlying,
                optionType,
                strikePrice,
                shortSymbol,
                shortOrderId,
                shortEntryPremium,
                hedgeSymbol,
                hedgeOrderId,
                hedgeEntryPremium,
                slOrderId,
                slTriggerPrice,
                slLimitPrice,
                quantity,
                mode,
                Instant.now(),
                false,
                null,
                null,
                null,
                BigDecimal.ZERO,
                null);
    }

    public ActiveSpreadPosition close(
            Instant exitTime,
            BigDecimal shortExitPremium,
            BigDecimal hedgeExitPremium,
            BigDecimal realizedPnl,
            String exitReason) {
        return new ActiveSpreadPosition(
                tradeId,
                strategyId,
                underlying,
                optionType,
                strikePrice,
                shortSymbol,
                shortOrderId,
                shortEntryPremium,
                hedgeSymbol,
                hedgeOrderId,
                hedgeEntryPremium,
                slOrderId,
                slTriggerPrice,
                slLimitPrice,
                quantity,
                mode,
                entryTime,
                true,
                exitTime,
                shortExitPremium,
                hedgeExitPremium,
                realizedPnl,
                exitReason);
    }
}
