package com.tradingbot.strategy.breakoutvwap.model;

/** Lifecycle of one symbol's BVS setup. */
public enum BvsSetupState {
    /** The 09:30-10:00 session range has not fully printed yet. */
    AWAITING_RANGE,
    /** Range complete, waiting for dual-level confluence to latch a bias. */
    AWAITING_CONFLUENCE,
    /** Bias latched, waiting for a VWAP-crossing trigger candle. */
    BIAS_ARMED,
    /** Trigger candle armed, waiting for its high/low breach inside the entry window. */
    TRIGGER_ARMED
}
