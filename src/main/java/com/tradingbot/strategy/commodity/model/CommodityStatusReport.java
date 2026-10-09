package com.tradingbot.strategy.commodity.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Encapsulates the overall state and diagnostics of the Commodity VWAP strategy. */
public record CommodityStatusReport(
        Instant timestamp,
        boolean enabled,
        int totalSymbolsTracked,
        int activeTradesCount,
        Map<String, CommoditySetupSummary> setups,
        List<CommodityTradePosition> closedTrades) {

    public record CommoditySetupSummary(
            String symbol,
            CommodityBias bias,
            Double pcr,
            CommoditySetupState state,
            Double triggerHigh,
            Double triggerLow,
            Double vwap,
            int tradesToday,
            CommodityTradePosition activePosition) {}
}
