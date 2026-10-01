package com.tradingbot.strategy.bollingerha.model;

import java.math.BigDecimal;

/** Represents the resolved ATM Call and Put contracts for the Bollinger HA strategy. */
public record SelectedStrikes(
        BigDecimal spotPrice,
        BigDecimal atmStrike,
        String ceToken,
        String ceSymbol,
        String peToken,
        String peSymbol) {}
