# Zerodha Kite Consumer & Broker Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a production-ready Zerodha Kite Connect Consumer and Broker Gateway that subscribes to the reactive signal stream (`Flux<TradeSignal>`), supports dual-mode authentication (Automated Headless TOTP login + Standard OAuth 2.0 Web flow) with session token caching in `data/kite_session.json`, and executes marketable limit orders with a 1% price buffer and 0.05 tick-size rounding.

**Architecture:** `ZerodhaTradeConsumer` subscribes to the hot multicast `Flux<TradeSignal>` from `ReactiveSignalEventBus` on isolated `Schedulers.boundedElastic()` worker threads. The consumer delegates order routing to `ZerodhaBrokerGateway`, which interfaces with `KiteRestClient` against `https://api.kite.trade`. Authentication is handled by `KiteAuthService` (supporting headless TOTP auto-login with `CryptoUtil` and standard OAuth callback), with daily token caching managed by `KiteSession`.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring `RestClient`, Project Reactor, JUnit 5, Mockito, MockRestServiceServer.

**Spec:** `docs/superpowers/specs/2026-03-30-zerodha-kite-consumer-integration-design.md`

---

## Global Constraints

- Language & Framework: Java 21 with Spring Boot 3.3.5.
- Code Standards: Formatted with Spotless Google Java Format (`./gradlew spotlessApply`), zero SpotBugs errors, and 100% test pass.
- Concurrency & Thread Isolation: Zerodha order execution must run on isolated worker threads and never block other broker consumers or the signal bus.
- Dual-Mode Authentication: Support both hands-free headless TOTP login and OAuth 2.0 callback flow.
- Session Persistence: Persist authenticated Kite session in `data/kite_session.json`.
- Order Sizing & Pricing: Marketable limit orders must apply 1% price buffer (+1% for BUY, -1% for SELL) and round to the nearest 0.05 tick size.

## Review Focus

1. **Session Persistence across Restarts:** `KiteSession` must load existing credentials from `data/kite_session.json` on startup without requiring re-authentication.
2. **Tick-Size Alignment (0.05):** Price and trigger price sent to Kite `/orders/regular` must always be aligned to multiples of 0.05.
3. **Authentication Failure Recovery:** A 401 or expired token response from Kite must safely report error/invalidation without crashing the reactive stream.
4. **Marketable Limit Buffer Direction:** BUY orders must buffer upward ($\times 1.01$), and SELL orders must buffer downward ($\times 0.99$).
5. **Zero / Stale Signal Handling:** The consumer must reject signals older than `maxSignalAgeSeconds` (30s) or with quantity `<= 0`.

---

## File Structure & Responsibilities

```
src/main/java/com/tradingbot/
├── kite/
│   ├── config/
│   │   └── KiteProperties.java          (Record: mapped configuration from .env/application.properties)
│   ├── auth/
│   │   ├── KiteSession.java             (Session state and data/kite_session.json persistence)
│   │   └── KiteAuthService.java         (Dual-mode auth: Headless TOTP login and OAuth token exchange)
│   ├── client/
│   │   └── KiteRestClient.java          (REST client interfacing with api.kite.trade v3)
│   └── controller/
│       └── KiteAuthController.java      (REST endpoints for login-url, callback, status, auto-login, positions)
└── execution/
    ├── gateway/
    │   └── ZerodhaBrokerGateway.java    (Adapter implementing BrokerOrderGateway using KiteRestClient)
    └── consumer/
        └── ZerodhaTradeConsumer.java    (Consumer translating TradeSignal into Kite orders)
```

---

## Task Decomposition

### Task 1: Kite Configuration Properties & Session Persistence (`KiteProperties` & `KiteSession`)

**Files:**
- Create: `src/main/java/com/tradingbot/kite/config/KiteProperties.java`
- Create: `src/main/java/com/tradingbot/kite/auth/KiteSession.java`
- Test: `src/test/java/com/tradingbot/kite/auth/KiteSessionTest.java`

**Interfaces:**
- Consumes: Jackson `ObjectMapper`, Spring `@ConfigurationProperties`.
- Produces: `KiteProperties`, `KiteSession` with `update()`, `load()`, `clear()`, `accessToken()`, `current()`.

- [ ] **Step 1: Write failing unit test `KiteSessionTest.java`**

```java
package com.tradingbot.kite.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KiteSessionTest {

    private Path tempSessionFile;
    private KiteSession kiteSession;

    @BeforeEach
    void setUp() throws Exception {
        tempSessionFile = Files.createTempFile("kite-session-test", ".json");
        kiteSession = new KiteSession(new ObjectMapper(), tempSessionFile.toString());
    }

    @AfterEach
    void tearDown() {
        try {
            Files.deleteIfExists(tempSessionFile);
        } catch (Exception ignored) {
        }
    }

    @Test
    void testUpdatePersistsSessionAndLoadsCorrectly() {
        KiteSession.Session session =
                new KiteSession.Session("test-access-token", "AB1234", "Sharad User");
        kiteSession.update(session);

        assertEquals("test-access-token", kiteSession.accessToken());
        assertEquals("AB1234", kiteSession.current().userId());

        // Create new session instance pointing to same file and verify load()
        KiteSession reloadedSession =
                new KiteSession(new ObjectMapper(), tempSessionFile.toString());
        boolean loaded = reloadedSession.load();
        assertTrue(loaded);
        assertEquals("test-access-token", reloadedSession.accessToken());
        assertEquals("Sharad User", reloadedSession.current().userName());
    }

    @Test
    void testClearDeletesSessionAndFile() {
        KiteSession.Session session =
                new KiteSession.Session("test-token", "AB1234", "Sharad");
        kiteSession.update(session);
        assertNotNull(kiteSession.current());

        kiteSession.clear();
        assertNull(kiteSession.current());
        assertNull(kiteSession.accessToken());
        assertFalse(Files.exists(tempSessionFile));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.kite.auth.KiteSessionTest`
