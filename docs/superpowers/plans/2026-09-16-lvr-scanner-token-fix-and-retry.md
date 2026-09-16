# LVR Morning Scanner Token Resolution, Startup Pre-Population & Retry Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the 09:25 AM LVR universe scan by pre-populating all NSE F&O instruments at startup with valid numeric tokens, repairing token resolution contracts, and implementing automatic 5-minute scan retries with graceful cutoff fallback.

**Architecture:** 
1. Pre-register all active NSE F&O universe equities and indices in `StockFnoRegistry` with official numeric tokens, lot sizes, and strike steps.
2. In `ShoonyaMarketDataService`, warm `tokenCache` at `@PostConstruct` startup and enforce numeric validation on tokens.
3. In `LowestVolumeReversalService`, latch `universeScanCompletedToday = true` only upon discovering valid stocks; retry on subsequent 5-minute candle cycles (09:30, 09:35...) sending Telegram retry alerts, with automatic fallback to 10 Champion stocks if retries persist until 10:00 AM.

**Tech Stack:** Java 21, Spring Boot 3.3.3, Jackson, JUnit 5, Mockito, AssertJ, Gradle.

**Spec:** `lvr_scanner_token_fix_and_retry_spec.md`

## Global Constraints
- Target Java version: Java 21.
- Never return symbol names from `StockFnoRegistry.getToken(symbol)`; return `null` for unknown symbols.
- All tokens for NSE equities and indices must be valid numeric strings (e.g. `"2885"`, `"11536"`).
- `universeScanCompletedToday` must only be set to `true` when either scan candidates are found or fallback cutoff is reached.
- All tests must pass via `./gradlew test`.

---

### Task 1: Fix Token Resolution Contract and Pre-Populate F&O Universe

**Files:**
- Modify: `src/main/java/com/tradingbot/util/StockFnoRegistry.java`
- Modify: `src/main/java/com/tradingbot/util/Nifty200Registry.java`
- Test: `src/test/java/com/tradingbot/util/StockFnoRegistryTest.java`

**Interfaces:**
- Consumes: None
- Produces: 
  - `StockFnoRegistry.getToken(String symbol)` -> `String` (returns numeric token or `null`)
  - `StockFnoRegistry.getAllInstruments()` -> `Map<String, InstrumentInfo>` (pre-populated with complete F&O universe)
  - `Nifty200Registry.getMetadata(String symbol)` -> `StockMetadata`

- [x] **Step 1: Write failing test in `StockFnoRegistryTest.java`**
- [x] **Step 2: Run test to verify it fails**
- [x] **Step 3: Implement `StockFnoRegistry.java` and `Nifty200Registry.java`**
- [x] **Step 4: Run test to verify it passes**
- [x] **Step 5: Commit**

---

### Task 2: Startup Token Cache Warming and Numeric Validation

**Files:**
- Modify: `src/main/java/com/tradingbot/marketdata/ShoonyaMarketDataService.java`
- Test: `src/test/java/com/tradingbot/marketdata/ShoonyaMarketDataServiceTest.java`

**Interfaces:**
- Consumes: `StockFnoRegistry.getAllInstruments()`
- Produces: 
  - `ShoonyaMarketDataService.warmTokenCache()` (`@PostConstruct`)
  - `ShoonyaMarketDataService.resolveToken(String symbol)` -> `String`

- [x] **Step 1: Write failing test in `ShoonyaMarketDataServiceTest.java`**
- [x] **Step 2: Run test to verify it fails**
- [x] **Step 3: Implement `warmTokenCache()` and `isValidNumericToken()` in `ShoonyaMarketDataService.java`**
- [x] **Step 4: Run test to verify it passes**
- [x] **Step 5: Commit**

---

### Task 3: Add Telegram Scan Retry Alert Notification

**Files:**
- Modify: `src/main/java/com/tradingbot/telegram/TelegramService.java`
- Test: `src/test/java/com/tradingbot/telegram/TelegramServiceTest.java`

**Interfaces:**
- Consumes: `TelegramConfig`
- Produces: `TelegramService.sendLvrScanRetryAlert(boolean niftyBullish, LocalTime nextRetryTime, int activeSetupsCount)`

- [x] **Step 1: Write failing test in `TelegramServiceTest.java`**
- [x] **Step 2: Run test to verify it fails**
- [x] **Step 3: Implement `sendLvrScanRetryAlert` in `TelegramService.java`**
- [x] **Step 4: Run test to verify it passes**
- [x] **Step 5: Commit**

---

### Task 4: Conditional Daily Lock, 5-Minute Scheduler Retry, and Cutoff Fallback

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

**Interfaces:**
- Consumes: `ShoonyaMarketDataService`, `TelegramService`, `StockFnoRegistry`
- Produces: `runMorningUniverseScan()`, `runCycle(LocalTime nowTime)`

- [x] **Step 1: Write failing tests in `LowestVolumeReversalServiceTest.java`**
- [x] **Step 2: Run test to verify it fails**
- [x] **Step 3: Implement retry logic and fallback in `LowestVolumeReversalService.java`**
- [x] **Step 4: Run test to verify it passes**
- [x] **Step 5: Commit**

---

### Task 5: Complete Verification and Regression Testing

**Files:**
- Test: All tests in `src/test/java/...`

- [x] **Step 1: Run full test suite**
- [x] **Step 2: Commit any cleanups**
