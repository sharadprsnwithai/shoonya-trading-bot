package com.tradingbot.execution.consumer;

import com.tradingbot.execution.gateway.BrokerOrderGateway;
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
                        OrderType.MKT,
                        ProductType.MIS,
                        quantity,
                        BigDecimal.ZERO,
                        null,
                        signal.signalId());

        OrderResponse resp = orderGateway.placeOrder(request);
        log.info(
                "[CONSUMER:{}] Zerodha live order placed: {} | Result: {}",
                getConsumerId(),
                signal.tradingSymbol(),
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
