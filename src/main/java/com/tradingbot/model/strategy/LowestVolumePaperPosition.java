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
    private String brokerTradingSymbol;

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
        this.exitMode = exitMode != null ? exitMode : LvrExitMode.FULL_TARGET_1_2;
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
        this.exitMode =
                exitMode != null ? exitMode : LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500;
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

    /** Executes partial profit booking at Target 1 (1:2 RR) in Trailing / Runner mode. */
    public synchronized void executePartialBook(BigDecimal exitPriceOrPremium, Instant timestamp) {
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
            this.exitReason = "TARGET_1_2_FULL_EXIT";
            this.runnerExitPremium = exitPriceOrPremium;
            this.exitTime = this.partialExitTime;
        }

        // Move stock SL to cost / breakeven (entry price)
        this.currentStockSl = this.stockEntryPrice;

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
                priceDiff.multiply(BigDecimal.valueOf(bookedQty)).setScale(2, RoundingMode.HALF_UP);
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

    public int getEffectiveBookedQuantityOnTarget() {
        int effectiveLots =
                (this.lots > 0)
                        ? this.lots
                        : Math.max(1, this.totalQuantity / Math.max(1, this.lotSize));
        int bookedLots = (effectiveLots + 1) / 2;
        int effectiveLotSize =
                (this.lotSize > 0) ? this.lotSize : (this.totalQuantity / effectiveLots);
        return Math.min(this.totalQuantity, bookedLots * effectiveLotSize);
    }

    public int getBookedQuantity() {
        return this.totalQuantity - this.remainingQuantity;
    }

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

    public String getContractSymbol() {
        return optionSymbol;
    }

    public String getBrokerTradingSymbol() {
        return brokerTradingSymbol;
    }

    public void setBrokerTradingSymbol(String brokerTradingSymbol) {
        this.brokerTradingSymbol = brokerTradingSymbol;
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

    // --- M10: disk snapshot mapping ---

    /** M10: captures this position (entry state + mutable trading state) into a snapshot. */
    public static LvrPositionSnapshot snapshotOf(LowestVolumePaperPosition pos) {
        if (pos == null) return null;
        LvrPositionSnapshot s = new LvrPositionSnapshot();
        s.tradeId = pos.tradeId;
        s.symbol = pos.symbol;
        s.instrumentType = pos.instrumentType;
        s.exitMode = pos.exitMode;
        s.optionType = pos.optionType;
        s.optionSymbol = pos.optionSymbol;
        s.atmStrike = pos.atmStrike;
        s.lotSize = pos.lotSize;
        s.lots = pos.lots;
        s.direction = pos.direction;
        s.entryPremium = pos.entryPremium;
        s.stockEntryPrice = pos.stockEntryPrice;
        s.initialStockSl = pos.initialStockSl;
        s.currentStockSl = pos.currentStockSl;
        s.target1StockPrice = pos.target1StockPrice;
        s.totalQuantity = pos.totalQuantity;
        s.remainingQuantity = pos.remainingQuantity;
        s.plannedRisk = pos.plannedRisk;
        s.entryTime = pos.entryTime;
        s.partialBooked = pos.partialBooked;
        s.partialExitPremium = pos.partialExitPremium;
        s.partialExitTime = pos.partialExitTime;
        s.partialPnl = pos.partialPnl;
        s.runnerExitPremium = pos.runnerExitPremium;
        s.exitTime = pos.exitTime;
        s.runnerPnl = pos.runnerPnl;
        s.totalRealizedPnl = pos.totalRealizedPnl;
        s.exitReason = pos.exitReason;
        s.closed = pos.closed;
        s.trailingSuperTrendValue = pos.trailingSuperTrendValue;
        return s;
    }

    /** M10: rebuilds a position (including mutable state) from a persisted snapshot. */
    public static LowestVolumePaperPosition restoreFrom(LvrPositionSnapshot s) {
        if (s == null || s.symbol == null) return null;
        LvrInstrumentType type =
                s.instrumentType != null ? s.instrumentType : LvrInstrumentType.FUTURES;
        LowestVolumePaperPosition pos;
        if (type == LvrInstrumentType.FUTURES) {
            pos =
                    new LowestVolumePaperPosition(
                            s.tradeId,
                            s.symbol,
                            type,
                            s.exitMode,
                            s.optionSymbol,
                            s.lotSize,
                            s.lots,
                            s.direction,
                            s.stockEntryPrice,
                            s.initialStockSl,
                            s.target1StockPrice,
                            s.totalQuantity,
                            s.plannedRisk,
                            s.entryTime);
        } else {
            pos =
                    new LowestVolumePaperPosition(
                            s.tradeId,
                            s.symbol,
                            s.exitMode,
                            s.optionType,
                            s.optionSymbol,
                            s.atmStrike,
                            s.lotSize,
                            s.lots,
                            s.direction,
                            s.entryPremium,
                            s.stockEntryPrice,
                            s.initialStockSl,
                            s.target1StockPrice,
                            s.totalQuantity,
                            s.plannedRisk,
                            s.entryTime);
        }
        // Restore mutable trading state the constructors don't cover.
        // (entryPremium is final and correctly set by both constructors above.)
        pos.currentStockSl = s.currentStockSl != null ? s.currentStockSl : pos.currentStockSl;
        pos.remainingQuantity =
                s.remainingQuantity > 0 ? s.remainingQuantity : pos.remainingQuantity;
        pos.partialBooked = s.partialBooked;
        pos.partialExitPremium = s.partialExitPremium;
        pos.partialExitTime = s.partialExitTime;
        pos.partialPnl = s.partialPnl != null ? s.partialPnl : pos.partialPnl;
        pos.runnerExitPremium = s.runnerExitPremium;
        pos.exitTime = s.exitTime;
        pos.runnerPnl = s.runnerPnl != null ? s.runnerPnl : pos.runnerPnl;
        pos.totalRealizedPnl =
                s.totalRealizedPnl != null ? s.totalRealizedPnl : pos.totalRealizedPnl;
        pos.exitReason = s.exitReason;
        pos.closed = s.closed;
        pos.trailingSuperTrendValue = s.trailingSuperTrendValue;
        return pos;
    }
}
