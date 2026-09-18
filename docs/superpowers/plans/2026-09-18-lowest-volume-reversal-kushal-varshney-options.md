# Lowest Volume Reversal & Continuation (LVR) Options Strategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite the Lowest Volume Reversal & Continuation (LVR) intraday engine based on the Kushal Varshney framework — implementing 09:25 IST Market Sentiment (NIFTY 50 Advances/Declines), 11 NSE Sector ranking, 5-minute lowest volume opposite-candle pullback triggers, order trailing, and Stock Option Buying (ATM CE/PE) with 1:4 spot RR partial profit booking (50% lots), Cost SL, and 10 EMA / 15:15 IST trailing exits.

**Architecture:** A 3-step pipeline (`Selection -> Setup -> Action`):
1. `LowestVolumeReversalScanner` resolves 09:25 IST market sentiment and ranks the 11 sectors using `NiftySectorRegistry` to select top candidate stocks (filtering out circuit-locked / overextended moves > 5%).
2. `LowestVolumeReversalService` monitors 5-minute candles, establishes baseline volume (ignoring candles 1–3), tracks rolling minimum volume, arms triggers on opposite-color low volume candles, and trails orders dynamically.
3. `LowestVolumeReversalService` executes ATM option buying upon spot trigger breach, monitoring underlying spot price for SL, 1:4 RR partial exit (50% lots), Cost SL movement, 10 EMA trailing, and 15:15 IST hard exit.

