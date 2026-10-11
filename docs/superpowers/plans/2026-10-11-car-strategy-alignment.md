# CAR Strategy Alignment with Mahesh Kaushik Rules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align the Cumulative Average Reversal (CAR) strategy math engine, universe definition, and signal logic with Mahesh Chandra Kaushik's official rules and Google Finance spreadsheet (`Genius Stock CAR`).

**Architecture:** Update `CarCalculator` to anchor on the 52-week calendar highest `high` (instead of closing high), compute cumulative averages with full precision (avoiding premature 2-decimal truncation that breaks streaks), evaluate a 10-session window with 9 strict day-to-day increments (`SUMPRODUCT = 9`), and allow scanning a user portfolio watchlist / held stocks in addition to or instead of hardcoded Nifty 100.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, AssertJ, Jackson.

**Spec:** `docs/superpowers/specs/2026-03-30-car-weekly-gtt-strategy-design.md` and Mahesh Chandra Kaushik CAR video / Google Finance template (`1cCnSsUrbOS3TX8VcxLyZsybMwGoDvnIDytwkxKET3LQ`).

## Global Constraints
- Anchor Day $H$ must be determined by the maximum daily `high` in the 52-week calendar window (`asOf.minusDays(364)` / `52 * 7` days).
- Cumulative average series begins at Anchor Day $H$ with Close($H$).
- Intermediate cumulative average calculation must maintain high precision (minimum 4 decimals or unrounded `BigDecimal`) so subtle price increments are preserved.
- Tail evaluation must mirror `=IF(SUMPRODUCT(--(G2:G10 > G3:G11)) = 9, "BUY / AVERAGE OUT", "AVOID / HOLD")`: exactly 9 consecutive daily increases across the latest 10 trading sessions.
- Preserve backward compatibility with existing configuration properties while correcting defaults and semantics.

---

### Task 1: Fix Anchor Detection in `CarCalculator` (Highest High vs Highest Close & Calendar Lookback)

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/car/CarCalculator.java`
- Test: `src/test/java/com/tradingbot/strategy/car/CarCalculatorTest.java`

**Interfaces:**
- Consumes: `List<Candle> dailyCandles`, `LocalDate asOf`
- Produces: `CarAnalysisResult` with anchor based on 52-week maximum `high()`.

- [ ] **Step 1: Write failing unit test in `CarCalculatorTest.java` verifying anchor uses High instead of Close**
Test a scenario where a candle has an intraday spike high on Day A (High = 1000, Close = 900), but highest close was on Day B (High = 950, Close = 940). Verify anchor date is Day A.

- [ ] **Step 2: Run test to confirm failure**
Confirm test fails because current code anchors on Day B (Close 940).

- [ ] **Step 3: Update `CarCalculator.java` anchor selection logic**
- Change lookback window from index offset (`dailyCandles.size() - lookbackDays`) to calendar cutoff: `asOf.minusWeeks(52)` (or `asOf.minusDays(365)`).
- Find anchor by `c.high() != null && c.high().compareTo(highestHigh) > 0`.
- Record `highestHigh` as the anchor peak price, and start `postAnchorCandles` from that anchor candle.

- [ ] **Step 4: Run unit test to confirm pass**
Ensure `testRelaxoWorkedExampleCumulativeAverageCalculation` and new anchor test pass.

---

### Task 2: Fix Precision and 9-Increment Streak Logic in `CarCalculator`

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/car/CarCalculator.java`
- Modify: `src/main/java/com/tradingbot/strategy/car/config/CarWeeklyProperties.java`
- Test: `src/test/java/com/tradingbot/strategy/car/CarCalculatorTest.java`

**Interfaces:**
- Consumes: `List<BigDecimal> closes`
- Produces: `List<BigDecimal> cumAverages` (scaled to 4-6 decimals or exact arithmetic) and `isCarPositive` (based on 9 consecutive increments across the last 10 sessions).

- [ ] **Step 1: Write failing unit test for 10-day window (9 daily increments) with subtle fractions**
Test a 10-day tail where daily averages increase by ₹0.003 (e.g. 284.221 -> 284.224 -> 284.227). Verify `isCarPositive` is `true` and streak is recognized as 9 increments.

- [ ] **Step 2: Run test to confirm failure**
Confirm it fails due to 2-decimal rounding (`284.22 == 284.22`) and `streak >= 10` requirement.

- [ ] **Step 3: Refactor `calculateCumulativeAverages` and streak check**
- In `calculateCumulativeAverages`, divide with scale 4 or 6 (e.g., `runningSum.divide(count, 4, RoundingMode.HALF_UP)`) instead of scale 2.
- In streak evaluation, check the last 10 sessions (or `requiredPositiveDays` sessions). For $N=10$ sessions, check for $N-1 = 9$ strict increments ($CumAvg_t > CumAvg_{t-1}$).
- Set `isCarPositive = streak >= (requiredPositiveDays - 1)`.

- [ ] **Step 4: Run unit tests to confirm pass**

---

### Task 3: Universe Configuration & Watchlist Support in `CarWeeklyGttService`

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/car/config/CarWeeklyProperties.java`
- Modify: `src/main/java/com/tradingbot/strategy/car/CarWeeklyGttService.java`
- Test: `src/test/java/com/tradingbot/strategy/car/CarWeeklyGttServiceTest.java`

**Interfaces:**
- Consumes: `CarWeeklyProperties.watchlistSymbols`, `portfolioState.getHoldings()`, `Nifty100Registry`
- Produces: Target scan universe matching user portfolio / custom watchlist.

- [ ] **Step 1: Add configurable watchlist property in `CarWeeklyProperties`**
Add `List<String> watchlistSymbols` (defaults to empty or customizable via `trading-bot.strategy.car-weekly.watchlist-symbols` / `.env`).
Add a flag `boolean scanOnlyWatchlistAndHoldings` (allowing users to focus strictly on their portfolio losing stocks and chosen candidates as in the video, rather than scanning all 100 Nifty stocks).

- [ ] **Step 2: Update `scanUniverse` in `CarWeeklyGttService`**
When `scanOnlyWatchlistAndHoldings` is true, universe is `Holdings U Watchlist`. When false, universe includes `NIFTY_100 U Holdings U Watchlist`.

- [ ] **Step 3: Write test in `CarWeeklyGttServiceTest` to verify universe filtering**

---

### Task 4: End-to-End Verification against Video Stocks (GAIL, POWERGRID, RELAXO, ADANIENT)

**Files:**
- Test: `src/test/java/com/tradingbot/strategy/car/CarStrategyEndToEndIntegrationTest.java`

- [ ] **Step 1: Write integration test validating GAIL, POWERGRID, RELAXO, ADANIENT against Google Finance expected signals**
Verify that GAIL triggers CAR-positive when its 52W high is on 2025-11-27 and 9 rising days exist, while RELAXO and ADANIENT yield AVOID/HOLD.

- [ ] **Step 2: Run all strategy tests**
Verify all tests pass cleanly.
