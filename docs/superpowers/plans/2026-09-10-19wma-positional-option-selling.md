# 19-Period Daily WMA Positional Hedged Option Selling Strategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build, test, backtest, and deploy the automated 19-period Daily WMA Positional Hedged Option Selling Strategy on NIFTY 50 with dynamic monthly expiry routing, $\le ₹105.00$ max entry premium, 2.0% OTM long hedge leg, disk state persistence, and full Telegram notifications.

**Architecture:** Spring Boot reactive-ready service architecture with scheduled daily trend evaluation at 09:30:10 AM IST, multi-day positional lifecycle management persisted to JSON, dynamic monthly expiry selection (current month for day $\le 15$, next month for day $> 15$), multi-leg execution with margin-first hedge sequencing and rollback safeguards, and Telegram integration.

**Tech Stack:** Java 21, Spring Boot 3.3.4, TA-Lib (Core), Shoonya Broker API (REST & Option Chain), Jackson JSON, AssertJ, Mockito, JUnit 5, Docker.

**Spec:** `docs/superpowers/specs/2026-09-10-19wma-positional-option-selling-design.md`

## Global Constraints
- Target Asset: NIFTY 50 (`NSE:10576`).
- Max Entry Premium: $\le ₹105.00$ INR per share.
- Target Delta: $\Delta \approx 0.20 - 0.25$ (default 0.22).
- Dynamic Expiry Routing: Day of month $\le 15 \implies$ Current Month Expiry; Day of month $> 15 \implies$ Next Month Expiry.
- Hedge: Buy 2.0% OTM option from short strike (rounded to nearest 50).
- Multi-Leg Safety: Buy hedge leg first for margin relief, sell short leg second; rollback hedge immediately if short leg fails.
- Evaluation Timing: 09:30:10 IST on Mon–Fri trading days.
- State Persistence: Persist active position to `data/wma_positional_state.json`.
- Test Verification: Strictly verify `./gradlew test --rerun-tasks` and `./gradlew bootJar`.

---

### Task 1: Technical Analysis 19 WMA Series Calculation

**Files:**
- Modify: `src/main/java/com/tradingbot/indicator/TechnicalAnalysisService.java`
- Modify: `src/test/java/com/tradingbot/indicator/TechnicalAnalysisServiceTest.java`

**Interfaces:**
- Consumes: `TA_LIB.wma(...)` or weighted linear arithmetic
- Produces: `public double[] calculateWmaSeries(double[] prices, int period)`

- [x] **Step 1: Write the failing unit test for 19-period WMA in `TechnicalAnalysisServiceTest.java`**
- [x] **Step 2: Run `./gradlew test --tests com.tradingbot.indicator.TechnicalAnalysisServiceTest` to confirm failure**
- [x] **Step 3: Implement `calculateWmaSeries(double[] prices, int period)` in `TechnicalAnalysisService.java`**
- [x] **Step 4: Run test to confirm it passes cleanly**
- [x] **Step 5: Commit changes**

---

### Task 2: Domain Model `DailyWmaPosition` with Positional State Tracking

**Files:**
- Create: `src/main/java/com/tradingbot/model/strategy/DailyWmaPosition.java`
- Create: `src/test/java/com/tradingbot/model/strategy/DailyWmaPositionTest.java`

**Interfaces:**
- Consumes: `BigDecimal`, `Instant`, `LocalDate`
- Produces: `DailyWmaPosition` with fields: `tradeId`, `symbol`, `action`, `optionType`, `bias`, `entryDate`, `entrySpot`, `wma19AtEntry`, `expiryDate`, `shortSymbol`, `shortStrike`, `shortEntryPrice`, `shortDelta`, `quantity`, `hedgeSymbol`, `hedgeStrike`, `hedgeEntryPrice`, `hedgeQuantity`, `netCredit`, `stopLossPrice`, `isClosed`, `exitDate`, `exitSpot`, `shortExitPrice`, `hedgeExitPrice`, `realizedPnl`, `exitReason`.
- Methods: `calculateSpreadPnl(shortLtp, hedgeLtp)`, `close(shortExitPrice, hedgeExitPrice, exitReason, exitTime, exitSpot)`.

- [x] **Step 1: Write failing unit tests for `DailyWmaPosition` in `DailyWmaPositionTest.java`**
- [x] **Step 2: Run test to verify failure**
- [x] **Step 3: Implement `DailyWmaPosition.java`**
- [x] **Step 4: Run test to verify it passes**
- [x] **Step 5: Commit changes**

---

### Task 3: Telegram Alerting for Positional WMA Strategy

**Files:**
- Modify: `src/main/java/com/tradingbot/telegram/TelegramService.java`
- Modify: `src/test/java/com/tradingbot/telegram/TelegramServiceTest.java`

**Interfaces:**
- Consumes: `DailyWmaPosition`
- Produces:
  - `public void sendDailyWmaEntryAlert(DailyWmaPosition pos, double spot, double wma19)`
  - `public void sendDailyWmaExitAlert(DailyWmaPosition pos, String reason)`
  - `public void sendDailyWmaStopLossAlert(DailyWmaPosition pos, double currentLtp)`

- [x] **Step 1: Write unit tests for Daily WMA Telegram alerts in `TelegramServiceTest.java`**
- [x] **Step 2: Implement alert formatting methods in `TelegramService.java`**
- [x] **Step 3: Run `./gradlew test --tests com.tradingbot.telegram.TelegramServiceTest`**
- [x] **Step 4: Commit changes**

