# Drift VWAP ATM Option Selling Strategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the institutional Drift VWAP ATM Option Selling strategy (Matio Kanti framework) for NIFTY 50 in `shoonya-trading-bot`, selling ATM Puts on Bullish 15m Drift and ATM Calls on Bearish 15m Drift with 70% decay target, 60% SL expansion, and 15:10 EOD hard exit.

**Architecture:** Create a dedicated `com.tradingbot.strategy.driftvwap` package with service, scheduler, models, controller, and REST endpoints. The service uses multi-timeframe 15m/5m candle data, calculates anchored VWAP and 1-hour momentum, arms 5m pullback triggers, sells ATM weekly options through the existing `SignalPublisher` / multi-broker consumer pipeline, and tracks intra-day positions every 30 seconds.

**Tech Stack:** Java 21, Spring Boot 3, Gradle, Jackson, Reactor, JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-10-04-drift-vwap-pullback-nifty50-design.md`

## Global Constraints

- Directional Option Selling: Bullish Drift $\to$ Sell ATM Put (PE); Bearish Drift $\to$ Sell ATM Call (CE).
- Timing constraints: Settlement window 09:15–10:15 (no trades); active window 10:15–14:55; entry cutoff at **14:55 IST**; complete hard exit at **15:10 IST**.
- Target: +70% premium decay (`targetPrem = entryPrem * 0.30`); Stop Loss: -60% premium expansion (`slPrem = entryPrem * 1.60`).
- Broker protective order: `OrderType.SL_LMT` placed on broker with 5% execution limit buffer.
- Guardrails: Max 1 concurrent position, Max 4 trades/day, Daily 2-loss circuit breaker.
- Strategy ID must be `"DRIFT_VWAP_OPTION_SELLING"`.

## Review Focus

1. **15m Drift / 1-Hr Momentum Boundary:** Ensure that with `< 4` fifteen-minute bars (e.g. before 10:15 IST), momentum returns 0.0 and drift stays `NEUTRAL`.
2. **ATM Strike Rounding:** Ensure NIFTY spot at 25,024 rounds to `25000` and 25,026 rounds to `25050` (`step = 50`).
3. **Option Selling P&L Calculation:** Profit is `(entryPremium - exitPremium) * quantity` (positive when premium drops).
4. **Daily 2-Loss Halting:** Ensure that after 2 loss exits on the same day, new entry triggers are blocked until the next session.
5. **Protective SL Order Cancellation on Exit:** Ensure the consumer cancels the resting broker SL order when target or 15:10 EOD exit is triggered.

---

### Task 1: Domain Models & Properties (`DriftVwapPosition`, `DriftVwapTrendState`, `DriftVwapProperties`)

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/driftvwap/model/DriftDirection.java`
- Create: `src/main/java/com/tradingbot/strategy/driftvwap/model/DriftVwapTrendState.java`
- Create: `src/main/java/com/tradingbot/strategy/driftvwap/model/DriftVwapPosition.java`
- Create: `src/main/java/com/tradingbot/strategy/driftvwap/config/DriftVwapProperties.java`
- Test: `src/test/java/com/tradingbot/strategy/driftvwap/model/DriftVwapPositionTest.java`

**Interfaces:**
- Produces `DriftDirection`: `BULLISH_DRIFT`, `BEARISH_DRIFT`, `NEUTRAL`
- Produces `DriftVwapPosition`:
  - `close(BigDecimal exitPremium, String reason, Instant time)`
  - `calculateUnrealizedPnl(BigDecimal currentPremium)`
  - `getRealizedPnl()`, `isClosed()`, `getEntryPremium()`, `getSlPremium()`, `getTargetPremium()`
- Produces `DriftVwapProperties`: Configuration with prefix `trading-bot.strategy.drift-vwap`

- [ ] **Step 1: Write the failing tests for `DriftVwapPosition` P&L and target calculation**

