# Zerodha Kite Consumer & Broker Gateway Integration Specification

**Date:** 2026-03-30  
**Status:** DRAFT / UNDER REVIEW  
**Authors:** Trading Bot Core Engineering Team  
**Reference Source:** `D:\code\kite-trading-bot`  

---

## 1. Executive Summary & Goals

### 1.1 Objective
Integrate a production-ready **Zerodha Kite Connect Consumer** into the trading bot's decoupled reactive signal distribution architecture. The Zerodha consumer will subscribe concurrently to `Flux<TradeSignal>`, authenticate with Zerodha Kite Connect APIs, manage session persistence, and execute live or paper orders using marketable limit order protection with isolated worker threads.

### 1.2 Key Requirements
1. **Kite Authentication & Session Persistence:** Support OAuth 2.0 web login, token exchange with SHA-256 checksums, and token persistence to `data/kite_session.json` (similar to existing `data/shoonya_session.json`).
2. **Kite REST API Client:** Direct REST communication with `https://api.kite.trade` v3 endpoints (`/session/token`, `/user/profile`, `/orders/regular`, `/portfolio/positions`, `/orders/{orderId}`).
3. **Marketable Limit Order Execution (1% Buffer):** Place orders with a 1% protective buffer (1.01x for BUY, 0.99x for SELL) rounded to the nearest 0.05 tick size to ensure immediate fill while preventing slippage.
4. **Decoupled Consumer Subscription:** `ZerodhaTradeConsumer` receives signals from `ReactiveSignalEventBus`, performs freshness verification, scales quantities with consumer-specific lot multipliers, and executes orders via `ZerodhaBrokerGateway`.
5. **REST API & OAuth Web Endpoints:** Endpoints to generate Kite login URL, process OAuth callbacks, check session connectivity, and view live Zerodha positions.

---

## 2. Architecture & Component Interaction

```
                   ┌──────────────────────────────────────────────┐
                   │           ReactiveSignalEventBus             │
                   │             [ Flux<TradeSignal> ]            │
                   └──────────────────────┬───────────────────────┘
                                          │
                        Multicast Stream  │  (Hot Broadcast)
                                          │
         ┌────────────────────────────────┴───────────────────────────────┐
         │                                                               │
         ▼ (Worker Thread)                                               ▼ (Worker Thread)
┌─────────────────────────────────┐                     ┌─────────────────────────────────┐
│      ShoonyaTradeConsumer       │                     │      ZerodhaTradeConsumer       │
│     (id: "shoonya-primary")     │                     │     (id: "zerodha-primary")     │
│    [Mode: LIVE | Mult: 1.0x]    │                     │    [Mode: LIVE | Mult: 2.0x]    │
└────────────────┬────────────────┘                     └────────────────┬────────────────┘
                 │                                                       │
                 ▼                                                       ▼
┌─────────────────────────────────┐                     ┌─────────────────────────────────┐
│      ShoonyaBrokerGateway       │                     │      ZerodhaBrokerGateway       │
│      (ShoonyaOrderService)      │                     │       (KiteRestClient)          │
└────────────────┬────────────────┘                     └────────────────┬────────────────┘
                 │                                                       │
                 ▼                                                       ▼
┌─────────────────────────────────┐                     ┌─────────────────────────────────┐
│     Shoonya Broker REST API     │                     │    Zerodha Kite Connect API     │
│   (https://api.shoonya.com)     │                     │    (https://api.kite.trade)     │
└─────────────────────────────────┘                     └─────────────────────────────────┘
```

---

## 3. Package Structure & Responsibilities

```
src/main/java/com/tradingbot/
├── kite/
│   ├── config/
│   │   └── KiteProperties.java          (Configuration properties mapped from environment)
│   ├── auth/
│   │   ├── KiteSession.java             (Session token holder with data/kite_session.json persistence)
│   │   └── KiteAuthService.java         (OAuth login URL generation, token exchange, session health)
│   ├── client/
│   │   └── KiteRestClient.java          (Low-level HTTP client for api.kite.trade v3)
│   └── controller/
│       └── KiteAuthController.java      (REST controller for OAuth login/callback & positions)
└── execution/
    ├── gateway/
    │   └── ZerodhaBrokerGateway.java    (Adapter implementing BrokerOrderGateway using KiteRestClient)
    └── consumer/
        └── ZerodhaTradeConsumer.java    (Consumer translating TradeSignal into Kite orders)
```

---

## 4. Detailed Component Design

### 4.1 Configuration Properties (`KiteProperties`)
Maps environment variables from `.env` and `application.properties`:
```java
@ConfigurationProperties(prefix = "trading-bot.kite")
public record KiteProperties(
    boolean enabled,
    String apiKey,
    String apiSecret,
    String redirectUri,
    String accessToken,
    String sessionFile,
    String frontendRedirect
) {
    public KiteProperties {
        if (sessionFile == null || sessionFile.isBlank()) {
            sessionFile = "data/kite_session.json";
        }
    }
}
```

Environment variables supported:
* `KITE_API_KEY`: Zerodha Kite Connect API key (e.g. `xz6f5qndx8fl6jc5`)
* `KITE_API_SECRET`: Kite Connect API secret (e.g. `w2hl2ppyv3k0bqem4i31tq8bmwi9npsx`)
* `KITE_REDIRECT_URI`: OAuth callback URI (e.g. `http://localhost:8080/api/v1/kite/auth/callback`)
* `KITE_ACCESS_TOKEN`: Pre-generated access token (optional override)
* `KITE_SESSION_FILE`: Path to saved session token JSON (`data/kite_session.json`)

