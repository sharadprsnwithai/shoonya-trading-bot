package com.tradingbot.strategy.driftvwap.model;

/** Direction of the 15-minute macro drift relative to anchored VWAP and 1-hour momentum. */
public enum DriftDirection {
    BULLISH_DRIFT,
    BEARISH_DRIFT,
    NEUTRAL
}
