# Monthly Directional Probability Score & Broken-Wing Butterfly Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a quantitative Monthly Directional Probability Score for NIFTY 50 and the top 10 NIFTY stocks, ranking the **Top 3 Directional Picks** (with 1x2x1 Broken-Wing Butterfly option structures: Buy 1x 4OTM, Sell 2x 8OTM, Buy 1x 10OTM) and the **Top 3 Sideways Picks** (for delta-neutral GARCH strangles/condors), delivered alongside the Monthly Option Range GARCH report to Telegram and the REST API on the 1st trading session.

**Architecture:** A pure Java multi-factor quantitative engine (`MonthlyDirectionalProbabilityEngine`) evaluates macro trend (200/50 EMA), momentum/mean-reversion (20 SMA, RSI, MACD), GJR-GARCH asymmetric leverage ($\gamma$), and option open interest/PCR to calculate directional probabilities (`bullishProbabilityPct`, `bearishProbabilityPct`). When directional bias meets or exceeds 65%, a 1x2x1 broken-wing butterfly strike generator snaps ATM $\pm$ 4, 8, 10 OTM strikes using exchange strike steps. An executive ranker identifies the Top 3 Directional and Top 3 Sideways candidates across the universe, integrated into `MonthlyRangeReport` and formatted for Telegram and REST.

**Tech Stack:** Java 21, Spring Boot 3.3.5, TA4J / TA-Lib, Nelder-Mead GJR-GARCH, Spotless, PMD, SpotBugs.

**Spec:** NIFTY 50 + Top 10 Stock Monthly Quantitative Directional Model and 1x2x1 Broken-Wing Butterfly Option Structure (Buy 1x 4OTM, Sell 2x 8OTM, Buy 1x 10OTM CE/PE).

## Global Constraints
- Target universe includes `NIFTY50` plus the top 10 Nifty F&O heavyweights (`RELIANCE`, `TCS`, `HDFCBANK`, `INFY`, `ICICIBANK`, `SBIN`, `BHARTIARTL`, `ITC`, `LT`, `TATAMOTORS`).
- High-conviction threshold is set to $\ge 65.0\%$ (configurable via `MonthlyRangeProperties`).
- Ratio spread strikes must snap to official NSE strike steps from `StockFnoRegistry`.
- Never fail the monthly report if live option chain or feed is unavailable; degrade gracefully using historical price and GJR-GARCH volatility data.
- Must pass `./gradlew check -x test` (Spotless, PMD, SpotBugs baseline, OpenRewrite) and all unit tests.

## Review Focus
- High conviction ($\ge 65\%$): Correctly recommends 1x2x1 CE ratio spread for Bullish, PE ratio spread for Bearish.
- Neutral / low conviction ($< 65\%$): Flags `NEUTRAL` / `MODERATE`, marks butterfly `actionable = false`, and recommends holding delta-neutral GARCH range instead.
- Stock strike resolution: Correct step snapping across distinct prices (e.g. ₹50 step for NIFTY, ₹20 for RELIANCE, ₹2.5 for SBIN, ₹2 for ITC).
- Calendar scheduling: Dispatches on the 1st trading day of the calendar month as well as the post-expiry monthly rollover day.
- Telegram report readability: Cleanly formats probability bars, conviction badges, and leg-by-leg strike details.

---

