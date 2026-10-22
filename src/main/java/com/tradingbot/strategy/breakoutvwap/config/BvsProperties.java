package com.tradingbot.strategy.breakoutvwap.config;

import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the Breakout VWAP (BVS) strategy: 12-stock 09:25 selection from four buckets,
 * dual-level confluence bias (09:30-10:00 range + previous day H/L), VWAP-cross 5m trigger candle,
 * futures entry on the trigger high/low break, 50% partial at 1:2.5R, runner until the 10-EMA close
 * cross.
 */
@Component
@ConfigurationProperties(prefix = "trading-bot.strategy.breakout-vwap")
public class BvsProperties {

    /** Master switch for the strategy (env BVS_ENABLED). */
    private boolean enabled = true;

    /** Switch for all scheduled jobs (env BVS_SCHEDULER_ENABLED). */
    private boolean schedulerEnabled = true;

    /** Stocks per selection bucket, 4 buckets total (env BVS_BUCKET_SIZE). */
    private int bucketSize = 3;

    /** Reward-to-risk at which 50% of the position is booked (env BVS_RR_PARTIAL). */
    private double rrPartial = 2.5;

    /** EMA period for the runner exit on 5m closes (env BVS_EMA_PERIOD). */
    private int emaPeriod = 10;

    /**
     * Minutes after the trigger candle close within which price must breach its high/low (env
     * BVS_TRIGGER_WINDOW_MINUTES).
     */
    private int triggerWindowMinutes = 5;

    /** No new entries at/after this IST time (env BVS_ENTRY_CUTOFF). */
    private LocalTime entryCutoff = LocalTime.of(14, 30);

    /** Square off every open position at/after this IST time (env BVS_HARD_EXIT). */
    private LocalTime hardExit = LocalTime.of(15, 0);

    /** Inclusive start of the session-range window, IST (env BVS_SESSION_RANGE_START). */
    private LocalTime sessionRangeStart = LocalTime.of(9, 30);

    /** Exclusive end of the session-range window, IST (env BVS_SESSION_RANGE_END). */
    private LocalTime sessionRangeEnd = LocalTime.of(10, 0);

    /** Cron for the 09:25 universe scan (env BVS_SCAN_CRON). */
    private String scanCron = "0 25 9 ? * MON-FRI";

    /** Cron for the OI-spurt prefetch, staggered after LVR's 09:25:00 (env BVS_OI_SPURT_CRON). */
    private String oiSpurtCron = "5 25 9 ? * MON-FRI";

    /** Cron for the 5-minute candle cycle (env BVS_CYCLE_CRON). */
    private String cycleCron = "20 */5 9-15 ? * MON-FRI";

    /** Live-tick poll interval in milliseconds (env BVS_TICK_RATE_MS). */
    private long tickRateMs = 30000;

    /** Send Telegram alerts for scan/entry/partial/exit events (env BVS_TELEGRAM_ALERTS). */
    private boolean telegramAlerts = true;

    /** Persist daily state to disk so restarts resume (env BVS_STATE_PERSISTENCE_ENABLED). */
    private boolean statePersistenceEnabled = true;

    /** JSON state file surviving restarts (env BVS_STATE_FILE). */
    private String stateFile = "./data/bvs-state.json";

    /** Risk per trade in percent of paper capital (env BVS_RISK_PER_TRADE). */
    private double riskPerTradePercent = 1.0;

    /** Paper capital base for position sizing (env BVS_PAPER_CAPITAL). */
    private double paperCapital = 500000;

    /** Default lots when dynamic position sizing is off (env BVS_LOTS). */
    private int lots = 1;

    /**
     * Size lots from risk-per-trade instead of the fixed default (env BVS_DYNAMIC_POSITION_SIZING).
     */
    private boolean dynamicPositionSizing = true;

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

    public int getBucketSize() {
        return bucketSize;
    }

    public void setBucketSize(int bucketSize) {
        this.bucketSize = bucketSize;
    }

    public double getRrPartial() {
        return rrPartial;
    }

    public void setRrPartial(double rrPartial) {
        this.rrPartial = rrPartial;
    }

    public int getEmaPeriod() {
        return emaPeriod;
    }

    public void setEmaPeriod(int emaPeriod) {
        this.emaPeriod = emaPeriod;
    }

    public int getTriggerWindowMinutes() {
        return triggerWindowMinutes;
    }

    public void setTriggerWindowMinutes(int triggerWindowMinutes) {
        this.triggerWindowMinutes = triggerWindowMinutes;
    }

    public LocalTime getEntryCutoff() {
        return entryCutoff;
    }

    public void setEntryCutoff(LocalTime entryCutoff) {
        this.entryCutoff = entryCutoff;
    }

    public LocalTime getHardExit() {
        return hardExit;
    }

    public void setHardExit(LocalTime hardExit) {
        this.hardExit = hardExit;
    }

    public LocalTime getSessionRangeStart() {
        return sessionRangeStart;
    }

    public void setSessionRangeStart(LocalTime sessionRangeStart) {
        this.sessionRangeStart = sessionRangeStart;
    }

    public LocalTime getSessionRangeEnd() {
        return sessionRangeEnd;
    }

    public void setSessionRangeEnd(LocalTime sessionRangeEnd) {
        this.sessionRangeEnd = sessionRangeEnd;
    }

    public String getScanCron() {
        return scanCron;
    }

    public void setScanCron(String scanCron) {
        this.scanCron = scanCron;
    }

    public String getOiSpurtCron() {
        return oiSpurtCron;
    }

    public void setOiSpurtCron(String oiSpurtCron) {
        this.oiSpurtCron = oiSpurtCron;
    }

    public String getCycleCron() {
        return cycleCron;
    }

    public void setCycleCron(String cycleCron) {
        this.cycleCron = cycleCron;
    }

    public long getTickRateMs() {
        return tickRateMs;
    }

    public void setTickRateMs(long tickRateMs) {
        this.tickRateMs = tickRateMs;
    }

    public boolean isTelegramAlerts() {
        return telegramAlerts;
    }

    public void setTelegramAlerts(boolean telegramAlerts) {
        this.telegramAlerts = telegramAlerts;
    }

    public boolean isStatePersistenceEnabled() {
        return statePersistenceEnabled;
    }

    public void setStatePersistenceEnabled(boolean statePersistenceEnabled) {
        this.statePersistenceEnabled = statePersistenceEnabled;
    }

    public String getStateFile() {
        return stateFile;
    }

    public void setStateFile(String stateFile) {
        this.stateFile = stateFile;
    }

    public double getRiskPerTradePercent() {
        return riskPerTradePercent;
    }

    public void setRiskPerTradePercent(double riskPerTradePercent) {
        this.riskPerTradePercent = riskPerTradePercent;
    }

    public double getPaperCapital() {
        return paperCapital;
    }

    public void setPaperCapital(double paperCapital) {
        this.paperCapital = paperCapital;
    }

    public int getLots() {
        return lots;
    }

    public void setLots(int lots) {
        this.lots = lots;
    }

    public boolean isDynamicPositionSizing() {
        return dynamicPositionSizing;
    }

    public void setDynamicPositionSizing(boolean dynamicPositionSizing) {
        this.dynamicPositionSizing = dynamicPositionSizing;
    }
}
