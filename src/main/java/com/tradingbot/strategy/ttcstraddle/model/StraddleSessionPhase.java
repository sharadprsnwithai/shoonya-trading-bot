package com.tradingbot.strategy.ttcstraddle.model;

/** Execution phase of the intraday double-session straddle strategy. */
public enum StraddleSessionPhase {
    IDLE,
    HEDGES_BOUGHT,
    MORNING_STRADDLE_ACTIVE,
    MORNING_STRADDLE_EXITED,
    AFTERNOON_STRADDLE_ACTIVE,
    ALL_CLOSED
}
