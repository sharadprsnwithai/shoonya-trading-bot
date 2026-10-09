package com.tradingbot.execution.consumer;

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
import java.time.Instant;
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

    @Test
    void testZerodhaOptionBuyingEntryShortExecutesBuyWithoutSpotTrigger()
            throws InterruptedException {
        BrokerOrderGateway mockGateway = mock(BrokerOrderGateway.class);
        when(mockGateway.placeOrder(any(OrderRequest.class)))
                .thenAnswer(
                        inv ->
                                OrderResponse.success(
                                        "KITE_124", inv.getArgument(0), "Order placed"));

        ZerodhaTradeConsumer consumer =
                new ZerodhaTradeConsumer(
                        "zerodha-live", ExecutionMode.LIVE, 1.0, true, 30, mockGateway);

        Sinks.Many<TradeSignal> sink = Sinks.many().multicast().directBestEffort();
        consumer.start(sink.asFlux());

        TradeSignal signal =
                TradeSignal.of(
                        "LVR_OPTIONS",
                        "INFY",
                        "INFY26OCT1450PE",
                        SignalAction.ENTRY_SHORT,
                        BigDecimal.valueOf(35),
                        BigDecimal.valueOf(
                                1480), // Spot SL, should NOT be passed as trigger price on
                        // derivative order
                        BigDecimal.valueOf(1420),
                        400,
                        "LVR Option Entry Triggered",
                        java.util.Map.of("instrumentType", "OPTION"));

        sink.tryEmitNext(signal);
        Thread.sleep(150);

        verify(mockGateway, times(1))
                .placeOrder(
                        argThat(
                                (OrderRequest req) ->
                                        req.tradingSymbol().equals("INFY26OCT1450PE")
                                                && req.transactionType() == TransactionType.BUY
                                                && req.triggerPrice() == null
                                                && req.quantity() == 400));
        consumer.stop();
    }

    @Test
    void testStrategySpecificRoutingBetweenLiveAndPaper() throws InterruptedException {
        BrokerOrderGateway mockGateway = mock(BrokerOrderGateway.class);
        when(mockGateway.placeOrder(any(OrderRequest.class)))
                .thenAnswer(
                        inv ->
                                OrderResponse.success(
                                        "KITE_125", inv.getArgument(0), "Order placed"));

        // Configure default mode as PAPER, but override BOLLINGER_HA_1M to LIVE and LVR to PAPER
        java.util.Map<String, ExecutionMode> strategyModes =
                java.util.Map.of(
                        "BOLLINGER_HA_1M", ExecutionMode.LIVE,
                        "LOWEST_VOLUME_REVERSAL", ExecutionMode.PAPER);

        ZerodhaTradeConsumer consumer =
                new ZerodhaTradeConsumer(
                        "zerodha-custom",
                        ExecutionMode.PAPER,
                        strategyModes,
                        1.0,
                        true,
                        30,
                        mockGateway);

        Sinks.Many<TradeSignal> sink = Sinks.many().multicast().directBestEffort();
        consumer.start(sink.asFlux());

        // 1. Emit LVR signal -> Must execute in PAPER (no call to mockGateway)
        TradeSignal lvrSignal =
                TradeSignal.of(
                        "LOWEST_VOLUME_REVERSAL",
                        "RELIANCE",
                        "RELIANCE26OCTFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(2500),
                        null,
                        null,
                        250,
                        "LVR Signal",
                        null);
        sink.tryEmitNext(lvrSignal);
        Thread.sleep(150);

        verify(mockGateway, never()).placeOrder(any());

        // 2. Emit BOLLINGER_HA_1M signal -> Must execute in LIVE (places order with mockGateway)
        TradeSignal bHaSignal =
                TradeSignal.of(
                        "BOLLINGER_HA_1M",
                        "NIFTY",
                        "NIFTY26OCT25950CE",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(150),
                        BigDecimal.valueOf(135),
                        BigDecimal.valueOf(180),
                        130,
                        "Bollinger HA Entry",
                        java.util.Map.of("instrumentType", "OPTION"));
        sink.tryEmitNext(bHaSignal);
        Thread.sleep(150);

        verify(mockGateway, times(1))
                .placeOrder(
                        argThat(
                                (OrderRequest req) ->
                                        req.tradingSymbol().equals("NIFTY26OCT25950CE")
                                                && req.transactionType() == TransactionType.BUY
                                                && req.quantity() == 130));
        consumer.stop();
    }

    @Test
    void testZerodhaUsesMarketOrdersOnEntryAndExit() {
        BrokerOrderGateway gateway = mock(BrokerOrderGateway.class);
        when(gateway.placeOrder(any()))
                .thenReturn(
                        new OrderResponse(
                                true,
                                "KITE-ENTRY",
                                OrderStatus.COMPLETE,
                                "Filled",
                                null,
                                Instant.now()),
                        new OrderResponse(
                                true,
                                "KITE-SL",
                                OrderStatus.OPEN,
                                "SL placed",
                                null,
                                Instant.now()),
                        new OrderResponse(
                                true,
                                "KITE-EXIT",
                                OrderStatus.COMPLETE,
                                "Exited",
                                null,
                                Instant.now()));

        ZerodhaTradeConsumer consumer =
                new ZerodhaTradeConsumer(
                        "zerodha-live", ExecutionMode.LIVE, 1.0, true, 30L, gateway);

        TradeSignal entry =
                TradeSignal.of(
                        "BOLLINGER_HA_1M",
                        "NIFTY",
                        "NIFTY26OCT24000CE",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(120.00),
                        BigDecimal.valueOf(95.00),
                        BigDecimal.valueOf(170.00),
                        65,
                        "Entry",
                        java.util.Map.of(
                                "instrumentType",
                                "OPTION",
                                "exchange",
                                "NFO",
                                "brokerStopLossPrice",
                                BigDecimal.valueOf(95.00)));
        consumer.handleLiveExecution(entry, 65);

        TradeSignal exit =
                TradeSignal.of(
                        "BOLLINGER_HA_1M",
                        "NIFTY",
                        "NIFTY26OCT24000CE",
                        SignalAction.EXIT_LONG,
                        BigDecimal.valueOf(150.00),
                        BigDecimal.valueOf(95.00),
                        BigDecimal.valueOf(170.00),
                        65,
                        "Target",
                        java.util.Map.of("instrumentType", "OPTION", "exchange", "NFO"));
        consumer.handleLiveExecution(exit, 65);

        verify(gateway, times(1))
                .placeOrder(
                        argThat(
                                req ->
                                        req.orderType() == OrderType.MKT
                                                && req.transactionType() == TransactionType.BUY
                                                && req.price().compareTo(BigDecimal.ZERO) == 0));
        verify(gateway, times(1))
                .placeOrder(
                        argThat(
                                req ->
                                        req.orderType() == OrderType.MKT
                                                && req.transactionType() == TransactionType.SELL));
        verify(gateway, never()).placeOrder(argThat(req -> req.orderType() == OrderType.LMT));
    }

    @Test
    void testZerodhaProtectiveStopRemainsStopLossLimitWhilePositionIsMarket() {
        BrokerOrderGateway gateway = mock(BrokerOrderGateway.class);
        when(gateway.placeOrder(any()))
                .thenReturn(
                        new OrderResponse(
                                true,
                                "KITE-ENTRY-2",
                                OrderStatus.COMPLETE,
                                "Filled",
                                null,
                                Instant.now()),
                        new OrderResponse(
                                true,
                                "KITE-SL-2",
                                OrderStatus.OPEN,
                                "SL placed",
                                null,
                                Instant.now()));

        ZerodhaTradeConsumer consumer =
                new ZerodhaTradeConsumer(
                        "zerodha-live", ExecutionMode.LIVE, 1.0, true, 30L, gateway);

        TradeSignal entry =
                TradeSignal.of(
                        "BOLLINGER_HA_1M",
                        "NIFTY",
                        "NIFTY26OCT24000CE",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(120.00),
                        BigDecimal.valueOf(95.00),
                        BigDecimal.valueOf(170.00),
                        65,
                        "Entry",
                        java.util.Map.of(
                                "instrumentType",
                                "OPTION",
                                "exchange",
                                "NFO",
                                "brokerStopLossPrice",
                                BigDecimal.valueOf(95.00)));
        consumer.handleLiveExecution(entry, 65);

        verify(gateway, times(1))
                .placeOrder(
                        argThat(
                                req ->
                                        req.orderType() == OrderType.MKT
                                                && req.transactionType() == TransactionType.BUY
                                                && req.quantity() == 65));
        // The protective stop keeps its price — only position orders were converted to market.
        verify(gateway, times(1))
                .placeOrder(
                        argThat(
                                req ->
                                        req.orderType() == OrderType.SL_LMT
                                                && req.transactionType() == TransactionType.SELL
                                                && req.triggerPrice()
                                                                .compareTo(
                                                                        BigDecimal.valueOf(95.00))
                                                        == 0
                                                && req.price().compareTo(BigDecimal.valueOf(85.50))
                                                        == 0));
    }

    @Test
    void testZerodhaPlacesLimitOrderWhenSpecifiedInMetadata() {
        BrokerOrderGateway gateway = mock(BrokerOrderGateway.class);
        when(gateway.placeOrder(any()))
                .thenReturn(
                        new OrderResponse(
                                true,
                                "KITE-LMT-1",
                                OrderStatus.COMPLETE,
                                "Filled",
                                null,
                                Instant.now()));

        ZerodhaTradeConsumer consumer =
                new ZerodhaTradeConsumer(
                        "zerodha-test", ExecutionMode.LIVE, 1.0, true, 30L, gateway);

        TradeSignal limitSignal =
                TradeSignal.of(
                        "LOWEST_VOLUME_REVERSAL",
                        "PNB",
                        "PNB FUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(113.40),
                        BigDecimal.valueOf(112.95),
                        BigDecimal.valueOf(114.50),
                        8000,
                        "Futures Limit Entry",
                        java.util.Map.of(
                                "instrumentType",
                                "FUTURES",
                                "orderType",
                                "LMT",
                                "exchange",
                                "NSE"));
        consumer.handleLiveExecution(limitSignal, 8000);

        verify(gateway, times(1))
                .placeOrder(
                        argThat(
                                req ->
                                        req.orderType() == OrderType.LMT
                                                && req.price().compareTo(BigDecimal.valueOf(113.40))
                                                        == 0
                                                && req.symbol().equals("PNB FUT")));
    }
}
