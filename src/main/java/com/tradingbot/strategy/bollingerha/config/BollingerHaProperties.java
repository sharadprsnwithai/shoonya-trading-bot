package com.tradingbot.strategy.bollingerha.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Configuration properties for the 1-minute Bollinger Bands Heikin-Ashi option buying strategy. */
@Configuration
@ConfigurationProperties(prefix = "trading-bot.strategy.bollinger-ha")
public class BollingerHaProperties {

    private boolean enabled = true;
    private String underlying = "NIFTY";
    private int timeframeMinutes = 1;
    private int bbPeriod = 20;
    private double bbStdDev = 2.0;
    private BigDecimal maxSlPoints = new BigDecimal("20.0");
    private BigDecimal minSlPoints = new BigDecimal("2.0");
    private BigDecimal bufferPoints = new BigDecimal("1.0");
    private BigDecimal riskRewardRatio = new BigDecimal("2.0");
    private int maxDailyTrades = 2;
    private String sizingMode = "FIXED_LOTS";
    private int defaultLots = 2;
    private BigDecimal fixedRiskAmount = new BigDecimal("2000.0");
    private String entryWindowStart = "09:15";
    private String entryWindowCutoff = "10:30";
    private String autoSquareOffTime = "15:15";
    private boolean trendFilterEnabled = true;
    private int trendEmaPeriod = 20;

    public boolean isTrendFilterEnabled() {
        return trendFilterEnabled;
    }

    public void setTrendFilterEnabled(boolean trendFilterEnabled) {
        this.trendFilterEnabled = trendFilterEnabled;
    }

    public int getTrendEmaPeriod() {
        return trendEmaPeriod;
    }

    public void setTrendEmaPeriod(int trendEmaPeriod) {
        this.trendEmaPeriod = trendEmaPeriod;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUnderlying() {
        return underlying;
    }

    public void setUnderlying(String underlying) {
        this.underlying = underlying;
    }

    public int getTimeframeMinutes() {
        return timeframeMinutes;
    }

    public void setTimeframeMinutes(int timeframeMinutes) {
        this.timeframeMinutes = timeframeMinutes;
    }

    public int getBbPeriod() {
        return bbPeriod;
    }

    public void setBbPeriod(int bbPeriod) {
        this.bbPeriod = bbPeriod;
    }

    public double getBbStdDev() {
        return bbStdDev;
    }

    public void setBbStdDev(double bbStdDev) {
        this.bbStdDev = bbStdDev;
    }

    public BigDecimal getMaxSlPoints() {
        return maxSlPoints;
    }

    public void setMaxSlPoints(BigDecimal maxSlPoints) {
        this.maxSlPoints = maxSlPoints;
    }

    public BigDecimal getMinSlPoints() {
        return minSlPoints;
    }

    public void setMinSlPoints(BigDecimal minSlPoints) {
        this.minSlPoints = minSlPoints;
    }

    public BigDecimal getBufferPoints() {
        return bufferPoints;
    }

    public void setBufferPoints(BigDecimal bufferPoints) {
        this.bufferPoints = bufferPoints;
    }

    public BigDecimal getRiskRewardRatio() {
        return riskRewardRatio;
    }

    public void setRiskRewardRatio(BigDecimal riskRewardRatio) {
        this.riskRewardRatio = riskRewardRatio;
    }

    public int getMaxDailyTrades() {
        return maxDailyTrades;
    }

    public void setMaxDailyTrades(int maxDailyTrades) {
        this.maxDailyTrades = maxDailyTrades;
    }

    public String getSizingMode() {
        return sizingMode;
    }

    public void setSizingMode(String sizingMode) {
        this.sizingMode = sizingMode;
    }

    public int getDefaultLots() {
        return defaultLots;
    }

    public void setDefaultLots(int defaultLots) {
        this.defaultLots = defaultLots;
    }

    public BigDecimal getFixedRiskAmount() {
        return fixedRiskAmount;
    }

    public void setFixedRiskAmount(BigDecimal fixedRiskAmount) {
        this.fixedRiskAmount = fixedRiskAmount;
    }

    public String getEntryWindowStart() {
        return entryWindowStart;
    }

    public void setEntryWindowStart(String entryWindowStart) {
        this.entryWindowStart = entryWindowStart;
    }

    public String getEntryWindowCutoff() {
        return entryWindowCutoff;
    }

    public void setEntryWindowCutoff(String entryWindowCutoff) {
        this.entryWindowCutoff = entryWindowCutoff;
    }

    public String getAutoSquareOffTime() {
        return autoSquareOffTime;
    }

    public void setAutoSquareOffTime(String autoSquareOffTime) {
        this.autoSquareOffTime = autoSquareOffTime;
    }
}
