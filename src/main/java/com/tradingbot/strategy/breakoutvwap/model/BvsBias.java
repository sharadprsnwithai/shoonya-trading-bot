package com.tradingbot.strategy.breakoutvwap.model;

/** Directional bias derived from the dual-level confluence. */
public enum BvsBias {
    /** Price closed above both the session-range high and the previous day's high. */
    LONG,
    /** Price closed below both the session-range low and the previous day's low. */
    SHORT,
    /** No confluence — the strategy does not trade. */
    NONE
}
