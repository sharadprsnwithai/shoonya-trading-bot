# 1:30 PM MCX Commodity Directional PCR & VWAP Breakout Strategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the 1:30 PM MCX Commodity (`GOLD`, `SILVER`, `CRUDEOIL`) Directional PCR & 15m VWAP Breakout Strategy with 1:2 RR, automated risk management, REST endpoints, and Telegram alerts.

**Architecture:** A dedicated strategy package `com.tradingbot.strategy.commodity` containing model records, Spring Boot properties configuration, an orchestrating strategy service integrating `ShoonyaOptionChainService` and `TechnicalAnalysisService`, scheduled triggers (13:30 bias check, 15m cycles, 23:15 EOD square-off), a REST controller, and `/commodity` Telegram bot command handler.

**Tech Stack:** Java 21, Spring Boot 3, JUnit 5, Mockito, AssertJ, Jackson.

**Spec:** `docs/superpowers/specs/2026-10-09-commodity-pcr-vwap-strategy-design.md`

## Global Constraints

- Java 21 LTS with record classes and immutable models where appropriate.
- Bias determination at 13:30 IST: $\text{PCR} \ge 1.15 \implies \text{BULLISH}$, $\text{PCR} \le 0.85 \implies \text{BEARISH}$, $0.85 < \text{PCR} < 1.15 \implies \text{NEUTRAL}$.
- 15-minute VWAP crossover: Bullish setup triggers on close $> \text{VWAP}$; Bearish setup triggers on close $< \text{VWAP}$.
- Stop Loss is anchored to intraday $\text{VWAP at entry}$; Target is $\text{Entry} \pm (2.0 \times \text{Risk})$ ($1:2$ RR).
- Safety bounds: Max 1 trade per commodity per session; entry cutoff at 22:30 IST; EOD square-off at 23:15 IST.
- Spotless formatting (`./gradlew spotlessApply`) and full test passing (`./gradlew test`) on each task.

## Review Focus

1. **Option chain unavailable / zero OI:** Gracefully defaults to `NEUTRAL` bias and skips trading without throwing unhandled exceptions.
2. **VWAP equals Entry Price (Zero Risk Division):** Prevents zero or negative risk calculation by enforcing a minimum tick buffer.
3. **Session Re-entry Lockout:** Ensures a commodity that completed a trade (hit Target or SL) does not trigger secondary entries on subsequent 15m bars in the same day.
4. **Out-of-hours / Weekend Invocations:** Manual REST scan or scheduler invocations on holidays/weekends handle empty market data cleanly.
5. **Telegram Formatting Markdown Safety:** Special characters in symbols, prices, and error logs are properly escaped to prevent Telegram 400 Bad Request responses.

---

### Task 1: Commodity Models, Config Properties & Registry Enhancements

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/commodity/model/CommodityBias.java`
- Create: `src/main/java/com/tradingbot/strategy/commodity/model/CommoditySetupState.java`
- Create: `src/main/java/com/tradingbot/strategy/commodity/model/CommoditySetup.java`
- Create: `src/main/java/com/tradingbot/strategy/commodity/model/CommodityTradePosition.java`
- Create: `src/main/java/com/tradingbot/strategy/commodity/model/CommodityStatusReport.java`
- Create: `src/main/java/com/tradingbot/strategy/commodity/config/CommodityVwapProperties.java`
- Modify: `src/main/java/com/tradingbot/util/CommodityRegistry.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/tradingbot/strategy/commodity/config/CommodityVwapPropertiesTest.java`
- Test: `src/test/java/com/tradingbot/strategy/commodity/model/CommodityModelTest.java`

**Interfaces:**
- Produces:
  - `CommodityBias` enum: `BULLISH`, `BEARISH`, `NEUTRAL`
  - `CommoditySetupState` enum: `IDLE`, `BIAS_IDENTIFIED`, `ARMED_LONG`, `ARMED_SHORT`, `IN_TRADE`, `COMPLETED`, `SKIPPED`
  - `CommoditySetup`: records bias, pcr, triggerHigh, triggerLow, crossoverTime, vwapAtSetup
  - `CommodityTradePosition`: records side, entryPrice, stopLoss, targetPrice, quantity, entryTime, exitPrice, exitReason
  - `CommodityVwapProperties`: Spring `@ConfigurationProperties("trading-bot.strategy.commodity-vwap")`
  - `CommodityRegistry`: support mini symbols `GOLDM`, `SILVERM`, `CRUDEOILM`

- [ ] **Step 1: Write the failing tests**

Write `CommodityVwapPropertiesTest` and `CommodityModelTest` verifying property defaults and model builders/risk-reward calculations.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.commodity.*`
Expected: FAIL (classes not found)

- [ ] **Step 3: Implement Enums, Records, Properties, and Registry additions**

Implement `CommodityBias`, `CommoditySetupState`, `CommoditySetup`, `CommodityTradePosition`, `CommodityStatusReport`, `CommodityVwapProperties`, and register `GOLDM`, `SILVERM`, `CRUDEOILM` in `CommodityRegistry`. Add property defaults to `application.properties`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.strategy.commodity.*`
Expected: PASS

- [ ] **Step 5: Apply formatting and commit**

```bash
./gradlew spotlessApply
git add src/main/java/com/tradingbot/strategy/commodity/ src/main/java/com/tradingbot/util/CommodityRegistry.java src/main/resources/application.properties src/test/java/com/tradingbot/strategy/commodity/
git commit -m "feat(commodity): add models, properties, and registry for commodity vwap strategy"
```

---

### Task 2: Core Commodity VWAP Strategy Service

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/commodity/service/CommodityVwapStrategyService.java`
- Test: `src/test/java/com/tradingbot/strategy/commodity/service/CommodityVwapStrategyServiceTest.java`

