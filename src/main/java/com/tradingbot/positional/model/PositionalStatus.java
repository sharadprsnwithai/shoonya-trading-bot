package com.tradingbot.positional.model;

/** Current state status for the Bollinger Band & Heikin-Ashi Positional Strategy. */
public enum PositionalStatus {
    FLAT,
    ALERT_PENDING,
    STAGED_FOR_APPROVAL,
    IN_BULL_PUT_SPREAD,
    IN_BEAR_CALL_SPREAD,
    IN_LONG_CE,
    IN_SHORT_PE
}
