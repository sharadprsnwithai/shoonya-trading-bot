package com.tradingbot.util;

import java.util.Collections;
import java.util.List;

/**
 * Registry of the NIFTY 100 stock universe used by the OHL-VWAP paper-trading strategy.
 *
 * <p>Contains the canonical symbol list only. NSE instrument tokens are <b>not</b> hardcoded here:
 * symbols with verified tokens in {@link StockFnoRegistry} use those, and every other symbol is
 * resolved at runtime through the Shoonya SearchScrip API (see {@code
 * ShoonyaMarketDataService#resolveToken}). This guarantees the correct live NSE token even for
 * recently-listed tickers (e.g. ENRIN, TMCV, TMPV, LTM) without relying on stale hardcoded
 * identifiers.
 *
 * <p>F&amp;O eligibility is an exchange fact that changes over time; it is therefore verified at
 * scan time by querying the NFO exchange for the underlying (see {@code
 * OhlvVwapStrategyService#isFnoEligible}).
 */
public final class Nifty100Registry {

    /** Immutable NIFTY 100 constituent list (as provided by the strategy author). */
    private static final List<String> NIFTY_100_SYMBOLS =
            List.of(
                    "ABB",
                    "ADANIENSOL",
                    "ADANIENT",
                    "ADANIGREEN",
                    "ADANIPORTS",
                    "ADANIPOWER",
                    "AMBUJACEM",
                    "APOLLOHOSP",
                    "ASIANPAINT",
                    "DMART",
                    "AXISBANK",
                    "BAJAJ-AUTO",
                    "BAJFINANCE",
                    "BAJAJFINSV",
                    "BAJAJHLDNG",
                    "BANKBARODA",
                    "BEL",
                    "BPCL",
                    "BHARTIARTL",
                    "BOSCHLTD",
                    "BRITANNIA",
                    "CGPOWER",
                    "CANBK",
                    "CHOLAFIN",
                    "CIPLA",
                    "COALINDIA",
                    "CUMMINSIND",
                    "DLF",
                    "DIVISLAB",
                    "DRREDDY",
                    "EICHERMOT",
                    "ETERNAL",
                    "GAIL",
                    "GODREJCP",
                    "GRASIM",
                    "HCLTECH",
                    "HDFCAMC",
                    "HDFCBANK",
                    "HDFCLIFE",
                    "HINDALCO",
                    "HAL",
                    "HINDUNILVR",
                    "HINDZINC",
                    "HYUNDAI",
                    "ICICIBANK",
                    "ITC",
                    "INDHOTEL",
                    "IOC",
                    "IRFC",
                    "INFY",
                    "INDIGO",
                    "JSWSTEEL",
                    "JINDALSTEL",
                    "JIOFIN",
                    "KOTAKBANK",
                    "LTM",
                    "LT",
                    "LODHA",
                    "M&M",
                    "MARUTI",
                    "MAXHEALTH",
                    "MAZDOCK",
                    "MUTHOOTFIN",
                    "NTPC",
                    "NESTLEIND",
                    "ONGC",
                    "PIDILITIND",
                    "PFC",
                    "POWERGRID",
                    "PNB",
                    "RECLTD",
                    "RELIANCE",
                    "SBILIFE",
                    "MOTHERSON",
                    "SHREECEM",
                    "SHRIRAMFIN",
                    "ENRIN",
                    "SIEMENS",
                    "SOLARINDS",
                    "SBIN",
                    "SUNPHARMA",
                    "TVSMOTOR",
                    "TATACAP",
                    "TCS",
                    "TATACONSUM",
                    "TMCV",
                    "TMPV",
                    "TATAPOWER",
                    "TATASTEEL",
                    "TECHM",
                    "TITAN",
                    "TORNTPHARM",
                    "TRENT",
                    "ULTRACEMCO",
                    "UNIONBANK",
                    "UNITDSPR",
                    "VBL",
                    "VEDL",
                    "WIPRO",
                    "ZYDUSLIFE");

    private Nifty100Registry() {}

    /** Returns the immutable NIFTY 100 symbol list. */
    public static List<String> getAllSymbols() {
        return Collections.unmodifiableList(NIFTY_100_SYMBOLS);
    }

    /**
     * Returns a pre-verified NSE token for a symbol when one exists in {@link StockFnoRegistry},
     * otherwise {@code null} (caller must fall back to runtime SearchScrip resolution).
     *
     * @param symbol canonical symbol, e.g. "RELIANCE"
     * @return the StockFnoRegistry token if known, else null
     */
    public static String getKnownToken(String symbol) {
        if (symbol == null) {
            return null;
        }
        return StockFnoRegistry.getToken(symbol.toUpperCase().trim());
    }
}
