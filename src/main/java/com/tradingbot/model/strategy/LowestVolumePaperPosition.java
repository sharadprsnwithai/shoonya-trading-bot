package com.tradingbot.model.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Represents an active or closed paper trading position for the Lowest Volume Reversal strategy.
 * Supports both Stock Futures (1.0 Delta direct price) and ATM Options (CE/PE).
 */
public class LowestVolumePaperPosition {

    private final String tradeId;
    private final String symbol;
    private final LvrInstrumentType instrumentType;
    private final LvrExitMode exitMode;

    // Option metadata (null/empty if FUTURES)
    private final String optionType; // "CE" or "PE" or null
    private final String optionSymbol; // e.g. "SUNPHARMA FUT" or "RELIANCE25JUL2700CE"
    private final BigDecimal atmStrike;
    private final int lotSize;
    private final int lots;

    private final LowestVolumeDirection direction;

    // Entry premium for Options (or entry price if Futures)
    private final BigDecimal entryPremium;

    // Signal prices (SL and Target 1)
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

    /** Primary constructor for Futures / Full specification. */
    public LowestVolumePaperPosition(
            String tradeId,
            String symbol,
            LvrInstrumentType instrumentType,
            LvrExitMode exitMode,
            String contractSymbol,
            int lotSize,
            int lots,
            LowestVolumeDirection direction,
            BigDecimal entryPrice,
            BigDecimal initialSl,
            BigDecimal target1Price,
            int totalQuantity,
            BigDecimal plannedRisk,
            Instant entryTime) {
        this.tradeId = tradeId;
        this.symbol = symbol;
        this.instrumentType = instrumentType != null ? instrumentType : LvrInstrumentType.FUTURES;
        this.exitMode = exitMode != null ? exitMode : LvrExitMode.FULL_TARGET_1_4;
        this.optionType = null;
        this.optionSymbol = contractSymbol;
        this.atmStrike = null;
        this.lotSize = lotSize;
        this.lots = lots;
        this.direction = direction;
        this.entryPremium = entryPrice;
        this.stockEntryPrice = entryPrice;
        this.initialStockSl = initialSl;
        this.currentStockSl = initialSl;
        this.target1StockPrice = target1Price;
        this.totalQuantity = totalQuantity;
        this.remainingQuantity = totalQuantity;
        this.plannedRisk = plannedRisk;
        this.entryTime = entryTime != null ? entryTime : Instant.now();
    }

    /** Options-oriented constructor with configurable exit mode. */
    public LowestVolumePaperPosition(
            String tradeId,
            String symbol,
            LvrExitMode exitMode,
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
        this.instrumentType = LvrInstrumentType.OPTIONS;
        this.exitMode = exitMode != null ? exitMode : LvrExitMode.PARTIAL_1_4_TRAIL_1_1_EOD_1500;
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

    /** Options-oriented constructor for backward compatibility. */
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
        this(
                tradeId,
                symbol,
                LvrExitMode.PARTIAL_RUNNER_10EMA,
                optionType,
                optionSymbol,
                atmStrike,
                lotSize,
                lots,
                direction,
                entryPremium,
                stockEntryPrice,
                initialStockSl,
                target1StockPrice,
                totalQuantity,
                plannedRisk,
                entryTime);
    }

    /** Executes 100% full exit for Stock Futures (1.0 Delta direct price P&L). */
    public synchronized void closeFullFutures(
            BigDecimal exitPrice, String reason, Instant timestamp) {
        close(exitPrice, reason, timestamp);
    }

    /** Executes partial profit booking at Target 1 (1:4 RR) in Trailing / Runner mode. */
    public synchronized void executePartialBook(
            BigDecimal exitPriceOrPremium, Instant timestamp) {
        if (this.partialBooked || this.closed) {
            return;
        }

        int effectiveLots =
                (this.lots > 0)
                        ? this.lots
                        : Math.max(1, this.totalQuantity / Math.max(1, this.lotSize));
        int bookedLots = (effectiveLots + 1) / 2; // Ceiling of 50% lots
        int effectiveLotSize =
                (this.lotSize > 0) ? this.lotSize : (this.totalQuantity / effectiveLots);
        int bookedQty = Math.min(this.totalQuantity, bookedLots * effectiveLotSize);

        this.remainingQuantity = this.totalQuantity - bookedQty;
        this.partialExitPremium = exitPriceOrPremium;
        this.partialExitTime = timestamp != null ? timestamp : Instant.now();
        this.partialBooked = true;

        // If all lots booked (e.g. 1 lot total), mark position fully closed at Target 1
        if (this.remainingQuantity == 0) {
            this.closed = true;
            this.exitReason = "TARGET_1_4_FULL_EXIT";
            this.runnerExitPremium = exitPriceOrPremium;
            this.exitTime = this.partialExitTime;
        }

        // Move SL based on Exit Mode
        BigDecimal unitRisk = this.stockEntryPrice.subtract(this.initialStockSl).abs();
        if (this.exitMode == LvrExitMode.PARTIAL_1_4_TRAIL_1_1_EOD_1500) {
            // Move SL to 1:1 (+1R locked)
            if (this.direction == LowestVolumeDirection.LONG) {
                this.currentStockSl = this.stockEntryPrice.add(unitRisk);
            } else {
                this.currentStockSl = this.stockEntryPrice.subtract(unitRisk);
            }
        } else {
            // Move stock SL to stock breakeven (stock entry price)
            this.currentStockSl = this.stockEntryPrice;
        }

        // Compute Partial P&L
        BigDecimal priceDiff;
        if (this.instrumentType == LvrInstrumentType.FUTURES) {
            priceDiff =
                    (this.direction == LowestVolumeDirection.LONG)
                            ? exitPriceOrPremium.subtract(this.stockEntryPrice)
                            : this.stockEntryPrice.subtract(exitPriceOrPremium);
        } else {
            priceDiff = exitPriceOrPremium.subtract(this.entryPremium);
        }
        this.partialPnl =
                priceDiff
                        .multiply(BigDecimal.valueOf(bookedQty))
                        .setScale(2, RoundingMode.HALF_UP);
        this.totalRealizedPnl = this.partialPnl;
    }

    /** Closes the remaining position or full position in Trailing / Runner mode. */
    public synchronized void close(
            BigDecimal exitPriceOrPremium, String reason, Instant timestamp) {
        if (this.closed) {
            return;
        }

        this.runnerExitPremium = exitPriceOrPremium;
        this.exitTime = timestamp != null ? timestamp : Instant.now();
        this.exitReason = reason;
        this.closed = true;

        if (remainingQuantity > 0) {
            BigDecimal priceDiff;
            if (this.instrumentType == LvrInstrumentType.FUTURES) {
                priceDiff =
                        (this.direction == LowestVolumeDirection.LONG)
                                ? exitPriceOrPremium.subtract(this.stockEntryPrice)
                                : this.stockEntryPrice.subtract(exitPriceOrPremium);
            } else {
                priceDiff = exitPriceOrPremium.subtract(this.entryPremium);
            }
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

    public LvrInstrumentType getInstrumentType() {
        return instrumentType;
    }

    public LvrExitMode getExitMode() {
        return exitMode;
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
