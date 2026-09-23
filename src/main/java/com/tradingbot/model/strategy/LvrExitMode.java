package com.tradingbot.model.strategy;

/** Exit strategy mode for Lowest Volume Reversal trades. */
public enum LvrExitMode {
    FULL_TARGET_1_4,
    PARTIAL_RUNNER_10EMA,
    PARTIAL_1_4_TRAIL_1_1_EOD_1500
}
