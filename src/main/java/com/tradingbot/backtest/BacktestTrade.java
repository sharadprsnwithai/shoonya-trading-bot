package com.tradingbot.backtest;

import com.tradingbot.strategy.SignalAction;
import java.math.BigDecimal;
import java.time.Instant;

/** Immutable representation of an individual simulated backtest trade. */
public record BacktestTrade(
        int tradeId,
        String date,
        String symbol,
        SignalAction action,
        Instant entryTime,
        BigDecimal entryPrice,
        Instant exitTime,
        BigDecimal exitPrice,
        double pnlPoints,
        BigDecimal pnlAmount,
        String exitReason,
        boolean isWin) {}
