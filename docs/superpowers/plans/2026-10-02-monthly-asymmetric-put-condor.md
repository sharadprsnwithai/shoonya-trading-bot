# Monthly Asymmetric Put Condor Subsystem Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a production-grade, defined-risk Monthly Asymmetric Put Condor trading subsystem on NIFTY 50 options with 4 dynamic adjustments (Upside Financing, Sweet Spot Lock, Deep Crash Defense, and Expiry Gamma Shield) using Shoonya for market data and Zerodha Kite Connect for live order execution on a ₹1 Crore pledged collateral account.

**Architecture:** A dedicated Spring Boot package `com.tradingbot.strategy.condor` implementing a 5-state finite automaton. It polls Shoonya for live Nifty spot and option chain prices, resolves 200-pt strikes, automatically slices large orders (>1,800 freeze limit), prioritizes Buy legs for exchange margin relief, and persists active positions in SQLite (`trading_bot.db`).

**Tech Stack:** Java 21, Spring Boot 3.3+, SQLite (JDBC), Shoonya API (`ShoonyaMarketDataService`), Zerodha Kite Connect API (`ZerodhaBrokerGateway`), JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-10-02-monthly-asymmetric-put-condor-design.md`

## Global Constraints

- Platform: Java 21 / Spring Boot 3 with Gradle build.
- Underlying Instrument: `NIFTY 50` Index Options with 200-pt strike width and 100-multiple round strike rounding.
- Initial Order Placement Sequence: Always execute BUY orders first, confirm fills, then execute SELL orders to maintain exchange RMS margin relief.
- Order Slicing Floor: Any order with quantity $> 1,800$ must be sliced into equal sub-orders $\le 1,800$ quantity with 200ms spacing.
- Liquidation Order Sequence: Always execute BUY orders (closing shorts) first, then SELL orders (closing longs).
- Default Properties: `CONDOR_ENABLED=true`, `CONDOR_EXECUTION_MODE=PAPER`, `CONDOR_LOTS=50`, `CONDOR_TARGET_PROFIT_PCT=6.0`, `CONDOR_EARLY_EXIT_TARGET_PCT=4.5`, `CONDOR_STOP_LOSS_PCT=3.0`.

## Review Focus

1. **Exchange Margin Rejection:** Submitting short legs before long legs causes RMS failure on unhedged margin. *Pinned in Task 5 & Task 6 test.*
2. **Freeze Limit Rejection (>1800 Qty):** Slicing calculation must always produce integer multiples of lot size (65) without drop or overshoot. *Pinned in Task 5 test.*
3. **State Loss on Bot Restart:** App crash during an active monthly cycle must reload active position from SQLite and resume tick monitoring without re-entering. *Pinned in Task 4 & Task 6 test.*
4. **Holiday / Weekend False Entry:** Holiday calendar must skip weekends and NSE holidays, triggering on the exact first trading session. *Pinned in Task 1 test.*
5. **Simultaneous Adjustment Double-Trigger:** If Nifty has a volatile gap, state machine must guard against conflicting simultaneous adjustments. *Pinned in Task 6 test.*

---

### Task 1: Calendar & Holiday Detection Engine (`NseTradingCalendarUtil`)

**Files:**
- Create: `src/main/java/com/tradingbot/util/NseTradingCalendarUtil.java`
- Test: `src/test/java/com/tradingbot/util/NseTradingCalendarUtilTest.java`

**Interfaces:**
- Produces: 
  - `boolean isTradingDay(LocalDate date)`
  - `LocalDate getFirstTradingDayOfMonth(int year, int month)`
  - `boolean isTodayFirstTradingDayOfMonth(LocalDate today)`
  - `LocalDate getMonthlyExpiryThursday(int year, int month)`

- [ ] **Step 1: Write failing unit test for `NseTradingCalendarUtil`**
Test weekend detection, holiday exclusion (e.g. May 1st Maharashtra Day, Oct 2nd Gandhi Jayanti), and resolution of the 1st trading day of month (e.g. May 2026 starts on Monday May 4th).

- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew test --tests com.tradingbot.util.NseTradingCalendarUtilTest`
Expected: Compilation failure or missing class.

- [ ] **Step 3: Implement `NseTradingCalendarUtil`**
Implement weekend checks (`DayOfWeek.SATURDAY`, `SUNDAY`), NSE holiday set, `getFirstTradingDayOfMonth`, and `getMonthlyExpiryThursday`.

