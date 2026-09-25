package com.tradingbot.bus;

import com.tradingbot.strategy.TradeSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Hot multicast reactive event bus for trading signals. Uses direct multicast without replay cache,
 * ensuring late subscribers only receive future signals.
 */
@Component
public class ReactiveSignalEventBus implements SignalPublisher, SignalStreamProvider {

    private static final Logger log = LoggerFactory.getLogger(ReactiveSignalEventBus.class);
    public static final int BUFFER_CAPACITY = 1024;

    private final Sinks.Many<TradeSignal> sink;
    private final Flux<TradeSignal> signalFlux;

    public ReactiveSignalEventBus() {
        this.sink = Sinks.many().multicast().directBestEffort();
        this.signalFlux = this.sink.asFlux().share();
    }

    @Override
    public boolean publish(TradeSignal signal) {
        if (signal == null || !signal.isActionable()) {
            return false;
        }

        Sinks.EmitResult result = sink.tryEmitNext(signal);
        if (result.isSuccess()) {
            log.info(
                    "[SIGNAL-BUS] Emitted signal: {} | Strategy: {} | {} {} @ {}",
                    signal.signalId(),
                    signal.strategyId(),
                    signal.action(),
                    signal.tradingSymbol(),
                    signal.price());
            return true;
        } else {
            log.error("[SIGNAL-BUS] Failed to emit signal {}: {}", signal.signalId(), result);
            return false;
        }
    }

    @Override
    public Flux<TradeSignal> getSignalStream() {
        return signalFlux;
    }
}
