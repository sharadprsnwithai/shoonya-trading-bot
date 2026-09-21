# Bollinger Band & Heikin-Ashi Nifty Positional Strategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the automated Bollinger Band (20, 2) & Heikin-Ashi Positional Strategy for Nifty 50 with a 3:00 PM IST daily evaluation scheduler, Monthly ATM Option Buying execution, JSON state persistence (`data/bb_rsi_positional_state.json`), and bidirectional Telegram Bot command & inline button control.

**Architecture:** 
- `com.tradingbot.positional.indicator`: Computes Heikin-Ashi candle series and Bollinger Bands (20, 2) on daily index data.
- `com.tradingbot.positional.service`: Manages the state machine (touches, alert candles, entry invalidation, stop loss, opposite band target), runs the 3:00 PM IST cron scheduler, and persists active state to `data/bb_rsi_positional_state.json`.
- `com.tradingbot.positional.execution`: Identifies monthly ATM option contracts (>20 DTE) and executes buy orders via `ShoonyaOrderService`.
- `com.tradingbot.telegram`: Dispatches interactive Telegram notifications with inline buttons and polls Telegram `/getUpdates` for real-time commands (`/status`, `/scan`, `/approve`, `/reject`, `/exit`, `/mode`).

**Tech Stack:** Java 21, Spring Boot 3.3.5, JUnit 5, Mockito, TA4J / TA-Lib, Jackson, Java `HttpClient`.

**Spec:** `docs/superpowers/specs/2026-03-30-bollinger-ha-positional-design.md`

## Global Constraints
- Java 21 toolchain with clean compile and spotless formatting (`./gradlew spotlessApply`).
- State file path must default to `data/bb_rsi_positional_state.json`.
- Scheduler runs at `0 0 15 * * MON-FRI` (3:00 PM IST Monday-Friday).
- Option selection must choose nearest Monthly Expiry with > 20 DTE.
- All Telegram requests use native Java `HttpClient`.

---

### Task 1: Domain Models & State Persistence (`com.tradingbot.positional.model`)

**Files:**
- Create: `src/main/java/com/tradingbot/positional/model/PositionalState.java`
- Create: `src/main/java/com/tradingbot/positional/model/PositionalAlert.java`
- Create: `src/main/java/com/tradingbot/positional/model/PositionalTrade.java`
- Create: `src/main/java/com/tradingbot/positional/model/PositionalStatus.java`
- Create: `src/main/java/com/tradingbot/positional/config/PositionalStrategyConfig.java`
- Test: `src/test/java/com/tradingbot/positional/model/PositionalStateSerializationTest.java`

**Interfaces:**
- Consumes: Jackson `ObjectMapper`
- Produces: `PositionalState` containing status enum (`FLAT`, `ALERT_PENDING`, `STAGED_FOR_APPROVAL`, `IN_LONG_CE`, `IN_SHORT_PE`), `PositionalAlert` (alert date, normal High, normal Low, direction), and `PositionalTrade` (entry date, strike, premium, SL, target).

- [ ] **Step 1: Write the failing serialization and config test**
- [ ] **Step 2: Run `./gradlew test --tests *PositionalStateSerializationTest*` to verify failure**
- [ ] **Step 3: Implement domain models and configuration properties class**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit changes**

---

### Task 2: Heikin-Ashi & Bollinger Band Indicator Service (`com.tradingbot.positional.indicator`)

**Files:**
- Create: `src/main/java/com/tradingbot/positional/indicator/HeikinAshiCandle.java`
- Create: `src/main/java/com/tradingbot/positional/indicator/BollingerBandSnapshot.java`
- Create: `src/main/java/com/tradingbot/positional/indicator/BollingerHaIndicatorService.java`
- Test: `src/test/java/com/tradingbot/positional/indicator/BollingerHaIndicatorServiceTest.java`

**Interfaces:**
- Consumes: `List<Candle>` (daily OHLC candles from `ShoonyaMarketDataService` or local feed)
- Produces: `List<BollingerBandSnapshot>` containing $HA\_Open, HA\_High, HA\_Low, HA\_Close$, $BB\_Upper$, $SMA20$, $BB\_Lower$.

