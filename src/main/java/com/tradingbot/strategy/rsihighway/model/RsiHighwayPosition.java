package com.tradingbot.strategy.rsihighway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregate model tracking an active swing position, its executed tranches, average price, stop
 * losses, and trailing state.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RsiHighwayPosition {

    private String symbol;
    private String exchange = "NSE";
    private List<RsiHighwayTranche> tranches = new ArrayList<>();
    private int totalQuantity = 0;
    private double averagePrice = 0.0;
    private double initialSlPrice = 0.0;
    private double currentSlPrice = 0.0;
    private double highestPriceSeen = 0.0;
    private double highestDailyRsiSeen = 0.0;
    private Instant createdAt = Instant.now();
    private Instant lastEvaluatedAt = Instant.now();
    private boolean active = true;
    private double currentLtp = 0.0;
    private double exitPrice = 0.0;
    private Instant exitTime;
    private String exitReason;
    private double realizedPnl = 0.0;

    public RsiHighwayPosition() {}

    public RsiHighwayPosition(
            String symbol, String exchange, double initialPrice, double initialSlPrice) {
        this.symbol = symbol;
        this.exchange = exchange;
        this.averagePrice = initialPrice;
        this.currentLtp = initialPrice;
        this.initialSlPrice = initialSlPrice;
        this.currentSlPrice = initialSlPrice;
        this.highestPriceSeen = initialPrice;
        this.createdAt = Instant.now();
        this.lastEvaluatedAt = Instant.now();
        this.active = true;
    }

    public synchronized void addTranche(RsiHighwayTranche tranche) {
        if (tranche == null || tranche.quantity() <= 0) return;

        double totalCost =
                (this.averagePrice * this.totalQuantity)
                        + (tranche.entryPrice() * tranche.quantity());
        this.totalQuantity += tranche.quantity();
        this.averagePrice = totalCost / this.totalQuantity;
        this.tranches.add(tranche);
        this.highestPriceSeen = Math.max(this.highestPriceSeen, tranche.entryPrice());
        this.currentLtp = Math.max(this.currentLtp, tranche.entryPrice());
        this.lastEvaluatedAt = Instant.now();
    }

    public synchronized void updateMarketPrice(double ltp) {
        if (ltp > 0) {
            this.currentLtp = ltp;
            this.highestPriceSeen = Math.max(this.highestPriceSeen, ltp);
            this.lastEvaluatedAt = Instant.now();
        }
    }

    public synchronized void closePosition(double exitPrice, String exitReason, Instant exitTime) {
        this.active = false;
        this.exitPrice = exitPrice;
        this.currentLtp = exitPrice;
        this.exitReason = exitReason;
        this.exitTime = exitTime != null ? exitTime : Instant.now();
        this.lastEvaluatedAt = this.exitTime;
        this.realizedPnl = (exitPrice - this.averagePrice) * this.totalQuantity;
    }

    public double getUnrealizedPnl() {
        if (!active || currentLtp <= 0.0 || totalQuantity <= 0) {
            return 0.0;
        }
        return (currentLtp - averagePrice) * totalQuantity;
    }

    public int getTrancheCount() {
        return tranches.size();
    }

    // Getters and Setters
    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getExchange() {
        return exchange;
    }

    public void setExchange(String exchange) {
        this.exchange = exchange;
    }

    public List<RsiHighwayTranche> getTranches() {
        return tranches;
    }

    public void setTranches(List<RsiHighwayTranche> tranches) {
        this.tranches = tranches;
    }

    public int getTotalQuantity() {
        return totalQuantity;
    }

    public void setTotalQuantity(int totalQuantity) {
        this.totalQuantity = totalQuantity;
    }

    public double getAveragePrice() {
        return averagePrice;
    }

    public void setAveragePrice(double averagePrice) {
        this.averagePrice = averagePrice;
    }

    public double getInitialSlPrice() {
        return initialSlPrice;
    }

    public void setInitialSlPrice(double initialSlPrice) {
        this.initialSlPrice = initialSlPrice;
    }

    public double getCurrentSlPrice() {
        return currentSlPrice;
    }

    public void setCurrentSlPrice(double currentSlPrice) {
        this.currentSlPrice = currentSlPrice;
    }

    public double getHighestPriceSeen() {
        return highestPriceSeen;
    }

    public void setHighestPriceSeen(double highestPriceSeen) {
        this.highestPriceSeen = highestPriceSeen;
    }

    public double getHighestDailyRsiSeen() {
        return highestDailyRsiSeen;
    }

    public void setHighestDailyRsiSeen(double highestDailyRsiSeen) {
        this.highestDailyRsiSeen = highestDailyRsiSeen;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastEvaluatedAt() {
        return lastEvaluatedAt;
    }

    public void setLastEvaluatedAt(Instant lastEvaluatedAt) {
        this.lastEvaluatedAt = lastEvaluatedAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public double getCurrentLtp() {
        return currentLtp;
    }

    public void setCurrentLtp(double currentLtp) {
        this.currentLtp = currentLtp;
    }

    public double getExitPrice() {
        return exitPrice;
    }

    public void setExitPrice(double exitPrice) {
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

    public double getRealizedPnl() {
        return realizedPnl;
    }

    public void setRealizedPnl(double realizedPnl) {
        this.realizedPnl = realizedPnl;
    }
}
