package com.tradingbot.execution.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tradingbot.execution.gateway.BrokerOrderGateway;
import com.tradingbot.model.execution.BrokerPosition;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.OrderStatus;
import com.tradingbot.model.order.OrderType;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Live-P&L hardening tests for the order path: unfilled LIMIT entries must never count as
 * confirmed, exit-family orders must cancel the resting protective stop FIRST (verified), and a
 * failed exit must re-protect the still-open position.
 */
class AbstractTradeExecutionConsumerOrderPathTest {

    private static final BigDecimal STOP = BigDecimal.valueOf(1480.00);

    private static TradeSignal futuresEntry(String id) {
        return TradeSignal.of(
                "LVR_FUTURES",
                "INFY",
                "INFY26MARFUT",
                SignalAction.ENTRY_LONG,
                BigDecimal.valueOf(1500),
                STOP,
                BigDecimal.valueOf(1540),
                100,
                "Entry",
                java.util.Map.of(
                        "instrumentType", "FUTURES",
                        "orderType", "LMT",
                        "exchange", "NFO"));
    }

    private static TradeSignal futuresExit(String id, SignalAction action) {
        return TradeSignal.of(
                "LVR_FUTURES",
                "INFY",
                "INFY26MARFUT",
                action,
                BigDecimal.valueOf(1470),
                STOP,
                BigDecimal.valueOf(1540),
                100,
                "Exit",
                java.util.Map.of("instrumentType", "FUTURES", "exchange", "NFO"));
    }

    /** placeOrder stub: position orders get ORD-n, stops get SL-n. */
    private static void stubOrderIds(
            BrokerOrderGateway gateway, AtomicInteger ord, AtomicInteger sl) {
        when(gateway.placeOrder(any(OrderRequest.class)))
                .thenAnswer(
                        inv -> {
                            OrderRequest req = inv.getArgument(0);
                            boolean isSl =
                                    req.orderType() == OrderType.SL_MKT
                                            || req.orderType() == OrderType.SL_LMT;
                            String id =
                                    isSl
                                            ? "SL-" + sl.incrementAndGet()
                                            : "ORD-" + ord.incrementAndGet();
                            return OrderResponse.success(
                                    id, req, isSl ? "SL accepted" : "accepted");
                        });
    }

    @Test
    void unfilledLimitEntryIsCancelledAndMarkedUnconfirmed() {
        BrokerOrderGateway gateway = mock(BrokerOrderGateway.class);
        when(gateway.supportsOrderStatusPolling()).thenReturn(true);
        stubOrderIds(gateway, new AtomicInteger(), new AtomicInteger());
        // The limit never fills: the order stays OPEN through the whole bounded poll.
        when(gateway.getOrderStatus(anyString())).thenReturn(OrderStatus.OPEN);
        when(gateway.cancelOrder("ORD-1"))
                .thenReturn(
                        new OrderResponse(
                                true,
                                "ORD-1",
                                OrderStatus.CANCELLED,
                                "cancelled",
                                null,
                                Instant.now()));

        ZerodhaTradeConsumer consumer =
                new ZerodhaTradeConsumer(
                        "zerodha-live", ExecutionMode.LIVE, 1.0, true, 30, gateway);

        consumer.handleLiveExecution(futuresEntry("SIG-1"), 100);

        // The unfilled entry order must be cancelled at the broker...
        verify(gateway).cancelOrder("ORD-1");
        // ...and it must never be recorded as a broker-confirmed entry.
        verify(gateway, times(1))
                .placeOrder(any()); // entry only — no protective stop for an unfilled entry
        // A later EXIT for the same symbol must be suppressed (no naked short).
        consumer.handleLiveExecution(futuresExit("SIG-1", SignalAction.EXIT_LONG), 100);
        verify(gateway, times(1)).placeOrder(any());
    }

