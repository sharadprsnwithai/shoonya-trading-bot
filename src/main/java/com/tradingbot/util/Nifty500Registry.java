package com.tradingbot.util;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registry maintaining the NIFTY 500 stock universe for the RSI Highway Multi-Timeframe Strategy.
 * Contains token mapping, symbols list, and lookup utilities.
 */
public final class Nifty500Registry {

    public record StockMetadata(
            String symbol,
            String exchange,
            String token,
            boolean isFno,
            BigDecimal tickSize
    ) {}

    private static final Map<String, StockMetadata> STOCKS = new LinkedHashMap<>();

    private static final List<String> NIFTY_500_SYMBOLS = List.of(
            "360ONE", "3MINDIA", "ABB", "ACC", "ACMESOLAR", "AIAENG", "APLAPOLLO", "AUBANK", "AWL", "AADHARHFC",
            "AARTIIND", "AAVAS", "ABBOTINDIA", "ACE", "ACUTAAS", "ADANIENSOL", "ADANIENT", "ADANIGREEN", "ADANIPORTS", "ADANIPOWER",
            "ATGL", "ABCAPITAL", "ABFRL", "ABLBL", "ABREL", "ABSLAMC", "CPPLUS", "AEGISLOG", "AEGISVOPAK", "AFCONS",
            "AFFLE", "AJANTPHARM", "ALKEM", "ABDL", "ARE&M", "AMBER", "AMBUJACEM", "ANANDRATHI", "ANANTRAJ", "ANGELONE",
            "ANTHEM", "ANURAS", "APARINDS", "APOLLOHOSP", "APOLLOTYRE", "APTUS", "ASAHIINDIA", "ASHOKLEY", "ASIANPAINT", "ASTERDM",
            "ASTRAL", "ATHERENERG", "ATUL", "AUROPHARMA", "AIIL", "DMART", "AXISBANK", "BEML", "BLS", "BSE",
            "BAJAJ-AUTO", "BAJFINANCE", "BAJAJFINSV", "BAJAJHLDNG", "BAJAJHFL", "BALKRISIND", "BALRAMCHIN", "BANDHANBNK", "BANKBARODA", "BANKINDIA",
            "MAHABANK", "BATAINDIA", "BAYERCROP", "BELRISE", "BERGEPAINT", "BDL", "BEL", "BHARATFORG", "BHEL", "BPCL",
            "BHARTIARTL", "BHARTIHEXA", "BIKAJI", "GROWW", "BIOCON", "BSOFT", "BLUEDART", "BLUEJET", "BLUESTARCO", "BBTC",
            "BOSCHLTD", "FIRSTCRY", "BRIGADE", "BRITANNIA", "MAPMYINDIA", "CCL", "CESC", "CGPOWER", "CIEINDIA", "CRISIL",
            "CANFINHOME", "CANBK", "CANHLIFE", "CAPLIPOINT", "CGCL", "CARBORUNIV", "CARTRADE", "CASTROLIND", "CEATLTD", "CEMPRO",
            "CENTRALBK", "CDSL", "CHALET", "CHAMBLFERT", "CHENNPETRO", "CHOICEIN", "CHOLAHLDNG", "CHOLAFIN", "CIPLA", "CUB",
            "CLEAN", "COALINDIA", "COCHINSHIP", "COFORGE", "COHANCE", "COLPAL", "CAMS", "CONCORDBIO", "CONCOR", "COROMANDEL",
            "CRAFTSMAN", "CREDITACC", "CROMPTON", "CUMMINSIND", "CYIENT", "DCMSHRIRAM", "DLF", "DOMS", "DABUR", "DALBHARAT",
            "DATAPATTNS", "DEEPAKFERT", "DEEPAKNTR", "DELHIVERY", "DEVYANI", "DIVISLAB", "DIXON", "LALPATHLAB", "DRREDDY", "DUMMYHEG",
            "EIDPARRY", "EIHOTEL", "EICHERMOT", "ELECON", "ELGIEQUIP", "EMAMILTD", "EMCURE", "EMMVEE", "ENDURANCE", "ENGINERSIN",
            "ERIS", "ESCORTS", "ETERNAL", "EXIDEIND", "NYKAA", "FEDERALBNK", "FACT", "FINCABLES", "FSL", "FIVESTAR",
            "FORCEMOT", "FORTIS", "GAIL", "GVT&D", "GMRAIRPORT", "GABRIEL", "GALLANTT", "GRSE", "GICRE", "GILLETTE",
            "GLAND", "GLAXO", "GLENMARK", "MEDANTA", "GODIGIT", "GPIL", "GODFRYPHLP", "GODREJCP", "GODREJIND", "GODREJPROP",
            "GRANULES", "GRAPHITE", "GRASIM", "GRAVITA", "GESHIP", "FLUOROCHEM", "GMDCLTD", "HBLENGINE", "HCLTECH", "HDBFS",
            "HDFCAMC", "HDFCBANK", "HDFCLIFE", "HEG", "HFCL", "HAVELLS", "HEROMOTOCO", "HEXT", "HSCL", "HINDALCO",
            "HAL", "HINDCOPPER", "HINDPETRO", "HINDUNILVR", "HINDZINC", "POWERINDIA", "HOMEFIRST", "HONASA", "HONAUT", "HUDCO",
            "HYUNDAI", "ICICIBANK", "ICICIGI", "ICICIAMC", "ICICIPRULI", "IDBI", "IDFCFIRSTB", "IFCI", "IIFL", "IRB",
            "IRCON", "ITCHOTELS", "ITC", "ITI", "INDGN", "INDIACEM", "INDIAMART", "INDIANB", "IEX", "INDHOTEL",
            "IOC", "IOB", "IRCTC", "IRFC", "IREDA", "IGL", "INDUSTOWER", "INDUSINDBK", "NAUKRI", "INFY",
            "INOXWIND", "INTELLECT", "INDIGO", "IGIL", "IKS", "IPCALAB", "JKCEMENT", "JBMA", "JKTYRE", "JMFINANCIL",
            "JSWCEMENT", "JSWDULUX", "JSWENERGY", "JSWINFRA", "JSWSTEEL", "JAINREC", "JPPOWER", "J&KBANK", "JINDALSAW", "JSL",
            "JINDALSTEL", "JIOFIN", "JUBLFOOD", "JUBLINGREA", "JUBLPHARMA", "JWL", "JYOTICNC", "KPRMILL", "KEI", "KPITTECH",
            "KAJARIACER", "KPIL", "KALYANKJIL", "KARURVYSYA", "KAYNES", "KEC", "KFINTECH", "KIRLOSENG", "KOTAKBANK", "KIMS",
            "LTF", "LTTS", "LGEINDIA", "LICHSGFIN", "LTFOODS", "LTM", "LT", "LATENTVIEW", "LAURUSLABS", "THELEELA",
            "LEMONTREE", "LENSKART", "LICI", "LINDEINDIA", "LLOYDSME", "LODHA", "LUPIN", "MMTC", "MRF", "MGL",
            "M&MFIN", "M&M", "MANAPPURAM", "MRPL", "MANKIND", "MARICO", "MARUTI", "MFSL", "MAXHEALTH", "MAZDOCK",
            "MEESHO", "MINDACORP", "MSUMI", "MOTILALOFS", "MPHASIS", "MCX", "MUTHOOTFIN", "NATCOPHARM", "NBCC", "NCC",
            "NHPC", "NLCINDIA", "NMDC", "NSLNISP", "NTPCGREEN", "NTPC", "NH", "NATIONALUM", "NAVA", "NAVINFLUOR",
            "NESTLEIND", "NETWEB", "NEULANDLAB", "NEWGEN", "NAM-INDIA", "NIVABUPA", "NUVAMA", "NUVOCO", "OBEROIRLTY", "ONGC",
            "OIL", "OLAELEC", "OLECTRA", "PAYTM", "ONESOURCE", "OFSS", "POLICYBZR", "PCBL", "PGEL", "PIIND",
            "PNBHOUSING", "PTCIL", "PVRINOX", "PAGEIND", "PARADEEP", "PATANJALI", "PERSISTENT", "PETRONET", "PFIZER", "PHOENIXLTD",
            "PWL", "PIDILITIND", "PINELABS", "PIRAMALFIN", "PPLPHARMA", "POLYMED", "POLYCAB", "POONAWALLA", "PFC", "POWERGRID",
            "PREMIERENE", "PRESTIGE", "PFOCUS", "PNB", "RRKABEL", "RBLBANK", "RECLTD", "RHIM", "RITES", "RADICO",
            "RVNL", "RAILTEL", "RAINBOW", "RKFORGE", "REDINGTON", "RELIANCE", "RPOWER", "SBFC", "SBICARD", "SBILIFE",
            "SJVN", "SRF", "SAGILITY", "SAILIFE", "SAMMAANCAP", "MOTHERSON", "SAPPHIRE", "SARDAEN", "SAREGAMA", "SCHAEFFLER",
            "SCHNEIDER", "SCI", "SHREECEM", "SHRIRAMFIN", "SHYAMMETL", "ENRIN", "SIEMENS", "SIGNATURE", "SOBHA", "SOLARINDS",
            "SONACOMS", "SONATSOFTW", "STARHEALTH", "SBIN", "SAIL", "SUMICHEM", "SUNPHARMA", "SUNTV", "SUNDARMFIN", "SUPREMEIND",
            "SPLPETRO", "SUZLON", "SWANCORP", "SWIGGY", "SYNGENE", "SYRMA", "TBOTEK", "TVSMOTOR", "TATACAP", "TATACHEM",
            "TATACOMM", "TCS", "TATACONSUM", "TATAELXSI", "TATAINVEST", "TMCV", "TMPV", "TATAPOWER", "TATASTEEL", "TATATECH",
            "TTML", "TECHM", "TECHNOE", "TEGA", "TEJASNET", "TENNIND", "NIACL", "RAMCOCEM", "THERMAX", "TIMKEN",
            "TITAGARH", "TITAN", "TORNTPHARM", "TORNTPOWER", "TARIL", "TRAVELFOOD", "TRENT", "TRIDENT", "TRITURBINE", "TIINDIA",
            "UCOBANK", "UNOMINDA", "UPL", "UTIAMC", "ULTRACEMCO", "UNIONBANK", "UBL", "UNITDSPR", "URBANCO", "USHAMART",
            "VTL", "VBL", "VEDL", "VIJAYA", "VMM", "IDEA", "VOLTAS", "WAAREEENER", "WELCORP", "WELSPUNLIV",
            "WHIRLPOOL", "WIPRO", "WOCKPHARMA", "YESBANK", "ZFCVINDIA", "ZEEL", "ZENTEC", "ZENSARTECH", "ZYDUSLIFE", "ZYDUSWELL",
            "ECLERX"
    );

    static {
        BigDecimal defaultTick = BigDecimal.valueOf(0.05);
        for (String sym : NIFTY_500_SYMBOLS) {
            String clean = sym.toUpperCase(Locale.ROOT).trim();
            // Resolve token if present in StockFnoRegistry or Nifty200Registry
            String tok = StockFnoRegistry.getToken(clean);
            boolean isFno = StockFnoRegistry.getAllInstruments().containsKey(clean);
            if (tok == null) {
                var n200 = Nifty200Registry.getMetadata(clean);
                if (n200 != null) {
                    tok = n200.token();
                    isFno = n200.isFno();
                }
            }
            STOCKS.put(clean, new StockMetadata(clean, "NSE", tok, isFno, defaultTick));
        }
    }

    private Nifty500Registry() {}

    public static List<String> getAllSymbols() {
        return NIFTY_500_SYMBOLS;
    }

    public static boolean containsSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        return STOCKS.containsKey(symbol.toUpperCase(Locale.ROOT).trim());
    }

    public static StockMetadata getMetadata(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        return STOCKS.get(symbol.toUpperCase(Locale.ROOT).trim());
    }

    public static Map<String, StockMetadata> getAllMetadata() {
        return Collections.unmodifiableMap(STOCKS);
    }
}
