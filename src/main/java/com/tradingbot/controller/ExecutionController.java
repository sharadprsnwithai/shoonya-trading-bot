package com.tradingbot.controller;

import com.tradingbot.execution.consumer.TradeConsumerManager;
import com.tradingbot.execution.consumer.TradeExecutionConsumer;
import com.tradingbot.execution.gateway.ShoonyaBrokerGateway;
import com.tradingbot.execution.gateway.ZerodhaBrokerGateway;
import com.tradingbot.model.execution.BrokerPosition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for inspecting active trade execution consumers, broker client statuses, and live
 * positions.
 */
@RestController
@RequestMapping("/api/v1/execution")
public class ExecutionController {

    private final TradeConsumerManager consumerManager;
    private final ShoonyaBrokerGateway shoonyaGateway;
    private final ZerodhaBrokerGateway zerodhaGateway;

    @Autowired
    public ExecutionController(
            TradeConsumerManager consumerManager,
            ShoonyaBrokerGateway shoonyaGateway,
            ZerodhaBrokerGateway zerodhaGateway) {
        this.consumerManager = consumerManager;
        this.shoonyaGateway = shoonyaGateway;
        this.zerodhaGateway = zerodhaGateway;
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

    /**
     * Fetches current open positions across all active brokers (Cash + Derivatives). Example: GET
     * /api/v1/execution/positions
     */
    @GetMapping("/positions")
    public ResponseEntity<List<BrokerPosition>> getAllPositions() {
        List<BrokerPosition> all = new ArrayList<>();
        if (shoonyaGateway != null) {
            all.addAll(shoonyaGateway.getPositions());
        }
        if (zerodhaGateway != null) {
            all.addAll(zerodhaGateway.getPositions());
        }
        return ResponseEntity.ok(all);
    }

    /**
     * Fetches current open positions for a specific broker ("SHOONYA" or "ZERODHA"). Example: GET
     * /api/v1/execution/positions/ZERODHA
     */
    @GetMapping("/positions/{broker}")
    public ResponseEntity<List<BrokerPosition>> getBrokerPositions(@PathVariable String broker) {
        String b = broker != null ? broker.toUpperCase().trim() : "";
        if ("SHOONYA".equals(b)) {
            return ResponseEntity.ok(
                    shoonyaGateway != null ? shoonyaGateway.getPositions() : List.of());
        } else if ("ZERODHA".equals(b)) {
            return ResponseEntity.ok(
                    zerodhaGateway != null ? zerodhaGateway.getPositions() : List.of());
        } else {
            return ResponseEntity.badRequest().build();
        }
    }
}
