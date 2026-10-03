# Implementation Plan: LVR Drawdown Reduction, Parameter Tuning & Modular Setup Control

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement drawdown-reduction enhancements: modular `vandeBharatEnabled` toggle (default `false` for Pure LVR), configurable 1:2.5 RR Target 1 (`targetRr = 2.5`), and daily 2-loss circuit breaker (`maxDailyLosses = 2`).

**Architecture:** Update `LowestVolumeReversalService` to gate Setup 3 behind `vandeBharatEnabled`, compute Target 1 with configurable `targetRr`, track `todayLossCount` to halt new entries after reaching `maxDailyLosses`, and expose configuration in `application.properties`.

**Tech Stack:** Java 21, Spring Boot 3, Gradle, JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-10-03-lvr-drawdown-reduction-and-tuning-design.md`

## Global Constraints

- Setup 3 (Vande Bharat) is disabled by default (`vandeBharatEnabled = false`) so the engine trades Pure LVR Pullbacks.
- Target 1 defaults to 1:2.5 RR (`targetRr = 2.5`).
- Daily loss count limit defaults to 2 (`maxDailyLosses = 2`), halting new entries when reached.
- All calculations remain 100% deterministic with rounding to nearest ₹0.05 tick.

## Review Focus

1. **Setup 3 Toggle Verification:** Ensure Vande Bharat inside-bars do NOT arm when `vandeBharatEnabled = false`, and DO arm when `vandeBharatEnabled = true`.
2. **1:2.5 Target Calculation:** Verify that with `targetRr = 2.5`, Target 1 is computed as `entry + 2.5 * risk` for Long and `entry - 2.5 * risk` for Short.
3. **Daily Loss Count Halting:** Verify that after 2 consecutive stop losses on the same day, new entries are blocked.
4. **Daily State Reset:** Verify `todayLossCount` resets to 0 upon `resetDaily()`.

---

### Task 1: Modular Setup 3 Toggle (`vandeBharatEnabled`) & Configurable Target RR (`targetRr`)

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeCandleEngineTest.java`

- [ ] **Step 1: Write the failing tests for `vandeBharatEnabled` toggle and 1:2.5 Target calculation**

In `src/test/java/com/tradingbot/service/LowestVolumeCandleEngineTest.java`:
```java
@Test
@DisplayName("Should skip Setup 3 Vande Bharat when vandeBharatEnabled is false (Pure LVR default)")
void testVandeBharatDisabledByDefault() {
    LowestVolumeReversalService service = new LowestVolumeReversalService(null, null, null, null, null);
    service.setVandeBharatEnabled(false);

    Instant t0 = Instant.parse("2026-09-18T03:45:00Z");
    List<Candle> candles = List.of(
            Candle.of5m("RADICO", t0, BigDecimal.valueOf(1000), BigDecimal.valueOf(1010), BigDecimal.valueOf(995), BigDecimal.valueOf(1008), 50000),
            Candle.of5m("RADICO", t0.plus(5, ChronoUnit.MINUTES), BigDecimal.valueOf(1008), BigDecimal.valueOf(1015), BigDecimal.valueOf(1005), BigDecimal.valueOf(1012), 40000),
            Candle.of5m("RADICO", t0.plus(10, ChronoUnit.MINUTES), BigDecimal.valueOf(1012), BigDecimal.valueOf(1018), BigDecimal.valueOf(1010), BigDecimal.valueOf(1016), 35000),
            // C4: Mother Green
            Candle.of5m("RADICO", t0.plus(15, ChronoUnit.MINUTES), BigDecimal.valueOf(1016), BigDecimal.valueOf(1030), BigDecimal.valueOf(1015), BigDecimal.valueOf(1028), 60000),
            // C5: Inside Red (High 1027 <= 1030, Low 1018 >= 1015) with Vol = 45000 (> day lowest 35000, so LVR does not match)
            Candle.of5m("RADICO", t0.plus(20, ChronoUnit.MINUTES), BigDecimal.valueOf(1026), BigDecimal.valueOf(1027), BigDecimal.valueOf(1018), BigDecimal.valueOf(1020), 45000)
    );

    LowestVolumeSetup setup = service.evaluateCandleSequence("RADICO", LowestVolumeDirection.LONG, candles);

    // When vandeBharatEnabled is false, inside-bar is skipped and setup remains SCANNING
    assertEquals(LowestVolumeSetupState.SCANNING, setup.getState());
    assertNull(setup.getTriggerPrice());
}

@Test
@DisplayName("Should compute Target 1 at 1:2.5 RR with targetRr = 2.5")
void testTargetCalculationWith2Point5RR() {
    LowestVolumeReversalService service = new LowestVolumeReversalService(null, null, null, null, null);
    service.setTargetRr(2.5);

    Instant t0 = Instant.parse("2026-09-18T03:45:00Z");
    List<Candle> candles = List.of(
            Candle.of5m("SUNPHARMA", t0, BigDecimal.valueOf(500), BigDecimal.valueOf(510), BigDecimal.valueOf(498), BigDecimal.valueOf(508), 12000),
            Candle.of5m("SUNPHARMA", t0.plus(5, ChronoUnit.MINUTES), BigDecimal.valueOf(508), BigDecimal.valueOf(515), BigDecimal.valueOf(505), BigDecimal.valueOf(512), 9000),
            Candle.of5m("SUNPHARMA", t0.plus(10, ChronoUnit.MINUTES), BigDecimal.valueOf(512), BigDecimal.valueOf(518), BigDecimal.valueOf(510), BigDecimal.valueOf(515), 7000),
            // C4: Red pullback candle (O=515, H=516, L=508, C=510), Vol = 4000 (< 7000)
            Candle.of5m("SUNPHARMA", t0.plus(15, ChronoUnit.MINUTES), BigDecimal.valueOf(515), BigDecimal.valueOf(516), BigDecimal.valueOf(508), BigDecimal.valueOf(510), 4000)
    );

    LowestVolumeSetup setup = service.evaluateCandleSequence("SUNPHARMA", LowestVolumeDirection.LONG, candles);

    // Trigger = 516.05, SL = 507.95, Risk = 8.10. Target 1 (1:2.5 RR) = 516.05 + (2.5 * 8.10) = 536.30
    assertEquals(0, BigDecimal.valueOf(516.05).compareTo(setup.getTriggerPrice()));
    assertEquals(0, BigDecimal.valueOf(507.95).compareTo(setup.getStopLossPrice()));
    assertEquals(0, BigDecimal.valueOf(536.30).compareTo(setup.getTarget1Price()));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeCandleEngineTest.testVandeBharatDisabledByDefault`
