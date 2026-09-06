package com.tradingbot.model.execution;

/**
 * Trade execution modes: - PAPER: Simulated local executions with virtual orders and live price
 * fills - LIVE: Real broker order routing to Shoonya via /PlaceOrder
 */
public enum ExecutionMode {
    PAPER,
    LIVE
}
