package com.tradingbot.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry containing NSE and BSE F&O instrument specifications (Strike Intervals, Standard Lot
 * Sizes, Expiry rules, and Option Symbol generation) for major Indices (NIFTY, SENSEX, BANKNIFTY)
 * and the Top 10 Champion Stocks (BSE, LAURUSLABS, SAIL, POLYCAB, ADANIENSOL, MCX, ADANIGREEN,
 * TORNTPHARM, BHEL, HINDALCO).
 */
public final class StockFnoRegistry {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record InstrumentInfo(
            String symbol,
            String token,
            BigDecimal strikeStep,
            int lotSize,
            String exchange,
            String segment) {
        public InstrumentInfo(String symbol, String token, BigDecimal strikeStep, int lotSize) {
            this(symbol, token, strikeStep, lotSize, "NSE", "NFO");
        }
    }

    private static final Map<String, InstrumentInfo> INSTRUMENTS = new LinkedHashMap<>();

    static {
        // Major Benchmark Indices
        register("NIFTY50", "26000", 50.0, 65, "NSE", "NFO");
        register("NIFTY", "26000", 50.0, 65, "NSE", "NFO");
        register("NIFTY 50", "26000", 50.0, 65, "NSE", "NFO");
        register("BANKNIFTY", "26009", 100.0, 30, "NSE", "NFO");
        register("BANK NIFTY", "26009", 100.0, 30, "NSE", "NFO");
        register("SENSEX", "1", 100.0, 20, "BSE", "BFO");
        register("BSESN", "1", 100.0, 20, "BSE", "BFO");
        register("FINNIFTY", "26037", 50.0, 65, "NSE", "NFO");
        register("MIDCPNIFTY", "26074", 25.0, 120, "NSE", "NFO");

        // Top 10 Champion High-Beta Stocks
        register("BSE", "19585", 50.0, 250, "NSE", "NFO");
        register("LAURUSLABS", "19234", 5.0, 1100, "NSE", "NFO");
        register("SAIL", "2963", 2.5, 4700, "NSE", "NFO");
        register("POLYCAB", "9590", 50.0, 125, "NSE", "NFO");
        register("ADANIENSOL", "14927", 20.0, 675, "NSE", "NFO");
        register("MCX", "31181", 50.0, 125, "NSE", "NFO");
        register("ADANIGREEN", "3563", 20.0, 500, "NSE", "NFO");
        register("TORNTPHARM", "3518", 50.0, 250, "NSE", "NFO");
        register("BHEL", "438", 5.0, 2625, "NSE", "NFO");
        register("HINDALCO", "1363", 10.0, 1400, "NSE", "NFO");

        // Active NSE F&O Equities Universe (Pre-populated for 0ms startup resolution)
        register("RELIANCE", "2885", 20.0, 250, "NSE", "NFO");
        register("TCS", "11536", 20.0, 175, "NSE", "NFO");
        register("INFY", "1594", 10.0, 400, "NSE", "NFO");
        register("HDFCBANK", "1333", 10.0, 550, "NSE", "NFO");
        register("ICICIBANK", "4963", 10.0, 700, "NSE", "NFO");
        register("SBIN", "3045", 5.0, 750, "NSE", "NFO");
        register("BHARTIARTL", "10604", 10.0, 475, "NSE", "NFO");
        register("TATAMOTORS", "3456", 5.0, 575, "NSE", "NFO");
        register("AXISBANK", "5900", 10.0, 625, "NSE", "NFO");
        register("KOTAKBANK", "1922", 10.0, 400, "NSE", "NFO");
        register("LT", "11483", 20.0, 150, "NSE", "NFO");
        register("BAJFINANCE", "317", 50.0, 125, "NSE", "NFO");
        register("BAJAJFINSV", "16675", 20.0, 500, "NSE", "NFO");
        register("MARUTI", "10999", 100.0, 50, "NSE", "NFO");
        register("TITAN", "3506", 20.0, 175, "NSE", "NFO");
        register("SUNPHARMA", "3351", 10.0, 350, "NSE", "NFO");
        register("WIPRO", "3787", 5.0, 1500, "NSE", "NFO");
        register("ITC", "1660", 2.0, 1600, "NSE", "NFO");
        register("TATASTEEL", "3499", 1.0, 5500, "NSE", "NFO");
        register("NTPC", "11630", 5.0, 1500, "NSE", "NFO");
        register("POWERGRID", "14977", 5.0, 1800, "NSE", "NFO");
        register("ONGC", "2475", 5.0, 2250, "NSE", "NFO");
        register("COALINDIA", "20374", 5.0, 1050, "NSE", "NFO");
        register("BPCL", "526", 5.0, 1800, "NSE", "NFO");
        register("IOC", "1624", 2.0, 4875, "NSE", "NFO");
        register("ADANIENT", "25", 20.0, 300, "NSE", "NFO");
        register("ADANIPORTS", "15083", 10.0, 400, "NSE", "NFO");
        register("ASIANPAINT", "236", 20.0, 200, "NSE", "NFO");
        register("ULTRACEMCO", "11532", 100.0, 100, "NSE", "NFO");
        register("GRASIM", "1232", 20.0, 250, "NSE", "NFO");
        register("JSWSTEEL", "11723", 10.0, 675, "NSE", "NFO");
        register("TECHM", "13538", 10.0, 600, "NSE", "NFO");
        register("HCLTECH", "7229", 10.0, 350, "NSE", "NFO");
        register("DIVISLAB", "10940", 50.0, 100, "NSE", "NFO");
        register("CIPLA", "694", 10.0, 650, "NSE", "NFO");
        register("APOLLOHOSP", "157", 50.0, 125, "NSE", "NFO");
        register("DRREDDY", "881", 50.0, 125, "NSE", "NFO");
        register("EICHERMOT", "910", 50.0, 150, "NSE", "NFO");
        register("HEROMOTOCO", "1348", 50.0, 150, "NSE", "NFO");
        register("BRITANNIA", "547", 50.0, 125, "NSE", "NFO");
        register("NESTLEIND", "17963", 20.0, 200, "NSE", "NFO");
        register("INDUSINDBK", "5258", 10.0, 500, "NSE", "NFO");
        register("M&M", "2031", 20.0, 200, "NSE", "NFO");
        register("DLF", "14732", 10.0, 825, "NSE", "NFO");
        register("GODREJCP", "10099", 10.0, 500, "NSE", "NFO");
        register("GODREJPROP", "17875", 20.0, 225, "NSE", "NFO");
        register("SHREECEM", "3103", 100.0, 25, "NSE", "NFO");
        register("HAVELLS", "9819", 20.0, 500, "NSE", "NFO");
        register("DABUR", "772", 5.0, 1250, "NSE", "NFO");
        register("PIDILITIND", "2664", 20.0, 250, "NSE", "NFO");
        register("CHOLAFIN", "685", 10.0, 625, "NSE", "NFO");
        register("MUTHOOTFIN", "23650", 20.0, 350, "NSE", "NFO");
        register("SRF", "3273", 20.0, 250, "NSE", "NFO");
        register("TRENT", "1964", 50.0, 100, "NSE", "NFO");
        register("VBL", "16669", 10.0, 1000, "NSE", "NFO");
        register("VOLTAS", "3718", 10.0, 300, "NSE", "NFO");
        register("BEL", "383", 5.0, 2850, "NSE", "NFO");
        register("HAL", "2303", 50.0, 150, "NSE", "NFO");
        register("PFC", "14299", 5.0, 1300, "NSE", "NFO");
        register("RECLTD", "15355", 5.0, 1400, "NSE", "NFO");
        register("SBICARD", "17971", 5.0, 800, "NSE", "NFO");
        register("SBILIFE", "21808", 10.0, 375, "NSE", "NFO");
        register("HDFCLIFE", "467", 5.0, 1100, "NSE", "NFO");
        register("ICICIGI", "21770", 10.0, 350, "NSE", "NFO");
        register("IDFCFIRSTB", "11184", 1.0, 7500, "NSE", "NFO");
        register("PNB", "10666", 1.0, 8000, "NSE", "NFO");
        register("BANKBARODA", "4668", 2.0, 2925, "NSE", "NFO");
        register("CANBK", "10794", 1.0, 6750, "NSE", "NFO");
        register("AUBANK", "21238", 5.0, 1000, "NSE", "NFO");
        register("ASHOKLEY", "212", 2.0, 5000, "NSE", "NFO");
        register("BHARATFORG", "422", 10.0, 500, "NSE", "NFO");
        register("AMBUJACEM", "1270", 5.0, 900, "NSE", "NFO");
        register("JINDALSTEL", "6733", 10.0, 625, "NSE", "NFO");
        register("NMDC", "15332", 2.5, 4500, "NSE", "NFO");
        register("NATIONALUM", "6364", 2.0, 3750, "NSE", "NFO");
        register("VEDL", "3063", 5.0, 1150, "NSE", "NFO");
        register("GAIL", "4717", 2.0, 4650, "NSE", "NFO");
        register("PETRONET", "11351", 5.0, 1500, "NSE", "NFO");
        register("HINDPETRO", "1406", 5.0, 2025, "NSE", "NFO");
        register("BIOCON", "11373", 5.0, 2500, "NSE", "NFO");
        register("ALKEM", "11703", 50.0, 125, "NSE", "NFO");
        register("AUROPHARMA", "275", 10.0, 550, "NSE", "NFO");
        register("LUPIN", "10440", 20.0, 425, "NSE", "NFO");
        register("GLENMARK", "7406", 10.0, 450, "NSE", "NFO");
        register("ZYDUSLIFE", "4144", 10.0, 900, "NSE", "NFO");
        register("COFORGE", "11543", 50.0, 150, "NSE", "NFO");
        register("PERSISTENT", "18365", 50.0, 100, "NSE", "NFO");
        register("LTM", "17818", 50.0, 150, "NSE", "NFO");
        register("MPHASIS", "4503", 20.0, 275, "NSE", "NFO");
        register("MPASIS", "4503", 20.0, 275, "NSE", "NFO");
        register("OFSS", "10738", 100.0, 100, "NSE", "NFO");
        register("TATACOMM", "3426", 20.0, 500, "NSE", "NFO");
        register("NAUKRI", "13751", 50.0, 125, "NSE", "NFO");
        register("JUBLFOOD", "18096", 5.0, 1250, "NSE", "NFO");
        register("TATACONSUM", "3432", 10.0, 900, "NSE", "NFO");
        register("COLPAL", "15141", 20.0, 300, "NSE", "NFO");
        register("MARICO", "4067", 5.0, 1200, "NSE", "NFO");
        register("UNITDSPR", "10447", 10.0, 700, "NSE", "NFO");
        register("ABCAPITAL", "21614", 2.0, 3100, "NSE", "NFO");
        register("LICHSGFIN", "1997", 5.0, 1000, "NSE", "NFO");
        register("SHRIRAMFIN", "4306", 20.0, 300, "NSE", "NFO");
        register("POONAWALLA", "11654", 5.0, 1250, "NSE", "NFO");
        register("CONCOR", "4749", 10.0, 1000, "NSE", "NFO");
        register("INDIGO", "11195", 20.0, 150, "NSE", "NFO");
        register("GMRAIRPORT", "13528", 1.0, 7000, "NSE", "NFO");
        register("EXIDEIND", "676", 5.0, 1200, "NSE", "NFO");
        register("CUMMINSIND", "1901", 20.0, 300, "NSE", "NFO");
        register("ASTRAL", "14418", 20.0, 375, "NSE", "NFO");
        register("SUPREMEIND", "3363", 50.0, 125, "NSE", "NFO");
        register("PIIND", "24184", 50.0, 125, "NSE", "NFO");
        register("UPL", "11287", 5.0, 1300, "NSE", "NFO");
        register("COROMANDEL", "739", 10.0, 700, "NSE", "NFO");
        register("DIXON", "21690", 100.0, 100, "NSE", "NFO");
        register("SYNGENE", "10243", 5.0, 1000, "NSE", "NFO");
        register("SUNTV", "13404", 5.0, 1500, "NSE", "NFO");
        register("PVRINOX", "13147", 10.0, 450, "NSE", "NFO");
        register("MOTHERSON", "4204", 1.0, 6200, "NSE", "NFO");
        register("TATAELXSI", "3417", 50.0, 100, "NSE", "NFO");
        register("TATAPOWER", "3440", 2.5, 2000, "NSE", "NFO");
        register("JSWENERGY", "17869", 5.0, 1000, "NSE", "NFO");
        register("SUZLON", "12018", 1.0, 7000, "NSE", "NFO");
        register("RVNL", "17870", 5.0, 2500, "NSE", "NFO");
        register("IRCTC", "13611", 5.0, 875, "NSE", "NFO");
        register("IRFC", "18154", 2.0, 3500, "NSE", "NFO");
        register("IREDA", "22019", 2.0, 3000, "NSE", "NFO");
        register("NHPC", "17387", 1.0, 6000, "NSE", "NFO");
        register("HUDCO", "21669", 2.0, 3000, "NSE", "NFO");
        register("OIL", "17465", 5.0, 1400, "NSE", "NFO");
        register("CGPOWER", "7083", 5.0, 1100, "NSE", "NFO");
        register("COCHINSHIP", "18150", 10.0, 700, "NSE", "NFO");
        register("MAZDOCK", "20786", 20.0, 250, "NSE", "NFO");
        register("BDL", "21644", 10.0, 400, "NSE", "NFO");
        register("PATANJALI", "21406", 10.0, 500, "NSE", "NFO");
        register("PRESTIGE", "20396", 10.0, 600, "NSE", "NFO");
        register("LODHA", "2245", 10.0, 500, "NSE", "NFO");
        register("PHOENIXLTD", "17463", 10.0, 350, "NSE", "NFO");
        register("PBFINTECH", "20688", 10.0, 350, "NSE", "NFO");
        register("FEDERALBNK", "1023", 2.0, 5000, "NSE", "NFO");
        register("YESBANK", "11915", 0.5, 10000, "NSE", "NFO");
        register("IDEA", "14366", 0.25, 40000, "NSE", "NFO");
    }

