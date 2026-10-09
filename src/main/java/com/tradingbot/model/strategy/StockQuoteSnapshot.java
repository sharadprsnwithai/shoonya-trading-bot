package com.tradingbot.model.strategy;

/** Snapshot of real-time stock quote metrics used for universe ranking and Telegram alerting. */
public record StockQuoteSnapshot(
        String symbol,
        double ltp,
        double prevClose,
        double open,
        double pctChange,
        long volume,
        double vwap,
        long openInterest,
        long prevDayOpenInterest,
        double oiPctChange) {

    public StockQuoteSnapshot(
            String symbol, double ltp, double prevClose, double open, double pctChange) {
        this(symbol, ltp, prevClose, open, pctChange, 0L, 0.0, 0L, 0L, 0.0);
    }

    public StockQuoteSnapshot(
            String symbol,
            double ltp,
            double prevClose,
            double open,
            double pctChange,
            long volume,
            double vwap,
            long openInterest,
            long prevDayOpenInterest) {
        this(
                symbol,
                ltp,
                prevClose,
                open,
                pctChange,
                volume,
                vwap,
                openInterest,
                prevDayOpenInterest,
                prevDayOpenInterest > 0
                        ? ((openInterest - prevDayOpenInterest) / (double) prevDayOpenInterest)
                                * 100.0
                        : 0.0);
    }
}
