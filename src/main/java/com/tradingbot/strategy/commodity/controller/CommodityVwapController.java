package com.tradingbot.strategy.commodity.controller;

import com.tradingbot.strategy.commodity.model.CommodityStatusReport;
import com.tradingbot.strategy.commodity.service.CommodityVwapStrategyService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST controller for monitoring and manually managing the 1:30 PM MCX Commodity VWAP Strategy. */
@RestController
@RequestMapping("/api/v1/strategy/commodity-vwap")
public class CommodityVwapController {

    private final CommodityVwapStrategyService strategyService;

    public CommodityVwapController(CommodityVwapStrategyService strategyService) {
        this.strategyService = strategyService;
    }

    /** Returns the current real-time status, biases, setups, and open positions. */
    @GetMapping("/status")
    public ResponseEntity<CommodityStatusReport> getStatus() {
        return ResponseEntity.ok(strategyService.getStatusReport());
    }

    /** Triggers an immediate 15-minute strategy evaluation cycle across all symbols. */
    @PostMapping("/scan")
    public ResponseEntity<CommodityStatusReport> triggerScan() {
        strategyService.evaluateStrategyCycle();
        return ResponseEntity.ok(strategyService.getStatusReport());
    }

    /** Triggers an immediate 1:30 PM style PCR directional bias determination. */
    @PostMapping("/bias")
    public ResponseEntity<CommodityStatusReport> triggerBiasEvaluation() {
        strategyService.evaluateDailyBias();
        return ResponseEntity.ok(strategyService.getStatusReport());
    }

    /** Manually triggers square-off for all active commodity positions. */
    @PostMapping("/square-off")
    public ResponseEntity<CommodityStatusReport> squareOff(
            @RequestParam(defaultValue = "MANUAL_API_SQUARE_OFF") String reason) {
        strategyService.squareOffAllPositions(reason);
        return ResponseEntity.ok(strategyService.getStatusReport());
    }

    /** Resets the intraday session state for all commodities. */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetSession(
            @RequestParam(defaultValue = "false") boolean force) {
        strategyService.resetSession(force);
        return ResponseEntity.ok(
                Map.of(
                        "status",
                        "SUCCESS",
                        "message",
                        "Commodity strategy session reset successfully."));
    }
}
