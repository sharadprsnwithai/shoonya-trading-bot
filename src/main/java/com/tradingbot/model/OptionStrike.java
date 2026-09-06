package com.tradingbot.model;

import java.math.BigDecimal;

/** Represents a strike price with its associated Call (CE) and Put (PE) option contracts. */
public record OptionStrike(
        BigDecimal strikePrice, boolean isAtm, OptionContract call, OptionContract put) {}
