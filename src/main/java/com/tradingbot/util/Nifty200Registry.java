package com.tradingbot.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry maintaining the NIFTY 200 stock universe, including token resolution, derivative/F&O
 * eligibility, lot sizes, and option strike intervals.
 */
public final class Nifty200Registry {

    public record StockMetadata(
            String symbol, String token, boolean isFno, int lotSize, BigDecimal strikeStep) {}

    private static final Map<String, StockMetadata> STOCKS = new LinkedHashMap<>();

    private static final List<String> NIFTY_200_SYMBOLS =
            List.of(
                    "360ONE",
                    "ABB",
                    "APLAPOLLO",
                    "AUBANK",
                    "ADANIENSOL",
                    "ADANIENT",
                    "ADANIGREEN",
                    "ADANIPORTS",
                    "ADANIPOWER",
                    "ATGL",
                    "ABCAPITAL",
                    "ALKEM",
                    "AMBUJACEM",
                    "APOLLOHOSP",
                    "ASHOKLEY",
                    "ASIANPAINT",
                    "ASTRAL",
                    "AUROPHARMA",
                    "AXISBANK",
                    "BSE",
                    "BAJAJ-AUTO",
                    "BAJFINANCE",
                    "BAJAJFINSV",
                    "BAJAJHLDNG",
                    "BANKBARODA",
                    "BANKINDIA",
                    "BDL",
                    "BEL",
                    "BHARATFORG",
                    "BHEL",
                    "BPCL",
                    "BHARTIARTL",
                    "BIOCON",
                    "BLUESTARCO",
                    "BOSCHLTD",
                    "BRITANNIA",
                    "CGPOWER",
                    "CANBK",
                    "CHOLAFIN",
                    "CIPLA",
                    "COALINDIA",
                    "COCHINSHIP",
                    "COFORGE",
                    "COLPAL",
                    "CONCOR",
                    "COROMANDEL",
                    "CUMMINSIND",
                    "DLF",
                    "DABUR",
                    "DIVISLAB",
                    "DIXON",
                    "DRREDDY",
                    "EICHERMOT",
                    "EXIDEIND",
                    "FEDERALBNK",
                    "FORTIS",
                    "GAIL",
                    "GMRAIRPORT",
                    "GLENMARK",
                    "GODFRYPHLP",
                    "GODREJCP",
                    "GODREJPROP",
                    "GRASIM",
                    "HCLTECH",
                    "HDFCAMC",
                    "HDFCBANK",
                    "HDFCLIFE",
                    "HAVELLS",
                    "HEROMOTOCO",
                    "HINDALCO",
                    "HAL",
                    "HINDPETRO",
                    "HINDUNILVR",
                    "HINDZINC",
                    "POWERINDIA",
                    "HUDCO",
                    "HYUNDAI",
                    "ICICIBANK",
                    "ICICIGI",
                    "IDFCFIRSTB",
                    "ITC",
                    "INDIANB",
                    "INDHOTEL",
                    "IOC",
                    "IRCTC",
                    "IRFC",
                    "IREDA",
                    "INDUSTOWER",
                    "INDUSINDBK",
                    "NAUKRI",
                    "INFY",
                    "INDIGO",
                    "JSWENERGY",
                    "JSWSTEEL",
                    "JINDALSTEL",
                    "JIOFIN",
                    "JUBLFOOD",
                    "KEI",
                    "KPITTECH",
                    "KALYANKJIL",
                    "KOTAKBANK",
                    "LTF",
                    "LICHSGFIN",
                    "LTM",
                    "LT",
                    "LAURUSLABS",
                    "LODHA",
                    "LUPIN",
                    "M&M",
                    "MRF",
                    "MARICO",
                    "MARUTI",
                    "MAXHEALTH",
                    "MAZDOCK",
                    "MFSL",
                    "MPASIS",
                    "MUTHOOTFIN",
                    "NHPC",
                    "NMDC",
                    "NTPC",
                    "NATIONALUM",
                    "NESTLEIND",
                    "OBCL",
                    "OIL",
                    "ONGC",
                    "OFSS",
                    "PBFINTECH",
                    "PCBL",
                    "PIIND",
                    "PNB",
                    "PRESTIGE",
                    "PATANJALI",
                    "PERSISTENT",
                    "PETRONET",
                    "PHOENIXLTD",
                    "PIDILITIND",
                    "POLYCAB",
                    "POONAWALLA",
                    "PFC",
                    "POWERGRID",
                    "PREMIERENE",
                    "PVRINOX",
                    "MOTHERSON",
                    "RVNL",
                    "RECLTD",
                    "RELIANCE",
                    "SBICARD",
                    "SBILIFE",
                    "SHREECEM",
                    "SHRIRAMFIN",
                    "SRF",
                    "MOTILALOFS",
                    "SAFARI",
                    "SAMVARDHNA",
                    "SUNDARMFIN",
                    "SUNPHARMA",
                    "SUNTV",
                    "SUPREMEIND",
                    "SUZLON",
                    "SWANENERGY",
                    "SYNGENE",
                    "TATACOMM",
                    "TCS",
                    "TATACONSUM",
                    "TATAELXSI",
                    "TATAMOTORS",
                    "TATAPOWER",
                    "TATASTEEL",
                    "TECHM",
                    "TITAN",
                    "TORNTPHARM",
                    "TRENT",
                    "TRITURBINE",
                    "TIINDIA",
                    "UPL",
                    "ULTRACEMCO",
                    "UNIONBANK",
                    "UNITDSPR",
                    "UNOMINDA",
                    "VBL",
                    "VEDL",
                    "VOLTAS",
                    "WIPRO",
                    "YESBANK",
                    "ZYDUSLIFE",
                    "IDEA");

    static {
        for (String sym : NIFTY_200_SYMBOLS) {
            String token = StockFnoRegistry.getToken(sym);
            int lot = StockFnoRegistry.getLotSize(sym);
            BigDecimal strikeStep = StockFnoRegistry.getStrikeStep(sym, BigDecimal.valueOf(1000));
            boolean isFno = (lot > 1);
            STOCKS.put(
                    sym,
                    new StockMetadata(
                            sym, token != null ? token : "10576", isFno, lot, strikeStep));
        }
    }

    private Nifty200Registry() {}

    public static List<String> getAllSymbols() {
        return NIFTY_200_SYMBOLS;
    }

    public static StockMetadata getMetadata(String symbol) {
        if (symbol == null) return null;
        return STOCKS.get(symbol.toUpperCase().trim());
    }

    public static boolean isFnoEligible(String symbol) {
        StockMetadata meta = getMetadata(symbol);
        return meta != null && meta.isFno();
    }

    public static int getLotSize(String symbol) {
        StockMetadata meta = getMetadata(symbol);
        return meta != null ? meta.lotSize() : 1;
    }

    public static BigDecimal calculateAtmStrike(String symbol, double price) {
        BigDecimal spot = BigDecimal.valueOf(price);
        BigDecimal step = StockFnoRegistry.getStrikeStep(symbol, spot);
        return spot.divide(step, 0, RoundingMode.HALF_UP).multiply(step);
    }
}
