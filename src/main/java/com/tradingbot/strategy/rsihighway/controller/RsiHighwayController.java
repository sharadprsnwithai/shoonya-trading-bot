package com.tradingbot.strategy.rsihighway.controller;

import com.tradingbot.strategy.rsihighway.config.RsiHighwayConfig;
import com.tradingbot.strategy.rsihighway.model.MarketBreadthSnapshot;
import com.tradingbot.strategy.rsihighway.model.RsiHighwayPosition;
import com.tradingbot.strategy.rsihighway.model.RsiHighwayState;
import com.tradingbot.strategy.rsihighway.service.RsiHighwaySwingService;
import com.tradingbot.util.Nifty500Registry;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for inspecting state, query market breadth, and trigger manual evaluation
 * for the RSI Highway Multi-Timeframe Strategy.
 */
@RestController
@RequestMapping("/api/strategy/rsi-highway")
public class RsiHighwayController {

    private final RsiHighwaySwingService swingService;
    private final RsiHighwayConfig config;

    public RsiHighwayController(RsiHighwaySwingService swingService, RsiHighwayConfig config) {
        this.swingService = swingService;
        this.config = config;
    }

    @GetMapping("/state")
    public ResponseEntity<RsiHighwayState> getState() {
        return ResponseEntity.ok(swingService.getState());
    }

    @GetMapping("/positions")
    public ResponseEntity<Map<String, RsiHighwayPosition>> getPositions() {
        return ResponseEntity.ok(swingService.getActivePositions());
    }

    @GetMapping("/closed-positions")
    public ResponseEntity<List<RsiHighwayPosition>> getClosedPositions() {
        return ResponseEntity.ok(swingService.getClosedPositions());
    }

    @GetMapping("/breadth")
    public ResponseEntity<MarketBreadthSnapshot> getBreadth() {
        return ResponseEntity.ok(swingService.getLastBreadthSnapshot());
    }

    @GetMapping("/universe")
    public ResponseEntity<List<String>> getUniverse() {
        return ResponseEntity.ok(Nifty500Registry.getAllSymbols());
    }

    @PostMapping("/scan")
    public ResponseEntity<RsiHighwayState> triggerScan() {
        swingService.evaluateEodScan();
        return ResponseEntity.ok(swingService.getState());
    }

    @PostMapping("/scan-stock")
    public ResponseEntity<RsiHighwayState> triggerScanStock(@RequestParam("symbol") String symbol) {
        if (symbol != null && !symbol.isBlank()) {
            swingService.evaluateEodScanForSymbols(List.of(symbol.toUpperCase(Locale.ROOT).trim()));
        }
        return ResponseEntity.ok(swingService.getState());
    }

    @PostMapping("/morning-check")
    public ResponseEntity<RsiHighwayState> triggerMorningCheck() {
        swingService.evaluateMorningPlungeCheck();
        return ResponseEntity.ok(swingService.getState());
    }
}
