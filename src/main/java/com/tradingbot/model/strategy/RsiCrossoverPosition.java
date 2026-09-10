package com.tradingbot.model.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** Represents an active or closed option position (Buy or Sell) in the RSI Crossover strategy. */
public class RsiCrossoverPosition {

    private final String tradeId;
    private final String symbol;
    private final String action; // BUY or SELL
    private final String optionType; // CE or PE
    private final BigDecimal strike;
    private final BigDecimal entryPrice;
    private final int quantity;
    private final Instant entryTime;

    private BigDecimal exitPrice;
    private Instant exitTime;
    private String exitReason;
    private BigDecimal pnl;
    private boolean closed;

    public RsiCrossoverPosition(
            String tradeId,
            String symbol,
            String action,
            String optionType,
            BigDecimal strike,
            BigDecimal entryPrice,
            int quantity,
            Instant entryTime) {
        this.tradeId = tradeId;
        this.symbol = symbol;
        this.action = action != null ? action.toUpperCase() : "BUY";
        this.optionType = optionType;
        this.strike = strike;
        this.entryPrice = entryPrice;
        this.quantity = quantity;
        this.entryTime = entryTime;
        this.closed = false;
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

    public void close(BigDecimal exitPrice, String exitReason, Instant exitTime) {
        this.exitPrice = exitPrice;
        this.exitReason = exitReason;
        this.exitTime = exitTime;
        this.pnl = calculatePnl(exitPrice);
        this.closed = true;
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

    public BigDecimal getExitPrice() {
        return exitPrice;
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

    public boolean isClosed() {
        return closed;
    }
}
