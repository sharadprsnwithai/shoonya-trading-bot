package com.tradingbot.model;

import java.util.List;

/** Standard REST API response wrapper for historical OHLC candle data. */
public record OhlcResponse(
        String symbol,
        String exchange,
        String token,
        String interval,
        int count,
        List<Candle> data) {
    public static OhlcResponse of(
            String symbol, String exchange, String token, String interval, List<Candle> data) {
        return new OhlcResponse(
                symbol, exchange, token, interval, data != null ? data.size() : 0, data);
    }
}
