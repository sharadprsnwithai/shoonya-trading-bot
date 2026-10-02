package com.tradingbot.strategy.condor.controller;

import com.tradingbot.strategy.condor.model.PutCondorCycleHistory;
import com.tradingbot.strategy.condor.model.PutCondorPosition;
import com.tradingbot.strategy.condor.model.PutCondorState;
import com.tradingbot.strategy.condor.repository.SqlitePutCondorRepository;
import com.tradingbot.strategy.condor.scheduler.MonthlyPutCondorScheduler;
import com.tradingbot.strategy.condor.service.MonthlyPutCondorService;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST API for monitoring and managing the Monthly Asymmetric Put Condor strategy. */
@RestController
@RequestMapping("/api/strategy/put-condor")
public class MonthlyPutCondorController {

    private static final Logger log = LoggerFactory.getLogger(MonthlyPutCondorController.class);

    private final MonthlyPutCondorService condorService;
    private final SqlitePutCondorRepository repository;
    private final MonthlyPutCondorScheduler scheduler;

    @Autowired
    public MonthlyPutCondorController(
            MonthlyPutCondorService condorService,
            SqlitePutCondorRepository repository,
            MonthlyPutCondorScheduler scheduler) {
        this.condorService = condorService;
        this.repository = repository;
        this.scheduler = scheduler;
    }

    /** Returns current active Put Condor position and MTM state. */
    @GetMapping("/status")
    public ResponseEntity<?> getStatus() {
        PutCondorPosition active = condorService.getActivePosition();
        if (active == null) {
            return ResponseEntity.ok(
                    Map.of("state", PutCondorState.IDLE.name(), "message", "No active position"));
        }
        return ResponseEntity.ok(active);
    }

    /** Manually forces deployment of the Put Condor cycle at current or specified spot price. */
    @PostMapping("/enter")
    public ResponseEntity<?> enterCycle(
            @RequestBody(required = false) Map<String, Object> payload) {
        BigDecimal spot = null;
        if (payload != null && payload.containsKey("spotPrice")) {
            spot = new BigDecimal(payload.get("spotPrice").toString());
        }
        if (spot == null || spot.compareTo(BigDecimal.ZERO) <= 0) {
            spot = scheduler.fetchNiftySpotPrice();
        }

        log.info("[REST API] Triggering manual Put Condor cycle entry at spot ₹{}", spot);
        boolean success = condorService.evaluateAndEnterCycle(spot);
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put(
                "message",
                success ? "Put Condor deployed successfully" : "Failed to deploy Put Condor");
        if (condorService.getActivePosition() != null) {
            response.put("position", condorService.getActivePosition());
        }
        if (success) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }

    /** Manually triggers Adjustment A (Upside Bull Put Spread). */
    @PostMapping("/adjust-upside")
    public ResponseEntity<?> triggerUpsideAdjustment(@RequestParam(required = false) Double spot) {
        BigDecimal spotPrice =
                spot != null ? BigDecimal.valueOf(spot) : scheduler.fetchNiftySpotPrice();
        log.info("[REST API] Manually triggering Adjustment A at spot ₹{}", spotPrice);
        condorService.triggerUpsideAdjustment(spotPrice);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Adjustment A executed");
        if (condorService.getActivePosition() != null) {
            response.put("position", condorService.getActivePosition());
        }
        return ResponseEntity.ok(response);
    }

    /** Manually triggers Adjustment B (Sweet Spot Roll). */
    @PostMapping("/adjust-sweet-spot")
    public ResponseEntity<?> triggerSweetSpotRoll(@RequestParam(required = false) Double spot) {
        BigDecimal spotPrice =
                spot != null ? BigDecimal.valueOf(spot) : scheduler.fetchNiftySpotPrice();
        log.info("[REST API] Manually triggering Adjustment B at spot ₹{}", spotPrice);
        condorService.triggerSweetSpotRoll(spotPrice);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Adjustment B executed");
        if (condorService.getActivePosition() != null) {
            response.put("position", condorService.getActivePosition());
        }
        return ResponseEntity.ok(response);
    }

    /** Emergency square-off of all active Put Condor legs. */
    @PostMapping("/exit")
    public ResponseEntity<?> emergencyExit(
            @RequestParam(defaultValue = "MANUAL_REST_EXIT") String reason) {
        log.warn("[REST API] Emergency liquidation triggered: reason={}", reason);
        condorService.squareOffAll(reason);
        return ResponseEntity.ok(
                Map.of("success", true, "message", "All positions squared off successfully"));
    }

    /** Returns historical completed cycle records. */
    @GetMapping("/history")
    public ResponseEntity<?> getHistory(@RequestParam(defaultValue = "20") int limit) {
        List<PutCondorCycleHistory> history = repository.getHistory(limit);
        return ResponseEntity.ok(
                Map.of("success", true, "count", history.size(), "history", history));
    }
}
