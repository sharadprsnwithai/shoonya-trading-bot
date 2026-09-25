package com.tradingbot.execution.consumer;

import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.strategy.TradeSignal;
import reactor.core.publisher.Flux;

/** Standard contract for trade execution consumers subscribing to the signal stream. */
public interface TradeExecutionConsumer {
    String getConsumerId();

    String getBrokerName();

    ExecutionMode getExecutionMode();

    boolean isEnabled();

    double getQuantityMultiplier();

    void start(Flux<TradeSignal> signalStream);

    void stop();
}
