package com.tradingbot.strategy.commodity.feed;

import com.tradingbot.model.Candle;
import com.tradingbot.model.OptionChainResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only market data seam used by the commodity VWAP strategy. The live implementation delegates
 * to Shoonya services; replay implementations serve historical bars so backtests exercise the exact
 * production code path.
 */
public interface CommodityQuoteFeed {

    /** Latest traded price for the symbol, or {@code null} when unavailable. */
    BigDecimal liveLtp(String symbol);

    /**
     * 15-minute candles covering roughly the last {@code daysBack} calendar days, chronological.
     */
    List<Candle> candles15m(String symbol, int daysBack);

    /** Near-month option chain for the symbol, or {@code null} when unavailable. */
    OptionChainResponse optionChain(String symbol);

    /**
     * Expiry date of the near-month futures contract for the symbol, or {@code null} when it cannot
     * be resolved. Used by the strategy's DTE (days-to-expiry) safety gate to avoid the MCX
     * physical-tender window.
     */
    default LocalDate contractExpiry(String symbol) {
        return null;
    }
}
