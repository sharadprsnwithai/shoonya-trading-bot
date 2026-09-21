package com.tradingbot.util;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry maintaining Major Commodity instruments (Crude Oil, Gold, Silver, Copper, Natural Gas),
 * lot sizes, tick sizes, strike intervals, and Yahoo / Broker symbol mappings.
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
                        "GOLD",
                        "Gold Futures",
                        "GC=F",
                        "MCX",
                        100,
                        new BigDecimal("1.00"),
                        new BigDecimal("100.00"),
                        0.321507)); // 0.321507 troy oz per 10g unit -> 32.1507 oz (1 kg) per lot
        register(
                new CommodityMetadata(
                        "SILVER",
                        "Silver Futures",
                        "SI=F",
                        "MCX",
                        30,
                        new BigDecimal("1.00"),
                        new BigDecimal("500.00"),
                        32.15075)); // 32.15075 troy oz per kg -> 964.5225 oz (30 kg) per lot
        register(
                new CommodityMetadata(
                        "COPPER",
                        "Copper Futures",
                        "HG=F",
                        "MCX",
                        2500,
                        new BigDecimal("0.05"),
                        new BigDecimal("5.00"),
                        2.20462)); // 2.20462 lbs per kg -> 5511.55 lbs (2500 kg) per lot
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

    public static boolean isCommodity(String symbol) {
        if (symbol == null) return false;
        String clean = symbol.trim().toUpperCase();
        return COMMODITIES.containsKey(clean)
                || "CRUDE".equals(clean)
                || "CRUDE OIL".equals(clean)
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