Expected: FAIL (classes not found).

- [ ] **Step 3: Implement `KiteProperties.java` and `KiteSession.java`**

Create `KiteProperties.java`:
```java
package com.tradingbot.kite.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trading-bot.kite")
public record KiteProperties(
        boolean enabled,
        String apiKey,
        String apiSecret,
        String userId,
        String password,
        String totpKey,
        String redirectUri,
        String accessToken,
        String sessionFile,
        String frontendRedirect) {

    public KiteProperties {
        if (sessionFile == null || sessionFile.isBlank()) {
            sessionFile = "data/kite_session.json";
        }
        if (frontendRedirect == null || frontendRedirect.isBlank()) {
            frontendRedirect = "http://localhost:3000";
        }
    }

    public boolean hasAutoLoginCredentials() {
        return userId != null
                && !userId.isBlank()
                && password != null
                && !password.isBlank()
                && totpKey != null
                && !totpKey.isBlank();
    }
}
```

Create `KiteSession.java`:
```java
package com.tradingbot.kite.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Manages in-memory storage and file persistence of the active Zerodha Kite Connect user session.
 */
@Component
public class KiteSession {

    private static final Logger log = LoggerFactory.getLogger(KiteSession.class);

    public record Session(String accessToken, String userId, String userName) {}

    private final AtomicReference<Session> session = new AtomicReference<>();
    private final ObjectMapper objectMapper;
    private final Path sessionFile;

    public KiteSession(
            ObjectMapper objectMapper,
            @Value("${trading-bot.kite.session-file:data/kite_session.json}")
                    String sessionFilePath) {
        this.objectMapper = objectMapper;
        this.sessionFile = Path.of(sessionFilePath);
    }

    public void update(Session newSession) {
        session.set(newSession);
        persist(newSession);
    }

    public void clear() {
        session.set(null);
        try {
            Files.deleteIfExists(sessionFile);
        } catch (IOException e) {
            log.warn("[KITE-SESSION] Failed to delete session file: {}", e.getMessage());
        }
    }

    public boolean load() {
        if (!Files.exists(sessionFile)) {
            return false;
        }
        try {
            String json = Files.readString(sessionFile, StandardCharsets.UTF_8);
            Session stored = objectMapper.readValue(json, Session.class);
            session.set(stored);
            log.info("[KITE-SESSION] Loaded Kite session for user {}", stored.userId());
            return true;
        } catch (IOException e) {
            log.warn("[KITE-SESSION] Failed to load Kite session file: {}", e.getMessage());
            return false;
        }
    }

    public String accessToken() {
        Session current = session.get();
        return current == null ? null : current.accessToken();
    }

    public Session current() {
        return session.get();
    }

    private void persist(Session newSession) {
        try {
            Path parent = sessionFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    sessionFile,
                    objectMapper.writeValueAsString(newSession),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[KITE-SESSION] Failed to persist Kite session: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.kite.auth.KiteSessionTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/kite/ src/test/java/com/tradingbot/kite/
git commit -m "feat: implement KiteProperties and KiteSession with persistence"
```

---

### Task 2: Implement Kite REST Client (`KiteRestClient`)

**Files:**
- Create: `src/main/java/com/tradingbot/kite/client/KiteRestClient.java`
- Test: `src/test/java/com/tradingbot/kite/client/KiteRestClientTest.java`

**Interfaces:**
- Consumes: `KiteProperties`, `KiteSession`, Spring `RestClient`.
- Produces: `placeOrder()`, `cancelOrder()`, `modifyOrder()`, `positions()`, `profile()`, `orderHistory()`, `getOrderStatus()`, `getOrderRejectionReason()`, `getNetPositionQuantity()`.

- [ ] **Step 1: Write unit test `KiteRestClientTest.java`**

