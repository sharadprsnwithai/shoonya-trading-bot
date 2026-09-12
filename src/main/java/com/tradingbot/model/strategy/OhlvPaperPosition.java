package com.tradingbot.model.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Represents an active or closed paper-trading option position for the OHL-VWAP strategy. Entry and
 * exit decisions are driven by the underlying stock's 5-minute candles and VWAP; P&amp;L is
 * computed on option premiums (ATM strike, per-share basis; lot size used when F&amp;O metadata is
 * known, otherwise qty=1).
 */
public class OhlvPaperPosition {

    private final String tradeId;
    private final String symbol;
    private final OhlvDirection direction;
    private final String optionType; // "CE" (BULLISH) or "PE" (BEARISH)
    private final BigDecimal atmStrike;
    private final int lotSize;
    private final int qty;

    private final BigDecimal entryPremium;
    private final Instant entryTime;

    private BigDecimal exitPremium;
    private Instant exitTime;
    private String exitReason;
    private BigDecimal realizedPnl;
    private boolean closed;

    public OhlvPaperPosition(
            String tradeId,
            String symbol,
            OhlvDirection direction,
            BigDecimal atmStrike,
            int lotSize,
            BigDecimal entryPremium,
            Instant entryTime) {
        this.tradeId = tradeId;
        this.symbol = symbol;
        this.direction = direction;
        this.optionType = direction == OhlvDirection.BULLISH ? "CE" : "PE";
        this.atmStrike = atmStrike;
        this.lotSize = Math.max(1, lotSize);
        this.qty = this.lotSize;
        this.entryPremium = entryPremium;
        this.entryTime = entryTime != null ? entryTime : Instant.now();
        this.closed = false;
    }

    /** Closes the position at the given premium, computing realized P&L per share basis. */
    public synchronized void close(BigDecimal exitPremium, String reason, Instant timestamp) {
        if (this.closed) {
            return;
        }
        this.exitPremium = exitPremium;
        this.exitTime = timestamp != null ? timestamp : Instant.now();
        this.exitReason = reason;
        BigDecimal diff =
                exitPremium != null ? exitPremium.subtract(this.entryPremium) : BigDecimal.ZERO;
        this.realizedPnl =
                diff.multiply(BigDecimal.valueOf(this.qty)).setScale(2, RoundingMode.HALF_UP);
        this.closed = true;
    }

    /** Computes unrealized P&L at a hypothetical premium. */
    public BigDecimal calculateUnrealizedPnl(BigDecimal premium) {
        if (premium == null || this.entryPremium == null) {
            return BigDecimal.ZERO;
        }
        return premium.subtract(this.entryPremium)
                .multiply(BigDecimal.valueOf(this.qty))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public String getTradeId() {
        return tradeId;
    }

    public String getSymbol() {
        return symbol;
    }

    public OhlvDirection getDirection() {
        return direction;
    }

    public String getOptionType() {
        return optionType;
    }

    public BigDecimal getAtmStrike() {
        return atmStrike;
    }

    public int getLotSize() {
        return lotSize;
    }

    public int getQty() {
        return qty;
    }

    public BigDecimal getEntryPremium() {
        return entryPremium;
    }

    public Instant getEntryTime() {
        return entryTime;
    }

    public BigDecimal getExitPremium() {
        return exitPremium;
    }

    public Instant getExitTime() {
        return exitTime;
    }

    public String getExitReason() {
        return exitReason;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    public boolean isClosed() {
        return closed;
    }
}
