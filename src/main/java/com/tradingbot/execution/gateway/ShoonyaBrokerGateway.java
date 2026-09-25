package com.tradingbot.execution.gateway;

import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.order.ShoonyaOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ShoonyaBrokerGateway implements BrokerOrderGateway {

    private final ShoonyaOrderService orderService;

    @Autowired
    public ShoonyaBrokerGateway(ShoonyaOrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public String getBrokerName() {
        return "SHOONYA";
    }

    @Override
    public OrderResponse placeOrder(OrderRequest request) {
        return orderService.placeOrder(request);
    }

    @Override
    public OrderResponse cancelOrder(String orderId) {
        return orderService.cancelOrder(orderId);
    }

    @Override
    public OrderResponse modifyOrder(String orderId, OrderRequest request) {
        return orderService.modifyOrder(orderId, request);
    }
}
