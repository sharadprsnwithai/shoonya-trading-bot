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
}
