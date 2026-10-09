package com.tradingbot.strategy.commodity.config;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the 1:30 PM MCX Commodity Directional PCR & VWAP Breakout Strategy.
 */
@Component
@ConfigurationProperties(prefix = "trading-bot.strategy.commodity-vwap")
public class CommodityVwapProperties {

    /** Whether the commodity strategy is globally enabled. */
    private boolean enabled = true;

    /** List of commodity symbols to track. Defaults to SILVER, CRUDEOIL. */
    private List<String> symbols = new ArrayList<>(List.of("SILVER", "CRUDEOIL"));

    /** Minimum PCR value to classify directional bias as BULLISH. */
    private double pcrBullishMin = 1.15;

    /** Maximum PCR value to classify directional bias as BEARISH. */
    private double pcrBearishMax = 0.85;

    /** Target Risk-to-Reward ratio (e.g. 2.5 for 1:2.5 RR). */
    private double riskRewardRatio = 2.5;

    /** Intraday time after which no new setups are armed (default 22:30 IST). */
    private LocalTime entryCutoff = LocalTime.of(22, 30);

    /** Intraday time at which all open positions are squared off (default 23:15 IST). */
    private LocalTime eodSquareOffTime = LocalTime.of(23, 15);

    /** Max allowed trades per symbol per trading session. */
    private int maxTradesPerSymbol = 1;

    /** Whether to enforce 20-EMA trend alignment to filter out false counter-trend crossovers. */
    private boolean emaTrendFilterEnabled = true;

    /** EMA period for trend filter (default 20). */
    private int emaPeriod = 20;

    /**
     * Maximum allowed minutes for a trigger to stay armed before expiring (default 30 min = 2
     * bars).
     */
    private int maxTriggerAgeMinutes = 30;

    /** Execution mode (PAPER or LIVE). Defaults to PAPER. */
    private String executionMode = "PAPER";

    /** Whether to dispatch real-time Telegram notifications. */
    private boolean telegramAlertsEnabled = true;

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

    public boolean isEmaTrendFilterEnabled() {
        return emaTrendFilterEnabled;
    }

    public void setEmaTrendFilterEnabled(boolean emaTrendFilterEnabled) {
        this.emaTrendFilterEnabled = emaTrendFilterEnabled;
    }

    public int getEmaPeriod() {
        return emaPeriod;
    }

    public void setEmaPeriod(int emaPeriod) {
        this.emaPeriod = emaPeriod;
    }

    public int getMaxTriggerAgeMinutes() {
        return maxTriggerAgeMinutes;
    }

    public void setMaxTriggerAgeMinutes(int maxTriggerAgeMinutes) {
        this.maxTriggerAgeMinutes = maxTriggerAgeMinutes;
    }

    public List<String> getSymbols() {
        return symbols;
    }

    public void setSymbols(List<String> symbols) {
        this.symbols = symbols;
    }

    public double getPcrBullishMin() {
        return pcrBullishMin;
    }

    public void setPcrBullishMin(double pcrBullishMin) {
        this.pcrBullishMin = pcrBullishMin;
    }

    public double getPcrBearishMax() {
        return pcrBearishMax;
    }

    public void setPcrBearishMax(double pcrBearishMax) {
        this.pcrBearishMax = pcrBearishMax;
    }

    public double getRiskRewardRatio() {
        return riskRewardRatio;
    }

    public void setRiskRewardRatio(double riskRewardRatio) {
        this.riskRewardRatio = riskRewardRatio;
    }

    public LocalTime getEntryCutoff() {
        return entryCutoff;
    }

    public void setEntryCutoff(LocalTime entryCutoff) {
        this.entryCutoff = entryCutoff;
    }

    public LocalTime getEodSquareOffTime() {
        return eodSquareOffTime;
    }

    public void setEodSquareOffTime(LocalTime eodSquareOffTime) {
        this.eodSquareOffTime = eodSquareOffTime;
    }

    public int getMaxTradesPerSymbol() {
        return maxTradesPerSymbol;
    }

    public void setMaxTradesPerSymbol(int maxTradesPerSymbol) {
        this.maxTradesPerSymbol = maxTradesPerSymbol;
    }

    public boolean isTelegramAlertsEnabled() {
        return telegramAlertsEnabled;
    }

    public void setTelegramAlertsEnabled(boolean telegramAlertsEnabled) {
        this.telegramAlertsEnabled = telegramAlertsEnabled;
    }
}
