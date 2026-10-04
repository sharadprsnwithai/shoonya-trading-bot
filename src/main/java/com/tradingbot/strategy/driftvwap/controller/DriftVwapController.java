package com.tradingbot.strategy.driftvwap.controller;

import com.tradingbot.strategy.driftvwap.DriftVwapOptionSellingService;
import com.tradingbot.strategy.driftvwap.model.DriftVwapPosition;
import com.tradingbot.strategy.driftvwap.model.DriftVwapTrendState;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/drift-vwap")
public class DriftVwapController {

    private final DriftVwapOptionSellingService service;

    public DriftVwapController(DriftVwapOptionSellingService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> resp = new LinkedHashMap<>();
        DriftVwapTrendState trend = service.getLatestTrendState();
        DriftVwapPosition openPos = service.getOpenPosition();

        resp.put("status", "SUCCESS");
        resp.put("strategy", DriftVwapOptionSellingService.STRATEGY_ID);
        resp.put("trend", trend);
        resp.put("openPosition", openPos);
        resp.put("todayTradesCount", service.getTodayTradesCount().get());
        resp.put("todayLossCount", service.getTodayLossCount().get());
        resp.put("tradeHistoryCount", service.getTradeHistory().size());

        return ResponseEntity.ok(resp);
    }

    @PostMapping("/run-cycle")
    public ResponseEntity<Map<String, Object>> runCycle() {
        service.runCycle();
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "5-minute Drift VWAP cycle triggered"));
    }

    @PostMapping("/exit")
    public ResponseEntity<Map<String, Object>> hardExit() {
        service.executeHardExit();
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Hard exit executed for Drift VWAP"));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetDaily() {
        service.resetDaily();
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "Daily session state reset"));
    }
}
