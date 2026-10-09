# OI Spurts, Vande Bharat Inside Bar & PDH/PDL Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the OI Spurts stock selection scanner (09:25 AM), Vande Bharat inside-bar candle pattern (Setup 3), and post-signal Previous Day High/Low (PDH/PDL) execution gate into the Lowest Volume Reversal (LVR) strategy.

**Architecture:** Extend `LowestVolumeReversalScanner` with an institutional Open Interest spurts screener, extend `evaluateCandleSequence()` in `LowestVolumeReversalService` to recognize Setup 3 (Vande Bharat) inside-bar reversals alongside Setup 2, and add a deterministic PDH/PDL range filter check in `evaluateLivePriceActions()` before order execution.

**Tech Stack:** Java 21, Spring Boot 3, Gradle, JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-10-03-oi-spurts-vande-bharat-pdh-pdl-lvr-design.md`

## Global Constraints

- Timeframe for pattern detection is strictly 5-minute candles (`Candle.of5m`).
- All calculations are 100% deterministic with rounding to nearest ₹0.05 tick using `roundToTick()`.
- Minimum stop-loss percentage floor (`minStopLossPct: 0.35%`) applies to both Setup 2 and Setup 3.
- Range rejection rule: Any trade where `PDL <= spotPrice <= PDH` is rejected immediately with state `REJECTED_EXHAUSTED`.
- Broad market index underlyings (`NIFTY 50`, `NIFTY BANK`, `FINNIFTY`, `MIDCPNIFTY`) are excluded from stock selection.

## Review Focus

1. **Equal Highs/Lows in Inside Bar:** A candle whose High equals the Mother High and Low equals Mother Low must be treated as a valid inside bar without throwing IndexOutOfBounds or division by zero.
2. **Missing/Zero Open Interest in OI Spurts:** Symbols with `prevDayOpenInterest <= 0` or missing OI data must be filtered out safely without throwing `ArithmeticException`.
3. **Missing Previous Day Candle (Null PDH/PDL):** When previous day's daily candle is unavailable, the gate must fail closed or log a warning rather than throwing `NullPointerException`.
4. **Setup Conflict Resolution:** When both a low-volume candle and an inside-bar form, the most recent valid trigger arms the setup.
5. **Exact Tick Boundary at PDH/PDL:** A Long trade at `spotPrice == PDH` is considered trapped inside range (`spotPrice > PDH` strictly required).

---

### Task 1: Model Enhancements for PDH, PDL, Setup Pattern, and OI

**Files:**
- Modify: `src/main/java/com/tradingbot/model/strategy/LowestVolumeSetup.java`
- Modify: `src/main/java/com/tradingbot/model/strategy/StockQuoteSnapshot.java`
- Test: `src/test/java/com/tradingbot/model/strategy/LowestVolumePaperPositionTest.java`

**Interfaces:**
- Produces in `LowestVolumeSetup`:
  - `BigDecimal getPdh()`, `void setPdh(BigDecimal pdh)`
  - `BigDecimal getPdl()`, `void setPdl(BigDecimal pdl)`
  - `String getSetupPattern()`, `void setSetupPattern(String setupPattern)`
  - `Double getOiChangePct()`, `void setOiChangePct(Double oiChangePct)`
- Produces in `StockQuoteSnapshot`:
  - `long openInterest()`, `long prevDayOpenInterest()`, `double oiPctChange()`

- [ ] **Step 1: Write the failing test for model fields**

In `src/test/java/com/tradingbot/model/strategy/LowestVolumePaperPositionTest.java`:
```java
@Test
@DisplayName("Should store PDH, PDL, setupPattern and OI change in LowestVolumeSetup")
void testLowestVolumeSetupPdhPdlAndPattern() {
    LowestVolumeSetup setup = new LowestVolumeSetup("RELIANCE", LowestVolumeDirection.LONG);
    setup.setPdh(BigDecimal.valueOf(3000.00));
    setup.setPdl(BigDecimal.valueOf(2950.00));
    setup.setSetupPattern("VANDE_BHARAT_INSIDE_BAR");
    setup.setOiChangePct(12.5);

    assertEquals(BigDecimal.valueOf(3000.00), setup.getPdh());
    assertEquals(BigDecimal.valueOf(2950.00), setup.getPdl());
    assertEquals("VANDE_BHARAT_INSIDE_BAR", setup.getSetupPattern());
    assertEquals(12.5, setup.getOiChangePct());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.model.strategy.LowestVolumePaperPositionTest.testLowestVolumeSetupPdhPdlAndPattern`
Expected: Compilation failure due to missing getters/setters on `LowestVolumeSetup`.

- [ ] **Step 3: Implement fields and accessors in `LowestVolumeSetup` & `StockQuoteSnapshot`**

Add fields `pdh`, `pdl`, `setupPattern`, `oiChangePct` with getters and setters in `LowestVolumeSetup.java`. Ensure `resetToScanning()` preserves `pdh` and `pdl` while resetting trigger fields.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.model.strategy.LowestVolumePaperPositionTest.testLowestVolumeSetupPdhPdlAndPattern`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/model/strategy/LowestVolumeSetup.java src/main/java/com/tradingbot/model/strategy/StockQuoteSnapshot.java src/test/java/com/tradingbot/model/strategy/LowestVolumePaperPositionTest.java
git commit -m "feat(lvr): add PDH, PDL, setupPattern, and OI change fields to LowestVolumeSetup"
```

---

### Task 2: OI Spurts Screener in `LowestVolumeReversalScanner`

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalScanner.java`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalScannerTest.java`

**Interfaces:**
- Produces: `public List<String> scanOiSpurts(Map<String, StockQuoteSnapshot> fnoQuotes, int topN)`
- Behavior: Filters out non-stocks (`NIFTY 50`, `BANKNIFTY`, `FINNIFTY`, `MIDCPNIFTY`), computes `pctChangeOI`, sorts descending by absolute `% Change in OI`, and returns top $N$ symbol names.

- [ ] **Step 1: Write the failing tests for OI Spurts scanner**

In `src/test/java/com/tradingbot/service/LowestVolumeReversalScannerTest.java`:
```java
@Test
@DisplayName("Should rank stocks by absolute OI % change descending and exclude indices")
void testScanOiSpurtsRankingAndFiltering() {
    LowestVolumeReversalScanner scanner = new LowestVolumeReversalScanner();
    Map<String, StockQuoteSnapshot> quotes = new HashMap<>();

    // Add candidate quotes: symbol, ltp, pctChange, volume, vwap, openInterest, prevDayOpenInterest
    quotes.put("MCDOWELL-N", new StockQuoteSnapshot("MCDOWELL-N", 1200.0, 2.5, 50000, 1195.0, 110000, 100000)); // +10.0%
    quotes.put("RADICO", new StockQuoteSnapshot("RADICO", 1800.0, 3.1, 40000, 1790.0, 105500, 100000));     // +5.5%
    quotes.put("RVNL", new StockQuoteSnapshot("RVNL", 400.0, -1.5, 80000, 402.0, 104000, 100000));          // +4.0%
    quotes.put("NIFTY 50", new StockQuoteSnapshot("NIFTY 50", 25000.0, 0.5, 1000000, 24980.0, 5000000, 4000000)); // +25.0% (Index - must be excluded)
    quotes.put("TCS", new StockQuoteSnapshot("TCS", 4200.0, 0.1, 10000, 4205.0, 101000, 100000));           // +1.0%

    List<String> topStocks = scanner.scanOiSpurts(quotes, 3);

    assertEquals(3, topStocks.size());
    assertEquals("MCDOWELL-N", topStocks.get(0));
    assertEquals("RADICO", topStocks.get(1));
    assertEquals("RVNL", topStocks.get(2));
    assertFalse(topStocks.contains("NIFTY 50"));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalScannerTest.testScanOiSpurtsRankingAndFiltering`
Expected: Compilation failure due to missing `scanOiSpurts` method.

- [ ] **Step 3: Implement `scanOiSpurts()` in `LowestVolumeReversalScanner.java`**

Implement `scanOiSpurts(Map<String, StockQuoteSnapshot> fnoQuotes, int topN)`:
1. Return empty list if quotes is null or empty.
2. Filter out index keys (`NIFTY`, `BANKNIFTY`, `FINNIFTY`, `MIDCPNIFTY`, `NIFTY 50`, `NIFTY BANK`).
3. Calculate `oiPctChange = (prevOi > 0) ? ((currentOi - prevOi) / (double) prevOi) * 100.0 : quote.oiPctChange()`.
4. Filter out entries where `prevOi <= 0 && oiPctChange == 0.0`.
5. Sort descending by `Math.abs(oiPctChange)`.
6. Return `limit(topN).map(StockQuoteSnapshot::symbol).toList()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalScannerTest.testScanOiSpurtsRankingAndFiltering`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/service/LowestVolumeReversalScanner.java src/test/java/com/tradingbot/service/LowestVolumeReversalScannerTest.java
git commit -m "feat(lvr): implement OI Spurts screener ranking and index exclusion"
```

---

### Task 3: Setup 3 (Vande Bharat Inside Bar) Pattern in Candle Engine

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeCandleEngineTest.java`

**Interfaces:**
- Consumes: `Candle`, `LowestVolumeSetup`, `LowestVolumeDirection`
- Produces in `evaluateCandleSequence()`:
  - Detects Mother Green + Inside Red (Long) and Mother Red + Inside Green (Short) on $C_{i-1}$ and $C_i$ ($i \ge 3$).
  - Sets `setup.setTriggerCandle(...)` with trigger price at Mother High + 0.05 (Long) and SL at Inside Low - 0.05.
  - Sets `setup.setSetupPattern("VANDE_BHARAT_INSIDE_BAR")`.

- [ ] **Step 1: Write the failing tests for Vande Bharat Inside Bar setup**

In `src/test/java/com/tradingbot/service/LowestVolumeCandleEngineTest.java`:
```java
@Test
@DisplayName("Should arm LONG setup on Setup 3 Vande Bharat (Mother Green + Inside Red)")
void testArmLongSetupOnVandeBharatInsideBar() {
    LowestVolumeReversalService service =
            new LowestVolumeReversalService(null, null, null, null, null);

    Instant t0 = Instant.parse("2026-09-18T03:45:00Z"); // 09:15 IST
    List<Candle> candles =
            List.of(
                    // C1, C2, C3 baseline
                    Candle.of5m("RADICO", t0, BigDecimal.valueOf(1000), BigDecimal.valueOf(1010), BigDecimal.valueOf(995), BigDecimal.valueOf(1008), 50000),
                    Candle.of5m("RADICO", t0.plus(5, ChronoUnit.MINUTES), BigDecimal.valueOf(1008), BigDecimal.valueOf(1015), BigDecimal.valueOf(1005), BigDecimal.valueOf(1012), 40000),
                    Candle.of5m("RADICO", t0.plus(10, ChronoUnit.MINUTES), BigDecimal.valueOf(1012), BigDecimal.valueOf(1018), BigDecimal.valueOf(1010), BigDecimal.valueOf(1016), 35000),
                    // C4 (09:30): Strong Mother Green Candle
                    Candle.of5m("RADICO", t0.plus(15, ChronoUnit.MINUTES), BigDecimal.valueOf(1016), BigDecimal.valueOf(1030), BigDecimal.valueOf(1015), BigDecimal.valueOf(1028), 60000),
                    // C5 (09:35): Inside Red Candle (High 1027 <= 1030, Low 1018 >= 1015)
                    Candle.of5m("RADICO", t0.plus(20, ChronoUnit.MINUTES), BigDecimal.valueOf(1026), BigDecimal.valueOf(1027), BigDecimal.valueOf(1018), BigDecimal.valueOf(1020), 45000)
            );

    LowestVolumeSetup setup =
            service.evaluateCandleSequence("RADICO", LowestVolumeDirection.LONG, candles);

    assertNotNull(setup);
    assertEquals(LowestVolumeSetupState.TRIGGER_ARMED, setup.getState());
    assertEquals("VANDE_BHARAT_INSIDE_BAR", setup.getSetupPattern());
    assertEquals(BigDecimal.valueOf(1030.05), setup.getTriggerPrice()); // Mother High + 0.05
    assertEquals(BigDecimal.valueOf(1017.95), setup.getStopLossPrice()); // Inside Low - 0.05
}

@Test
@DisplayName("Should arm SHORT setup on Setup 3 Vande Bharat (Mother Red + Inside Green)")
void testArmShortSetupOnVandeBharatInsideBar() {
    LowestVolumeReversalService service =
            new LowestVolumeReversalService(null, null, null, null, null);

    Instant t0 = Instant.parse("2026-09-18T03:45:00Z");
    List<Candle> candles =
            List.of(
                    Candle.of5m("VOLTAS", t0, BigDecimal.valueOf(1000), BigDecimal.valueOf(1005), BigDecimal.valueOf(990), BigDecimal.valueOf(992), 50000),
                    Candle.of5m("VOLTAS", t0.plus(5, ChronoUnit.MINUTES), BigDecimal.valueOf(992), BigDecimal.valueOf(995), BigDecimal.valueOf(985), BigDecimal.valueOf(988), 40000),
                    Candle.of5m("VOLTAS", t0.plus(10, ChronoUnit.MINUTES), BigDecimal.valueOf(988), BigDecimal.valueOf(990), BigDecimal.valueOf(980), BigDecimal.valueOf(982), 35000),
                    // C4 (09:30): Strong Mother Red Candle
                    Candle.of5m("VOLTAS", t0.plus(15, ChronoUnit.MINUTES), BigDecimal.valueOf(982), BigDecimal.valueOf(984), BigDecimal.valueOf(965), BigDecimal.valueOf(968), 60000),
                    // C5 (09:35): Inside Green Candle (High 980 <= 984, Low 970 >= 965)
                    Candle.of5m("VOLTAS", t0.plus(20, ChronoUnit.MINUTES), BigDecimal.valueOf(970), BigDecimal.valueOf(980), BigDecimal.valueOf(970), BigDecimal.valueOf(978), 45000)
            );

    LowestVolumeSetup setup =
            service.evaluateCandleSequence("VOLTAS", LowestVolumeDirection.SHORT, candles);

    assertNotNull(setup);
    assertEquals(LowestVolumeSetupState.TRIGGER_ARMED, setup.getState());
    assertEquals("VANDE_BHARAT_INSIDE_BAR", setup.getSetupPattern());
    assertEquals(BigDecimal.valueOf(964.95), setup.getTriggerPrice()); // Mother Low - 0.05
    assertEquals(BigDecimal.valueOf(980.05), setup.getStopLossPrice()); // Inside High + 0.05
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeCandleEngineTest.testArmLongSetupOnVandeBharatInsideBar`
Expected: FAIL (Setup remains SCANNING because Vande Bharat is not yet detected).

- [ ] **Step 3: Implement Vande Bharat Inside-Bar pattern in `evaluateCandleSequence()`**

In `LowestVolumeReversalService.java`:
1. In the candle loop ($i \ge 3$), check for Setup 2 (LVR low volume pullback) AND Setup 3 (Vande Bharat inside bar).
2. For Setup 3:
   - Check if $C_{i-1}$ is Mother Candle ($Close > Open$ for Long, $Close < Open$ for Short).
   - Check if $C_i$ is Inside Opposite Candle ($High(C_i) \le High(C_{i-1})$ and $Low(C_i) \ge Low(C_{i-1})$).
   - If pattern matches, arm the trigger with `roundToTick(mother.high + 0.05)` (Long) / `roundToTick(mother.low - 0.05)` (Short), compute SL with `minStopLossPct` floor, calculate 1:2 Target, and set `setup.setSetupPattern("VANDE_BHARAT_INSIDE_BAR")`.
   - For Setup 2 (LVR), set `setup.setSetupPattern("LVR_VOLUME_PULLBACK")`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeCandleEngineTest`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/service/LowestVolumeReversalService.java src/test/java/com/tradingbot/service/LowestVolumeCandleEngineTest.java
git commit -m "feat(lvr): add Setup 3 Vande Bharat inside-bar pattern recognition to candle engine"
```

---

### Task 4: Post-Signal PDH/PDL Filter & ORB Deactivation in Execution Gate

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

**Interfaces:**
- Consumes: `setup.getPdh()`, `setup.getPdl()`, `spotPrice`, `pdhPdlFilterEnabled`
- Behavior in `evaluateLivePriceActions()`:
  - If `pdhPdlFilterEnabled` is `true`:
    - For Long: `spotPrice > setup.getPdh()`. If false $\to$ reject with state `REJECTED_EXHAUSTED` (Trapped in range).
    - For Short: `spotPrice < setup.getPdl()`. If false $\to$ reject with state `REJECTED_EXHAUSTED`.
  - Set `opening15mRangeFilterEnabled: false` by default.

- [ ] **Step 1: Write the failing tests for PDH/PDL execution gate**

In `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`:
```java
@Test
@DisplayName("Should reject LONG trade when spot price is below or equal to PDH (trapped in range)")
void testRejectLongTradeWhenInsidePdhPdlRange() {
    // Setup with PDH = 1050, PDL = 1000
    // Trigger price = 1030
    // Live spot = 1030 (breached trigger, but <= PDH 1050)
    // Expect: REJECTED_EXHAUSTED with reason "Trapped inside PDH-PDL range"
}

@Test
@DisplayName("Should execute LONG trade when spot price is strictly above PDH")
void testAllowLongTradeWhenAbovePdh() {
    // Setup with PDH = 1000, PDL = 950
    // Trigger price = 1010
    // Live spot = 1010 (> PDH 1000)
    // Expect: Passes gate and executes entry
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalServiceTest.testRejectLongTradeWhenInsidePdhPdlRange`
Expected: FAIL (PDH/PDL check not yet implemented in live execution gate).

- [ ] **Step 3: Implement PDH/PDL filter and properties in `LowestVolumeReversalService.java`**

1. Add `@Value("${trading-bot.strategy.lowest-volume.pdh-pdl-filter-enabled:true}") private volatile boolean pdhPdlFilterEnabled = true;`
2. Change `@Value("${trading-bot.strategy.lowest-volume.opening-15m-range-filter-enabled:false}")` (default `false`).
3. In `evaluateLivePriceActions()`, after trigger breach and VWAP confirmation, insert the PDH/PDL check:
   - If `pdhPdlFilterEnabled` and `setup.getPdh() != null` and `setup.getPdl() != null`:
     - Long: `spotPrice.compareTo(setup.getPdh()) > 0`
     - Short: `spotPrice.compareTo(setup.getPdl()) < 0`
     - If failed $\to$ transition to `LowestVolumeSetupState.REJECTED_EXHAUSTED`, add symbol to `exhaustedSymbols`, log info, dispatch Telegram alert, and return.
4. During morning scan / candidate initialization, fetch previous day's daily candle via `marketDataService.fetchDailyCandles(symbol, 2)` and populate `setup.setPdh(prevDay.high())` and `setup.setPdl(prevDay.low())`.

- [ ] **Step 4: Run all unit tests and replay regression**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalServiceTest`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/service/LowestVolumeReversalService.java src/main/resources/application.properties src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java
git commit -m "feat(lvr): add PDH/PDL breakout execution gate and configure default safety filters"
```

---

### Task 5: Full System Verification & Replay Runner Validation

**Files:**
- Test: `src/test/java/com/tradingbot/runner/ShoonyaTodayLvrReplayRunnerTest.java`
- Test: `src/test/java/com/tradingbot/runner/ShoonyaLast5DaysLvrReplayRunnerTest.java`

- [ ] **Step 1: Run complete test suite and replay tests**

Run: `./gradlew test`
Expected: All tests pass across the entire project with zero regressions.

- [ ] **Step 2: Commit final verification baseline**

```bash
git commit --allow-empty -m "chore(lvr): verify complete test suite and replay runner for OI Spurts, Vande Bharat, and PDH/PDL"
```
