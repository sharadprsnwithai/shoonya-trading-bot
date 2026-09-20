package com.tradingbot.strategy.kiss.controller;

import com.tradingbot.strategy.kiss.model.KissPosition;
import com.tradingbot.strategy.kiss.model.KissSignal;
import com.tradingbot.strategy.kiss.model.KissState;
import com.tradingbot.strategy.kiss.service.KissSwingService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST Management endpoints for the KISS Multi-Timeframe Strategy. */
@RestController
@RequestMapping("/api/v1/kiss")
public class KissController {

    private final KissSwingService swingService;

    public KissController(KissSwingService swingService) {
        this.swingService = swingService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        KissState state = swingService.getState();
        return ResponseEntity.ok(
                Map.of(
                        "totalEquity", state.getTotalPortfolioEquity(),
                        "availableCapital", state.getAvailableCapital(),
                        "activePositionsCount", state.getPositions().size(),
                        "closedPositionsCount", state.getClosedPositions().size(),
                        "lastScanTime",
                                state.getLastScanTime() != null
                                        ? state.getLastScanTime().toString()
                                        : "NEVER",
                        "positions", state.getPositions(),
                        "recentSignals", state.getRecentSignals()));
    }

    @PostMapping("/scan")
    public ResponseEntity<List<KissSignal>> triggerScan() {
        List<KissSignal> signals = swingService.scanAndExecute();
        return ResponseEntity.ok(signals);
    }

    @GetMapping("/evaluate/{symbol}")
    public ResponseEntity<?> evaluateSymbol(@PathVariable String symbol) {
        KissSignal signal = swingService.evaluateSymbol(symbol.toUpperCase());
        if (signal != null) {
            return ResponseEntity.ok(signal);
        }
        return ResponseEntity.ok(
                Map.of("symbol", symbol.toUpperCase(), "status", "NO_ACTIVE_SETUP"));
    }

    @PostMapping("/positions/close/{symbol}")
    public ResponseEntity<?> closePosition(@PathVariable String symbol) {
        KissPosition pos = swingService.getState().getPositions().get(symbol.toUpperCase());
        if (pos == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No active position for " + symbol));
        }
        swingService.manageOpenPositions();
        return ResponseEntity.ok(Map.of("message", "Position evaluated/closed for " + symbol));
    }
}
