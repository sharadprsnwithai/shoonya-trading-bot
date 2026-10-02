package com.tradingbot.strategy.condor.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Tracks the state, strikes, tradingsymbols, entry prices, realized adjustments,
 * and real-time MTM of an active Monthly Asymmetric Put Condor position.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PutCondorPosition {

    private PutCondorState state = PutCondorState.IDLE;
    private LocalDate cycleExpiryDate;
    private Instant entryTimestamp;
    private BigDecimal entrySpotPrice = BigDecimal.ZERO;
    private int lots = 50;
    private int lotSize = 65;
    private int totalQuantity = 3250;

    // Base Condor Strikes (200-pt width)
    private int k1BuyStrike;
    private int k2SellStrike;
    private int k3SellStrike;
    private int k4BuyStrike;

    // Tradingsymbols (Zerodha / NFO format)
    private String k1Tradingsymbol;
    private String k2Tradingsymbol;
    private String k3Tradingsymbol;
    private String k4Tradingsymbol;

    // Entry Prices (Filled Average Prices)
    private BigDecimal k1EntryPrice = BigDecimal.ZERO;
    private BigDecimal k2EntryPrice = BigDecimal.ZERO;
    private BigDecimal k3EntryPrice = BigDecimal.ZERO;
    private BigDecimal k4EntryPrice = BigDecimal.ZERO;

    // Initial Net Debit
    private BigDecimal initialNetDebitPts = BigDecimal.ZERO;
    private BigDecimal initialNetDebitRs = BigDecimal.ZERO;

    // Adjustment A: Upside Spread (100-pt Bull Put Spread)
    private boolean upsideSpreadActive = false;
    private int upsideSellStrike;
    private int upsideBuyStrike;
    private String upsideSellTradingsymbol;
    private String upsideBuyTradingsymbol;
    private BigDecimal upsideSellEntryPrice = BigDecimal.ZERO;
    private BigDecimal upsideBuyEntryPrice = BigDecimal.ZERO;
    private BigDecimal upsideNetCreditPts = BigDecimal.ZERO;

    // Adjustment B: Sweet Spot Roll (K1 Shift)
    private boolean sweetSpotRollActive = false;
    private int activeK1Strike;
    private String activeK1Tradingsymbol;
    private BigDecimal activeK1EntryPrice = BigDecimal.ZERO;

    // Cash Ledger & MTM
    private BigDecimal realizedBookedProfitRs = BigDecimal.ZERO;
    private BigDecimal currentMtmRs = BigDecimal.ZERO;
    private BigDecimal maxDrawdownRs = BigDecimal.ZERO;
    private BigDecimal peakMtmRs = BigDecimal.ZERO;
    private Instant lastUpdated;
    private String exitReason;

    public PutCondorPosition() {
        this.lastUpdated = Instant.now();
    }

    /**
     * Computes and sets the initial net debit paid per share and in rupees.
     */
    public void calculateAndSetInitialDebit() {
        BigDecimal totalBuy = k1EntryPrice.add(k4EntryPrice);
        BigDecimal totalSell = k2EntryPrice.add(k3EntryPrice);
        this.initialNetDebitPts = totalBuy.subtract(totalSell);
        this.initialNetDebitRs = this.initialNetDebitPts.multiply(BigDecimal.valueOf(totalQuantity));
    }

    /**
     * Calculates the real-time MTM given current LTPs of all active legs.
     */
    public BigDecimal computeCurrentMtm(
            BigDecimal k1Ltp,
            BigDecimal k2Ltp,
            BigDecimal k3Ltp,
            BigDecimal k4Ltp,
            BigDecimal upSellLtp,
            BigDecimal upBuyLtp) {

        // Base Condor Value
        BigDecimal currentK1 = k1Ltp != null ? k1Ltp : BigDecimal.ZERO;
        BigDecimal currentK2 = k2Ltp != null ? k2Ltp : BigDecimal.ZERO;
        BigDecimal currentK3 = k3Ltp != null ? k3Ltp : BigDecimal.ZERO;
        BigDecimal currentK4 = k4Ltp != null ? k4Ltp : BigDecimal.ZERO;

        BigDecimal longPnl = (currentK1.subtract(activeK1EntryPrice.compareTo(BigDecimal.ZERO) > 0 ? activeK1EntryPrice : k1EntryPrice))
                .add(currentK4.subtract(k4EntryPrice));

        BigDecimal shortPnl = (k2EntryPrice.subtract(currentK2))
                .add(k3EntryPrice.subtract(currentK3));

        BigDecimal condorPnlPts = longPnl.add(shortPnl);

        // Upside Spread PnL
        BigDecimal upsidePnlPts = BigDecimal.ZERO;
        if (upsideSpreadActive && upSellLtp != null && upBuyLtp != null) {
            BigDecimal upShortPnl = upsideSellEntryPrice.subtract(upSellLtp);
            BigDecimal upLongPnl = upBuyLtp.subtract(upsideBuyEntryPrice);
            upsidePnlPts = upShortPnl.add(upLongPnl);
        }

        BigDecimal totalPts = condorPnlPts.add(upsidePnlPts);
        BigDecimal unrealizedRs = totalPts.multiply(BigDecimal.valueOf(totalQuantity));
        this.currentMtmRs = this.realizedBookedProfitRs.add(unrealizedRs).setScale(2, RoundingMode.HALF_UP);

        if (this.currentMtmRs.compareTo(this.peakMtmRs) > 0) {
            this.peakMtmRs = this.currentMtmRs;
        }
        if (this.currentMtmRs.compareTo(this.maxDrawdownRs) < 0) {
            this.maxDrawdownRs = this.currentMtmRs;
        }
        this.lastUpdated = Instant.now();
        return this.currentMtmRs;
    }

    // Getters and Setters

    public PutCondorState getState() {
        return state;
    }

    public void setState(PutCondorState state) {
        this.state = state;
    }

    public LocalDate getCycleExpiryDate() {
        return cycleExpiryDate;
    }

    public void setCycleExpiryDate(LocalDate cycleExpiryDate) {
        this.cycleExpiryDate = cycleExpiryDate;
    }

    public Instant getEntryTimestamp() {
        return entryTimestamp;
    }

    public void setEntryTimestamp(Instant entryTimestamp) {
        this.entryTimestamp = entryTimestamp;
    }

    public BigDecimal getEntrySpotPrice() {
        return entrySpotPrice;
    }

    public void setEntrySpotPrice(BigDecimal entrySpotPrice) {
        this.entrySpotPrice = entrySpotPrice;
    }

    public int getLots() {
        return lots;
    }

    public void setLots(int lots) {
        this.lots = lots;
        this.totalQuantity = this.lots * this.lotSize;
    }

    public int getLotSize() {
        return lotSize;
    }

    public void setLotSize(int lotSize) {
        this.lotSize = lotSize;
        this.totalQuantity = this.lots * this.lotSize;
    }

    public int getTotalQuantity() {
        return totalQuantity;
    }

    public void setTotalQuantity(int totalQuantity) {
        this.totalQuantity = totalQuantity;
    }

    public int getK1BuyStrike() {
        return k1BuyStrike;
    }

    public void setK1BuyStrike(int k1BuyStrike) {
        this.k1BuyStrike = k1BuyStrike;
    }

    public int getK2SellStrike() {
        return k2SellStrike;
    }

    public void setK2SellStrike(int k2SellStrike) {
        this.k2SellStrike = k2SellStrike;
    }

    public int getK3SellStrike() {
        return k3SellStrike;
    }

    public void setK3SellStrike(int k3SellStrike) {
        this.k3SellStrike = k3SellStrike;
    }

    public int getK4BuyStrike() {
        return k4BuyStrike;
    }

    public void setK4BuyStrike(int k4BuyStrike) {
        this.k4BuyStrike = k4BuyStrike;
    }

    public String getK1Tradingsymbol() {
        return k1Tradingsymbol;
    }

    public void setK1Tradingsymbol(String k1Tradingsymbol) {
        this.k1Tradingsymbol = k1Tradingsymbol;
    }

    public String getK2Tradingsymbol() {
        return k2Tradingsymbol;
    }

    public void setK2Tradingsymbol(String k2Tradingsymbol) {
        this.k2Tradingsymbol = k2Tradingsymbol;
    }

    public String getK3Tradingsymbol() {
        return k3Tradingsymbol;
    }

    public void setK3Tradingsymbol(String k3Tradingsymbol) {
        this.k3Tradingsymbol = k3Tradingsymbol;
    }

    public String getK4Tradingsymbol() {
        return k4Tradingsymbol;
    }

    public void setK4Tradingsymbol(String k4Tradingsymbol) {
        this.k4Tradingsymbol = k4Tradingsymbol;
    }

    public BigDecimal getK1EntryPrice() {
        return k1EntryPrice;
    }

    public void setK1EntryPrice(BigDecimal k1EntryPrice) {
        this.k1EntryPrice = k1EntryPrice;
    }

    public BigDecimal getK2EntryPrice() {
        return k2EntryPrice;
    }

    public void setK2EntryPrice(BigDecimal k2EntryPrice) {
        this.k2EntryPrice = k2EntryPrice;
    }

    public BigDecimal getK3EntryPrice() {
        return k3EntryPrice;
    }

    public void setK3EntryPrice(BigDecimal k3EntryPrice) {
        this.k3EntryPrice = k3EntryPrice;
    }

    public BigDecimal getK4EntryPrice() {
        return k4EntryPrice;
    }

    public void setK4EntryPrice(BigDecimal k4EntryPrice) {
        this.k4EntryPrice = k4EntryPrice;
    }

    public BigDecimal getInitialNetDebitPts() {
        return initialNetDebitPts;
    }

    public void setInitialNetDebitPts(BigDecimal initialNetDebitPts) {
        this.initialNetDebitPts = initialNetDebitPts;
    }

    public BigDecimal getInitialNetDebitRs() {
        return initialNetDebitRs;
    }

    public void setInitialNetDebitRs(BigDecimal initialNetDebitRs) {
        this.initialNetDebitRs = initialNetDebitRs;
    }

    public boolean isUpsideSpreadActive() {
        return upsideSpreadActive;
    }

    public void setUpsideSpreadActive(boolean upsideSpreadActive) {
        this.upsideSpreadActive = upsideSpreadActive;
    }

    public int getUpsideSellStrike() {
        return upsideSellStrike;
    }

    public void setUpsideSellStrike(int upsideSellStrike) {
        this.upsideSellStrike = upsideSellStrike;
    }

    public int getUpsideBuyStrike() {
        return upsideBuyStrike;
    }

    public void setUpsideBuyStrike(int upsideBuyStrike) {
        this.upsideBuyStrike = upsideBuyStrike;
    }

    public String getUpsideSellTradingsymbol() {
        return upsideSellTradingsymbol;
    }

    public void setUpsideSellTradingsymbol(String upsideSellTradingsymbol) {
        this.upsideSellTradingsymbol = upsideSellTradingsymbol;
    }

    public String getUpsideBuyTradingsymbol() {
        return upsideBuyTradingsymbol;
    }

    public void setUpsideBuyTradingsymbol(String upsideBuyTradingsymbol) {
        this.upsideBuyTradingsymbol = upsideBuyTradingsymbol;
    }

    public BigDecimal getUpsideSellEntryPrice() {
        return upsideSellEntryPrice;
    }

    public void setUpsideSellEntryPrice(BigDecimal upsideSellEntryPrice) {
        this.upsideSellEntryPrice = upsideSellEntryPrice;
    }

    public BigDecimal getUpsideBuyEntryPrice() {
        return upsideBuyEntryPrice;
    }

    public void setUpsideBuyEntryPrice(BigDecimal upsideBuyEntryPrice) {
        this.upsideBuyEntryPrice = upsideBuyEntryPrice;
    }

    public BigDecimal getUpsideNetCreditPts() {
        return upsideNetCreditPts;
    }

    public void setUpsideNetCreditPts(BigDecimal upsideNetCreditPts) {
        this.upsideNetCreditPts = upsideNetCreditPts;
    }

    public boolean isSweetSpotRollActive() {
        return sweetSpotRollActive;
    }

    public void setSweetSpotRollActive(boolean sweetSpotRollActive) {
        this.sweetSpotRollActive = sweetSpotRollActive;
    }

    public int getActiveK1Strike() {
        return activeK1Strike;
    }

    public void setActiveK1Strike(int activeK1Strike) {
        this.activeK1Strike = activeK1Strike;
    }

    public String getActiveK1Tradingsymbol() {
        return activeK1Tradingsymbol;
    }

    public void setActiveK1Tradingsymbol(String activeK1Tradingsymbol) {
        this.activeK1Tradingsymbol = activeK1Tradingsymbol;
    }

    public BigDecimal getActiveK1EntryPrice() {
        return activeK1EntryPrice;
    }

    public void setActiveK1EntryPrice(BigDecimal activeK1EntryPrice) {
        this.activeK1EntryPrice = activeK1EntryPrice;
    }

    public BigDecimal getRealizedBookedProfitRs() {
        return realizedBookedProfitRs;
    }

    public void setRealizedBookedProfitRs(BigDecimal realizedBookedProfitRs) {
        this.realizedBookedProfitRs = realizedBookedProfitRs;
    }

    public BigDecimal getCurrentMtmRs() {
        return currentMtmRs;
    }

    public void setCurrentMtmRs(BigDecimal currentMtmRs) {
        this.currentMtmRs = currentMtmRs;
    }

    public BigDecimal getMaxDrawdownRs() {
        return maxDrawdownRs;
    }

    public void setMaxDrawdownRs(BigDecimal maxDrawdownRs) {
        this.maxDrawdownRs = maxDrawdownRs;
    }

    public BigDecimal getPeakMtmRs() {
        return peakMtmRs;
    }

    public void setPeakMtmRs(BigDecimal peakMtmRs) {
        this.peakMtmRs = peakMtmRs;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Instant lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public String getExitReason() {
        return exitReason;
    }

    public void setExitReason(String exitReason) {
        this.exitReason = exitReason;
    }
}