**Tech Stack:** Java 21, Spring Boot 3.3.4, Shoonya (NorenAPI), Gradle, JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-09-18-lowest-volume-reversal-kushal-varshney-options-design.md`

## Global Constraints
- Target Java version: 21, Spring Boot 3.3.4.
- All tests must pass cleanly at each step (`./gradlew test`).
- 5-minute timeframe (`5m`) only; ignore candles 1, 2, 3 (09:15–09:30 IST) for entry.
- Max attempts per stock per day: 2.
- Options buying only (ATM PE for Bearish, ATM CE for Bullish); underlying spot price drives SL and 1:4 RR targets.

---

### Task 1: Domain Models & NiftySectorRegistry

**Files:**
- Create: `src/main/java/com/tradingbot/util/NiftySectorRegistry.java`
- Create: `src/main/java/com/tradingbot/model/strategy/LowestVolumeSectorState.java`
- Modify: `src/main/java/com/tradingbot/model/strategy/LowestVolumeSetupState.java`
- Modify: `src/main/java/com/tradingbot/model/strategy/LowestVolumeSetup.java`
- Modify: `src/main/java/com/tradingbot/model/strategy/LowestVolumePaperPosition.java`
- Test: `src/test/java/com/tradingbot/util/NiftySectorRegistryTest.java`

**Interfaces:**
- `NiftySectorRegistry.getSectors()` -> `Map<String, List<String>>` (11 sectors mapped to F&O symbols)
- `NiftySectorRegistry.getSectorForSymbol(String symbol)` -> `String`
- `LowestVolumeSectorState` record: `(int advances, int declines, LowestVolumeDirection sentiment, String topSector, double sectorPctChange, List<String> candidateSymbols)`

- [ ] **Step 1: Write unit tests for NiftySectorRegistry**

```java
package com.tradingbot.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NiftySectorRegistryTest {

    @Test
    @DisplayName("Should contain all 11 NSE sectors with valid F&O constituents")
    void testAll11SectorsPresent() {
        Map<String, List<String>> sectors = NiftySectorRegistry.getSectorConstituents();
        assertEquals(11, sectors.size());
        assertTrue(sectors.containsKey("NIFTY MEDIA"));
        assertTrue(sectors.containsKey("NIFTY IT"));
        assertTrue(sectors.containsKey("NIFTY PHARMA"));
        assertTrue(sectors.containsKey("NIFTY AUTO"));
        assertTrue(sectors.containsKey("NIFTY METAL"));
        assertTrue(sectors.containsKey("NIFTY BANK"));
        assertTrue(sectors.containsKey("NIFTY FIN SERVICE"));
        assertTrue(sectors.containsKey("NIFTY FMCG"));
        assertTrue(sectors.containsKey("NIFTY PSU BANK"));
        assertTrue(sectors.containsKey("NIFTY REALTY"));
        assertTrue(sectors.containsKey("NIFTY ENERGY"));

        assertTrue(sectors.get("NIFTY MEDIA").contains("PVRINOX"));
        assertTrue(sectors.get("NIFTY MEDIA").contains("SUNTV"));
        assertTrue(sectors.get("NIFTY IT").contains("TCS"));
        assertTrue(sectors.get("NIFTY IT").contains("INFY"));
    }

    @Test
    @DisplayName("Should correctly find sector by stock symbol")
    void testGetSectorForSymbol() {
        assertEquals("NIFTY MEDIA", NiftySectorRegistry.getSectorForSymbol("PVRINOX"));
        assertEquals("NIFTY IT", NiftySectorRegistry.getSectorForSymbol("INFY"));
        assertEquals("NIFTY PHARMA", NiftySectorRegistry.getSectorForSymbol("GLENMARK"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.util.NiftySectorRegistryTest`
Expected: FAIL (class not found)

- [ ] **Step 3: Implement NiftySectorRegistry and updated Strategy Models**

Create `NiftySectorRegistry.java` with 11 sectors and constituent F&O symbols.
Create `LowestVolumeSectorState.java`.
Update `LowestVolumeSetup.java`, `LowestVolumeSetupState.java`, and `LowestVolumePaperPosition.java` with fields:
- `armedCandleTime`, `spotTriggerPrice`, `spotSlPrice`, `target1Price` (1:4 RR), `target1Reached` (boolean), `costSlArmed` (boolean), `optionSymbol`, `optionStrike`, `optionType` ("CE"/"PE"), `totalLots`, `openLots`, `closedLots`, `entryOptionLtp`, `exitOptionLtp`, `attemptsCount`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.util.NiftySectorRegistryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/util/NiftySectorRegistry.java src/main/java/com/tradingbot/model/strategy/ src/test/java/com/tradingbot/util/NiftySectorRegistryTest.java
git commit -m "feat(lvr): add NiftySectorRegistry and updated LVR domain models"
```

---

### Task 2: LowestVolumeReversalScanner (09:25 IST Sentiment & Sector Ranking)

**Files:**
- Create: `src/main/java/com/tradingbot/service/LowestVolumeReversalScanner.java`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalScannerTest.java`

**Interfaces:**
- `LowestVolumeReversalScanner.evaluateMarketSentiment(List<StockQuoteSnapshot> nifty50Quotes)` -> `LowestVolumeDirection` (BULLISH if adv > dec, else BEARISH)
- `LowestVolumeReversalScanner.rankSectors(Map<String, List<StockQuoteSnapshot>> sectorQuotes, LowestVolumeDirection sentiment)` -> `List<SectorRankResult>`
- `LowestVolumeReversalScanner.filterCandidateStocks(List<StockQuoteSnapshot> sectorStockQuotes, LowestVolumeDirection sentiment)` -> `List<String>` (filters out > 5% moves, circuit locked, limits to top 2-3)

- [ ] **Step 1: Write unit tests for LowestVolumeReversalScanner**

```java
package com.tradingbot.service;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LowestVolumeReversalScannerTest {

    private final LowestVolumeReversalScanner scanner = new LowestVolumeReversalScanner();

    @Test
    @DisplayName("Should detect Bearish sentiment when Declines > Advances")
    void testBearishSentiment() {
        List<StockQuoteSnapshot> quotes = List.of(
            new StockQuoteSnapshot("TCS", "11536", BigDecimal.valueOf(3900), BigDecimal.valueOf(4000), -2.5, 10000),
            new StockQuoteSnapshot("INFY", "1594", BigDecimal.valueOf(1800), BigDecimal.valueOf(1850), -2.7, 10000),
            new StockQuoteSnapshot("RELIANCE", "2885", BigDecimal.valueOf(2900), BigDecimal.valueOf(2920), -0.68, 10000),
            new StockQuoteSnapshot("HDFCBANK", "1333", BigDecimal.valueOf(1600), BigDecimal.valueOf(1590), 0.63, 10000)
        );
        LowestVolumeDirection dir = scanner.evaluateMarketSentiment(quotes);
        assertEquals(LowestVolumeDirection.BEARISH, dir);
    }

    @Test
    @DisplayName("Should rank sectors and select Top Loser sector during Bearish sentiment")
    void testRankSectorsBearish() {
        Map<String, List<StockQuoteSnapshot>> sectorData = Map.of(
            "NIFTY MEDIA", List.of(new StockQuoteSnapshot("PVRINOX", "13147", BigDecimal.valueOf(1500), BigDecimal.valueOf(1600), -4.75, 1000)),
            "NIFTY IT", List.of(new StockQuoteSnapshot("INFY", "1594", BigDecimal.valueOf(1800), BigDecimal.valueOf(1850), -2.7, 1000)),
            "NIFTY PHARMA", List.of(new StockQuoteSnapshot("SUNPHARMA", "3351", BigDecimal.valueOf(1700), BigDecimal.valueOf(1680), 1.2, 1000))
        );
        var ranked = scanner.rankSectors(sectorData, LowestVolumeDirection.BEARISH);
        assertEquals("NIFTY MEDIA", ranked.get(0).sectorName());
        assertEquals(-4.75, ranked.get(0).pctChange(), 0.001);
    }

    @Test
    @DisplayName("Should filter out overextended stocks (> 5%) and circuit locked stocks")
    void testFilterCandidateStocks() {
        List<StockQuoteSnapshot> quotes = List.of(
            new StockQuoteSnapshot("PVRINOX", "13147", BigDecimal.valueOf(1550), BigDecimal.valueOf(1600), -3.12, 1000),
            new StockQuoteSnapshot("SUNTV", "13401", BigDecimal.valueOf(780), BigDecimal.valueOf(800), -2.50, 1000),
            new StockQuoteSnapshot("OVEREXTENDED", "9999", BigDecimal.valueOf(90), BigDecimal.valueOf(100), -10.0, 1000),
            new StockQuoteSnapshot("TOO_FAR", "8888", BigDecimal.valueOf(94), BigDecimal.valueOf(100), -6.0, 1000)
        );
        List<String> candidates = scanner.filterCandidateStocks(quotes, LowestVolumeDirection.BEARISH);
        assertEquals(2, candidates.size());
        assertTrue(candidates.contains("PVRINOX"));
        assertTrue(candidates.contains("SUNTV"));
        assertFalse(candidates.contains("OVEREXTENDED"));
        assertFalse(candidates.contains("TOO_FAR"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalScannerTest`
Expected: FAIL (class not found)

- [ ] **Step 3: Implement LowestVolumeReversalScanner**

Implement `LowestVolumeReversalScanner.java` with methods `evaluateMarketSentiment`, `rankSectors`, and `filterCandidateStocks`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalScannerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/service/LowestVolumeReversalScanner.java src/test/java/com/tradingbot/service/LowestVolumeReversalScannerTest.java
git commit -m "feat(lvr): implement 09:25 market sentiment and sectoral ranking scanner"
```

---

### Task 3: 5-Minute Candle Lowest Volume Engine & Order Trailing

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

**Interfaces:**
- Baseline: `min(Vol_C1, Vol_C2, Vol_C3)` ignoring entries on candles 1, 2, 3.
- Pullback trigger:
  - Bearish: Green candle ($C > O$) with $\text{Vol} < \text{dayLowestVolume} \rightarrow$ Armed (Trigger = $\text{Low} - 0.05$, SL = $\text{High} + 0.05$).
  - Bullish: Red candle ($C < O$) with $\text{Vol} < \text{dayLowestVolume} \rightarrow$ Armed (Trigger = $\text{High} + 0.05$, SL = $\text{Low} - 0.05$).
- Trailing: If pending order not triggered and a newer opposite candle with lower volume forms, update armed trigger and SL levels.

- [ ] **Step 1: Write unit tests for 5-minute lowest volume detection and trailing**

```java
package com.tradingbot.service;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.model.Candle;
import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.LowestVolumeSetup;
import com.tradingbot.model.strategy.LowestVolumeSetupState;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LowestVolumeCandleEngineTest {

    @Test
    @DisplayName("Should ignore first 3 candles for entry and compute baseline dayLowestVolume")
    void testFirst3CandlesBaseline() {
        // C1: 09:15 vol 10000, C2: 09:20 vol 8000, C3: 09:25 vol 6000
        Instant t0 = Instant.parse("2026-09-18T03:45:00Z"); // 09:15 IST
        List<Candle> candles = List.of(
            new Candle(t0, BigDecimal.valueOf(100), BigDecimal.valueOf(105), BigDecimal.valueOf(98), BigDecimal.valueOf(102), 10000),
            new Candle(t0.plus(5, ChronoUnit.MINUTES), BigDecimal.valueOf(102), BigDecimal.valueOf(103), BigDecimal.valueOf(99), BigDecimal.valueOf(100), 8000),
            new Candle(t0.plus(10, ChronoUnit.MINUTES), BigDecimal.valueOf(100), BigDecimal.valueOf(101), BigDecimal.valueOf(97), BigDecimal.valueOf(98), 6000)
        );
        // Verify day lowest volume is 6000 and no setup armed on first 3 candles
    }

    @Test
    @DisplayName("Should arm SHORT setup on Green candle 4 with volume < 6000")
    void testArmShortSetupOnGreenLowVolume() {
        Instant t0 = Instant.parse("2026-09-18T03:45:00Z");
        List<Candle> candles = List.of(
            new Candle(t0, BigDecimal.valueOf(100), BigDecimal.valueOf(105), BigDecimal.valueOf(98), BigDecimal.valueOf(102), 10000),
            new Candle(t0.plus(5, ChronoUnit.MINUTES), BigDecimal.valueOf(102), BigDecimal.valueOf(103), BigDecimal.valueOf(99), BigDecimal.valueOf(100), 8000),
            new Candle(t0.plus(10, ChronoUnit.MINUTES), BigDecimal.valueOf(100), BigDecimal.valueOf(101), BigDecimal.valueOf(97), BigDecimal.valueOf(98), 6000),
            // C4: 09:30 Green candle (O=98, H=102, L=97, C=101), Vol = 4500 (< 6000)
            new Candle(t0.plus(15, ChronoUnit.MINUTES), BigDecimal.valueOf(98), BigDecimal.valueOf(102), BigDecimal.valueOf(97), BigDecimal.valueOf(101), 4500)
        );
        // Verify setup is ARMED with Trigger = 96.95, SL = 102.05
    }

    @Test
    @DisplayName("Should trail trigger when a subsequent Green candle forms with even lower volume")
    void testTrailOrderOnNewerLowerVolumeGreenCandle() {
        // C4: Green Vol 4500, C5: Green Vol 3000 (O=101, H=104, L=100, C=103)
        // Verify trigger updates to 99.95, SL to 104.05
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeCandleEngineTest`
Expected: FAIL

- [ ] **Step 3: Implement 5-minute lowest volume tracking & order trailing in LowestVolumeReversalService**

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeCandleEngineTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/service/LowestVolumeReversalService.java src/test/java/com/tradingbot/service/LowestVolumeCandleEngineTest.java
git commit -m "feat(lvr): implement 5m candle lowest volume baseline, trigger arming, and dynamic order trailing"
```

---

### Task 4: Option Execution, 1:4 Spot Partial Exit & Cost SL Trailing

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Modify: `src/main/java/com/tradingbot/model/strategy/LowestVolumePaperPosition.java`
- Test: `src/test/java/com/tradingbot/service/LowestVolumePositionManagementTest.java`

**Interfaces:**
- Strike Selection: Nearest Monthly ATM Strike for underlying at spot trigger breach.
- Spot SL: Close all lots immediately if spot hits $\text{SL Spot}$.
- 1:4 RR Target: When spot reaches $4 \times (\text{Entry} - \text{SL})$:
  - Exit 50% lots (e.g. 1 of 2 lots).
  - Move SL of remaining lots to Entry Spot (Cost SL).
- Trailing Exit: 10 EMA cross on 5m candle or 15:15 IST hard square-off.
- Max 2 attempts per stock.

- [ ] **Step 1: Write unit tests for Option Execution and Spot-based 1:4 Target / Cost SL / 10 EMA Trailing**

```java
package com.tradingbot.service;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.LowestVolumePaperPosition;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LowestVolumePositionManagementTest {

    @Test
    @DisplayName("Should execute 1:4 RR partial exit on spot reaching target and adjust to Cost SL")
    void testOneToFourRrPartialExitAndCostSl() {
        // Bearish trade: Spot Entry = 100.0, Spot SL = 102.0 (Risk = 2.0). 1:4 Target = 92.0 (100 - 8).
        // Option Entry: 2 lots at premium 15.0.
        // Price drops to 92.0:
        // 1 lot booked at current option LTP.
        // Remaining 1 lot SL moved to Spot 100.0 (Cost SL).
    }

    @Test
    @DisplayName("Should exit remaining lots at 15:15 IST hard cutoff")
    void testHardExitAt1515() {
        // Verify all open positions are marked CLOSED at 15:15 IST
    }
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumePositionManagementTest`
Expected: FAIL

- [ ] **Step 3: Implement position management and trailing logic**

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumePositionManagementTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/service/LowestVolumeReversalService.java src/main/java/com/tradingbot/model/strategy/LowestVolumePaperPosition.java src/test/java/com/tradingbot/service/LowestVolumePositionManagementTest.java
git commit -m "feat(lvr): implement ATM option buying, 1:4 spot RR partial exit, Cost SL, and 15:15 EOD exit"
```

---

### Task 5: Scheduler, Controller & Telegram Notifications Integration

**Files:**
- Modify: `src/main/java/com/tradingbot/scheduler/LowestVolumeReversalScheduler.java`
- Modify: `src/main/java/com/tradingbot/controller/LowestVolumeStrategyController.java`
- Modify: `src/main/java/com/tradingbot/telegram/TelegramService.java`
- Test: `src/test/java/com/tradingbot/controller/LowestVolumeStrategyControllerTest.java`

**Interfaces:**
- Scheduler:
  - 09:15 IST: Daily state reset.
  - 09:25 IST: Morning market sentiment & sector scan.
  - Every 5 minutes (09:30–13:00 IST): Candle evaluation cycle.
  - Every 30 seconds (09:30–15:15 IST): Spot price trigger breach & SL/Target check.
  - 15:15 IST: Hard EOD position square-off.
- Telegram Alerts: Sector winner alert, setup armed alert, option execution alert, 1:4 partial exit alert, cost SL move alert, and EOD report.

- [ ] **Step 1: Write integration tests for controller & scheduler**

- [ ] **Step 2: Run test to verify failure**

- [ ] **Step 3: Implement scheduler timing, controller endpoints, and formatted Telegram alerts**

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.controller.LowestVolumeStrategyControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/scheduler/LowestVolumeReversalScheduler.java src/main/java/com/tradingbot/controller/LowestVolumeStrategyController.java src/main/java/com/tradingbot/telegram/TelegramService.java src/test/java/com/tradingbot/controller/LowestVolumeStrategyControllerTest.java
git commit -m "feat(lvr): wire 09:25 scan scheduler, controller endpoints, and telegram alerts"
```

---

### Task 6: Full Regression Verification

**Files:**
- All tests across repository.

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test`
Expected: 0 failures, all tests PASS

- [ ] **Step 2: Commit any cleanups and verify git tree is clean**

```bash
git status
```
