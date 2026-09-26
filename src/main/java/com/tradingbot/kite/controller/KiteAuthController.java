package com.tradingbot.kite.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.kite.auth.KiteAuthService;
import com.tradingbot.kite.client.KiteRestClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

@RestController
@RequestMapping("/api/v1/kite")
public class KiteAuthController {

    private static final Logger log = LoggerFactory.getLogger(KiteAuthController.class);

    private final KiteAuthService authService;
    private final KiteRestClient restClient;

    public KiteAuthController(KiteAuthService authService, KiteRestClient restClient) {
        this.authService = authService;
        this.restClient = restClient;
    }

    @GetMapping("/login-url")
    public ResponseEntity<Map<String, String>> loginUrl() {
        return ResponseEntity.ok(Map.of("loginUrl", authService.loginUrl()));
    }

    @GetMapping("/status")
    public ResponseEntity<KiteAuthService.KiteStatus> status() {
        return ResponseEntity.ok(authService.status());
    }

    @PostMapping("/auto-login")
    public ResponseEntity<KiteAuthService.KiteStatus> autoLogin() {
        return ResponseEntity.ok(authService.performAutoLogin());
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout() {
        authService.logout();
        return ResponseEntity.ok(
                Map.of("success", true, "message", "Kite session cleared successfully"));
    }

    @GetMapping("/auth/callback")
    public RedirectView callback(
            @RequestParam(value = "request_token", required = false) String requestToken,
            @RequestParam(value = "status", required = false) String status) {
        if (requestToken == null || requestToken.isBlank()) {
            log.warn("[KITE-CALLBACK] Callback received without request_token (status: {})", status);
            return new RedirectView("http://localhost:3000?kite=cancelled");
        }
        try {
            KiteAuthService.KiteStatus res = authService.exchangeRequestToken(requestToken);
            return new RedirectView("http://localhost:3000?kite=" + res.status().toLowerCase());
        } catch (Exception e) {
            log.error("[KITE-CALLBACK] Failed exchanging request token: {}", e.getMessage(), e);
            return new RedirectView("http://localhost:3000?kite=error");
        }
    }

    @GetMapping("/positions")
    public ResponseEntity<List<Map<String, Object>>> positions() {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            JsonNode data = restClient.positions();
            if (data != null && data.isArray()) {
                for (JsonNode row : data) {
                    long qty = row.path("quantity").asLong();
                    if (qty != 0) {
                        list.add(
                                Map.of(
                                        "symbol", row.path("tradingsymbol").asText(),
                                        "exchange", row.path("exchange").asText(),
                                        "product", row.path("product").asText(),
                                        "quantity", qty,
                                        "averagePrice", row.path("average_price").asDouble(),
                                        "lastPrice", row.path("last_price").asDouble(),
                                        "pnl", row.path("pnl").asDouble()));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[KITE-REST] Could not fetch Kite positions: {}", e.getMessage());
        }
        return ResponseEntity.ok(list);
    }
}
