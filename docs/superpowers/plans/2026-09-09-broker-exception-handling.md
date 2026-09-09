# Broker API Exception Handling & Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fortify broker (Finvasia Shoonya / NorenAPI) communication, atomic multi-leg order execution, session expiration recovery, HTTP status verification, async timeout handling, and fix all unit tests.

**Architecture:** Strengthen `ShoonyaOrderService` with session auto-invalidation & retries, add atomic rollback in `ExecutionManager` for failed legs, add HTTP status validation across all HTTP calls, bound `CompletableFuture` joins with timeouts, add a global `@RestControllerAdvice`, and refactor `LowestVolumeReversalService` for deterministic testability.

**Tech Stack:** Java 21, Spring Boot 3, Jackson ObjectMapper, java.net.http.HttpClient, JUnit 5, Mockito, AssertJ.

---

## Task 1: Harden `ShoonyaOrderService` with Session Expiry Retries, HTTP Status Checks, and Full Error Logging

**Files:**
- Modify: `D:/code/shoonya-trading-bot/src/main/java/com/tradingbot/order/ShoonyaOrderService.java`
- Test: `D:/code/shoonya-trading-bot/src/test/java/com/tradingbot/order/ShoonyaOrderServiceTest.java`

**Interfaces:**
- `ShoonyaOrderService.placeOrder(OrderRequest request) -> OrderResponse`
- `ShoonyaOrderService.modifyOrder(...) -> OrderResponse`
- `ShoonyaOrderService.cancelOrder(String orderId) -> OrderResponse`
- `ShoonyaOrderService.getOrderBook() -> JsonNode`
- `ShoonyaOrderService.getPositionBook() -> JsonNode`

- [ ] **Step 1: Write tests for session expiry retry and HTTP non-200 failure in `ShoonyaOrderServiceTest`**
- [ ] **Step 2: Run tests to verify they fail or need implementation**
- [ ] **Step 3: Update `ShoonyaOrderService` with 2-attempt retry loop, session invalidation check (`isSessionExpired`), HTTP status code check, and robust logging on all endpoints**
- [ ] **Step 4: Run `ShoonyaOrderServiceTest` and verify all tests pass**

---

## Task 2: Implement Atomic Multi-Leg Execution & Safe Exit Rollback in `ExecutionManager`

**Files:**
- Modify: `D:/code/shoonya-trading-bot/src/main/java/com/tradingbot/execution/ExecutionManager.java`
- Test: `D:/code/shoonya-trading-bot/src/test/java/com/tradingbot/execution/ExecutionManagerTest.java`

**Interfaces:**
- `ExecutionManager.executeDirectionalOptionSelling(String, String, String, BigDecimal, int, boolean) -> ActiveSpreadPosition`
- `ExecutionManager.closeSpreadPosition(String, String) -> ActiveSpreadPosition`

- [ ] **Step 1: Write tests in `ExecutionManagerTest` for rollback when short order fails after hedge, and emergency exit when SL-L order fails**
- [ ] **Step 2: Run tests to observe failure**
- [ ] **Step 3: Update `ExecutionManager` with atomic rollback (cancel/sell hedge if short fails, emergency square-off if SL-L fails, and verify exit responses)**
- [ ] **Step 4: Run `ExecutionManagerTest` and verify all tests pass**

---

## Task 3: Improve `ShoonyaMarketDataService` & `ShoonyaOptionChainService` Resilience

**Files:**
- Modify: `D:/code/shoonya-trading-bot/src/main/java/com/tradingbot/marketdata/ShoonyaMarketDataService.java`
- Modify: `D:/code/shoonya-trading-bot/src/main/java/com/tradingbot/marketdata/ShoonyaOptionChainService.java`
- Test: `D:/code/shoonya-trading-bot/src/test/java/com/tradingbot/marketdata/ShoonyaMarketDataServiceTest.java`
- Test: `D:/code/shoonya-trading-bot/src/test/java/com/tradingbot/marketdata/ShoonyaOptionChainServiceTest.java`

- [ ] **Step 1: Fix `resolveToken` in `ShoonyaMarketDataService` so unknown stocks do not silently resolve to NIFTY50 (`10576`)**
- [ ] **Step 2: Add session expiry check, HTTP status check, and bounded timeout on `CompletableFuture` in `ShoonyaOptionChainService`**
- [ ] **Step 3: Run market data and option chain tests to verify passing**

---

## Task 4: Add Timeout Bounds and Fix Time-Dependent Tests in `LowestVolumeReversalService`

**Files:**
- Modify: `D:/code/shoonya-trading-bot/src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Modify: `D:/code/shoonya-trading-bot/src/main/java/com/tradingbot/service/MultiTimeframeScannerService.java`
- Test: `D:/code/shoonya-trading-bot/src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

- [ ] **Step 1: Add time parameter overload / clock hook to `evaluateLivePriceActions(LocalTime nowTime)` and add timeouts to `CompletableFuture` joins in `LowestVolumeReversalService` and `MultiTimeframeScannerService`**
- [ ] **Step 2: Update `LowestVolumeReversalServiceTest` to pass explicit test times or use the test-friendly method**
- [ ] **Step 3: Run `./gradlew test` and verify all 108+ tests pass**

---

## Task 5: Add Global REST Exception Handler

**Files:**
- Create: `D:/code/shoonya-trading-bot/src/main/java/com/tradingbot/controller/GlobalExceptionHandler.java`
- Test: `D:/code/shoonya-trading-bot/src/test/java/com/tradingbot/controller/GlobalExceptionHandlerTest.java`

- [ ] **Step 1: Create `GlobalExceptionHandler` with `@RestControllerAdvice` returning structured error responses for `IllegalArgumentException`, `IllegalStateException`, and generic `Exception`**
- [ ] **Step 2: Write test for `GlobalExceptionHandler`**
- [ ] **Step 3: Run all test suites and verify 100% build pass**