- [ ] **Step 4: Run test to verify it passes**
Run: `./gradlew test --tests com.tradingbot.util.NseTradingCalendarUtilTest`
Expected: BUILD SUCCESS (All tests pass).

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/tradingbot/util/NseTradingCalendarUtil.java src/test/java/com/tradingbot/util/NseTradingCalendarUtilTest.java
git commit -m "feat(condor): implement NseTradingCalendarUtil for holiday and cycle detection"
```

---

### Task 2: Strategy Configuration & Properties (`MonthlyPutCondorProperties`)

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/condor/config/MonthlyPutCondorProperties.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/tradingbot/strategy/condor/config/MonthlyPutCondorPropertiesTest.java`

**Interfaces:**
- Produces: Spring `@ConfigurationProperties(prefix = "trading-bot.strategy.put-condor")` class containing getters/setters for: `enabled`, `executionMode`, `lots`, `lotSize`, `strikeWidth`, `targetProfitPct`, `earlyExitTargetPct`, `earlyExitDaysBeforeExpiry`, `stopLossPct`, `upsideTriggerPts`, `entryTime`, `monitorIntervalSeconds`, `telegramAlerts`, `maxFreezeLimit`.

- [ ] **Step 1: Write failing unit test for `MonthlyPutCondorProperties`**
Verify default values and Spring property binding.

- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew test --tests com.tradingbot.strategy.condor.config.MonthlyPutCondorPropertiesTest`
Expected: Compilation failure.

- [ ] **Step 3: Implement `MonthlyPutCondorProperties` and update `application.properties`**
Add `@Configuration` and `@ConfigurationProperties` with defaults matching the design spec.

- [ ] **Step 4: Run test to verify it passes**
Run: `./gradlew test --tests com.tradingbot.strategy.condor.config.MonthlyPutCondorPropertiesTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/tradingbot/strategy/condor/config/MonthlyPutCondorProperties.java src/main/resources/application.properties src/test/java/com/tradingbot/strategy/condor/config/MonthlyPutCondorPropertiesTest.java
git commit -m "feat(condor): add MonthlyPutCondorProperties configuration binder"
```

---

### Task 3: Domain Models & State Representation

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/condor/model/PutCondorState.java`
- Create: `src/main/java/com/tradingbot/strategy/condor/model/PutCondorPosition.java`
- Create: `src/main/java/com/tradingbot/strategy/condor/model/PutCondorAdjustmentType.java`
- Create: `src/main/java/com/tradingbot/strategy/condor/model/PutCondorCycleHistory.java`
- Test: `src/test/java/com/tradingbot/strategy/condor/model/PutCondorPositionTest.java`

**Interfaces:**
- Produces:
  - `enum PutCondorState { IDLE, CONDOR_ACTIVE, UPSIDE_FINANCED, SWEET_SPOT_LOCK, SQUARED_OFF }`
  - `enum PutCondorAdjustmentType { NONE, UPSIDE_FINANCING, SWEET_SPOT_ROLL, DEEP_CRASH_DEFENSE, GAMMA_SHIELD_EARLY_EXIT }`
  - `class PutCondorPosition`: tracks cycle expiry, spot at entry, K1-K4 strikes, tradingsymbols, entry prices, upside spread strikes, realized cash, and MTM history.
  - `class PutCondorCycleHistory`: records completed cycle outcomes for SQLite storage.

- [ ] **Step 1: Write unit tests for JSON serialization and MTM calculation on `PutCondorPosition`**
Test state transitions, leg PnL calculation, and JSON string round-trip.

- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew test --tests com.tradingbot.strategy.condor.model.PutCondorPositionTest`
Expected: Compilation failure.

- [ ] **Step 3: Implement domain models (`PutCondorState`, `PutCondorPosition`, `PutCondorAdjustmentType`, `PutCondorCycleHistory`)**
Include full getter/setter/builder and Jackson annotations.

- [ ] **Step 4: Run test to verify it passes**
Run: `./gradlew test --tests com.tradingbot.strategy.condor.model.PutCondorPositionTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/tradingbot/strategy/condor/model/ src/test/java/com/tradingbot/strategy/condor/model/
git commit -m "feat(condor): add domain models for Put Condor state and position tracking"
```

---

### Task 4: SQLite Persistence Repository (`SqlitePutCondorRepository`)

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/condor/repository/SqlitePutCondorRepository.java`
- Test: `src/test/java/com/tradingbot/strategy/condor/repository/SqlitePutCondorRepositoryTest.java`

**Interfaces:**
- Consumes: `PutCondorPosition`, `PutCondorCycleHistory`
- Produces:
  - `void saveActivePosition(PutCondorPosition position)`
  - `Optional<PutCondorPosition> loadActivePosition()`
  - `void clearActivePosition()`
  - `void saveCycleHistory(PutCondorCycleHistory history)`
  - `List<PutCondorCycleHistory> getHistory(int limit)`

