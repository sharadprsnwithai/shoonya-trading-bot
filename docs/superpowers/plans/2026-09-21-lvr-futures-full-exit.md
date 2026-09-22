# LVR Stock Futures Execution & 100% Full Exit at 1:4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Stock Futures execution with 1.0 Delta pricing and 100% full exit at 1:4 Risk-Reward for the Lowest Volume Reversal (LVR) strategy, with backward-compatible configuration toggles for Options and Trailing Runner modes.

**Architecture:** Extend `LowestVolumeReversalService` and `LowestVolumePaperPosition` with `LvrInstrumentType` (`FUTURES` vs `OPTIONS`) and `LvrExitMode` (`FULL_TARGET_1_4` vs `PARTIAL_RUNNER_10EMA`). In `FUTURES` + `FULL_TARGET_1_4` mode, the bot trades the stock F&O contract with direct rupee P&L calculation and executes an immediate 100% full exit upon breaching the 1:4 target price.

**Tech Stack:** Java 21, Spring Boot 3.3.4, JUnit 5, AssertJ, Mockito, Spotless / Google Java Format.

**Spec:** `docs/superpowers/specs/2026-09-21-lvr-futures-full-exit-design.md`

## Global Constraints
- **Default Instrument Mode:** `trading-bot.strategy.lowest-volume.instrument-type=FUTURES`
- **Default Exit Mode:** `trading-bot.strategy.lowest-volume.exit-mode=FULL_TARGET_1_4`
- **Risk-Reward:** Fixed $1:4$ ($P_{\text{target}} = P_{\text{entry}} \pm 4 \times \text{Risk}$)
- **Delta:** 1.0 direct spot/futures tracking in Futures mode
- **Lot Multiplier:** Sourced dynamically per symbol from `StockFnoRegistry.get(symbol).lotSize()`
- **All existing tests must remain passing.**

---

### Task 1: Add LVR Configuration Enums & Update `LowestVolumePaperPosition`

**Files:**
- Create: `src/main/java/com/tradingbot/model/strategy/LvrInstrumentType.java`
- Create: `src/main/java/com/tradingbot/model/strategy/LvrExitMode.java`
- Modify: `src/main/java/com/tradingbot/model/strategy/LowestVolumePaperPosition.java`
- Create: `src/test/java/com/tradingbot/model/strategy/LowestVolumePaperPositionTest.java`

**Interfaces:**
- `LvrInstrumentType`: `FUTURES`, `OPTIONS`
- `LvrExitMode`: `FULL_TARGET_1_4`, `PARTIAL_RUNNER_10EMA`
- `LowestVolumePaperPosition`: Add constructors/methods supporting Futures mode with direct price accounting and 100% full exit.

- [ ] **Step 1: Create Enums `LvrInstrumentType` and `LvrExitMode`**
```java
package com.tradingbot.model.strategy;

public enum LvrInstrumentType {
    FUTURES,
    OPTIONS
}
```
```java
package com.tradingbot.model.strategy;

public enum LvrExitMode {
    FULL_TARGET_1_4,
    PARTIAL_RUNNER_10EMA
}
```

- [ ] **Step 2: Write failing unit tests for Futures position accounting**
Create `src/test/java/com/tradingbot/model/strategy/LowestVolumePaperPositionTest.java` verifying Futures P&L calculation for LONG and SHORT:
- Long entry at 1875.00, SL at 1872.00, Target at 1887.00, lot size 350, 1 lot.
- Full target exit at 1887.00 gives realized P&L = +₹4,200.00 (+4R).
- Stop loss exit at 1872.00 gives realized P&L = -₹1,050.00 (-1R).

- [ ] **Step 3: Run test to verify failure**
Run: `./gradlew test --tests com.tradingbot.model.strategy.LowestVolumePaperPositionTest`
Expected: Compilation failure (missing futures constructors/methods).

- [ ] **Step 4: Update `LowestVolumePaperPosition`**
Implement support for `instrumentType`, `exitMode`, `closeFullFutures(BigDecimal exitPrice, String reason, Instant time)` and direct P&L calculation in `LowestVolumePaperPosition.java`.

- [ ] **Step 5: Re-run unit tests to verify pass**
Run: `./gradlew test --tests com.tradingbot.model.strategy.LowestVolumePaperPositionTest`
Expected: PASS.

- [ ] **Step 6: Commit Task 1**
Commit: `git commit -m "feat(lvr): add LvrInstrumentType and LvrExitMode with Futures position accounting"`

---

### Task 2: Update Configuration Properties in `application.properties` and Strategy Config

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`

- [ ] **Step 1: Add configuration properties to `application.properties`**
```properties
trading-bot.strategy.lowest-volume.instrument-type=FUTURES
trading-bot.strategy.lowest-volume.exit-mode=FULL_TARGET_1_4
```

- [ ] **Step 2: Inject configuration properties in `LowestVolumeReversalService`**
Add `@Value` injection with defaults:
```java
@Value("${trading-bot.strategy.lowest-volume.instrument-type:FUTURES}")
private LvrInstrumentType instrumentType = LvrInstrumentType.FUTURES;

