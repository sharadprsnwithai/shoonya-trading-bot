package com.tradingbot.model.strategy;

/**
 * State machine states for Lowest Volume Reversal & Continuation strategy.
 *
 * <pre>
 * IDLE -> SCANNING -> LEG_FORMING -> LEG_CONFIRMED -> PULLBACK_TRACKING
 *      -> TRIGGER_ARMED -> IN_POSITION -> PARTIAL_BOOKED -> CLOSED_*
 * </pre>
 */
public enum LowestVolumeSetupState {
    IDLE,
    SCANNING,
    LEG_FORMING,
    LEG_CONFIRMED,
    PULLBACK_TRACKING,
    TRIGGER_ARMED,
    IN_POSITION,
    PARTIAL_BOOKED,
    CLOSED_SL,
    CLOSED_TARGET,
    CLOSED_TRAIL_EXIT,
    CLOSED_TIMEOUT,
    REJECTED_EXHAUSTED
}
