package com.tradingbot.model.order;

import java.time.Instant;

/** Standard response payload returned after placing or modifying an order. */
public record OrderResponse(
        boolean success,
        String orderId,
        OrderStatus status,
        String message,
        OrderRequest request,
        Instant timestamp) {
    public static OrderResponse success(String orderId, OrderRequest request, String message) {
        return new OrderResponse(true, orderId, OrderStatus.OPEN, message, request, Instant.now());
    }

    public static OrderResponse failure(OrderRequest request, String error) {
        return new OrderResponse(false, null, OrderStatus.REJECTED, error, request, Instant.now());
    }
}