- [ ] **Step 1: Write failing repository test with SQLite in-memory / temp DB**
Test saving active position, recovering it after simulated restart, updating it, and archiving to history.

- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew test --tests com.tradingbot.strategy.condor.repository.SqlitePutCondorRepositoryTest`
Expected: Compilation failure.

- [ ] **Step 3: Implement `SqlitePutCondorRepository`**
Create DDL schema for tables `put_condor_state` and `put_condor_history` if not exists, implement CRUD via Spring `JdbcTemplate` / `DataSource`.

- [ ] **Step 4: Run test to verify it passes**
Run: `./gradlew test --tests com.tradingbot.strategy.condor.repository.SqlitePutCondorRepositoryTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/tradingbot/strategy/condor/repository/ src/test/java/com/tradingbot/strategy/condor/repository/
git commit -m "feat(condor): implement SqlitePutCondorRepository for state persistence"
```

---

### Task 5: Freeze Limit Order Slicing & Execution Gateway (`PutCondorOrderSlicer`)

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/condor/service/PutCondorOrderSlicer.java`
- Test: `src/test/java/com/tradingbot/strategy/condor/service/PutCondorOrderSlicerTest.java`

**Interfaces:**
- Consumes: `ZerodhaBrokerGateway`, `MonthlyPutCondorProperties`
- Produces:
  - `List<Integer> calculateSlices(int totalQuantity, int lotSize, int maxFreezeLimit)`
  - `boolean executeBasket(List<CondorLegOrder> orders, ExecutionMode mode)`
  - `boolean executeLiquidation(List<CondorLegOrder> orders, ExecutionMode mode)`

- [ ] **Step 1: Write unit test for order slicing and margin priority sequencing**
Verify 50 lots (3,250 qty) slices into `[1625, 1625]`, 70 lots (4,550 qty) slices into `[1560, 1560, 1430]`, all divisible by 65, and that BUY orders execute before SELL orders.

- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew test --tests com.tradingbot.strategy.condor.service.PutCondorOrderSlicerTest`
Expected: Compilation failure.

- [ ] **Step 3: Implement `PutCondorOrderSlicer`**
Implement integer slice math, 200ms spacing, limit order price rounding, and delegation to `ZerodhaBrokerGateway` or simulated paper fills.

- [ ] **Step 4: Run test to verify it passes**
Run: `./gradlew test --tests com.tradingbot.strategy.condor.service.PutCondorOrderSlicerTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/tradingbot/strategy/condor/service/PutCondorOrderSlicer.java src/test/java/com/tradingbot/strategy/condor/service/PutCondorOrderSlicerTest.java
git commit -m "feat(condor): implement PutCondorOrderSlicer with freeze limit slicing"
```

---

### Task 6: Core Strategy Service & Dynamic Adjustments Engine (`MonthlyPutCondorService`)

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/condor/service/MonthlyPutCondorService.java`
- Test: `src/test/java/com/tradingbot/strategy/condor/service/MonthlyPutCondorServiceTest.java`

**Interfaces:**
- Consumes: `ShoonyaMarketDataService`, `ShoonyaOptionChainService`, `PutCondorOrderSlicer`, `SqlitePutCondorRepository`, `TelegramService`
- Produces:
  - `boolean evaluateAndEnterCycle(BigDecimal spotPrice)`
  - `void onMarketTick(BigDecimal currentSpot, LocalDate today)`
  - `void triggerUpsideAdjustment(BigDecimal spotPrice)`
  - `void triggerSweetSpotRoll(BigDecimal spotPrice)`
  - `void squareOffAll(String reason)`
  - `PutCondorPosition getActivePosition()`

- [ ] **Step 1: Write unit tests covering state machine transitions**
Test entry strike calculation ($K_1, K_2, K_3, K_4$), Target Profit exit (+6.0%), Adjustment A trigger (Spot $\ge$ ATM + 150), Adjustment B trigger (Spot $\le K_2$), Adjustment C trigger (Spot $\le K_4 - 100$), and Adjustment D trigger (T $\le$ 3 days & MTM $\ge$ +4.5%).

- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew test --tests com.tradingbot.strategy.condor.service.MonthlyPutCondorServiceTest`
Expected: Compilation failure.

- [ ] **Step 3: Implement `MonthlyPutCondorService`**
Implement the complete state machine lifecycle, Shoonya quote resolution, MTM calculation, adjustment triggers, Telegram alerts, and SQLite state syncing.

- [ ] **Step 4: Run test to verify it passes**
Run: `./gradlew test --tests com.tradingbot.strategy.condor.service.MonthlyPutCondorServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/tradingbot/strategy/condor/service/MonthlyPutCondorService.java src/test/java/com/tradingbot/strategy/condor/service/MonthlyPutCondorServiceTest.java
git commit -m "feat(condor): implement MonthlyPutCondorService core decision engine"
```

---

### Task 7: Automated Lifecycle Schedulers (`MonthlyPutCondorScheduler`)

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/condor/scheduler/MonthlyPutCondorScheduler.java`
- Test: `src/test/java/com/tradingbot/strategy/condor/scheduler/MonthlyPutCondorSchedulerTest.java`

