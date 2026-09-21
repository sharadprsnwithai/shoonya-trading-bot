package com.tradingbot.positional.controller;

import com.tradingbot.positional.config.PositionalStrategyConfig;
import com.tradingbot.positional.model.PositionalState;
import com.tradingbot.positional.service.BollingerHaPositionalService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST Controller for querying and controlling the Positional Trading Strategy. */
@RestController
@RequestMapping("/api/positional")
public class BollingerHaPositionalController {

    private final BollingerHaPositionalService positionalService;
    private final PositionalStrategyConfig positionalConfig;

    public BollingerHaPositionalController(
            BollingerHaPositionalService positionalService,
            PositionalStrategyConfig positionalConfig) {
        this.positionalService = positionalService;
        this.positionalConfig = positionalConfig;
    }

    @GetMapping("/status")
    public ResponseEntity<PositionalState> getStatus() {
        return ResponseEntity.ok(positionalService.getState());
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        return ResponseEntity.ok(
                Map.of(
                        "summary", positionalService.getSummaryStatus(),
                        "mode", positionalConfig.getExecutionMode(),
                        "symbol", positionalConfig.getSymbol(),
                        "state", positionalService.getState()));
    }

    @PostMapping("/scan")
    public ResponseEntity<PositionalState> triggerScan() {
        positionalService.scanAndEvaluate();
        return ResponseEntity.ok(positionalService.getState());
    }

    @PostMapping("/approve")
    public ResponseEntity<Map<String, Object>> approveTrade() {
        boolean success = positionalService.approveStagedTrade();
        return ResponseEntity.ok(
                Map.of(
                        "success",
                        success,
                        "message",
                        success ? "Trade approved and executed." : "No staged trade to approve.",
                        "state",
                        positionalService.getState()));
    }

    @PostMapping("/reject")
    public ResponseEntity<Map<String, Object>> rejectTrade() {
        boolean success = positionalService.rejectStagedTrade();
        return ResponseEntity.ok(
                Map.of(
                        "success",
                        success,
                        "message",
                        success
                                ? "Trade rejected and reset to FLAT."
                                : "No staged trade to reject.",
                        "state",
                        positionalService.getState()));
    }

    @PostMapping("/exit")
    public ResponseEntity<Map<String, Object>> forceExit(
            @RequestParam(name = "reason", defaultValue = "MANUAL_REST_EXIT") String reason) {
        positionalService.forceExitCurrentPosition(reason);
        return ResponseEntity.ok(
                Map.of(
                        "success",
                        true,
                        "message",
                        "Exit initiated with reason: " + reason,
                        "state",
                        positionalService.getState()));
    }

    @PostMapping("/mode")
    public ResponseEntity<Map<String, Object>> updateExecutionMode(
            @RequestParam("mode") String mode) {
        String upper = mode.trim().toUpperCase();
        if ("AUTO".equals(upper) || "MANUAL_CONFIRMATION".equals(upper) || "MANUAL".equals(upper)) {
            positionalConfig.setExecutionMode(upper);
            return ResponseEntity.ok(
                    Map.of(
                            "success",
                            true,
                            "executionMode",
                            positionalConfig.getExecutionMode(),
                            "message",
                            "Execution mode updated successfully."));
        } else {
            return ResponseEntity.badRequest()
                    .body(
                            Map.of(
                                    "success",
                                    false,
                                    "message",
                                    "Invalid execution mode. Choose AUTO or MANUAL_CONFIRMATION."));
        }
    }
}