### 4.2 Session Persistence (`KiteSession`)
* Manages an `AtomicReference<Session>` in memory.
* Automatically serializes session JSON to `data/kite_session.json` upon token exchange.
* Loads existing valid session on startup so application restarts do not require re-login during the trading day.
* `clear()` invalidates memory and deletes the file upon logout or 401 expiration.

### 4.3 Authentication Service (`KiteAuthService`)
* **Login URL Generation:** `https://kite.zerodha.com/connect/login?v=3&api_key={apiKey}&redirect_uri={redirectUri}`
* **Token Exchange:** Computes SHA-256 checksum:
  $$\text{checksum} = \text{SHA256}(\text{apiKey} + \text{requestToken} + \text{apiSecret})$$
  Posts form payload to `/session/token` on `https://api.kite.trade`.
* **Session Health Check:** Calls `/user/profile` to verify session validity and user ID.

### 4.4 Kite REST Client (`KiteRestClient`)
* Configured with Spring `RestClient` pointing to `https://api.kite.trade` with header `X-Kite-Version: 3`.
* Attaches `Authorization: token {apiKey}:{accessToken}` on authenticated endpoints.
* **Order Placement (`/orders/regular`):**
  - Form-urlencoded `POST` request with parameters: `exchange`, `tradingsymbol`, `transaction_type`, `quantity`, `product`, `order_type`, `price`, `validity=DAY`.
  - Parses `order_id` or extracts structured error messages.
* **Positions & Portfolio (`/portfolio/positions`):**
  - Reads open positions for safety cross-checks (e.g. verifying position exists before closing to prevent naked shorting).
* **Order Status (`/orders/{orderId}`):**
  - Inspects execution status (`COMPLETE`, `REJECTED`, `OPEN`, `CANCELLED`).

### 4.5 Zerodha Broker Gateway (`ZerodhaBrokerGateway`)
Implements `BrokerOrderGateway`:
* Maps `OrderRequest` $\to$ Kite format.
* Calculates marketable limit price with 1% buffer:
  - BUY order: $\text{price} \times 1.01$, rounded to nearest 0.05 tick.
  - SELL order: $\text{price} \times 0.99$, rounded to nearest 0.05 tick.
* Routes order through `KiteRestClient.placeOrder(...)`.
* In `PAPER` mode, logs and simulates fills without network I/O.
* In `LIVE` mode, verifies order completion or rejection.

### 4.6 Zerodha Trade Consumer (`ZerodhaTradeConsumer`)
* Subscribes to `Flux<TradeSignal>` via `AbstractTradeExecutionConsumer`.
* Validates freshness (`isSignalFreshAndActionable` with `maxSignalAgeSeconds = 30s`).
* Scales lot sizes with `quantityMultiplier`.
* Dispatches Telegram execution alerts when orders are placed/filled on Zerodha.

---

## 5. REST Controller Endpoints (`KiteAuthController`)

| HTTP Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/kite/login-url` | Returns the OAuth login URL for Zerodha authorization |
| `GET` | `/api/v1/kite/auth/callback` | Receives `request_token`, completes token exchange, saves session |
| `GET` | `/api/v1/kite/status` | Returns session connectivity status and user ID |
| `POST` | `/api/v1/kite/logout` | Clears active Kite session and deletes `data/kite_session.json` |
| `GET` | `/api/v1/kite/positions` | Fetches live net positions directly from Zerodha Kite account |

---

## 6. Multi-Consumer Configuration (`application.properties`)

```properties
# Zerodha Kite Connect Configuration
trading-bot.kite.enabled=${KITE_ENABLED:true}
trading-bot.kite.api-key=${KITE_API_KEY:xz6f5qndx8fl6jc5}
trading-bot.kite.api-secret=${KITE_API_SECRET:w2hl2ppyv3k0bqem4i31tq8bmwi9npsx}
trading-bot.kite.redirect-uri=${KITE_REDIRECT_URI:http://localhost:8080/api/v1/kite/auth/callback}
trading-bot.kite.session-file=${KITE_SESSION_FILE:data/kite_session.json}

# Execution Consumers
trading-bot.execution.consumers[0].id=shoonya-primary
trading-bot.execution.consumers[0].broker=SHOONYA
trading-bot.execution.consumers[0].mode=PAPER
trading-bot.execution.consumers[0].quantity-multiplier=1.0
trading-bot.execution.consumers[0].enabled=true

trading-bot.execution.consumers[1].id=zerodha-primary
trading-bot.execution.consumers[1].broker=ZERODHA
trading-bot.execution.consumers[1].mode=${ZERODHA_EXECUTION_MODE:PAPER}
trading-bot.execution.consumers[1].quantity-multiplier=1.0
trading-bot.execution.consumers[1].enabled=true
```

---

## 7. Testing & Quality Assurance Plan

1. **`KiteSessionTest`:**
   - Tests memory caching, file persistence (`data/kite_session.json`), and clean file deletion on `clear()`.
2. **`KiteAuthServiceTest`:**
   - Tests OAuth login URL generation and SHA-256 token exchange parsing with mock HTTP responses.
3. **`KiteRestClientTest`:**
   - Tests form-urlencoded order placement, 401 unauthorized handling, and tick-size rounding.
4. **`ZerodhaBrokerGatewayTest`:**
   - Tests translation of `OrderRequest` into Kite marketable limit orders (1% buffer, 0.05 tick size).
5. **`ZerodhaTradeConsumerTest`:**
   - Tests reactive signal consumption, quantity scaling, stale rejection, and live order placement.
6. **Code Standards:**
   - Spotless formatting (`./gradlew spotlessApply`), SpotBugs analysis clean, and 100% test pass.
