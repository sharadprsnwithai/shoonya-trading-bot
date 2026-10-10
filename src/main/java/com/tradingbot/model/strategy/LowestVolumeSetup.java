package com.tradingbot.model.strategy;

import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tracks the life-cycle and state variables of an individual stock setup under the Lowest Volume
 * Reversal & Continuation Strategy (Kushal Varshney 5m Framework).
 */
public class LowestVolumeSetup {

    private final String symbol;
    private volatile LowestVolumeDirection direction;
    private volatile LowestVolumeSetupState state;
    private final List<Candle> initialLegCandles = new ArrayList<>();
    private volatile Candle triggerCandle;
    private volatile BigDecimal triggerPrice;
    private volatile BigDecimal stopLossPrice;
    private volatile BigDecimal target1Price;
    private volatile double atr14;
    private volatile BigDecimal initialLegMove = BigDecimal.ZERO;
    private volatile long triggerCandleVolume;
    private volatile long dayLowestVolume = Long.MAX_VALUE;
    private volatile int armedCandlesElapsed = 0;
    private volatile int tradeAttempts = 0;
    private volatile String rejectionReason;
    private volatile Double latestVwap;
    private volatile BigDecimal first15MinHigh;
    private volatile BigDecimal first15MinLow;
    private volatile BigDecimal pdh;
    private volatile BigDecimal pdl;
    private volatile Double pcr;
    private volatile BigDecimal optionResistanceStrike;
    private volatile BigDecimal optionSupportStrike;
    private volatile String setupPattern;
    private volatile Double oiChangePct;
    private volatile Instant updatedAt;
    private volatile Instant setupCreatedTime;
    private volatile Instant lastExitTime;
    // N1: session extremes observed at arming (first post-arm tick) — freshness baseline for
    // trigger-breach detection so a pre-arm session high/low can never fire an entry.
    private volatile Double sessionHighAtArming;
    private volatile Double sessionLowAtArming;
    // N5/H7: bounded lazy-retry counter for PDH/PDL fetch before permanent exhaustion.
    private volatile int pdhFetchAttempts = 0;

    public LowestVolumeSetup(String symbol, LowestVolumeDirection direction) {
        this.symbol = symbol;
        this.direction = direction;
        this.state = LowestVolumeSetupState.SCANNING;
        this.updatedAt = Instant.now();
        this.setupCreatedTime = Instant.now();
    }

    public synchronized void transitionTo(LowestVolumeSetupState newState, String reason) {
        this.state = newState;
        this.rejectionReason = reason;
        this.updatedAt = Instant.now();
    }

    public synchronized void resetToScanning() {
        this.state = LowestVolumeSetupState.SCANNING;
        this.initialLegCandles.clear();
        this.triggerCandle = null;
        this.triggerPrice = null;
        this.stopLossPrice = null;
        this.target1Price = null;
        this.armedCandlesElapsed = 0;
        this.rejectionReason = null;
        this.latestVwap = null;
        this.setupPattern = null;
        this.sessionHighAtArming = null;
        this.sessionLowAtArming = null;
        this.pdhFetchAttempts = 0;
        this.updatedAt = Instant.now();
    }

    public synchronized void setInitialLeg(List<Candle> candles, BigDecimal move, double atr) {
        this.initialLegCandles.clear();
        if (candles != null) {
            this.initialLegCandles.addAll(candles);
        }
        this.initialLegMove = move;
        this.atr14 = atr;
        this.state = LowestVolumeSetupState.LEG_CONFIRMED;
        this.updatedAt = Instant.now();
    }

    public synchronized void setTriggerCandle(
            Candle candle, BigDecimal triggerPrc, BigDecimal slPrc, BigDecimal targetPrc) {
        // M4: only a fresh arm restarts the armed-timeout counter. A trail / re-anchor of the
        // same armed lineage keeps counting so a setup can never stay armed indefinitely.
        boolean freshArm = this.state != LowestVolumeSetupState.TRIGGER_ARMED;
        this.triggerCandle = candle;
        this.triggerPrice = triggerPrc;
        this.stopLossPrice = slPrc;
        this.target1Price = targetPrc;
        this.triggerCandleVolume = (candle != null) ? candle.volume() : 0L;
        if (freshArm) {
            this.armedCandlesElapsed = 0;
            this.sessionHighAtArming = null;
            this.sessionLowAtArming = null;
        }
        this.state = LowestVolumeSetupState.TRIGGER_ARMED;
        this.updatedAt = Instant.now();
    }

    public synchronized void incrementArmedTimeout() {
        this.armedCandlesElapsed++;
        this.updatedAt = Instant.now();
    }

    public synchronized void recordTradeAttempt() {
        this.tradeAttempts++;
    }

    /** H11: undoes a {@link #recordTradeAttempt()} when the entry had to be rolled back. */
    public synchronized void undoTradeAttempt() {
        if (this.tradeAttempts > 0) {
            this.tradeAttempts--;
        }
    }

    public synchronized int getTradeAttempts() {
        return tradeAttempts;
    }

    public synchronized void setTradeAttempts(int tradeAttempts) {
        this.tradeAttempts = tradeAttempts;
    }

    public synchronized long getDayLowestVolume() {
        return dayLowestVolume;
    }

