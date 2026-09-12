package com.tradingbot.model.strategy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Domain model representing a multi-day positional hedged option selling trade
 * (Bull Put Spread or Bear Call Spread) based on the 19-period Daily WMA strategy.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DailyWmaPosition {

    private String tradeId;
    private String symbol;
    private String action; // SELL
    private String optionType; // PE or CE
    private String bias; // BULLISH or BEARISH

    private LocalDate entryDate;
    private Instant entryTime;
    private BigDecimal entrySpot;
    private BigDecimal wma19AtEntry;
    private LocalDate expiryDate;

    // Short Leg
    private String shortSymbol;
    private BigDecimal shortStrike;
    private BigDecimal shortEntryPrice;
    private Double shortDelta;
    private int quantity;

    // Hedge Leg (2.0% OTM)
    private String hedgeSymbol;
    private BigDecimal hedgeStrike;
    private BigDecimal hedgeEntryPrice;
    private int hedgeQuantity;

    private BigDecimal netCredit;
    private BigDecimal stopLossPrice;
    private int reEntryCount;

    // Exit State
    private boolean isClosed;
    private LocalDate exitDate;
    private Instant exitTime;
    private BigDecimal exitSpot;
    private BigDecimal shortExitPrice;
    private BigDecimal hedgeExitPrice;
    private BigDecimal realizedPnl;
    private String exitReason;

    public DailyWmaPosition() {}

    public DailyWmaPosition(
            String tradeId,
            String symbol,
            String action,
            String optionType,
            String bias,
            LocalDate entryDate,
            Instant entryTime,
            BigDecimal entrySpot,
            BigDecimal wma19AtEntry,
            LocalDate expiryDate,
            String shortSymbol,
            BigDecimal shortStrike,
            BigDecimal shortEntryPrice,
            Double shortDelta,
            int quantity,
            String hedgeSymbol,
            BigDecimal hedgeStrike,
            BigDecimal hedgeEntryPrice,
            int hedgeQuantity,
            BigDecimal netCredit,
            BigDecimal stopLossPrice,
            int reEntryCount) {
        this.tradeId = tradeId;
        this.symbol = symbol;
        this.action = action;
        this.optionType = optionType;
        this.bias = bias;
        this.entryDate = entryDate;
        this.entryTime = entryTime;
        this.entrySpot = entrySpot;
        this.wma19AtEntry = wma19AtEntry;
        this.expiryDate = expiryDate;
        this.shortSymbol = shortSymbol;
        this.shortStrike = shortStrike;
        this.shortEntryPrice = shortEntryPrice;
        this.shortDelta = shortDelta;
        this.quantity = quantity;
        this.hedgeSymbol = hedgeSymbol;
        this.hedgeStrike = hedgeStrike;
        this.hedgeEntryPrice = hedgeEntryPrice;
        this.hedgeQuantity = hedgeQuantity;
        this.netCredit = netCredit;
        this.stopLossPrice = stopLossPrice;
        this.reEntryCount = reEntryCount;
        this.isClosed = false;
    }

    /**
     * Calculates total spread P&L given current mark-to-market prices of both legs.
     */
    public BigDecimal calculateSpreadPnl(BigDecimal currentShortLtp, BigDecimal currentHedgeLtp) {
        BigDecimal shortDiff = shortEntryPrice.subtract(currentShortLtp);
        BigDecimal shortPnl = shortDiff.multiply(BigDecimal.valueOf(quantity));

        BigDecimal hedgeDiff = (currentHedgeLtp != null && hedgeEntryPrice != null)
                ? currentHedgeLtp.subtract(hedgeEntryPrice)
                : BigDecimal.ZERO;
        BigDecimal hedgePnl = hedgeDiff.multiply(BigDecimal.valueOf(hedgeQuantity));

        return shortPnl.add(hedgePnl).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Closes the multi-leg position.
     */
    public void close(
            BigDecimal shortExitPrice,
            BigDecimal hedgeExitPrice,
            String exitReason,
            Instant exitTime,
            LocalDate exitDate,
            BigDecimal exitSpot) {
        this.isClosed = true;
        this.shortExitPrice = shortExitPrice;
        this.hedgeExitPrice = hedgeExitPrice;
        this.exitReason = exitReason;
        this.exitTime = exitTime;
        this.exitDate = exitDate;
        this.exitSpot = exitSpot;
        this.realizedPnl = calculateSpreadPnl(shortExitPrice, hedgeExitPrice);
    }

    // Getters and Setters
    public String getTradeId() { return tradeId; }
    public void setTradeId(String tradeId) { this.tradeId = tradeId; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getOptionType() { return optionType; }
    public void setOptionType(String optionType) { this.optionType = optionType; }

    public String getBias() { return bias; }
    public void setBias(String bias) { this.bias = bias; }

    public LocalDate getEntryDate() { return entryDate; }
    public void setEntryDate(LocalDate entryDate) { this.entryDate = entryDate; }

    public Instant getEntryTime() { return entryTime; }
    public void setEntryTime(Instant entryTime) { this.entryTime = entryTime; }

    public BigDecimal getEntrySpot() { return entrySpot; }
    public void setEntrySpot(BigDecimal entrySpot) { this.entrySpot = entrySpot; }

    public BigDecimal getWma19AtEntry() { return wma19AtEntry; }
    public void setWma19AtEntry(BigDecimal wma19AtEntry) { this.wma19AtEntry = wma19AtEntry; }

    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }

    public String getShortSymbol() { return shortSymbol; }
    public void setShortSymbol(String shortSymbol) { this.shortSymbol = shortSymbol; }

    public BigDecimal getShortStrike() { return shortStrike; }
    public void setShortStrike(BigDecimal shortStrike) { this.shortStrike = shortStrike; }

    public BigDecimal getShortEntryPrice() { return shortEntryPrice; }
    public void setShortEntryPrice(BigDecimal shortEntryPrice) { this.shortEntryPrice = shortEntryPrice; }

    public Double getShortDelta() { return shortDelta; }
    public void setShortDelta(Double shortDelta) { this.shortDelta = shortDelta; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public String getHedgeSymbol() { return hedgeSymbol; }
    public void setHedgeSymbol(String hedgeSymbol) { this.hedgeSymbol = hedgeSymbol; }

    public BigDecimal getHedgeStrike() { return hedgeStrike; }
    public void setHedgeStrike(BigDecimal hedgeStrike) { this.hedgeStrike = hedgeStrike; }

    public BigDecimal getHedgeEntryPrice() { return hedgeEntryPrice; }
    public void setHedgeEntryPrice(BigDecimal hedgeEntryPrice) { this.hedgeEntryPrice = hedgeEntryPrice; }

    public int getHedgeQuantity() { return hedgeQuantity; }
    public void setHedgeQuantity(int hedgeQuantity) { this.hedgeQuantity = hedgeQuantity; }

    public BigDecimal getNetCredit() { return netCredit; }
    public void setNetCredit(BigDecimal netCredit) { this.netCredit = netCredit; }

    public BigDecimal getStopLossPrice() { return stopLossPrice; }
    public void setStopLossPrice(BigDecimal stopLossPrice) { this.stopLossPrice = stopLossPrice; }

    public int getReEntryCount() { return reEntryCount; }
    public void setReEntryCount(int reEntryCount) { this.reEntryCount = reEntryCount; }

    public boolean isClosed() { return isClosed; }
    public void setClosed(boolean closed) { isClosed = closed; }

    public LocalDate getExitDate() { return exitDate; }
    public void setExitDate(LocalDate exitDate) { this.exitDate = exitDate; }

    public Instant getExitTime() { return exitTime; }
    public void setExitTime(Instant exitTime) { this.exitTime = exitTime; }

    public BigDecimal getExitSpot() { return exitSpot; }
    public void setExitSpot(BigDecimal exitSpot) { this.exitSpot = exitSpot; }

    public BigDecimal getShortExitPrice() { return shortExitPrice; }
    public void setShortExitPrice(BigDecimal shortExitPrice) { this.shortExitPrice = shortExitPrice; }

    public BigDecimal getHedgeExitPrice() { return hedgeExitPrice; }
    public void setHedgeExitPrice(BigDecimal hedgeExitPrice) { this.hedgeExitPrice = hedgeExitPrice; }

    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public void setRealizedPnl(BigDecimal realizedPnl) { this.realizedPnl = realizedPnl; }

    public String getExitReason() { return exitReason; }
    public void setExitReason(String exitReason) { this.exitReason = exitReason; }
}
