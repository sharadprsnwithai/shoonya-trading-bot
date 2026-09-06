package com.tradingbot.strategy;

/** Trade action types emitted by intraday strategies. */
public enum SignalAction {
    BUY,
    SELL,
    EXIT_LONG,
    EXIT_SHORT,
    SQUARE_OFF,
    HOLD
}
