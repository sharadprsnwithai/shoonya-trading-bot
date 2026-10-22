package com.tradingbot.strategy.breakoutvwap.model;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Mutable per-symbol BVS setup state: session range, previous-day levels, latched bias, armed
 * trigger and its entry window. One instance per universe symbol, reset daily after the 09:25 scan.
 */
@Getter
@Setter
public class BvsSetup {

    private String symbol;
    private BvsSetupState state = BvsSetupState.AWAITING_RANGE;
    private BvsBias bias = BvsBias.NONE;
    private Double sessionRangeHigh;
    private Double sessionRangeLow;
    private Double pdh;
    private Double pdl;
    private Double triggerHigh;
    private Double triggerLow;

    /** Close time of the trigger candle — the entry window starts here. */
    private Instant triggerArmedAt;

    /** True once the armed window has been used (entry taken or expired). */
    private boolean entryConsumed;

    public BvsSetup() {}

    public BvsSetup(String symbol) {
        this.symbol = symbol;
    }
}
