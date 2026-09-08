package com.tradingbot.model.strategy;

import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tracks the life-cycle and state variables of an individual stock setup under the Lowest Volume
 * Reversal & Continuation Strategy.
 */
public class LowestVolumeSetup {

    private final String symbol;
    private LowestVolumeDirection direction;
    private LowestVolumeSetupState state;
    private final List<Candle> initialLegCandles = new ArrayList<>();
    private Candle triggerCandle;
    private BigDecimal triggerPrice;
    private BigDecimal stopLossPrice;
    private BigDecimal target1Price;
    private double atr14;
    private BigDecimal initialLegMove = BigDecimal.ZERO;
    private long triggerCandleVolume;
    private int armedCandlesElapsed = 0;
    private String rejectionReason;
    private Instant updatedAt;
    private Instant setupCreatedTime;

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
        this.triggerCandle = candle;
        this.triggerPrice = triggerPrc;
        this.stopLossPrice = slPrc;
        this.target1Price = targetPrc;
        this.triggerCandleVolume = (candle != null) ? candle.volume() : 0L;
        this.armedCandlesElapsed = 0;
        this.state = LowestVolumeSetupState.TRIGGER_ARMED;
        this.updatedAt = Instant.now();
    }

    public synchronized void incrementArmedTimeout() {
        this.armedCandlesElapsed++;
        this.updatedAt = Instant.now();
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

    public Instant getSetupCreatedTime() {
        return setupCreatedTime;
    }

    public boolean isExhaustedOrRejected() {
        return state == LowestVolumeSetupState.REJECTED_EXHAUSTED;
    }

    public boolean isInPosition() {
        return state == LowestVolumeSetupState.IN_POSITION
                || state == LowestVolumeSetupState.PARTIAL_BOOKED;
    }

    public boolean isClosed() {
        return state == LowestVolumeSetupState.CLOSED_SL
                || state == LowestVolumeSetupState.CLOSED_TARGET
                || state == LowestVolumeSetupState.CLOSED_TRAIL_EXIT
                || state == LowestVolumeSetupState.CLOSED_TIMEOUT;
    }
}