In `src/test/java/com/tradingbot/strategy/driftvwap/model/DriftVwapPositionTest.java`:
```java
package com.tradingbot.strategy.driftvwap.model;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DriftVwapPositionTest {

    @Test
    @DisplayName("Sold Option Position - Target Hit (+70% decay) computes correct positive P&L")
    void testSoldOptionTargetHitComputesPositivePnl() {
        BigDecimal entryPrem = BigDecimal.valueOf(100.00);
        BigDecimal targetPrem = BigDecimal.valueOf(30.00); // 70% decay
        BigDecimal slPrem = BigDecimal.valueOf(160.00);    // 60% expansion
        int qty = 150; // 2 lots

        DriftVwapPosition pos = new DriftVwapPosition(
                "DVWAP-1", "NIFTY", "PE", "NIFTY26OCT25000PE", BigDecimal.valueOf(25000),
                2, 75, DriftDirection.BULLISH_DRIFT, BigDecimal.valueOf(25000),
                entryPrem, slPrem, targetPrem, qty, BigDecimal.valueOf(9000), Instant.now());

        assertFalse(pos.isClosed());
        assertEquals(BigDecimal.valueOf(100.00), pos.getEntryPremium());
        assertEquals(BigDecimal.valueOf(30.00), pos.getTargetPremium());
        assertEquals(BigDecimal.valueOf(160.00), pos.getSlPremium());

        // Target hit @ 30.00 -> Profit = (100 - 30) * 150 = +10,500.00
        pos.close(targetPrem, "OPTION_TARGET_DECAY", Instant.now());

        assertTrue(pos.isClosed());
        assertEquals(BigDecimal.valueOf(10500.00), pos.getRealizedPnl());
        assertEquals("OPTION_TARGET_DECAY", pos.getExitReason());
    }

    @Test
    @DisplayName("Sold Option Position - SL Hit (+60% expansion) computes correct negative P&L")
    void testSoldOptionSlHitComputesNegativePnl() {
        BigDecimal entryPrem = BigDecimal.valueOf(80.00);
        BigDecimal targetPrem = BigDecimal.valueOf(24.00);
        BigDecimal slPrem = BigDecimal.valueOf(128.00);
        int qty = 75;

        DriftVwapPosition pos = new DriftVwapPosition(
                "DVWAP-2", "NIFTY", "CE", "NIFTY26OCT25000CE", BigDecimal.valueOf(25000),
                1, 75, DriftDirection.BEARISH_DRIFT, BigDecimal.valueOf(25000),
                entryPrem, slPrem, targetPrem, qty, BigDecimal.valueOf(3600), Instant.now());

        // SL hit @ 128.00 -> Loss = (80 - 128) * 75 = -3,600.00
        pos.close(slPrem, "OPTION_SL_EXPANSION", Instant.now());

        assertTrue(pos.isClosed());
        assertEquals(BigDecimal.valueOf(-3600.00), pos.getRealizedPnl());
        assertEquals("OPTION_SL_EXPANSION", pos.getExitReason());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.driftvwap.model.DriftVwapPositionTest`
Expected: Compilation failure (package and classes do not exist).

- [ ] **Step 3: Implement domain models and properties**

1. Create `DriftDirection.java` (`BULLISH_DRIFT`, `BEARISH_DRIFT`, `NEUTRAL`).
2. Create `DriftVwapTrendState.java` (`record DriftVwapTrendState(BigDecimal close15m, BigDecimal vwap15m, BigDecimal prevVwap15m, double momentum1hrPct, DriftDirection direction)`).
3. Create `DriftVwapPosition.java` with full field attributes, `close()`, `calculateUnrealizedPnl()`, and getters.
4. Create `DriftVwapProperties.java` with Spring Boot `@ConfigurationProperties(prefix = "trading-bot.strategy.drift-vwap")`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.strategy.driftvwap.model.DriftVwapPositionTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/driftvwap/model/ src/main/java/com/tradingbot/strategy/driftvwap/config/ src/test/java/com/tradingbot/strategy/driftvwap/model/
git commit -m "feat(drift-vwap): add domain models and properties for Drift VWAP Option Selling strategy"
```

---

### Task 2: Trend Drift & Pullback Engine (`DriftVwapOptionSellingService`)

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/driftvwap/DriftVwapOptionSellingService.java`
- Test: `src/test/java/com/tradingbot/strategy/driftvwap/DriftVwapOptionSellingServiceTest.java`

**Interfaces:**
- Consumes: `ShoonyaMarketDataService`, `ShoonyaOptionChainService`, `TechnicalAnalysisService`, `SignalPublisher`
- Produces:
  - `DriftVwapTrendState evaluate15mDrift(List<Candle> candles15m)`
  - `boolean check5mPullbackTrigger(DriftDirection drift, Candle candle5m)`
  - `DriftVwapPosition executeOptionSellingEntry(BigDecimal spotPrice, DriftDirection direction)`
  - `void evaluateLivePriceActions()` (30-second live check for Target decay, SL expansion, and 15:10 EOD exit)
  - `void runCycle()` (5-minute candle evaluation)
  - `void resetDaily()`

