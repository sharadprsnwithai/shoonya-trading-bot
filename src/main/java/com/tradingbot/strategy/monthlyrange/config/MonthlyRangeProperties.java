package com.tradingbot.strategy.monthlyrange.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the Monthly Option Range GARCH(1,1) strategy.
 */
@Component
@ConfigurationProperties(prefix = "trading-bot.strategy.monthly-range")
public class MonthlyRangeProperties {

    private boolean enabled = true;
    private String cron = "0 0 10 ? * WED";
    private List<String> symbols =
            new ArrayList<>(
                    List.of(
                            "RELIANCE",
                            "TCS",
                            "HDFCBANK",
                            "INFY",
                            "ICICIBANK",
                            "SBIN",
                            "TATAMOTORS",
                            "NIFTY50"));
    private int forecastHorizonDays = 22;
    private int historyLookbackDays = 504;
    private boolean telegramAlertsEnabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public List<String> getSymbols() {
        return symbols;
    }

    public void setSymbols(List<String> symbols) {
        this.symbols = symbols != null ? new ArrayList<>(symbols) : new ArrayList<>();
    }

    public int getForecastHorizonDays() {
        return forecastHorizonDays;
    }

    public void setForecastHorizonDays(int forecastHorizonDays) {
        this.forecastHorizonDays = forecastHorizonDays;
    }

    public int getHistoryLookbackDays() {
        return historyLookbackDays;
    }

    public void setHistoryLookbackDays(int historyLookbackDays) {
        this.historyLookbackDays = historyLookbackDays;
    }

    public boolean isTelegramAlertsEnabled() {
        return telegramAlertsEnabled;
    }

    public void setTelegramAlertsEnabled(boolean telegramAlertsEnabled) {
        this.telegramAlertsEnabled = telegramAlertsEnabled;
    }
}