    public synchronized void setDayLowestVolume(long dayLowestVolume) {
        this.dayLowestVolume = dayLowestVolume;
    }

    public synchronized void updateDayLowestVolume(long volume) {
        if (volume > 0 && volume < this.dayLowestVolume) {
            this.dayLowestVolume = volume;
        }
    }

    public Double getLatestVwap() {
        return latestVwap;
    }

    public void setLatestVwap(Double latestVwap) {
        this.latestVwap = latestVwap;
    }

    public String getSymbol() {
        return symbol;
    }

    public LowestVolumeDirection getDirection() {
        return direction;
    }

    public void setDirection(LowestVolumeDirection direction) {
        this.direction = direction;
    }

    public LowestVolumeSetupState getState() {
        return state;
    }

    public List<Candle> getInitialLegCandles() {
        return Collections.unmodifiableList(initialLegCandles);
    }

    public Candle getTriggerCandle() {
        return triggerCandle;
    }

    public BigDecimal getTriggerPrice() {
        return triggerPrice;
    }

    public BigDecimal getStopLossPrice() {
        return stopLossPrice;
    }

    public BigDecimal getTarget1Price() {
        return target1Price;
    }

    public double getAtr14() {
        return atr14;
    }

    public void setAtr14(double atr14) {
        this.atr14 = atr14;
    }

    public boolean isClosed() {
        return state == LowestVolumeSetupState.CLOSED_SL
                || state == LowestVolumeSetupState.CLOSED_TARGET
                || state == LowestVolumeSetupState.CLOSED_TRAIL_EXIT
                || state == LowestVolumeSetupState.CLOSED_TIMEOUT;
    }

    public boolean isExhaustedOrRejected() {
        return state == LowestVolumeSetupState.REJECTED_EXHAUSTED;
    }

    public BigDecimal getFirst15MinHigh() {
        return first15MinHigh;
    }

    public void setFirst15MinHigh(BigDecimal first15MinHigh) {
        this.first15MinHigh = first15MinHigh;
    }

    public BigDecimal getFirst15MinLow() {
        return first15MinLow;
    }

    public void setFirst15MinLow(BigDecimal first15MinLow) {
        this.first15MinLow = first15MinLow;
    }

    public BigDecimal getPdh() {
        return pdh;
    }

    public void setPdh(BigDecimal pdh) {
        this.pdh = pdh;
    }

    public BigDecimal getPdl() {
        return pdl;
    }

    public void setPdl(BigDecimal pdl) {
        this.pdl = pdl;
    }

    public Double getPcr() {
        return pcr;
    }

    public void setPcr(Double pcr) {
        this.pcr = pcr;
    }

    public BigDecimal getOptionResistanceStrike() {
        return optionResistanceStrike;
    }

    public void setOptionResistanceStrike(BigDecimal optionResistanceStrike) {
        this.optionResistanceStrike = optionResistanceStrike;
    }

    public BigDecimal getOptionSupportStrike() {
        return optionSupportStrike;
    }

    public void setOptionSupportStrike(BigDecimal optionSupportStrike) {
        this.optionSupportStrike = optionSupportStrike;
    }

    public String getSetupPattern() {
        return setupPattern;
    }

    public void setSetupPattern(String setupPattern) {
        this.setupPattern = setupPattern;
    }

    public Double getOiChangePct() {
        return oiChangePct;
    }

    public void setOiChangePct(Double oiChangePct) {
        this.oiChangePct = oiChangePct;
    }

    public boolean isInPosition() {
        return state == LowestVolumeSetupState.IN_POSITION
                || state == LowestVolumeSetupState.PARTIAL_BOOKED;
    }

    public BigDecimal getInitialLegMove() {
        return initialLegMove;
    }

    public long getTriggerCandleVolume() {
        return triggerCandleVolume;
    }

    public int getArmedCandlesElapsed() {
        return armedCandlesElapsed;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastExitTime() {
        return lastExitTime;
    }

    public void setLastExitTime(Instant lastExitTime) {
        this.lastExitTime = lastExitTime;
    }

    public Instant getSetupCreatedTime() {
        return setupCreatedTime;
    }

    public Double getSessionHighAtArming() {
        return sessionHighAtArming;
    }

    public void setSessionHighAtArming(Double sessionHighAtArming) {
        this.sessionHighAtArming = sessionHighAtArming;
    }

    public Double getSessionLowAtArming() {
        return sessionLowAtArming;
    }

    public void setSessionLowAtArming(Double sessionLowAtArming) {
        this.sessionLowAtArming = sessionLowAtArming;
    }

    public int getPdhFetchAttempts() {
        return pdhFetchAttempts;
    }

    /** Records one PDH/PDL lazy-fetch attempt. Returns the new attempt count. */
    public synchronized int recordPdhFetchAttempt() {
        this.pdhFetchAttempts++;
        return this.pdhFetchAttempts;
    }

    @Override
    public String toString() {
        return "LowestVolumeSetup{"
                + "symbol='"
                + symbol
                + '\''
                + ", direction="
                + direction
                + ", state="
                + state
                + ", triggerPrice="
                + triggerPrice
                + ", stopLossPrice="
                + stopLossPrice
                + ", target1Price="
                + target1Price
                + ", dayLowestVolume="
                + dayLowestVolume
                + ", tradeAttempts="
                + tradeAttempts
                + '}';
    }
}
