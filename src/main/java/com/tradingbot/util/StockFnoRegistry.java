package com.tradingbot.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
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
        register("NIFTY50", "10576", 50.0, 65, "NSE", "NFO");
        register("NIFTY", "10576", 50.0, 65, "NSE", "NFO");
        register("BANKNIFTY", "26009", 100.0, 30, "NSE", "NFO");
        register("BANK NIFTY", "26009", 100.0, 30, "NSE", "NFO");
        register("SENSEX", "1", 100.0, 20, "BSE", "BFO");
        register("BSESN", "1", 100.0, 20, "BSE", "BFO");
        register("FINNIFTY", "26037", 50.0, 65, "NSE", "NFO");
        register("MIDCPNIFTY", "26074", 25.0, 120, "NSE", "NFO");

        // Top 10 Champion High-Beta Stocks (Ranked by Profit Factor & Positive Expectancy)
        register("BSE", "19585", 50.0, 250, "NSE", "NFO");
        register("LAURUSLABS", "19234", 5.0, 1100, "NSE", "NFO");
        register("SAIL", "2963", 2.5, 4700, "NSE", "NFO");
        register("POLYCAB", "9590", 50.0, 125, "NSE", "NFO");
        register("ADANIENSOL", "1023", 20.0, 675, "NSE", "NFO");
        register("MCX", "31181", 50.0, 125, "NSE", "NFO");
        register("ADANIGREEN", "3563", 20.0, 500, "NSE", "NFO");
        register("TORNTPHARM", "3518", 50.0, 250, "NSE", "NFO");
        register("BHEL", "438", 5.0, 2625, "NSE", "NFO");
        register("HINDALCO", "1363", 10.0, 1400, "NSE", "NFO");
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
            return symbol.toUpperCase().trim();
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
            entryDate = LocalDate.now();
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
            entryDate = LocalDate.now();
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
            expiryDate = LocalDate.now();
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
