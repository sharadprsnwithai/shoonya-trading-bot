package com.tradingbot.model.execution;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Tracks an active or closed Long Option position in the Triple SuperTrend + RSI Option Buying
 * strategy.
 */
public record TripleSuperTrendPosition(
        String tradeId,
        String symbol,
        String optionType, // "CE" or "PE"
        BigDecimal strike,
        String expiry,
        BigDecimal entryPremium,
        BigDecimal underlyingEntryPrice,
        int quantity,
        Instant entryTime,
        boolean isClosed,
        Instant exitTime,
        BigDecimal exitPremium,
        BigDecimal realizedPnl,
        String exitReason,
        double entryFastSt,
        double entryMediumSt,
        double entrySlowSt,
        double entryRsi,
        BigDecimal hedgeStrike,
        BigDecimal hedgePremium,
        BigDecimal netCredit,
        BigDecimal maxRisk) {

    public static TripleSuperTrendPosition open(
            String tradeId,
            String symbol,
            String optionType,
            BigDecimal strike,
            String expiry,
            BigDecimal entryPremium,
            BigDecimal underlyingEntryPrice,
            int quantity,
            double fastSt,
            double mediumSt,
            double slowSt,
            double rsi) {
        return open(
                tradeId,
                symbol,
                optionType,
                strike,
                expiry,
                entryPremium,
                underlyingEntryPrice,
                quantity,
                fastSt,
                mediumSt,
                slowSt,
                rsi,
                null,
                BigDecimal.ZERO,
                entryPremium,
                BigDecimal.ZERO);
    }

    public static TripleSuperTrendPosition open(
            String tradeId,
            String symbol,
            String optionType,
            BigDecimal strike,
            String expiry,
            BigDecimal entryPremium,
            BigDecimal underlyingEntryPrice,
            int quantity,
            Instant entryTime,
            double fastSt,
            double mediumSt,
            double slowSt,
            double rsi,
            BigDecimal hedgeStrike,
            BigDecimal hedgePremium,
            BigDecimal netCredit,
            BigDecimal maxRisk) {
        return new TripleSuperTrendPosition(
                tradeId,
                symbol,
                optionType,
                strike,
                expiry,
                entryPremium,
                underlyingEntryPrice,
                quantity,
                entryTime != null ? entryTime : Instant.now(),
                false,
                null,
                null,
                BigDecimal.ZERO,
                null,
                fastSt,
                mediumSt,
                slowSt,
                rsi,
                hedgeStrike,
                hedgePremium != null ? hedgePremium : BigDecimal.ZERO,
                netCredit != null ? netCredit : entryPremium,
                maxRisk != null ? maxRisk : BigDecimal.ZERO);
    }

    public static TripleSuperTrendPosition open(
            String tradeId,
            String symbol,
            String optionType,
            BigDecimal strike,
            String expiry,
            BigDecimal entryPremium,
            BigDecimal underlyingEntryPrice,
            int quantity,
            double fastSt,
            double mediumSt,
            double slowSt,
            double rsi,
            BigDecimal hedgeStrike,
            BigDecimal hedgePremium,
            BigDecimal netCredit,
            BigDecimal maxRisk) {
        return open(
                tradeId,
                symbol,
                optionType,
                strike,
                expiry,
                entryPremium,
                underlyingEntryPrice,
                quantity,
                Instant.now(),
                fastSt,
                mediumSt,
                slowSt,
                rsi,
                hedgeStrike,
                hedgePremium,
                netCredit,
                maxRisk);
    }

    public boolean isCreditSpread() {
        return hedgeStrike != null && hedgeStrike.compareTo(BigDecimal.ZERO) > 0;
    }

    public TripleSuperTrendPosition close(BigDecimal exitPremium, String reason) {
        return close(exitPremium, reason, false);
    }

    public TripleSuperTrendPosition close(
            BigDecimal exitPremium, String reason, boolean isSelling) {
        BigDecimal pnl = BigDecimal.ZERO;
        if (exitPremium != null
                && entryPremium != null
                && entryPremium.compareTo(BigDecimal.ZERO) > 0) {
            if (isCreditSpread() && isSelling) {
                // For Credit Spread: Net Credit collected vs Exit spread cost
                // If short leg decayed, hedge also decayed, max loss is capped at maxRisk
                BigDecimal points = netCredit.subtract(exitPremium);
                if (maxRisk != null && maxRisk.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal maxLossPoints = maxRisk.negate();
                    if (points.compareTo(maxLossPoints) < 0) {
                        points = maxLossPoints;
                    }
                }
                pnl =
                        points.multiply(BigDecimal.valueOf(quantity))
                                .setScale(2, RoundingMode.HALF_UP);
            } else {
                BigDecimal points =
                        isSelling
                                ? entryPremium.subtract(exitPremium)
                                : exitPremium.subtract(entryPremium);
                pnl =
                        points.multiply(BigDecimal.valueOf(quantity))
                                .setScale(2, RoundingMode.HALF_UP);
            }
        }
        return new TripleSuperTrendPosition(
                this.tradeId,
                this.symbol,
                this.optionType,
                this.strike,
                this.expiry,
                this.entryPremium,
                this.underlyingEntryPrice,
                this.quantity,
                this.entryTime,
                true,
                Instant.now(),
                exitPremium,
                pnl,
                reason,
                this.entryFastSt,
                this.entryMediumSt,
                this.entrySlowSt,
                this.entryRsi,
                this.hedgeStrike,
                this.hedgePremium,
                this.netCredit,
                this.maxRisk);
    }
}
