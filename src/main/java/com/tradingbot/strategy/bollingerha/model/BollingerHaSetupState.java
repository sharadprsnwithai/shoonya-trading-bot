package com.tradingbot.strategy.bollingerha.model;

import java.math.BigDecimal;
import java.time.Instant;

/** Internal state machine tracking lower Bollinger Band touches and reversal signals per strike. */
public class BollingerHaSetupState {

    private final String optionType; // CE or PE
    private final String token;
    private final String symbol;
    private final BigDecimal strike;
    private boolean touchedLowerBand;
    private int candlesSinceTouch;
    private BigDecimal signalHigh;
    private BigDecimal signalLow;
    private Instant signalTime;
    private boolean pendingTrigger;

    public BollingerHaSetupState(
            String optionType, String token, String symbol, BigDecimal strike) {
        this.optionType = optionType;
        this.token = token;
        this.symbol = symbol;
        this.strike = strike;
        resetTouch();
    }

    public void recordLowerBandTouch() {
        this.touchedLowerBand = true;
        this.candlesSinceTouch = 0;
    }

    public void incrementCandleAge() {
        if (touchedLowerBand) {
            candlesSinceTouch++;
            // Only valid if green reversal occurs within 3 candles of lower band touch
            if (candlesSinceTouch > 3) {
                resetTouch();
            }
        }
    }

    public void armSignal(BigDecimal high, BigDecimal low, Instant time) {
        this.signalHigh = high;
        this.signalLow = low;
        this.signalTime = time;
        this.pendingTrigger = true;
    }

    public void resetTouch() {
        this.touchedLowerBand = false;
        this.candlesSinceTouch = 0;
        this.signalHigh = null;
        this.signalLow = null;
        this.signalTime = null;
        this.pendingTrigger = false;
    }

    public String getOptionType() {
        return optionType;
    }

    public String getToken() {
        return token;
    }

    public String getSymbol() {
        return symbol;
    }

    public BigDecimal getStrike() {
        return strike;
    }

    public boolean isTouchedLowerBand() {
        return touchedLowerBand;
    }

    public int getCandlesSinceTouch() {
        return candlesSinceTouch;
    }

    public BigDecimal getSignalHigh() {
        return signalHigh;
    }

    public BigDecimal getSignalLow() {
        return signalLow;
    }

    public Instant getSignalTime() {
        return signalTime;
    }

    public boolean isPendingTrigger() {
        return pendingTrigger;
    }

    public void setPendingTrigger(boolean pendingTrigger) {
        this.pendingTrigger = pendingTrigger;
    }
}