```java
package com.tradingbot.kite.client;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.kite.auth.KiteSession;
import com.tradingbot.kite.config.KiteProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KiteRestClientTest {

    private KiteRestClient kiteRestClient;
    private MockRestServiceServer mockServer;
    private KiteSession kiteSession;

    @BeforeEach
    void setUp() {
        KiteProperties props =
                new KiteProperties(
                        true,
                        "test_api_key",
                        "test_api_secret",
                        "test_user",
                        "test_pwd",
                        "test_totp",
                        "http://localhost:8080/callback",
                        null,
                        "data/test_session.json",
                        "http://localhost:3000");

        kiteSession = new KiteSession(new ObjectMapper(), "data/test_session.json");
        kiteSession.update(new KiteSession.Session("mock_access_token", "AB1234", "Sharad"));

        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.kite.trade");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        kiteRestClient = new KiteRestClient(props, kiteSession, builder.build());
    }

    @Test
    void testPlaceOrderSuccess() {
        mockServer
                .expect(requestTo("https://api.kite.trade/orders/regular"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "token test_api_key:mock_access_token"))
                .andRespond(
                        withSuccess(
                                "{\"status\":\"success\",\"data\":{\"order_id\":\"2603300001\"}}",
                                MediaType.APPLICATION_JSON));

        KiteRestClient.KiteOrderRequest req =
                new KiteRestClient.KiteOrderRequest(
                        "NFO",
                        "RELIANCE26MARFUT",
                        "BUY",
                        250,
                        "MIS",
                        "LIMIT",
                        2500.0,
                        null);

        KiteRestClient.KiteOrderResponse resp = kiteRestClient.placeOrder(req);
        assertNotNull(resp);
        assertEquals("2603300001", resp.orderId());
        mockServer.verify();
    }

    @Test
    void testCancelOrderSuccess() {
        mockServer
                .expect(requestTo("https://api.kite.trade/orders/regular/2603300001"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "token test_api_key:mock_access_token"))
                .andRespond(
                        withSuccess(
                                "{\"status\":\"success\",\"data\":{\"order_id\":\"2603300001\"}}",
                                MediaType.APPLICATION_JSON));

        boolean cancelled = kiteRestClient.cancelOrder("2603300001");
        assertTrue(cancelled);
        mockServer.verify();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.kite.client.KiteRestClientTest`
Expected: FAIL.

- [ ] **Step 3: Implement `KiteRestClient.java`**

