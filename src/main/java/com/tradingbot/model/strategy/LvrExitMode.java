package com.tradingbot.model.strategy;

/**
 * Exit strategy mode for Lowest Volume Reversal trades.
 *
 * <p>Authority: targets are 1:2 R:R (see {@code lvr_option_buying_spec.md} resolution in
 * {@code LVR_BUG_REPORT.md} M11). The former {@code *_1_4} aliases computed 1:2 targets while
 * claiming 1:4 — they were removed to end the config/spec drift; 1:2-named constants are the
 * only supported modes.
 */
public enum LvrExitMode {
    PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500,
    PARTIAL_1_2_TRAIL_COST_EOD_1500,
    FULL_TARGET_1_2,
    PARTIAL_RUNNER_10EMA
}
