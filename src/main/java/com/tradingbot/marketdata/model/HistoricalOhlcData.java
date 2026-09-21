package com.tradingbot.marketdata.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/** Consolidated JSON persistence structure for all historical OHLC candles across all symbols. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HistoricalOhlcData(
        Instant lastUpdated, int symbolCount, Map<String, SymbolOhlcBundle> symbols) {

    public HistoricalOhlcData {
        lastUpdated = lastUpdated != null ? lastUpdated : Instant.now();
        symbols = symbols != null ? symbols : Collections.emptyMap();
        symbolCount = symbols.size();
    }
}
