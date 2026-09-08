package com.tradingbot.model.strategy;

/** Snapshot of real-time stock quote metrics used for universe ranking and Telegram alerting. */
public record StockQuoteSnapshot(
        String symbol, double ltp, double prevClose, double open, double pctChange) {}