- [ ] **Step 1: Write unit tests with synthetic price series to verify HA conversion and Bollinger Band (20, 2) values**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement `BollingerHaIndicatorService` and calculation algorithms**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit changes**

---

### Task 3: State Machine & Positional Strategy Service (`com.tradingbot.positional.service`)

**Files:**
- Create: `src/main/java/com/tradingbot/positional/service/BollingerHaPositionalService.java`
- Create: `src/main/java/com/tradingbot/positional/scheduler/PositionalTradingScheduler.java`
- Modify: `src/main/resources/application.yml` (add `trading-bot.positional` block)
- Test: `src/test/java/com/tradingbot/positional/service/BollingerHaPositionalServiceTest.java`

**Interfaces:**
- Consumes: `BollingerHaIndicatorService`, `ShoonyaMarketDataService`, `PositionalStrategyConfig`
- Produces: `scanAndEvaluate()`, `approveStagedTrade()`, `rejectStagedTrade()`, `forceExitCurrentPosition()`, `getCurrentStatus()`

- [ ] **Step 1: Write unit tests mocking market data to test Alert Candle formation, entry breakout, invalidation, SL hit, and Target hit**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement `BollingerHaPositionalService` with state persistence to `data/bb_rsi_positional_state.json` and `@Scheduled(cron = "0 0 15 * * MON-FRI", zone = "Asia/Kolkata")`**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit changes**

---

### Task 4: Positional Option Execution Service (`com.tradingbot.positional.execution`)

**Files:**
- Create: `src/main/java/com/tradingbot/positional/execution/PositionalExecutionService.java`
- Test: `src/test/java/com/tradingbot/positional/execution/PositionalExecutionServiceTest.java`

**Interfaces:**
- Consumes: `ShoonyaOptionChainService`, `ShoonyaOrderService`, `ShoonyaConfig`
- Produces: `executeOptionBuy(String symbol, String optionType, BigDecimal spotPrice, int lots)` and `exitOptionPosition(PositionalTrade trade)`

- [ ] **Step 1: Write unit tests verifying selection of Monthly expiry (> 20 DTE) and ATM strike resolution**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement `PositionalExecutionService` supporting both PAPER and LIVE execution modes**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit changes**

---

### Task 5: Telegram Interactive Command Listener & Alert Dispatcher (`com.tradingbot.telegram`)

**Files:**
- Create: `src/main/java/com/tradingbot/telegram/TelegramBotCommandListener.java`
- Modify: `src/main/java/com/tradingbot/telegram/TelegramService.java` (add helper to dispatch inline buttons)
- Test: `src/test/java/com/tradingbot/telegram/TelegramBotCommandListenerTest.java`

**Interfaces:**
- Consumes: `TelegramService`, `BollingerHaPositionalService`
- Produces: Long-polling Telegram `/getUpdates` loop handling `/status`, `/scan`, `/approve`, `/reject`, `/exit`, `/mode` and inline button callback queries (`btn_approve`, `btn_reject`)

- [ ] **Step 1: Write unit test mocking HTTP responses for Telegram update payloads and verifying command dispatching**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement `TelegramBotCommandListener` with background polling executor and button dispatch in `TelegramService`**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit changes**

---

### Task 6: REST Controller & End-to-End Verification

**Files:**
- Create: `src/main/java/com/tradingbot/controller/BollingerHaPositionalController.java`
- Test: `src/test/java/com/tradingbot/controller/BollingerHaPositionalControllerTest.java`

**Interfaces:**
- Consumes: `BollingerHaPositionalService`
- Produces: HTTP endpoints `GET /api/v1/positional/status`, `POST /api/v1/positional/scan`, `POST /api/v1/positional/approve`, `POST /api/v1/positional/exit`

- [ ] **Step 1: Write integration tests for all controller endpoints**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement `BollingerHaPositionalController`**
- [ ] **Step 4: Run all test suites across the project (`./gradlew test`)**
- [ ] **Step 5: Run `./gradlew spotlessApply` and commit**

---
