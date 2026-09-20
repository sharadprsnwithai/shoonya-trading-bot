package com.tradingbot.strategy.rsihighway.model;

/** Price action confirmation patterns supported by the RSI Highway Strategy. */
public enum PriceActionPattern {
    BULLISH_ENGULFING("Bullish Engulfing"),
    HAMMER("Hammer / Pin Bar"),
    MOMENTUM_EXPANSION("Momentum Expansion"),
    HORIZONTAL_BREAKOUT("Horizontal Breakout"),
    INSIDE_BAR_BREAKOUT("Inside Bar Breakout");

    private final String displayName;

    PriceActionPattern(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
