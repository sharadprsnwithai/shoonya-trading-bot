package com.tradingbot.model;

/**
 * One underlying row from NSE's live OI spurts API ({@code
 * https://www.nseindia.com/api/live-analysis-oi-spurts-underlyings}), used by the LVR strategy's
 * 09:25 IST phase-2 candidate selection.
 *
 * @param symbol NSE underlying symbol (stock or index), uppercased
 * @param latestOi current open interest in contracts
 * @param prevOi previous trading day's closing open interest in contracts
 * @param changeInOi open interest change in contracts ({@code latestOi - prevOi})
 * @param avgInOi percentage change in open interest ({@code changeInOi / prevOi * 100}) — NSE's own
 *     ranking metric for this feed
 * @param underlyingValue last traded price of the underlying
 */
public record NseOiSpurtItem(
        String symbol,
        long latestOi,
        long prevOi,
        long changeInOi,
        double avgInOi,
        double underlyingValue) {}
