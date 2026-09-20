package com.tradingbot.strategy.kiss.model;

import java.time.Instant;

/** Represents a full multi-timeframe technical indicator calculation snapshot for a symbol. */
public record KissSnapshot(
        String symbol,
        double currentPrice,
        boolean weeklyHaBullish,
        double haOpen,
        double haHigh,
        double haLow,
        double haClose,
        double ema55High,
        double ema55Low,
        double ema55Signal,
        boolean emaSlopeBullish,
        double macdLine,
        double macdSignal,
        double macdHist,
        boolean inBand,
        boolean isBullishSetup,
        boolean isBearishSetup,
        double suggestedSl,
        double suggestedTarget,
        Instant timestamp) {}
