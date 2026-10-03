package com.tradingbot.execution.consumer;

import com.tradingbot.execution.gateway.BrokerOrderGateway;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.OrderStatus;
import com.tradingbot.model.order.OrderType;
import com.tradingbot.model.order.TransactionType;
import com.tradingbot.strategy.TradeSignal;
import java.math.BigDecimal;
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
    private final java.util.Map<String, ExecutionMode> strategyModes;
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
        this(
                consumerId,
                brokerName,
                executionMode,
                java.util.Collections.emptyMap(),
                quantityMultiplier,
                enabled,
                maxSignalAgeSeconds);
    }

    protected AbstractTradeExecutionConsumer(
            String consumerId,
            String brokerName,
            ExecutionMode executionMode,
            java.util.Map<String, ExecutionMode> strategyModes,
            double quantityMultiplier,
            boolean enabled,
            long maxSignalAgeSeconds) {
        this.consumerId = consumerId;
        this.brokerName = brokerName;
        this.executionMode = executionMode != null ? executionMode : ExecutionMode.PAPER;
        this.strategyModes =
                strategyModes != null ? strategyModes : java.util.Collections.emptyMap();
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
                // H11: EXIT signals must NEVER be dropped as stale — a delayed exit is exactly
                // the signal that has to reach the broker to flatten an open position.
                if (signal.action() != null
                        && signal.action().name().startsWith("EXIT")) {
                    log.warn(
                            "[CONSUMER:{}] Stale EXIT signal {} (Age: {}s > Max: {}s) for {} —"
                                    + " executing anyway to flatten the broker position.",
                            consumerId,
                            signal.signalId(),
                            ageSeconds,
                            maxSignalAgeSeconds,
                            signal.tradingSymbol());
                    return true;
                }
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

    public ExecutionMode resolveMode(TradeSignal signal) {
        if (signal != null
                && signal.strategyId() != null
                && strategyModes.containsKey(signal.strategyId())) {
            return strategyModes.get(signal.strategyId());
        }
        return this.executionMode;
    }

    @Override
    public java.util.Map<String, ExecutionMode> getStrategyModes() {
        return strategyModes;
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

            ExecutionMode effectiveMode = resolveMode(signal);
            if (effectiveMode == ExecutionMode.PAPER) {
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

    // --- C3: order validation, failed-fill ledger, best-effort protective SL ---

    private final java.util.Set<String> confirmedEntries =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> unconfirmedEntries =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<String, String> protectiveSlOrders =
            new java.util.concurrent.ConcurrentHashMap<>();

    protected static boolean isEntryAction(com.tradingbot.strategy.SignalAction action) {
        if (action == null) return false;
        String n = action.name();
        return n.startsWith("ENTRY") || n.equals("BUY") || n.equals("SELL");
    }

    protected static boolean isExitAction(com.tradingbot.strategy.SignalAction action) {
        return action != null && action.name().startsWith("EXIT");
    }

    protected String ledgerKey(TradeSignal signal) {
        String underlying = signal.underlyingSymbol();
        return (underlying != null && !underlying.isBlank()) ? underlying : signal.tradingSymbol();
    }

    /**
     * C3: places an order with response validation and entry-ledger tracking.
     *
     * <ul>
     *   <li>Rejected/failed ENTRY → symbol is marked {@code ENTRY_UNCONFIRMED}; later EXITs for
     *       it are suppressed (no naked reverse order at the broker).
     *   <li>Confirmed ENTRY → symbol moves to the confirmed ledger and a best-effort protective
     *       SL-M order is placed using the contract-scaled {@code brokerStopLossPrice} metadata.
     *   <li>When the gateway supports order-status polling (Zerodha), the status is polled
     *       briefly before the entry is considered confirmed.
     * </ul>
     */
    protected OrderResponse placeOrderConfirmed(
            TradeSignal signal, OrderRequest request, BrokerOrderGateway gateway) {
        OrderResponse resp;
        try {
            resp = gateway.placeOrder(request);
        } catch (Exception e) {
            resp = OrderResponse.failure(request, e.getMessage());
        }

        boolean rejected =
                resp == null || !resp.success() || resp.status() == OrderStatus.REJECTED;
        String key = ledgerKey(signal);

        if (!rejected && isEntryAction(signal.action()) && orderIdOf(resp) != null) {
            OrderStatus polled = pollOrderStatus(gateway, orderIdOf(resp));
            if (polled == OrderStatus.REJECTED || polled == OrderStatus.CANCELLED) {
                rejected = true;
                log.error(
                        "[CONSUMER:{}] Order {} later reported as {} for {}",
                        consumerId,
                        orderIdOf(resp),
                        polled,
                        key);
            }
        }

        if (rejected) {
            if (isEntryAction(signal.action())) {
                unconfirmedEntries.add(key);
                confirmedEntries.remove(key);
                log.error(
                        "[CONSUMER:{}] ENTRY_UNCONFIRMED for {} ({}): {}. Divergence between"
                                + " paper and broker — subsequent EXITs for this symbol are"
                                + " SUPPRESSED to prevent a naked reverse order.",
                        consumerId,
                        key,
                        request.symbol(),
                        resp != null ? resp.message() : "null response");
            } else {
                log.error(
                        "[CONSUMER:{}] {} order FAILED/REJECTED for {} ({}): {}. Manual"
                                + " intervention may be required.",
                        consumerId,
                        signal.action(),
                        key,
                        request.symbol(),
                        resp != null ? resp.message() : "null response");
            }
            return resp;
        }

        if (isEntryAction(signal.action())) {
            confirmedEntries.add(key);
            unconfirmedEntries.remove(key);
            placeBestEffortProtectiveStop(signal, request, gateway);
        } else if (isExitAction(signal.action())) {
            confirmedEntries.remove(key);
            String slOrderId = protectiveSlOrders.remove(key);
            if (slOrderId != null && !slOrderId.isBlank()) {
                try {
                    gateway.cancelOrder(slOrderId);
                    log.info(
                            "[CONSUMER:{}] Cancelled resting protective SL order {} for {} on exit.",
                            consumerId,
                            slOrderId,
                            key);
                } catch (Exception e) {
                    log.warn(
                            "[CONSUMER:{}] Failed to cancel protective SL order {} for {}: {}",
                            consumerId,
                            slOrderId,
                            key,
                            e.getMessage());
                }
            }
        }

        log.info(
                "[CONSUMER:{}] Order confirmed: {} {} x {} ({} {})",
                consumerId,
                signal.action(),
                request.symbol(),
                request.quantity(),
                resp != null ? resp.orderId() : "no-order-id",
                resp != null ? resp.status() : "");
        return resp;
    }

    /**
     * C3: EXIT gate — an EXIT for a symbol whose ENTRY was never confirmed at the broker is
     * suppressed (it would be a naked reverse order).
     */
    protected boolean guardExitAllowed(TradeSignal signal) {
        if (!isExitAction(signal.action())) {
            return true;
        }
        String key = ledgerKey(signal);
        if (unconfirmedEntries.contains(key)) {
            log.error(
                    "[CONSUMER:{}] EXIT for {} SUPPRESSED — the ENTRY was never confirmed at the"
                            + " broker (naked reverse order prevented).",
                    consumerId,
                    key);
            return false;
        }
        return true;
    }

    /** C3: bounded order-status poll; returns {@code null} when polling is unsupported. */
    protected OrderStatus pollOrderStatus(BrokerOrderGateway gateway, String orderId) {
        if (!gateway.supportsOrderStatusPolling()) {
            return null;
        }
        for (int attempt = 1; attempt <= 5; attempt++) {
            OrderStatus status;
            try {
                status = gateway.getOrderStatus(orderId);
            } catch (Exception e) {
                log.warn(
                        "[CONSUMER:{}] Order status poll failed for {}: {}",
                        consumerId,
                        orderId,
                        e.getMessage());
                return null;
            }
            if (status == OrderStatus.COMPLETE
                    || status == OrderStatus.REJECTED
                    || status == OrderStatus.CANCELLED) {
                return status;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        log.warn(
                "[CONSUMER:{}] Order {} still not terminal after bounded poll — treating place"
                        + " response as authoritative.",
                consumerId,
                orderId);
        return null;
    }

    /**
     * C3: best-effort broker-side protective stop after a confirmed ENTRY, using the
     * contract-scaled {@code brokerStopLossPrice} from the signal metadata (falls back to the
     * spot-based stop only for futures). Failure here degrades to the strategy's 30s exit loop.
     */
    private void placeBestEffortProtectiveStop(
            TradeSignal signal, OrderRequest entryRequest, BrokerOrderGateway gateway) {
        if (signal.metadata() == null) {
            return;
        }
        BigDecimal slPrice = parseBigDecimal(signal.metadata().get("brokerStopLossPrice"));
        if (slPrice == null || slPrice.compareTo(BigDecimal.ZERO) <= 0) {
            String instType = String.valueOf(signal.metadata().get("instrumentType"));
            if ("FUTURES".equalsIgnoreCase(instType)) {
                slPrice = signal.stopLoss();
            }
        }
        if (slPrice == null || slPrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug(
                    "[CONSUMER:{}] No contract-scaled SL available for {} — skipping protective"
                            + " order (30s exit loop remains the safety net).",
                    consumerId,
                    entryRequest.symbol());
            return;
        }

        TransactionType side =
                entryRequest.transactionType() == TransactionType.BUY
                        ? TransactionType.SELL
                        : TransactionType.BUY;
        OrderRequest slRequest =
                new OrderRequest(
                        entryRequest.symbol(),
                        entryRequest.exchange(),
                        side,
                        OrderType.SL_MKT,
                        entryRequest.productType(),
                        entryRequest.quantity(),
                        BigDecimal.ZERO,
                        slPrice,
                        signal.signalId() + "-SL");
        try {
            OrderResponse slResp = gateway.placeOrder(slRequest);
            if (slResp != null && slResp.success()) {
                String slOrderId = orderIdOf(slResp);
                if (slOrderId != null && !slOrderId.isBlank()) {
                    String key = ledgerKey(signal);
                    protectiveSlOrders.put(key, slOrderId);
                }
                log.info(
                        "[CONSUMER:{}] Protective SL placed for {} @ trigger {} (orderId={}).",
                        consumerId,
                        entryRequest.symbol(),
                        slPrice,
                        slResp.orderId());
            } else {
                log.warn(
                        "[CONSUMER:{}] Best-effort protective SL FAILED for {} @ trigger {}"
                                + " ({}). Position protected only by the 30s exit loop.",
                        consumerId,
                        entryRequest.symbol(),
                        slPrice,
                        slResp != null ? slResp.message() : "null response");
            }
        } catch (Exception e) {
            log.warn(
                    "[CONSUMER:{}] Protective SL threw for {}: {}",
                    consumerId,
                    entryRequest.symbol(),
                    e.getMessage());
        }
    }

    protected static BigDecimal parseBigDecimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal bd) return bd;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    protected static String orderIdOf(OrderResponse resp) {
        return resp != null ? resp.orderId() : null;
    }

    /** C3: symbols with a broker-confirmed entry (used for drift reconciliation). */
    public java.util.Set<String> getConfirmedEntrySymbols() {
        return java.util.Collections.unmodifiableSet(confirmedEntries);
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
