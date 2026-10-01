package com.tradingbot.strategy.bollingerha.model;

import java.math.BigDecimal;

/** Immutable Bollinger Bands calculation result (upper, middle/SMA, lower, bandwidth). */
public record BollingerBandResult(
        BigDecimal upper, BigDecimal middle, BigDecimal lower, BigDecimal bandwidth) {}
