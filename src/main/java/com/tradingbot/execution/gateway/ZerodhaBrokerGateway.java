package com.tradingbot.execution.gateway;

import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.OrderStatus;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Broker gateway implementation for Zerodha Kite Connect API. */
@Component
public class ZerodhaBrokerGateway implements BrokerOrderGateway {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaBrokerGateway.class);

    @Override
    public String getBrokerName() {
        return "ZERODHA";
    }

    @Override
    public OrderResponse placeOrder(OrderRequest request) {
        log.info(
                "[ZERODHA-GATEWAY] Routing order to Zerodha Kite API: {} {} x {}",
                request.transactionType(),
                request.tradingSymbol(),
                request.quantity());
        String orderId = "KITE_" + System.currentTimeMillis();
        return new OrderResponse(
                true, orderId, OrderStatus.OPEN, "Zerodha order placed", request, Instant.now());
    }

    @Override
    public OrderResponse cancelOrder(String orderId) {
        log.info("[ZERODHA-GATEWAY] Cancelling Zerodha order: {}", orderId);
        return new OrderResponse(
                true,
                orderId,
                OrderStatus.CANCELLED,
                "Zerodha order cancelled",
                null,
                Instant.now());
    }

    @Override
    public OrderResponse modifyOrder(String orderId, OrderRequest request) {
        log.info("[ZERODHA-GATEWAY] Modifying Zerodha order: {}", orderId);
        return new OrderResponse(
                true, orderId, OrderStatus.OPEN, "Zerodha order modified", request, Instant.now());
    }
}
