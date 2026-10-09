package com.tradingbot.strategy.commodity.model;

import java.math.BigDecimal;
import java.time.Instant;

/** Encapsulates the intraday state, bias, trigger levels, and lifecycle of a commodity setup. */
public class CommoditySetup {

    private final String symbol;
    private volatile CommodityBias bias = CommodityBias.NEUTRAL;
    private volatile Double pcr;
    private volatile CommoditySetupState state = CommoditySetupState.IDLE;
    private volatile BigDecimal triggerHigh;
    private volatile BigDecimal triggerLow;
    private volatile BigDecimal vwapAtSetup;
    private volatile Instant armedTime;
    private volatile int tradesToday = 0;
    private volatile CommodityTradePosition activePosition;

    public CommoditySetup(String symbol) {
        this.symbol = symbol;
    }

    public void updateBias(CommodityBias bias, Double pcr) {
        this.bias = bias;
        this.pcr = pcr;
        if (bias == CommodityBias.NEUTRAL) {
            this.state = CommoditySetupState.SKIPPED;
        } else {
            this.state = CommoditySetupState.BIAS_IDENTIFIED;
        }
    }

    public void armLong(BigDecimal triggerHigh, BigDecimal vwapAtSetup, Instant armedTime) {
        this.triggerHigh = triggerHigh;
        this.vwapAtSetup = vwapAtSetup;
        this.armedTime = armedTime;
        this.state = CommoditySetupState.ARMED_LONG;
    }

    public void armShort(BigDecimal triggerLow, BigDecimal vwapAtSetup, Instant armedTime) {
        this.triggerLow = triggerLow;
        this.vwapAtSetup = vwapAtSetup;
        this.armedTime = armedTime;
        this.state = CommoditySetupState.ARMED_SHORT;
    }

    public void enterTrade(CommodityTradePosition position) {
        this.activePosition = position;
        this.state = CommoditySetupState.IN_TRADE;
        this.tradesToday++;
    }

    public void completeTrade(CommodityTradePosition closedPosition) {
        this.activePosition = closedPosition;
        this.state = CommoditySetupState.COMPLETED;
    }

    public void reset() {
        this.bias = CommodityBias.NEUTRAL;
        this.pcr = null;
        this.state = CommoditySetupState.IDLE;
        this.triggerHigh = null;
        this.triggerLow = null;
        this.vwapAtSetup = null;
        this.armedTime = null;
        this.tradesToday = 0;
        this.activePosition = null;
    }

    public String getSymbol() {
        return symbol;
    }

    public CommodityBias getBias() {
        return bias;
    }

    public Double getPcr() {
        return pcr;
    }

    public CommoditySetupState getState() {
        return state;
    }

    public void setState(CommoditySetupState state) {
        this.state = state;
    }

    public BigDecimal getTriggerHigh() {
        return triggerHigh;
    }

    public BigDecimal getTriggerLow() {
        return triggerLow;
    }

    public BigDecimal getVwapAtSetup() {
        return vwapAtSetup;
    }

    public Instant getArmedTime() {
        return armedTime;
    }

    public int getTradesToday() {
        return tradesToday;
    }

    public void setTradesToday(int tradesToday) {
        this.tradesToday = tradesToday;
    }

    public CommodityTradePosition getActivePosition() {
        return activePosition;
    }

    public void setActivePosition(CommodityTradePosition activePosition) {
        this.activePosition = activePosition;
    }
}
