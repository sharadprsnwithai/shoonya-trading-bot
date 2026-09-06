package com.tradingbot.model.order;

/** Order execution lifecycle status. */
public enum OrderStatus {
    OPEN,
    PENDING,
    TRIGGER_PENDING,
    COMPLETE,
    REJECTED,
    CANCELLED
}
