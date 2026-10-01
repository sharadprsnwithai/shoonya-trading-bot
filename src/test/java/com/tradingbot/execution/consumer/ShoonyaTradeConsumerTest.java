package com.tradingbot.execution.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.tradingbot.execution.gateway.BrokerOrderGateway;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
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
}