**Interfaces:**
- Consumes: `MonthlyPutCondorService`, `ShoonyaMarketDataService`, `NseTradingCalendarUtil`
- Produces:
  - `@Scheduled` job for morning cycle entry (`0 30 10 * * MON-FRI`)
  - `@Scheduled` job for 60s active tick monitor (`0 */1 9-15 * * MON-FRI`)
  - `@Scheduled` job for 15:10 expiry settlement (`0 10 15 * * THU`)
  - `@Scheduled` job for 15:35 daily EOD Telegram digest

- [ ] **Step 1: Write unit tests for scheduler triggers and calendar validation**
Verify morning job only invokes entry on the 1st trading day of month when state is `IDLE`.

- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew test --tests com.tradingbot.strategy.condor.scheduler.MonthlyPutCondorSchedulerTest`
Expected: Compilation failure.

- [ ] **Step 3: Implement `MonthlyPutCondorScheduler`**
Implement Spring cron jobs, market hours gate, and exception handling.

- [ ] **Step 4: Run test to verify it passes**
Run: `./gradlew test --tests com.tradingbot.strategy.condor.scheduler.MonthlyPutCondorSchedulerTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/tradingbot/strategy/condor/scheduler/MonthlyPutCondorScheduler.java src/test/java/com/tradingbot/strategy/condor/scheduler/MonthlyPutCondorSchedulerTest.java
git commit -m "feat(condor): implement MonthlyPutCondorScheduler automated cron jobs"
```

---

### Task 8: REST Controller & Management API (`MonthlyPutCondorController`)

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/condor/controller/MonthlyPutCondorController.java`
- Test: `src/test/java/com/tradingbot/strategy/condor/controller/MonthlyPutCondorControllerTest.java`

**Interfaces:**
- Consumes: `MonthlyPutCondorService`, `SqlitePutCondorRepository`
- Produces:
  - `GET /api/strategy/put-condor/status`
  - `POST /api/strategy/put-condor/enter`
  - `POST /api/strategy/put-condor/adjust-upside`
  - `POST /api/strategy/put-condor/adjust-sweet-spot`
  - `POST /api/strategy/put-condor/exit`
  - `GET /api/strategy/put-condor/history`

- [ ] **Step 1: Write WebMvcTest for `MonthlyPutCondorController`**
Test all REST endpoints using MockMvc.

- [ ] **Step 2: Run test to verify it fails**
Run: `./gradlew test --tests com.tradingbot.strategy.condor.controller.MonthlyPutCondorControllerTest`
Expected: Compilation failure.

- [ ] **Step 3: Implement `MonthlyPutCondorController`**
Implement REST controller with standardized JSON responses and error handling.

- [ ] **Step 4: Run test to verify it passes**
Run: `./gradlew test --tests com.tradingbot.strategy.condor.controller.MonthlyPutCondorControllerTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/tradingbot/strategy/condor/controller/MonthlyPutCondorController.java src/test/java/com/tradingbot/strategy/condor/controller/MonthlyPutCondorControllerTest.java
git commit -m "feat(condor): implement MonthlyPutCondorController REST endpoints"
```

---

### Task 9: Full Build Verification & End-to-End Integration

**Files:**
- Test: `src/test/java/com/tradingbot/strategy/condor/MonthlyPutCondorIntegrationTest.java`
- Modify: `.env.example`

**Interfaces:**
- Consumes: Complete Put Condor subsystem

- [ ] **Step 1: Write full lifecycle integration test**
Simulate complete cycle: Entry $\to$ Spot moves UP $\to$ Adjustment A triggered $\to$ Target Hit $\to$ Square-off $\to$ History archived.

- [ ] **Step 2: Update `.env.example` with Put Condor configuration keys**
Add all `CONDOR_*` environment variables to `.env.example`.

- [ ] **Step 3: Run full test suite and build**
Run: `./gradlew clean test check`
Expected: BUILD SUCCESS (All tests pass, spotless check passes).

- [ ] **Step 4: Commit**
```bash
git add .env.example src/test/java/com/tradingbot/strategy/condor/MonthlyPutCondorIntegrationTest.java
git commit -m "feat(condor): complete end-to-end integration and environment configuration"
```
