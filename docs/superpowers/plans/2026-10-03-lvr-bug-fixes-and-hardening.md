# LVR Strategy Bug Fixes & Engine Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all Critical (C1–C3), High (H1–H8, H10, H12), and Medium (M4–M6) bugs identified in `LVR_BUG_REPORT.md` to make the LVR strategy fully operational and production-safe.

**Architecture:** Remove session extreme comparisons from intra-candle trigger/SL checks, align Strategy IDs across publishers and consumers, ensure proper contract expiry rolling, decouple open position risk management from strategy enable flags, make PDH/PDL fail-closed, and extract live OI data for institutional screening.

**Tech Stack:** Java 21, Spring Boot 3, Gradle, JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-10-03-lvr-bug-fixes-and-hardening-design.md`

## Global Constraints

- Never use session-wide extremes (`quoteNode.h` / `quoteNode.l`) to invalidate setups whose stop losses are tighter than the opening range.
- Live entries require real-time `spotPrice` to cross the trigger within the allowable slippage boundary.
- Open positions must always be managed (SL, Target 1, Trailing, EOD square-off) regardless of `enabled` toggle.
- Strategy ID must strictly match `"LOWEST_VOLUME_REVERSAL"` across publisher and configuration keys.

## Review Focus

1. **Pre-entry SL Invalidation with Session Low:** Ensure a Long setup with SL at 995 does not invalidate when the 09:15 session low was 980 if current spot is 1000.
2. **Trigger Breach with Stale Session High:** Ensure a Long setup with trigger at 1010 does not fire entry when spot is 1000, even if the 09:15 session high was 1020.
3. **Monthly Expiry Rollover on Friday Post-Expiry:** Ensure a contract requested the day after last Thursday resolves to the following month's expiry date.
4. **Position Protection When Disabled:** Ensure an open position with SL at 100 exits when spot drops to 99 even if `enabled = false`.
5. **PDH/PDL Fail-Closed:** Ensure a trade is rejected if `pdhPdlFilterEnabled = true` but PDH data is missing/null.

---

### Task 1: Fix Pre-Entry SL Invalidation (C1) and Stale Trigger Breach (C2)

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java:940-1060`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

- [ ] **Step 1: Write the failing tests for live spot trigger breach and SL protection**

In `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`:
```java
@Test
@DisplayName("C1 Fix: Setup must NOT invalidate prior to entry when session day low is below SL but current spot is healthy")
void testSetupDoesNotInvalidateOnSessionDayLow() {
    LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
    setup.setTriggerCandle(
            Candle.of5m("SUNPHARMA", Instant.now(), BigDecimal.valueOf(1870), BigDecimal.valueOf(1875), BigDecimal.valueOf(1865), BigDecimal.valueOf(1872), 5000),
            BigDecimal.valueOf(1875.05),
            BigDecimal.valueOf(1864.95), // SL at 1864.95
            BigDecimal.valueOf(1895.25));
    setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
    service.getActiveSetups().put("SUNPHARMA", setup);

    // Quote where session low 'l' is 1850.00 (from 09:15 AM open), but current spot 'lp' is 1872.00 (healthy)
    ObjectMapper mapper = new ObjectMapper();
    when(marketDataService.fetchQuote(any(), any()))
            .thenReturn(mapper.createObjectNode().put("lp", "1872.00").put("h", "1874.00").put("l", "1850.00"));
    when(marketDataService.resolveToken("SUNPHARMA")).thenReturn("3351");

    service.evaluateLivePriceActions();

    // Setup must STAY ARMED and NOT be reset to SCANNING
    assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
    assertThat(setup.getTriggerPrice()).isEqualByComparingTo("1875.05");
}

@Test
@DisplayName("C2 Fix: Trigger breach must NOT fire when current spot is below trigger, even if session day high was above trigger")
void testTriggerDoesNotFireOnStaleDayHigh() {
    LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
    setup.setTriggerCandle(
            Candle.of5m("SUNPHARMA", Instant.now(), BigDecimal.valueOf(1870), BigDecimal.valueOf(1875), BigDecimal.valueOf(1865), BigDecimal.valueOf(1872), 5000),
            BigDecimal.valueOf(1875.05),
            BigDecimal.valueOf(1864.95),
            BigDecimal.valueOf(1895.25));
    setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
    service.getActiveSetups().put("SUNPHARMA", setup);

    // Quote where session high 'h' is 1880.00 (from 09:15 AM spike), but current spot 'lp' is 1872.00 (below trigger 1875.05)
    ObjectMapper mapper = new ObjectMapper();
    when(marketDataService.fetchQuote(any(), any()))
            .thenReturn(mapper.createObjectNode().put("lp", "1872.00").put("h", "1880.00").put("l", "1868.00"));
    when(marketDataService.resolveToken("SUNPHARMA")).thenReturn("3351");

    service.evaluateLivePriceActions();

    // No trade should be entered since live spot (1872) hasn't breached trigger (1875.05)
    assertThat(service.getOpenPositions()).doesNotContainKey("SUNPHARMA");
    assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalServiceTest.testSetupDoesNotInvalidateOnSessionDayLow`
