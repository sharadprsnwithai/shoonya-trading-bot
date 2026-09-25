package com.tradingbot.controller;

import com.tradingbot.bus.SignalPublisher;
import com.tradingbot.strategy.TradeSignal;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST controller for manual signal emission and testing reactive distribution. */
@RestController
@RequestMapping("/api/v1/signals")
public class SignalController {

    private final SignalPublisher signalPublisher;

    public SignalController(SignalPublisher signalPublisher) {
        this.signalPublisher = signalPublisher;
    }

    /** Broadcast a trade signal to all subscribed consumers. Example: POST /api/v1/signals/test */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> publishTestSignal(@RequestBody TradeSignal signal) {
        boolean emitted = signalPublisher.publish(signal);
        return ResponseEntity.ok(
                Map.of(
                        "success",
                        emitted,
                        "signalId",
                        signal != null ? signal.signalId() : "null",
                        "message",
                        emitted
                                ? "Signal broadcasted to all consumers"
                                : "Signal rejected (inactive / non-actionable)"));
    }
}
