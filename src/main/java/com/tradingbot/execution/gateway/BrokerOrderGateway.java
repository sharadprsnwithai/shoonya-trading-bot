package com.tradingbot.execution.gateway;

import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;

/** Gateway contract abstracting broker-specific order API interactions. */
public interface BrokerOrderGateway {
    String getBrokerName();

    OrderResponse placeOrder(OrderRequest request);

    OrderResponse cancelOrder(String orderId);

    OrderResponse modifyOrder(String orderId, OrderRequest request);
}
