# Fused IV, OI & Event-Month GARCH Strategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enhance the Monthly Option Range Strategy with an Event/Earnings detection engine ($k = 2.35\sigma$ in event months vs $2.0\sigma$ in normal months), ATM Straddle pricing, and Institutional Open Interest (OI) support/resistance fusion to guarantee >95% empirical accuracy.

**Architecture:** A multi-layer quantitative calculator evaluates calendar earnings cycles (Jan/Apr/Jul/Oct) and IV/HV ratios, adjusts the confidence multiplier $k$, queries live Option Chains for ATM Straddle move and Max OI strikes (Max Call OI resistance, Max Put OI support), and selects conservative outer boundary strikes.

**Tech Stack:** Java 21, Spring Boot 3.3.5, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-10-08-monthly-option-range-fused-iv-oi-design.md`

## Global Constraints

- Pure native Java logic without breaking existing strategy APIs or models.
- Support both live Shoonya Option Chain responses and standalone theoretical fallbacks.
- Strictly adhere to `StockFnoRegistry` strike step snapping.
- Pass `./gradlew spotlessApply` and `./gradlew test`.

## Review Focus

1. **Event month confidence expansion**: Ensure event months correctly expand multiplier to $2.35\sigma$ and calculate wider price boundaries.
2. **Conservative OI outer bounds**: Ensure `Final Safe PE` takes $\min(\text{GARCH PE}, \text{Max Put OI})$ and `Final Safe CE` takes $\max(\text{GARCH CE}, \text{Max Call OI})$.
3. **Graceful fallback when option chain / OI is unavailable**: Strategy must seamlessly fall back to GARCH-only bounds without crashing or NaN.
4. **Clean Telegram layout**: Formatted message must clearly highlight event regime, ATM Straddle move, OI support/resistance levels, and final recommended strikes.

---

### Task 1: Enhanced Properties & Forecast Model

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/monthlyrange/config/MonthlyRangeProperties.java`
- Modify: `src/main/java/com/tradingbot/strategy/monthlyrange/model/MonthlyRangeForecast.java`
- Test: `src/test/java/com/tradingbot/strategy/monthlyrange/model/MonthlyRangeModelTest.java`

- [ ] **Step 1: Write failing test in `MonthlyRangeModelTest`**
- [ ] **Step 2: Run test to verify failure**
- [ ] **Step 3: Update `MonthlyRangeProperties` (with event multiplier, earnings months list) and `MonthlyRangeForecast` (with event flags, OI strikes, straddle move)**
- [ ] **Step 4: Run test to verify passes**
- [ ] **Step 5: Commit**

---

### Task 2: Event Detection & Option Chain OI / Straddle Fusion in Calculator

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/monthlyrange/engine/MonthlyRangeCalculator.java`
- Test: `src/test/java/com/tradingbot/strategy/monthlyrange/engine/MonthlyRangeCalculatorTest.java`

- [ ] **Step 1: Write failing tests in `MonthlyRangeCalculatorTest` for earnings month multiplier, OI support/resistance fusion, and straddle move**
- [ ] **Step 2: Run test to verify failure**
- [ ] **Step 3: Implement event detection, dynamic multiplier ($2.35\sigma$), and OI boundary snapping in `MonthlyRangeCalculator`**
- [ ] **Step 4: Run test to verify passes**
- [ ] **Step 5: Commit**

---

### Task 3: Service Integration with Shoonya Option Chain & Telegram Advisory Formatting

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/monthlyrange/service/MonthlyRangeService.java`
- Test: `src/test/java/com/tradingbot/strategy/monthlyrange/service/MonthlyRangeServiceTest.java`

- [ ] **Step 1: Write failing tests in `MonthlyRangeServiceTest` with mocked OptionChain responses**
- [ ] **Step 2: Run test to verify failure**
- [ ] **Step 3: Update `MonthlyRangeService` to fetch option chain if available and format comprehensive Telegram message**
- [ ] **Step 4: Run test to verify passes**
- [ ] **Step 5: Commit**

---

### Task 4: Verification, Full Test Suite & Spotless

**Files:**
- Test: All tests across the project

- [ ] **Step 1: Run `./gradlew spotlessApply`**
- [ ] **Step 2: Run full test suite `./gradlew test`**
- [ ] **Step 3: Commit and verify git history**