    private static void register(
            String symbol,
            String token,
            double strikeStep,
            int lotSize,
            String exchange,
            String segment) {
        INSTRUMENTS.put(
                symbol.toUpperCase(),
                new InstrumentInfo(
                        symbol.toUpperCase(),
                        token,
                        BigDecimal.valueOf(strikeStep),
                        lotSize,
                        exchange,
                        segment));
    }

    private StockFnoRegistry() {}

    /** Returns immutable list of default subscribed symbols (Indices + Top 10 Champion Stocks). */
    public static List<String> getAllSubscribedSymbols() {
        return List.of(
                "NIFTY50",
                "BSE",
                "LAURUSLABS",
                "SAIL",
                "POLYCAB",
                "ADANIENSOL",
                "MCX",
                "ADANIGREEN",
                "TORNTPHARM",
                "BHEL",
                "HINDALCO");
    }

    public static Map<String, InstrumentInfo> getAllInstruments() {
        return Collections.unmodifiableMap(INSTRUMENTS);
    }

    public static InstrumentInfo get(String symbol) {
        if (symbol == null) return null;
        return INSTRUMENTS.get(symbol.toUpperCase().trim());
    }

    /** Returns true if symbol is an Index underlying (NIFTY, SENSEX, BANKNIFTY, etc.). */
    public static boolean isIndex(String symbol) {
        if (symbol == null) return false;
        String s = symbol.toUpperCase().replace(" ", "");
        return s.contains("NIFTY") || s.contains("SENSEX") || s.contains("BSESN");
    }

