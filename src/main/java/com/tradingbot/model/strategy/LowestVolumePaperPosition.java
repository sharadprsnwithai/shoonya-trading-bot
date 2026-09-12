package com.tradingbot.model.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Represents an active or closed paper trading position for the Lowest Volume Reversal strategy.
 * Trades ATM options (CE for LONG, PE for SHORT). Signal logic (SL, Target 1, SuperTrend) runs on
 * stock candles; P&L is computed on option premiums.
 */
public class LowestVolumePaperPosition {

    private final String tradeId;
    private final String symbol;

    // Option metadata
    private final String optionType; // "CE" or "PE"
    private final String optionSymbol; // full option symbol e.g. RELIANCE25JUL2700CE
    private final BigDecimal atmStrike;
    private final int lotSize;
    private final int lots;

    private final LowestVolumeDirection direction;

    // Entry premium (option premium at entry)
    private final BigDecimal entryPremium;

    // Stock-level signal prices (SL and Target 1 checked on stock candles)
    private final BigDecimal stockEntryPrice;
    private final BigDecimal initialStockSl;
    private BigDecimal currentStockSl;
    private final BigDecimal target1StockPrice;

    private final int totalQuantity; // lots × lotSize
    private int remainingQuantity;
    private final BigDecimal plannedRisk;
    private final Instant entryTime;

    private boolean partialBooked = false;
    private BigDecimal partialExitPremium;
    private Instant partialExitTime;
    private BigDecimal partialPnl = BigDecimal.ZERO;

    private BigDecimal runnerExitPremium;
    private Instant exitTime;
    private BigDecimal runnerPnl = BigDecimal.ZERO;
    private BigDecimal totalRealizedPnl = BigDecimal.ZERO;
    private String exitReason;
    private boolean closed = false;

    private BigDecimal trailingSuperTrendValue;

    public LowestVolumePaperPosition(
            String tradeId,
            String symbol,
            String optionType,
            String optionSymbol,
            BigDecimal atmStrike,
            int lotSize,
            int lots,
            LowestVolumeDirection direction,
            BigDecimal entryPremium,
            BigDecimal stockEntryPrice,
            BigDecimal initialStockSl,
            BigDecimal target1StockPrice,
            int totalQuantity,
            BigDecimal plannedRisk,
            Instant entryTime) {
        this.tradeId = tradeId;
        this.symbol = symbol;
        this.optionType = optionType;
        this.optionSymbol = optionSymbol;
        this.atmStrike = atmStrike;
        this.lotSize = lotSize;
        this.lots = lots;
        this.direction = direction;
        this.entryPremium = entryPremium;
        this.stockEntryPrice = stockEntryPrice;
        this.initialStockSl = initialStockSl;
        this.currentStockSl = initialStockSl;
        this.target1StockPrice = target1StockPrice;
        this.totalQuantity = totalQuantity;
        this.remainingQuantity = totalQuantity;
        this.plannedRisk = plannedRisk;
        this.entryTime = entryTime != null ? entryTime : Instant.now();
    }

    /**
     * Executes partial profit booking at Target 1 (1:4 RR). Books 50% of the position and moves SL
     * on the remainder to Breakeven (entry premium).
     */
    public synchronized void executePartialBook(BigDecimal exitPremium, Instant timestamp) {
        if (this.partialBooked || this.closed) {
            return;
        }

        int bookedQty = (totalQuantity + 1) / 2; // Ceil 50%
        this.remainingQuantity = totalQuantity - bookedQty;
        this.partialExitPremium = exitPremium;
        this.partialExitTime = timestamp != null ? timestamp : Instant.now();
        this.partialBooked = true;

        // Move stock SL to stock breakeven (stock entry price)
        this.currentStockSl = this.stockEntryPrice;

        // Compute Partial P&L: (exitPremium - entryPremium) × bookedQty
        BigDecimal priceDiff = exitPremium.subtract(entryPremium);
        this.partialPnl =
                priceDiff.multiply(BigDecimal.valueOf(bookedQty)).setScale(2, RoundingMode.HALF_UP);
        this.totalRealizedPnl = this.partialPnl;
    }

    /**
     * Closes the remaining position or full position (e.g. SL hit, Supertrend flip, or 15:00 hard
     * exit). P&L is computed on option premiums.
     */
    public synchronized void close(BigDecimal exitPremium, String reason, Instant timestamp) {
        if (this.closed) {
            return;
        }

        this.runnerExitPremium = exitPremium;
        this.exitTime = timestamp != null ? timestamp : Instant.now();
        this.exitReason = reason;
        this.closed = true;

        if (remainingQuantity > 0) {
            BigDecimal priceDiff = exitPremium.subtract(entryPremium);
            this.runnerPnl =
                    priceDiff
                            .multiply(BigDecimal.valueOf(remainingQuantity))
                            .setScale(2, RoundingMode.HALF_UP);
            this.totalRealizedPnl =
                    this.totalRealizedPnl.add(this.runnerPnl).setScale(2, RoundingMode.HALF_UP);
            this.remainingQuantity = 0;
        }
    }

    // --- Getters ---

    public String getTradeId() {
        return tradeId;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getOptionType() {
        return optionType;
    }

    public String getOptionSymbol() {
        return optionSymbol;
    }

    public BigDecimal getAtmStrike() {
        return atmStrike;
    }

    public int getLotSize() {
        return lotSize;
    }

    public int getLots() {
        return lots;
    }

    public LowestVolumeDirection getDirection() {
        return direction;
    }

    public BigDecimal getEntryPremium() {
        return entryPremium;
    }

    public BigDecimal getStockEntryPrice() {
        return stockEntryPrice;
    }

    /**
     * @deprecated Use {@link #getInitialStockSl()} or {@link #getCurrentStockSl()}
     */
    @Deprecated
    public BigDecimal getEntryPrice() {
        return stockEntryPrice;
    }

    public BigDecimal getInitialStockSl() {
        return initialStockSl;
    }

    public BigDecimal getCurrentStockSl() {
        return currentStockSl;
    }

    public void setCurrentStockSl(BigDecimal currentStockSl) {
        this.currentStockSl = currentStockSl;
    }

    public BigDecimal getTarget1StockPrice() {
        return target1StockPrice;
    }

    /**
     * @deprecated Use {@link #getTarget1StockPrice()}
     */
    @Deprecated
    public BigDecimal getTarget1Price() {
        return target1StockPrice;
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

    public BigDecimal getPartialExitPremium() {
        return partialExitPremium;
    }

    /**
     * @deprecated Use {@link #getPartialExitPremium()}
     */
    @Deprecated
    public BigDecimal getPartialExitPrice() {
        return partialExitPremium;
    }

    public Instant getPartialExitTime() {
        return partialExitTime;
    }

    public BigDecimal getPartialPnl() {
        return partialPnl;
    }

    public BigDecimal getRunnerExitPremium() {
        return runnerExitPremium;
    }

    /**
     * @deprecated Use {@link #getRunnerExitPremium()}
     */
    @Deprecated
    public BigDecimal getRunnerExitPrice() {
        return runnerExitPremium;
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
