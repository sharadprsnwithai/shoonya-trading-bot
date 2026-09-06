package com.tradingbot.backtest;

import java.math.BigDecimal;
import java.util.List;

/** Summary result of a strategy historical backtest run. */
public record BacktestResult(
        String strategyId,
        String symbol,
        int daysTested,
        int totalTrades,
        int winningTrades,
        int losingTrades,
        double winRatePercent,
        double totalPointsCaptured,
        BigDecimal grossProfit,
        BigDecimal grossLoss,
        BigDecimal netPnL,
        BigDecimal maxDrawdown,
        double profitFactor,
        List<BacktestTrade> trades) {}
