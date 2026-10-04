package com.tradingbot.strategy.driftvwap.config;

import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trading-bot.strategy.drift-vwap")
public class DriftVwapProperties {

    private boolean enabled = true;
    private String underlying = "NIFTY";
    private int lots = 2;
    private double targetDecayPct = 70.0;
    private double slExpansionPct = 60.0;
    private double minMomentumPct = 0.12;
    private int maxDailyTrades = 4;
    private int maxDailyLosses = 2;
    private LocalTime entryWindowStart = LocalTime.of(10, 15);
    private LocalTime entryWindowCutoff = LocalTime.of(14, 55);
    private LocalTime hardExitTime = LocalTime.of(15, 10);
    private boolean telegramAlerts = true;
    private String stateFilePath = "data/drift_vwap_state.json";

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

    public int getLots() {
        return lots;
    }

    public void setLots(int lots) {
        this.lots = lots;
    }

    public double getTargetDecayPct() {
        return targetDecayPct;
    }

    public void setTargetDecayPct(double targetDecayPct) {
        this.targetDecayPct = targetDecayPct;
    }

    public double getSlExpansionPct() {
        return slExpansionPct;
    }

    public void setSlExpansionPct(double slExpansionPct) {
        this.slExpansionPct = slExpansionPct;
    }

    public double getMinMomentumPct() {
        return minMomentumPct;
    }

    public void setMinMomentumPct(double minMomentumPct) {
        this.minMomentumPct = minMomentumPct;
    }

    public int getMaxDailyTrades() {
        return maxDailyTrades;
    }

    public void setMaxDailyTrades(int maxDailyTrades) {
        this.maxDailyTrades = maxDailyTrades;
    }

    public int getMaxDailyLosses() {
        return maxDailyLosses;
    }

    public void setMaxDailyLosses(int maxDailyLosses) {
        this.maxDailyLosses = maxDailyLosses;
    }

    public LocalTime getEntryWindowStart() {
        return entryWindowStart;
    }

    public void setEntryWindowStart(LocalTime entryWindowStart) {
        this.entryWindowStart = entryWindowStart;
    }

    public LocalTime getEntryWindowCutoff() {
        return entryWindowCutoff;
    }

    public void setEntryWindowCutoff(LocalTime entryWindowCutoff) {
        this.entryWindowCutoff = entryWindowCutoff;
    }

    public LocalTime getHardExitTime() {
        return hardExitTime;
    }

    public void setHardExitTime(LocalTime hardExitTime) {
        this.hardExitTime = hardExitTime;
    }

    public boolean isTelegramAlerts() {
        return telegramAlerts;
    }

    public void setTelegramAlerts(boolean telegramAlerts) {
        this.telegramAlerts = telegramAlerts;
    }

    public String getStateFilePath() {
        return stateFilePath;
    }

    public void setStateFilePath(String stateFilePath) {
        this.stateFilePath = stateFilePath;
    }
}
