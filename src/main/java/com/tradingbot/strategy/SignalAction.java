package com.tradingbot.strategy;

/** Trade action types emitted by intraday strategies. */
public enum SignalAction {
    BUY,
    SELL,
    ENTRY_LONG,
    ENTRY_SHORT,
    EXIT_LONG,
    EXIT_SHORT,
    PARTIAL_EXIT_LONG,
    PARTIAL_EXIT_SHORT,
    UPDATE_STOP_LOSS,
    SQUARE_OFF,
    HOLD
}
