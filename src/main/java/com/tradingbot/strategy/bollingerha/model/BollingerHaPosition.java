package com.tradingbot.strategy.bollingerha.model;

import java.math.BigDecimal;
import java.time.Instant;

/** Represents an active or completed intraday position for the Bollinger HA strategy. */
public class BollingerHaPosition {

    private final String positionId;
    private final String symbol;
    private final String token;
    private final String optionType; // CE or PE
    private final BigDecimal entryPrice;
    private BigDecimal stopLoss;
    private final BigDecimal targetPrice;
    private final int totalQuantity;
    private int remainingQuantity;
    private final Instant entryTime;
    private boolean targetHit;
    private boolean costSlActive;
    private boolean closed;
    private BigDecimal exitPrice;
    private Instant exitTime;
    private String exitReason;

    public BollingerHaPosition(
            String positionId,
            String symbol,
            String token,
            String optionType,
            BigDecimal entryPrice,
            BigDecimal stopLoss,
            BigDecimal targetPrice,
            int totalQuantity,
            Instant entryTime) {
        this.positionId = positionId;
        this.symbol = symbol;
        this.token = token;
        this.optionType = optionType;
        this.entryPrice = entryPrice;
        this.stopLoss = stopLoss;
        this.targetPrice = targetPrice;
        this.totalQuantity = totalQuantity;
        this.remainingQuantity = totalQuantity;
        this.entryTime = entryTime;
        this.targetHit = false;
        this.costSlActive = false;
        this.closed = false;
    }

    public String getPositionId() {
        return positionId;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getToken() {
        return token;
    }

    public String getOptionType() {
        return optionType;
    }

    public BigDecimal getEntryPrice() {
        return entryPrice;
    }

    public BigDecimal getStopLoss() {
        return stopLoss;
    }

    public void setStopLoss(BigDecimal stopLoss) {
        this.stopLoss = stopLoss;
    }

    public BigDecimal getTargetPrice() {
        return targetPrice;
    }

    public int getTotalQuantity() {
        return totalQuantity;
    }

    public int getRemainingQuantity() {
        return remainingQuantity;
    }

    public void setRemainingQuantity(int remainingQuantity) {
        this.remainingQuantity = remainingQuantity;
    }

    public Instant getEntryTime() {
        return entryTime;
    }

    public boolean isTargetHit() {
        return targetHit;
    }

    public void setTargetHit(boolean targetHit) {
        this.targetHit = targetHit;
    }

    public boolean isCostSlActive() {
        return costSlActive;
    }

    public void setCostSlActive(boolean costSlActive) {
        this.costSlActive = costSlActive;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    public BigDecimal getExitPrice() {
        return exitPrice;
    }

    public void setExitPrice(BigDecimal exitPrice) {
        this.exitPrice = exitPrice;
    }

    public Instant getExitTime() {
        return exitTime;
    }

    public void setExitTime(Instant exitTime) {
        this.exitTime = exitTime;
    }

    public String getExitReason() {
        return exitReason;
    }

    public void setExitReason(String exitReason) {
        this.exitReason = exitReason;
    }
}
