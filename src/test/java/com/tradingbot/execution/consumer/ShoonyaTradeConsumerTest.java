package com.tradingbot.execution.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.tradingbot.execution.gateway.BrokerOrderGateway;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.OrderStatus;
import com.tradingbot.model.order.OrderType;
import com.tradingbot.model.order.TransactionType;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

class ShoonyaTradeConsumerTest {

    @Test
    void testShoonyaConsumerExecutesLiveOrder() throws InterruptedException {
        BrokerOrderGateway mockGateway = mock(BrokerOrderGateway.class);
        when(mockGateway.placeOrder(any(OrderRequest.class)))
                .thenAnswer(
                        inv -> {
                            OrderRequest req = inv.getArgument(0);
                            return OrderResponse.success("ORD_123", req, "Order placed");
                        });

        ShoonyaTradeConsumer consumer =
                new ShoonyaTradeConsumer(
                        "shoonya-live", ExecutionMode.LIVE, 1.0, true, 30, mockGateway);

        Sinks.Many<TradeSignal> sink = Sinks.many().multicast().directBestEffort();
        consumer.start(sink.asFlux());

        TradeSignal signal =
                TradeSignal.of(
                        "LVR_FUTURES",
                        "RELIANCE",
                        "RELIANCE26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(2500),
                        BigDecimal.valueOf(2480),
                        BigDecimal.valueOf(2540),
                        250,
                        "Breakout",
                        null);

        sink.tryEmitNext(signal);
        Thread.sleep(150);

        verify(mockGateway, times(1))
                .placeOrder(
                        argThat(
                                (OrderRequest req) ->
                                        req.tradingSymbol().equals("RELIANCE26MARFUT")
                                                && req.transactionType() == TransactionType.BUY
                                                && req.quantity() == 250));
        consumer.stop();
    }

    @Test
    void testShoonyaOptionBuyingEntryShortExecutesBuyPE() throws InterruptedException {
        BrokerOrderGateway mockGateway = mock(BrokerOrderGateway.class);
        when(mockGateway.placeOrder(any(OrderRequest.class)))
                .thenAnswer(
                        inv ->
                                OrderResponse.success(
                                        "ORD_124", inv.getArgument(0), "Order placed"));

        ShoonyaTradeConsumer consumer =
                new ShoonyaTradeConsumer(
                        "shoonya-live", ExecutionMode.LIVE, 1.0, true, 30, mockGateway);

        Sinks.Many<TradeSignal> sink = Sinks.many().multicast().directBestEffort();
        consumer.start(sink.asFlux());

        TradeSignal signal =
                TradeSignal.of(
                        "LVR_OPTIONS",
                        "RELIANCE",
                        "RELIANCE26OCT2700PE",
                        SignalAction.ENTRY_SHORT,
                        BigDecimal.valueOf(45),
                        BigDecimal.valueOf(2500),
                        BigDecimal.valueOf(2460),
                        250,
                        "LVR Option Entry Triggered",
                        java.util.Map.of("instrumentType", "OPTION"));

        sink.tryEmitNext(signal);
        Thread.sleep(150);

        verify(mockGateway, times(1))
                .placeOrder(
                        argThat(
                                (OrderRequest req) ->
                                        req.tradingSymbol().equals("RELIANCE26OCT2700PE")
                                                && req.transactionType() == TransactionType.BUY
                                                && req.quantity() == 250));
        consumer.stop();
    }

    @Test
    void testShoonyaOptionBuyingExitShortExecutesSellPE() throws InterruptedException {
        BrokerOrderGateway mockGateway = mock(BrokerOrderGateway.class);
        when(mockGateway.placeOrder(any(OrderRequest.class)))
                .thenAnswer(
                        inv ->
                                OrderResponse.success(
                                        "ORD_125", inv.getArgument(0), "Order placed"));

        ShoonyaTradeConsumer consumer =
                new ShoonyaTradeConsumer(
                        "shoonya-live", ExecutionMode.LIVE, 1.0, true, 30, mockGateway);

        Sinks.Many<TradeSignal> sink = Sinks.many().multicast().directBestEffort();
        consumer.start(sink.asFlux());

        TradeSignal signal =
                TradeSignal.of(
                        "LVR_OPTIONS",
                        "RELIANCE",
                        "RELIANCE26OCT2700PE",
                        SignalAction.EXIT_SHORT,
                        BigDecimal.valueOf(60),
                        BigDecimal.valueOf(2500),
                        null,
                        250,
                        "TARGET_1_2_FULL_EXIT",
                        java.util.Map.of("instrumentType", "OPTION"));

        sink.tryEmitNext(signal);
        Thread.sleep(150);

        verify(mockGateway, times(1))
                .placeOrder(
                        argThat(
                                (OrderRequest req) ->
                                        req.tradingSymbol().equals("RELIANCE26OCT2700PE")
                                                && req.transactionType() == TransactionType.SELL
                                                && req.quantity() == 250));
        consumer.stop();
    }

