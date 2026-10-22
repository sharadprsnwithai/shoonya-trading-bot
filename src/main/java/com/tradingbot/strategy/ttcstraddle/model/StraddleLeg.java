package com.tradingbot.strategy.ttcstraddle.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Represents a single option leg within an intraday straddle or hedge wing. */
public class StraddleLeg {

    private final String tradingSymbol;
    private final String optionType; // CE or PE
    private final BigDecimal strike;
    private final BigDecimal entryPrice;
    private final BigDecimal stopLossPrice;
    private final int quantity;
    private volatile boolean active;

    public StraddleLeg(
            String tradingSymbol,
            String optionType,
            BigDecimal strike,
            BigDecimal entryPrice,
            BigDecimal stopLossPrice,
            int quantity,
            boolean active) {
        this.tradingSymbol = tradingSymbol;
        this.optionType = optionType;
        this.strike = strike;
        this.entryPrice = entryPrice;
        this.stopLossPrice = stopLossPrice;
        this.quantity = quantity;
        this.active = active;
    }

    public static BigDecimal computeStopLoss(BigDecimal entryPrice, double slPercentage) {
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal multiplier = BigDecimal.valueOf(1.0 + (slPercentage / 100.0));
        return entryPrice.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }

    public String getTradingSymbol() {
        return tradingSymbol;
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

    public BigDecimal getStopLossPrice() {
        return stopLossPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
