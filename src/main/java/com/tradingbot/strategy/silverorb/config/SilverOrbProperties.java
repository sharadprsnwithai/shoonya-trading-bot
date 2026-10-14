package com.tradingbot.strategy.silverorb.config;

import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the Silver ORB evening-session strategy (MCX silver futures, PAPER only): ORB
 * bias from the 18:00-18:30 IST window, entry on a calendar-day VWAP cross in the bias direction,
 * 7% risk / 25% target of margin, square-off at 23:00 IST.
 */
@Component
@ConfigurationProperties(prefix = "trading-bot.strategy.silver-orb")
public class SilverOrbProperties {

    private boolean enabled = true;
    private boolean schedulerEnabled = true;
    private boolean telegramAlertsEnabled = true;

    /** Cron for the 5-minute evaluation cycle (second 20 past every 5-min mark, evening hours). */
    private String cron = "20 */5 18-23 ? * MON-FRI";

    /** Exchange holding the contract (MCX). */
    private String exch = "MCX";

    /** Shoonya token of the active silver main future (roll manually; 495214 = SILVER04DEC26). */
    private String token = "495214";

    /** Display symbol of the active contract (telegram/log labels). */
    private String symbol = "SILVER04DEC26";

    /** Candle timeframe in minutes (the spec is built on 5m bars). */
    private String timeframe = "5";

    /** Contract size in kg per lot (MCX SILVER main = 30 kg). */
    private double kgPerLot = 30.0;

    /** Paper lots traded. */
    private int lots = 1;

    /** Assumed margin in percent of notional (10% => 10x leverage, matches the backtest). */
    private double marginPct = 10.0;

    /** Risk per trade in percent of margin (7% => 0.70% of price at 10% margin). */
    private double riskPct = 7.0;

    /** Target per trade in percent of margin (25% => 2.50% of price at 10% margin). */
    private double targetPct = 25.0;

    /** Minimum bars required inside the ORB window before the day is tradeable. */
    private int minOrbBars = 4;

    /** Skip the day when (orbHigh-orbLow)/orbLow is below this percent (dead range). */
    private double minOrbRangePct = 0.05;

    /** ORB window start (inclusive), IST. */
    private LocalTime orbStart = LocalTime.of(18, 0);

    /** ORB window end (exclusive), IST. */
    private LocalTime orbEnd = LocalTime.of(18, 30);

    /** No fresh entries at/after this IST time. */
    private LocalTime entryCutoff = LocalTime.of(21, 0);

    /** Flat by this IST time (exit at the close of the first bar opening at/after it). */
    private LocalTime squareOff = LocalTime.of(23, 0);

    /**
     * Last IST day of the paper-validation run (inclusive); afterwards only the summary is sent.
     */
    private LocalDate runUntil = LocalDate.of(2026, 11, 14);

    /** JSON state file surviving restarts (day state + cumulative trade log). */
    private String stateFile = "./data/silver-orb-state.json";

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

    public boolean isTelegramAlertsEnabled() {
        return telegramAlertsEnabled;
    }

    public void setTelegramAlertsEnabled(boolean telegramAlertsEnabled) {
        this.telegramAlertsEnabled = telegramAlertsEnabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public String getExch() {
        return exch;
    }

    public void setExch(String exch) {
        this.exch = exch;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public String getTimeframe() {
        return timeframe;
    }

    public void setTimeframe(String timeframe) {
        this.timeframe = timeframe;
    }

    public double getKgPerLot() {
        return kgPerLot;
    }

    public void setKgPerLot(double kgPerLot) {
        this.kgPerLot = kgPerLot;
    }

    public int getLots() {
        return lots;
    }

    public void setLots(int lots) {
        this.lots = lots;
    }

    public double getMarginPct() {
        return marginPct;
    }

    public void setMarginPct(double marginPct) {
        this.marginPct = marginPct;
    }

    public double getRiskPct() {
        return riskPct;
    }

    public void setRiskPct(double riskPct) {
        this.riskPct = riskPct;
    }

    public double getTargetPct() {
        return targetPct;
    }

    public void setTargetPct(double targetPct) {
        this.targetPct = targetPct;
    }

    public int getMinOrbBars() {
        return minOrbBars;
    }

    public void setMinOrbBars(int minOrbBars) {
        this.minOrbBars = minOrbBars;
    }

    public double getMinOrbRangePct() {
        return minOrbRangePct;
    }

    public void setMinOrbRangePct(double minOrbRangePct) {
        this.minOrbRangePct = minOrbRangePct;
    }

    public LocalTime getOrbStart() {
        return orbStart;
    }

    public void setOrbStart(LocalTime orbStart) {
        this.orbStart = orbStart;
    }

    public LocalTime getOrbEnd() {
        return orbEnd;
    }

    public void setOrbEnd(LocalTime orbEnd) {
        this.orbEnd = orbEnd;
    }

    public LocalTime getEntryCutoff() {
        return entryCutoff;
    }

    public void setEntryCutoff(LocalTime entryCutoff) {
        this.entryCutoff = entryCutoff;
    }

    public LocalTime getSquareOff() {
        return squareOff;
    }

    public void setSquareOff(LocalTime squareOff) {
        this.squareOff = squareOff;
    }

    public LocalDate getRunUntil() {
        return runUntil;
    }

    public void setRunUntil(LocalDate runUntil) {
        this.runUntil = runUntil;
    }

    public String getStateFile() {
        return stateFile;
    }

    public void setStateFile(String stateFile) {
        this.stateFile = stateFile;
    }

    /** Candle size in minutes parsed from {@link #timeframe} (falls back to 5 on bad input). */
    public int barMinutes() {
        try {
            return Integer.parseInt(timeframe);
        } catch (NumberFormatException e) {
            return 5;
        }
    }
}