**Interfaces:**
- Consumes:
  - `ShoonyaOptionChainService`: `getOptionChain(String underlying, ...)` or PCR resolution
  - `TechnicalAnalysisService`: `calculateVwapSeries(List<Candle>)`
  - `ShoonyaMarketDataService`: `getHistoricalCandles(...)`, `getQuote(...)`
  - `TelegramService`: `sendTextMessage(String)`
  - `CommodityVwapProperties`, `CommodityRegistry`
- Produces:
  - `CommodityVwapStrategyService`:
    - `void evaluateDailyBias()` (at 13:30 IST)
    - `void evaluateStrategyCycle()` (every 15 min)
    - `void squareOffAllPositions(String reason)` (at 23:15 IST)
    - `CommodityStatusReport getStatusReport()`
    - `void resetSession(boolean force)`
    - `String formatTelegramReport()`

- [ ] **Step 1: Write comprehensive failing tests in `CommodityVwapStrategyServiceTest`**

Cover:
- PCR $\ge 1.15 \to \text{BULLISH}$, $\le 0.85 \to \text{BEARISH}$, $0.85-1.15 \to \text{NEUTRAL}$
- Crossover above VWAP when Bullish arms `ARMED_LONG` with `triggerHigh = candle.high()`
- Crossover below VWAP when Bearish arms `ARMED_SHORT` with `triggerLow = candle.low()`
- LTP breaking `triggerHigh` triggers Long Entry with $\text{SL}=\text{VWAP}$ and $\text{Target}=\text{Entry}+2\times(\text{Entry}-\text{SL})$
- LTP breaking `triggerLow` triggers Short Entry with $\text{SL}=\text{VWAP}$ and $\text{Target}=\text{Entry}-2\times(\text{SL}-\text{Entry})$
- Target / SL exit transitions state to `COMPLETED` and locks out duplicate trades for the session
- 22:30 IST cutoff prevents new setups
- 23:15 IST EOD square-off forces open positions closed

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.commodity.service.CommodityVwapStrategyServiceTest`
Expected: FAIL (service not implemented)

- [ ] **Step 3: Implement `CommodityVwapStrategyService`**

Implement complete strategy logic, state management per symbol, option chain fetching, 15m candle evaluation, breakout detection, risk-reward calculation, trade lifecycle tracking, and Telegram notification dispatch.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.strategy.commodity.service.CommodityVwapStrategyServiceTest`
Expected: PASS

- [ ] **Step 5: Apply formatting and commit**

```bash
./gradlew spotlessApply
git add src/main/java/com/tradingbot/strategy/commodity/service/ src/test/java/com/tradingbot/strategy/commodity/service/
git commit -m "feat(commodity): implement core commodity PCR and VWAP breakout strategy service"
```

---

### Task 3: Scheduler, REST Controller & Telegram Bot Integration

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/commodity/scheduler/CommodityVwapScheduler.java`
- Create: `src/main/java/com/tradingbot/strategy/commodity/controller/CommodityVwapController.java`
- Modify: `src/main/java/com/tradingbot/telegram/TelegramBotCommandListener.java`
- Test: `src/test/java/com/tradingbot/strategy/commodity/scheduler/CommodityVwapSchedulerTest.java`
- Test: `src/test/java/com/tradingbot/strategy/commodity/controller/CommodityVwapControllerTest.java`
- Test: `src/test/java/com/tradingbot/telegram/TelegramBotCommandListenerTest.java`

**Interfaces:**
- Consumes:
  - `CommodityVwapStrategyService`
- Produces:
  - `CommodityVwapScheduler`: scheduled crons at 13:30, */15 13-23, and 23:15 IST (Mon-Fri)
  - `CommodityVwapController`: endpoints `/api/v1/strategy/commodity-vwap/status`, `/scan`, `/reset`
  - Telegram bot command: `/commodity`

- [ ] **Step 1: Write failing controller, scheduler, and telegram listener tests**

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests com.tradingbot.strategy.commodity.controller.* --tests com.tradingbot.strategy.commodity.scheduler.*`
Expected: FAIL

- [ ] **Step 3: Implement Scheduler, Controller, and wire Telegram command listener**

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests com.tradingbot.strategy.commodity.* --tests com.tradingbot.telegram.TelegramBotCommandListenerTest`
Expected: PASS

- [ ] **Step 5: Apply formatting and commit**

```bash
./gradlew spotlessApply
git add src/main/java/com/tradingbot/strategy/commodity/ src/main/java/com/tradingbot/telegram/TelegramBotCommandListener.java src/test/
git commit -m "feat(commodity): add scheduler, REST controller, and Telegram command listener for commodity strategy"
```

---

### Task 4: Full System Verification & Regression Suite

**Files:**
- Test: Full test suite execution across the entire repository.

- [ ] **Step 1: Run complete Gradle test suite and Spotless check**

Run: `./gradlew clean check`
Expected: All tests PASS, Spotless clean, zero regressions.

- [ ] **Step 2: Commit any final cleanup**

```bash
git commit --allow-empty -m "chore(commodity): verify full build and test suite passing"
```