Expected: FAIL (missing `setVandeBharatEnabled` or still arming).

- [ ] **Step 3: Implement `vandeBharatEnabled` and `targetRr` in `LowestVolumeReversalService.java`**

1. Add:
   ```java
   @Value("${trading-bot.strategy.lowest-volume.vande-bharat-enabled:false}")
   private volatile boolean vandeBharatEnabled = false;

   @Value("${trading-bot.strategy.lowest-volume.target-rr:2.5}")
   private volatile double targetRr = 2.5;
   ```
2. In `evaluateCandleSequence`:
   - Use `targetRr` in Target 1 calculation:
     - Short: `roundToTick(triggerPrc.subtract(risk.multiply(BigDecimal.valueOf(targetRr))))`
     - Long: `roundToTick(triggerPrc.add(risk.multiply(BigDecimal.valueOf(targetRr))))`
   - Gate Setup 3 with `if (vandeBharatEnabled && allowVb && vbVolumeFloor && candleAllowed)`.
3. In `executePositionEntry`:
   - Use `targetRr` for `actualTarget1` calculation.
4. Add getters/setters for `vandeBharatEnabled` and `targetRr`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeCandleEngineTest`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/service/LowestVolumeReversalService.java src/main/resources/application.properties src/test/java/com/tradingbot/service/LowestVolumeCandleEngineTest.java
git commit -m "feat(lvr): add modular vandeBharatEnabled toggle and 1:2.5 Target RR parameter (Task 1)"
```

---

### Task 2: Daily Loss Count Circuit Breaker (`maxDailyLosses` & `todayLossCount`)

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

- [ ] **Step 1: Write failing test for daily loss count halting**

In `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`:
```java
@Test
@DisplayName("Should halt new trade entries when max daily loss count is reached")
void testHaltsEntriesWhenMaxDailyLossCountReached() {
    service.setMaxDailyLosses(2);
    service.getTodayLossCount().set(2); // 2 losses incurred today

    LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
    setup.setTriggerCandle(
            Candle.of5m("SUNPHARMA", Instant.now(), BigDecimal.valueOf(1870), BigDecimal.valueOf(1875), BigDecimal.valueOf(1868), BigDecimal.valueOf(1872), 5000),
            BigDecimal.valueOf(1875.05), BigDecimal.valueOf(1867.95), BigDecimal.valueOf(1895.30));
    setup.setLatestVwap(1870.00);
    setup.setPdh(BigDecimal.valueOf(1870.00));
    setup.setPdl(BigDecimal.valueOf(1850.00));
    setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
    service.getActiveSetups().put("SUNPHARMA", setup);

    ObjectMapper mapper = new ObjectMapper();
    when(marketDataService.fetchQuote(any(), any()))
            .thenReturn(mapper.createObjectNode().put("lp", "1876.00").put("ap", "1870.00"));
    when(marketDataService.resolveToken("SUNPHARMA")).thenReturn("3351");

    service.evaluateLivePriceActions();

    // Entry must be blocked due to daily loss limit
    assertThat(service.getOpenPositions()).doesNotContainKey("SUNPHARMA");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalServiceTest.testHaltsEntriesWhenMaxDailyLossCountReached`
Expected: FAIL.

- [ ] **Step 3: Implement `maxDailyLosses` and `todayLossCount` in `LowestVolumeReversalService.java`**

1. Add:
   ```java
   @Value("${trading-bot.strategy.lowest-volume.max-daily-losses:2}")
   private volatile int maxDailyLosses = 2;

   private final AtomicInteger todayLossCount = new AtomicInteger(0);
   ```
2. In `evaluateOpenPositions()`:
   When position closes with `SPOT_SL_HIT` (or negative P&L): `todayLossCount.incrementAndGet();`
3. In `evaluateLivePriceActions()`:
   Check `if (maxDailyLosses > 0 && todayLossCount.get() >= maxDailyLosses) { ... }`
4. In `resetDaily()`:
   `todayLossCount.set(0);`
5. In `application.properties`:
   Add `trading-bot.strategy.lowest-volume.max-daily-losses=${LVR_MAX_DAILY_LOSSES:2}`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalServiceTest`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/service/LowestVolumeReversalService.java src/main/resources/application.properties src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java
git commit -m "feat(lvr): implement daily loss count circuit breaker to eliminate whipsaw drawdown (Task 2)"
```

---

### Task 3: Full System Verification

**Files:**
- Test: All tests in `src/test/java`

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test`
Expected: 100% tests pass across the entire codebase.

- [ ] **Step 2: Commit final verification**

```bash
git commit --allow-empty -m "chore(lvr): verify complete test suite after drawdown reduction enhancements"
```
