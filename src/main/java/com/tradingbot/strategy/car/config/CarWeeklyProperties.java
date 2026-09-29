package com.tradingbot.strategy.car.config;

import java.util.ArrayList;
import java.util.List;
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
    private List<String> accumulationOnlySymbols = new ArrayList<>();

    public boolean isAccumulationOnly(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }
        String clean = symbol.toUpperCase().replace("NSE:", "").replace("BSE:", "").trim();
        // Automatic Sovereign Gold Bond (SGB) detection
        if (clean.startsWith("SGB") || clean.endsWith("SGB") || clean.contains("SGB")) {
            return true;
        }
        if (accumulationOnlySymbols == null || accumulationOnlySymbols.isEmpty()) {
            return false;
        }
        return accumulationOnlySymbols.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.toUpperCase().trim())
                .anyMatch(pattern -> clean.equalsIgnoreCase(pattern) || clean.contains(pattern));
    }

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

    public String stateFilePath() {
        return stateFilePath;
    }

    public String getStateFilePath() {
        return stateFilePath;
    }

    public void setStateFilePath(String stateFilePath) {
        this.stateFilePath = stateFilePath;
    }

    public List<String> getAccumulationOnlySymbols() {
        return accumulationOnlySymbols;
    }

    public void setAccumulationOnlySymbols(List<String> accumulationOnlySymbols) {
        this.accumulationOnlySymbols = accumulationOnlySymbols;
    }
}