- [ ] **Step 1: Write failing tests for 15m drift evaluation and 5m pullback trigger**

In `src/test/java/com/tradingbot/strategy/driftvwap/DriftVwapOptionSellingServiceTest.java`:
```java
@Test
@DisplayName("Should detect BULLISH_DRIFT on 15m when Close > VWAP, VWAP rising, and 1-Hr momentum >= +0.12%")
void testEvaluateBullishDrift() {
    DriftVwapOptionSellingService service = new DriftVwapOptionSellingService(null, null, new TechnicalAnalysisService(), null, null, null);
    Instant t0 = Instant.parse("2026-10-01T03:45:00Z"); // 09:15 IST

    List<Candle> candles15m = List.of(
            Candle.of("NIFTY", t0, BigDecimal.valueOf(25000), BigDecimal.valueOf(25020), BigDecimal.valueOf(24990), BigDecimal.valueOf(25010), 10000),
            Candle.of("NIFTY", t0.plus(15, ChronoUnit.MINUTES), BigDecimal.valueOf(25010), BigDecimal.valueOf(25040), BigDecimal.valueOf(25005), BigDecimal.valueOf(25035), 12000),
            Candle.of("NIFTY", t0.plus(30, ChronoUnit.MINUTES), BigDecimal.valueOf(25035), BigDecimal.valueOf(25060), BigDecimal.valueOf(25030), BigDecimal.valueOf(25055), 15000),
            Candle.of("NIFTY", t0.plus(45, ChronoUnit.MINUTES), BigDecimal.valueOf(25055), BigDecimal.valueOf(25080), BigDecimal.valueOf(25050), BigDecimal.valueOf(25075), 14000),
            Candle.of("NIFTY", t0.plus(60, ChronoUnit.MINUTES), BigDecimal.valueOf(25075), BigDecimal.valueOf(25100), BigDecimal.valueOf(25070), BigDecimal.valueOf(25095), 16000)
    );

    DriftVwapTrendState state = service.evaluate15mDrift(candles15m);
    assertNotNull(state);
    assertEquals(DriftDirection.BULLISH_DRIFT, state.direction());
    assertTrue(state.momentum1hrPct() >= 0.12);
}

@Test
@DisplayName("Should trigger LONG entry on 1st RED 5m pullback bar during BULLISH_DRIFT")
void testTriggerLongOnRedPullbackBar() {
    DriftVwapOptionSellingService service = new DriftVwapOptionSellingService(null, null, new TechnicalAnalysisService(), null, null, null);
    Candle redCandle5m = Candle.of5m("NIFTY", Instant.now(), BigDecimal.valueOf(25080), BigDecimal.valueOf(25085), BigDecimal.valueOf(25060), BigDecimal.valueOf(25065), 5000);

    boolean triggered = service.check5mPullbackTrigger(DriftDirection.BULLISH_DRIFT, redCandle5m);
    assertTrue(triggered);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.driftvwap.DriftVwapOptionSellingServiceTest`
Expected: Compilation failure.

- [ ] **Step 3: Implement `DriftVwapOptionSellingService.java`**

1. Implement `evaluate15mDrift()`:
   - Compute 15m VWAP series anchored from 09:15 AM open.
   - Compute 1-hour momentum from 4 bars back: `(Close - Close_prev4) / Close_prev4 * 100.0`.
   - Check conditions for `BULLISH_DRIFT` and `BEARISH_DRIFT`.
2. Implement `check5mPullbackTrigger()`:
   - Return `true` if `BULLISH_DRIFT` and `candle5m.isRed()`.
   - Return `true` if `BEARISH_DRIFT` and `candle5m.isGreen()`.
3. Implement `executeOptionSellingEntry()`:
   - Calculate ATM strike = `roundStrike(spotPrice, 50)`.
   - Bullish $\to$ Sell ATM Put (PE); Bearish $\to$ Sell ATM Call (CE).
   - Fetch live option LTP via `optionChainService` / `marketDataService`.
   - Calculate Target (70% decay) and SL (60% expansion).
   - Publish `TradeSignal` with `strategyId = "DRIFT_VWAP_OPTION_SELLING"`, `brokerStopLossPrice`, and `brokerTargetPrice`.