    /** Resolves trading exchange ("BSE" for SENSEX, "NSE" for all others). */
    public static String getExchange(String symbol) {
        if (symbol != null) {
            InstrumentInfo info = INSTRUMENTS.get(symbol.toUpperCase().trim());
            if (info != null) {
                return info.exchange();
            }
            if (symbol.toUpperCase().contains("SENSEX") || symbol.toUpperCase().contains("BSESN")) {
                return "BSE";
            }
        }
        return "NSE";
    }

    /** Resolves derivative segment ("BFO" for SENSEX, "NFO" for NSE derivatives). */
    public static String getSegment(String symbol) {
        if (symbol != null) {
            InstrumentInfo info = INSTRUMENTS.get(symbol.toUpperCase().trim());
            if (info != null) {
                return info.segment();
            }
            if (symbol.toUpperCase().contains("SENSEX") || symbol.toUpperCase().contains("BSESN")) {
                return "BFO";
            }
        }
        return "NFO";
    }

    /** Resolves strike interval/step for a symbol. Defaults to price-tier heuristic if unlisted. */
    public static BigDecimal getStrikeStep(String symbol, BigDecimal spotPrice) {
        if (symbol != null) {
            InstrumentInfo info = INSTRUMENTS.get(symbol.toUpperCase().trim());
            if (info != null) {
                return info.strikeStep();
            }
        }
        if (spotPrice == null || spotPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.valueOf(50);
        }
        double price = spotPrice.doubleValue();
        if (price >= 10000.0) return BigDecimal.valueOf(100.0);
        if (price >= 5000.0) return BigDecimal.valueOf(100.0);
        if (price >= 2000.0) return BigDecimal.valueOf(50.0);
        if (price >= 1000.0) return BigDecimal.valueOf(20.0);
        if (price >= 500.0) return BigDecimal.valueOf(10.0);
        if (price >= 200.0) return BigDecimal.valueOf(5.0);
        return BigDecimal.valueOf(2.5);
    }

