package com.tradingbot.strategy.commodity.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** Represents an active or closed trade position for the Commodity VWAP breakout strategy. */
public record CommodityTradePosition(
        String symbol,
        String side, // LONG or SHORT
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal targetPrice,
        BigDecimal risk,
        int quantity,
        Instant entryTime,
        BigDecimal exitPrice,
        Instant exitTime,
        String exitReason, // TARGET_HIT, STOP_LOSS_HIT, EOD_SQUARE_OFF, MANUAL_EXIT
        BigDecimal pnl,
        boolean isClosed) {

    /** Factory to initialize an active LONG trade position with 1:2 RR. */
    public static CommodityTradePosition createLong(
            String symbol,
            BigDecimal entryPrice,
            BigDecimal stopLoss,
            BigDecimal riskRewardRatio,
            int quantity,
            Instant entryTime) {
        BigDecimal risk = entryPrice.subtract(stopLoss).abs();
        if (risk.compareTo(BigDecimal.ZERO) <= 0) {
            risk = new BigDecimal("1.00"); // Minimum floor fallback
        }
        BigDecimal rr = riskRewardRatio != null ? riskRewardRatio : new BigDecimal("2.0");
        BigDecimal targetPrice =
                entryPrice.add(risk.multiply(rr)).setScale(2, RoundingMode.HALF_UP);

        return new CommodityTradePosition(
                symbol,
                "LONG",
                entryPrice.setScale(2, RoundingMode.HALF_UP),
                stopLoss.setScale(2, RoundingMode.HALF_UP),
                targetPrice,
                risk.setScale(2, RoundingMode.HALF_UP),
                quantity,
                entryTime,
                null,
                null,
                null,
                BigDecimal.ZERO,
                false);
    }

    /** Factory to initialize an active SHORT trade position with 1:2 RR. */
    public static CommodityTradePosition createShort(
            String symbol,
            BigDecimal entryPrice,
            BigDecimal stopLoss,
            BigDecimal riskRewardRatio,
            int quantity,
            Instant entryTime) {
        BigDecimal risk = stopLoss.subtract(entryPrice).abs();
        if (risk.compareTo(BigDecimal.ZERO) <= 0) {
            risk = new BigDecimal("1.00"); // Minimum floor fallback
        }
        BigDecimal rr = riskRewardRatio != null ? riskRewardRatio : new BigDecimal("2.0");
        BigDecimal targetPrice =
                entryPrice.subtract(risk.multiply(rr)).setScale(2, RoundingMode.HALF_UP);

        return new CommodityTradePosition(
                symbol,
                "SHORT",
                entryPrice.setScale(2, RoundingMode.HALF_UP),
                stopLoss.setScale(2, RoundingMode.HALF_UP),
                targetPrice,
                risk.setScale(2, RoundingMode.HALF_UP),
                quantity,
                entryTime,
                null,
                null,
                null,
                BigDecimal.ZERO,
                false);
    }

    /** Closes the position at a given exit price with an exit reason. */
    public CommodityTradePosition close(
            BigDecimal exitPrice, Instant exitTime, String reason, double multiplier) {
        BigDecimal cleanExit =
                exitPrice != null ? exitPrice.setScale(2, RoundingMode.HALF_UP) : this.entryPrice;
        BigDecimal diff =
                "LONG".equalsIgnoreCase(this.side)
                        ? cleanExit.subtract(this.entryPrice)
                        : this.entryPrice.subtract(cleanExit);
        BigDecimal totalPnl =
                diff.multiply(BigDecimal.valueOf(this.quantity))
                        .multiply(BigDecimal.valueOf(multiplier))
                        .setScale(2, RoundingMode.HALF_UP);

        return new CommodityTradePosition(
                this.symbol,
                this.side,
                this.entryPrice,
                this.stopLoss,
                this.targetPrice,
                this.risk,
                this.quantity,
                this.entryTime,
                cleanExit,
                exitTime != null ? exitTime : Instant.now(),
                reason,
                totalPnl,
                true);
    }
}
