package com.tradingbot.strategy.commodity.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Represents an active or closed trade position for the Commodity VWAP breakout strategy. Supports
 * 50% partial profit booking at Target 1 (1:2.5 RR), SL adjustment to Cost/Breakeven, and dynamic
 * 10 EMA trailing for the remaining runner quantity.
 */
public class CommodityTradePosition {

    private final String symbol;
    private final String side; // LONG or SHORT
    private final BigDecimal entryPrice;
    private final BigDecimal initialStopLoss;
    private volatile BigDecimal currentStopLoss;
    private final BigDecimal targetPrice;
    private final BigDecimal risk;
    private final int totalQuantity;
    private volatile int remainingQuantity;
    private final Instant entryTime;

    private volatile boolean partialBooked = false;
    private volatile BigDecimal partialExitPrice;
    private volatile Instant partialExitTime;
    private volatile BigDecimal partialPnl = BigDecimal.ZERO;

    private volatile BigDecimal runnerExitPrice;
    private volatile Instant exitTime;
    private volatile String exitReason;
    private volatile BigDecimal runnerPnl = BigDecimal.ZERO;
    private volatile BigDecimal totalRealizedPnl = BigDecimal.ZERO;
    private volatile boolean closed = false;

    public CommodityTradePosition(
            String symbol,
            String side,
            BigDecimal entryPrice,
            BigDecimal initialStopLoss,
            BigDecimal targetPrice,
            BigDecimal risk,
            int totalQuantity,
            Instant entryTime) {
        this.symbol = symbol;
        this.side = side;
        this.entryPrice = entryPrice.setScale(2, RoundingMode.HALF_UP);
        this.initialStopLoss = initialStopLoss.setScale(2, RoundingMode.HALF_UP);
        this.currentStopLoss = this.initialStopLoss;
        this.targetPrice = targetPrice.setScale(2, RoundingMode.HALF_UP);
        this.risk = risk.setScale(2, RoundingMode.HALF_UP);
        this.totalQuantity = totalQuantity;
        this.remainingQuantity = totalQuantity;
        this.entryTime = entryTime != null ? entryTime : Instant.now();
    }

    /** Factory to initialize an active LONG trade position with RR target. */
    public static CommodityTradePosition createLong(
            String symbol,
            BigDecimal entryPrice,
            BigDecimal stopLoss,
            BigDecimal riskRewardRatio,
            int quantity,
            Instant entryTime) {
        BigDecimal risk = entryPrice.subtract(stopLoss).abs();
        if (risk.compareTo(BigDecimal.ZERO) <= 0) {
            risk = new BigDecimal("1.00");
        }
        BigDecimal rr = riskRewardRatio != null ? riskRewardRatio : new BigDecimal("2.5");
        BigDecimal targetPrice =
                entryPrice.add(risk.multiply(rr)).setScale(2, RoundingMode.HALF_UP);

        return new CommodityTradePosition(
                symbol, "LONG", entryPrice, stopLoss, targetPrice, risk, quantity, entryTime);
    }

    /** Factory to initialize an active SHORT trade position with RR target. */
    public static CommodityTradePosition createShort(
            String symbol,
            BigDecimal entryPrice,
            BigDecimal stopLoss,
            BigDecimal riskRewardRatio,
            int quantity,
            Instant entryTime) {
        BigDecimal risk = stopLoss.subtract(entryPrice).abs();
        if (risk.compareTo(BigDecimal.ZERO) <= 0) {
            risk = new BigDecimal("1.00");
        }
        BigDecimal rr = riskRewardRatio != null ? riskRewardRatio : new BigDecimal("2.5");
        BigDecimal targetPrice =
                entryPrice.subtract(risk.multiply(rr)).setScale(2, RoundingMode.HALF_UP);

        return new CommodityTradePosition(
                symbol, "SHORT", entryPrice, stopLoss, targetPrice, risk, quantity, entryTime);
    }

    /**
     * Executes 50% partial profit booking at Target 1 (e.g. 1:2.5 RR) and moves Stop Loss to Cost
     * (Entry Price).
     */
    public synchronized CommodityTradePosition executePartialBook(
            BigDecimal exitPrice, Instant timestamp, double unitMultiplier) {
        if (this.partialBooked || this.closed) {
            return this;
        }

        int bookedQty = Math.max(1, this.totalQuantity / 2);
        this.remainingQuantity = Math.max(0, this.totalQuantity - bookedQty);
        this.partialExitPrice =
                exitPrice != null ? exitPrice.setScale(2, RoundingMode.HALF_UP) : this.targetPrice;
        this.partialExitTime = timestamp != null ? timestamp : Instant.now();
        this.partialBooked = true;

        // Move Stop Loss to Cost / Breakeven (Entry Price)
        this.currentStopLoss = this.entryPrice;

        BigDecimal diff =
                "LONG".equalsIgnoreCase(this.side)
                        ? this.partialExitPrice.subtract(this.entryPrice)
                        : this.entryPrice.subtract(this.partialExitPrice);
        this.partialPnl =
                diff.multiply(BigDecimal.valueOf(bookedQty))
                        .multiply(BigDecimal.valueOf(unitMultiplier))
                        .setScale(2, RoundingMode.HALF_UP);
        this.totalRealizedPnl = this.partialPnl;

        if (this.remainingQuantity == 0) {
            this.closed = true;
            this.exitReason = "TARGET_FULL_EXIT";
            this.runnerExitPrice = this.partialExitPrice;
            this.exitTime = this.partialExitTime;
        }

        return this;
    }

