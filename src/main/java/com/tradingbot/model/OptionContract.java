package com.tradingbot.model;

import java.math.BigDecimal;

/** Represents a single option contract (Call or Put) at a specific strike. */
public record OptionContract(
        String symbol,
        String token,
        String optionType,
        BigDecimal strikePrice,
        BigDecimal ltp,
        long openInterest,
        long volume,
        BigDecimal bidPrice,
        BigDecimal askPrice,
        BigDecimal previousClose) {}
