package com.tradingbot.strategy.monthlyrange.config;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuration properties for the Monthly Option Range GARCH(1,1) strategy. */
@Component
@ConfigurationProperties(prefix = "trading-bot.strategy.monthly-range")
public class MonthlyRangeProperties {

    private boolean enabled = true;
    private String cron = "0 0 10 ? * MON-FRI";
    private List<String> symbols =
            new ArrayList<>(
                    List.of(
                            "NIFTY50",
                            "RELIANCE",
                            "TCS",
                            "HDFCBANK",
                            "INFY",
                            "ICICIBANK",
                            "SBIN",
                            "BHARTIARTL",
                            "ITC",
                            "LT",
                            "TATAMOTORS"));
    private int forecastHorizonDays = 22;
    private int historyLookbackDays = 504;
    private boolean telegramAlertsEnabled = true;

    // Monthly Directional Probability & 1x2x1 Broken-Wing Butterfly Tuning
    private boolean directionalProbabilityEnabled = true;
    private double strongConvictionThreshold = 65.0;
    private boolean triggerOnFirstTradingDayOfMonth = true;

    // Asymmetric Downside Skew & Event Month Tuning
    private double normalPeMultiplier = 2.10;
    private double normalCeMultiplier = 1.95;
    private double eventPeMultiplier = 2.45;
    private double eventCeMultiplier = 2.25;
    private List<Integer> earningsMonths =
            new ArrayList<>(List.of(1, 4, 7, 10)); // Jan, Apr, Jul, Oct
    private double ivHvSpikeThreshold = 1.25;
    private int optionChainStrikeCount = 25;

    // Short-dated (weekly / daily) horizon range forecasting
    private boolean weeklyRangeEnabled = true;
    private String weeklyCron = "0 0 8 ? * WED";
    private List<String> weeklySymbols = new ArrayList<>(List.of("NIFTY50"));
    private String weeklyExpiryDayOfWeek = "TUESDAY";
    private String weeklyDeploymentDayOfWeek = "WEDNESDAY";
    private List<Double> rangeKSigmas = new ArrayList<>(List.of(1.0, 1.5, 2.0));
    private double calibrationTrainPct = 0.60;
    private int calibrationMinWindows = 25;
    private boolean gjrComparisonEnabled = true;

    // Reference wing structure: buy far wing, sell two near wings, buy far wing (150/200/350).
    // Offsets are in points from spot; credit is the net premium collected on entry.
    private double wingOffsetPts = 150.0;
    private double wingShortOffsetPts = 200.0;
    private double wingCreditPts = 20.0;

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
        return new ArrayList<>(symbols);
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

    public boolean isDirectionalProbabilityEnabled() {
        return directionalProbabilityEnabled;
    }

    public void setDirectionalProbabilityEnabled(boolean directionalProbabilityEnabled) {
        this.directionalProbabilityEnabled = directionalProbabilityEnabled;
    }

    public double getStrongConvictionThreshold() {
        return strongConvictionThreshold;
    }

    public void setStrongConvictionThreshold(double strongConvictionThreshold) {
        this.strongConvictionThreshold = strongConvictionThreshold;
    }

    public boolean isTriggerOnFirstTradingDayOfMonth() {
        return triggerOnFirstTradingDayOfMonth;
    }

    public void setTriggerOnFirstTradingDayOfMonth(boolean triggerOnFirstTradingDayOfMonth) {
        this.triggerOnFirstTradingDayOfMonth = triggerOnFirstTradingDayOfMonth;
    }

    public double getNormalPeMultiplier() {
        return normalPeMultiplier;
    }

    public void setNormalPeMultiplier(double normalPeMultiplier) {
        this.normalPeMultiplier = normalPeMultiplier;
    }

    public double getNormalCeMultiplier() {
        return normalCeMultiplier;
    }

    public void setNormalCeMultiplier(double normalCeMultiplier) {
        this.normalCeMultiplier = normalCeMultiplier;
    }

    public double getEventPeMultiplier() {
        return eventPeMultiplier;
    }

    public void setEventPeMultiplier(double eventPeMultiplier) {
        this.eventPeMultiplier = eventPeMultiplier;
    }

    public double getEventCeMultiplier() {
        return eventCeMultiplier;
    }

    public void setEventCeMultiplier(double eventCeMultiplier) {
        this.eventCeMultiplier = eventCeMultiplier;
    }

    public List<Integer> getEarningsMonths() {
        return new ArrayList<>(earningsMonths);
    }