    @Test
    void entryCancelFailureStillFailsClosedAsUnconfirmed() {
        BrokerOrderGateway gateway = mock(BrokerOrderGateway.class);
        when(gateway.supportsOrderStatusPolling()).thenReturn(true);
        stubOrderIds(gateway, new AtomicInteger(), new AtomicInteger());
        when(gateway.getOrderStatus(anyString())).thenReturn(OrderStatus.OPEN);
        // Cancel attempt fails: the order's fate is unknown — still must not count as confirmed.
        when(gateway.cancelOrder("ORD-1")).thenReturn(OrderResponse.failure(null, "cancel failed"));

        ZerodhaTradeConsumer consumer =
                new ZerodhaTradeConsumer(
                        "zerodha-live", ExecutionMode.LIVE, 1.0, true, 30, gateway);

        consumer.handleLiveExecution(futuresEntry("SIG-2"), 100);
        verify(gateway).cancelOrder("ORD-1");
        verify(gateway, times(1)).placeOrder(any());

        consumer.handleLiveExecution(futuresExit("SIG-2", SignalAction.EXIT_LONG), 100);
        verify(gateway, times(1)).placeOrder(any()); // EXIT suppressed — still unconfirmed
    }

    @Test
    void exitAbortsWhenProtectiveStopCannotBeCancelled() {
        BrokerOrderGateway gateway = mock(BrokerOrderGateway.class);
        stubOrderIds(gateway, new AtomicInteger(), new AtomicInteger());
        when(gateway.cancelOrder(anyString()))
                .thenReturn(OrderResponse.failure(null, "cancel failed"));

        ZerodhaTradeConsumer consumer =
                new ZerodhaTradeConsumer(
                        "zerodha-live", ExecutionMode.LIVE, 1.0, true, 30, gateway);

        consumer.handleLiveExecution(futuresEntry("SIG-3"), 100); // entry + protective stop
        consumer.handleLiveExecution(futuresExit("SIG-3", SignalAction.EXIT_LONG), 100);

        // Entry + stop only. The exit must NOT go out while the stop is still live at the broker.
        verify(gateway, times(2)).placeOrder(any());
        verify(gateway, atLeastOnce()).cancelOrder(anyString());
    }

    @Test
    void partialExitAbortsWhenProtectiveStopCannotBeCancelled() {
        BrokerOrderGateway gateway = mock(BrokerOrderGateway.class);
        stubOrderIds(gateway, new AtomicInteger(), new AtomicInteger());
        when(gateway.cancelOrder(anyString()))
                .thenReturn(OrderResponse.failure(null, "cancel failed"));

        ZerodhaTradeConsumer consumer =
                new ZerodhaTradeConsumer(
                        "zerodha-live", ExecutionMode.LIVE, 1.0, true, 30, gateway);

        consumer.handleLiveExecution(futuresEntry("SIG-4"), 100); // entry + protective stop
        consumer.handleLiveExecution(futuresExit("SIG-4", SignalAction.PARTIAL_EXIT_LONG), 50);

        // Entry + stop only — the partial exit (and its runner stop) must be withheld.
        verify(gateway, times(2)).placeOrder(any());
    }

    @Test
    void rejectedExitReplacesProtectiveStopBecausePositionStaysOpen() {
        BrokerOrderGateway gateway = mock(BrokerOrderGateway.class);
        stubOrderIds(gateway, new AtomicInteger(), new AtomicInteger());
        when(gateway.cancelOrder(anyString()))
                .thenAnswer(
                        inv ->
                                new OrderResponse(
                                        true,
                                        inv.getArgument(0),
                                        OrderStatus.CANCELLED,
                                        "cancelled",
                                        null,
                                        Instant.now()));
        // The second position order (the exit; SLs don't count) is rejected at the broker.
        AtomicInteger positionOrders = new AtomicInteger();
        when(gateway.placeOrder(any(OrderRequest.class)))
                .thenAnswer(
                        inv -> {
                            OrderRequest req = inv.getArgument(0);
                            boolean isSl =
                                    req.orderType() == OrderType.SL_MKT
                                            || req.orderType() == OrderType.SL_LMT;
                            if (isSl) {
                                return OrderResponse.success(
                                        "SL-" + req.triggerPrice(), req, "SL accepted");
                            }
                            int n = positionOrders.incrementAndGet();
                            if (n == 2) {
                                return OrderResponse.failure(req, "margin rejected");
                            }
                            return OrderResponse.success("ORD-" + n, req, "accepted");
                        });

        ZerodhaTradeConsumer consumer =
                new ZerodhaTradeConsumer(
                        "zerodha-live", ExecutionMode.LIVE, 1.0, true, 30, gateway);

        consumer.handleLiveExecution(futuresEntry("SIG-5"), 100); // ORD-1 + SL
        consumer.handleLiveExecution(futuresExit("SIG-5", SignalAction.EXIT_LONG), 100);

        // exit (failed) + a fresh protective stop for the position that is still open.
        org.mockito.ArgumentCaptor<OrderRequest> captor =
                org.mockito.ArgumentCaptor.forClass(OrderRequest.class);
        verify(gateway, times(4)).placeOrder(captor.capture());
        OrderRequest rePlaced = captor.getAllValues().get(3);
        verify(gateway).cancelOrder("SL-1480.0"); // the resting stop, by its stubbed id
        org.junit.jupiter.api.Assertions.assertEquals(OrderType.SL_MKT, rePlaced.orderType());
        org.junit.jupiter.api.Assertions.assertEquals(100, rePlaced.quantity());
    }

