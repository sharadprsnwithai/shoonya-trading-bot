package com.tradingbot.strategy.breakoutvwap.persistence;

import com.tradingbot.strategy.breakoutvwap.model.BvsBias;
import java.time.Instant;

/**
 * JSON snapshot of one BVS trade (open or closed) for the daily state file. Public fields for
 * direct Jackson binding (same pattern as {@code LvrPositionSnapshot}); mirrors the live {@code
 * BvsPosition}.
 */
public class BvsPositionSnapshot {

    public String symbol;
    public String brokerTradingSymbol;
    public String token;
    public BvsBias direction;
    public double entryPrice;
    public double stopPrice;
    public double rValue;
    public double targetPrice;
    public int totalQuantity;
    public int remainingQuantity;
    public boolean partialBooked;
    public Instant entryTime;
    public String tradeId;
}
