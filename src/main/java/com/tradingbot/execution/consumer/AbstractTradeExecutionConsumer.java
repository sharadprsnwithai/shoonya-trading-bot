package com.tradingbot.execution.consumer;

import com.tradingbot.execution.gateway.BrokerOrderGateway;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.OrderStatus;
import com.tradingbot.model.order.OrderType;
import com.tradingbot.model.order.ProductType;
import com.tradingbot.model.order.TransactionType;
import com.tradingbot.strategy.SignalAction;
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
                // H11: exit-family signals must NEVER be dropped as stale — a delayed exit is
                // exactly the signal that has to reach the broker to flatten an open position.
                if (isExitAction(signal.action())) {
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
        if (action == null) {
            return false;
        }
        String n = action.name();
        return n.startsWith("ENTRY") || "BUY".equals(n) || "SELL".equals(n);
    }

    protected static boolean isExitAction(com.tradingbot.strategy.SignalAction action) {
        if (action == null) {
            return false;
        }
        String n = action.name();
        // SQUARE_OFF is a full flatten: like EXIT_* it must cancel the resting protective stop,
        // clear the entry ledger and pass the unconfirmed-entry guard.
        return n.startsWith("EXIT") || action == SignalAction.SQUARE_OFF;
    }

    protected static boolean isPartialExitAction(com.tradingbot.strategy.SignalAction action) {
        if (action == null) {
            return false;
        }
        return action == SignalAction.PARTIAL_EXIT_LONG
                || action == SignalAction.PARTIAL_EXIT_SHORT;
    }

    protected String ledgerKey(TradeSignal signal) {
        String underlying = signal.underlyingSymbol();
        return underlying != null && !underlying.isBlank() ? underlying : signal.tradingSymbol();
    }

    /**
     * C3/PNL-1: places an order with response validation and entry-ledger tracking.
     *
     * <ul>
     *   <li>Rejected/failed ENTRY → symbol is marked {@code ENTRY_UNCONFIRMED}; later EXITs for it
     *       are suppressed (no naked reverse order at the broker).
     *   <li>PNL-1: an ENTRY that is still unfilled after the bounded status poll is cancelled and
     *       marked {@code ENTRY_UNCONFIRMED} — an open LIMIT order is not a position.
     *   <li>Confirmed ENTRY → the broker position is cross-checked (PNL-4), the symbol moves to the
     *       confirmed ledger and a best-effort protective SL-M order is placed using the
     *       contract-scaled {@code brokerStopLossPrice} metadata.
     *   <li>PNL-2: exit-family orders cancel the resting protective stop FIRST (verified); a failed
     *       cancel aborts the exit with the position still protected by the stop.
     *   <li>PNL-1: a failed or unfilled-and-cancelled EXIT re-places the protective stop — the
     *       position is still open and must not be left naked.
     * </ul>
     */
    protected OrderResponse placeOrderConfirmed(
            TradeSignal signal, OrderRequest request, BrokerOrderGateway gateway) {
        String key = ledgerKey(signal);
        boolean entry = isEntryAction(signal.action());
        boolean partialExit = isPartialExitAction(signal.action());
        boolean exit = isExitAction(signal.action());

        // PNL-2: the stop must be off the book before an exit-family order goes out — otherwise a
        // fast market can trigger the stop and the exit together (double execution → naked short).
        if (!entry && (exit || partialExit)) {
            if (!cancelRestingProtectiveStop(gateway, key, signal.action().name())) {
                log.error(
                        "[CONSUMER:{}] {} for {} ABORTED — the protective SL could not be cancelled"
                                + " at the broker. The position stays protected by the stop; the"
                                + " next evaluation retries the exit.",
                        consumerId,
                        signal.action(),
                        key);
                return OrderResponse.failure(
                        request, signal.action() + " aborted: protective SL cancel failed");
            }
        }

        OrderResponse resp;
        try {
            resp = gateway.placeOrder(request);
        } catch (Exception e) {
            resp = OrderResponse.failure(request, e.getMessage());
        }

        boolean rejected = resp == null || !resp.success() || resp.status() == OrderStatus.REJECTED;

        if (!rejected && entry && orderIdOf(resp) != null) {
            OrderPollOutcome polled = pollOrderOutcome(gateway, orderIdOf(resp));
            if (polled == OrderPollOutcome.REJECTED || polled == OrderPollOutcome.CANCELLED) {
                rejected = true;
                log.error(
                        "[CONSUMER:{}] Order {} later reported as {} for {}",
                        consumerId,
                        orderIdOf(resp),
                        polled,
                        key);
            } else if (polled == OrderPollOutcome.STILL_OPEN) {
                // PNL-1: an entry order that never filled is not a position. Cancel it and fail
                // closed — even when the cancel itself fails the entry stays unconfirmed, because
                // a possibly-later-filling order must not be treated as a booked position.
                rejected = true;
                cancelQuietly(gateway, orderIdOf(resp), key, "unfilled ENTRY");
                log.error(
                        "[CONSUMER:{}] ENTRY order {} for {} still unfilled after the bounded"
                                + " status poll — cancelled and marked UNCONFIRMED. If the cancel"
                                + " failed the order may still fill; the drift reconciler will"
                                + " flag any resulting position.",
                        consumerId,
                        orderIdOf(resp),
                        key);
            }
        }

        // PNL-4: cross-check the confirmed entry against the broker's actual position.
        if (!rejected && entry) {
            verifyFillAtBroker(signal, request, gateway);
        }

        if (rejected) {
            if (entry) {
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
                // PNL-1/2: the protective stop is already off the book (pre-flight cancel); the
                // position is still open — it must not be left naked.
                if (exit || partialExit) {
                    replaceProtectiveStopForOpenPosition(signal, request, gateway);
                }
            }
            return resp;
        }

        if (entry) {
            confirmedEntries.add(key);
            unconfirmedEntries.remove(key);
            placeBestEffortProtectiveStop(signal, request, gateway);
        } else if (partialExit) {
            // 1.2: the 100% stop was cancelled pre-flight (PNL-2); place the runner stop at Cost
            // SL for the lots that remain in the market.
            int remainingQty = request.quantity();
            if (signal.metadata() != null && signal.metadata().get("remainingQuantity") != null) {
                try {
                    remainingQty =
                            Integer.parseInt(
                                    String.valueOf(signal.metadata().get("remainingQuantity")));
                } catch (Exception ignore) {
                    log.warn(
                            "[CONSUMER:{}] Unparseable remainingQuantity metadata '{}' — using full quantity",
                            consumerId,
                            signal.metadata().get("remainingQuantity"),
                            ignore);
                }
            }
            if (remainingQty > 0) {
                OrderRequest runnerEntryReq =
                        new OrderRequest(
                                request.symbol(),
                                request.exchange(),
                                request.transactionType() == TransactionType.BUY
                                        ? TransactionType.SELL
                                        : TransactionType
                                                .BUY, // entry side was opposite of exit side
                                OrderType.MKT,
                                request.productType(),
                                remainingQty,
                                BigDecimal.ZERO,
                                null,
                                signal.signalId() + "-RUNNER");
                placeBestEffortProtectiveStop(signal, runnerEntryReq, gateway);
            }
        } else if (exit) {
            confirmedEntries.remove(key);
            // PNL-1: verify the exit actually completed; a rejected or never-filled exit leaves
            // the position open and must get its protective stop back.
            if (!resolveExitOutcome(signal, gateway, resp)) {
                replaceProtectiveStopForOpenPosition(signal, request, gateway);
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
     * PNL-2: cancels and forgets the resting protective stop for {@code key}. Returns {@code true}
     * when there was nothing to cancel or the broker VERIFIED the cancel; on failure the order id
     * is kept in the map so the next tick retries, and the caller must treat the stop as live.
     */
    private boolean cancelRestingProtectiveStop(
            BrokerOrderGateway gateway, String key, String reason) {
        String slOrderId = protectiveSlOrders.get(key);
        if (slOrderId == null || slOrderId.isBlank()) {
            return true;
        }
        try {
            OrderResponse resp = gateway.cancelOrder(slOrderId);
            if (resp != null && resp.success()) {
                protectiveSlOrders.remove(key, slOrderId);
                log.info(
                        "[CONSUMER:{}] Cancelled resting protective SL order {} for {} on {}.",
                        consumerId,
                        slOrderId,
                        key,
                        reason);
                return true;
            }
            log.error(
                    "[CONSUMER:{}] FAILED to cancel protective SL order {} for {} on {} ({}) —"
                            + " kept for retry; the stop is still live at the broker.",
                    consumerId,
                    slOrderId,
                    key,
                    reason,
                    resp != null ? resp.message() : "null response");
            return false;
        } catch (Exception e) {
            log.error(
                    "[CONSUMER:{}] FAILED to cancel protective SL order {} for {} on {} — kept for"
                            + " retry; the stop is still live at the broker.",
                    consumerId,
                    slOrderId,
                    key,
                    reason,
                    e);
            return false;
        }
    }

    /**
     * D3: handles signals that must never produce a position order — stop maintenance only.
     *
     * <p>{@code UPDATE_STOP_LOSS} re-places the resting protective stop at {@link
     * TradeSignal#stopLoss()} for {@code quantity} after cancelling the previous one. The position
     * itself is untouched, so callers must skip normal order placement when this returns {@code
     * true}.
     *
     * <p>The stop side is derived from the {@code positionSide} metadata (LONG/SHORT, defaulting to
     * LONG because {@code UPDATE_STOP_LOSS} carries no direction of its own).
     *
     * @return {@code true} when the signal was fully handled
     */
    protected boolean handleStopMaintenance(
            TradeSignal signal, int quantity, BrokerOrderGateway gateway) {
        if (signal.action() != SignalAction.UPDATE_STOP_LOSS) {
            return false;
        }
        String key = ledgerKey(signal);
        // PNL-2: never place a second stop while the first one may still be live.
        if (!cancelRestingProtectiveStop(gateway, key, "stop-loss update")) {
            log.error(
                    "[CONSUMER:{}] UPDATE_STOP_LOSS for {} ABORTED — the previous stop could not be"
                            + " cancelled at the broker (a second stop would risk double"
                            + " execution).",
                    consumerId,
                    key);
            return true;
        }

        int qty = quantity > 0 ? quantity : signal.baseQuantity();
        if (qty <= 0) {
            log.warn(
                    "[CONSUMER:{}] UPDATE_STOP_LOSS for {} has non-positive quantity {} — the"
                            + " stop was cancelled but not re-placed.",
                    consumerId,
                    key,
                    qty);
            return true;
        }

        String exchange = "NFO";
        if (signal.metadata() != null && signal.metadata().get("exchange") != null) {
            exchange = String.valueOf(signal.metadata().get("exchange"));
        }
        boolean shortPosition =
                signal.metadata() != null
                        && "SHORT"
                                .equalsIgnoreCase(
                                        String.valueOf(signal.metadata().get("positionSide")));
        // placeBestEffortProtectiveStop() flips the anchor side to get the stop side, so the
        // anchor carries the ENTRY side: BUY for a long position, SELL for a short one.
        OrderRequest anchor =
                new OrderRequest(
                        signal.tradingSymbol(),
                        exchange,
                        shortPosition ? TransactionType.SELL : TransactionType.BUY,
                        OrderType.MKT,
                        ProductType.MIS,
                        qty,
                        BigDecimal.ZERO,
                        null,
                        signal.signalId() + "-SL-UPDATE");
        placeBestEffortProtectiveStop(signal, anchor, gateway);
        return true;
    }

    /**
     * C3: EXIT gate — an exit-family signal (EXIT_xxx or SQUARE_OFF) for a symbol whose ENTRY was
     * never confirmed at the broker is suppressed (it would be a naked reverse order).
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

    /**
     * PNL-1: outcome of a bounded order-status poll — distinguishes an unfilled order from an
     * unpolled one.
     */
    protected enum OrderPollOutcome {
        UNSUPPORTED,
        POLL_FAILED,
        STILL_OPEN,
        COMPLETE,
        REJECTED,
        CANCELLED
    }

    /** C3/PNL-1: bounded order-status poll. */
    protected OrderPollOutcome pollOrderOutcome(BrokerOrderGateway gateway, String orderId) {
        if (!gateway.supportsOrderStatusPolling()) {
            return OrderPollOutcome.UNSUPPORTED;
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
                        e.getMessage(),
                        e);
                return OrderPollOutcome.POLL_FAILED;
            }
            if (status == OrderStatus.COMPLETE) {
                return OrderPollOutcome.COMPLETE;
            }
            if (status == OrderStatus.REJECTED) {
                return OrderPollOutcome.REJECTED;
            }
            if (status == OrderStatus.CANCELLED) {
                return OrderPollOutcome.CANCELLED;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return OrderPollOutcome.POLL_FAILED;
            }
        }
        log.warn(
                "[CONSUMER:{}] Order {} still not terminal after bounded poll — treating as"
                        + " unfilled.",
                consumerId,
                orderId);
        return OrderPollOutcome.STILL_OPEN;
    }

    /**
     * PNL-1: best-effort cancel; returns {@code true} only when the broker confirmed the cancel.
     */
    private boolean cancelQuietly(
            BrokerOrderGateway gateway, String orderId, String key, String reason) {
        try {
            OrderResponse resp = gateway.cancelOrder(orderId);
            if (resp != null && resp.success()) {
                log.info(
                        "[CONSUMER:{}] Cancelled {} order {} for {} as intended.",
                        consumerId,
                        reason,
                        orderId,
                        key);
                return true;
            }
            log.error(
                    "[CONSUMER:{}] CANCEL FAILED for {} order {} of {} ({}) — the order may"
                            + " still fill; the drift reconciler will flag it.",
                    consumerId,
                    reason,
                    orderId,
                    key,
                    resp != null ? resp.message() : "null response");
            return false;
        } catch (Exception e) {
            log.error(
                    "[CONSUMER:{}] CANCEL FAILED for {} order {} of {} — the order may still fill;"
                            + " the drift reconciler will flag it.",
                    consumerId,
                    reason,
                    orderId,
                    key,
                    e);
            return false;
        }
    }

    /**
     * PNL-1: resolves whether an exit-family order actually completed at the broker. Returns {@code
     * true} when the exit must be treated as done (filled, or not pollable — the place response is
     * authoritative on gateways without status polling).
     */
    private boolean resolveExitOutcome(
            TradeSignal signal, BrokerOrderGateway gateway, OrderResponse resp) {
        String orderId = resp != null ? resp.orderId() : null;
        if (orderId == null || orderId.isBlank() || !gateway.supportsOrderStatusPolling()) {
            return true;
        }
        OrderPollOutcome polled = pollOrderOutcome(gateway, orderId);
        if (polled == OrderPollOutcome.STILL_OPEN) {
            // The resting exit limit never filled — cancel the remainder. If the cancel itself
            // fails the order stays live and may still fill (the exit we wanted), so the caller
            // must NOT re-place a stop that would fight it.
            String key = ledgerKey(signal);
            return !cancelQuietly(gateway, orderId, key, "unfilled " + signal.action());
        }
        if (polled == OrderPollOutcome.REJECTED || polled == OrderPollOutcome.CANCELLED) {
            return false;
        }
        // COMPLETE, POLL_FAILED or UNSUPPORTED: treat the place response as authoritative.
        return true;
    }

    /**
     * PNL-1/2: the position is still open (the exit failed or was never filled) — re-place the
     * protective stop so it is never left naked. For a failed partial exit the stop must cover the
     * runner lots too ({@code remainingQuantity} metadata, PNL-6).
     */
    private void replaceProtectiveStopForOpenPosition(
            TradeSignal signal, OrderRequest failedRequest, BrokerOrderGateway gateway) {
        int openQty = failedRequest.quantity();
        if (signal.metadata() != null && signal.metadata().get("remainingQuantity") != null) {
            try {
                openQty +=
                        Integer.parseInt(
                                String.valueOf(signal.metadata().get("remainingQuantity")));
            } catch (Exception ignore) {
                log.warn(
                        "[CONSUMER:{}] Unparseable remainingQuantity metadata '{}' — re-placing the"
                                + " stop for the failed order's quantity only.",
                        consumerId,
                        signal.metadata().get("remainingQuantity"),
                        ignore);
            }
        }
        OrderRequest anchor =
                new OrderRequest(
                        failedRequest.symbol(),
                        failedRequest.exchange(),
                        failedRequest.transactionType() == TransactionType.BUY
                                ? TransactionType.SELL
                                : TransactionType.BUY,
                        OrderType.MKT,
                        failedRequest.productType(),
                        openQty,
                        BigDecimal.ZERO,
                        null,
                        signal.signalId() + "-REPAIR-SL");
        log.error(
                "[CONSUMER:{}] {} for {} did not complete — the position is still OPEN; re-placing"
                        + " the protective stop for {} units.",
                consumerId,
                signal.action(),
                ledgerKey(signal),
                openQty);
        placeBestEffortProtectiveStop(signal, anchor, gateway);
    }

    /**
     * PNL-4: after a confirmed entry, cross-checks the broker's actual position against the order
     * that was booked. Log-only — it never blocks exits; it makes fill divergence (partial fills,
     * far-off average prices, phantom confirmations) visible instead of silent.
     */
    private void verifyFillAtBroker(
            TradeSignal signal, OrderRequest request, BrokerOrderGateway gateway) {
        try {
            java.util.List<com.tradingbot.model.execution.BrokerPosition> positions =
                    gateway.getPositions();
            if (positions == null) {
                return;
            }
            String symbol = request.symbol();
            com.tradingbot.model.execution.BrokerPosition match =
                    positions.stream()
                            .filter(p -> p.quantity() != 0)
                            .filter(
                                    p ->
                                            symbol.equalsIgnoreCase(p.tradingSymbol())
                                                    || symbol.equalsIgnoreCase(p.symbol()))
                            .findFirst()
                            .orElse(null);
            if (match == null) {
                log.error(
                        "[CONSUMER:{}] Fill verification: NO open broker position for {} after a"
                                + " confirmed {} entry — the fill may have failed; the drift"
                                + " reconciler will flag it.",
                        consumerId,
                        symbol,
                        signal.action());
                return;
            }
            if (Math.abs(match.quantity()) != request.quantity()) {
                log.error(
                        "[CONSUMER:{}] PARTIAL FILL for {}: broker holds {} @ {} but the signal"
                                + " booked {} — paper/broker quantity divergence.",
                        consumerId,
                        symbol,
                        match.quantity(),
                        match.averagePrice(),
                        request.quantity());
            } else {
                log.info(
                        "[CONSUMER:{}] Fill verified for {}: {} @ avg {}.",
                        consumerId,
                        symbol,
                        match.quantity(),
                        match.averagePrice());
            }
        } catch (Exception e) {
            log.warn(
                    "[CONSUMER:{}] Fill verification failed for {}: {}",
                    consumerId,
                    request.symbol(),
                    e.getMessage());
        }
    }

    /**
     * C3: best-effort broker-side protective stop, using the contract-scaled {@code
     * brokerStopLossPrice} from the signal metadata. When that metadata is absent the stop falls
     * back to {@link TradeSignal#stopLoss()} for futures and for options (an option's stop is
     * already denominated in premium, so it needs no contract scaling). Failure here leaves the
     * position protected only by the strategy's own tick/candle exit management.
     */
    private void placeBestEffortProtectiveStop(
            TradeSignal signal, OrderRequest entryRequest, BrokerOrderGateway gateway) {
        if (signal.metadata() == null) {
            return;
        }
        boolean isOption = isOptionSignal(signal, entryRequest.symbol());
        BigDecimal slPrice = parseBigDecimal(signal.metadata().get("brokerStopLossPrice"));
        if (slPrice == null || slPrice.compareTo(BigDecimal.ZERO) <= 0) {
            String instType = String.valueOf(signal.metadata().get("instrumentType"));
            if ("FUTURES".equalsIgnoreCase(instType) || isOption) {
                slPrice = signal.stopLoss();
            }
        }
        if (slPrice == null || slPrice.compareTo(BigDecimal.ZERO) <= 0) {
            log.debug(
                    "[CONSUMER:{}] No stop price available for {} — skipping protective order"
                            + " (strategy-side exit management remains the safety net).",
                    consumerId,
                    entryRequest.symbol());
            return;
        }

        TransactionType side =
                entryRequest.transactionType() == TransactionType.BUY
                        ? TransactionType.SELL
                        : TransactionType.BUY;

        OrderType orderType;
        BigDecimal limitPrice;
        if (isOption) {
            // 1.3: Options require SL_LMT with limit price buffer (SL-M is blocked on options by
            // NSE/BSE)
            orderType = OrderType.SL_LMT;
            if (side == TransactionType.SELL) {
                // Long Option position SL exit: Sell when premium falls to slPrice. Limit buffer =
                // 10% below slPrice
                BigDecimal buffered = slPrice.multiply(BigDecimal.valueOf(0.90));
                limitPrice = roundToTick(buffered).max(BigDecimal.valueOf(0.05));
            } else {
                // Short Option position SL exit: Buy when premium rises to slPrice. Limit buffer =
                // 10% above slPrice
                BigDecimal buffered = slPrice.multiply(BigDecimal.valueOf(1.10));
                limitPrice = roundToTick(buffered);
            }
        } else {
            orderType = OrderType.SL_MKT;
            limitPrice = BigDecimal.ZERO;
        }

        OrderRequest slRequest =
                new OrderRequest(
                        entryRequest.symbol(),
                        entryRequest.exchange(),
                        side,
                        orderType,
                        entryRequest.productType(),
                        entryRequest.quantity(),
                        limitPrice,
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
                                + " ({}). Stop remains unmanaged at the broker — strategy-side"
                                + " exit management is now the only protection.",
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
                    e.getMessage(),
                    e);
        }
    }

    /**
     * Returns {@code true} when the signal targets a listed option: either the {@code
     * instrumentType} metadata says OPTION/OPTIONS, or the trading symbol carries a CE/PE suffix.
     *
     * @param symbol the contract to classify; falls back to {@link TradeSignal#tradingSymbol()}
     */
    protected static boolean isOptionSignal(TradeSignal signal, String symbol) {
        if (signal != null && signal.metadata() != null) {
            String instType = String.valueOf(signal.metadata().get("instrumentType"));
            if ("OPTION".equalsIgnoreCase(instType) || "OPTIONS".equalsIgnoreCase(instType)) {
                return true;
            }
        }
        String sym = symbol != null ? symbol : (signal != null ? signal.tradingSymbol() : null);
        return sym != null
                && (sym.endsWith("CE")
                        || sym.endsWith("PE")
                        || sym.contains(" CE")
                        || sym.contains(" PE"));
    }

    /** Convenience overload classifying {@link TradeSignal#tradingSymbol()}. */
    protected static boolean isOptionSignal(TradeSignal signal) {
        return isOptionSignal(signal, null);
    }

    protected static BigDecimal parseBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    protected static String orderIdOf(OrderResponse resp) {
        return resp != null ? resp.orderId() : null;
    }

    private static BigDecimal roundToTick(BigDecimal price) {
        if (price == null) {
            return BigDecimal.ZERO;
        }
        return price.divide(BigDecimal.valueOf(0.05), 0, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(0.05))
                .setScale(2, java.math.RoundingMode.HALF_UP);
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
