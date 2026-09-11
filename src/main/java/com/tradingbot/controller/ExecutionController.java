package com.tradingbot.controller;

import com.tradingbot.execution.ExecutionManager;
import com.tradingbot.model.execution.ActiveSpreadPosition;
import com.tradingbot.model.execution.ExecutionMode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for managing live / paper option spread executions, protective hedge legs, and
 * broker-level SL-L orders.
 */
@RestController
@RequestMapping("/api/v1/execution")
public class ExecutionController {

    private final ExecutionManager executionManager;

    public ExecutionController(ExecutionManager executionManager) {
        this.executionManager = executionManager;
    }

    /** Get current trade execution mode (PAPER vs LIVE). Example: GET /api/v1/execution/mode */
    @GetMapping("/mode")
    public ResponseEntity<Map<String, Object>> getExecutionMode() {
        return ResponseEntity.ok(
                Map.of(
                        "mode",
                        executionManager.getExecutionMode(),
                        "description",
                        executionManager.getExecutionMode() == ExecutionMode.LIVE
                                ? "LIVE: Orders placed to real broker Shoonya"
                                : "PAPER: Simulated paper trades with live price fills"));
    }

    /** Switch execution mode (PAPER vs LIVE). Example: POST /api/v1/execution/mode?mode=PAPER */
    @PostMapping("/mode")
    public ResponseEntity<Map<String, Object>> setExecutionMode(@RequestParam ExecutionMode mode) {
        executionManager.setExecutionMode(mode);
        return ResponseEntity.ok(
                Map.of("mode", executionManager.getExecutionMode(), "status", "SUCCESS"));
    }

    /**
     * Execute a Directional Option Selling Spread with: 1. Protective deep OTM Hedge Leg (margin
     * reduction & tail risk cap). 2. Short ATM Option Leg. 3. Instant broker-level SL-L (Stop-Loss
     * Limit) order. Example: POST
     * /api/v1/execution/trade?optionType=PE&strikePrice=24000&buyHedge=true
     */
    @PostMapping("/trade")
    public ResponseEntity<ActiveSpreadPosition> executeOptionSellingTrade(
            @RequestParam(defaultValue = "DIRECTIONAL_OPTION_SELLING") String strategyId,
            @RequestParam(defaultValue = "NIFTY") String underlying,
            @RequestParam String optionType, // "PE" or "CE"
            @RequestParam BigDecimal strikePrice,
            @RequestParam(defaultValue = "65") int quantity,
            @RequestParam(defaultValue = "true") boolean buyHedge) {
        ActiveSpreadPosition position =
                executionManager.executeDirectionalOptionSelling(
                        strategyId, underlying, optionType, strikePrice, quantity, buyHedge);
        return ResponseEntity.ok(position);
    }

    /**
     * Close an active option spread position. Cancels broker-level SL-L order, buys back short
     * option, and sells hedge. Example: POST /api/v1/execution/close/TRD_1?reason=SUPERTREND_FLIP
     */
    @PostMapping("/close/{tradeId}")
    public ResponseEntity<ActiveSpreadPosition> closeSpreadPosition(
            @PathVariable String tradeId,
            @RequestParam(defaultValue = "MANUAL_CLOSE") String reason) {
        ActiveSpreadPosition closed = executionManager.closeSpreadPosition(tradeId, reason);
        return ResponseEntity.ok(closed);
    }

    /** List all open and closed spread positions. Example: GET /api/v1/execution/positions */
    @GetMapping("/positions")
    public ResponseEntity<List<ActiveSpreadPosition>> getAllPositions() {
        return ResponseEntity.ok(executionManager.getAllPositions());
    }

    /** List only currently open spread positions. Example: GET /api/v1/execution/positions/open */
    @GetMapping("/positions/open")
    public ResponseEntity<List<ActiveSpreadPosition>> getOpenPositions() {
        return ResponseEntity.ok(executionManager.getOpenPositions());
    }
}
