package com.tradingbot.execution.gateway;

import com.tradingbot.model.execution.BrokerPosition;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.OrderStatus;
import java.util.List;

/** Gateway contract abstracting broker-specific order API interactions and position retrieval. */
public interface BrokerOrderGateway {
    String getBrokerName();

    OrderResponse placeOrder(OrderRequest request);

    OrderResponse cancelOrder(String orderId);

    OrderResponse modifyOrder(String orderId, OrderRequest request);

    List<BrokerPosition> getPositions();

    List<BrokerPosition> getHoldings();

    /** C3: whether this gateway can poll order status after placement (default: unsupported). */
    default boolean supportsOrderStatusPolling() {
        return false;
    }

    /** C3: latest known status for an order; only called when polling is supported. */
    default OrderStatus getOrderStatus(String orderId) {
        return OrderStatus.PENDING;
    }
}
