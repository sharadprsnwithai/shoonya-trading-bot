package com.tradingbot.strategy.breakoutvwap.model;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * One open or completed BVS futures trade: fixed stop at the trigger candle's opposite extreme,
 * 2.5R partial target, breakeven stop for the runner after the partial.
 */
@Getter
@Setter
public class BvsPosition {

    private String symbol;
    private String brokerTradingSymbol;
    private String token;
    private BvsBias direction;
    private double entryPrice;
    private double stopPrice;
    private double rValue;
    private double targetPrice;
    private int totalQuantity;
    private int remainingQuantity;
    private boolean partialBooked;
    private Instant entryTime;
    private String tradeId;
}
