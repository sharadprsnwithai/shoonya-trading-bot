# LVR Strategy Comprehensive Bug Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve all 75 audit findings in `LVR_BUG_AUDIT.md` across the Lowest Volume Reversal (LVR) strategy, trade execution consumers, broker gateways, position modeling, scanner, and controllers to ensure completely safe, synchronized, and resilient live order execution and position lifecycle management.

**Architecture:** 
- Fix trade consumer direction mapping (`ShoonyaTradeConsumer`, `ZerodhaTradeConsumer`) so Option Buying `ENTRY_SHORT` routes to BUY PE (not SELL PE), and format broker symbols and order types correctly.
- Synchronize position lifecycle and state transitions in `LowestVolumeReversalService` using atomic operations (`putIfAbsent`, state checks inside critical sections, per-position error handling, idempotent exit tracking).
- Correct candle evaluation (filter out forming candle, use intraday session candles, accurate VWAP/EMA calculations, and enable 10-EMA trailing during 5m cycle closes).
- Robust market scanner & sentiment engine (strict breadth coverage check with retry until 10:00 AM, state reset of `niftyBullish`, and atomic candidate replenishment).
- Risk management & pricing guardrails (unrealized floating loss accounting in circuit breaker, reject fabricated option entry premiums, strict tick-size 0.05 alignment, and rate-limited sector checks).

**Tech Stack:** Java 21, Spring Boot, Project Reactor / Signal Bus, JUnit 5, Mockito, Shoonya / Kite REST APIs.

**Spec:** `LVR_BUG_AUDIT.md`, `lowest_volume_reversal_spec.md`, `lvr_30sec_live_check_spec.md`, `lvr_option_buying_spec.md`, `lvr_scanner_token_fix_and_retry_spec.md`.

## Global Constraints
- Every monetary calculation and price must be 0.05 tick-size aligned and use `BigDecimal` / `RoundingMode.HALF_UP`.
- Thread safety: `activeSetups`, `openPositions`, and strategy flags must use `volatile` / atomic operations / locks to prevent race conditions across the 30s tick, 5m scheduler cron, and HTTP threads.
- No fabricated prices or silent fallbacks for trade entries or live exits.

---

### Task 1: Trade Signal Consumers & Broker Gateways Execution Safety
**Bugs Addressed:** 2, 3, 4, 17, 34, 39

**Files:**
- Modify: `src/main/java/com/tradingbot/execution/consumer/ShoonyaTradeConsumer.java`
- Modify: `src/main/java/com/tradingbot/execution/consumer/ZerodhaTradeConsumer.java`
- Modify: `src/main/java/com/tradingbot/execution/gateway/ZerodhaBrokerGateway.java`
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Test: `src/test/java/com/tradingbot/execution/consumer/ShoonyaTradeConsumerTest.java`
- Test: `src/test/java/com/tradingbot/execution/consumer/ZerodhaTradeConsumerTest.java`

- [ ] **Step 1: Write failing tests for Option Buying direction, Zerodha SL trigger logic, broker trading symbol resolution on Target exits, and non-zero partial exit quantities**
- [ ] **Step 2: Run tests to verify failure**
- [ ] **Step 3: Fix `ShoonyaTradeConsumer.java` and `ZerodhaTradeConsumer.java`:**
  - In Option mode (when metadata `instrumentType == OPTION` or symbol contains CE/PE):
    - `ENTRY_LONG` (Buy CE) -> `TransactionType.BUY`
    - `ENTRY_SHORT` (Buy PE) -> `TransactionType.BUY`
    - `EXIT_LONG` / `PARTIAL_EXIT_LONG` (Sell CE) -> `TransactionType.SELL`
    - `EXIT_SHORT` / `PARTIAL_EXIT_SHORT` (Sell PE) -> `TransactionType.SELL`
  - In Futures mode:
    - `ENTRY_LONG` -> `TransactionType.BUY`, `ENTRY_SHORT` -> `TransactionType.SELL`, `EXIT_LONG` -> `TransactionType.SELL`, `EXIT_SHORT` -> `TransactionType.BUY`.
  - In `ZerodhaTradeConsumer`: Do not pass spot stop loss as option trigger price; for market/limit entry and exits, set `triggerPrice` only if true stop order is desired on the derivative itself.
- [ ] **Step 4: Fix 1:2 Target exits in `LowestVolumeReversalService.java:1380, 1421` to use `resolveBrokerTradingSymbol(pos)` instead of `pos.getContractSymbol()`, and calculate exit quantity before position state change so `remainingQuantity` is never 0 on publish.**
- [ ] **Step 5: Run tests and verify PASS**
- [ ] **Step 6: Commit:** `git commit -m "fix(execution): correct option transaction types, broker symbols and zerodha trigger routing"`

---

### Task 2: LowestVolumePaperPosition & Position Math
**Bugs Addressed:** 26, 34, 35, 56, 63

