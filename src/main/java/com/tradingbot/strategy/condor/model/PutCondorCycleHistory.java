package com.tradingbot.strategy.condor.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Historical record of a completed monthly Put Condor cycle for SQLite archiving. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PutCondorCycleHistory {

    private Long id;
    private String cycleMonth;
    private LocalDate entryDate;
    private LocalDate exitDate;
    private BigDecimal entrySpot = BigDecimal.ZERO;
    private BigDecimal exitSpot = BigDecimal.ZERO;
    private int lots;
    private int totalQuantity;
    private BigDecimal initialNetDebitRs = BigDecimal.ZERO;
    private BigDecimal realizedPnlRs = BigDecimal.ZERO;
    private BigDecimal roiPct = BigDecimal.ZERO;
    private BigDecimal maxDrawdownRs = BigDecimal.ZERO;
    private String adjustmentsSummary;
    private String exitReason;
    private Instant createdAt;

    public PutCondorCycleHistory() {
        this.createdAt = Instant.now();
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCycleMonth() {
        return cycleMonth;
    }

    public void setCycleMonth(String cycleMonth) {
        this.cycleMonth = cycleMonth;
    }

    public LocalDate getEntryDate() {
        return entryDate;
    }

    public void setEntryDate(LocalDate entryDate) {
        this.entryDate = entryDate;
    }

    public LocalDate getExitDate() {
        return exitDate;
    }

    public void setExitDate(LocalDate exitDate) {
        this.exitDate = exitDate;
    }

    public BigDecimal getEntrySpot() {
        return entrySpot;
    }

    public void setEntrySpot(BigDecimal entrySpot) {
        this.entrySpot = entrySpot;
    }

    public BigDecimal getExitSpot() {
        return exitSpot;
    }

    public void setExitSpot(BigDecimal exitSpot) {
        this.exitSpot = exitSpot;
    }

    public int getLots() {
        return lots;
    }

    public void setLots(int lots) {
        this.lots = lots;
    }

    public int getTotalQuantity() {
        return totalQuantity;
    }

    public void setTotalQuantity(int totalQuantity) {
        this.totalQuantity = totalQuantity;
    }

    public BigDecimal getInitialNetDebitRs() {
        return initialNetDebitRs;
    }

    public void setInitialNetDebitRs(BigDecimal initialNetDebitRs) {
        this.initialNetDebitRs = initialNetDebitRs;
    }

    public BigDecimal getRealizedPnlRs() {
        return realizedPnlRs;
    }

    public void setRealizedPnlRs(BigDecimal realizedPnlRs) {
        this.realizedPnlRs = realizedPnlRs;
    }

    public BigDecimal getRoiPct() {
        return roiPct;
    }

    public void setRoiPct(BigDecimal roiPct) {
        this.roiPct = roiPct;
    }

    public BigDecimal getMaxDrawdownRs() {
        return maxDrawdownRs;
    }

    public void setMaxDrawdownRs(BigDecimal maxDrawdownRs) {
        this.maxDrawdownRs = maxDrawdownRs;
    }

    public String getAdjustmentsSummary() {
        return adjustmentsSummary;
    }

    public void setAdjustmentsSummary(String adjustmentsSummary) {
        this.adjustmentsSummary = adjustmentsSummary;
    }

    public String getExitReason() {
        return exitReason;
    }

    public void setExitReason(String exitReason) {
        this.exitReason = exitReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
