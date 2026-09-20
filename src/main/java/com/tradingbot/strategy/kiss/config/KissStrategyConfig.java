package com.tradingbot.strategy.kiss.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Configuration properties for the KISS (Keep It Swing Systematic) Multi-Timeframe Strategy. */
@Configuration
@ConfigurationProperties(prefix = "trading-bot.strategy.kiss")
public class KissStrategyConfig {

    private boolean enabled = true;
    private int emaPeriod = 55;
    private int macdFastPeriod = 12;
    private int macdSlowPeriod = 26;
    private int macdSignalPeriod = 9;
    private double riskRewardRatio = 3.0;
    private double maxRiskPerTradePct = 0.015; // 1.5% max account risk
    private double paperCapital = 500000.0;
    private int maxConcurrentPositions = 10;
    private String timeframe = "60"; // 60 minutes = 1 Hour
    private String stateFilePath = "data/kiss_strategy_state.json";
    private boolean enableWeekendExit = true;
    private long scanDelayMs = 50L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getEmaPeriod() {
        return emaPeriod;
    }

    public void setEmaPeriod(int emaPeriod) {
        this.emaPeriod = emaPeriod;
    }

    public int getMacdFastPeriod() {
        return macdFastPeriod;
    }

    public void setMacdFastPeriod(int macdFastPeriod) {
        this.macdFastPeriod = macdFastPeriod;
    }

    public int getMacdSlowPeriod() {
        return macdSlowPeriod;
    }

    public void setMacdSlowPeriod(int macdSlowPeriod) {
        this.macdSlowPeriod = macdSlowPeriod;
    }

    public int getMacdSignalPeriod() {
        return macdSignalPeriod;
    }

    public void setMacdSignalPeriod(int macdSignalPeriod) {
        this.macdSignalPeriod = macdSignalPeriod;
    }

    public double getRiskRewardRatio() {
        return riskRewardRatio;
    }

    public void setRiskRewardRatio(double riskRewardRatio) {
        this.riskRewardRatio = riskRewardRatio;
    }

    public double getMaxRiskPerTradePct() {
        return maxRiskPerTradePct;
    }

    public void setMaxRiskPerTradePct(double maxRiskPerTradePct) {
        this.maxRiskPerTradePct = maxRiskPerTradePct;
    }

    public double getPaperCapital() {
        return paperCapital;
    }

    public void setPaperCapital(double paperCapital) {
        this.paperCapital = paperCapital;
    }

    public int getMaxConcurrentPositions() {
        return maxConcurrentPositions;
    }

    public void setMaxConcurrentPositions(int maxConcurrentPositions) {
        this.maxConcurrentPositions = maxConcurrentPositions;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public void setTimeframe(String timeframe) {
        this.timeframe = timeframe;
    }

    public String getStateFilePath() {
        return stateFilePath;
    }

    public void setStateFilePath(String stateFilePath) {
        this.stateFilePath = stateFilePath;
    }

    public boolean isEnableWeekendExit() {
        return enableWeekendExit;
    }

    public void setEnableWeekendExit(boolean enableWeekendExit) {
        this.enableWeekendExit = enableWeekendExit;
    }

    public long getScanDelayMs() {
        return scanDelayMs;
    }

    public void setScanDelayMs(long scanDelayMs) {
        this.scanDelayMs = scanDelayMs;
    }
}
