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
 * Registry containing NSE F&O instrument specifications (Strike Intervals, Standard Lot Sizes) for
 * NIFTY 50 and 29 high-liquidity stock underlyings used in Option Buying strategies.
 */
public final class StockFnoRegistry {

    public record InstrumentInfo(String symbol, String token, BigDecimal strikeStep, int lotSize) {}

    private static final Map<String, InstrumentInfo> INSTRUMENTS = new LinkedHashMap<>();

    static {
        // NIFTY 50 Index (Continuous / Benchmark token)
        register("NIFTY50", "10576", 50.0, 65);
        register("NIFTY", "10576", 50.0, 65);

        // 29 F&O Equities with standard NSE equity tokens
        register("ABB", "13", 100.0, 125);
        register("ADANIENSOL", "1023", 20.0, 675);
        register("ADANIGREEN", "3563", 20.0, 500);
        register("ADANIPOWER", "17534", 10.0, 875);
        register("ABCAPITAL", "21614", 5.0, 3100);
        register("BSE", "19585", 50.0, 250);
        register("BHARATFORG", "422", 20.0, 500);
        register("BHEL", "438", 5.0, 2625);
        register("CGPOWER", "760", 10.0, 850);
        register("CUMMINSIND", "1901", 50.0, 200);
        register("FEDERALBNK", "1023", 2.5, 5000);
        register("GVT&D", "10999", 50.0, 250);
        register("GLENMARK", "7406", 20.0, 350);
        register("HINDALCO", "1363", 10.0, 1400);
        register("POWERINDIA", "21690", 100.0, 50);
        register("KEI", "11374", 50.0, 125);
        register("LTF", "18043", 2.5, 4462);
        register("LAURUSLABS", "19234", 5.0, 1100);
        register("MCX", "31181", 50.0, 125);
        register("NTPC", "11630", 5.0, 1500);
        register("NATIONALUM", "6364", 2.5, 3750);
        register("POLYCAB", "9590", 50.0, 125);
        register("MOTHERSON", "4204", 2.5, 3100);
        register("SHRIRAMFIN", "4306", 50.0, 150);
        register("SOLARINDS", "13426", 100.0, 50);
        register("SAIL", "2963", 2.5, 4700);
        register("TATASTEEL", "3499", 2.5, 5500);
        register("TORNTPHARM", "3518", 50.0, 250);
        register("VEDL", "3045", 10.0, 1150);

        // Additional F&O Equities for 200-stock universe
        register("IDEA", "14366", 0.5, 40000);
        register("COFORGE", "11536", 50.0, 75);
        register("AUBANK", "21238", 10.0, 1000);
        register("SHREECEM", "3103", 100.0, 25);
        register("RECLTD", "15355", 5.0, 1500);
        register("PFC", "14299", 5.0, 1300);
        register("BHARTIARTL", "317", 10.0, 475);
    }

    private static void register(String symbol, String token, double strikeStep, int lotSize) {
        INSTRUMENTS.put(
                symbol.toUpperCase(),
                new InstrumentInfo(
                        symbol.toUpperCase(), token, BigDecimal.valueOf(strikeStep), lotSize));
    }

    private StockFnoRegistry() {}

    /** Returns immutable list of all 30 subscribed symbols (NIFTY50 + 29 stocks). */
    public static List<String> getAllSubscribedSymbols() {
        return List.of(
                "NIFTY50",
                "ABB",
                "ADANIENSOL",
                "ADANIGREEN",
                "ADANIPOWER",
                "ABCAPITAL",
                "BSE",
                "BHARATFORG",
                "BHEL",
                "CGPOWER",
                "CUMMINSIND",
                "FEDERALBNK",
                "GVT&D",
                "GLENMARK",
                "HINDALCO",
                "POWERINDIA",
                "KEI",
                "LTF",
                "LAURUSLABS",
                "MCX",
                "NTPC",
                "NATIONALUM",
                "POLYCAB",
                "MOTHERSON",
                "SHRIRAMFIN",
                "SOLARINDS",
                "SAIL",
                "TATASTEEL",
                "TORNTPHARM",
                "VEDL");
    }