    public void setEarningsMonths(List<Integer> earningsMonths) {
        this.earningsMonths =
                earningsMonths != null ? new ArrayList<>(earningsMonths) : new ArrayList<>();
    }

    public double getIvHvSpikeThreshold() {
        return ivHvSpikeThreshold;
    }

    public void setIvHvSpikeThreshold(double ivHvSpikeThreshold) {
        this.ivHvSpikeThreshold = ivHvSpikeThreshold;
    }

    public int getOptionChainStrikeCount() {
        return optionChainStrikeCount;
    }

    public void setOptionChainStrikeCount(int optionChainStrikeCount) {
        this.optionChainStrikeCount = optionChainStrikeCount;
    }

    public boolean isWeeklyRangeEnabled() {
        return weeklyRangeEnabled;
    }

    public void setWeeklyRangeEnabled(boolean weeklyRangeEnabled) {
        this.weeklyRangeEnabled = weeklyRangeEnabled;
    }

    public String getWeeklyCron() {
        return weeklyCron;
    }

    public void setWeeklyCron(String weeklyCron) {
        this.weeklyCron = weeklyCron;
    }

    public List<String> getWeeklySymbols() {
        return new ArrayList<>(weeklySymbols);
    }

    public void setWeeklySymbols(List<String> weeklySymbols) {
        this.weeklySymbols =
                weeklySymbols != null ? new ArrayList<>(weeklySymbols) : new ArrayList<>();
    }

    public String getWeeklyExpiryDayOfWeek() {
        return weeklyExpiryDayOfWeek;
    }

    public void setWeeklyExpiryDayOfWeek(String weeklyExpiryDayOfWeek) {
        this.weeklyExpiryDayOfWeek = weeklyExpiryDayOfWeek;
    }

    /**
     * Resolves the configured contractual weekly expiry weekday, defaulting to Tuesday — the NSE
     * equity-derivatives contract day — whenever the property is absent or unparseable.
     *
     * @return the configured expiry weekday, never null
     */
    public DayOfWeek resolveExpiryDayOfWeek() {
        return parseDayOfWeek(weeklyExpiryDayOfWeek, DayOfWeek.TUESDAY);
    }

    public String getWeeklyDeploymentDayOfWeek() {
        return weeklyDeploymentDayOfWeek;
    }

    public void setWeeklyDeploymentDayOfWeek(String weeklyDeploymentDayOfWeek) {
        this.weeklyDeploymentDayOfWeek = weeklyDeploymentDayOfWeek;
    }

    /**
     * Resolves the configured day the weekly routine should fire, defaulting to Wednesday — the
     * first session after a Tuesday expiry.
     *
     * @return the configured deployment weekday, never null
     */
    public DayOfWeek resolveDeploymentDayOfWeek() {
        return parseDayOfWeek(weeklyDeploymentDayOfWeek, DayOfWeek.WEDNESDAY);
    }

    private static DayOfWeek parseDayOfWeek(String raw, DayOfWeek fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return DayOfWeek.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    public List<Double> getRangeKSigmas() {
        return new ArrayList<>(rangeKSigmas);
    }

    public void setRangeKSigmas(List<Double> rangeKSigmas) {
        this.rangeKSigmas =
                rangeKSigmas != null ? new ArrayList<>(rangeKSigmas) : new ArrayList<>();
    }

    public double getCalibrationTrainPct() {
        return calibrationTrainPct;
    }

    public void setCalibrationTrainPct(double calibrationTrainPct) {
        this.calibrationTrainPct = calibrationTrainPct;
    }

    public int getCalibrationMinWindows() {
        return calibrationMinWindows;
    }

    public void setCalibrationMinWindows(int calibrationMinWindows) {
        this.calibrationMinWindows = calibrationMinWindows;
    }

    public boolean isGjrComparisonEnabled() {
        return gjrComparisonEnabled;
    }

    public void setGjrComparisonEnabled(boolean gjrComparisonEnabled) {
        this.gjrComparisonEnabled = gjrComparisonEnabled;
    }

    public double getWingOffsetPts() {
        return wingOffsetPts;
    }

    public void setWingOffsetPts(double wingOffsetPts) {
        this.wingOffsetPts = wingOffsetPts;
    }

    public double getWingShortOffsetPts() {
        return wingShortOffsetPts;
    }

    public void setWingShortOffsetPts(double wingShortOffsetPts) {
        this.wingShortOffsetPts = wingShortOffsetPts;
    }

    public double getWingCreditPts() {
        return wingCreditPts;
    }

    public void setWingCreditPts(double wingCreditPts) {
        this.wingCreditPts = wingCreditPts;
    }
}
