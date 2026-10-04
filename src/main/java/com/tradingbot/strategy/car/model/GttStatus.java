package com.tradingbot.strategy.car.model;

public enum GttStatus {
    PENDING,
    TRIGGERED,
    CANCELLED,
    REJECTED,
    EXPIRED,
    DISABLED;

    /** True when the trigger can never fire again, so the local state entry must be released. */
    public boolean isTerminalNotTriggered() {
        return this == CANCELLED || this == REJECTED || this == EXPIRED || this == DISABLED;
    }
}
