package com.tradingbot.positional.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Configuration properties for the Bollinger Band & Heikin-Ashi Positional Strategy. */
@Configuration
@ConfigurationProperties(prefix = "trading-bot.positional")
public class PositionalStrategyConfig {

    private boolean enabled = true;
    private String cron = "0 0 15 * * MON-FRI";
    private String symbol = "NIFTY 50";
    private String executionMode = "MANUAL_CONFIRMATION"; // AUTO or MANUAL_CONFIRMATION
    private int numLots = 10;
    private int quantity = 65; // Nifty lot size
    private int hedgeOffsetPoints = 300; // 0.20 Delta hedge offset (~300 pts OTM)
    private double maxRiskRupees = 100000.0;
    private String stateFilePath = "data/bb_rsi_positional_state.json";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    public boolean isAutoExecution() {
        return "AUTO".equalsIgnoreCase(this.executionMode);
    }

    public int getNumLots() {
        return numLots;
    }

    public void setNumLots(int numLots) {
        this.numLots = numLots;
    }

    public int getLotSize() {
        return numLots;
    }

    public void setLotSize(int lotSize) {
        this.numLots = lotSize;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public int getTotalQuantity() {
        return numLots * quantity;
    }

    public int getHedgeOffsetPoints() {
        return hedgeOffsetPoints;
    }

    public void setHedgeOffsetPoints(int hedgeOffsetPoints) {
        this.hedgeOffsetPoints = hedgeOffsetPoints;
    }

    public double getMaxRiskRupees() {
        return maxRiskRupees;
    }

    public void setMaxRiskRupees(double maxRiskRupees) {
        this.maxRiskRupees = maxRiskRupees;
    }

    public String getStateFilePath() {
        return stateFilePath;
    }

    public void setStateFilePath(String stateFilePath) {
        this.stateFilePath = stateFilePath;
    }
}
