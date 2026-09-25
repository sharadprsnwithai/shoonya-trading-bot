package com.tradingbot.bus;

import com.tradingbot.strategy.TradeSignal;

public interface SignalPublisher {
    boolean publish(TradeSignal signal);
}
