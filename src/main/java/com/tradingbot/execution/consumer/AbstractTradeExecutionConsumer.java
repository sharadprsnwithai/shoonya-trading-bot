package com.tradingbot.execution.consumer;

import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.strategy.TradeSignal;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Base abstract consumer providing thread isolation, stale signal rejection, quantity scaling, and
 * exception containment.
 */
public abstract class AbstractTradeExecutionConsumer implements TradeExecutionConsumer {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final String consumerId;
    private final String brokerName;
    private final ExecutionMode executionMode;
    private final double quantityMultiplier;
    private final boolean enabled;
    private final long maxSignalAgeSeconds;
    private Disposable subscription;

    protected AbstractTradeExecutionConsumer(
            String consumerId,
            String brokerName,
            ExecutionMode executionMode,
            double quantityMultiplier,
            boolean enabled,
            long maxSignalAgeSeconds) {
        this.consumerId = consumerId;
        this.brokerName = brokerName;
        this.executionMode = executionMode != null ? executionMode : ExecutionMode.PAPER;
        this.quantityMultiplier = quantityMultiplier > 0 ? quantityMultiplier : 1.0;
        this.enabled = enabled;
        this.maxSignalAgeSeconds = maxSignalAgeSeconds > 0 ? maxSignalAgeSeconds : 30L;
    }

    @Override
    public void start(Flux<TradeSignal> signalStream) {
        if (!enabled) {
            log.info("[CONSUMER:{}] Consumer is disabled. Skipping subscription.", consumerId);
            return;
        }

        log.info(
                "[CONSUMER:{}] Subscribing to signal stream. Broker: {} | Mode: {} | Multiplier:"
                        + " {}x | MaxAge: {}s",
                consumerId,
                brokerName,
                executionMode,
                quantityMultiplier,
                maxSignalAgeSeconds);

        this.subscription =
                signalStream
                        .publishOn(Schedulers.boundedElastic())
                        .filter(this::isSignalFreshAndActionable)
                        .doOnNext(this::processSignalSafe)
                        .doOnError(
                                err ->
                                        log.error(
                                                "[CONSUMER:{}] Uncaught stream error: {}",
                                                consumerId,
                                                err.getMessage(),
                                                err))
                        .retry()
                        .subscribe();
    }

    @Override
    public void stop() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
            log.info("[CONSUMER:{}] Disposed signal stream subscription.", consumerId);
        }
    }

    protected boolean isSignalFreshAndActionable(TradeSignal signal) {
        if (signal == null || !signal.isActionable()) {
            return false;
        }
        if (signal.timestamp() != null) {
            long ageSeconds =
                    Math.abs(Duration.between(signal.timestamp(), Instant.now()).getSeconds());
            if (ageSeconds > maxSignalAgeSeconds) {
                log.warn(
                        "[CONSUMER:{}] Dropping STALE signal {} (Age: {}s > Max: {}s) for {}",
                        consumerId,
                        signal.signalId(),
                        ageSeconds,
                        maxSignalAgeSeconds,
                        signal.tradingSymbol());
                return false;
            }
        }
        return true;
    }

    private void processSignalSafe(TradeSignal signal) {
        try {
            int targetQty = calculateQuantity(signal.baseQuantity());
            if (targetQty <= 0) {
                log.warn(
                        "[CONSUMER:{}] Computed quantity for signal {} is <= 0. Skipping order.",
                        consumerId,
                        signal.signalId());
                return;
            }

            if (executionMode == ExecutionMode.PAPER) {
                handlePaperExecution(signal, targetQty);
            } else {
                handleLiveExecution(signal, targetQty);
            }
        } catch (Exception e) {
            log.error(
                    "[CONSUMER:{}] Execution failed for signal {}: {}",
                    consumerId,
                    signal.signalId(),
                    e.getMessage(),
                    e);
        }
    }

    protected int calculateQuantity(int baseQuantity) {
        return (int) Math.round(baseQuantity * quantityMultiplier);
    }

    protected abstract void handleLiveExecution(TradeSignal signal, int quantity);

    protected abstract void handlePaperExecution(TradeSignal signal, int quantity);

    @Override
    public String getConsumerId() {
        return consumerId;
    }

    @Override
    public String getBrokerName() {
        return brokerName;
    }

    @Override
    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public double getQuantityMultiplier() {
        return quantityMultiplier;
    }
}