Expected: FAIL (Setup resets to SCANNING due to `lowPrc <= SL`).

- [ ] **Step 3: Implement clean live spot breach and SL evaluation in `checkSpotTriggerBreach`**

In `LowestVolumeReversalService.java`:
1. Remove `lowPrc` and `highPrc` checks from pre-entry SL and target guards. Evaluate solely against live `spotPrice`.
2. In trigger breach check, require live `spotPrice >= triggerPrice` (Long) / `spotPrice <= triggerPrice` (Short) and within allowable slippage.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalServiceTest.testSetupDoesNotInvalidateOnSessionDayLow`
Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalServiceTest.testTriggerDoesNotFireOnStaleDayHigh`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/service/LowestVolumeReversalService.java src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java
git commit -m "fix(lvr): evaluate pre-entry SL and trigger breach against live spot price instead of session extremes (C1, C2)"
```

---

### Task 2: Strategy ID Alignment (H1) and Dual Consumer Protection (H2)

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

- [ ] **Step 1: Write failing test for Strategy ID constant**

In `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`:
```java
@Test
@DisplayName("H1 Fix: Published TradeSignal must use unified strategyId LOWEST_VOLUME_REVERSAL")
void testPublishedSignalStrategyIdAlignment() {
    com.tradingbot.bus.SignalPublisher mockPublisher = mock(com.tradingbot.bus.SignalPublisher.class);
    service = new LowestVolumeReversalService(marketDataService, taService, null, config, null, null, mockPublisher);
    service.setClock(Clock.fixed(Instant.parse("2026-09-18T04:30:00Z"), IST));

    LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
    setup.setTriggerCandle(Candle.of5m("SUNPHARMA", Instant.now(), BigDecimal.valueOf(1870), BigDecimal.valueOf(1876), BigDecimal.valueOf(1869), BigDecimal.valueOf(1875), 5000), BigDecimal.valueOf(1876.05), BigDecimal.valueOf(1868.95), BigDecimal.valueOf(1890.25));
    setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");

    service.executePositionEntry("SUNPHARMA", setup, BigDecimal.valueOf(1876.10));

    org.mockito.Mockito.verify(mockPublisher).publish(org.mockito.ArgumentMatchers.argThat(sig ->
            "LOWEST_VOLUME_REVERSAL".equals(sig.strategyId())));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalServiceTest.testPublishedSignalStrategyIdAlignment`
Expected: FAIL (published strategyId is `"LVR_FUTURES"`).

- [ ] **Step 3: Implement `STRATEGY_ID = "LOWEST_VOLUME_REVERSAL"` and update consumer configuration**

1. In `LowestVolumeReversalService.java`:
   `public static final String STRATEGY_ID = "LOWEST_VOLUME_REVERSAL";`
   Use `STRATEGY_ID` in `publishSignal`.
2. In `application.properties`:
   Set `trading-bot.execution.consumers.zerodha.enabled=${ZERODHA_CONSUMER_ENABLED:false}` (Default to `false` for secondary broker).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalServiceTest.testPublishedSignalStrategyIdAlignment`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/service/LowestVolumeReversalService.java src/main/resources/application.properties src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java
git commit -m "fix(lvr): align strategyId to LOWEST_VOLUME_REVERSAL and disable secondary broker by default (H1, H2)"
```

---

### Task 3: Contract Expiry Rolling in `StockFnoRegistry` (H3)

**Files:**
- Modify: `src/main/java/com/tradingbot/util/StockFnoRegistry.java`
- Test: `src/test/java/com/tradingbot/util/StockFnoRegistryTest.java`

- [ ] **Step 1: Write failing test for post-expiry rollover**

In `src/test/java/com/tradingbot/util/StockFnoRegistryTest.java`:
```java
@Test
@DisplayName("H3 Fix: Monthly futures symbol must roll to next month after current month expiry")
void testFuturesSymbolRollsAfterMonthlyExpiry() {
    // September 2026 expiry was Thursday Sep 24, 2026. On Friday Sep 25, 2026, expiry must resolve to Oct 2026.
    LocalDate dayAfterExpiry = LocalDate.of(2026, 9, 25);
    String sym = StockFnoRegistry.formatFuturesTradingSymbol("RELIANCE", dayAfterExpiry);
    assertThat(sym).contains("26OCTFUT");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.util.StockFnoRegistryTest.testFuturesSymbolRollsAfterMonthlyExpiry`
Expected: FAIL (Symbol was formatted with Sep 2026 instead of Oct 2026).

- [ ] **Step 3: Implement dynamic target expiry in `formatFuturesTradingSymbol` and `formatTradingSymbol`**

In `StockFnoRegistry.java`:
When `expiryDate` is passed or default-derived, check if `expiryDate.isBefore(referenceDate) || expiryDate.isEqual(referenceDate)`. If so, roll to `calculateTargetExpiry(symbol, referenceDate, false, 1)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.util.StockFnoRegistryTest.testFuturesSymbolRollsAfterMonthlyExpiry`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/util/StockFnoRegistry.java src/test/java/com/tradingbot/util/StockFnoRegistryTest.java
git commit -m "fix(fno): dynamically roll monthly futures/options contracts past expiry date (H3)"
```

---

### Task 4: Open Position Protection Decoupling (H4) and Sticky Stand-Down (H10)

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

- [ ] **Step 1: Write failing test for position protection when disabled**

In `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`:
```java
@Test
@DisplayName("H4 Fix: Open positions must be managed for SL exit even when strategy enabled is false")
void testOpenPositionsManagedWhenStrategyDisabled() {
    service.setEnabled(false); // Operator toggled off strategy
    LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
    setup.transitionTo(LowestVolumeSetupState.IN_POSITION, "In position");

    LowestVolumePaperPosition pos = new LowestVolumePaperPosition(
            "LVR-1", "SUNPHARMA", LvrInstrumentType.FUTURES, LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500,
            "SUNPHARMA FUT", 100, 1, LowestVolumeDirection.LONG,
            BigDecimal.valueOf(1870.00), BigDecimal.valueOf(1860.00), BigDecimal.valueOf(1890.00),
            100, BigDecimal.valueOf(1000.00), Instant.now());
    service.getOpenPositions().put("SUNPHARMA", pos);

    // Spot dropped below SL (1855 < 1860)
    ObjectMapper mapper = new ObjectMapper();
    when(marketDataService.fetchQuote(any(), any()))
            .thenReturn(mapper.createObjectNode().put("lp", "1855.00").put("ap", "1865.00"));
    when(marketDataService.resolveToken("SUNPHARMA")).thenReturn("3351");

    service.evaluateLivePriceActions();

    // Position must be exited via SL
    assertThat(pos.isClosed()).isTrue();
    assertThat(pos.getExitReason()).isEqualTo("SPOT_SL_HIT");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalServiceTest.testOpenPositionsManagedWhenStrategyDisabled`
Expected: FAIL (`evaluateLivePriceActions` returns early when `!enabled`).

- [ ] **Step 3: Implement decoupled open position management and `standDownToday` flag**

In `LowestVolumeReversalService.java`:
1. In `runCycle()`:
   - Always run `if (!openPositions.isEmpty()) evaluateOpenPositions(nowTime);`
   - If `!enabled`, return after position management.
2. In `evaluateLivePriceActions()`:
   - If `!openPositions.isEmpty()`, always run `evaluateOpenPositions(...)`.
   - If `!enabled`, skip evaluating candidate setups for new entries.
3. Add `private volatile boolean standDownToday = false;`. Set to true when breadth fails. Check in `runMidMorningUniverseRefresh()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalServiceTest.testOpenPositionsManagedWhenStrategyDisabled`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/service/LowestVolumeReversalService.java src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java
git commit -m "fix(lvr): decouple open position management from enable flag and implement sticky stand-down (H4, H10)"
```

---

### Task 5: PDH/PDL Fail-Closed (H7) & OI Quote Extraction (H8)

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

- [ ] **Step 1: Write failing test for PDH/PDL fail-closed behavior**

In `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`:
```java
@Test
@DisplayName("H7 Fix: PDH/PDL filter must fail closed and reject trade if PDH is null after fetch attempts")
void testPdhPdlFailsClosedWhenPdhNull() {
    service.setPdhPdlFilterEnabled(true);
    service.setOpening15mRangeFilterEnabled(false);
    service.setSectorMomentumFilterEnabled(false);

    LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
    setup.setTriggerCandle(Candle.of5m("SUNPHARMA", Instant.now(), BigDecimal.valueOf(1870), BigDecimal.valueOf(1875), BigDecimal.valueOf(1868), BigDecimal.valueOf(1872), 5000), BigDecimal.valueOf(1875.05), BigDecimal.valueOf(1867.95), BigDecimal.valueOf(1889.25));
    setup.setLatestVwap(1870.00);
    setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
    // setup.getPdh() remains null
    service.getActiveSetups().put("SUNPHARMA", setup);

    ObjectMapper mapper = new ObjectMapper();
    when(marketDataService.fetchQuote(any(), any()))
            .thenReturn(mapper.createObjectNode().put("lp", "1876.00").put("ap", "1870.00"));
    when(marketDataService.resolveToken("SUNPHARMA")).thenReturn("3351");
    when(marketDataService.fetchDailyCandles("SUNPHARMA", 5)).thenReturn(Collections.emptyList());

    service.evaluateLivePriceActions();

    assertThat(service.getOpenPositions()).doesNotContainKey("SUNPHARMA");
    assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.REJECTED_EXHAUSTED);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalServiceTest.testPdhPdlFailsClosedWhenPdhNull`
Expected: FAIL (Trade was allowed because `pdh == null` skipped the check).

- [ ] **Step 3: Implement PDH fail-closed and quote OI extraction**

1. In `checkSpotTriggerBreach`:
   - If `pdhPdlFilterEnabled`:
     - If `setup.getPdh() == null || setup.getPdl() == null`: call `initPdhPdlForSetup(setup)`.
     - If still null: reject with `REJECTED_EXHAUSTED` ("PDH/PDL unavailable").
2. In `replenishActiveCandidatesIfNeeded()`: call `initPdhPdlForSetup(setup)` on newly promoted setups.
3. In `fetchMorningQuotesUnified()`: read `"oi"` and `"oio"` from quote JSON to populate `openInterest` and `prevDayOpenInterest` in `StockQuoteSnapshot`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalServiceTest.testPdhPdlFailsClosedWhenPdhNull`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/service/LowestVolumeReversalService.java src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java
git commit -m "fix(lvr): make PDH/PDL filter fail-closed and extract live OI data in morning quotes (H7, H8)"
```

---

### Task 6: Full System Verification & Replay Regression

**Files:**
- Test: All tests in `src/test/java`

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test`
Expected: 100% tests pass across the entire codebase.

- [ ] **Step 2: Commit final verification**

```bash
git commit --allow-empty -m "chore(lvr): verify complete test suite after Critical & High bug fixes"
```
