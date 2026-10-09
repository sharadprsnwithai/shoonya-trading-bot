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
     * Minimum full-position risk (risk x quantity x unit multiplier) expressed as a multiple of the
     * estimated round-trip transaction cost (e.g. 0.8 = the position must risk at least 80% of its
     * round-trip cost). Entries whose expectancy cannot cover costs are skipped. Set 0 to disable.
     */
    private double minRiskToCostRatio = 0.8;

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
     * Whether to suppress new breakout entries around high-impact US macro news releases (NFP, CPI,
     * PPI, Retail Sales at 08:30 ET, DST-mapped to IST).
     */
    private boolean macroNewsBlackoutEnabled = true;

    /**
     * Whether to suppress new Crude Oil breakout entries during US EIA Inventory prints (Wed 10:30
     * ET, DST-mapped to IST).
     */
    private boolean eiaInventoryBlackoutEnabled = true;

    /**
     * Half-width in minutes of the news blackout window around each US release (e.g. 30 = a
     * 60-minute total window). Conservative default reflects that impact extends well beyond the
     * print itself.
     */
    private int blackoutHalfWidthMinutes = 30;

    /**
     * Daily max-loss circuit breaker in INR (realized + floating). When today's total loss reaches
     * this limit the breaker trips and latches for the rest of the session, halting new entries.
     * Set to 0 or negative to disable.
     */
    private double maxDailyLossInr = 8000.0;

    /**
     * Maximum number of losing trades allowed per day before new entries are halted (loss-count
     * kill-switch). Set to 0 or negative to disable.
     */
    private int maxDailyLosses = 2;

    /**
     * Minimum days to expiry (DTE) required to trade contract; avoids MCX 5-day physical tender
     * period.
     */
    private int minDteDays = 5;

    /** Whether to require breakout candle volume >= 10-bar average volume. */
    private boolean volumeConfirmationEnabled = true;

    /**
     * Number of prior bars forming the average-volume baseline for the volume confirmation gate.
     */
    private int volumeAvgPeriod = 10;

    /**
     * Minimum ratio of the arming bar's volume to the prior-bar average volume (e.g. 1.0 = must
     * match or exceed the average).
     */
    private double volumeMinRatio = 1.0;

    /** Adaptive PCR z-score confirmation settings. */
    private PcrZScore pcrZScore = new PcrZScore();

    /** Execution mode (PAPER or LIVE). Defaults to PAPER. */
    private String executionMode = "PAPER";

    /** Whether to dispatch real-time Telegram notifications. */
    private boolean telegramAlertsEnabled = true;

    /** Round-trip transaction cost assumptions applied to reported and backtested PnL. */
    private Costs costs = new Costs();

    /** Nested configuration for transaction cost modeling. */
    public static class Costs {

        /** Brokerage charged per lot per side in INR. */
        private double brokerageInrPerLotPerSide = 25.0;

        /** Slippage assumed per side, in basis points of contract notional. */
        private double slippageBpsPerSide = 5.0;

        public double getBrokerageInrPerLotPerSide() {
            return brokerageInrPerLotPerSide;
        }

        public void setBrokerageInrPerLotPerSide(double brokerageInrPerLotPerSide) {
            this.brokerageInrPerLotPerSide = brokerageInrPerLotPerSide;
        }

        public double getSlippageBpsPerSide() {
            return slippageBpsPerSide;
        }

        public void setSlippageBpsPerSide(double slippageBpsPerSide) {
            this.slippageBpsPerSide = slippageBpsPerSide;
        }
    }

    /**
     * Adaptive PCR confirmation: once enough daily PCR samples have been recorded, a
     * static-threshold bias signal is vetoed unless it is also unusual relative to the recent PCR
     * regime (z-score). Until {@code minSamples} is reached the static thresholds alone decide
     * (bootstrap phase).
     */
    public static class PcrZScore {

        /** Whether the z-score veto is active once enough samples exist. */
        private boolean enabled = true;

        /** Maximum number of most-recent daily PCR samples kept per symbol. */
        private int windowSize = 20;

        /** Minimum samples required before the z-score veto engages. */
        private int minSamples = 10;

        /** Minimum z-score for a BULLISH PCR reading to be confirmed. */
        private double zBullish = 0.5;

        /** Maximum z-score for a BEARISH PCR reading to be confirmed. */
        private double zBearish = -0.5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getWindowSize() {
            return windowSize;
        }

        public void setWindowSize(int windowSize) {
            this.windowSize = windowSize;
        }

        public int getMinSamples() {
            return minSamples;
        }

        public void setMinSamples(int minSamples) {
            this.minSamples = minSamples;
        }

        public double getZBullish() {
            return zBullish;
        }

        public void setZBullish(double zBullish) {
            this.zBullish = zBullish;
        }

        public double getZBearish() {
            return zBearish;
        }

        public void setZBearish(double zBearish) {
            this.zBearish = zBearish;
        }
    }

    public int getVolumeAvgPeriod() {
        return volumeAvgPeriod;
    }

    public void setVolumeAvgPeriod(int volumeAvgPeriod) {
        this.volumeAvgPeriod = volumeAvgPeriod;
    }

    public double getVolumeMinRatio() {
        return volumeMinRatio;
    }

    public void setVolumeMinRatio(double volumeMinRatio) {
        this.volumeMinRatio = volumeMinRatio;
    }

    public PcrZScore getPcrZScore() {
        return pcrZScore;
    }

    public void setPcrZScore(PcrZScore pcrZScore) {
        this.pcrZScore = pcrZScore;
    }

    public Costs getCosts() {
        return costs;
    }

    public void setCosts(Costs costs) {
        this.costs = costs;
    }

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

    public int getBlackoutHalfWidthMinutes() {
        return blackoutHalfWidthMinutes;
    }

    public void setBlackoutHalfWidthMinutes(int blackoutHalfWidthMinutes) {
        this.blackoutHalfWidthMinutes = blackoutHalfWidthMinutes;
    }

    public double getMaxDailyLossInr() {
        return maxDailyLossInr;
    }

    public void setMaxDailyLossInr(double maxDailyLossInr) {
        this.maxDailyLossInr = maxDailyLossInr;
    }

    public int getMaxDailyLosses() {
        return maxDailyLosses;
    }

    public void setMaxDailyLosses(int maxDailyLosses) {
        this.maxDailyLosses = maxDailyLosses;
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

    public double getMinRiskToCostRatio() {
        return minRiskToCostRatio;
    }

    public void setMinRiskToCostRatio(double minRiskToCostRatio) {
        this.minRiskToCostRatio = minRiskToCostRatio;
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
