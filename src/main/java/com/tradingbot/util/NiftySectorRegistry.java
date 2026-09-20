package com.tradingbot.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry mapping the 11 major NSE Sectoral Indices to their active F&O constituent stocks. Used
 * by the Lowest Volume Reversal (LVR) strategy for 09:25 IST sector ranking and stock selection.
 */
public final class NiftySectorRegistry {

    public static final List<String> NIFTY_50_CONSTITUENTS =
            List.of(
                    "ADANIENT",
                    "ADANIPORTS",
                    "APOLLOHOSP",
                    "ASIANPAINT",
                    "AXISBANK",
                    "BAJAJ-AUTO",
                    "BAJFINANCE",
                    "BAJAJFINSV",
                    "BEL",
                    "BPCL",
                    "BHARTIARTL",
                    "BRITANNIA",
                    "CIPLA",
                    "COALINDIA",
                    "DRREDDY",
                    "EICHERMOT",
                    "GRASIM",
                    "HCLTECH",
                    "HDFCBANK",
                    "HDFCLIFE",
                    "HEROMOTOCO",
                    "HINDALCO",
                    "HINDUNILVR",
                    "ICICIBANK",
                    "INDUSINDBK",
                    "INFY",
                    "ITC",
                    "JSWSTEEL",
                    "KOTAKBANK",
                    "LT",
                    "M&M",
                    "MARUTI",
                    "NESTLEIND",
                    "NTPC",
                    "ONGC",
                    "POWERGRID",
                    "RELIANCE",
                    "SBILIFE",
                    "SHRIRAMFIN",
                    "SBIN",
                    "SUNPHARMA",
                    "TCS",
                    "TATACONSUM",
                    "TATAMOTORS",
                    "TATASTEEL",
                    "TECHM",
                    "TITAN",
                    "TRENT",
                    "ULTRACEMCO",
                    "WIPRO");

    private static final Map<String, List<String>> SECTORS = new LinkedHashMap<>();
    private static final Map<String, String> SYMBOL_TO_SECTOR = new LinkedHashMap<>();

    static {
        // 1. NIFTY MEDIA
        registerSector("NIFTY MEDIA", List.of("PVRINOX", "SUNTV", "ZEEL"));

        // 2. NIFTY IT
        registerSector(
                "NIFTY IT",
                List.of(
                        "TCS",
                        "INFY",
                        "WIPRO",
                        "TECHM",
                        "HCLTECH",
                        "COFORGE",
                        "LTIM",
                        "PERSISTENT",
                        "MPHASIS",
                        "LTTS"));

        // 3. NIFTY PHARMA
        registerSector(
                "NIFTY PHARMA",
                List.of(
                        "GLENMARK",
                        "ZYDUSLIFE",
                        "CIPLA",
                        "SUNPHARMA",
                        "DRREDDY",
                        "DIVISLAB",
                        "LUPIN",
                        "AUROPHARMA",
                        "BIOCON",
                        "TORNTPHARM",
                        "ALKEM",
                        "IPCALAB",
                        "LAURUSLABS",
                        "ABBOTINDIA",
                        "GRANULES"));

        // 4. NIFTY AUTO
        registerSector(
                "NIFTY AUTO",
                List.of(
                        "TATAMOTORS",
                        "MARUTI",
                        "M&M",
                        "BAJAJ-AUTO",
                        "HEROMOTOCO",
                        "EICHERMOT",
                        "TVSMOTOR",
                        "BHARATFORG",
                        "BOSCHLTD",
                        "MOTHERSON",
                        "MRF",
                        "BALKRISIND",
                        "APOLLOTYRE",
                        "EXIDEIND",
                        "ASHOKLEY"));

        // 5. NIFTY METAL
        registerSector(
                "NIFTY METAL",
                List.of(
                        "TATASTEEL",
                        "JSWSTEEL",
                        "HINDALCO",
                        "JINDALSTEL",
                        "SAIL",
                        "NATIONALUM",
                        "NMDC",
                        "VEDL",
                        "HINDZINC",
                        "APLAPOLLO"));

        // 6. NIFTY BANK
        registerSector(
                "NIFTY BANK",
                List.of(
                        "HDFCBANK",
                        "ICICIBANK",
                        "AXISBANK",
                        "KOTAKBANK",
                        "INDUSINDBK",
                        "SBIN",
                        "BANKBARODA",
                        "PNB",
                        "CANBK",
                        "FEDERALBNK",
                        "IDFCFIRSTB",
                        "AUBANK",
                        "BANDHANBNK",
                        "RBLBANK"));

        // 7. NIFTY FINANCIAL SERVICES
        registerSector(
                "NIFTY FIN SERVICE",
                List.of(
                        "BAJFINANCE",
                        "BAJAJFINSV",
                        "CHOLAFIN",
                        "HDFCLIFE",
                        "SBILIFE",
                        "ICICIGI",
                        "ICICIPRULI",
                        "HDFCAMC",
                        "MUTHOOTFIN",
                        "PFC",
                        "RECLTD",
                        "SHRIRAMFIN",
                        "M&MFIN",
                        "LICHSGFIN"));

        // 8. NIFTY FMCG
        registerSector(
                "NIFTY FMCG",
                List.of(
                        "HINDUNILVR",
                        "ITC",
                        "NESTLEIND",
                        "BRITANNIA",
                        "DABUR",
                        "GODREJCP",
                        "MARICO",
                        "COLPAL",
                        "TATACONSUM",
                        "VBL",
                        "UNITDSPR",
                        "UBL",
                        "BALRAMCHIN"));

        // 9. NIFTY PSU BANK
        registerSector(
                "NIFTY PSU BANK",
                List.of("SBIN", "BANKBARODA", "PNB", "CANBK", "UNIONBANK", "INDIANB"));

        // 10. NIFTY REALTY
        registerSector(
                "NIFTY REALTY",
                List.of("DLF", "GODREJPROP", "OBERREALTY", "PRESTIGE", "PHOENIXLTD", "BRIGADE"));

        // 11. NIFTY ENERGY
        registerSector(
                "NIFTY ENERGY",
                List.of(
                        "RELIANCE",
                        "NTPC",
                        "POWERGRID",
                        "ONGC",
                        "BPCL",
                        "IOC",
                        "GAIL",
                        "HINDPETRO",
                        "TATAPOWER",
                        "IGL",
                        "MGL",
                        "GUJGASLTD"));
    }

    private static void registerSector(String sectorName, List<String> symbols) {
        SECTORS.put(sectorName, Collections.unmodifiableList(symbols));
        for (String sym : symbols) {
            SYMBOL_TO_SECTOR.putIfAbsent(sym.toUpperCase(), sectorName);
        }
    }

    public static Map<String, List<String>> getSectorConstituents() {
        return Collections.unmodifiableMap(SECTORS);
    }

    public static List<String> getStocksForSector(String sectorName) {
        if (sectorName == null) {
            return Collections.emptyList();
        }
        return SECTORS.getOrDefault(sectorName.toUpperCase(), Collections.emptyList());
    }

    public static String getSectorForSymbol(String symbol) {
        if (symbol == null) {
            return null;
        }
        return SYMBOL_TO_SECTOR.get(symbol.toUpperCase());
    }

    private NiftySectorRegistry() {}
}
