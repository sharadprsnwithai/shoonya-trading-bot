package com.tradingbot.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Immutable OHLCV candle representation.
 *
 * @param symbol Canonical symbol (e.g. "NSE:NIFTY50", "NSE:RELIANCE")
 * @param timeframe Timeframe string (e.g. "1", "5", "15", "D")
 * @param timestamp Candle timestamp
 * @param open Open price
 * @param high High price
 * @param low Low price
 * @param close Close price
 * @param volume Traded volume
 */
public record Candle(
        String symbol,
        String timeframe,
        Instant timestamp,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume) {
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata"));

    public boolean isGreen() {
        return close.compareTo(open) > 0;
    }

    public boolean isRed() {
        return close.compareTo(open) < 0;
    }

    public static Candle of5m(
            String symbol,
            Instant timestamp,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            long volume) {
        return new Candle(symbol, "5", timestamp, open, high, low, close, volume);
    }

    public String formattedTime() {
        return FORMATTER.format(timestamp);
    }
}
