# Monthly Option Range GARCH(1,1) Strategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a native Java quantitative GARCH(1,1) monthly option range forecasting strategy for NSE stocks (`RELIANCE`, `TCS`, `HDFCBANK`, `INFY`, `ICICIBANK`, `SBIN`, `TATAMOTORS`) and `NIFTY50` to calculate statistical range boundaries and safe option strikes every month on the last Wednesday at 10:00 AM IST (post last-Tuesday expiry), with REST endpoints and Telegram reports.

**Architecture:** A pure Java numerical GARCH(1,1) optimizer (Nelder-Mead MLE) and multi-step forecaster computes forward cumulative volatility and confidence channels (1-SD 68%, 2-SD 95%) from 2 years of daily candles. A calculator maps channels to valid strike increments using `StockFnoRegistry`, and an orchestrated service runs on the last Wednesday of the month or on-demand via REST/Telegram.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring Web, Jackson, TA4J / TA-Lib, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-10-08-monthly-option-range-garch-strategy-design.md`

## Global Constraints

- Java 21 LTS with standard Spring Boot idioms.
- Pure native Java mathematical optimization with zero Python runtime dependency.
- All timestamps and calendar checks operate in `Asia/Kolkata` (IST) timezone.
- Strikes must strictly snap to valid NSE strike steps from `StockFnoRegistry`.
- Code must format cleanly via Spotless (`./gradlew spotlessApply`) and pass all tests (`./gradlew test`).

## Review Focus

1. **Stationarity violation in optimizer**: Optimization must enforce $\omega > 0, \alpha \ge 0, \beta \ge 0, \alpha + \beta < 1.0$ with soft penalty barriers so variance never explodes or turns negative.
2. **Zero or low volatility fallback**: When input price series has zero variance or flat prices, fallback gracefully to historical standard deviation without crashing or NaN.
3. **Calendar post-expiry date detection**: Accurately detect whether today is the last Wednesday of the calendar month (immediately post last-Tuesday expiry) across leap years and holiday rollbacks.
4. **Strike step snapping**: Verify rounding down for Safe PE (floor) and rounding up for Safe CE (ceiling) with stock-specific increments (₹5, ₹10, ₹20, ₹50).
5. **REST & Telegram safety**: Missing candle data or network errors for a single stock must log a warning and not fail the entire batch report for other stocks.

---

### Task 1: Configuration & Domain Models

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/monthlyrange/config/MonthlyRangeProperties.java`
- Create: `src/main/java/com/tradingbot/strategy/monthlyrange/model/GarchModelParams.java`
- Create: `src/main/java/com/tradingbot/strategy/monthlyrange/model/MonthlyRangeForecast.java`
- Create: `src/main/java/com/tradingbot/strategy/monthlyrange/model/MonthlyRangeReport.java`
- Test: `src/test/java/com/tradingbot/strategy/monthlyrange/model/MonthlyRangeModelTest.java`

**Interfaces:**
- Produces: `MonthlyRangeProperties`, `GarchModelParams`, `MonthlyRangeForecast`, `MonthlyRangeReport`

- [ ] **Step 1: Write unit test for domain models and properties**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement `MonthlyRangeProperties` and records `GarchModelParams`, `MonthlyRangeForecast`, `MonthlyRangeReport`**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit**

---

### Task 2: Pure Java GARCH(1,1) Optimizer & Forecaster

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/monthlyrange/engine/Garch11Optimizer.java`
- Create: `src/main/java/com/tradingbot/strategy/monthlyrange/engine/GarchVolForecaster.java`
- Test: `src/test/java/com/tradingbot/strategy/monthlyrange/engine/Garch11OptimizerTest.java`
- Test: `src/test/java/com/tradingbot/strategy/monthlyrange/engine/GarchVolForecasterTest.java`

**Interfaces:**
- Consumes: `GarchModelParams`
- Produces: `Garch11Optimizer.fit(double[] returns)`, `GarchVolForecaster.forecastMonthlyVol(GarchModelParams params, double[] returns, int horizonDays)`

- [ ] **Step 1: Write unit tests for `Garch11Optimizer` and `GarchVolForecaster`**
  - Verify parameter estimation on test return vector with known persistence.
  - Verify stability on boundary constraints ($\alpha + \beta < 1.0$).
  - Verify multi-step variance propagation and cumulative monthly standard deviation.
- [ ] **Step 2: Run tests to verify failure**
- [ ] **Step 3: Implement `Garch11Optimizer` with Nelder-Mead MLE simplex algorithm and `GarchVolForecaster`**
- [ ] **Step 4: Run tests to verify all pass**
- [ ] **Step 5: Commit**

---

### Task 3: Monthly Range Calculator & Strike Step Resolution

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/monthlyrange/engine/MonthlyRangeCalculator.java`
- Test: `src/test/java/com/tradingbot/strategy/monthlyrange/engine/MonthlyRangeCalculatorTest.java`

