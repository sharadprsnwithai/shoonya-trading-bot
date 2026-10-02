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

    /**
     * Per-strategy execution overrides (H2: used to detect duplicate LIVE routing across
     * consumers). Defaults to empty for consumers without overrides.
     */
    default java.util.Map<String, ExecutionMode> getStrategyModes() {
        return java.util.Collections.emptyMap();
    }

    double getQuantityMultiplier();

    /**
     * C3: underlying symbols with a broker-confirmed ENTRY (failed-fill ledger, used for drift
     * reconciliation). Defaults to empty for consumers that don't track entries.
     */
    default java.util.Set<String> getConfirmedEntrySymbols() {
        return java.util.Set.of();
    }

    void start(Flux<TradeSignal> signalStream);

    void stop();
}
