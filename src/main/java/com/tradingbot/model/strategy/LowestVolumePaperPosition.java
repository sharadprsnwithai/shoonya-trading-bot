package com.tradingbot.model.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Represents an active or closed paper trading position for the Lowest Volume Reversal strategy.
 */
public class LowestVolumePaperPosition {

    private final String tradeId;
    private final String symbol;
    private final LowestVolumeDirection direction;
    private final BigDecimal entryPrice;
    private final BigDecimal initialSl;
    private BigDecimal currentSl;
    private final BigDecimal target1Price;
    private final int totalQuantity;
    private int remainingQuantity;
    private final BigDecimal plannedRisk;
    private final Instant entryTime;

    private boolean partialBooked = false;
    private BigDecimal partialExitPrice;
    private Instant partialExitTime;
    private BigDecimal partialPnl = BigDecimal.ZERO;

    private BigDecimal runnerExitPrice;
    private Instant exitTime;
    private BigDecimal runnerPnl = BigDecimal.ZERO;
    private BigDecimal totalRealizedPnl = BigDecimal.ZERO;
    private String exitReason;
    private boolean closed = false;

    private BigDecimal trailingSuperTrendValue;

    public LowestVolumePaperPosition(
            String tradeId,
            String symbol,
            LowestVolumeDirection direction,
            BigDecimal entryPrice,
            BigDecimal initialSl,
            BigDecimal target1Price,
            int totalQuantity,
            BigDecimal plannedRisk,
            Instant entryTime) {
        this.tradeId = tradeId;
        this.symbol = symbol;
        this.direction = direction;
        this.entryPrice = entryPrice;
        this.initialSl = initialSl;
        this.currentSl = initialSl;
        this.target1Price = target1Price;
        this.totalQuantity = totalQuantity;
        this.remainingQuantity = totalQuantity;
        this.plannedRisk = plannedRisk;
        this.entryTime = entryTime != null ? entryTime : Instant.now();
    }

    /**
     * Executes partial profit booking at Target 1 (1:2 RR). Books 50% of the position and moves SL
     * on the remainder to Breakeven (Entry Price).
     */
    public synchronized void executePartialBook(BigDecimal exitPrice, Instant timestamp) {
        if (this.partialBooked || this.closed) {
            return;
        }

        int bookedQty = (totalQuantity + 1) / 2; // Ceil 50%
        this.remainingQuantity = totalQuantity - bookedQty;
        this.partialExitPrice = exitPrice;
        this.partialExitTime = timestamp != null ? timestamp : Instant.now();
        this.partialBooked = true;

        // Move SL to Breakeven
        this.currentSl = this.entryPrice;

        // Compute Partial P&L
        BigDecimal priceDiff =
                (direction == LowestVolumeDirection.LONG)
                        ? exitPrice.subtract(entryPrice)
                        : entryPrice.subtract(exitPrice);
        this.partialPnl =
                priceDiff.multiply(BigDecimal.valueOf(bookedQty)).setScale(2, RoundingMode.HALF_UP);
        this.totalRealizedPnl = this.partialPnl;
    }

    /**
     * Closes the remaining position or full position (e.g. SL hit, Supertrend flip, or 15:00 hard
     * exit).
     */
    public synchronized void close(BigDecimal exitPrice, String reason, Instant timestamp) {
        if (this.closed) {
            return;
        }

        this.runnerExitPrice = exitPrice;
        this.exitTime = timestamp != null ? timestamp : Instant.now();
        this.exitReason = reason;
        this.closed = true;

        if (remainingQuantity > 0) {
            BigDecimal priceDiff =
                    (direction == LowestVolumeDirection.LONG)
                            ? exitPrice.subtract(entryPrice)
                            : entryPrice.subtract(exitPrice);
            this.runnerPnl =
                    priceDiff
                            .multiply(BigDecimal.valueOf(remainingQuantity))
                            .setScale(2, RoundingMode.HALF_UP);
            this.totalRealizedPnl =
                    this.totalRealizedPnl.add(this.runnerPnl).setScale(2, RoundingMode.HALF_UP);
            this.remainingQuantity = 0;
        }
    }

    // Getters and helper methods
    public String getTradeId() {
        return tradeId;
    }

    public String getSymbol() {
        return symbol;
    }

    public LowestVolumeDirection getDirection() {
        return direction;
    }

    public BigDecimal getEntryPrice() {
        return entryPrice;
    }

    public BigDecimal getInitialSl() {
        return initialSl;
    }

    public BigDecimal getCurrentSl() {
        return currentSl;
    }

    public void setCurrentSl(BigDecimal currentSl) {
        this.currentSl = currentSl;
    }

    public BigDecimal getTarget1Price() {
        return target1Price;
    }

    public int getTotalQuantity() {
        return totalQuantity;
    }

    public int getRemainingQuantity() {
        return remainingQuantity;
    }

    public BigDecimal getPlannedRisk() {
        return plannedRisk;
    }

    public Instant getEntryTime() {
        return entryTime;
    }

    public boolean isPartialBooked() {
        return partialBooked;
    }

    public BigDecimal getPartialExitPrice() {
        return partialExitPrice;
    }

    public Instant getPartialExitTime() {
        return partialExitTime;
    }

    public BigDecimal getPartialPnl() {
        return partialPnl;
    }

    public BigDecimal getRunnerExitPrice() {
        return runnerExitPrice;
    }

    public Instant getExitTime() {
        return exitTime;
    }

    public BigDecimal getRunnerPnl() {
        return runnerPnl;
    }

    public BigDecimal getTotalRealizedPnl() {
        return totalRealizedPnl;
    }

    public String getExitReason() {
        return exitReason;
    }

    public boolean isClosed() {
        return closed;
    }

    public BigDecimal getTrailingSuperTrendValue() {
        return trailingSuperTrendValue;
    }

    public void setTrailingSuperTrendValue(BigDecimal trailingSuperTrendValue) {
        this.trailingSuperTrendValue = trailingSuperTrendValue;
    }
}
