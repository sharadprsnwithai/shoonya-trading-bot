package com.tradingbot.execution.gateway;

import com.tradingbot.model.execution.BrokerPosition;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import java.util.List;

/** Gateway contract abstracting broker-specific order API interactions and position retrieval. */
public interface BrokerOrderGateway {
    String getBrokerName();

    OrderResponse placeOrder(OrderRequest request);

    OrderResponse cancelOrder(String orderId);

    OrderResponse modifyOrder(String orderId, OrderRequest request);

    List<BrokerPosition> getPositions();
}