**Files:**
- Modify: `src/main/java/com/tradingbot/model/strategy/LowestVolumePaperPosition.java`
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Test: `src/test/java/com/tradingbot/model/strategy/LowestVolumePaperPositionTest.java`

- [ ] **Step 1: Write failing tests for partial exit quantity consistency, tick-aligned stop loss/target, strike resolution, and option delta-adjusted risk calculation**
- [ ] **Step 2: Run tests to verify failure**
- [ ] **Step 3: Update `LowestVolumePaperPosition.java`:**
  - Align partial booking quantities between paper model and published signals: `int exitQty = (this.totalLots + 1) / 2 * this.lotSize;`
  - Add theta decay factor or proper premium estimation guard in `estimateOptionPremium`.
- [ ] **Step 4: Update `resolveAtmStrike` and tick rounding in `LowestVolumeReversalService.java`:**
  - Guard `resolveAtmStrike` against returning strike 0 (ensure minimum valid strike step).
  - Round all stop losses and targets to nearest 0.05 step (`Math.round(val * 20.0) / 20.0`).
  - Calculate `plannedRisk` taking into account option premium / delta points instead of 1:1 spot points.
- [ ] **Step 5: Run tests and verify PASS**
- [ ] **Step 6: Commit:** `git commit -m "fix(position): align partial exit sizing, tick rounding and atm strike resolution"`

---

### Task 3: Service Concurrency, State Synchronization & Position Lifecycle Safety
**Bugs Addressed:** 1, 5, 6, 8, 12, 19, 25, 29, 38, 58, 59, 60, 67, 72

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Modify: `src/main/java/com/tradingbot/scheduler/LowestVolumeReversalScheduler.java`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

- [ ] **Step 1: Write failing tests for double-entry race condition, duplicate SL exit calls, hard exit error recovery, daily reset safety, and atomic setup transitions**
- [ ] **Step 2: Run tests to verify failure**
- [ ] **Step 3: Implement concurrency & lifecycle fixes in `LowestVolumeReversalService.java`:**
  - In `executePositionEntry`: Re-verify `setup.getState() == TRIGGER_ARMED` and `!openPositions.containsKey(symbol)` inside the `synchronized` block; use `openPositions.putIfAbsent(symbol, position)`.
  - In `evaluateOpenPositions`: Check `if (pos.isClosed()) continue;` at the start of SL, Target, and EMA trailing evaluation blocks.
  - In `executeHardExit`: Wrap each position exit in its own `try/catch`, call `openPositions.remove(symbol)` per position upon exit, and only square off open positions.
  - In `resetDaily`: If `openPositions` contains active trades, issue warning and square off before clearing state; reset `niftyBullish` and daily date latch.
  - In `runCycle` & scheduler: Add reentrancy guard (`isCycleRunning`), make toggle fields `volatile`, ensure `scheduledDailyReset` respects `schedulerEnabled`.
  - In position evaluation: If `strategyEnabled` is false, continue to allow exit/risk management for already open positions.
- [ ] **Step 4: Run tests and verify PASS**
- [ ] **Step 5: Commit:** `git commit -m "fix(core): synchronize position entries, exits, hard exits, and lifecycle state"`

---

