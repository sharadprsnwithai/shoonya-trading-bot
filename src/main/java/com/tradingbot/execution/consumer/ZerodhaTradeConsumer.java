package com.tradingbot.execution.consumer;

import com.tradingbot.execution.gateway.BrokerOrderGateway;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.model.order.OrderRequest;
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
        this(
                consumerId,
                executionMode,
                java.util.Collections.emptyMap(),
                quantityMultiplier,
                enabled,
                maxSignalAgeSeconds,
                orderGateway);
    }

    public ZerodhaTradeConsumer(
            String consumerId,
            ExecutionMode executionMode,
            java.util.Map<String, ExecutionMode> strategyModes,
            double quantityMultiplier,
            boolean enabled,
            long maxSignalAgeSeconds,
            BrokerOrderGateway orderGateway) {
        super(
                consumerId,
                "ZERODHA",
                executionMode,
                strategyModes,
                quantityMultiplier,
                enabled,
                maxSignalAgeSeconds);
        this.orderGateway = orderGateway;
    }

    @Override
    protected void handleLiveExecution(TradeSignal signal, int quantity) {
        // C3: never send an EXIT for an entry that was never confirmed at this broker.
        if (!guardExitAllowed(signal)) {
            return;
        }
        // D3: stop maintenance only — cancels and re-places the protective stop, sends no
        // position order.
        if (handleStopMaintenance(signal, quantity, orderGateway)) {
            return;
        }
        TransactionType txnType = resolveTransactionType(signal);

        String exchange = "NFO";
        if (signal.metadata() != null && signal.metadata().get("exchange") != null) {
            exchange = String.valueOf(signal.metadata().get("exchange"));
        }

        OrderType orderType = OrderType.MKT;
        BigDecimal price = BigDecimal.ZERO;
        if (signal.metadata() != null && signal.metadata().containsKey("orderType")) {
            Object ot = signal.metadata().get("orderType");
            if (ot instanceof OrderType) {
                orderType = (OrderType) ot;
            } else if (ot != null) {
                try {
                    orderType = OrderType.valueOf(String.valueOf(ot).trim().toUpperCase());
                } catch (Exception ignored) {
                }
            }
            if (orderType == OrderType.LMT
                    && signal.price() != null
                    && signal.price().compareTo(BigDecimal.ZERO) > 0) {
                price = signal.price();
            }
        }

        OrderRequest request =
                new OrderRequest(
                        signal.tradingSymbol(),
                        exchange,
                        txnType,
                        orderType,
                        ProductType.MIS,
                        quantity,
                        price,
                        null,
                        signal.signalId());

        // C3: validated placement + failed-fill ledger + best-effort protective SL.
        placeOrderConfirmed(signal, request, orderGateway);
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
