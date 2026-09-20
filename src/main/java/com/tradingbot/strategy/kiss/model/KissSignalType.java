package com.tradingbot.strategy.kiss.model;

/** Actionable signal types emitted by the KISS Multi-Timeframe Strategy. */
public enum KissSignalType {
    BUY_SIGNAL,
    SHORT_SIGNAL,
    EXIT_TARGET,
    EXIT_STOP_LOSS,
    EXIT_MACD_REVERSAL,
    EXIT_WEEKEND
}
