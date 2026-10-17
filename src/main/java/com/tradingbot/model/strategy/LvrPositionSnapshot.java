package com.tradingbot.model.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * M10: plain, Jackson-friendly snapshot of a {@link LowestVolumePaperPosition} — captures entry
 * state AND mutable trading state (trailing SL, partial exit, close outcome) so positions can be
 * persisted to disk and restored after a restart without orphaning broker positions.
 *
 * <p>Public fields are intentional: Jackson detects them directly, keeping the DTO dumb and the
 * mapping logic in {@link LowestVolumePaperPosition#snapshotOf} / {@link
 * LowestVolumePaperPosition#restoreFrom} where private field access is legal.
 */
public class LvrPositionSnapshot {

    public String tradeId;
    public String symbol;
    public LvrInstrumentType instrumentType;
    public LvrExitMode exitMode;

    public String optionType;
    public String optionSymbol;
    public BigDecimal atmStrike;

    /** Exchange trading symbol the position was entered under (P3 / design §4.4). */
    public String brokerTradingSymbol;

    /** Contract expiry captured at entry (P3 / design §4.4); null on pre-§4.4 snapshots. */
    public LocalDate expiryDate;

    public int lotSize;
    public int lots;

    public LowestVolumeDirection direction;

    public BigDecimal entryPremium;
    public BigDecimal stockEntryPrice;
    public BigDecimal initialStockSl;
    public BigDecimal currentStockSl;
    public BigDecimal target1StockPrice;

    public int totalQuantity;
    public int remainingQuantity;
    public BigDecimal plannedRisk;
    public Instant entryTime;

    public boolean partialBooked;
    public BigDecimal partialExitPremium;
    public Instant partialExitTime;
    public BigDecimal partialPnl;

    public BigDecimal runnerExitPremium;
    public Instant exitTime;
    public BigDecimal runnerPnl;
    public BigDecimal totalRealizedPnl;
    public String exitReason;
    public boolean closed;

    public BigDecimal trailingSuperTrendValue;
}