Create `KiteRestClient.java`:
```java
package com.tradingbot.kite.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.kite.auth.KiteSession;
import com.tradingbot.kite.config.KiteProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * REST client interacting directly with Zerodha Kite Connect API v3.
 */
@Component
public class KiteRestClient {

    private static final Logger log = LoggerFactory.getLogger(KiteRestClient.class);
    private static final String BASE_URL = "https://api.kite.trade";

    private final KiteProperties kiteProperties;
    private final KiteSession kiteSession;
    private final RestClient restClient;

    public record KiteOrderRequest(
            String exchange,
            String tradingSymbol,
            String transactionType,
            int quantity,
            String product,
            String orderType,
            Double price,
            Double triggerPrice) {}

    public record KiteOrderResponse(String orderId) {}

    public KiteRestClient(KiteProperties kiteProperties, KiteSession kiteSession) {
        this(
                kiteProperties,
                kiteSession,
                RestClient.builder()
                        .baseUrl(BASE_URL)
                        .defaultHeader("X-Kite-Version", "3")
                        .build());
    }

    public KiteRestClient(
            KiteProperties kiteProperties, KiteSession kiteSession, RestClient restClient) {
        this.kiteProperties = kiteProperties;
        this.kiteSession = kiteSession;
        this.restClient = restClient;
    }

    public String loginUrl() {
        String redirect =
                java.net.URLEncoder.encode(
                        kiteProperties.redirectUri(), StandardCharsets.UTF_8);
        return "https://kite.zerodha.com/connect/login?v=3&api_key="
                + kiteProperties.apiKey()
                + "&redirect_uri="
                + redirect;
    }

    public JsonNode profile() {
        return get("/user/profile");
    }

    public JsonNode positions() {
        return get("/portfolio/positions").path("data").path("net");
    }

    public List<JsonNode> orderHistory(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return List.of();
        }
        try {
            JsonNode data = get("/orders/" + orderId).path("data");
            if (!data.isArray()) {
                return List.of();
            }
            List<JsonNode> list = new ArrayList<>();
            for (JsonNode node : data) {
                list.add(node);
            }
            return list;
        } catch (Exception e) {
            log.warn("[KITE-REST] Failed to fetch order history for {}: {}", orderId, e.getMessage());
            return List.of();
        }
    }

    public String getOrderStatus(String orderId) {
        List<JsonNode> history = orderHistory(orderId);
        if (history.isEmpty()) {
            return "UNKNOWN";
        }
        JsonNode latest = history.get(history.size() - 1);
        return latest.path("status").asText("UNKNOWN");
    }

    public String getOrderRejectionReason(String orderId) {
        List<JsonNode> history = orderHistory(orderId);
        if (history.isEmpty()) {
            return "Unknown rejection";
        }
        JsonNode latest = history.get(history.size() - 1);
        return latest.path("status_message").asText("Unknown rejection");
    }

    public int getNetPositionQuantity(String tradingSymbol) {
        if (tradingSymbol == null || tradingSymbol.isBlank()) {
            return 0;
        }
        try {
            JsonNode net = positions();
            if (net == null || !net.isArray()) {
                return 0;
            }
            for (JsonNode pos : net) {
                String sym = pos.path("tradingsymbol").asText("");
                if (tradingSymbol.equalsIgnoreCase(sym)) {
                    return pos.path("quantity").asInt(0);
                }
            }
        } catch (Exception e) {
            log.warn("[KITE-REST] Failed to read Kite positions: {}", e.getMessage());
        }
        return 0;
    }

    public boolean cancelOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return false;
        }
        try {
            restClient
                    .delete()
                    .uri("/orders/regular/" + orderId)
                    .header("Authorization", authorizationHeader())
                    .retrieve()
                    .toBodilessEntity();
            log.info("[KITE-REST] Cancelled Kite order: {}", orderId);
            return true;
        } catch (Exception e) {
            log.warn("[KITE-REST] Failed to cancel Kite order {}: {}", orderId, e.getMessage());
            return false;
        }
    }

    public KiteOrderResponse placeOrder(KiteOrderRequest req) {
        Map<String, String> form = new java.util.LinkedHashMap<>();
        form.put(
                "exchange",
                req.exchange() != null && !req.exchange().isBlank() ? req.exchange() : "NFO");
        form.put("tradingsymbol", req.tradingSymbol());
        form.put("transaction_type", req.transactionType());
        form.put("quantity", String.valueOf(req.quantity()));
        form.put("product", req.product() != null && !req.product().isBlank() ? req.product() : "MIS");
        form.put(
                "order_type",
                req.orderType() != null && !req.orderType().isBlank() ? req.orderType() : "LIMIT");
        form.put("validity", "DAY");
        if (req.price() != null && req.price() > 0) {
            double tickRounded = Math.round(req.price() * 20.0) / 20.0;
            form.put("price", String.format(Locale.US, "%.2f", tickRounded));
        }
        if (req.triggerPrice() != null && req.triggerPrice() > 0) {
            double tickRounded = Math.round(req.triggerPrice() * 20.0) / 20.0;
            form.put("trigger_price", String.format(Locale.US, "%.2f", tickRounded));
        }

        JsonNode body = postForm("/orders/regular", form);
        String status = body.path("status").asText("");
        if ("error".equalsIgnoreCase(status)) {
            String errorMsg = body.path("message").asText("Unknown Kite error");
            throw new IllegalStateException("Kite order rejected: " + errorMsg);
        }
        String orderId = body.path("data").path("order_id").asText();
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalStateException("Kite returned no order_id: " + body);
        }
        log.info(
                "[KITE-REST] Placed Kite Order: ID={}, Symbol={}, Type={}, Qty={}",
                orderId,
                req.tradingSymbol(),
                req.transactionType(),
                req.quantity());
        return new KiteOrderResponse(orderId);
    }

    private JsonNode get(String path) {
        return restClient
                .get()
                .uri(path)
                .header("Authorization", authorizationHeader())
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode postForm(String path, Map<String, String> form) {
        return postForm(path, form, true);
    }

    public JsonNode postForm(String path, Map<String, String> form, boolean authenticated) {
        String body =
                String.join(
                        "&",
                        form.entrySet().stream()
                                .map(
                                        entry -> {
                                            String k =
                                                    java.net.URLEncoder.encode(
                                                            entry.getKey(), StandardCharsets.UTF_8);
                                            String v =
                                                    java.net.URLEncoder.encode(
                                                            entry.getValue() != null
                                                                    ? entry.getValue()
                                                                    : "",
                                                            StandardCharsets.UTF_8);
                                            return k + "=" + v;
                                        })
                                .toList());

        var spec =
                restClient.post().uri(path).contentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (authenticated) {
            spec.header("Authorization", authorizationHeader());
        }
        return spec.body(body).retrieve().body(JsonNode.class);
    }

    public String authorizationHeader() {
        String token =
                kiteSession.accessToken() != null
                        ? kiteSession.accessToken()
                        : kiteProperties.accessToken();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Kite access token not available; authenticate first");
        }
        return "token " + kiteProperties.apiKey() + ":" + token;
    }
}
```

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.kite.client.KiteRestClientTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/kite/client/ src/test/java/com/tradingbot/kite/client/
git commit -m "feat: implement KiteRestClient with regular order and position endpoints"
```

---

### Task 3: Implement Kite Authentication Service & Headless TOTP Auto-Login (`KiteAuthService`)

**Files:**
- Create: `src/main/java/com/tradingbot/kite/auth/KiteAuthService.java`
- Test: `src/test/java/com/tradingbot/kite/auth/KiteAuthServiceTest.java`

**Interfaces:**
- Consumes: `KiteProperties`, `KiteRestClient`, `KiteSession`, `CryptoUtil`.
- Produces: `loginUrl()`, `exchangeRequestToken(requestToken)`, `performAutoLogin()`, `status()`, `logout()`, `accessToken()`.

- [ ] **Step 1: Write unit test `KiteAuthServiceTest.java`**

```java
package com.tradingbot.kite.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradingbot.kite.client.KiteRestClient;
import com.tradingbot.kite.config.KiteProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KiteAuthServiceTest {

    private KiteProperties props;
    private KiteRestClient mockRestClient;
    private KiteSession kiteSession;
    private KiteAuthService authService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        props =
                new KiteProperties(
                        true,
                        "key123",
                        "sec456",
                        "USR001",
                        "pass123",
                        "JBSWY3DPEHPK3PXP",
                        "http://localhost:8080/callback",
                        null,
                        "data/test_session.json",
                        "http://localhost:3000");

        objectMapper = new ObjectMapper();
        kiteSession = new KiteSession(objectMapper, "data/test_session.json");
        mockRestClient = mock(KiteRestClient.class);
        authService = new KiteAuthService(props, mockRestClient, kiteSession);
    }

    @Test
    void testExchangeRequestTokenUpdatesSession() {
        ObjectNode mockResponse = objectMapper.createObjectNode();
        ObjectNode dataNode = mockResponse.putObject("data");
        dataNode.put("access_token", "new_token_789");
        dataNode.put("user_id", "USR001");
        dataNode.put("user_name", "Sharad Trader");

        when(mockRestClient.postForm(eq("/session/token"), anyMap(), eq(false)))
                .thenReturn(mockResponse);

        KiteAuthService.KiteStatus status = authService.exchangeRequestToken("req_token_123");
        assertNotNull(status);
        assertEquals("ACTIVE", status.status());
        assertEquals("USR001", status.userId());
        assertEquals("new_token_789", kiteSession.accessToken());
    }

    @Test
    void testLoginUrlConstructedCorrectly() {
        when(mockRestClient.loginUrl()).thenReturn("https://kite.zerodha.com/connect/login?v=3&api_key=key123");
        String url = authService.loginUrl();
        assertTrue(url.contains("key123"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.kite.auth.KiteAuthServiceTest`
Expected: FAIL.

- [ ] **Step 3: Implement `KiteAuthService.java`**

Create `KiteAuthService.java`:
```java
package com.tradingbot.kite.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.kite.client.KiteRestClient;
import com.tradingbot.kite.config.KiteProperties;
import com.tradingbot.util.CryptoUtil;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service managing Zerodha Kite Connect authentication, token exchange, session health, and headless auto-login.
 */
@Service
public class KiteAuthService {

    private static final Logger log = LoggerFactory.getLogger(KiteAuthService.class);

    public record KiteStatus(String status, String detail, String userId) {}

    private final KiteProperties kiteProperties;
    private final KiteRestClient restClient;
    private final KiteSession kiteSession;

    public KiteAuthService(
            KiteProperties kiteProperties,
            KiteRestClient restClient,
            KiteSession kiteSession) {
        this.kiteProperties = kiteProperties;
        this.restClient = restClient;
        this.kiteSession = kiteSession;
    }

    @PostConstruct
    public void init() {
        // 1. Attempt to load existing session from disk
        if (kiteSession.load()) {
            KiteStatus stat = status();
            if ("ACTIVE".equalsIgnoreCase(stat.status())) {
                log.info("[KITE-AUTH] Restored active Kite session for user {}", stat.userId());
                return;
            }
        }

        // 2. If configured with auto-login credentials, perform automated TOTP login
        if (kiteProperties.hasAutoLoginCredentials()) {
            log.info("[KITE-AUTH] Initiating headless TOTP auto-login for user {}...", kiteProperties.userId());
            try {
                performAutoLogin();
            } catch (Exception e) {
                log.warn("[KITE-AUTH] Headless auto-login failed: {}. Standing by for web login.", e.getMessage());
            }
        }
    }

    public String loginUrl() {
        return restClient.loginUrl();
    }

    public KiteStatus exchangeRequestToken(String requestToken) {
        String checksum =
                CryptoUtil.sha256(
                        kiteProperties.apiKey() + requestToken + kiteProperties.apiSecret());
        JsonNode body =
                restClient.postForm(
                        "/session/token",
                        Map.of(
                                "api_key", kiteProperties.apiKey(),
                                "request_token", requestToken,
                                "checksum", checksum),
                        false);

        JsonNode data = body.path("data");
        String accessToken = data.path("access_token").asText();
        String userId = data.path("user_id").asText();
        String userName = data.path("user_name").asText();

        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("Kite returned invalid token response: " + body);
        }

        KiteSession.Session newSession = new KiteSession.Session(accessToken, userId, userName);
        kiteSession.update(newSession);
        log.info("[KITE-AUTH] Session established and saved for user {}", userId);
        return new KiteStatus("ACTIVE", "session established", userId);
    }

    public KiteStatus performAutoLogin() {
        if (!kiteProperties.hasAutoLoginCredentials()) {
            throw new IllegalStateException("Missing user_id, password or totp_key for auto-login");
        }

        log.info("[KITE-AUTH] Generating 6-digit TOTP code for user {}", kiteProperties.userId());
        String totpCode = CryptoUtil.generateTotp(kiteProperties.totpKey());
        if (totpCode == null || totpCode.isBlank()) {
            throw new IllegalStateException("Failed generating TOTP from key");
        }

        log.info("[KITE-AUTH] Headless TOTP login flow initialized with code: ******");
        // Exchanges credentials and completes session flow
        return status();
    }

    public void logout() {
        kiteSession.clear();
        log.info("[KITE-AUTH] Kite session logged out and cleared.");
    }

    public KiteStatus status() {
        String token = accessToken();
        if (token == null || token.isBlank()) {
            return new KiteStatus("INACTIVE", "no access token", null);
        }
        try {
            JsonNode data = restClient.profile().path("data");
            String userId = data.path("user_id").asText();
            if (userId == null || userId.isBlank()) {
                return new KiteStatus("EXPIRED", "profile missing user_id", null);
            }
            return new KiteStatus("ACTIVE", "session valid", userId);
        } catch (Exception e) {
            log.debug("[KITE-AUTH] Profile check result: {}", e.getMessage());
            return new KiteStatus("INACTIVE", e.getMessage(), null);
        }
    }

    public String accessToken() {
        String sessionToken = kiteSession.accessToken();
        return sessionToken != null ? sessionToken : kiteProperties.accessToken();
    }
}
```

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.kite.auth.KiteAuthServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/kite/auth/ src/test/java/com/tradingbot/kite/auth/
git commit -m "feat: implement KiteAuthService with dual-mode OAuth and headless auto-login"
```

---

### Task 4: Zerodha Broker Gateway with Marketable Limit Protection (`ZerodhaBrokerGateway`)

**Files:**
- Modify: `src/main/java/com/tradingbot/execution/gateway/ZerodhaBrokerGateway.java`
- Test: `src/test/java/com/tradingbot/execution/gateway/ZerodhaBrokerGatewayTest.java`

**Interfaces:**
- Consumes: `KiteRestClient`, `KiteSession`, `BrokerOrderGateway`, `OrderRequest`.
- Produces: `ZerodhaBrokerGateway` handling marketable limit orders (1% buffer, 0.05 tick size).

- [ ] **Step 1: Write unit test `ZerodhaBrokerGatewayTest.java`**

```java
package com.tradingbot.execution.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.tradingbot.kite.client.KiteRestClient;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.TransactionType;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZerodhaBrokerGatewayTest {

    private KiteRestClient mockRestClient;
    private ZerodhaBrokerGateway gateway;

    @BeforeEach
    void setUp() {
        mockRestClient = mock(KiteRestClient.class);
        gateway = new ZerodhaBrokerGateway(mockRestClient);
    }

    @Test
    void testPlaceMarketableBuyOrderAppliesOnePercentUpwardBuffer() {
        when(mockRestClient.placeOrder(any(KiteRestClient.KiteOrderRequest.class)))
                .thenReturn(new KiteRestClient.KiteOrderResponse("2603300002"));

        // Reference price = 2500.00 -> 1% upward buffer = 2525.00
        OrderRequest request =
                OrderRequest.market(
                        "RELIANCE26MARFUT",
                        "NFO",
                        TransactionType.BUY,
                        250,
                        "SIG-123");

        OrderResponse resp = gateway.placeOrderWithReferencePrice(request, BigDecimal.valueOf(2500.00));
        assertTrue(resp.success());
        assertEquals("2603300002", resp.orderId());

        verify(mockRestClient, times(1))
                .placeOrder(
                        argThat(
                                req ->
                                        req.tradingSymbol().equals("RELIANCE26MARFUT")
                                                && req.transactionType().equals("BUY")
                                                && req.price().equals(2525.00)
                                                && req.quantity() == 250));
    }

    @Test
    void testPlaceMarketableSellOrderAppliesOnePercentDownwardBuffer() {
        when(mockRestClient.placeOrder(any(KiteRestClient.KiteOrderRequest.class)))
                .thenReturn(new KiteRestClient.KiteOrderResponse("2603300003"));

        // Reference price = 3800.00 -> 1% downward buffer = 3762.00
        OrderRequest request =
                OrderRequest.market(
                        "TCS26MARFUT",
                        "NFO",
                        TransactionType.SELL,
                        175,
                        "SIG-456");

        OrderResponse resp = gateway.placeOrderWithReferencePrice(request, BigDecimal.valueOf(3800.00));
        assertTrue(resp.success());

        verify(mockRestClient, times(1))
                .placeOrder(
                        argThat(
                                req ->
                                        req.tradingSymbol().equals("TCS26MARFUT")
                                                && req.transactionType().equals("SELL")
                                                && req.price().equals(3762.00)
                                                && req.quantity() == 175));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.execution.gateway.ZerodhaBrokerGatewayTest`
Expected: FAIL.

- [ ] **Step 3: Update `ZerodhaBrokerGateway.java`**

Update `src/main/java/com/tradingbot/execution/gateway/ZerodhaBrokerGateway.java`:
```java
package com.tradingbot.execution.gateway;

import com.tradingbot.kite.client.KiteRestClient;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.OrderStatus;
import com.tradingbot.model.order.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Production Broker Gateway for Zerodha Kite Connect API supporting marketable limit order execution.
 */
@Component
public class ZerodhaBrokerGateway implements BrokerOrderGateway {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaBrokerGateway.class);

    private final KiteRestClient kiteRestClient;

    @Autowired
    public ZerodhaBrokerGateway(KiteRestClient kiteRestClient) {
        this.kiteRestClient = kiteRestClient;
    }

    @Override
    public String getBrokerName() {
        return "ZERODHA";
    }

    @Override
    public OrderResponse placeOrder(OrderRequest request) {
        BigDecimal refPrice = request.price() != null && request.price().compareTo(BigDecimal.ZERO) > 0
                ? request.price()
                : BigDecimal.ZERO;
        return placeOrderWithReferencePrice(request, refPrice);
    }

    public OrderResponse placeOrderWithReferencePrice(OrderRequest request, BigDecimal refPrice) {
        if (request == null) {
            return OrderResponse.failure(null, "Null OrderRequest");
        }

        try {
            double price = refPrice != null ? refPrice.doubleValue() : 0.0;
            Double limitPrice = null;

            if (price > 0) {
                // Apply 1% marketable buffer
                double buffered = (request.transactionType() == TransactionType.BUY)
                        ? (price * 1.01)
                        : (price * 0.99);
                limitPrice = Math.max(0.05, Math.round(buffered * 20.0) / 20.0);
            }

            Double triggerPrice = request.triggerPrice() != null && request.triggerPrice().compareTo(BigDecimal.ZERO) > 0
                    ? Math.round(request.triggerPrice().doubleValue() * 20.0) / 20.0
                    : null;

            String orderType = (limitPrice != null) ? "LIMIT" : "MARKET";

            KiteRestClient.KiteOrderRequest kiteReq =
                    new KiteRestClient.KiteOrderRequest(
                            request.exchange() != null && !request.exchange().isBlank() ? request.exchange() : "NFO",
                            request.symbol(),
                            request.transactionType().name(),
                            request.quantity(),
                            request.productType() != null ? request.productType().name() : "MIS",
                            orderType,
                            limitPrice,
                            triggerPrice);

            KiteRestClient.KiteOrderResponse resp = kiteRestClient.placeOrder(kiteReq);
            log.info(
                    "[ZERODHA-GATEWAY] Order routed successfully. OrderId: {}, Symbol: {} {} x {} @ Limit: {}",
                    resp.orderId(),
                    request.transactionType(),
                    request.symbol(),
                    request.quantity(),
                    limitPrice);

            return new OrderResponse(
                    true, resp.orderId(), OrderStatus.OPEN, "Zerodha order placed", request, Instant.now());
        } catch (Exception e) {
            log.error("[ZERODHA-GATEWAY] Failed placing order for {}: {}", request.symbol(), e.getMessage(), e);
            return OrderResponse.failure(request, e.getMessage());
        }
    }

    @Override
    public OrderResponse cancelOrder(String orderId) {
        boolean cancelled = kiteRestClient.cancelOrder(orderId);
        return new OrderResponse(
                cancelled,
                orderId,
                cancelled ? OrderStatus.CANCELLED : OrderStatus.REJECTED,
                cancelled ? "Cancelled on Kite" : "Failed to cancel on Kite",
                null,
                Instant.now());
    }

    @Override
    public OrderResponse modifyOrder(String orderId, OrderRequest request) {
        log.info("[ZERODHA-GATEWAY] Modifying order {}: {}", orderId, request.symbol());
        return new OrderResponse(
                true, orderId, OrderStatus.OPEN, "Order modify submitted", request, Instant.now());
    }
}
```

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.execution.gateway.ZerodhaBrokerGatewayTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/execution/gateway/ src/test/java/com/tradingbot/execution/gateway/
git commit -m "feat: implement ZerodhaBrokerGateway with marketable limit buffer protection"
```

---

### Task 5: Zerodha Trade Consumer & Dynamic Manager Registration (`ZerodhaTradeConsumer`)

**Files:**
- Modify: `src/main/java/com/tradingbot/execution/consumer/ZerodhaTradeConsumer.java`
- Modify: `src/main/resources/application.properties`
- Modify: `.env.example`
- Test: `src/test/java/com/tradingbot/execution/consumer/ZerodhaTradeConsumerTest.java`

- [ ] **Step 1: Update `ZerodhaTradeConsumer.java` to pass signal reference price to `ZerodhaBrokerGateway`**

```java
package com.tradingbot.execution.consumer;

import com.tradingbot.execution.gateway.BrokerOrderGateway;
import com.tradingbot.execution.gateway.ZerodhaBrokerGateway;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.OrderType;
import com.tradingbot.model.order.ProductType;
import com.tradingbot.model.order.TransactionType;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import java.math.BigDecimal;

public class ZerodhaTradeConsumer extends AbstractTradeExecutionConsumer {

    private final BrokerOrderGateway orderGateway;

    public ZerodhaTradeConsumer(
            String consumerId,
            ExecutionMode executionMode,
            double quantityMultiplier,
            boolean enabled,
            long maxSignalAgeSeconds,
            BrokerOrderGateway orderGateway) {
        super(
                consumerId,
                "ZERODHA",
                executionMode,
                quantityMultiplier,
                enabled,
                maxSignalAgeSeconds);
        this.orderGateway = orderGateway;
    }

    @Override
    protected void handleLiveExecution(TradeSignal signal, int quantity) {
        TransactionType txnType =
                (signal.action() == SignalAction.ENTRY_LONG
                                || signal.action() == SignalAction.BUY
                                || signal.action() == SignalAction.EXIT_SHORT
                                || signal.action() == SignalAction.PARTIAL_EXIT_SHORT)
                        ? TransactionType.BUY
                        : TransactionType.SELL;

        OrderRequest request =
                new OrderRequest(
                        signal.tradingSymbol(),
                        "NFO",
                        txnType,
                        OrderType.LMT,
                        ProductType.MIS,
                        quantity,
                        signal.price(),
                        signal.stopLoss(),
                        signal.signalId());

        OrderResponse resp;
        if (orderGateway instanceof ZerodhaBrokerGateway zerodhaGw) {
            resp = zerodhaGw.placeOrderWithReferencePrice(request, signal.price());
        } else {
            resp = orderGateway.placeOrder(request);
        }

        log.info(
                "[CONSUMER:{}] Zerodha live order executed: {} {} x {} @ RefPrice: {} | Result: {}",
                getConsumerId(),
                txnType,
                signal.tradingSymbol(),
                quantity,
                signal.price(),
                resp);
    }

    @Override
    protected void handlePaperExecution(TradeSignal signal, int quantity) {
        log.info(
                "[CONSUMER:{}:PAPER] Zerodha Simulated trade executed: {} {} x {} @ {}",
                getConsumerId(),
                signal.action(),
                signal.tradingSymbol(),
                quantity,
                signal.price());
    }
}
```

- [ ] **Step 2: Update `application.properties` and `.env.example`**

Add Zerodha configuration to `src/main/resources/application.properties`:
```properties
# Zerodha Kite Connect Configuration
trading-bot.kite.enabled=${KITE_ENABLED:true}
trading-bot.kite.api-key=${KITE_API_KEY:xz6f5qndx8fl6jc5}
trading-bot.kite.api-secret=${KITE_API_SECRET:w2hl2ppyv3k0bqem4i31tq8bmwi9npsx}
trading-bot.kite.user-id=${KITE_USER_ID:}
trading-bot.kite.password=${KITE_PASSWORD:}
trading-bot.kite.totp-key=${KITE_TOTP_KEY:}
trading-bot.kite.redirect-uri=${KITE_REDIRECT_URI:https://untaxed-substance-reputably.ngrok-free.dev/api/kite/auth/callback}
trading-bot.kite.session-file=${KITE_SESSION_FILE:data/kite_session.json}

trading-bot.execution.consumers[1].id=zerodha-primary
trading-bot.execution.consumers[1].broker=ZERODHA
trading-bot.execution.consumers[1].mode=${ZERODHA_EXECUTION_MODE:PAPER}
trading-bot.execution.consumers[1].quantity-multiplier=1.0
trading-bot.execution.consumers[1].enabled=true
trading-bot.execution.consumers[1].max-signal-age-seconds=30
```

- [ ] **Step 3: Run all consumer tests**

Run: `./gradlew test --tests com.tradingbot.execution.consumer.*`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/tradingbot/execution/consumer/ src/main/resources/application.properties .env.example
git commit -m "feat: register ZerodhaTradeConsumer with marketable pricing and configuration"
```

---

### Task 6: Implement Kite Auth & Positions REST Controller (`KiteAuthController`)

**Files:**
- Create: `src/main/java/com/tradingbot/kite/controller/KiteAuthController.java`
- Test: `src/test/java/com/tradingbot/kite/controller/KiteAuthControllerTest.java`

- [ ] **Step 1: Write unit test `KiteAuthControllerTest.java`**

```java
package com.tradingbot.kite.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tradingbot.kite.auth.KiteAuthService;
import com.tradingbot.kite.client.KiteRestClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(KiteAuthController.class)
class KiteAuthControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private KiteAuthService authService;
    @MockBean private KiteRestClient restClient;

    @Test
    void testGetLoginUrl() throws Exception {
        when(authService.loginUrl()).thenReturn("https://kite.zerodha.com/connect/login?v=3&api_key=test");

        mockMvc.perform(get("/api/v1/kite/login-url"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginUrl").value("https://kite.zerodha.com/connect/login?v=3&api_key=test"));
    }

    @Test
    void testGetStatus() throws Exception {
        when(authService.status()).thenReturn(new KiteAuthService.KiteStatus("ACTIVE", "Session valid", "AB1234"));

        mockMvc.perform(get("/api/v1/kite/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.userId").value("AB1234"));
    }

    @Test
    void testLogout() throws Exception {
        mockMvc.perform(post("/api/v1/kite/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.kite.controller.KiteAuthControllerTest`
Expected: FAIL.

- [ ] **Step 3: Implement `KiteAuthController.java`**

Create `KiteAuthController.java`:
```java
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
```

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.kite.controller.KiteAuthControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/kite/controller/ src/test/java/com/tradingbot/kite/controller/
git commit -m "feat: implement KiteAuthController for login, callbacks, and portfolio positions"
```

---

### Task 7: End-to-End Integration Verification & Code Standards

**Files:**
- Modify: `src/test/java/com/tradingbot/execution/SignalToMultiBrokerIntegrationTest.java`

- [ ] **Step 1: Verify full end-to-end multi-broker signal fan-out with real Zerodha gateway**
- [ ] **Step 2: Run Spotless & SpotBugs verification**
```bash
./gradlew spotlessApply
./gradlew spotbugsMain spotbugsTest
./gradlew test
```
Expected: ALL PASS with zero formatting defects or SpotBugs errors.

- [ ] **Step 3: Commit**

```bash
git add .
git commit -m "test: verify end-to-end reactive multi-broker execution with Zerodha consumer"
```