    public static Map<String, InstrumentInfo> getAllInstruments() {
        return Collections.unmodifiableMap(INSTRUMENTS);
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

    private static final List<String> CURATED_SYMBOLS =
            new java.util.concurrent.CopyOnWriteArrayList<>(
                    List.of(
                            "IDEA",
                            "BSE",
                            "VEDL",
                            "COFORGE",
                            "GLENMARK",
                            "AUBANK",
                            "SHREECEM",
                            "RECLTD",
                            "PFC",
                            "BHARTIARTL"));

    /**
     * Returns top trending, high-liquidity curated symbols dynamically selected on the 1st of every
     * month.
     */
    public static List<String> getCuratedSymbols() {
        return List.copyOf(CURATED_SYMBOLS);
    }

    /**
     * Updates the active curated symbols (called automatically by MonthlyBasketRebalanceScheduler
     * on the 1st of month).
     */
    public static void setCuratedSymbols(List<String> newCurated) {
        if (newCurated != null && !newCurated.isEmpty()) {
            CURATED_SYMBOLS.clear();
            CURATED_SYMBOLS.addAll(newCurated);
        }
    }

    /**
     * Calculates OTM strike for hedging (Credit Spread leg). For Call (CE): OTM strike = atmStrike
     * + (strikeStep * offsetSteps) For Put (PE): OTM strike = atmStrike - (strikeStep *
     * offsetSteps)
     */
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

    /** Resolves NSE instrument token for a symbol. Returns null if unknown. */
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
     * Calculates the target monthly expiry date implementing the ~30-40 DTE rule: - Identifies the
     * last Thursday of the current month. - If days to expiry is less than minDteThreshold (e.g. 10
     * days), rolls to the last Thursday of the next month to avoid rapid theta decay.
     *
     * @param entryDate current trade date
     * @param minDteThreshold threshold days below which we roll to next month (default 10)
     * @return target expiry date
     */
    public static LocalDate calculateTargetExpiry(LocalDate entryDate, int minDteThreshold) {
        if (entryDate == null) {
            entryDate = LocalDate.now();
        }
        LocalDate currentMonthLastThursday = getLastThursdayOfMonth(entryDate);
        long daysRemaining = ChronoUnit.DAYS.between(entryDate, currentMonthLastThursday);

        if (daysRemaining < Math.max(1, minDteThreshold)) {
            LocalDate nextMonth = entryDate.plusMonths(1);
            return getLastThursdayOfMonth(nextMonth);
        }
        return currentMonthLastThursday;
    }

    /**
     * Calculates the target weekly expiry date (every Thursday for Indices like NIFTY50). If days
     * to expiry is less than or equal to minDteThreshold (e.g. 2 days on Wed/Thu), rolls to the
     * following Thursday to avoid massive theta decay and gamma crush.
     *
     * @param entryDate current trade date
     * @param minDteThreshold threshold days below which we roll to next week's expiry
     * @return target weekly expiry date
     */
    public static LocalDate calculateWeeklyTargetExpiry(LocalDate entryDate, int minDteThreshold) {
        if (entryDate == null) {
            entryDate = LocalDate.now();
        }
        LocalDate currentWeekThursday =
                entryDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.THURSDAY));
        long daysRemaining = ChronoUnit.DAYS.between(entryDate, currentWeekThursday);

        if (daysRemaining <= Math.max(0, minDteThreshold)) {
            return currentWeekThursday.plusWeeks(1);
        }
        return currentWeekThursday;
    }

    /**
     * Resolves target expiry for a given symbol. Indices (NIFTY, NIFTY50, BANKNIFTY) use weekly
     * expiry if preferWeekly is true. Equities (BSE, VEDL, etc.) always use monthly contracts with
     * standard DTE roll.
     *
     * @param symbol trading underlying symbol
     * @param entryDate current trade date
     * @param preferWeekly whether to prefer weekly contracts for indices
     * @param minDteThreshold threshold days below which to roll to next expiry
     * @return target expiry date
     */
    public static LocalDate calculateTargetExpiry(
            String symbol, LocalDate entryDate, boolean preferWeekly, int minDteThreshold) {
        boolean isIndex =
                symbol != null
                        && (symbol.equalsIgnoreCase("NIFTY")
                                || symbol.equalsIgnoreCase("NIFTY50")
                                || symbol.equalsIgnoreCase("BANKNIFTY"));
        if (isIndex && preferWeekly) {
            return calculateWeeklyTargetExpiry(entryDate, Math.min(2, minDteThreshold));
        }
        return calculateTargetExpiry(entryDate, minDteThreshold);
    }

    /** Finds the last Thursday of the given date's month (standard NSE monthly expiry). */
    public static LocalDate getLastThursdayOfMonth(LocalDate date) {
        LocalDate lastDay = date.with(TemporalAdjusters.lastDayOfMonth());
        while (lastDay.getDayOfWeek() != DayOfWeek.THURSDAY) {
            lastDay = lastDay.minusDays(1);
        }
        return lastDay;
    }
}
