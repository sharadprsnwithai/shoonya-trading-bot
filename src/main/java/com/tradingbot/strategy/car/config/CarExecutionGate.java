package com.tradingbot.strategy.car.config;

import com.tradingbot.execution.config.ExecutionProperties;
import com.tradingbot.model.execution.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Decides whether the CAR Weekly GTT routine may touch a real broker.
 *
 * <p>Every execution consumer declares a per-strategy mode under {@code
 * trading-bot.execution.consumers[n].strategy-modes.CAR_WEEKLY_GTT}. When none of the enabled
 * consumers resolves to {@link ExecutionMode#LIVE} the routine runs in paper mode and is routed to
 * the local watcher, so an unauthenticated {@code POST /api/v1/car/run-weekly} or an accidental
 * default configuration can never place a real order.
 */
@Component
public class CarExecutionGate {

    /** Strategy id used by {@code trading-bot.execution.consumers[*].strategy-modes}. */
    public static final String STRATEGY_ID = "CAR_WEEKLY_GTT";

    private static final Logger log = LoggerFactory.getLogger(CarExecutionGate.class);

    private final ExecutionProperties executionProperties;

    @Autowired
    public CarExecutionGate(ExecutionProperties executionProperties) {
        this.executionProperties = executionProperties;
    }

    /** True only when at least one enabled consumer resolves this strategy to LIVE. */
    public boolean isLive() {
        if (executionProperties == null) {
            return false;
        }
        for (ExecutionProperties.ConsumerConfig consumer : executionProperties.getConsumers()) {
            if (consumer == null || !consumer.isEnabled()) {
                continue;
            }
            ExecutionMode mode = consumer.getStrategyModes().get(STRATEGY_ID);
            if (mode == null) {
                mode = consumer.getMode();
            }
            if (mode == ExecutionMode.LIVE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Same as {@link #isLive()} but logs the resolved mode once per call site so operators can see
     * why an order did or did not reach the broker.
     */
    public boolean isLiveWithLogging() {
        boolean live = isLive();
        log.info(
                "[CAR-GATE] CAR_WEEKLY_GTT resolved to {} mode.",
                live ? "LIVE (real broker orders)" : "PAPER (local watcher only)");
        return live;
    }
}
