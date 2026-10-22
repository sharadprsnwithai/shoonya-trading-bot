package com.tradingbot.strategy.ttcstraddle.model;

/** Execution phase of the 10:00 AM intraday straddle strategy. */
public enum StraddleSessionPhase {
    IDLE,
    HEDGES_BOUGHT,
    STRADDLE_ACTIVE,
    ALL_CLOSED
}
