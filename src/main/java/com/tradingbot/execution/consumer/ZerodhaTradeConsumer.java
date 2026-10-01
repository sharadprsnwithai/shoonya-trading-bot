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
        TransactionType txnType = resolveTransactionType(signal);

        OrderRequest request =
                new OrderRequest(
                        signal.tradingSymbol(),
                        "NFO",
                        txnType,
                        OrderType.LMT,
                        ProductType.MIS,
                        quantity,
                        signal.price(),
                        null,
                        signal.signalId());

        OrderResponse resp = orderGateway.placeOrder(request);

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

    private boolean isOptionSignal(TradeSignal signal) {
        if (signal.metadata() != null) {
            String instType = String.valueOf(signal.metadata().get("instrumentType"));
            if ("OPTION".equalsIgnoreCase(instType) || "OPTIONS".equalsIgnoreCase(instType)) {
                return true;
            }
        }
        String sym = signal.tradingSymbol();
        return sym != null
                && (sym.endsWith("CE")
                        || sym.endsWith("PE")
                        || sym.contains(" CE")
                        || sym.contains(" PE"));
    }

    private TransactionType resolveTransactionType(TradeSignal signal) {
        if (isOptionSignal(signal)) {
            // Option Buying: Entries are BUY (both CE and PE), Exits are SELL (close long option
            // position)
            if (signal.action() == SignalAction.ENTRY_LONG
                    || signal.action() == SignalAction.ENTRY_SHORT
                    || signal.action() == SignalAction.BUY) {
                return TransactionType.BUY;
            } else {
                return TransactionType.SELL;
            }
        }

        // Futures / Equity: Directional long/short
        return (signal.action() == SignalAction.ENTRY_LONG
                        || signal.action() == SignalAction.BUY
                        || signal.action() == SignalAction.EXIT_SHORT
                        || signal.action() == SignalAction.PARTIAL_EXIT_SHORT)
                ? TransactionType.BUY
                : TransactionType.SELL;
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