---

### Task 4: Core Strategy Service `DailyWmaStrategyService`

**Files:**
- Create: `src/main/java/com/tradingbot/service/DailyWmaStrategyService.java`
- Create: `src/test/java/com/tradingbot/service/DailyWmaStrategyServiceTest.java`

**Interfaces:**
- Consumes: `ShoonyaMarketDataService`, `ShoonyaOptionChainService`, `ShoonyaOrderService`, `ExecutionManager`, `TechnicalAnalysisService`, `TelegramService`, `ObjectMapper`
- Produces:
  - `public void evaluateDailyCycle()`
  - `public void monitorStopLoss()`
  - `public void executeExpirySquareOff(String reason)`
  - `public LocalDate resolveTargetExpiry(LocalDate tradeDate)`
  - `public DailyWmaPosition getOpenPosition()`
  - `public List<DailyWmaPosition> getTradeHistory()`

- [x] **Step 1: Write unit tests covering bullish entry, bearish entry, $\le ₹105$ filtering, dynamic expiry routing, 2% OTM hedge execution, trend reversal exit, and state persistence in `DailyWmaStrategyServiceTest.java`**
- [x] **Step 2: Implement `DailyWmaStrategyService.java`**
- [x] **Step 3: Run `./gradlew test --tests com.tradingbot.service.DailyWmaStrategyServiceTest`**
- [x] **Step 4: Commit changes**

---

### Task 5: Scheduler `DailyWmaScheduler`

**Files:**
- Create: `src/main/java/com/tradingbot/scheduler/DailyWmaScheduler.java`
- Create: `src/test/java/com/tradingbot/scheduler/DailyWmaSchedulerTest.java`

**Interfaces:**
- Consumes: `DailyWmaStrategyService`, `ShoonyaConfig`
- Produces:
  - `@Scheduled(cron = "${trading-bot.strategy.daily-wma.eval-cron:10 30 9 * * MON-FRI}", zone = "Asia/Kolkata") public void evaluateDailyCycle()`
  - `@Scheduled(cron = "${trading-bot.strategy.daily-wma.sl-monitor-cron:10 */5 9-15 * * MON-FRI}", zone = "Asia/Kolkata") public void monitorStopLoss()`
  - `@Scheduled(cron = "${trading-bot.strategy.daily-wma.squareoff-cron:10 15 15 * * MON-FRI}", zone = "Asia/Kolkata") public void checkExpirySquareOff()`

- [x] **Step 1: Write unit test in `DailyWmaSchedulerTest.java`**
- [x] **Step 2: Implement `DailyWmaScheduler.java`**
- [x] **Step 3: Run `./gradlew test --tests com.tradingbot.scheduler.DailyWmaSchedulerTest`**
- [x] **Step 4: Commit changes**

---

### Task 6: Configuration & Application Properties

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `.env.example`
- Modify: `.env`

- [x] **Step 1: Add `trading-bot.strategy.daily-wma.*` properties in `application.properties`**
- [x] **Step 2: Update `.env.example` and `.env` with environment variable mappings**
- [x] **Step 3: Commit changes**

---

### Task 7: Java Backtest Engine `DailyWmaBacktestService`

**Files:**
- Create: `src/main/java/com/tradingbot/backtest/DailyWmaBacktestService.java`
- Create: `src/test/java/com/tradingbot/backtest/DailyWmaBacktestServiceTest.java`

- [x] **Step 1: Write unit test in `DailyWmaBacktestServiceTest.java`**
- [x] **Step 2: Implement `DailyWmaBacktestService.java`**
- [x] **Step 3: Run `./gradlew test --tests com.tradingbot.backtest.DailyWmaBacktestServiceTest`**
- [x] **Step 4: Commit changes**

---

### Task 8: REST Controller `DailyWmaStrategyController`

**Files:**
- Create: `src/main/java/com/tradingbot/controller/DailyWmaStrategyController.java`
- Create: `src/test/java/com/tradingbot/controller/DailyWmaStrategyControllerTest.java`

**Endpoints:**
- `GET /api/strategy/daily-wma/status`: Returns active position, 19 WMA value, and today's bias.
- `GET /api/strategy/daily-wma/history`: Returns historical closed trades.
- `POST /api/strategy/daily-wma/trigger`: Manually triggers 09:30 AM evaluation cycle.
- `POST /api/strategy/daily-wma/close`: Forces square-off of open position.
- `POST /api/strategy/daily-wma/backtest`: Triggers backtest over specified days.

- [x] **Step 1: Write unit tests in `DailyWmaStrategyControllerTest.java`**
- [x] **Step 2: Implement `DailyWmaStrategyController.java`**
- [x] **Step 3: Run `./gradlew test --tests com.tradingbot.controller.DailyWmaStrategyControllerTest`**
- [x] **Step 4: Commit changes**

---

### Task 9: Full Verification, Docker Container Build & Deployment

- [x] **Step 1: Run full test suite: `./gradlew test --rerun-tasks`**
- [x] **Step 2: Package JAR: `./gradlew bootJar`**
- [x] **Step 3: Build & Push Docker image: `docker build -t sharadprsn/shoonya-trading-bot:latest .` and `docker push sharadprsn/shoonya-trading-bot:latest`**
- [x] **Step 4: Final verification summary**
