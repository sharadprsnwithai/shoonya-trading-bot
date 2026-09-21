# KISS (Keep It Swing Systematic) Multi-Timeframe Strategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the complete KISS (Keep It Swing Systematic) 1-Hour Heikin-Ashi 55-EMA Band & MACD Swing Trading Strategy for Nifty 200 Futures and Major Commodities (Crude Oil, Gold, Silver, Copper), providing clear Long/Short entry and exit signals.

**Architecture:** 
- Multi-timeframe trend-following engine with Weekly Heikin-Ashi filter, 1-Hour 55 EMA High/Low Envelope, 55 Directional Slope, and 1-Hour MACD (12, 26, 9) zero-line trigger.
- Unified scanning across Nifty 200 equities (`Nifty200Registry`) and MCX/Global Commodities (`CommodityRegistry`).
- Full position management with risk budgeting ($1.5\%$), $1:3$ to $1:4$ risk-reward targets, MACD momentum trailing exits, and weekend risk rules.
- Interactive REST endpoints (`/api/v1/kiss/*`) and automated scheduling.

**Tech Stack:** Java 21, Spring Boot 3.3.5, JUnit 5, Mockito, SQLite JDBC, Jackson.

**Spec:** `docs/superpowers/specs/2026-09-20-kiss-strategy-design.md`

## Global Constraints
- Target Timeframe: 1-Hour (1H) Heikin-Ashi bars for trigger and execution.
- Instruments: Nifty 200 Futures + Commodities (`CRUDEOIL`, `GOLD`, `SILVER`, `COPPER`).
- Both Long and Short signals enabled on Futures.
- Spotless formatting and strict test verification.

---

### Task 1: Commodity Registry & Candle Resampling Enhancements

**Files:**
- Create: `src/main/java/com/tradingbot/util/CommodityRegistry.java`
- Modify: `src/main/java/com/tradingbot/util/CandleResamplingUtil.java`
- Modify: `src/main/java/com/tradingbot/marketdata/YahooFinanceService.java`
- Test: `src/test/java/com/tradingbot/util/CommodityRegistryTest.java`
- Test: `src/test/java/com/tradingbot/util/CandleResamplingUtilTest.java`

- [ ] Step 1: Write failing tests for `CommodityRegistryTest` and `CandleResamplingUtil.resample5MinTo1Hour`.
- [ ] Step 2: Implement 1-Hour and 4-Hour resampling in `CandleResamplingUtil.java`.
- [ ] Step 3: Implement commodity ticker mappings (`CL=F`, `GC=F`, `SI=F`, `HG=F`) and hourly candle fetching in `YahooFinanceService.java`.
- [ ] Step 4: Run tests and verify passing.

---

### Task 2: KISS Strategy Configuration & Domain Models

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/kiss/config/KissStrategyConfig.java`
- Create: `src/main/java/com/tradingbot/strategy/kiss/model/KissSignalType.java`
- Create: `src/main/java/com/tradingbot/strategy/kiss/model/KissSignal.java`
- Create: `src/main/java/com/tradingbot/strategy/kiss/model/KissPosition.java`
- Create: `src/main/java/com/tradingbot/strategy/kiss/model/KissState.java`
- Create: `src/main/java/com/tradingbot/strategy/kiss/model/KissSnapshot.java`
- Test: `src/test/java/com/tradingbot/strategy/kiss/model/KissStateTest.java`

- [ ] Step 1: Write model classes with JSON serialization support and full position lifecycle methods.
- [ ] Step 2: Write tests for state serialization and position tracking.
- [ ] Step 3: Verify tests pass.

---

### Task 3: KISS Technical Indicator Service

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/kiss/indicator/KissIndicatorService.java`
- Test: `src/test/java/com/tradingbot/strategy/kiss/indicator/KissIndicatorServiceTest.java`

- [ ] Step 1: Write failing unit test covering Weekly HA calculation, 55 EMA High/Low Band calculation, 55 Directional Slope, and MACD zero-line crossover.
- [ ] Step 2: Implement `KissIndicatorService.java` with complete indicator formulas.
- [ ] Step 3: Verify all indicator unit tests pass.

---

### Task 4: KISS Swing Strategy Service (Core Engine & Execution)

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/kiss/service/KissSwingService.java`
- Test: `src/test/java/com/tradingbot/strategy/kiss/service/KissSwingServiceTest.java`

- [ ] Step 1: Write unit tests verifying Long entry, Short entry, Target 1:3 exit, Stop Loss exit, MACD reversal exit, and Friday weekend exit.
- [ ] Step 2: Implement `KissSwingService.java` with multi-universe scanning (Nifty 200 + Commodities) and automated position tracking.
- [ ] Step 3: Verify unit tests pass.

---

### Task 5: Scheduler, Controller & Live Scanner Runner Test

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/kiss/scheduler/KissScheduler.java`
- Create: `src/main/java/com/tradingbot/strategy/kiss/controller/KissController.java`
- Create: `src/test/java/com/tradingbot/runner/KissStrategyLiveScanRunnerTest.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/tradingbot/strategy/kiss/controller/KissControllerTest.java`

- [ ] Step 1: Implement `KissScheduler.java` and `KissController.java`.
- [ ] Step 2: Implement live scanner test `KissStrategyLiveScanRunnerTest.java` across Nifty 200 and Commodities.
- [ ] Step 3: Run the live scan, verify output signals, run test suite, and push Docker image.
