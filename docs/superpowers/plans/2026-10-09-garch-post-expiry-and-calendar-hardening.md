# GARCH Post-Expiry Calendar & Option Chain Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement exact post-expiry calendar detection (`isFirstTradingDayPostMonthlyTuesdayExpiry`), weekday scheduling, strike floor positivity clamping, and Shoonya futures contract resolution without impacting LVR or other strategies.

**Architecture:** Add `isFirstTradingDayPostMonthlyTuesdayExpiry` in `NseTradingCalendarUtil`, update `MonthlyRangeScheduler` to evaluate weekdays, clamp strikes in `MonthlyRangeCalculator`, and resolve near-month futures in `ShoonyaOptionChainService`.

**Tech Stack:** Java 21, Spring Boot 3.3.5, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-10-09-garch-post-expiry-and-calendar-hardening-design.md`

## Global Constraints

- No breaking changes to LVR or other strategy contracts.
- Strictly adhere to `Asia/Kolkata` (IST) exchange calendar.
- Pass `./gradlew spotlessApply` and `./gradlew test`.

---

### Task 1: Post-Expiry Calendar Resolution in `NseTradingCalendarUtil`

**Files:**
- Modify: `src/main/java/com/tradingbot/util/NseTradingCalendarUtil.java`
- Test: `src/test/java/com/tradingbot/util/NseTradingCalendarUtilTest.java`

- [ ] **Step 1: Write failing tests in `NseTradingCalendarUtilTest` for June 2026 (June 30 -> July 1), March 2026 (March 31 -> April 1), and holiday rollbacks**
- [ ] **Step 2: Run tests to verify failure**
- [ ] **Step 3: Implement `isFirstTradingDayPostMonthlyTuesdayExpiry(LocalDate date)` in `NseTradingCalendarUtil`**
- [ ] **Step 4: Run tests to verify pass**
- [ ] **Step 5: Commit**

---

### Task 2: Weekday Post-Expiry Trigger in `MonthlyRangeScheduler` & Strike Clamping in `MonthlyRangeCalculator`

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/monthlyrange/scheduler/MonthlyRangeScheduler.java`
- Modify: `src/main/java/com/tradingbot/strategy/monthlyrange/engine/MonthlyRangeCalculator.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/tradingbot/strategy/monthlyrange/scheduler/MonthlyRangeSchedulerTest.java`
- Test: `src/test/java/com/tradingbot/strategy/monthlyrange/engine/MonthlyRangeCalculatorTest.java`

- [ ] **Step 1: Write failing tests in `MonthlyRangeSchedulerTest` and `MonthlyRangeCalculatorTest`**
- [ ] **Step 2: Run tests to verify failure**
- [ ] **Step 3: Implement post-expiry trigger in scheduler and positivity clamp in calculator**
- [ ] **Step 4: Run tests to verify pass**
- [ ] **Step 5: Commit**

---

### Task 3: Single Stock Futures Symbol Resolution in `ShoonyaOptionChainService`

**Files:**
- Modify: `src/main/java/com/tradingbot/marketdata/ShoonyaOptionChainService.java`
- Test: `src/test/java/com/tradingbot/marketdata/ShoonyaOptionChainServiceTest.java` (or new/existing tests)

- [ ] **Step 1: Write test verifying futures resolution for stock option chains**
- [ ] **Step 2: Run test to verify failure/baseline**
- [ ] **Step 3: Update `getIndexOptionChain` to resolve near-month futures `tsym` for single stocks when `marketDataService != null`**
- [ ] **Step 4: Run test to verify pass**
- [ ] **Step 5: Commit**

---

### Task 4: Full Suite Verification (Zero Impact on LVR/CAR) & Spotless

**Files:**
- All tests

- [ ] **Step 1: Run `./gradlew spotlessApply`**
- [ ] **Step 2: Run full test suite `./gradlew test`**
- [ ] **Step 3: Commit and push**