@Value("${trading-bot.strategy.lowest-volume.exit-mode:FULL_TARGET_1_4}")
private LvrExitMode exitMode = LvrExitMode.FULL_TARGET_1_4;
```
Add getters and setters for dynamic test configuration.

- [ ] **Step 3: Write test to verify property binding**
Add test in `LowestVolumeReversalServiceTest` verifying default `instrumentType == FUTURES` and `exitMode == FULL_TARGET_1_4`.

- [ ] **Step 4: Run test to verify pass**
Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit Task 2**
Commit: `git commit -m "feat(lvr): add instrument-type and exit-mode configuration properties"`

---

### Task 3: Implement Futures Entry Execution & Telegram Alert Formatting

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Modify: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

- [ ] **Step 1: Write unit test for Futures Entry Dispatch**
In `LowestVolumeReversalServiceTest`, test `executePositionEntry` when `instrumentType == FUTURES`:
- Generates contract symbol `<SYMBOL> FUT`
- Uses exact spot price as entry price
- Calculates total quantity as `defaultLots * lotSize`
- Sets target 1:4 and SL prices

- [ ] **Step 2: Run test to verify failure**
Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalServiceTest`

- [ ] **Step 3: Implement Futures Entry in `LowestVolumeReversalService`**
Refactor `executeOptionEntry` to `executePositionEntry` supporting both `FUTURES` and `OPTIONS`:
- If `FUTURES`: construct `LowestVolumePaperPosition` with `LvrInstrumentType.FUTURES`, direct spot entry price, no option chain fetching needed.
- If `OPTIONS`: fetch ATM option LTP as before.
- Send appropriate Telegram alert for Futures Entry.

- [ ] **Step 4: Run test to verify pass**
Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit Task 3**
Commit: `git commit -m "feat(lvr): implement Futures entry execution and alert dispatching"`

---

### Task 4: Implement 100% Full Exit at 1:4 in Position Monitoring Loop

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Modify: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

- [ ] **Step 1: Write unit test for 100% Full Exit at 1:4**
In `LowestVolumeReversalServiceTest`:
- Enter a LONG futures position at 1875.00 (SL: 1872.00, Target: 1887.00).
- Simulate spot tick at 1887.50.
- Verify `evaluateOpenPositions()` executes 100% full exit, sets realized P&L to +₹4,200.00, transitions setup to `CLOSED_TARGET`, adds symbol to `exhaustedSymbols`, and empties `openPositions`.

- [ ] **Step 2: Run test to verify failure**
Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalServiceTest`

- [ ] **Step 3: Implement 100% Full Exit in `evaluateOpenPositions`**
In `LowestVolumeReversalService.evaluateOpenPositions`:
- When Spot breaches Target 1:
  - If `exitMode == FULL_TARGET_1_4`:
    - Call `pos.closeFullFutures(spotPrice, "TARGET_1_4_FULL_EXIT", Instant.now())`.
    - Setup transitions to `CLOSED_TARGET`.
    - Symbol added to `exhaustedSymbols`.
    - Send Telegram alert: `🎯 LVR 1:4 Target Reached (100% Full Exit)`.
    - Remove from `openPositions`.
  - If `exitMode == PARTIAL_RUNNER_10EMA`:
    - Retain partial 50% booking + 10 EMA trailing runner.
- When Spot breaches SL:
  - Close 100% at SL price with $-1\text{R}$ P&L.
- When 15:15 IST reached:
  - Force close 100% at market MTM.

- [ ] **Step 4: Run test to verify pass**
Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit Task 4**
Commit: `git commit -m "feat(lvr): implement 100% full exit at 1:4 in position evaluation loop"`

---

### Task 5: Replay Test Suite & Verification

**Files:**
- Modify: `src/test/java/com/tradingbot/runner/ShoonyaTodayLvrReplayRunnerTest.java`
- Modify: `src/test/java/com/tradingbot/runner/ShoonyaLast5DaysLvrReplayRunnerTest.java`

- [ ] **Step 1: Run LVR Replay tests in Futures mode**
Run: `./gradlew test --tests com.tradingbot.runner.ShoonyaTodayLvrReplayRunnerTest`
Verify clean 1:4 full exits with $+4.0\text{R}$ gains and $-1.0\text{R}$ stop losses.

- [ ] **Step 2: Run entire test suite and Spotless**
Run: `./gradlew spotlessApply && ./gradlew test`
Expected: 100% PASS with zero styling or build errors.

- [ ] **Step 3: Build & Push Docker image**
Run: `docker build -t sharadprsn/shoonya-trading-bot:latest . && docker push sharadprsn/shoonya-trading-bot:latest`

- [ ] **Step 4: Commit Task 5**
Commit: `git commit -m "test(lvr): verify replay suites and complete full-exit futures implementation"`