### Task 1: Domain Models for Directional Probability, Broken-Wing Butterfly, and Universe Ranking

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/monthlyrange/model/MarketBias.java`
- Create: `src/main/java/com/tradingbot/strategy/monthlyrange/model/ConvictionLevel.java`
- Create: `src/main/java/com/tradingbot/strategy/monthlyrange/model/BrokenWingButterflyRecommendation.java`
- Create: `src/main/java/com/tradingbot/strategy/monthlyrange/model/MonthlyDirectionalScore.java`
- Create: `src/main/java/com/tradingbot/strategy/monthlyrange/model/MonthlyUniverseRanking.java`
- Test: `src/test/java/com/tradingbot/strategy/monthlyrange/model/MonthlyDirectionalScoreTest.java`

- [x] **Step 1: Write failing unit test for domain models**
  Define tests in `MonthlyDirectionalScoreTest` verifying construction, bias classification ($\ge 65\%$ `STRONG`, $55-64\%$ `MODERATE`, $45-54\%$ `NEUTRAL`), butterfly strike mapping, and universe ranking (extracting Top 3 directional by highest conviction and Top 3 sideways by lowest deviation from 50%).
- [x] **Step 2: Run test to confirm failure**
  Run `./gradlew test --tests "com.tradingbot.strategy.monthlyrange.model.MonthlyDirectionalScoreTest"`.
- [x] **Step 3: Implement domain models**
  - `MarketBias`: `BULLISH`, `BEARISH`, `NEUTRAL`
  - `ConvictionLevel`: `STRONG`, `MODERATE`, `NEUTRAL`
  - `BrokenWingButterflyRecommendation`: record storing `bias`, `atmStrike`, `wing1Strike` (4OTM, 1 lot), `bodyStrike` (8OTM, 2 lots), `wing2Strike` (10OTM, 1 lot), `optionType`, `actionable`, `reason`.
  - `MonthlyDirectionalScore`: record storing `symbol`, `spotPrice`, `bullishProbabilityPct`, `bearishProbabilityPct`, `bias`, `conviction`, `factors`, `butterflyRecommendation`.
  - `MonthlyUniverseRanking`: record storing `List<MonthlyDirectionalScore> topDirectionalPicks` (up to 3) and `List<MonthlyRangeForecast> topSidewaysPicks` (up to 3).
- [x] **Step 4: Run tests and confirm they pass**
  Verify with `./gradlew test --tests "com.tradingbot.strategy.monthlyrange.model.MonthlyDirectionalScoreTest"`.

---

### Task 2: Implement `MonthlyDirectionalProbabilityEngine`

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/monthlyrange/engine/MonthlyDirectionalProbabilityEngine.java`
- Test: `src/test/java/com/tradingbot/strategy/monthlyrange/engine/MonthlyDirectionalProbabilityEngineTest.java`

- [x] **Step 1: Write unit tests covering multi-factor probability evaluation**
  Create tests in `MonthlyDirectionalProbabilityEngineTest` covering:
  - Strong Bullish conditions: Price > 200 EMA, 50 > 200 EMA, RSI > 55, PCR > 1.25 $\rightarrow$ Bullish probability $\ge 65\%$ and CE butterfly generated.
  - Strong Bearish conditions: Price < 200 EMA, 50 < 200 EMA, RSI < 45, PCR < 0.75, high GJR gamma $\rightarrow$ Bearish probability $\ge 65\%$ and PE butterfly generated.
  - Choppy/Neutral market: Mixed signals $\rightarrow$ Probability around 50%, conviction `NEUTRAL`, butterfly marked non-actionable.
  - Missing option chain fallback: Valid probability generated using price and volatility metrics alone.
- [x] **Step 2: Run test to confirm failure**
  Run `./gradlew test --tests "com.tradingbot.strategy.monthlyrange.engine.MonthlyDirectionalProbabilityEngineTest"`.
- [x] **Step 3: Implement `MonthlyDirectionalProbabilityEngine`**
  - Implement EMA(200), EMA(50), SMA(20), RSI(14), MACD on daily candles.
  - Score factor weights: Trend (30%), Momentum (25%), GJR-GARCH Asymmetric Leverage (20%), Option Chain PCR/OI (25%).
  - Snap strikes to official step from `StockFnoRegistry.getStrikeStep(symbol)` for 4 OTM, 8 OTM, and 10 OTM legs.
- [x] **Step 4: Run engine tests and verify pass**
  Run `./gradlew test --tests "com.tradingbot.strategy.monthlyrange.engine.MonthlyDirectionalProbabilityEngineTest"`.

---

### Task 3: Update `MonthlyRangeProperties` with Top 10 NIFTY Universe & Probability Config

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/monthlyrange/config/MonthlyRangeProperties.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/tradingbot/strategy/monthlyrange/model/MonthlyRangeModelTest.java`

- [x] **Step 1: Add unit test in `MonthlyRangeModelTest`**
  Verify default symbols list includes `NIFTY50` plus top 10 stocks (`RELIANCE`, `TCS`, `HDFCBANK`, `INFY`, `ICICIBANK`, `SBIN`, `BHARTIARTL`, `ITC`, `LT`, `TATAMOTORS`), and probability thresholds (`strongConvictionThreshold = 65.0`).
- [x] **Step 2: Implement properties updates**
  - Update default symbols in `MonthlyRangeProperties.java`.
  - Add `directionalProbabilityEnabled` (default `true`).
  - Add `strongConvictionThreshold` (default `65.0`).
  - Add `triggerOnFirstTradingDayOfMonth` (default `true`).
  - Expose getters and setters.
- [x] **Step 3: Update `application.properties`**
  Add corresponding property keys with defaults.
- [x] **Step 4: Verify test passes**
  Run `./gradlew test --tests "com.tradingbot.strategy.monthlyrange.model.MonthlyRangeModelTest"`.

---

### Task 4: Integrate Directional Score and Top 3 Rankings into `MonthlyRangeReport` and `MonthlyRangeCalculator`

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/monthlyrange/model/MonthlyRangeForecast.java`
- Modify: `src/main/java/com/tradingbot/strategy/monthlyrange/model/MonthlyRangeReport.java`
- Modify: `src/main/java/com/tradingbot/strategy/monthlyrange/engine/MonthlyRangeCalculator.java`
- Modify: `src/main/java/com/tradingbot/strategy/monthlyrange/service/MonthlyRangeService.java`
- Test: `src/test/java/com/tradingbot/strategy/monthlyrange/engine/MonthlyRangeCalculatorTest.java`
- Test: `src/test/java/com/tradingbot/strategy/monthlyrange/service/MonthlyRangeServiceTest.java`

