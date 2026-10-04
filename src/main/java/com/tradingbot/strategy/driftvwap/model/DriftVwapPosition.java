package com.tradingbot.strategy.driftvwap.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** Represents an active or closed Directional Option Selling position under the Drift VWAP strategy. */
public class DriftVwapPosition {

    private final String tradeId;
    private final String underlyingSymbol;
    private final String optionType; // "CE" or "PE"
    private final String contractSymbol; // e.g. "NIFTY26OCT25000PE"
    private final BigDecimal strikePrice;
    private final int lots;
    private final int lotSize;
    private final DriftDirection direction;
    private final BigDecimal entrySpotPrice;
    private final BigDecimal entryPremium;
    private final BigDecimal slPremium;
    private final BigDecimal targetPremium;
    private final int quantity; // lots * lotSize
    private final BigDecimal plannedRisk;
    private final Instant entryTime;

    private String brokerTradingSymbol;
    private BigDecimal exitPremium;
    private Instant exitTime;
    private BigDecimal realizedPnl = BigDecimal.ZERO;
    private String exitReason;
    private boolean closed = false;

    public DriftVwapPosition(
            String tradeId,
            String underlyingSymbol,
            String optionType,
            String contractSymbol,
            BigDecimal strikePrice,
            int lots,
            int lotSize,
            DriftDirection direction,
            BigDecimal entrySpotPrice,
            BigDecimal entryPremium,
            BigDecimal slPremium,
            BigDecimal targetPremium,
            int quantity,
            BigDecimal plannedRisk,
            Instant entryTime) {
        this.tradeId = tradeId;
        this.underlyingSymbol = underlyingSymbol;
        this.optionType = optionType;
        this.contractSymbol = contractSymbol;
        this.strikePrice = strikePrice;
        this.lots = lots;
        this.lotSize = lotSize;
        this.direction = direction;
        this.entrySpotPrice = entrySpotPrice;
        this.entryPremium = entryPremium;
        this.slPremium = slPremium;
        this.targetPremium = targetPremium;
        this.quantity = quantity;
        this.plannedRisk = plannedRisk;
        this.entryTime = entryTime != null ? entryTime : Instant.now();
    }

    public synchronized void close(BigDecimal exitPrice, String reason, Instant time) {
        if (this.closed) return;
        this.exitPremium = exitPrice != null ? exitPrice : this.entryPremium;
        this.exitReason = reason;
        this.exitTime = time != null ? time : Instant.now();
        this.closed = true;

        // Option Selling P&L: (entryPremium - exitPremium) * quantity
        BigDecimal points = this.entryPremium.subtract(this.exitPremium);
        this.realizedPnl =
                points.multiply(BigDecimal.valueOf(this.quantity))
                        .setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal calculateUnrealizedPnl(BigDecimal currentPremium) {
        if (this.closed || currentPremium == null) return this.realizedPnl;
        BigDecimal points = this.entryPremium.subtract(currentPremium);
        return points.multiply(BigDecimal.valueOf(this.quantity))
                .setScale(2, RoundingMode.HALF_UP);
    }

    // Getters and Setters
    public String getTradeId() {
        return tradeId;
    }

    public String getUnderlyingSymbol() {
        return underlyingSymbol;
    }

    public String getOptionType() {
        return optionType;
    }

    public String getContractSymbol() {
        return contractSymbol;
    }

    public BigDecimal getStrikePrice() {
        return strikePrice;
    }

    public int getLots() {
        return lots;
    }

    public int getLotSize() {
        return lotSize;
    }

    public DriftDirection getDirection() {
        return direction;
    }

    public BigDecimal getEntrySpotPrice() {
        return entrySpotPrice;
    }

    public BigDecimal getEntryPremium() {
        return entryPremium;
    }

    public BigDecimal getSlPremium() {
        return slPremium;
    }

    public BigDecimal getTargetPremium() {
        return targetPremium;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getPlannedRisk() {
        return plannedRisk;
    }

    public Instant getEntryTime() {
        return entryTime;
    }

    public String getBrokerTradingSymbol() {
        return brokerTradingSymbol;
    }

    public void setBrokerTradingSymbol(String brokerTradingSymbol) {
        this.brokerTradingSymbol = brokerTradingSymbol;
    }

    public BigDecimal getExitPremium() {
        return exitPremium;
    }

    public Instant getExitTime() {
        return exitTime;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    public String getExitReason() {
        return exitReason;
    }

    public boolean isClosed() {
        return closed;
    }
}
