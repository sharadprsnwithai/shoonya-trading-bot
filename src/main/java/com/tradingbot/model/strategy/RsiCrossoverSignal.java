package com.tradingbot.model.strategy;

/** Signals emitted by the RSI Crossover strategy. */
public enum RsiCrossoverSignal {
    NONE,
    BUY_CALL,
    BUY_PUT,
    EXIT_CALL,
    EXIT_PUT
}
