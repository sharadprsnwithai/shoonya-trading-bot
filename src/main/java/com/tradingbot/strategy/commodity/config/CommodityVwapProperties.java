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

    /** Maximum allowed stop loss risk percentage of entry price (e.g. 0.008 = 0.8%). */
    private double maxRiskPct = 0.008;

    /**
     * Intraday time from which breakout entries are active (default 15:30 IST / European pit open).
     */
    private LocalTime entryStartTime = LocalTime.of(15, 30);

    /** Intraday time after which no new setups are armed (default 21:30 IST). */
    private LocalTime entryCutoff = LocalTime.of(21, 30);

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

    /**
     * Whether to suppress new breakout entries during high-impact US macro news (18:00 - 18:15
     * IST).
     */
    private boolean macroNewsBlackoutEnabled = true;

    /**
     * Whether to suppress new Crude Oil breakout entries during US EIA Inventory prints (Wed 20:00
     * - 20:15 IST).
     */
    private boolean eiaInventoryBlackoutEnabled = true;

    /**
     * Minimum days to expiry (DTE) required to trade contract; avoids MCX 5-day physical tender
     * period.
     */
    private int minDteDays = 5;

    /** Whether to require breakout candle volume >= 10-bar average volume. */
    private boolean volumeConfirmationEnabled = true;

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

    public boolean isMacroNewsBlackoutEnabled() {
        return macroNewsBlackoutEnabled;
    }

    public void setMacroNewsBlackoutEnabled(boolean macroNewsBlackoutEnabled) {
        this.macroNewsBlackoutEnabled = macroNewsBlackoutEnabled;
    }

    public boolean isEiaInventoryBlackoutEnabled() {
        return eiaInventoryBlackoutEnabled;
    }

    public void setEiaInventoryBlackoutEnabled(boolean eiaInventoryBlackoutEnabled) {
        this.eiaInventoryBlackoutEnabled = eiaInventoryBlackoutEnabled;
    }

    public int getMinDteDays() {
        return minDteDays;
    }

    public void setMinDteDays(int minDteDays) {
        this.minDteDays = minDteDays;
    }

    public boolean isVolumeConfirmationEnabled() {
        return volumeConfirmationEnabled;
    }

    public void setVolumeConfirmationEnabled(boolean volumeConfirmationEnabled) {
        this.volumeConfirmationEnabled = volumeConfirmationEnabled;
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

    public double getMaxRiskPct() {
        return maxRiskPct;
    }

    public void setMaxRiskPct(double maxRiskPct) {
        this.maxRiskPct = maxRiskPct;
    }

    public LocalTime getEntryStartTime() {
        return entryStartTime;
    }

    public void setEntryStartTime(LocalTime entryStartTime) {
        this.entryStartTime = entryStartTime;
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
