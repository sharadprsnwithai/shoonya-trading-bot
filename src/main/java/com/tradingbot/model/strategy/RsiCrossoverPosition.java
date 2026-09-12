package com.tradingbot.model.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Represents an active or closed option position in the RSI Crossover strategy.
 * Supports both standalone single-leg execution and 2% OTM Hedged Credit Spreads.
 */
public class RsiCrossoverPosition {

    private final String tradeId;
    private final String symbol;
    private final String action; // BUY or SELL
    private final String optionType; // CE or PE
    private final BigDecimal strike;
    private final BigDecimal entryPrice;
    private final int quantity;
    private final Instant entryTime;

    // Optional Protective Hedge Leg (e.g. 2% OTM Buy Leg)
    private final boolean hedgeEnabled;
    private final String hedgeSymbol;
    private final BigDecimal hedgeStrike;
    private final BigDecimal hedgeEntryPrice;
    private final int hedgeQuantity;

    private BigDecimal exitPrice;
    private BigDecimal hedgeExitPrice;
    private Instant exitTime;
    private String exitReason;
    private BigDecimal pnl;
    private BigDecimal hedgePnl;
    private boolean closed;

    public RsiCrossoverPosition(
            String tradeId,
            String symbol,
            String action,
            String optionType,
            BigDecimal strike,
            BigDecimal entryPrice,
            int quantity,
            Instant entryTime,
            boolean hedgeEnabled,
            String hedgeSymbol,
            BigDecimal hedgeStrike,
            BigDecimal hedgeEntryPrice,
            int hedgeQuantity) {
        this.tradeId = tradeId;
        this.symbol = symbol;
        this.action = action != null ? action.toUpperCase() : "BUY";
        this.optionType = optionType;
        this.strike = strike;
        this.entryPrice = entryPrice;
        this.quantity = quantity;
        this.entryTime = entryTime;
        this.hedgeEnabled = hedgeEnabled;
        this.hedgeSymbol = hedgeSymbol;
        this.hedgeStrike = hedgeStrike;
        this.hedgeEntryPrice = hedgeEntryPrice;
        this.hedgeQuantity = hedgeQuantity;
        this.closed = false;
    }

    public RsiCrossoverPosition(
            String tradeId,
            String symbol,
            String action,
            String optionType,
            BigDecimal strike,
            BigDecimal entryPrice,
            int quantity,
            Instant entryTime) {
        this(
                tradeId,
                symbol,
                action,
                optionType,
                strike,
                entryPrice,
                quantity,
                entryTime,
                false,
                null,
                null,
                BigDecimal.ZERO,
                0);
    }

    public RsiCrossoverPosition(
            String tradeId,
            String symbol,
            String optionType,
            BigDecimal strike,
            BigDecimal entryPrice,
            int quantity,
            Instant entryTime) {
        this(tradeId, symbol, "BUY", optionType, strike, entryPrice, quantity, entryTime);
    }

    /** Calculates Main Leg P&L given the current price. */
    public BigDecimal calculatePnl(BigDecimal currentPrice) {
        if (currentPrice == null || entryPrice == null) {
            return BigDecimal.ZERO;
        }
        if ("SELL".equalsIgnoreCase(action)) {
            // For short option: profit when price drops
            return entryPrice
                    .subtract(currentPrice)
                    .multiply(BigDecimal.valueOf(quantity))
                    .setScale(2, RoundingMode.HALF_UP);
        } else {
            // For long option: profit when price rises
            return currentPrice
                    .subtract(entryPrice)
                    .multiply(BigDecimal.valueOf(quantity))
                    .setScale(2, RoundingMode.HALF_UP);
        }
    }

    /** Calculates Hedge Leg P&L (Long Option) given current hedge price. */
    public BigDecimal calculateHedgePnl(BigDecimal currentHedgePrice) {
        if (!hedgeEnabled || currentHedgePrice == null || hedgeEntryPrice == null || hedgeQuantity <= 0) {
            return BigDecimal.ZERO;
        }
        // Long hedge option: profit when hedge price rises
        return currentHedgePrice
                .subtract(hedgeEntryPrice)
                .multiply(BigDecimal.valueOf(hedgeQuantity))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Calculates total combined P&L across both main and hedge legs. */
    public BigDecimal calculateTotalPnl(BigDecimal currentMainPrice, BigDecimal currentHedgePrice) {
        BigDecimal mainPnl = calculatePnl(currentMainPrice);
        BigDecimal hPnl = calculateHedgePnl(currentHedgePrice);
        return mainPnl.add(hPnl).setScale(2, RoundingMode.HALF_UP);
    }

    /** Closes both main leg and hedge leg. */
    public void close(BigDecimal exitPrice, BigDecimal hedgeExitPrice, String exitReason, Instant exitTime) {
        this.exitPrice = exitPrice;
        this.hedgeExitPrice = hedgeExitPrice;
        this.exitReason = exitReason;
        this.exitTime = exitTime;
        this.pnl = calculatePnl(exitPrice);
        this.hedgePnl = calculateHedgePnl(hedgeExitPrice);
        this.closed = true;
    }

    public void close(BigDecimal exitPrice, String exitReason, Instant exitTime) {
        close(exitPrice, BigDecimal.ZERO, exitReason, exitTime);
    }

    /** Returns the combined net realized P&L. */
    public BigDecimal getTotalRealizedPnl() {
        BigDecimal main = pnl != null ? pnl : BigDecimal.ZERO;
        BigDecimal hedge = hedgePnl != null ? hedgePnl : BigDecimal.ZERO;
        return main.add(hedge).setScale(2, RoundingMode.HALF_UP);
    }

    /** Returns Net Credit received per share (Main Premium - Hedge Premium). */
    public BigDecimal getNetCredit() {
        if (entryPrice == null) return BigDecimal.ZERO;
        if (!hedgeEnabled || hedgeEntryPrice == null) return entryPrice;
        return entryPrice.subtract(hedgeEntryPrice).setScale(2, RoundingMode.HALF_UP);
    }

    public String getTradeId() {
        return tradeId;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getAction() {
        return action;
    }

    public String getOptionType() {
        return optionType;
    }

    public BigDecimal getStrike() {
        return strike;
    }

    public BigDecimal getEntryPrice() {
        return entryPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public Instant getEntryTime() {
        return entryTime;
    }

    public boolean isHedgeEnabled() {
        return hedgeEnabled;
    }

    public String getHedgeSymbol() {
        return hedgeSymbol;
    }

    public BigDecimal getHedgeStrike() {
        return hedgeStrike;
    }

    public BigDecimal getHedgeEntryPrice() {
        return hedgeEntryPrice;
    }

    public int getHedgeQuantity() {
        return hedgeQuantity;
    }

    public BigDecimal getExitPrice() {
        return exitPrice;
    }

    public BigDecimal getHedgeExitPrice() {
        return hedgeExitPrice;
    }

    public Instant getExitTime() {
        return exitTime;
    }

    public String getExitReason() {
        return exitReason;
    }

    public BigDecimal getPnl() {
        return pnl;
    }

    public BigDecimal getHedgePnl() {
        return hedgePnl;
    }

    public boolean isClosed() {
        return closed;
    }
}
