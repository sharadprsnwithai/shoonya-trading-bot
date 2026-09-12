package com.tradingbot.model.strategy;

/**
 * Direction of an OHL-VWAP setup derived from the first 15-minute candle's opening profile.
 *
 * <pre>
 * BEARISH = open ≈ high  (opened at day high, fell) → buy PE on VWAP top→bottom cross
 * BULLISH = open ≈ low   (opened at day low, rose)  → buy CE on VWAP bottom→top cross
 * </pre>
 */
public enum OhlvDirection {
    BEARISH,
    BULLISH
}
