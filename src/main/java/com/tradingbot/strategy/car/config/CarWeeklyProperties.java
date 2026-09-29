package com.tradingbot.strategy.car.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trading-bot.strategy.car-weekly")
public class CarWeeklyProperties {

    private boolean enabled = true;
    private double totalCapital = 1000000.0;
    private int numParts = 40;
    private double profitTargetPct = 6.28;
    private double triggerBuffer = 0.10;
    private int carPositiveDays = 10;
    private int highLookbackDays = 252;
    private boolean telegramAlerts = true;
    private String stateFilePath = "data/car_portfolio_state.json";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getTotalCapital() {
        return totalCapital;
    }

    public void setTotalCapital(double totalCapital) {
        this.totalCapital = totalCapital;
    }

    public int getNumParts() {
        return numParts;
    }

    public void setNumParts(int numParts) {
        this.numParts = numParts;
    }

    public double getProfitTargetPct() {
        return profitTargetPct;
    }

    public void setProfitTargetPct(double profitTargetPct) {
        this.profitTargetPct = profitTargetPct;
    }

    public double getTriggerBuffer() {
        return triggerBuffer;
    }

    public void setTriggerBuffer(double triggerBuffer) {
        this.triggerBuffer = triggerBuffer;
    }

    public int getCarPositiveDays() {
        return carPositiveDays;
    }

    public void setCarPositiveDays(int carPositiveDays) {
        this.carPositiveDays = carPositiveDays;
    }

    public int getHighLookbackDays() {
        return highLookbackDays;
    }

    public void setHighLookbackDays(int highLookbackDays) {
        this.highLookbackDays = highLookbackDays;
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