    /** Updates the dynamic trailing Stop Loss based on 10 EMA for the remaining runner quantity. */
    public synchronized void updateDynamicStopLoss(BigDecimal ema10) {
        if (ema10 == null || this.closed) return;
        BigDecimal cleanEma = ema10.setScale(2, RoundingMode.HALF_UP);

        if ("LONG".equalsIgnoreCase(this.side)) {
            // For LONG, dynamic SL can only ratchet upward (max of cost and ema10)
            if (cleanEma.compareTo(this.currentStopLoss) > 0) {
                this.currentStopLoss = cleanEma;
            }
        } else if ("SHORT".equalsIgnoreCase(this.side)) {
            // For SHORT, dynamic SL can only ratchet downward (min of cost and ema10)
            if (cleanEma.compareTo(this.currentStopLoss) < 0) {
                this.currentStopLoss = cleanEma;
            }
        }
    }

    /**
     * Closes the remaining position (or full position if not partial booked) with an exit price and
     * reason.
     */
    public synchronized CommodityTradePosition close(
            BigDecimal exitPrice, Instant exitTime, String reason, double unitMultiplier) {
        if (this.closed) return this;

        BigDecimal cleanExit =
                exitPrice != null ? exitPrice.setScale(2, RoundingMode.HALF_UP) : this.entryPrice;
        this.runnerExitPrice = cleanExit;
        this.exitTime = exitTime != null ? exitTime : Instant.now();
        this.exitReason = reason;
        this.closed = true;

        int closingQty = this.partialBooked ? this.remainingQuantity : this.totalQuantity;
        if (closingQty > 0) {
            BigDecimal diff =
                    "LONG".equalsIgnoreCase(this.side)
                            ? cleanExit.subtract(this.entryPrice)
                            : this.entryPrice.subtract(cleanExit);
            BigDecimal closingPnl =
                    diff.multiply(BigDecimal.valueOf(closingQty))
                            .multiply(BigDecimal.valueOf(unitMultiplier))
                            .setScale(2, RoundingMode.HALF_UP);

            if (this.partialBooked) {
                this.runnerPnl = closingPnl;
                this.totalRealizedPnl = this.partialPnl.add(this.runnerPnl);
            } else {
                this.totalRealizedPnl = closingPnl;
            }
        }

        return this;
    }

    // Getters for properties
    public String symbol() {
        return symbol;
    }

    public String side() {
        return side;
    }

    public BigDecimal entryPrice() {
        return entryPrice;
    }

    public BigDecimal stopLoss() {
        return currentStopLoss;
    }

    public BigDecimal initialStopLoss() {
        return initialStopLoss;
    }

    public BigDecimal currentStopLoss() {
        return currentStopLoss;
    }

    public BigDecimal targetPrice() {
        return targetPrice;
    }

    public BigDecimal risk() {
        return risk;
    }

    public int quantity() {
        return totalQuantity;
    }

    public int totalQuantity() {
        return totalQuantity;
    }

    public int remainingQuantity() {
        return remainingQuantity;
    }

    public Instant entryTime() {
        return entryTime;
    }

    public boolean isPartialBooked() {
        return partialBooked;
    }

    public BigDecimal partialExitPrice() {
        return partialExitPrice;
    }

    public Instant partialExitTime() {
        return partialExitTime;
    }

    public BigDecimal partialPnl() {
        return partialPnl;
    }

    public BigDecimal exitPrice() {
        return runnerExitPrice != null ? runnerExitPrice : partialExitPrice;
    }

    public BigDecimal runnerExitPrice() {
        return runnerExitPrice;
    }

    public Instant exitTime() {
        return exitTime;
    }

    public String exitReason() {
        return exitReason;
    }

    public BigDecimal runnerPnl() {
        return runnerPnl;
    }

    public BigDecimal pnl() {
        return totalRealizedPnl;
    }

    public BigDecimal totalRealizedPnl() {
        return totalRealizedPnl;
    }

    public boolean isClosed() {
        return closed;
    }
}
