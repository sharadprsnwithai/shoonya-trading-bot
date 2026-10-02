package com.tradingbot.strategy.condor.model;

/**
 * Categorization of dynamic adjustments performed during an active Put Condor cycle.
 */
public enum PutCondorAdjustmentType {
    NONE,
    UPSIDE_FINANCING,
    SWEET_SPOT_ROLL,
    DEEP_CRASH_DEFENSE,
    GAMMA_SHIELD_EARLY_EXIT
}
