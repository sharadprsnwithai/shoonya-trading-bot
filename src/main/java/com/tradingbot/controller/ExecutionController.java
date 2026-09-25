package com.tradingbot.controller;

import com.tradingbot.execution.consumer.TradeConsumerManager;
import com.tradingbot.execution.consumer.TradeExecutionConsumer;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST controller for inspecting active trade execution consumers and broker client statuses. */
@RestController
@RequestMapping("/api/v1/execution")
public class ExecutionController {

    private final TradeConsumerManager consumerManager;

    public ExecutionController(TradeConsumerManager consumerManager) {
        this.consumerManager = consumerManager;
    }

    /** Lists all registered trade execution consumers. Example: GET /api/v1/execution/consumers */
    @GetMapping("/consumers")
    public ResponseEntity<List<Map<String, Object>>> getConsumers() {
        List<Map<String, Object>> list =
                consumerManager.getRegisteredConsumers().stream()
                        .map(
                                c ->
                                        Map.<String, Object>of(
                                                "id",
                                                c.getConsumerId(),
                                                "broker",
                                                c.getBrokerName(),
                                                "mode",
                                                c.getExecutionMode(),
                                                "enabled",
                                                c.isEnabled(),
                                                "quantityMultiplier",
                                                c.getQuantityMultiplier()))
                        .toList();
        return ResponseEntity.ok(list);
    }

    /**
     * Gets details of a specific execution consumer. Example: GET
     * /api/v1/execution/consumers/shoonya-primary
     */
    @GetMapping("/consumers/{id}")
    public ResponseEntity<?> getConsumer(@PathVariable String id) {
        TradeExecutionConsumer consumer = consumerManager.getConsumer(id);
        if (consumer == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(
                Map.<String, Object>of(
                        "id",
                        consumer.getConsumerId(),
                        "broker",
                        consumer.getBrokerName(),
                        "mode",
                        consumer.getExecutionMode(),
                        "enabled",
                        consumer.isEnabled(),
                        "quantityMultiplier",
                        consumer.getQuantityMultiplier()));
    }
}
