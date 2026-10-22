# Intraday Double-Session Straddle Strategy (1 & 0 NS 916 TTC) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and deploy a clock-driven, non-directional intraday option selling strategy for NIFTY (Mon 1-DTE / Tue 0-DTE) and SENSEX (Wed 1-DTE) with 50% leg stop losses, deep OTM protection wings, and an automated ₹8,000 daily loss kill-switch.

**Architecture:** A Spring Boot service and clock-driven scheduler (`com.tradingbot.strategy.ttcstraddle`) decoupled through `ReactiveSignalEventBus` to emit signals to `ZerodhaTradeConsumer` and `ShoonyaTradeConsumer` with state persistence (`straddle-state.json`) and Telegram commands.

**Tech Stack:** Java 21, Spring Boot 3.3.5, JUnit 5, Mockito, AssertJ, Jackson, Project Reactor.

**Spec:** `docs/superpowers/specs/2026-10-22-intraday-ttc-straddle-strategy-design.md`

## Global Constraints
- Java 21 LTS with strict spotbugs, spotless, and PMD compliance.
- All time-based evaluations operate in `Asia/Kolkata` (`ZoneId.of("Asia/Kolkata")`) with clock injection for deterministic testing.
- Must support both `PAPER` and `LIVE` execution modes per consumer.
- All open positions must be strictly 100% squared off by 15:35 IST (zero overnight risk).

## Review Focus
1. 50% Stop-loss trigger accuracy during intraday spikes (ensuring stopped out leg does not re-enter and surviving leg keeps running).
2. Daily Loss Kill-Switch latch (preventing new entries once ₹8,000 loss threshold is breached).
3. Contract symbol resolution for NIFTY (`NFO`) and SENSEX (`BFO`) across weekly/current expiries.
4. Correct sequence of hedge wing purchases at 09:16 AM before short selling at 09:22 AM.
5. Square-off of all 4 open legs at 15:35 IST.

---

### Task 1: Strategy Configuration & Model Domain

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/ttcstraddle/config/IntradayStraddleProperties.java`
- Create: `src/main/java/com/tradingbot/strategy/ttcstraddle/model/StraddleLeg.java`
- Create: `src/main/java/com/tradingbot/strategy/ttcstraddle/model/StraddleSessionPhase.java`
- Create: `src/main/java/com/tradingbot/strategy/ttcstraddle/model/StraddleDailyState.java`
- Test: `src/test/java/com/tradingbot/strategy/ttcstraddle/model/StraddleModelTest.java`

- [ ] **Step 1:** Write `StraddleModelTest.java` to test leg construction, 50% SL calculation, and daily state reset.
- [ ] **Step 2:** Run `./gradlew test --tests "*StraddleModelTest*"` to confirm compilation failure.
- [ ] **Step 3:** Implement `IntradayStraddleProperties.java`, `StraddleLeg.java`, `StraddleSessionPhase.java`, and `StraddleDailyState.java`.
- [ ] **Step 4:** Run `./gradlew test --tests "*StraddleModelTest*"` to verify all pass.
- [ ] **Step 5:** Commit: `feat(straddle): add configuration and model domain for intraday straddle strategy`.

---

### Task 2: Core Strategy Engine (`IntradayStraddleService`)

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/ttcstraddle/service/IntradayStraddleService.java`
- Modify: `src/main/resources/application.properties`
- Modify: `.env.example`
- Test: `src/test/java/com/tradingbot/strategy/ttcstraddle/service/IntradayStraddleServiceTest.java`

- [ ] **Step 1:** Write `IntradayStraddleServiceTest.java` covering:
  - 09:16 hedge buying signal emission.
  - 09:22 morning straddle entry with 50% SL.
  - Independent 50% SL execution on tick breach.
  - 10:16 morning square-off (short legs closed, long hedges preserved).
  - 14:17 afternoon fresh straddle entry.
  - 15:35 final 4-leg square-off.
  - Kill-switch activation on cumulative loss.
- [ ] **Step 2:** Run `./gradlew test --tests "*IntradayStraddleServiceTest*"` to confirm failure.
- [ ] **Step 3:** Implement `IntradayStraddleService.java` with clock injection, reactive bus signal publishing, and state management.
- [ ] **Step 4:** Run `./gradlew test --tests "*IntradayStraddleServiceTest*"` to verify all tests pass.
- [ ] **Step 5:** Commit: `feat(straddle): implement core intraday straddle service engine`.

---

### Task 3: Clock-Driven Scheduler (`IntradayStraddleScheduler`)

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/ttcstraddle/scheduler/IntradayStraddleScheduler.java`
- Test: `src/test/java/com/tradingbot/strategy/ttcstraddle/scheduler/IntradayStraddleSchedulerTest.java`

- [ ] **Step 1:** Write `IntradayStraddleSchedulerTest.java` validating:
  - Daily reset at 09:15 IST.
  - Hedge buy at 09:16 IST.
  - Morning entry at 09:22 IST.
  - Morning exit at 10:16 IST (Nifty) / 10:30 IST (Sensex).
  - Afternoon entry at 14:17 IST.
  - Hard square-off at 15:35 IST.
  - 10-second price check loop between 09:22–15:35.
- [ ] **Step 2:** Run `./gradlew test --tests "*IntradayStraddleSchedulerTest*"` to confirm failure.
- [ ] **Step 3:** Implement `IntradayStraddleScheduler.java` with `@Scheduled` methods and weekday filters.
- [ ] **Step 4:** Run `./gradlew test --tests "*IntradayStraddleSchedulerTest*"` to verify pass.
- [ ] **Step 5:** Commit: `feat(straddle): implement clock-driven scheduler for intraday straddle`.

---

### Task 4: Telegram Commands & Emergency Controls

**Files:**
- Modify: `src/main/java/com/tradingbot/telegram/TelegramBotCommandListener.java`
- Test: `src/test/java/com/tradingbot/telegram/TelegramBotCommandListenerTest.java`

- [ ] **Step 1:** Add tests for `/straddle_status` and `/straddle_exit` in `TelegramBotCommandListenerTest.java`.
- [ ] **Step 2:** Run `./gradlew test --tests "*TelegramBotCommandListenerTest*"` to confirm failure.
- [ ] **Step 3:** Wire `IntradayStraddleService` into `TelegramBotCommandListener` and implement `/straddle_status` and `/straddle_exit`.
- [ ] **Step 4:** Run `./gradlew test --tests "*TelegramBotCommandListenerTest*"` to verify pass.
- [ ] **Step 5:** Commit: `feat(telegram): add /straddle_status and /straddle_exit commands`.

---

### Task 5: End-to-End Verification, Docker Build & Push

**Files:**
- Modify: `README.md`
- Build: `./gradlew test`, `./gradlew spotlessCheck`
- Docker: Build and push `sharadprsn/shoonya-trading-bot:latest`

- [ ] **Step 1:** Run full test suite: `./gradlew test`.
- [ ] **Step 2:** Run code formatting check: `./gradlew spotlessCheck`.
- [ ] **Step 3:** Update `README.md` documenting the new strategy and Telegram commands.
- [ ] **Step 4:** Build multi-stage Docker image and push to Docker Hub.
- [ ] **Step 5:** Commit and push to git.