    @Test
    void testRejectedEntryMarksUnconfirmedAndSuppressesSubsequentExit() throws InterruptedException {
        BrokerOrderGateway mockGateway = mock(BrokerOrderGateway.class);
        when(mockGateway.placeOrder(any(OrderRequest.class)))
                .thenAnswer(inv -> OrderResponse.failure(inv.getArgument(0), "REJECTED_BY_EXCHANGE"));

        ShoonyaTradeConsumer consumer =
                new ShoonyaTradeConsumer(
                        "shoonya-live", ExecutionMode.LIVE, 1.0, true, 30, mockGateway);
        Sinks.Many<TradeSignal> sink = Sinks.many().multicast().directBestEffort();
        consumer.start(sink.asFlux());

        TradeSignal entry =
                TradeSignal.of(
                        "LVR_FUTURES",
                        "RELIANCE",
                        "RELIANCE26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(2500),
                        BigDecimal.valueOf(2480),
                        BigDecimal.valueOf(2540),
                        250,
                        "Entry",
                        java.util.Map.of(
                                "instrumentType", "FUTURES",
                                "brokerStopLossPrice", BigDecimal.valueOf(2480.50)));
        TradeSignal exit =
                TradeSignal.of(
                        "LVR_FUTURES",
                        "RELIANCE",
                        "RELIANCE26MARFUT",
                        SignalAction.EXIT_SHORT,
                        BigDecimal.valueOf(2470),
                        null,
                        null,
                        250,
                        "SPOT_SL_HIT",
                        java.util.Map.of("instrumentType", "FUTURES"));

        sink.tryEmitNext(entry);
        Thread.sleep(150);
        sink.tryEmitNext(exit);
        Thread.sleep(150);

        // Only the failed ENTRY attempt reached the broker — the EXIT was suppressed so it
        // can never become a naked reverse order. No protective SL on a failed entry.
        verify(mockGateway, times(1)).placeOrder(any(OrderRequest.class));
        assertThat(consumer.getConfirmedEntrySymbols()).doesNotContain("RELIANCE");
        consumer.stop();
    }

