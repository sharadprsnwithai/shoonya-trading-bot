package com.tradingbot.strategy.condor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the Monthly Asymmetric Put Condor Strategy.
 */
@Configuration
@ConfigurationProperties(prefix = "trading-bot.strategy.put-condor")
public class MonthlyPutCondorProperties {

    /** Whether the monthly put condor strategy is enabled */
    private boolean enabled = true;

    /** Execution mode: PAPER or LIVE */
    private String executionMode = "PAPER";

    /** Position size in lots (e.g., 2, 5, 50, 70) */
    private int lots = 50;

    /** Lot size for NIFTY 50 options (default 65) */
    private int lotSize = 65;

    /** Strike interval width in points (default 200 pts) */
    private int strikeWidth = 200;

    /** Target profit percentage on allocated margin (default +6.0%) */
    private double targetProfitPct = 6.0;

    /** Early exit profit target in expiry week (Adjustment D: +4.5%) */
    private double earlyExitTargetPct = 4.5;

    /** Days before monthly expiry to enable early profit locking (default 3 days) */
    private int earlyExitDaysBeforeExpiry = 3;

    /** Hard stop-loss floor percentage (default 3.0%) */
    private double stopLossPct = 3.0;

    /** Points move above ATM to trigger Adjustment A upside spread (default 150 pts) */
    private int upsideTriggerPts = 150;

    /** Morning entry time on 1st trading day (e.g., "10:30") */
    private String entryTime = "10:30";

    /** Polling interval for real-time MTM and adjustment checks in seconds */
    private int monitorIntervalSeconds = 60;

    /** Whether to send real-time Telegram alerts */
    private boolean telegramAlerts = true;

    /** Maximum allowable quantity per order on NSE (default 1800 qty) */
    private int maxFreezeLimit = 1800;

    // Getters and Setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    public int getLots() {
        return lots;
    }

    public void setLots(int lots) {
        this.lots = lots;
    }

    public int getLotSize() {
        return lotSize;
    }

    public void setLotSize(int lotSize) {
        this.lotSize = lotSize;
    }

    public int getStrikeWidth() {
        return strikeWidth;
    }

    public void setStrikeWidth(int strikeWidth) {
        this.strikeWidth = strikeWidth;
    }

    public double getTargetProfitPct() {
        return targetProfitPct;
    }

    public void setTargetProfitPct(double targetProfitPct) {
        this.targetProfitPct = targetProfitPct;
    }

    public double getEarlyExitTargetPct() {
        return earlyExitTargetPct;
    }

    public void setEarlyExitTargetPct(double earlyExitTargetPct) {
        this.earlyExitTargetPct = earlyExitTargetPct;
    }

    public int getEarlyExitDaysBeforeExpiry() {
        return earlyExitDaysBeforeExpiry;
    }

    public void setEarlyExitDaysBeforeExpiry(int earlyExitDaysBeforeExpiry) {
        this.earlyExitDaysBeforeExpiry = earlyExitDaysBeforeExpiry;
    }

    public double getStopLossPct() {
        return stopLossPct;
    }

    public void setStopLossPct(double stopLossPct) {
        this.stopLossPct = stopLossPct;
    }

    public int getUpsideTriggerPts() {
        return upsideTriggerPts;
    }

    public void setUpsideTriggerPts(int upsideTriggerPts) {
        this.upsideTriggerPts = upsideTriggerPts;
    }

    public String getEntryTime() {
        return entryTime;
    }

    public void setEntryTime(String entryTime) {
        this.entryTime = entryTime;
    }

    public int getMonitorIntervalSeconds() {
        return monitorIntervalSeconds;
    }

    public void setMonitorIntervalSeconds(int monitorIntervalSeconds) {
        this.monitorIntervalSeconds = monitorIntervalSeconds;
    }

    public boolean isTelegramAlerts() {
        return telegramAlerts;
    }

    public void setTelegramAlerts(boolean telegramAlerts) {
        this.telegramAlerts = telegramAlerts;
    }

    public int getMaxFreezeLimit() {
        return maxFreezeLimit;
    }

    public void setMaxFreezeLimit(int maxFreezeLimit) {
        this.maxFreezeLimit = maxFreezeLimit;
    }
}
