package com.tradingbot.strategy;

import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;

/**
 * Standard SPI interface that all Intraday Strategies must implement. Strategies consume market
 * data events (Candles / Ticks) and emit actionable {@link TradeSignal}s.
 */
public interface IntradayStrategy {

    /** Standard NSE market hours */
    LocalTime MARKET_ENTRY_START = LocalTime.of(9, 15);

    LocalTime MARKET_ENTRY_CUTOFF = LocalTime.of(15, 10);
    LocalTime INTRADAY_SQUARE_OFF = LocalTime.of(15, 15);

    /** Unique identifier for this strategy (e.g. "NIFTY_VWAP_MOMENTUM_01"). */
    String getId();

    /** Human-readable display name for UI / Logging. */
    String getName();

    /** List of target symbols this strategy monitors (e.g. ["NIFTY50", "NSE:RELIANCE"]). */
    List<String> getSubscribedSymbols();

    /** Expected candle bar timeframe interval (e.g. "1m", "5m", "15m"). */
    String getTimeframe();

    /**
     * Core strategy evaluation method triggered on every completed candle bar.
     *
     * @param latestCandle the most recently completed candle
     * @param history previous historical candles (chronological order)
     * @return a {@link TradeSignal} indicating BUY, SELL, EXIT, or HOLD
     */
    TradeSignal onCandle(Candle latestCandle, List<Candle> history);

    /**
     * Real-time intra-bar tick evaluation for trailing stop-loss, profit booking, or instant
     * emergency exits.
     *
     * @param symbol trading symbol
     * @param ltp current last traded price
     * @param timestamp event timestamp
     * @return trade signal, or {@code TradeSignal.hold(...)} if no action required
     */
    default TradeSignal onTick(String symbol, BigDecimal ltp, Instant timestamp) {
        return TradeSignal.hold(getId(), symbol, "No intra-bar tick action");
    }

    /**
     * Validates whether current time is within the allowed intraday entry window (09:15 to 15:10
     * IST).
     */
    default boolean isWithinEntryWindow(LocalTime currentTime) {
        return !currentTime.isBefore(MARKET_ENTRY_START)
                && !currentTime.isAfter(MARKET_ENTRY_CUTOFF);
    }

    /**
     * Checks if current time has reached the mandatory intraday auto-square-off cutoff (>= 15:15
     * IST).
     */
    default boolean shouldAutoSquareOff(LocalTime currentTime) {
        return !currentTime.isBefore(INTRADAY_SQUARE_OFF);
    }

    /**
     * Lifecycle callback invoked at market open (09:15 IST) each morning to reset daily counters,
     * daily VWAP accumulator, max daily drawdown, and daily trade counts.
     */
    default void onResetDaily() {
        // Default no-op for stateless strategies; stateful strategies override to clear daily state
    }

    /** Returns true if this strategy is active and generating live orders. */
    boolean isEnabled();

    /** Dynamically enables or pauses this strategy (e.g. via Kill Switch, API, or Risk Manager). */
    void setEnabled(boolean enabled);
}