4. Implement `evaluateLivePriceActions()`:
   - 30-second live check: monitor option premium / spot moves.
   - If premium $\le$ target $\to$ close at target.
   - If premium $\ge$ SL $\to$ close at SL, increment `todayLossCount`.
   - If time $\ge$ 15:10 IST $\to$ execute hard EOD exit.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests com.tradingbot.strategy.driftvwap.DriftVwapOptionSellingServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/driftvwap/DriftVwapOptionSellingService.java src/test/java/com/tradingbot/strategy/driftvwap/DriftVwapOptionSellingServiceTest.java
git commit -m "feat(drift-vwap): implement Drift VWAP Option Selling core service engine and signals"
```

---

### Task 3: Scheduler, Controller & Telegram Integration

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/driftvwap/DriftVwapOptionSellingScheduler.java`
- Create: `src/main/java/com/tradingbot/strategy/driftvwap/controller/DriftVwapController.java`
- Modify: `src/main/java/com/tradingbot/telegram/TelegramBotCommandListener.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/tradingbot/strategy/driftvwap/controller/DriftVwapControllerTest.java`

- [ ] **Step 1: Write failing tests for Controller endpoints**

In `src/test/java/com/tradingbot/strategy/driftvwap/controller/DriftVwapControllerTest.java`:
```java
@Test
@DisplayName("GET /api/v1/drift-vwap/status returns live strategy state")
void testGetStatusEndpoint() {
    DriftVwapOptionSellingService mockService = mock(DriftVwapOptionSellingService.class);
    DriftVwapController controller = new DriftVwapController(mockService);

    when(mockService.isWithinTradingHours()).thenReturn(true);
    when(mockService.getOpenPosition()).thenReturn(null);

    var resp = controller.getStatus();
    assertEquals(200, resp.getStatusCode().value());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.driftvwap.controller.DriftVwapControllerTest`
Expected: FAIL.

- [ ] **Step 3: Implement Scheduler, Controller, and Telegram Commands**

1. Create `DriftVwapOptionSellingScheduler.java`:
   - `@Scheduled(cron = "${trading-bot.strategy.drift-vwap.cron:20 */5 9-15 ? * MON-FRI}")` $\to$ `service.runCycle()`
   - `@Scheduled(fixedRate = 30000)` $\to$ `service.evaluateLivePriceActions()`
   - `@Scheduled(cron = "0 10 15 ? * MON-FRI")` $\to$ `service.executeHardExit()`
2. Create `DriftVwapController.java`:
   - `@GetMapping("/status")`, `@PostMapping("/run-cycle")`, `@PostMapping("/exit")`, `@PostMapping("/reset")`
3. In `TelegramBotCommandListener.java`:
   - Add `/drift_vwap` / `/drift_status` command to show live drift direction, VWAP, 1-hr momentum, active position, and P&L.
4. In `application.properties`:
   - Add strategy properties and consumer strategy-modes:
     `trading-bot.execution.consumers[0].strategy-modes.DRIFT_VWAP_OPTION_SELLING=${SHOONYA_DRIFT_VWAP_MODE:PAPER}`
     `trading-bot.execution.consumers[1].strategy-modes.DRIFT_VWAP_OPTION_SELLING=${ZERODHA_DRIFT_VWAP_MODE:PAPER}`

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests com.tradingbot.strategy.driftvwap.controller.DriftVwapControllerTest`
Run: `./gradlew test --tests com.tradingbot.telegram.TelegramBotCommandListenerTest`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/driftvwap/ src/main/java/com/tradingbot/telegram/TelegramBotCommandListener.java src/main/resources/application.properties src/test/java/com/tradingbot/strategy/driftvwap/
git commit -m "feat(drift-vwap): add scheduler, controller endpoints, and telegram bot monitoring"
```

---

### Task 4: Full System Verification & Regression Suite

**Files:**
- Test: All unit and integration tests across the codebase

- [ ] **Step 1: Run complete test suite**

Run: `./gradlew test`
Expected: 100% tests pass across the entire codebase with zero regressions.

- [ ] **Step 2: Commit final implementation baseline**

```bash
git commit --allow-empty -m "chore(drift-vwap): verify complete test suite for Drift VWAP ATM Option Selling strategy"
```
