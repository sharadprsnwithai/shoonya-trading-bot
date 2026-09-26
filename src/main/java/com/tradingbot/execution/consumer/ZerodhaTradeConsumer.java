package com.tradingbot.execution.consumer;

import com.tradingbot.execution.gateway.BrokerOrderGateway;
import com.tradingbot.execution.gateway.ZerodhaBrokerGateway;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.OrderType;
import com.tradingbot.model.order.ProductType;
import com.tradingbot.model.order.TransactionType;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import java.math.BigDecimal;

public class ZerodhaTradeConsumer extends AbstractTradeExecutionConsumer {

    private final BrokerOrderGateway orderGateway;

    public ZerodhaTradeConsumer(
            String consumerId,
            ExecutionMode executionMode,
            double quantityMultiplier,
            boolean enabled,
            long maxSignalAgeSeconds,
            BrokerOrderGateway orderGateway) {
        super(
                consumerId,
                "ZERODHA",
                executionMode,
                quantityMultiplier,
                enabled,
                maxSignalAgeSeconds);
        this.orderGateway = orderGateway;
    }

    @Override
    protected void handleLiveExecution(TradeSignal signal, int quantity) {
        TransactionType txnType =
                (signal.action() == SignalAction.ENTRY_LONG
                                || signal.action() == SignalAction.BUY
                                || signal.action() == SignalAction.EXIT_SHORT
                                || signal.action() == SignalAction.PARTIAL_EXIT_SHORT)
                        ? TransactionType.BUY
                        : TransactionType.SELL;

        OrderRequest request =
                new OrderRequest(
                        signal.tradingSymbol(),
                        "NFO",
                        txnType,
                        OrderType.LMT,
                        ProductType.MIS,
                        quantity,
                        signal.price(),
                        signal.stopLoss(),
                        signal.signalId());

        OrderResponse resp;
        if (orderGateway instanceof ZerodhaBrokerGateway zerodhaGw) {
            resp = zerodhaGw.placeOrderWithReferencePrice(request, signal.price());
        } else {
            resp = orderGateway.placeOrder(request);
        }

        log.info(
                "[CONSUMER:{}] Zerodha live order executed: {} {} x {} @ RefPrice: {} |"
                        + " Result: {}",
                getConsumerId(),
                txnType,
                signal.tradingSymbol(),
                quantity,
                signal.price(),
                resp);
    }

    @Override
    protected void handlePaperExecution(TradeSignal signal, int quantity) {
        log.info(
                "[CONSUMER:{}:PAPER] Zerodha Simulated trade executed: {} {} x {} @ {}",
                getConsumerId(),
                signal.action(),
                signal.tradingSymbol(),
                quantity,
                signal.price());
    }
}
