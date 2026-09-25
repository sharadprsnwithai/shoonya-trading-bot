package com.tradingbot.model.strategy;

/** Exit strategy mode for Lowest Volume Reversal trades. */
public enum LvrExitMode {
    PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500,
    PARTIAL_1_2_TRAIL_COST_EOD_1500,
    FULL_TARGET_1_2,
    PARTIAL_RUNNER_10EMA,
    // Backward compatibility aliases
    FULL_TARGET_1_4,
    PARTIAL_1_4_TRAIL_1_1_EOD_1500
}
