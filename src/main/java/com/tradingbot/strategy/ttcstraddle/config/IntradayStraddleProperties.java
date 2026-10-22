package com.tradingbot.strategy.ttcstraddle.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuration properties for the 10:00 AM Intraday Straddle Strategy with 30% SL. */
@Component
@ConfigurationProperties(prefix = "trading-bot.strategy.intraday-straddle")
public class IntradayStraddleProperties {

    private boolean enabled = true;
    private boolean schedulerEnabled = true;
    private int lots = 1;
    private double maxDailyLoss = 8000.0;
    private double slPercentage = 30.0;
    private boolean thursdayEnabled = true; // Monday-Thursday active
    private boolean telegramAlerts = true;
    private String stateFile = "./data/straddle-state.json";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    public int getLots() {
        return lots;
    }

    public void setLots(int lots) {
        this.lots = lots;
    }

    public double getMaxDailyLoss() {
        return maxDailyLoss;
    }

    public void setMaxDailyLoss(double maxDailyLoss) {
        this.maxDailyLoss = maxDailyLoss;
    }

    public double getSlPercentage() {
        return slPercentage;
    }

    public void setSlPercentage(double slPercentage) {
        this.slPercentage = slPercentage;
    }

    public boolean isThursdayEnabled() {
        return thursdayEnabled;
    }

    public void setThursdayEnabled(boolean thursdayEnabled) {
        this.thursdayEnabled = thursdayEnabled;
    }

    public boolean isTelegramAlerts() {
        return telegramAlerts;
    }

    public void setTelegramAlerts(boolean telegramAlerts) {
        this.telegramAlerts = telegramAlerts;
    }

    public String getStateFile() {
        return stateFile;
    }

    public void setStateFile(String stateFile) {
        this.stateFile = stateFile;
    }
}
