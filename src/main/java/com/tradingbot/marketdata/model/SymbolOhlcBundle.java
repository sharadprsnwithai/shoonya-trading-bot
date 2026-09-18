package com.tradingbot.marketdata.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tradingbot.model.Candle;
import java.util.Collections;
import java.util.List;

/** Holds Daily, Weekly, and Monthly historical OHLC candle series for a specific symbol. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SymbolOhlcBundle(
        List<Candle> daily,
        List<Candle> weekly,
        List<Candle> monthly) {

    public SymbolOhlcBundle {
        daily = daily != null ? daily : Collections.emptyList();
        weekly = weekly != null ? weekly : Collections.emptyList();
        monthly = monthly != null ? monthly : Collections.emptyList();
    }
}
