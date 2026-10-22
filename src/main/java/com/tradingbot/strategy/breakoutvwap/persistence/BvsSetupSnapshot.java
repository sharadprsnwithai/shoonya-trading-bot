package com.tradingbot.strategy.breakoutvwap.persistence;

import com.tradingbot.strategy.breakoutvwap.model.BvsBias;
import com.tradingbot.strategy.breakoutvwap.model.BvsSetupState;
import java.time.Instant;

/**
 * JSON snapshot of a BVS setup for the daily state file. Public fields for direct Jackson binding
 * (same pattern as {@code LvrPositionSnapshot}); mirrors the live {@code BvsSetup}.
 */
public class BvsSetupSnapshot {

    public String symbol;
    public BvsSetupState state;
    public BvsBias bias;
    public Double sessionRangeHigh;
    public Double sessionRangeLow;
    public Double pdh;
    public Double pdl;
    public Double triggerHigh;
    public Double triggerLow;
    public Instant triggerArmedAt;
    public boolean entryConsumed;
}
