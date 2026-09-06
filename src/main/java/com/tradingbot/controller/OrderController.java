package com.tradingbot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.OrderType;
import com.tradingbot.order.ShoonyaOrderService;
import java.math.BigDecimal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST controller for placing, modifying, and canceling orders directly with Shoonya. */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final ShoonyaOrderService orderService;

    public OrderController(ShoonyaOrderService orderService) {
        this.orderService = orderService;
    }

    /** Place an order on Shoonya. Example: POST /api/v1/orders/place */
    @PostMapping("/place")
    public ResponseEntity<OrderResponse> placeOrder(@RequestBody OrderRequest request) {
        OrderResponse response = orderService.placeOrder(request);
        return ResponseEntity.ok(response);
    }

    /** Modify an existing open or trigger-pending order. Example: POST /api/v1/orders/modify */
    @PostMapping("/modify")
    public ResponseEntity<OrderResponse> modifyOrder(
            @RequestParam String orderId,
            @RequestParam String symbol,
            @RequestParam(defaultValue = "NFO") String exchange,
            @RequestParam int quantity,
            @RequestParam(required = false) BigDecimal price,
            @RequestParam(required = false) BigDecimal triggerPrice,
            @RequestParam(defaultValue = "LMT") OrderType orderType) {
        OrderResponse response =
                orderService.modifyOrder(
                        orderId, symbol, exchange, quantity, price, triggerPrice, orderType);
        return ResponseEntity.ok(response);
    }

    /** Cancel an open order on Shoonya. Example: POST /api/v1/orders/cancel/{orderId} */
    @PostMapping("/cancel/{orderId}")
    public ResponseEntity<OrderResponse> cancelOrder(@PathVariable String orderId) {
        OrderResponse response = orderService.cancelOrder(orderId);
        return ResponseEntity.ok(response);
    }

    /** View current order book from Shoonya. Example: GET /api/v1/orders/book */
    @GetMapping("/book")
    public ResponseEntity<JsonNode> getOrderBook() {
        return ResponseEntity.ok(orderService.getOrderBook());
    }

    /** View current position book from Shoonya. Example: GET /api/v1/orders/positions */
    @GetMapping("/positions")
    public ResponseEntity<JsonNode> getPositionBook() {
        return ResponseEntity.ok(orderService.getPositionBook());
    }
}
