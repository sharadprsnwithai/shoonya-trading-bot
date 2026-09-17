package com.tradingbot.strategy.rsihighway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the RSI Highway Multi-Timeframe Swing Strategy.
 */
@Configuration
@ConfigurationProperties(prefix = "trading-bot.strategy.rsi-highway")
public class RsiHighwayConfig {

    private boolean enabled = true;
    private String eodScanCron = "0 0 15 * * MON-FRI";
    private String morningCheckCron = "0 30 9 * * MON-FRI";
    private double monthlyRsiThreshold = 60.0;
    private double weeklyRsiThreshold = 60.0;
    private double dailyRsiLower = 48.0;
    private double dailyRsiUpper = 55.0;
    private double dailyRsiExit = 50.0;
    private double morningEmergencyRsi = 45.0;
    private int maxTranches = 3;
    private int maxConcurrentPositions = 10;
    private double riskPerTradePercent = 1.0;
    private double maxCapitalPerStockPercent = 10.0;
    private double paperCapital = 1000000.0;
    private boolean paperTrading = true;
    private int min52wLeaders = 5;
    private double maxIndexDrawdownPct = 0.20;
    private String stateFilePath = "data/rsi_highway_state.json";
    private boolean telegramAlerts = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEodScanCron() {
        return eodScanCron;
    }

    public void setEodScanCron(String eodScanCron) {
        this.eodScanCron = eodScanCron;
    }

    public String getMorningCheckCron() {
        return morningCheckCron;
    }

    public void setMorningCheckCron(String morningCheckCron) {
        this.morningCheckCron = morningCheckCron;
    }

    public double getMonthlyRsiThreshold() {
        return monthlyRsiThreshold;
    }

    public void setMonthlyRsiThreshold(double monthlyRsiThreshold) {
        this.monthlyRsiThreshold = monthlyRsiThreshold;
    }

    public double getWeeklyRsiThreshold() {
        return weeklyRsiThreshold;
    }

    public void setWeeklyRsiThreshold(double weeklyRsiThreshold) {
        this.weeklyRsiThreshold = weeklyRsiThreshold;
    }

    public double getDailyRsiLower() {
        return dailyRsiLower;
    }

    public void setDailyRsiLower(double dailyRsiLower) {
        this.dailyRsiLower = dailyRsiLower;
    }

    public double getDailyRsiUpper() {
        return dailyRsiUpper;
    }

    public void setDailyRsiUpper(double dailyRsiUpper) {
        this.dailyRsiUpper = dailyRsiUpper;
    }

    public double getDailyRsiExit() {
        return dailyRsiExit;
    }

    public void setDailyRsiExit(double dailyRsiExit) {
        this.dailyRsiExit = dailyRsiExit;
    }

    public double getMorningEmergencyRsi() {
        return morningEmergencyRsi;
    }

    public void setMorningEmergencyRsi(double morningEmergencyRsi) {
        this.morningEmergencyRsi = morningEmergencyRsi;
    }

    public int getMaxTranches() {
        return maxTranches;
    }

    public void setMaxTranches(int maxTranches) {
        this.maxTranches = maxTranches;
    }

    public int getMaxConcurrentPositions() {
        return maxConcurrentPositions;
    }

    public void setMaxConcurrentPositions(int maxConcurrentPositions) {
        this.maxConcurrentPositions = maxConcurrentPositions;
    }

    public double getRiskPerTradePercent() {
        return riskPerTradePercent;
    }

    public void setRiskPerTradePercent(double riskPerTradePercent) {
        this.riskPerTradePercent = riskPerTradePercent;
    }

    public double getMaxCapitalPerStockPercent() {
        return maxCapitalPerStockPercent;
    }

    public void setMaxCapitalPerStockPercent(double maxCapitalPerStockPercent) {
        this.maxCapitalPerStockPercent = maxCapitalPerStockPercent;
    }

    public double getPaperCapital() {
        return paperCapital;
    }

    public void setPaperCapital(double paperCapital) {
        this.paperCapital = paperCapital;
    }

    public boolean isPaperTrading() {
        return paperTrading;
    }

    public void setPaperTrading(boolean paperTrading) {
        this.paperTrading = paperTrading;
    }

    public int getMin52wLeaders() {
        return min52wLeaders;
    }

    public void setMin52wLeaders(int min52wLeaders) {
        this.min52wLeaders = min52wLeaders;
    }

    public double getMaxIndexDrawdownPct() {
        return maxIndexDrawdownPct;
    }

    public void setMaxIndexDrawdownPct(double maxIndexDrawdownPct) {
        this.maxIndexDrawdownPct = maxIndexDrawdownPct;
    }

    public String getStateFilePath() {
        return stateFilePath;
    }

    public void setStateFilePath(String stateFilePath) {
        this.stateFilePath = stateFilePath;
    }

    public boolean isTelegramAlerts() {
        return telegramAlerts;
    }

    public void setTelegramAlerts(boolean telegramAlerts) {
        this.telegramAlerts = telegramAlerts;
    }
}
