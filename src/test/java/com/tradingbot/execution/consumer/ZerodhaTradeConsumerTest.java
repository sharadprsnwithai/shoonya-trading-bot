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

class ZerodhaTradeConsumerTest {

    @Test
    void testZerodhaConsumerExecutesLiveOrderWithMultiplier() throws InterruptedException {
        BrokerOrderGateway mockGateway = mock(BrokerOrderGateway.class);
        when(mockGateway.placeOrder(any(OrderRequest.class)))
                .thenAnswer(
                        inv -> {
                            OrderRequest req = inv.getArgument(0);
                            return OrderResponse.success("KITE_123", req, "Order placed");
                        });

        ZerodhaTradeConsumer consumer =
                new ZerodhaTradeConsumer(
                        "zerodha-live", ExecutionMode.LIVE, 2.0, true, 30, mockGateway);

        Sinks.Many<TradeSignal> sink = Sinks.many().multicast().directBestEffort();
        consumer.start(sink.asFlux());

        TradeSignal signal =
                TradeSignal.of(
                        "LVR_FUTURES",
                        "TCS",
                        "TCS26MARFUT",
                        SignalAction.ENTRY_SHORT,
                        BigDecimal.valueOf(3800),
                        BigDecimal.valueOf(3830),
                        BigDecimal.valueOf(3740),
                        175,
                        "Breakdown",
                        null);

        sink.tryEmitNext(signal);
        Thread.sleep(150);

        // 175 * 2.0 = 350
        verify(mockGateway, times(1))
                .placeOrder(
                        argThat(
                                (OrderRequest req) ->
                                        req.tradingSymbol().equals("TCS26MARFUT")
                                                && req.transactionType() == TransactionType.SELL
                                                && req.quantity() == 350));
        consumer.stop();
    }
}