### Task 4: Strategy Rules, Candle Validation & Indicators
**Bugs Addressed:** 9, 15, 20, 21, 22, 23, 52, 61, 62, 65

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeCandleEngineTest.java`

- [ ] **Step 1: Write failing tests for 10-EMA trailing exit during 5m candle close before 13:00, forming candle exclusion, session candle filtering, and trigger breach high/low checks**
- [ ] **Step 2: Run tests to verify failure**
- [ ] **Step 3: Implement fixes in `LowestVolumeReversalService.java`:**
  - Pass `isCandleClose = true` in 5-min `runCycle` candle evaluation (while 30s live tick passes `isCandleClose = false`).
  - In `processCandidateSetups` / `evaluateCandleSequence`: Ignore any candle whose end timestamp (`c.timestamp() + 300s`) is in the future (still-forming candle).
  - Filter historical candles strictly to the current trading date `LocalDate.now(clock)`; do not fall back to previous session's candles when today's candles are fewer than 3.
  - Compute intraday 10-EMA and VWAP using only today's session candles.
  - In `checkSpotTriggerBreach`: Check both live quote LTP and high/low (`h` / `l`).
  - Fix armed timeout comparison (`>= setupTimeoutCandles`).
- [ ] **Step 4: Run tests and verify PASS**
- [ ] **Step 5: Commit:** `git commit -m "fix(strategy): enable 10-EMA trailing, ignore forming candles, and validate intraday quotes"`

---

### Task 5: Scanner, Sentiment & Reservoir Lifecycle
**Bugs Addressed:** 10, 11, 16, 24, 40, 46, 50, 53, 54, 55, 69, 75

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalScanner.java`
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Modify: `src/main/java/com/tradingbot/controller/LowestVolumeStrategyController.java`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalScannerTest.java`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

- [ ] **Step 1: Write failing tests for breadth coverage threshold, scan retry on insufficient quotes, `niftyBullish` updates in mid-morning refresh, and reservoir replenishment**
- [ ] **Step 2: Run tests to verify failure**
- [ ] **Step 3: Implement scanner & reservoir fixes:**
  - In `LowestVolumeReversalScanner`: Check minimum quote coverage (require at least 40 of 50 Nifty 50 constituents before deciding market sentiment).
  - In `runMorningUniverseScan`: If quote count < minimum coverage, do NOT lock `universeScanCompletedToday = true`; allow retry on subsequent cycles up to 10:00 AM cutoff.
  - In `runMidMorningUniverseRefresh`: Pass configured `minBreadthPct` to `evaluateMarketSentiment`, and update `this.niftyBullish = (sentiment == LowestVolumeDirection.LONG)` when sentiment is confirmed.
  - In `LowestVolumeStrategyController`: Guard `/morning-scan` endpoint to prevent wiping active setups if live trades are in progress.
  - Implement equal-volume tie breaking (most recent candle becomes trigger).
- [ ] **Step 4: Run tests and verify PASS**
- [ ] **Step 5: Commit:** `git commit -m "fix(scanner): enforce breadth coverage, retry scan until 10am, and align sentiment state"`

---

### Task 6: Risk Management, Pricing Guardrails & Filters
**Bugs Addressed:** 13, 14, 18, 27, 28, 30, 31, 32, 36, 41, 42, 45, 57

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Modify: `src/main/java/com/tradingbot/registry/StockFnoRegistry.java`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

- [ ] **Step 1: Write failing tests for circuit breaker unrealized PnL, rejection of fake option entry premiums, rate-limited sector filters, and fail-closed VWAP/sector checks**
- [ ] **Step 2: Run tests to verify failure**
- [ ] **Step 3: Implement risk and pricing fixes:**
  - In `isDailyCircuitBreakerTripped` & `calculateTodayRealizedPnl`: Include unrealized floating P&L of `openPositions` in total daily risk.
  - In `executePositionEntry`: If `instrumentType == OPTIONS` and `fetchOptionLtp` returns `<= 0`, abort entry and retry next tick (do not use fabricated formula).
  - In `checkLiveSectorAlignment`: Add backoff/cooldown on sector rejection so setup does not spam Telegram and API calls every 30s.
  - In VWAP and Sector confirmation: If data is missing/corrupted, treat as unconfirmed (fail-closed) rather than bypassing checks.
  - In `StockFnoRegistry`: Ensure fallback resolution logs a clear warning and validates instrument configuration.
  - Align expiry resolution between quotes and order symbols.
- [ ] **Step 4: Run tests and verify PASS**
- [ ] **Step 5: Commit:** `git commit -m "fix(risk): include floating losses in circuit breaker, reject fake premiums, and rate-limit sector filters"`

---

### Task 7: Replay Engine, Controller, Telegram & Diagnostics Polish
**Bugs Addressed:** 33, 43, 44, 48, 49, 51, 52, 64, 66, 68, 70, 71, 73, 74

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Modify: `src/main/java/com/tradingbot/controller/LowestVolumeStrategyController.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/tradingbot/controller/LowestVolumeStrategyControllerTest.java`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

- [ ] **Step 1: Write failing tests for replay isolation, Telegram money formatting with `Locale.US`, `/status` unrealized PnL, and clock consistency**
- [ ] **Step 2: Run tests to verify failure**
- [ ] **Step 3: Implement diagnostics & polish fixes:**
  - In `replaySession`: Isolate replay state so it does not mutate live `openPositions` or publish live signals to the event bus; wrap alert toggle in `try/finally`.
  - Format Telegram currency strings using `Locale.US` (`String.format(Locale.US, ...)`) and print actual option exit premium instead of spot price for option exits.
  - Update `/status` endpoint to report both realized and unrealized floating P&L.
  - Clean up dead configuration properties and use injectable `clock` consistently across all date/time lookups.
  - Set default `max-attempts-per-symbol` to 2 in `application.properties` and dynamically read it in Telegram messages.
- [ ] **Step 4: Run tests and verify PASS**
- [ ] **Step 5: Commit:** `git commit -m "fix(diagnostics): isolate replay session, format telegram alerts, and expose unrealized pnl in status"`

---

### Task 8: Full Build Verification & Regression Testing
**Files:**
- All modified files across Tasks 1–7.

- [ ] **Step 1: Run complete test suite with `./gradlew test`**
- [ ] **Step 2: Verify zero test failures and check test coverage for all 75 audit points**
- [ ] **Step 3: Verify clean build with `./gradlew build -x test`**
- [ ] **Step 4: Final verification commit:** `git commit -m "chore(lvr): complete verification of all 75 lvr bug fixes"`