**Interfaces:**
- Consumes: `Candle`, `Garch11Optimizer`, `GarchVolForecaster`, `StockFnoRegistry`
- Produces: `MonthlyRangeCalculator.calculate(String symbol, List<Candle> dailyCandles, int horizonDays)` returning `MonthlyRangeForecast`

- [ ] **Step 1: Write unit tests for `MonthlyRangeCalculator`**
  - Test calculation for RELIANCE (₹20 step), HDFCBANK (₹10 step), SBIN (₹5 step), and NIFTY (₹50 step).
  - Test 1-SD (~68%) and 2-SD (~95%) channels, HV-30, and ATR-22.
  - Test fallback handling for empty or short candle lists.
- [ ] **Step 2: Run tests to verify failure**
- [ ] **Step 3: Implement `MonthlyRangeCalculator`**
- [ ] **Step 4: Run tests to verify they pass**
- [ ] **Step 5: Commit**

---

### Task 4: Calendar Logic for Last-Wednesday Post-Expiry Detection

**Files:**
- Modify: `src/main/java/com/tradingbot/util/NseTradingCalendarUtil.java`
- Test: `src/test/java/com/tradingbot/util/NseTradingCalendarUtilTest.java`

**Interfaces:**
- Produces: `NseTradingCalendarUtil.getMonthlyExpiryTuesday(int year, int month)`, `NseTradingCalendarUtil.isLastWednesdayOfMonth(LocalDate date)`

- [ ] **Step 1: Write tests for monthly Tuesday expiry and last Wednesday of month calculations**
- [ ] **Step 2: Run tests to verify failure**
- [ ] **Step 3: Implement `getMonthlyExpiryTuesday` and `isLastWednesdayOfMonth` in `NseTradingCalendarUtil`**
- [ ] **Step 4: Run tests to verify they pass**
- [ ] **Step 5: Commit**

---

### Task 5: Monthly Range Service & Telegram Integration

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/monthlyrange/service/MonthlyRangeService.java`
- Test: `src/test/java/com/tradingbot/strategy/monthlyrange/service/MonthlyRangeServiceTest.java`

**Interfaces:**
- Consumes: `YahooFinanceService`, `MonthlyRangeCalculator`, `TelegramService`, `MonthlyRangeProperties`
- Produces: `MonthlyRangeService.generateMonthlyReport()`, `MonthlyRangeService.generateForecastForSymbol(String symbol)`

- [ ] **Step 1: Write unit and mock tests for `MonthlyRangeService`**
- [ ] **Step 2: Run tests to verify failure**
- [ ] **Step 3: Implement `MonthlyRangeService` with multi-stock processing, error isolation, markdown formatting, and Telegram alerts**
- [ ] **Step 4: Run tests to verify they pass**
- [ ] **Step 5: Commit**

---

### Task 6: Scheduler, REST Controller & Telegram Command Listener

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/monthlyrange/scheduler/MonthlyRangeScheduler.java`
- Create: `src/main/java/com/tradingbot/strategy/monthlyrange/controller/MonthlyRangeController.java`
- Modify: `src/main/java/com/tradingbot/telegram/TelegramBotCommandListener.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/tradingbot/strategy/monthlyrange/controller/MonthlyRangeControllerTest.java`

**Interfaces:**
- Produces: REST endpoints `/api/v1/monthly-range/run`, `/api/v1/monthly-range/forecast`, `/api/v1/monthly-range/forecast/{symbol}`, and Telegram command `/monthlyrange`

- [ ] **Step 1: Write controller and scheduler tests**
- [ ] **Step 2: Run tests to verify failure**
- [ ] **Step 3: Implement `MonthlyRangeScheduler`, `MonthlyRangeController`, update `TelegramBotCommandListener`, and configure `application.properties`**
- [ ] **Step 4: Run tests to verify they pass**
- [ ] **Step 5: Format code with Spotless (`./gradlew spotlessApply`) and run complete test suite (`./gradlew test`)**
- [ ] **Step 6: Commit**
