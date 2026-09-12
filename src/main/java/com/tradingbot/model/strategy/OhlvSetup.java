package com.tradingbot.model.strategy;

/**
 * Tracks the life-cycle of a NIFTY 100 stock under the OHL (Open=High/Low) + VWAP crossover
 * paper-trading strategy.
 *
 * <pre>
 * WATCHLIST -> (VWAP straddle) -> ENTERED -> (opposite-VWAP close | 15:00) -> CLOSED
 *          \-> (marked level broken) -> INVALIDATED
 * </pre>
 *
 * <p>A setup is created at 09:31 IST when the first 15-minute candle qualifies as open=high
 * (BEARISH) or open=low (BULLISH) within tolerance <i>and</i> the 09:15-09:16 one-minute volume is
 * above the volume filter threshold.
 */
public class OhlvSetup {

    public enum State {
        WATCHLIST,
        ENTERED,
        INVALIDATED,
        CLOSED
    }

    private final String symbol;
    private final OhlvDirection direction;
    private final java.math.BigDecimal markedHigh;
    private final java.math.BigDecimal markedLow;
    private final long firstCandleVolume;
    private final double prevDaySma20Volume;
    private State state;
    private String reason;
    private java.time.Instant updatedAt;

    public OhlvSetup(
            String symbol,
            OhlvDirection direction,
            java.math.BigDecimal markedHigh,
            java.math.BigDecimal markedLow,
            long firstCandleVolume,
            double prevDaySma20Volume) {
        this.symbol = symbol;
        this.direction = direction;
        this.markedHigh = markedHigh;
        this.markedLow = markedLow;
        this.firstCandleVolume = firstCandleVolume;
        this.prevDaySma20Volume = prevDaySma20Volume;
        this.state = State.WATCHLIST;
        this.updatedAt = java.time.Instant.now();
    }

    public synchronized void transitionTo(State newState, String reason) {
        this.state = newState;
        this.reason = reason;
        this.updatedAt = java.time.Instant.now();
    }

    public String getSymbol() {
        return symbol;
    }

    public OhlvDirection getDirection() {
        return direction;
    }

    public java.math.BigDecimal getMarkedHigh() {
        return markedHigh;
    }

    public java.math.BigDecimal getMarkedLow() {
        return markedLow;
    }

    public long getFirstCandleVolume() {
        return firstCandleVolume;
    }

    public double getPrevDaySma20Volume() {
        return prevDaySma20Volume;
    }

    public State getState() {
        return state;
    }

    public String getReason() {
        return reason;
    }

    public java.time.Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isMonitored() {
        return state == State.WATCHLIST;
    }

    public boolean isClosedOrInvalidated() {
        return state == State.CLOSED || state == State.INVALIDATED;
    }
}
