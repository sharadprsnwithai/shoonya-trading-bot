package com.tradingbot.util;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry maintaining Major Commodity instruments (Crude Oil, Gold, Silver, Copper, Natural Gas),
 * lot sizes, tick sizes, strike intervals, and Yahoo / Broker symbol mappings.
 *
 * <p>Note on {@code unitMultiplier}: MCX contracts are quoted natively in INR per standard unit
 * (INR per 10 g for gold, INR per kg for silver and copper, INR per barrel for crude), so PnL =
 * price delta x quantity and the multiplier is {@code 1.0} for all MCX instruments. USD-conversion
 * factors (e.g. 32.15075 troy oz per kg) belong to Yahoo/USD-denominated data pipelines only —
 * applying them to MCX prices mis-scales PnL.
 */
public final class CommodityRegistry {

    public record CommodityMetadata(
            String symbol,
            String name,
            String yahooTicker,
            String exchange,
            int lotSize,
            BigDecimal tickSize,
            BigDecimal strikeStep,
            double unitMultiplier) {

        public CommodityMetadata(
                String symbol,
                String name,
                String yahooTicker,
                String exchange,
                int lotSize,
                BigDecimal tickSize,
                BigDecimal strikeStep) {
            this(symbol, name, yahooTicker, exchange, lotSize, tickSize, strikeStep, 1.0);
        }
    }

    private static final Map<String, CommodityMetadata> COMMODITIES = new LinkedHashMap<>();

    static {
        register(
                new CommodityMetadata(
                        "CRUDEOIL",
                        "Crude Oil Futures",
                        "CL=F",
                        "MCX",
                        100,
                        new BigDecimal("1.00"),
                        new BigDecimal("50.00"),
                        1.0)); // 1 barrel per lot unit -> 100 barrels per lot
        register(
                new CommodityMetadata(
                        "CRUDEOILM",
                        "Crude Oil Mini Futures",
                        "CL=F",
                        "MCX",
                        10,
                        new BigDecimal("1.00"),
                        new BigDecimal("50.00"),
                        1.0)); // 10 barrels per lot
        register(
                new CommodityMetadata(
                        "GOLD",
                        "Gold Futures",
                        "GC=F",
                        "MCX",
                        100,
                        new BigDecimal("1.00"),
                        new BigDecimal("100.00"),
                        1.0)); // INR per 10g quoted natively on MCX
        register(
                new CommodityMetadata(
                        "GOLDM",
                        "Gold Mini Futures",
                        "GC=F",
                        "MCX",
                        10,
                        new BigDecimal("1.00"),
                        new BigDecimal("100.00"),
                        1.0)); // INR per 10g quoted natively on MCX
        register(
                new CommodityMetadata(
                        "SILVER",
                        "Silver Futures",
                        "SI=F",
                        "MCX",
                        30,
                        new BigDecimal("1.00"),
                        new BigDecimal("500.00"),
                        1.0)); // INR per kg quoted natively on MCX
        register(
                new CommodityMetadata(
                        "SILVERM",
                        "Silver Mini Futures",
                        "SI=F",
                        "MCX",
                        5,
                        new BigDecimal("1.00"),
                        new BigDecimal("500.00"),
                        1.0)); // INR per kg quoted natively on MCX
        register(
                new CommodityMetadata(
                        "COPPER",
                        "Copper Futures",
                        "HG=F",
                        "MCX",
                        2500,
                        new BigDecimal("0.05"),
                        new BigDecimal("5.00"),
                        1.0)); // INR per kg quoted natively on MCX
        register(
                new CommodityMetadata(
                        "NATURALGAS",
                        "Natural Gas Futures",
                        "NG=F",
                        "MCX",
                        1250,
                        new BigDecimal("0.10"),
                        new BigDecimal("5.00"),
                        1.0)); // 1 MMBtu per lot unit -> 1250 MMBtu per lot
    }

    private static void register(CommodityMetadata meta) {
        COMMODITIES.put(meta.symbol(), meta);
    }

    private CommodityRegistry() {}

    public static String toMiniSymbol(String symbol) {
        if (symbol == null) return null;
        String clean = symbol.trim().toUpperCase();
        if ("GOLD".equals(clean)) return "GOLDM";
        if ("SILVER".equals(clean)) return "SILVERM";
        if ("CRUDEOIL".equals(clean) || "CRUDE".equals(clean)) return "CRUDEOILM";
        return clean;
    }

    public static boolean isCommodity(String symbol) {
        if (symbol == null) return false;
        String clean = symbol.trim().toUpperCase();
        return COMMODITIES.containsKey(clean)
                || "CRUDE".equals(clean)
                || "CRUDE OIL".equals(clean)
                || "CRUDEOILM".equals(clean)
                || "GOLDM".equals(clean)
                || "SILVERM".equals(clean);
    }

    public static CommodityMetadata getMetadata(String symbol) {
        if (symbol == null) return null;
        String clean = symbol.trim().toUpperCase();
        if ("CRUDE".equals(clean) || "CRUDE OIL".equals(clean)) {
            return COMMODITIES.get("CRUDEOIL");
        }
        return COMMODITIES.get(clean);
    }

    public static double getUnitMultiplier(String symbol) {
        CommodityMetadata meta = getMetadata(symbol);
        return (meta != null) ? meta.unitMultiplier() : 1.0;
    }

    public static Map<String, CommodityMetadata> getAllCommodities() {
        return Collections.unmodifiableMap(COMMODITIES);
    }

    public static List<String> getAllSymbols() {
        return List.copyOf(COMMODITIES.keySet());
    }
}
