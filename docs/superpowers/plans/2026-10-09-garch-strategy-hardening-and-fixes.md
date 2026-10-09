# GARCH Monthly Option Range Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement mathematical rescaling, variance replay fixes, asymmetric skew multipliers, holiday-safe calendar rollbacks, and nearest-strike ATM resolution in the GARCH monthly option range strategy.

**Architecture:** Percentage-scaled Nelder-Mead optimization, 1-step terminal variance correction, asymmetric $k_{\text{PE}}/k_{\text{CE}}$ multipliers, holiday-aware calendar resolution in `NseTradingCalendarUtil`, and $\min |\text{strike} - \text{spot}|$ ATM straddle extraction.

**Tech Stack:** Java 21, Spring Boot 3.3.5, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-10-09-garch-strategy-hardening-and-fixes-design.md`

## Global Constraints

- Pure native Java logic.
- All calendar checks operate in `Asia/Kolkata` (IST) timezone.
- Pass `./gradlew spotlessApply` and `./gradlew test`.

---

### Task 1: Numerical Rescaling in `Garch11Optimizer` & Variance Replay Fix in `GarchVolForecaster`

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/monthlyrange/engine/Garch11Optimizer.java`
- Modify: `src/main/java/com/tradingbot/strategy/monthlyrange/engine/GarchVolForecaster.java`
- Test: `src/test/java/com/tradingbot/strategy/monthlyrange/engine/Garch11OptimizerTest.java`
- Test: `src/test/java/com/tradingbot/strategy/monthlyrange/engine/GarchVolForecasterTest.java`

- [ ] **Step 1: Write failing tests for numerical scaling invariance and terminal variance accuracy**
- [ ] **Step 2: Run tests to verify failure**
- [ ] **Step 3: Implement percentage return scaling in `Garch11Optimizer` and update variance replay loop in `GarchVolForecaster`**
- [ ] **Step 4: Run tests to verify they pass**
- [ ] **Step 5: Commit**

---

### Task 2: Holiday-Aware Last-Wednesday Calendar Resolution

**Files:**
- Modify: `src/main/java/com/tradingbot/util/NseTradingCalendarUtil.java`
- Test: `src/test/java/com/tradingbot/util/NseTradingCalendarUtilTest.java`

- [ ] **Step 1: Write failing test in `NseTradingCalendarUtilTest` for holiday rollback on last Wednesday**
- [ ] **Step 2: Run test to verify failure**
- [ ] **Step 3: Implement holiday rollback in `getLastWednesdayOfMonth` and `isLastWednesdayOfMonth`**
- [ ] **Step 4: Run test to verify they pass**
- [ ] **Step 5: Commit**

---

### Task 3: Asymmetric Downside Skew Multipliers & Nearest-Strike ATM Straddle Resolution

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/monthlyrange/config/MonthlyRangeProperties.java`
- Modify: `src/main/java/com/tradingbot/strategy/monthlyrange/engine/MonthlyRangeCalculator.java`
- Modify: `src/main/java/com/tradingbot/strategy/monthlyrange/service/MonthlyRangeService.java`
- Test: `src/test/java/com/tradingbot/strategy/monthlyrange/engine/MonthlyRangeCalculatorTest.java`

- [ ] **Step 1: Write failing tests for asymmetric PE/CE multipliers and fractional spot nearest ATM resolution**
- [ ] **Step 2: Run test to verify failure**
- [ ] **Step 3: Implement asymmetric multipliers, nearest strike search, wider OI window, and delivery risk notice**
- [ ] **Step 4: Run tests to verify they pass**
- [ ] **Step 5: Commit**

---

### Task 4: Full Test Suite Verification, Spotless & Push

**Files:**
- Test: All tests across the project

- [ ] **Step 1: Run `./gradlew spotlessApply`**
- [ ] **Step 2: Run full test suite `./gradlew test`**
- [ ] **Step 3: Commit and verify git history**
