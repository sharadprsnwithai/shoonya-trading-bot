package com.tradingbot.bus;

import com.tradingbot.strategy.TradeSignal;
import reactor.core.publisher.Flux;

public interface SignalStreamProvider {
    Flux<TradeSignal> getSignalStream();
}
