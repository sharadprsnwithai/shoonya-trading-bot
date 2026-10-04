package com.tradingbot.strategy.bollingerha.controller;

import com.tradingbot.strategy.bollingerha.config.BollingerHaProperties;
import com.tradingbot.strategy.bollingerha.scheduler.BollingerHaScheduler;
import com.tradingbot.strategy.bollingerha.service.BollingerHaIntradayEngine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST controller for monitoring and managing the Bollinger HA Intraday Strategy. */
@RestController
@RequestMapping("/api/v1/strategy/bollinger-ha")
public class BollingerHaController {

    private final BollingerHaIntradayEngine engine;
    private final BollingerHaScheduler scheduler;
    private final BollingerHaProperties properties;

    @Autowired
    public BollingerHaController(
            BollingerHaIntradayEngine engine,
            BollingerHaScheduler scheduler,
            BollingerHaProperties properties) {
        this.engine = engine;
        this.scheduler = scheduler;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("strategyId", BollingerHaIntradayEngine.STRATEGY_ID);
        resp.put("enabled", properties.isEnabled());
        resp.put("underlying", properties.getUnderlying());
        resp.put("activePosition", engine.getActivePosition());
        resp.put("dailyState", engine.getDailyState());
        resp.put("setupStates", engine.getSetupStates());
        resp.put("currentStrikes", scheduler.getCurrentStrikes());
        resp.put("timestamp", Instant.now());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/select-strikes")
    public ResponseEntity<Map<String, Object>> triggerStrikeSelection() {
        scheduler.runPreMarketStrikeSelection();
        Map<String, Object> resp = new HashMap<>();
        resp.put("message", "Strike selection completed");
        resp.put("strikes", scheduler.getCurrentStrikes());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/square-off")
    public ResponseEntity<Map<String, Object>> squareOff(
            @RequestParam(required = false, defaultValue = "Manual REST API Square-Off")
                    String reason) {
        engine.squareOffAll(reason);
        Map<String, Object> resp = new HashMap<>();
        resp.put("message", "Square-off command executed successfully");
        resp.put("reason", reason);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetDailyState() {
        if (!engine.resetDailyState()) {
            Map<String, Object> conflict = new HashMap<>();
            conflict.put("message", "Daily state not reset — square off the open position first");
            conflict.put("activePosition", engine.getActivePosition());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(conflict);
        }
        Map<String, Object> resp = new HashMap<>();
        resp.put("message", "Daily state reset successfully");
        return ResponseEntity.ok(resp);
    }

    /** Re-enables the strategy so the next live event can open a trade again. */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startStrategy() {
        properties.setEnabled(true);
        Map<String, Object> resp = new HashMap<>();
        resp.put("message", "Bollinger HA strategy enabled");
        resp.put("enabled", true);
        resp.put("activePosition", engine.getActivePosition());
        return ResponseEntity.ok(resp);
    }

    /**
     * Flattens any open position and then disables the strategy — stop first, so disabling never
     * leaves a live trade unmanaged.
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stopStrategy() {
        boolean squaredOff = false;
        if (engine.getActivePosition() != null) {
            engine.squareOffAll("Manual REST API Stop — strategy disabled");
            squaredOff = true;
        }
        properties.setEnabled(false);
        Map<String, Object> resp = new HashMap<>();
        resp.put("message", "Bollinger HA strategy stopped");
        resp.put("enabled", false);
        resp.put("squaredOff", squaredOff);
        return ResponseEntity.ok(resp);
    }

    /**
     * Injects a synthetic tick into the engine so the target/stop management path can be exercised
     * without waiting for live prices.
     *
     * @param token the contract token the tick belongs to
     * @param ltp the simulated last traded price
     */
    @PostMapping("/simulate")
    public ResponseEntity<Map<String, Object>> simulateTick(
            @RequestParam String token, @RequestParam BigDecimal ltp) {
        engine.onTick(token, ltp, Instant.now());
        Map<String, Object> resp = new HashMap<>();
        resp.put("message", "Simulated tick applied");
        resp.put("token", token);
        resp.put("ltp", ltp);
        resp.put("activePosition", engine.getActivePosition());
        resp.put("dailyState", engine.getDailyState());
        return ResponseEntity.ok(resp);
    }
}
