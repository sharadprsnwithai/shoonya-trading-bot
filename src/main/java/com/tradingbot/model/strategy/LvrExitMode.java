package com.tradingbot.model.strategy;

/**
 * Exit strategy mode for Lowest Volume Reversal trades.
 *
 * <p>Authority: the reward-to-risk ratio itself is NOT encoded in these names — it comes from
 * {@code trading-bot.strategy.lowest-volume.target-rr} (default 2.5), which arms the setup, sizes
 * the planned reward and sets the executed target. The {@code *_1_2} constant names are retained as
 * stable identifiers (they are persisted in exit-reason strings and asserted by consumers); the
 * former {@code *_1_4} aliases computed 1:2 targets while claiming 1:4 and were removed to end the
 * config/spec drift.
 */
public enum LvrExitMode {
    PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500,
    PARTIAL_1_2_TRAIL_COST_EOD_1500,
    FULL_TARGET_1_2,
    PARTIAL_RUNNER_10EMA
}
