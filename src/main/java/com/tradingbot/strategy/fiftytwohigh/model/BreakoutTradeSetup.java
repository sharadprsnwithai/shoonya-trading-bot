package com.tradingbot.strategy.fiftytwohigh.model;

import java.math.BigDecimal;

/**
 * Experimental "trade-ready" verdict for a close-confirmed 52-week-high breakout.
 *
 * <p>A raw 52-week-high cross is not, on its own, a tradeable edge. This record layers the five
 * research filters — volume participation, long-term trend, momentum, relative strength vs NIFTY,
 * and market regime — on top of the cross, and carries the recommended OTM bull-call-spread strikes
 * for the setup.
 *
 * <p>The filter encodes a two-year in-sample study of NSE F&amp;O breakouts. Treat every {@code
 * tradeReady=true} row as a <em>paper-trade candidate</em>, not validated alpha: confirm it live
 * before sizing.
 *
 * @param symbol NSE trading symbol
 * @param tradeReady whether the breakout cleared <em>all five</em> filters
 * @param volumeRatio Breakout-session volume divided by its 20-session average (nullable)
 * @param volumeConfirmed {@code volumeRatio} exceeded the configured participation threshold
 * @param above200dma close finished above the 200-session simple moving average
 * @param momentum20dPct 20-session price momentum in percent (nullable)
 * @param momentumPositive 20-session momentum was positive
 * @param relativeStrengthPct stock 20d momentum minus NIFTY 20d momentum, in percent (nullable)
 * @param relativeStrengthPositive relative strength vs NIFTY was positive
 * @param marketUptrend NIFTY 50 closed above its 200-session moving average
 * @param spotRef reference price used to derive strikes (the breakout close)
 * @param atmStrike nearest ATM strike to {@code spotRef}
 * @param buyStrike long leg of the recommended debit spread (ATM + long offset)
 * @param sellStrike short leg of the recommended debit spread (ATM + short offset)
 * @param lotSize F&amp;O lot size for the symbol
 */
public record BreakoutTradeSetup(
        String symbol,
        boolean tradeReady,
        BigDecimal volumeRatio,
        boolean volumeConfirmed,
        boolean above200dma,
        BigDecimal momentum20dPct,
        boolean momentumPositive,
        BigDecimal relativeStrengthPct,
        boolean relativeStrengthPositive,
        boolean marketUptrend,
        BigDecimal spotRef,
        BigDecimal atmStrike,
        BigDecimal buyStrike,
        BigDecimal sellStrike,
        int lotSize) {}
