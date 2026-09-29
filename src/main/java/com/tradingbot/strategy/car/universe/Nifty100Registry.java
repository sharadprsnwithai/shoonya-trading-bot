package com.tradingbot.strategy.car.universe;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Registry of NIFTY 100 stock constituents (Nifty 50 + Nifty Next 50). */
public final class Nifty100Registry {

    private Nifty100Registry() {}

    public static final List<String> NIFTY_100_CONSTITUENTS =
            List.of(
                    "RELIANCE",
                    "TCS",
                    "HDFCBANK",
                    "ICICIBANK",
                    "INFY",
                    "BHARTIARTL",
                    "ITC",
                    "SBIN",
                    "LICI",
                    "HINDUNILVR",
                    "LT",
                    "BAJFINANCE",
                    "HCLTECH",
                    "MARUTI",
                    "SUNPHARMA",
                    "ADANIENT",
                    "KOTAKBANK",
                    "TITAN",
                    "ONGC",
                    "TATAMOTORS",
                    "NTPC",
                    "AXISBANK",
                    "ADANIPORTS",
                    "ULTRACEMCO",
                    "POWERGRID",
                    "COALINDIA",
                    "BAJAJFINSV",
                    "M&M",
                    "WIPRO",
                    "NESTLEIND",
                    "ASIANPAINT",
                    "JSWSTEEL",
                    "IOC",
                    "GRASIM",
                    "TECHM",
                    "ADANIPOWER",
                    "HINDALCO",
                    "LTIM",
                    "VEDL",
                    "INDUSINDBK",
                    "DLF",
                    "TRENT",
                    "BEL",
                    "HAL",
                    "ZOMATO",
                    "SIEMENS",
                    "VBL",
                    "JIOFIN",
                    "PIDILITIND",
                    "CHOLAFIN",
                    "GAIL",
                    "DIVISLAB",
                    "GODREJCP",
                    "BPCL",
                    "SHRIRAMFIN",
                    "HINDZINC",
                    "TORNTPHARM",
                    "HDFCLIFE",
                    "IRFC",
                    "BANKBARODA",
                    "DRREDDY",
                    "ABB",
                    "BAJAJ-AUTO",
                    "CIPLA",
                    "APOLLOHOSP",
                    "TATAPOWER",
                    "PNB",
                    "EICHERMOT",
                    "INDIGO",
                    "HAVELLS",
                    "DABUR",
                    "AMBUJACEM",
                    "MANKIND",
                    "MAXHEALTH",
                    "SBILIFE",
                    "BOSCHLTD",
                    "UNIONBANK",
                    "COLPAL",
                    "IOB",
                    "ICICIPRULI",
                    "RECLTD",
                    "CANBK",
                    "TATACOMM",
                    "POLYCAB",
                    "UNITDSPR",
                    "MOTHERSON",
                    "PRESTIGE",
                    "MARICO",
                    "PFC",
                    "BERGEPAINT",
                    "PERSISTENT",
                    "OBEROIRLTY",
                    "PHOENIXLTD",
                    "MUTHOOTFIN",
                    "CUMMINSIND",
                    "AUROPHARMA",
                    "APOLLOTYRE",
                    "LTTS",
                    "GLENMARK",
                    "PVRINOX");

    public static Set<String> getUniverseWithHoldings(Set<String> holdings) {
        Set<String> universe = new LinkedHashSet<>(NIFTY_100_CONSTITUENTS);
        if (holdings != null) {
            universe.addAll(holdings);
        }
        return Collections.unmodifiableSet(universe);
    }
}