    /** Calculates the nearest At-The-Money (ATM) strike price based on symbol and spot price. */
    public static BigDecimal calculateAtmStrike(String symbol, BigDecimal spotPrice) {
        if (spotPrice == null || spotPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal step = getStrikeStep(symbol, spotPrice);
        return spotPrice.divide(step, 0, RoundingMode.HALF_UP).multiply(step);
    }

    /** Calculates OTM strike for hedging (Credit Spread leg). */
    public static BigDecimal calculateOtmStrike(
            String symbol, BigDecimal atmStrike, String optionType, int offsetSteps) {
        if (atmStrike == null || atmStrike.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal step = getStrikeStep(symbol, atmStrike);
        BigDecimal offset = step.multiply(BigDecimal.valueOf(Math.max(1, offsetSteps)));
        if ("CE".equalsIgnoreCase(optionType)) {
            return atmStrike.add(offset);
        } else {
            return atmStrike.subtract(offset).max(step);
        }
    }

    /** Resolves standard lot size for a symbol. Defaults to 1 if unknown. */
    public static int getLotSize(String symbol) {
        if (symbol != null) {
            InstrumentInfo info = INSTRUMENTS.get(symbol.toUpperCase().trim());
            if (info != null) {
                return info.lotSize();
            }
        }
        return 1;
    }

    /** Resolves instrument token for a symbol. Returns null if unknown. */
    public static String getToken(String symbol) {
        if (symbol != null) {
            InstrumentInfo info = INSTRUMENTS.get(symbol.toUpperCase().trim());
            if (info != null) {
                return info.token();
            }
        }
        return null;
    }

    /**
     * Calculates the target weekly expiry date (Friday for SENSEX, Thursday for NIFTY/BANKNIFTY).
     * If days to expiry <= minDteThreshold (e.g. 1 or 2 days), rolls to the next week.
     */
    public static LocalDate calculateWeeklyTargetExpiry(
            String symbol, LocalDate entryDate, int minDteThreshold) {
        if (entryDate == null) {
            entryDate = LocalDate.now(IST);
        }
        DayOfWeek expiryDay =
                (symbol != null
                                && (symbol.toUpperCase().contains("SENSEX")
                                        || symbol.toUpperCase().contains("BSESN")))
                        ? DayOfWeek.FRIDAY
                        : DayOfWeek.THURSDAY;

        LocalDate currentWeekExpiry = entryDate.with(TemporalAdjusters.nextOrSame(expiryDay));
        long daysRemaining = ChronoUnit.DAYS.between(entryDate, currentWeekExpiry);

        if (daysRemaining <= Math.max(0, minDteThreshold)) {
            return currentWeekExpiry.plusWeeks(1);
        }
        return currentWeekExpiry;
    }

    public static LocalDate calculateWeeklyTargetExpiry(LocalDate entryDate, int minDteThreshold) {
        return calculateWeeklyTargetExpiry("NIFTY", entryDate, minDteThreshold);
    }

    /**
     * Calculates monthly target expiry date (last Friday for SENSEX, last Thursday for
     * NIFTY/BANKNIFTY/Stocks). If days to expiry < minDteThreshold, rolls to next month.
     */
    public static LocalDate calculateMonthlyTargetExpiry(
            String symbol, LocalDate entryDate, int minDteThreshold) {
        if (entryDate == null) {
            entryDate = LocalDate.now(IST);
        }
        DayOfWeek expiryDay =
                (symbol != null
                                && (symbol.toUpperCase().contains("SENSEX")
                                        || symbol.toUpperCase().contains("BSESN")))
                        ? DayOfWeek.FRIDAY
                        : DayOfWeek.THURSDAY;

        LocalDate currentMonthExpiry = getLastDayOfWeekOfMonth(entryDate, expiryDay);
        long daysRemaining = ChronoUnit.DAYS.between(entryDate, currentMonthExpiry);

        if (daysRemaining < Math.max(1, minDteThreshold)) {
            LocalDate nextMonth = entryDate.plusMonths(1);
            return getLastDayOfWeekOfMonth(nextMonth, expiryDay);
        }
        return currentMonthExpiry;
    }

    public static LocalDate calculateTargetExpiry(LocalDate entryDate, int minDteThreshold) {
        return calculateMonthlyTargetExpiry("NIFTY", entryDate, minDteThreshold);
    }

    /** Resolves target expiry date respecting symbol type and preferWeekly flag. */
    public static LocalDate calculateTargetExpiry(
            String symbol, LocalDate entryDate, boolean preferWeekly, int minDteThreshold) {
        boolean index = isIndex(symbol);
        if (index && preferWeekly) {
            return calculateWeeklyTargetExpiry(symbol, entryDate, Math.min(2, minDteThreshold));
        }
        return calculateMonthlyTargetExpiry(symbol, entryDate, minDteThreshold);
    }

    /** Finds the last given DayOfWeek of the date's month. */
    public static LocalDate getLastDayOfWeekOfMonth(LocalDate date, DayOfWeek dayOfWeek) {
        LocalDate lastDay = date.with(TemporalAdjusters.lastDayOfMonth());
        while (lastDay.getDayOfWeek() != dayOfWeek) {
            lastDay = lastDay.minusDays(1);
        }
        return lastDay;
    }

    public static LocalDate getLastThursdayOfMonth(LocalDate date) {
        return getLastDayOfWeekOfMonth(date, DayOfWeek.THURSDAY);
    }

    /**
     * Formats the official Shoonya / NSE / BSE derivative trading symbol.
     *
     * <p>Examples: Monthly: NIFTY26OCT25000CE, BANKNIFTY26OCT52000PE, BSE26OCT2400CE,
     * SENSEX26OCT80000CE Weekly: NIFTY08OCT26C25000 / NIFTY26O0825000CE
     */
    public static String formatTradingSymbol(
            String symbol,
            LocalDate expiryDate,
            BigDecimal strikePrice,
            String optionType,
            boolean isWeekly) {
        String cleanUnderlying = symbol != null ? symbol.toUpperCase().replace(" ", "") : "NIFTY";
        if (cleanUnderlying.equals("NIFTY50") || cleanUnderlying.equals("NIFTY")) {
            cleanUnderlying = "NIFTY";
        } else if (cleanUnderlying.equals("BANKNIFTY") || cleanUnderlying.equals("BANK NIFTY")) {
            cleanUnderlying = "BANKNIFTY";
        } else if (cleanUnderlying.equals("BSESN") || cleanUnderlying.equals("SENSEX")) {
            cleanUnderlying = "SENSEX";
        }

        if (expiryDate == null) {
            expiryDate = LocalDate.now(IST);
        }

        String yearStr = String.valueOf(expiryDate.getYear()).substring(2);
        String monthStr = expiryDate.getMonth().name().substring(0, 3).toUpperCase();
        String typeStr = optionType != null ? optionType.toUpperCase() : "CE";
        long strikeVal = strikePrice != null ? strikePrice.longValue() : 0L;

        if (!isWeekly) {
            // Monthly contract format: <UNDERLYING><YY><MMM><STRIKE><CE/PE>
            return String.format(
                    "%s%s%s%d%s", cleanUnderlying, yearStr, monthStr, strikeVal, typeStr);
        } else {
            // Weekly contract format: <UNDERLYING><DD><MMM><YY><C/P><STRIKE>
            return String.format(
                    "%s%02d%s%s%d%s",
                    cleanUnderlying,
                    expiryDate.getDayOfMonth(),
                    monthStr,
                    yearStr,
                    strikeVal,
                    typeStr);
        }
    }

    /**
     * Computes an accurate theoretical option premium with intrinsic and extrinsic time value for
     * simulation, paper testing, or fallback when quotes are temporarily unavailable.
     */
    public static BigDecimal estimateTheoreticalPremium(
            String symbol,
            BigDecimal spotPrice,
            BigDecimal strikePrice,
            String optionType,
            double dteDays) {
        if (spotPrice == null || spotPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.valueOf(50.0);
        }
        if (strikePrice == null || strikePrice.compareTo(BigDecimal.ZERO) <= 0) {
            strikePrice = spotPrice;
        }

        double spot = spotPrice.doubleValue();
        double strike = strikePrice.doubleValue();
        double dte = Math.max(0.2, dteDays);
        boolean isCall = !"PE".equalsIgnoreCase(optionType);

        // Intrinsic value
        double intrinsic = isCall ? Math.max(0.0, spot - strike) : Math.max(0.0, strike - spot);

        // Base annual implied volatility estimate
        double iv = isIndex(symbol) ? 0.135 : 0.28;
        if ("SENSEX".equalsIgnoreCase(symbol)) iv = 0.145;
        if ("BANKNIFTY".equalsIgnoreCase(symbol) || "BANK NIFTY".equalsIgnoreCase(symbol))
            iv = 0.165;

        // Black-Scholes ATM time value approximation: ~ 0.4 * Spot * IV * sqrt(T)
        double timeFraction = dte / 365.0;
        double atmExtrinsic = 0.40 * spot * iv * Math.sqrt(timeFraction);

        // Moneyness exponential decay factor for OTM options
        double moneyness = Math.abs(spot - strike) / spot;
        double moneynessDecay =
                Math.exp(-0.5 * Math.pow(moneyness / (iv * Math.sqrt(timeFraction)), 2.0));
        double extrinsic = Math.max(1.0, atmExtrinsic * moneynessDecay);

        double totalPremium = intrinsic + extrinsic;
        return BigDecimal.valueOf(totalPremium).setScale(2, RoundingMode.HALF_UP);
    }
}