    @Test
    void unfilledExitIsCancelledAndProtectiveStopReplaced() {
        BrokerOrderGateway gateway = mock(BrokerOrderGateway.class);
        when(gateway.supportsOrderStatusPolling()).thenReturn(true);
        AtomicInteger positionOrders = new AtomicInteger();
        AtomicInteger slCounter = new AtomicInteger();
        when(gateway.placeOrder(any(OrderRequest.class)))
                .thenAnswer(
                        inv -> {
                            OrderRequest req = inv.getArgument(0);
                            boolean isSl =
                                    req.orderType() == OrderType.SL_MKT
                                            || req.orderType() == OrderType.SL_LMT;
                            String id =
                                    isSl
                                            ? "SL-" + slCounter.incrementAndGet()
                                            : "ORD-" + positionOrders.incrementAndGet();
                            return OrderResponse.success(id, req, "accepted");
                        });
        // The entry fills; the exit limit never does (stays OPEN).
        // NB: getArgument must be typed explicitly — inside String.valueOf it would infer char[].
        when(gateway.getOrderStatus(anyString()))
                .thenAnswer(
                        inv -> {
                            String id = inv.getArgument(0);
                            return id.startsWith("ORD-1") ? OrderStatus.COMPLETE : OrderStatus.OPEN;
                        });
        when(gateway.cancelOrder(anyString()))
                .thenAnswer(
                        inv ->
                                new OrderResponse(
                                        true,
                                        inv.getArgument(0),
                                        OrderStatus.CANCELLED,
                                        "cancelled",
                                        null,
                                        Instant.now()));

        ZerodhaTradeConsumer consumer =
                new ZerodhaTradeConsumer(
                        "zerodha-live", ExecutionMode.LIVE, 1.0, true, 30, gateway);

        consumer.handleLiveExecution(futuresEntry("SIG-6"), 100); // fills on poll
        consumer.handleLiveExecution(futuresExit("SIG-6", SignalAction.EXIT_LONG), 100);

        // The unfilled exit order must be cancelled so it cannot double-fill later...
        verify(gateway).cancelOrder("ORD-2");
        // ...and the still-open position must get its protective stop back.
        org.mockito.ArgumentCaptor<OrderRequest> captor =
                org.mockito.ArgumentCaptor.forClass(OrderRequest.class);
        verify(gateway, times(4)).placeOrder(captor.capture());
        org.junit.jupiter.api.Assertions.assertEquals(
                OrderType.SL_MKT, captor.getAllValues().get(3).orderType());
    }

    @Test
    void confirmedEntryVerifiesFillAgainstBrokerPositions() {
        BrokerOrderGateway gateway = mock(BrokerOrderGateway.class);
        stubOrderIds(gateway, new AtomicInteger(), new AtomicInteger());
        when(gateway.getPositions())
                .thenReturn(
                        List.of(
                                BrokerPosition.of(
                                        "ZERODHA",
                                        "NFO",
                                        "INFY",
                                        "INFY26MARFUT",
                                        "MIS",
                                        100,
                                        BigDecimal.valueOf(1501.50),
                                        BigDecimal.valueOf(1502.00),
                                        BigDecimal.ZERO,
                                        BigDecimal.ZERO)));

        ZerodhaTradeConsumer consumer =
                new ZerodhaTradeConsumer(
                        "zerodha-live", ExecutionMode.LIVE, 1.0, true, 30, gateway);

        consumer.handleLiveExecution(futuresEntry("SIG-7"), 100);

        // Fill verification pass: the broker's actual position must be queried after confirmation.
        verify(gateway, atLeastOnce()).getPositions();
        // A clean full fill must NOT downgrade the confirmed entry.
        verify(gateway, never()).cancelOrder(anyString());
    }
}
