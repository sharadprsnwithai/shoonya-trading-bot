package com.tradingbot.bus;

import com.tradingbot.strategy.TradeSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Hot multicast reactive event bus for trading signals. Uses a bounded multicast buffer so slow
 * consumers get back-pressure instead of silent drops, and an always-on internal drain subscriber
 * so publishing never fails with FAIL_ZERO_SUBSCRIBER in paper-only mode (H11).
 */
@Component
public class ReactiveSignalEventBus implements SignalPublisher, SignalStreamProvider {

    private static final Logger log = LoggerFactory.getLogger(ReactiveSignalEventBus.class);
    public static final int BUFFER_CAPACITY = 1024;

    private final Sinks.Many<TradeSignal> sink;
    private final Flux<TradeSignal> signalFlux;
    // H11: serializes emissions so tryEmitNext never races another thread. A pure spin on
    // FAIL_NON_SERIALIZED starves when publishing threads >= cores (observed drops under
    // 10-thread test load); a parking lock makes waiters yield the CPU instead.
    private final java.util.concurrent.locks.ReentrantLock emitLock =
            new java.util.concurrent.locks.ReentrantLock();

    public ReactiveSignalEventBus() {
        // H11: directBestEffort() dropped signals for slow/absent subscribers. A bounded
        // multicast buffer instead fails the publish (→ caller rolls back) only on genuine
        // overflow, and never silently loses a signal.
        this.sink = Sinks.many().multicast().onBackpressureBuffer(BUFFER_CAPACITY, false);
        // H11: always-on internal drain — keeps the sink from returning FAIL_ZERO_SUBSCRIBER
        // when no external broker consumer is wired (paper-only / tests).
        this.sink
                .asFlux()
                .subscribe(
                        s ->
                                log.debug(
                                        "[SIGNAL-BUS] Drained (no external consumer): {} {} {}",
                                        s.action(),
                                        s.underlyingSymbol(),
                                        s.tradingSymbol()),
                        e -> log.error("[SIGNAL-BUS] Internal drain error: {}", e.getMessage()),
                        () -> log.info("[SIGNAL-BUS] Internal drain terminated."));
        this.signalFlux = this.sink.asFlux().share();
    }

    @Override
    public boolean publish(TradeSignal signal) {
        if (signal == null || !signal.isActionable()) {
            return false;
        }

        Sinks.EmitResult result;
        emitLock.lock();
        try {
            result = sink.tryEmitNext(signal);
        } finally {
            emitLock.unlock();
        }

        if (result.isSuccess()) {
            log.info(
                    "[SIGNAL-BUS] Emitted signal: {} | Strategy: {} | {} {} @ {}",
                    signal.signalId(),
                    signal.strategyId(),
                    signal.action(),
                    signal.tradingSymbol(),
                    signal.price());
            return true;
        }

        // Only reachable as re-entrant publish from inside a subscriber callback (same thread
        // still mid-emission — spinning cannot resolve it) or genuine overflow/cancellation.
        log.error(
                "[SIGNAL-BUS] Failed to emit signal {}: {} (publishing caller must roll back)",
                signal.signalId(),
                result);
        return false;
    }

    @Override
    public Flux<TradeSignal> getSignalStream() {
        return signalFlux;
    }
}
