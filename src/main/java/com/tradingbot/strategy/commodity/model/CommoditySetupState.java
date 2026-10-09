package com.tradingbot.strategy.commodity.model;

/** State machine for intraday commodity setups. */
public enum CommoditySetupState {
    IDLE,
    BIAS_IDENTIFIED,
    ARMED_LONG,
    ARMED_SHORT,
    IN_TRADE,
    COMPLETED,
    SKIPPED
}