- [x] **Step 1: Write test expectations for `MonthlyRangeCalculatorTest`**
  Verify `MonthlyRangeForecast` now contains `MonthlyDirectionalScore` alongside GARCH bands, and `MonthlyRangeReport` contains `MonthlyUniverseRanking` ranking the Top 3 directional and Top 3 sideways picks.
- [x] **Step 2: Update `MonthlyRangeForecast` and `MonthlyRangeReport` records**
  Add `MonthlyDirectionalScore directionalScore` to `MonthlyRangeForecast`, and `MonthlyUniverseRanking ranking` to `MonthlyRangeReport`.
- [x] **Step 3: Update `MonthlyRangeCalculator` and `MonthlyRangeService`**
  Wire `MonthlyDirectionalProbabilityEngine` into calculation and rank candidates into Top 3 Directional (highest conviction $\ge 65\%$) and Top 3 Sideways (lowest $|p - 50\%|$ with stable 2-SD GARCH bands).
- [x] **Step 4: Run calculator and service tests**
  Run `./gradlew test --tests "com.tradingbot.strategy.monthlyrange.engine.MonthlyRangeCalculatorTest"` and `./gradlew test --tests "com.tradingbot.strategy.monthlyrange.service.MonthlyRangeServiceTest"`.

---

### Task 5: Enhance Telegram Alerts & Scheduler on 1st Trading Day of Month

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/monthlyrange/service/MonthlyRangeService.java`
- Modify: `src/main/java/com/tradingbot/strategy/monthlyrange/scheduler/MonthlyRangeScheduler.java`
- Test: `src/test/java/com/tradingbot/strategy/monthlyrange/service/MonthlyRangeServiceTest.java`
- Test: `src/test/java/com/tradingbot/strategy/monthlyrange/scheduler/MonthlyRangeSchedulerTest.java`

- [x] **Step 1: Write test for Telegram formatting**
  Verify `formatTelegramMessage` includes:
  - 🏆 **Executive Summary: Top 3 Directional Picks** (with their 1x2x1 Broken-Wing Butterfly legs: 4OTM, 8OTM, 10OTM)
  - 🛡️ **Executive Summary: Top 3 Sideways / Range-Bound Picks** (with their Safe OTM Strangle / Iron Condor strikes)
  - Full symbol-by-symbol breakdown with GARCH bands and probability scores.
- [x] **Step 2: Implement Telegram formatting in `MonthlyRangeService`**
  Format the Top 3 Directional and Top 3 Sideways sections at the top of the Telegram alert.
- [x] **Step 3: Update `MonthlyRangeScheduler`**
  Check `isTodayFirstTradingDayOfMonth(date)` OR `isFirstTradingDayPostMonthlyTuesdayExpiry(date)` so the report runs on the 1st trading day of each month as requested.
- [x] **Step 4: Run scheduler and formatting tests**
  Run `./gradlew test --tests "com.tradingbot.strategy.monthlyrange.scheduler.MonthlyRangeSchedulerTest"` and `./gradlew test --tests "com.tradingbot.strategy.monthlyrange.service.MonthlyRangeServiceTest"`.

---

### Task 6: Full Build Verification & Quality Gate

**Files:**
- All modified & created files

- [x] **Step 1: Run Spotless & OpenRewrite check**
  Run `./gradlew spotlessCheck`.
- [x] **Step 2: Run PMD & SpotBugs verification**
  Run `./gradlew check -x test`.
- [x] **Step 3: Run full offline test suite**
  Run `./gradlew test`.
- [x] **Step 4: Verify complete application build**
  Run `./gradlew build -x test`.
