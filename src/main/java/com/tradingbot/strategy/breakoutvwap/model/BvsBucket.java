package com.tradingbot.strategy.breakoutvwap.model;

/** The four "unusual activity" selection buckets feeding the BVS 09:25 universe scan. */
public enum BvsBucket {
    /** Top live gainers of the session so far, ranked by percent change. */
    GAINERS,
    /** Top live losers of the session so far, ranked by percent change. */
    LOSERS,
    /** Top open-interest spurts from the NSE OI-spurt feed. */
    OI_SPURT,
    /** Three-day lower-high / higher-low range contractions. */
    SQUEEZE
}
