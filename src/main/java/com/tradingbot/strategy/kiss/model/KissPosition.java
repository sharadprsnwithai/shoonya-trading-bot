package com.tradingbot.strategy.kiss.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/** Represents an active or closed Futures position tracked under the KISS Strategy. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class KissPosition {

    private String symbol;
    private KissSignalType signalType; // BUY_SIGNAL (Long) or SHORT_SIGNAL (Short)
    private double entryPrice;
    private double stopLoss;
    private double targetPrice;
    private int quantity; // Number of contracts/shares
    private int lotSize;
    private double currentLtp;
    private double highestPriceSeen;
    private double lowestPriceSeen;
    private double unrealizedPnl;
    private double unrealizedPnlPct;
    private double realizedPnl;
    private boolean active = true;
    private String exitReason;
    private Instant enteredAt;
    private Instant exitedAt;
    private Instant lastEvaluatedAt;

    public KissPosition() {}

    public KissPosition(
            String symbol,
            KissSignalType signalType,
            double entryPrice,
            double stopLoss,
            double targetPrice,
            int quantity,
            int lotSize,
            Instant enteredAt) {
        this.symbol = symbol;
        this.signalType = signalType;
        this.entryPrice = entryPrice;
        this.stopLoss = stopLoss;
        this.targetPrice = targetPrice;
        this.quantity = quantity;
        this.lotSize = lotSize;
        this.currentLtp = entryPrice;
        this.highestPriceSeen = entryPrice;
        this.lowestPriceSeen = entryPrice;
        this.enteredAt = enteredAt;
        this.lastEvaluatedAt = enteredAt;
        this.active = true;
    }

    public void updateMarketPrice(double ltp) {
        this.currentLtp = ltp;
        this.highestPriceSeen = Math.max(this.highestPriceSeen, ltp);
        this.lowestPriceSeen = Math.min(this.lowestPriceSeen, ltp);
        this.lastEvaluatedAt = Instant.now();

        if (signalType == KissSignalType.BUY_SIGNAL) {
            this.unrealizedPnl = (ltp - entryPrice) * quantity;
            this.unrealizedPnlPct =
                    entryPrice > 0 ? ((ltp - entryPrice) / entryPrice) * 100.0 : 0.0;
        } else {
            this.unrealizedPnl = (entryPrice - ltp) * quantity;
            this.unrealizedPnlPct =
                    entryPrice > 0 ? ((entryPrice - ltp) / entryPrice) * 100.0 : 0.0;
        }
    }

    public void closePosition(double exitPrice, String reason, Instant exitedAt) {
        this.active = false;
        this.currentLtp = exitPrice;
        this.exitReason = reason;
        this.exitedAt = exitedAt;
        if (signalType == KissSignalType.BUY_SIGNAL) {
            this.realizedPnl = (exitPrice - entryPrice) * quantity;
        } else {
            this.realizedPnl = (entryPrice - exitPrice) * quantity;
        }
    }

    // Getters and Setters
    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public KissSignalType getSignalType() {
        return signalType;
    }

    public void setSignalType(KissSignalType signalType) {
        this.signalType = signalType;
    }

    public double getEntryPrice() {
        return entryPrice;
    }

    public void setEntryPrice(double entryPrice) {
        this.entryPrice = entryPrice;
    }

    public double getStopLoss() {
        return stopLoss;
    }

    public void setStopLoss(double stopLoss) {
        this.stopLoss = stopLoss;
    }

    public double getTargetPrice() {
        return targetPrice;
    }

    public void setTargetPrice(double targetPrice) {
        this.targetPrice = targetPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public int getLotSize() {
        return lotSize;
    }

    public void setLotSize(int lotSize) {
        this.lotSize = lotSize;
    }

    public double getCurrentLtp() {
        return currentLtp;
    }

    public void setCurrentLtp(double currentLtp) {
        this.currentLtp = currentLtp;
    }

    public double getHighestPriceSeen() {
        return highestPriceSeen;
    }

    public void setHighestPriceSeen(double highestPriceSeen) {
        this.highestPriceSeen = highestPriceSeen;
    }

    public double getLowestPriceSeen() {
        return lowestPriceSeen;
    }

    public void setLowestPriceSeen(double lowestPriceSeen) {
        this.lowestPriceSeen = lowestPriceSeen;
    }

    public double getUnrealizedPnl() {
        return unrealizedPnl;
    }

    public void setUnrealizedPnl(double unrealizedPnl) {
        this.unrealizedPnl = unrealizedPnl;
    }

    public double getUnrealizedPnlPct() {
        return unrealizedPnlPct;
    }

    public void setUnrealizedPnlPct(double unrealizedPnlPct) {
        this.unrealizedPnlPct = unrealizedPnlPct;
    }

    public double getRealizedPnl() {
        return realizedPnl;
    }

    public void setRealizedPnl(double realizedPnl) {
        this.realizedPnl = realizedPnl;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getExitReason() {
        return exitReason;
    }

    public void setExitReason(String exitReason) {
        this.exitReason = exitReason;
    }

    public Instant getEnteredAt() {
        return enteredAt;
    }

    public void setEnteredAt(Instant enteredAt) {
        this.enteredAt = enteredAt;
    }

    public Instant getExitedAt() {
        return exitedAt;
    }

    public void setExitedAt(Instant exitedAt) {
        this.exitedAt = exitedAt;
    }

    public Instant getLastEvaluatedAt() {
        return lastEvaluatedAt;
    }

    public void setLastEvaluatedAt(Instant lastEvaluatedAt) {
        this.lastEvaluatedAt = lastEvaluatedAt;
    }
}