    @Test
    void testConfirmedEntryPlacesBestEffortProtectiveStop() throws InterruptedException {
        BrokerOrderGateway mockGateway = mock(BrokerOrderGateway.class);
        when(mockGateway.placeOrder(any(OrderRequest.class)))
                .thenAnswer(inv -> OrderResponse.success("ORD_1", inv.getArgument(0), "filled"));

        ShoonyaTradeConsumer consumer =
                new ShoonyaTradeConsumer(
                        "shoonya-live", ExecutionMode.LIVE, 1.0, true, 30, mockGateway);
        Sinks.Many<TradeSignal> sink = Sinks.many().multicast().directBestEffort();
        consumer.start(sink.asFlux());

        TradeSignal entry =
                TradeSignal.of(
                        "LVR_FUTURES",
                        "RELIANCE",
                        "RELIANCE26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(2500),
                        BigDecimal.valueOf(2480),
                        BigDecimal.valueOf(2540),
                        250,
                        "Entry",
                        java.util.Map.of(
                                "instrumentType", "FUTURES",
                                "brokerStopLossPrice", BigDecimal.valueOf(2480.50)));

        sink.tryEmitNext(entry);
        Thread.sleep(200);

        org.mockito.ArgumentCaptor<OrderRequest> captor =
                org.mockito.ArgumentCaptor.forClass(OrderRequest.class);
        verify(mockGateway, times(2)).placeOrder(captor.capture());
        OrderRequest entryReq = captor.getAllValues().get(0);
        OrderRequest slReq = captor.getAllValues().get(1);

        assertThat(entryReq.transactionType()).isEqualTo(TransactionType.BUY);
        assertThat(slReq.orderType()).isEqualTo(OrderType.SL_MKT);
        assertThat(slReq.transactionType()).isEqualTo(TransactionType.SELL);
        assertThat(slReq.triggerPrice()).isEqualByComparingTo(BigDecimal.valueOf(2480.50));
        assertThat(slReq.price()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(slReq.quantity()).isEqualTo(entryReq.quantity());
        assertThat(consumer.getConfirmedEntrySymbols()).contains("RELIANCE");
        consumer.stop();
    }

    @Test
    void testPollRejectionMarksEntryUnconfirmedAndSuppressesExit() throws InterruptedException {
        BrokerOrderGateway mockGateway = mock(BrokerOrderGateway.class);
        when(mockGateway.supportsOrderStatusPolling()).thenReturn(true);
        when(mockGateway.placeOrder(any(OrderRequest.class)))
                .thenAnswer(inv -> OrderResponse.success("ORD_9", inv.getArgument(0), "accepted"));
        when(mockGateway.getOrderStatus("ORD_9")).thenReturn(OrderStatus.REJECTED);

        ShoonyaTradeConsumer consumer =
                new ShoonyaTradeConsumer(
                        "shoonya-live", ExecutionMode.LIVE, 1.0, true, 30, mockGateway);
        Sinks.Many<TradeSignal> sink = Sinks.many().multicast().directBestEffort();
        consumer.start(sink.asFlux());

        TradeSignal entry =
                TradeSignal.of(
                        "LVR_FUTURES",
                        "INFY",
                        "INFY26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(1500),
                        BigDecimal.valueOf(1480),
                        BigDecimal.valueOf(1540),
                        100,
                        "Entry",
                        java.util.Map.of("instrumentType", "FUTURES"));
        TradeSignal exit =
                TradeSignal.of(
                        "LVR_FUTURES",
                        "INFY",
                        "INFY26MARFUT",
                        SignalAction.EXIT_SHORT,
                        BigDecimal.valueOf(1470),
                        null,
                        null,
                        100,
                        "SPOT_SL_HIT",
                        java.util.Map.of("instrumentType", "FUTURES"));

        sink.tryEmitNext(entry);
        Thread.sleep(250);
        sink.tryEmitNext(exit);
        Thread.sleep(150);

        // Place succeeded but the broker later reported REJECTED → entry unconfirmed → EXIT
        // suppressed (and no protective SL was placed for the rejected entry).
        verify(mockGateway, times(1)).placeOrder(any(OrderRequest.class));
        assertThat(consumer.getConfirmedEntrySymbols()).doesNotContain("INFY");
        consumer.stop();
    }

    @Test
    void testSuccessfulExitClearsConfirmedEntryLedger() throws InterruptedException {
        BrokerOrderGateway mockGateway = mock(BrokerOrderGateway.class);
        when(mockGateway.placeOrder(any(OrderRequest.class)))
                .thenAnswer(inv -> OrderResponse.success("ORD_2", inv.getArgument(0), "filled"));

        ShoonyaTradeConsumer consumer =
                new ShoonyaTradeConsumer(
                        "shoonya-live", ExecutionMode.LIVE, 1.0, true, 30, mockGateway);
        Sinks.Many<TradeSignal> sink = Sinks.many().multicast().directBestEffort();
        consumer.start(sink.asFlux());

        TradeSignal entry =
                TradeSignal.of(
                        "LVR_FUTURES",
                        "TCS",
                        "TCS26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(3800),
                        BigDecimal.valueOf(3780),
                        BigDecimal.valueOf(3840),
                        150,
                        "Entry",
                        null);
        TradeSignal exit =
                TradeSignal.of(
                        "LVR_FUTURES",
                        "TCS",
                        "TCS26MARFUT",
                        SignalAction.EXIT_LONG,
                        BigDecimal.valueOf(3850),
                        null,
                        null,
                        150,
                        "TARGET_1_2_FULL_EXIT",
                        null);

        sink.tryEmitNext(entry);
        Thread.sleep(150);
        assertThat(consumer.getConfirmedEntrySymbols()).contains("TCS");

        sink.tryEmitNext(exit);
        Thread.sleep(150);
        // Exit succeeded → symbol leaves the confirmed ledger (drift reconciliation stays truthful).
        assertThat(consumer.getConfirmedEntrySymbols()).doesNotContain("TCS");
        verify(mockGateway, times(2)).placeOrder(any(OrderRequest.class));
        consumer.stop();
    }
}
