# RSI Highway Multi-Timeframe Swing Strategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the "RSI Highway" multi-timeframe swing trading strategy on the Nifty 500 universe with automated 15:00 IST EOD scanning & execution, 09:30 IST morning plunge checks, dynamic RSI 50 trailing exits, and inverted pyramiding in `shoonya-trading-bot`.

**Architecture:** A top-down multi-timeframe engine that analyzes Monthly RSI(14) ("Highway" macro gate ≥ 60) and Weekly RSI(14) ("Car" momentum gate ≥ 60) from resampled daily candles, detects Daily RSI(14) 50 pullback/bounce or fresh crossover setups with candlestick price action triggers, manages portfolio risk via a 52-week high market breadth gate, handles multi-tranche pyramiding (up to 3 tranches), and executes CNC/delivery equity orders on Shoonya (Finvasia) with Telegram notifications and JSON state persistence.

**Tech Stack:** Java 21, Spring Boot 3.x, TA-Lib (Wilder's RSI, ATR), Jackson JSON, Shoonya NorenAPI HTTP Client, JUnit 5, AssertJ, Mockito.

**Spec:** `rsi_highway_swing_strategy_spec.md`

## Global Constraints

- **Language & Runtime:** Java 21 LTS with Spring Boot 3.x.
- **Universe:** 500 Nifty Equities registered in `Nifty500Registry` with exchange `NSE` and tick size `0.05`.
- **Timeframe Resampling:** Wilder's RSI(14) on Monthly (resampled), Weekly (resampled), and Daily EOD candles.
- **Scanning Times:** Daily EOD scan at **15:00 IST** (`0 0 15 * * MON-FRI`); Morning Health Check at **09:30 IST** (`0 30 9 * * MON-FRI`).
- **Trailing & Exit Rule:** Daily close with `Daily RSI < 50.0` triggers 100% position exit at 15:00–15:20 IST. Morning emergency plunge with `Daily RSI < 45.0` triggers immediate market exit at 09:30 IST.
- **Pyramiding:** Max 3 tranches (100% base, 50% add-1, 25% add-2) on subsequent daily RSI 50 bounces while Monthly & Weekly remain ≥ 60.
- **State File:** `data/rsi_highway_state.json`.

---

## File Structure & Responsibility Map

```
src/main/java/com/tradingbot/
├── util/
│   ├── Nifty500Registry.java                     # Complete registry of all 500 Nifty stocks with token lookup
│   └── CandleResamplingUtil.java                 # Extended to support Daily -> Monthly resampling
├── strategy/rsihighway/
│   ├── config/
│   │   └── RsiHighwayConfig.java                 # Spring Boot @ConfigurationProperties for RSI Highway strategy
│   ├── model/
│   │   ├── PriceActionPattern.java               # Enum: BULLISH_ENGULFING, HAMMER, MOMENTUM_EXPANSION, BREAKOUT
│   │   ├── RsiHighwaySignalType.java             # Enum: INITIAL_ENTRY, PYRAMID_TRANCHE_2, PYRAMID_TRANCHE_3, EXIT_RSI_50, EMERGENCY_EXIT
│   │   ├── MultiTimeframeRsiSnapshot.java        # Record holding Monthly, Weekly, Daily RSI, ATR, and PA pattern
│   │   ├── MarketBreadthSnapshot.java            # Record for 52W High breadth metrics & market regime
│   │   ├── RsiHighwayTranche.java                # Record representing single fill tranche
│   │   ├── RsiHighwayPosition.java               # Aggregate position model with tranches, avg price, SL, and status
│   │   ├── RsiHighwaySignal.java                 # Buy/Sell signal event model
│   │   └── RsiHighwayState.java                  # Persistent JSON state model (open positions, history, breadth)
│   ├── indicator/
│   │   ├── MultiTimeframeRsiService.java         # Computes Monthly, Weekly, Daily RSI & ATR from daily candles
│   │   └── PriceActionPatternDetector.java       # Identifies Engulfing, Hammer, Expansion, and Breakout candles
│   ├── service/
│   │   ├── RsiHighwayMarketBreadthService.java   # Computes 52-week High breadth score & index drawdown filter
│   │   ├── RsiHighwaySwingService.java           # Core strategy orchestrator (15:00 EOD scan & 09:30 morning check)
│   │   └── RsiHighwayExecutionService.java       # Handles order placement (Shoonya CNC / Paper) and trade management
│   ├── scheduler/
│   │   └── RsiHighwayScheduler.java              # Cron schedulers: 15:00 IST EOD scan and 09:30 IST morning check
│   └── controller/
│       └── RsiHighwayController.java             # REST API for manual scan, state inspection, breadth, force exit
```

---

## Implementation Tasks

### Task 1: Nifty 500 Universe Registry & Monthly Candle Resampling

**Files:**
- Create: `src/main/java/com/tradingbot/util/Nifty500Registry.java`
- Modify: `src/main/java/com/tradingbot/util/CandleResamplingUtil.java`
- Test: `src/test/java/com/tradingbot/util/Nifty500RegistryTest.java`
- Test: `src/test/java/com/tradingbot/util/CandleResamplingUtilTest.java`

**Interfaces:**
- `Nifty500Registry.getAllSymbols()` -> `List<String>` (500 symbols)
- `Nifty500Registry.containsSymbol(String symbol)` -> `boolean`
- `Nifty500Registry.getMetadata(String symbol)` -> `StockMetadata`
- `CandleResamplingUtil.resampleDailyToMonthly(List<Candle> dailyCandles)` -> `List<Candle>`

- [ ] **Step 1: Write failing unit test for Nifty500Registry and Monthly Resampling**

```java
// src/test/java/com/tradingbot/util/Nifty500RegistryTest.java
package com.tradingbot.util;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class Nifty500RegistryTest {

    @Test
    void testRegistryContains500Symbols() {
        List<String> symbols = Nifty500Registry.getAllSymbols();
        assertThat(symbols).isNotEmpty();
        assertThat(symbols.size()).isGreaterThanOrEqualTo(500);
        assertThat(Nifty500Registry.containsSymbol("RELIANCE")).isTrue();
        assertThat(Nifty500Registry.containsSymbol("TCS")).isTrue();
        assertThat(Nifty500Registry.containsSymbol("HDFCBANK")).isTrue();
        assertThat(Nifty500Registry.containsSymbol("360ONE")).isTrue();
        assertThat(Nifty500Registry.containsSymbol("ECLERX")).isTrue();
        assertThat(Nifty500Registry.containsSymbol("INVALID_XYZ_STOCK")).isFalse();
    }
}
```

```java
// src/test/java/com/tradingbot/util/CandleResamplingUtilTest.java (add Monthly test)
@Test
void testResampleDailyToMonthly() {
    List<Candle> daily = List.of(
        new Candle("TCS", "D", Instant.parse("2026-01-05T10:00:00Z"), BigDecimal.valueOf(3500), BigDecimal.valueOf(3600), BigDecimal.valueOf(3480), BigDecimal.valueOf(3550), 1000L),
        new Candle("TCS", "D", Instant.parse("2026-01-20T10:00:00Z"), BigDecimal.valueOf(3550), BigDecimal.valueOf(3700), BigDecimal.valueOf(3540), BigDecimal.valueOf(3680), 2000L),
        new Candle("TCS", "D", Instant.parse("2026-02-02T10:00:00Z"), BigDecimal.valueOf(3680), BigDecimal.valueOf(3750), BigDecimal.valueOf(3650), BigDecimal.valueOf(3720), 1500L)
    );
    List<Candle> monthly = CandleResamplingUtil.resampleDailyToMonthly(daily);
    assertThat(monthly).hasSize(2);
    assertThat(monthly.get(0).timeframe()).isEqualTo("1M");
    assertThat(monthly.get(0).open()).isEqualByComparingTo("3500");
    assertThat(monthly.get(0).high()).isEqualByComparingTo("3700");
    assertThat(monthly.get(0).close()).isEqualByComparingTo("3680");
    assertThat(monthly.get(0).volume()).isEqualTo(3000L);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.util.Nifty500RegistryTest --tests com.tradingbot.util.CandleResamplingUtilTest`
Expected: Compilation failure / Symbol not found.

- [ ] **Step 3: Implement `Nifty500Registry` with all 500 symbols and `resampleDailyToMonthly` in `CandleResamplingUtil`**

In `Nifty500Registry.java`: Populate all 500 symbols given in prompt (360ONE, 3MINDIA, ABB, ..., ECLERX), with mapping to `StockMetadata(symbol, token, isFno, lotSize, strikeStep)`.
In `CandleResamplingUtil.java`: Implement `resampleDailyToMonthly(List<Candle> dailyCandles)` by grouping candles with `year * 100 + localDate.getMonthValue()`.

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.util.Nifty500RegistryTest --tests com.tradingbot.util.CandleResamplingUtilTest`
Expected: BUILD SUCCESSFUL (all assertions pass).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/util/Nifty500Registry.java src/main/java/com/tradingbot/util/CandleResamplingUtil.java src/test/java/com/tradingbot/util/Nifty500RegistryTest.java src/test/java/com/tradingbot/util/CandleResamplingUtilTest.java
git commit -m "feat(rsihighway): add Nifty500 registry and daily-to-monthly candle resampler"
```

---

### Task 2: Multi-Timeframe RSI Indicator & Price Action Pattern Detector

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/rsihighway/model/PriceActionPattern.java`
- Create: `src/main/java/com/tradingbot/strategy/rsihighway/model/MultiTimeframeRsiSnapshot.java`
- Create: `src/main/java/com/tradingbot/strategy/rsihighway/indicator/PriceActionPatternDetector.java`
- Create: `src/main/java/com/tradingbot/strategy/rsihighway/indicator/MultiTimeframeRsiService.java`
- Test: `src/test/java/com/tradingbot/strategy/rsihighway/indicator/PriceActionPatternDetectorTest.java`
- Test: `src/test/java/com/tradingbot/strategy/rsihighway/indicator/MultiTimeframeRsiServiceTest.java`

**Interfaces:**
- `PriceActionPatternDetector.detectPattern(List<Candle> dailyCandles, double atr)` -> `Optional<PriceActionPattern>`
- `PriceActionPatternDetector.isRsi50BounceOrCross(List<Double> dailyRsiSeries)` -> `boolean`
- `MultiTimeframeRsiService.computeSnapshot(String symbol, List<Candle> dailyCandles)` -> `MultiTimeframeRsiSnapshot`

- [ ] **Step 1: Write failing unit test for PriceActionPatternDetector and MultiTimeframeRsiService**

```java
// src/test/java/com/tradingbot/strategy/rsihighway/indicator/PriceActionPatternDetectorTest.java
package com.tradingbot.strategy.rsihighway.indicator;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.rsihighway.model.PriceActionPattern;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;

class PriceActionPatternDetectorTest {

    private final PriceActionPatternDetector detector = new PriceActionPatternDetector();

    @Test
    void testBullishEngulfingPattern() {
        Candle prevRed = new Candle("TATAMOTORS", "D", Instant.parse("2026-03-01T10:00:00Z"),
                BigDecimal.valueOf(100), BigDecimal.valueOf(102), BigDecimal.valueOf(95), BigDecimal.valueOf(96), 1000L);
        Candle currGreen = new Candle("TATAMOTORS", "D", Instant.parse("2026-03-02T10:00:00Z"),
                BigDecimal.valueOf(95), BigDecimal.valueOf(105), BigDecimal.valueOf(94), BigDecimal.valueOf(104), 2000L);

        Optional<PriceActionPattern> pattern = detector.detectPattern(List.of(prevRed, currGreen), 5.0);
        assertThat(pattern).isPresent().contains(PriceActionPattern.BULLISH_ENGULFING);
    }

    @Test
    void testHammerPinbarPattern() {
        Candle prev = new Candle("INFY", "D", Instant.parse("2026-03-01T10:00:00Z"),
                BigDecimal.valueOf(1500), BigDecimal.valueOf(1510), BigDecimal.valueOf(1490), BigDecimal.valueOf(1495), 1000L);
        // Hammer: Open 1490, High 1502, Low 1450, Close 1500 -> Body = 10, Lower Wick = 40 (4x body), Upper Wick = 2
        Candle hammer = new Candle("INFY", "D", Instant.parse("2026-03-02T10:00:00Z"),
                BigDecimal.valueOf(1490), BigDecimal.valueOf(1502), BigDecimal.valueOf(1450), BigDecimal.valueOf(1500), 2500L);

        Optional<PriceActionPattern> pattern = detector.detectPattern(List.of(prev, hammer), 20.0);
        assertThat(pattern).isPresent().contains(PriceActionPattern.HAMMER);
    }

    @Test
    void testRsi50BounceDetection() {
        // Pullback to 52, then bounce to 56
        List<Double> rsiSeries = List.of(65.0, 58.0, 52.0, 56.5);
        assertThat(detector.isRsi50BounceOrCross(rsiSeries)).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.rsihighway.indicator.PriceActionPatternDetectorTest`
Expected: Compilation failure.

- [ ] **Step 3: Implement `PriceActionPatternDetector` and `MultiTimeframeRsiService`**

- `PriceActionPatternDetector.java`: Implements checks for `BULLISH_ENGULFING`, `HAMMER`, `MOMENTUM_EXPANSION`, and `HORIZONTAL_BREAKOUT`. Implements `isRsi50BounceOrCross` verifying pullback into [48, 55] followed by upward turn $\ge 50.0$ or cross above 50.
- `MultiTimeframeRsiService.java`: Takes daily candles (minimum 250 bars), resamples to Weekly and Monthly using `CandleResamplingUtil`, computes Wilder's RSI(14) via `TechnicalAnalysisService` (or TA-Lib) on Monthly, Weekly, and Daily series, plus Daily ATR(14), returning `MultiTimeframeRsiSnapshot`.

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.strategy.rsihighway.indicator.PriceActionPatternDetectorTest --tests com.tradingbot.strategy.rsihighway.indicator.MultiTimeframeRsiServiceTest`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/rsihighway/model/ src/main/java/com/tradingbot/strategy/rsihighway/indicator/ src/test/java/com/tradingbot/strategy/rsihighway/indicator/
git commit -m "feat(rsihighway): add multi-timeframe RSI calculator and price action detector"
```

---

### Task 3: Market Breadth & Macro Gate Service

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/rsihighway/model/MarketBreadthSnapshot.java`
- Create: `src/main/java/com/tradingbot/strategy/rsihighway/service/RsiHighwayMarketBreadthService.java`
- Test: `src/test/java/com/tradingbot/strategy/rsihighway/service/RsiHighwayMarketBreadthServiceTest.java`

**Interfaces:**
- `RsiHighwayMarketBreadthService.evaluateBreadth(Map<String, List<Candle>> universeDailyCandles, List<Candle> indexCandles)` -> `MarketBreadthSnapshot`
- `MarketBreadthSnapshot.isHighwayOpen()` -> `boolean`
- `MarketBreadthSnapshot.leadersNear52WeekHighCount()` -> `int`

- [ ] **Step 1: Write failing unit test for RsiHighwayMarketBreadthService**

```java
// src/test/java/com/tradingbot/strategy/rsihighway/service/RsiHighwayMarketBreadthServiceTest.java
package com.tradingbot.strategy.rsihighway.service;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.rsihighway.model.MarketBreadthSnapshot;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class RsiHighwayMarketBreadthServiceTest {

    private final RsiHighwayMarketBreadthService breadthService = new RsiHighwayMarketBreadthService();

    @Test
    void testHealthyMarketBreadthOpensHighway() {
        Map<String, List<Candle>> candlesMap = new HashMap<>();
        // Create 20 stocks at 52-week highs
        for (int i = 0; i < 20; i++) {
            candlesMap.put("STOCK_" + i, createCandlesNearHigh());
        }

        List<Candle> indexCandles = createIndexCandles(0.05); // Index only 5% below ATH
        MarketBreadthSnapshot snapshot = breadthService.evaluateBreadth(candlesMap, indexCandles);

        assertThat(snapshot.isHighwayOpen()).isTrue();
        assertThat(snapshot.leadersNear52WeekHighCount()).isGreaterThanOrEqualTo(15);
    }

    @Test
    void testSevereIndexDrawdownClosesHighway() {
        Map<String, List<Candle>> candlesMap = new HashMap<>();
        candlesMap.put("STOCK_1", createCandlesNearHigh());

        List<Candle> indexCandles = createIndexCandles(0.25); // Index 25% below ATH (> 20% limit)
        MarketBreadthSnapshot snapshot = breadthService.evaluateBreadth(candlesMap, indexCandles);

        assertThat(snapshot.isHighwayOpen()).isFalse();
        assertThat(snapshot.reason()).contains("Index Drawdown");
    }

    private List<Candle> createCandlesNearHigh() {
        List<Candle> list = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            list.add(new Candle("SYM", "D", Instant.now().minusSeconds((250 - i) * 86400L),
                    BigDecimal.valueOf(100 + i), BigDecimal.valueOf(102 + i), BigDecimal.valueOf(99 + i), BigDecimal.valueOf(101 + i), 10000L));
        }
        return list;
    }

    private List<Candle> createIndexCandles(double drawdownPct) {
        List<Candle> list = new ArrayList<>();
        list.add(new Candle("NIFTY_MIDCAP", "D", Instant.now().minusSeconds(864000L),
                BigDecimal.valueOf(50000), BigDecimal.valueOf(55000), BigDecimal.valueOf(49000), BigDecimal.valueOf(55000), 100000L)); // ATH 55000
        double currentPrice = 55000 * (1.0 - drawdownPct);
        list.add(new Candle("NIFTY_MIDCAP", "D", Instant.now(),
                BigDecimal.valueOf(currentPrice), BigDecimal.valueOf(currentPrice + 100), BigDecimal.valueOf(currentPrice - 100), BigDecimal.valueOf(currentPrice), 100000L));
        return list;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.rsihighway.service.RsiHighwayMarketBreadthServiceTest`
Expected: Compilation failure.

- [ ] **Step 3: Implement `RsiHighwayMarketBreadthService`**

Implement 52-week High computation (close within 2% of 250-day rolling max) and index peak-to-trough drawdown check against the 20% threshold.

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.strategy.rsihighway.service.RsiHighwayMarketBreadthServiceTest`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/rsihighway/model/MarketBreadthSnapshot.java src/main/java/com/tradingbot/strategy/rsihighway/service/RsiHighwayMarketBreadthService.java src/test/java/com/tradingbot/strategy/rsihighway/service/RsiHighwayMarketBreadthServiceTest.java
git commit -m "feat(rsihighway): implement market breadth and macro regime service"
```

---

### Task 4: Strategy Domain Models, Configuration & State Persistence

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/rsihighway/config/RsiHighwayConfig.java`
- Create: `src/main/java/com/tradingbot/strategy/rsihighway/model/RsiHighwayTranche.java`
- Create: `src/main/java/com/tradingbot/strategy/rsihighway/model/RsiHighwayPosition.java`
- Create: `src/main/java/com/tradingbot/strategy/rsihighway/model/RsiHighwaySignal.java`
- Create: `src/main/java/com/tradingbot/strategy/rsihighway/model/RsiHighwayState.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/tradingbot/strategy/rsihighway/model/RsiHighwayStateSerializationTest.java`

**Interfaces:**
- `RsiHighwayConfig` with properties prefix `trading-bot.strategy.rsi-highway`
- `RsiHighwayPosition.addTranche(RsiHighwayTranche tranche)` -> updates total qty and weighted average price
- `RsiHighwayPosition.calculateWeightedAveragePrice()` -> `double`
- `RsiHighwayState.saveToFile(File file, ObjectMapper mapper)` / `loadFromFile(File file, ObjectMapper mapper)`

- [ ] **Step 1: Write failing serialization and model unit tests**

```java
// src/test/java/com/tradingbot/strategy/rsihighway/model/RsiHighwayStateSerializationTest.java
package com.tradingbot.strategy.rsihighway.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RsiHighwayStateSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void testSaveAndLoadStateJson() throws Exception {
        File tempFile = Files.createTempFile("rsi_highway_state_test", ".json").toFile();
        tempFile.deleteOnExit();

        RsiHighwayState state = new RsiHighwayState();
        RsiHighwayPosition pos = new RsiHighwayPosition("RELIANCE", "NSE", 2800.0, 2600.0);
        pos.addTranche(new RsiHighwayTranche(1, 10, 2800.0, Instant.now(), "ORD_001"));
        state.getPositions().put("RELIANCE", pos);

        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile, state);

        RsiHighwayState loaded = objectMapper.readValue(tempFile, RsiHighwayState.class);
        assertThat(loaded.getPositions()).containsKey("RELIANCE");
        assertThat(loaded.getPositions().get("RELIANCE").getTotalQuantity()).isEqualTo(10);
        assertThat(loaded.getPositions().get("RELIANCE").getAveragePrice()).isEqualTo(2800.0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.rsihighway.model.RsiHighwayStateSerializationTest`
Expected: Compilation failure.

- [ ] **Step 3: Implement Config, Models, Position aggregation, and update application.properties**

Create configuration and domain classes. Add configuration defaults to `application.properties`:
```properties
# RSI Highway Multi-Timeframe Swing Strategy
trading-bot.strategy.rsi-highway.enabled=${RSI_HIGHWAY_ENABLED:true}
trading-bot.strategy.rsi-highway.eod-scan-cron=${RSI_HIGHWAY_EOD_CRON:0 0 15 * * MON-FRI}
trading-bot.strategy.rsi-highway.morning-check-cron=${RSI_HIGHWAY_MORNING_CRON:0 30 9 * * MON-FRI}
trading-bot.strategy.rsi-highway.monthly-rsi-threshold=${RSI_HIGHWAY_MONTHLY_RSI:60.0}
trading-bot.strategy.rsi-highway.weekly-rsi-threshold=${RSI_HIGHWAY_WEEKLY_RSI:60.0}
trading-bot.strategy.rsi-highway.daily-rsi-lower=${RSI_HIGHWAY_DAILY_RSI_LOWER:48.0}
trading-bot.strategy.rsi-highway.daily-rsi-upper=${RSI_HIGHWAY_DAILY_RSI_UPPER:55.0}
trading-bot.strategy.rsi-highway.daily-rsi-exit=${RSI_HIGHWAY_DAILY_RSI_EXIT:50.0}
trading-bot.strategy.rsi-highway.morning-emergency-rsi=${RSI_HIGHWAY_EMERGENCY_RSI:45.0}
trading-bot.strategy.rsi-highway.max-tranches=${RSI_HIGHWAY_MAX_TRANCHES:3}
trading-bot.strategy.rsi-highway.max-concurrent-positions=${RSI_HIGHWAY_MAX_POSITIONS:10}
trading-bot.strategy.rsi-highway.risk-per-trade-percent=${RSI_HIGHWAY_RISK_PER_TRADE:1.0}
trading-bot.strategy.rsi-highway.max-capital-per-stock-percent=${RSI_HIGHWAY_MAX_CAPITAL_PER_STOCK:10.0}
trading-bot.strategy.rsi-highway.state-file-path=${RSI_HIGHWAY_STATE_FILE:data/rsi_highway_state.json}
trading-bot.strategy.rsi-highway.paper-trading=${RSI_HIGHWAY_PAPER_TRADING:true}
trading-bot.strategy.rsi-highway.min-52w-leaders=${RSI_HIGHWAY_MIN_LEADERS:5}
```

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.strategy.rsihighway.model.RsiHighwayStateSerializationTest`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/rsihighway/config/ src/main/java/com/tradingbot/strategy/rsihighway/model/ src/main/resources/application.properties src/test/java/com/tradingbot/strategy/rsihighway/model/
git commit -m "feat(rsihighway): add domain models, state persistence, and application properties"
```

---

### Task 5: Core Strategy Engine & Signal Generation

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/rsihighway/service/RsiHighwaySwingService.java`
- Test: `src/test/java/com/tradingbot/strategy/rsihighway/service/RsiHighwaySwingServiceTest.java`

**Interfaces:**
- `RsiHighwaySwingService.runEodScanAndEvaluation()` -> triggers 15:00 IST EOD scan, evaluates positions, and arms signals
- `RsiHighwaySwingService.runMorningEmergencyCheck()` -> triggers 09:30 IST morning plunge checks
- `RsiHighwaySwingService.evaluateCandidate(String symbol, List<Candle> dailyCandles)` -> `Optional<RsiHighwaySignal>`
- `RsiHighwaySwingService.evaluatePositionExit(RsiHighwayPosition position, Candle latestDailyCandle, double dailyRsi)` -> `boolean`

- [ ] **Step 1: Write failing unit test for RsiHighwaySwingService**

```java
// src/test/java/com/tradingbot/strategy/rsihighway/service/RsiHighwaySwingServiceTest.java
package com.tradingbot.strategy.rsihighway.service;

import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.strategy.rsihighway.config.RsiHighwayConfig;
import com.tradingbot.strategy.rsihighway.indicator.MultiTimeframeRsiService;
import com.tradingbot.strategy.rsihighway.indicator.PriceActionPatternDetector;
import com.tradingbot.strategy.rsihighway.model.*;
import com.tradingbot.telegram.TelegramService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RsiHighwaySwingServiceTest {

    private ShoonyaMarketDataService marketDataService;
    private MultiTimeframeRsiService rsiService;
    private PriceActionPatternDetector patternDetector;
    private RsiHighwayMarketBreadthService breadthService;
    private RsiHighwayExecutionService executionService;
    private TelegramService telegramService;
    private RsiHighwayConfig config;
    private RsiHighwaySwingService swingService;

    @BeforeEach
    void setUp() {
        marketDataService = mock(ShoonyaMarketDataService.class);
        rsiService = mock(MultiTimeframeRsiService.class);
        patternDetector = mock(PriceActionPatternDetector.class);
        breadthService = mock(RsiHighwayMarketBreadthService.class);
        executionService = mock(RsiHighwayExecutionService.class);
        telegramService = mock(TelegramService.class);
        config = new RsiHighwayConfig();

        swingService = new RsiHighwaySwingService(
            marketDataService, rsiService, patternDetector,
            breadthService, executionService, telegramService, config
        );
    }

    @Test
    void testExitTriggeredWhenDailyRsiDropsBelow50() {
        RsiHighwayPosition position = new RsiHighwayPosition("TRENT", "NSE", 5000.0, 4600.0);
        position.addTranche(new RsiHighwayTranche(1, 10, 5000.0, Instant.now(), "T1"));

        Candle dailyCandle = new Candle("TRENT", "D", Instant.now(),
                BigDecimal.valueOf(4800), BigDecimal.valueOf(4850), BigDecimal.valueOf(4700), BigDecimal.valueOf(4720), 10000L);

        // Daily RSI is 47.5 (< 50.0) -> MUST EXIT
        boolean shouldExit = swingService.evaluatePositionExit(position, dailyCandle, 47.5);
        assertThat(shouldExit).isTrue();
    }

    @Test
    void testHoldPositionWhenDailyRsiRemainsAbove50() {
        RsiHighwayPosition position = new RsiHighwayPosition("TRENT", "NSE", 5000.0, 4600.0);
        position.addTranche(new RsiHighwayTranche(1, 10, 5000.0, Instant.now(), "T1"));

        Candle dailyCandle = new Candle("TRENT", "D", Instant.now(),
                BigDecimal.valueOf(5200), BigDecimal.valueOf(5350), BigDecimal.valueOf(5180), BigDecimal.valueOf(5300), 10000L);

        // Daily RSI is 62.0 (>= 50.0) -> HOLD
        boolean shouldExit = swingService.evaluatePositionExit(position, dailyCandle, 62.0);
        assertThat(shouldExit).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.rsihighway.service.RsiHighwaySwingServiceTest`
Expected: Compilation failure.

- [ ] **Step 3: Implement `RsiHighwaySwingService`**

Implement complete EOD scan at 15:00 IST and morning 09:30 IST check orchestrations, candidate qualification, position sizing formulas, pyramiding rules, and state management.

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.strategy.rsihighway.service.RsiHighwaySwingServiceTest`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/rsihighway/service/RsiHighwaySwingService.java src/test/java/com/tradingbot/strategy/rsihighway/service/RsiHighwaySwingServiceTest.java
git commit -m "feat(rsihighway): implement core swing strategy engine and signal orchestrator"
```

---

### Task 6: Execution Service & Shoonya CNC/Delivery Order Router

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/rsihighway/service/RsiHighwayExecutionService.java`
- Test: `src/test/java/com/tradingbot/strategy/rsihighway/service/RsiHighwayExecutionServiceTest.java`

**Interfaces:**
- `RsiHighwayExecutionService.executeEntrySignal(RsiHighwaySignal signal, double allocatedCapital)` -> `Optional<RsiHighwayTranche>`
- `RsiHighwayExecutionService.executeExit(RsiHighwayPosition position, String reason)` -> `boolean`
- `RsiHighwayExecutionService.calculatePositionSize(double entryPrice, double slPrice, double accountCapital, double rptPercent, double maxCapitalPercent)` -> `int`

- [ ] **Step 1: Write failing unit test for RsiHighwayExecutionService**

```java
// src/test/java/com/tradingbot/strategy/rsihighway/service/RsiHighwayExecutionServiceTest.java
package com.tradingbot.strategy.rsihighway.service;

import com.tradingbot.order.ShoonyaOrderService;
import com.tradingbot.strategy.rsihighway.config.RsiHighwayConfig;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static org.assertj.core.api.Assertions.assertThat;

class RsiHighwayExecutionServiceTest {

    private final ShoonyaOrderService orderService = Mockito.mock(ShoonyaOrderService.class);
    private final RsiHighwayConfig config = new RsiHighwayConfig();
    private final RsiHighwayExecutionService executionService = new RsiHighwayExecutionService(orderService, config);

    @Test
    void testPositionSizingFormula() {
        // Account Capital: 1,000,000
        // RPT: 1.0% = 10,000 max risk
        // Max Capital per Stock: 10% = 100,000 max allocation
        // Entry: 1000, SL: 950 (Risk = 50/share) -> By Risk: 10000 / 50 = 200 shares -> Cost = 200,000 (exceeds 100,000 cap)
        // Expected shares capped by 100,000 / 1000 = 100 shares
        int qty = executionService.calculatePositionSize(1000.0, 950.0, 1000000.0, 1.0, 10.0);
        assertThat(qty).isEqualTo(100);
    }

    @Test
    void testPositionSizingRiskConstrained() {
        // Entry: 1000, SL: 800 (Risk = 200/share)
        // By Risk: 10000 / 200 = 50 shares -> Cost = 50,000 (below 100,000 cap)
        int qty = executionService.calculatePositionSize(1000.0, 800.0, 1000000.0, 1.0, 10.0);
        assertThat(qty).isEqualTo(50);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.rsihighway.service.RsiHighwayExecutionServiceTest`
Expected: Compilation failure.

- [ ] **Step 3: Implement `RsiHighwayExecutionService`**

Implement risk-adjusted sizing, paper execution mode, and live Shoonya CNC order placement (Product: `C` / CNC, Exchange: `NSE`).

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.strategy.rsihighway.service.RsiHighwayExecutionServiceTest`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/rsihighway/service/RsiHighwayExecutionService.java src/test/java/com/tradingbot/strategy/rsihighway/service/RsiHighwayExecutionServiceTest.java
git commit -m "feat(rsihighway): implement execution service with risk-adjusted position sizing and Shoonya CNC routing"
```

---

### Task 7: Schedulers (15:00 IST EOD & 09:30 IST Morning) & Telegram Alerts

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/rsihighway/scheduler/RsiHighwayScheduler.java`
- Modify: `src/main/java/com/tradingbot/telegram/TelegramService.java` (add RSI Highway alert templates if needed)
- Test: `src/test/java/com/tradingbot/strategy/rsihighway/scheduler/RsiHighwaySchedulerTest.java`

**Interfaces:**
- `@Scheduled(cron = "${trading-bot.strategy.rsi-highway.eod-scan-cron:0 0 15 * * MON-FRI}", zone = "Asia/Kolkata")`
- `@Scheduled(cron = "${trading-bot.strategy.rsi-highway.morning-check-cron:0 30 9 * * MON-FRI}", zone = "Asia/Kolkata")`

- [ ] **Step 1: Write failing unit test for RsiHighwayScheduler**

```java
// src/test/java/com/tradingbot/strategy/rsihighway/scheduler/RsiHighwaySchedulerTest.java
package com.tradingbot.strategy.rsihighway.scheduler;

import com.tradingbot.strategy.rsihighway.config.RsiHighwayConfig;
import com.tradingbot.strategy.rsihighway.service.RsiHighwaySwingService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RsiHighwaySchedulerTest {

    private final RsiHighwaySwingService swingService = Mockito.mock(RsiHighwaySwingService.class);
    private final RsiHighwayConfig config = new RsiHighwayConfig();
    private final RsiHighwayScheduler scheduler = new RsiHighwayScheduler(swingService, config);

    @Test
    void testScheduledMethodsInvokeService() {
        scheduler.triggerEodScan();
        verify(swingService, times(1)).runEodScanAndEvaluation();

        scheduler.triggerMorningCheck();
        verify(swingService, times(1)).runMorningEmergencyCheck();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.rsihighway.scheduler.RsiHighwaySchedulerTest`
Expected: Compilation failure.

- [ ] **Step 3: Implement `RsiHighwayScheduler` and Telegram Alert integration**

Implement Spring `@Scheduled` methods with `@ConditionalOnProperty(name = "trading-bot.strategy.rsi-highway.enabled", havingValue = "true", matchIfMissing = true)`. Add formatted Telegram message templates for RSI Highway signals, pyramid triggers, and RSI < 50 exit notifications.

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.strategy.rsihighway.scheduler.RsiHighwaySchedulerTest`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/rsihighway/scheduler/ src/main/java/com/tradingbot/telegram/ src/test/java/com/tradingbot/strategy/rsihighway/scheduler/
git commit -m "feat(rsihighway): add 15:00 IST EOD and 09:30 IST morning schedulers and Telegram alerts"
```

---

### Task 8: REST Controller & Management Endpoints

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/rsihighway/controller/RsiHighwayController.java`
- Test: `src/test/java/com/tradingbot/strategy/rsihighway/controller/RsiHighwayControllerTest.java`

**Endpoints:**
- `POST /api/v1/rsi-highway/scan/eod` -> Triggers immediate manual EOD scan
- `POST /api/v1/rsi-highway/scan/morning` -> Triggers immediate manual morning check
- `GET /api/v1/rsi-highway/state` -> Returns current active state, positions, and qualified watchlist
- `GET /api/v1/rsi-highway/breadth` -> Returns latest market breadth snapshot
- `POST /api/v1/rsi-highway/positions/{symbol}/close` -> Manually closes a position

- [ ] **Step 1: Write failing controller mockMvc test**

```java
// src/test/java/com/tradingbot/strategy/rsihighway/controller/RsiHighwayControllerTest.java
package com.tradingbot.strategy.rsihighway.controller;

import com.tradingbot.strategy.rsihighway.model.MarketBreadthSnapshot;
import com.tradingbot.strategy.rsihighway.model.RsiHighwayState;
import com.tradingbot.strategy.rsihighway.service.RsiHighwaySwingService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RsiHighwayController.class)
class RsiHighwayControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RsiHighwaySwingService swingService;

    @Test
    void testGetStateEndpoint() throws Exception {
        RsiHighwayState state = new RsiHighwayState();
        when(swingService.getCurrentState()).thenReturn(state);

        mockMvc.perform(get("/api/v1/rsi-highway/state"))
                .andExpect(status().isOk());
    }

    @Test
    void testTriggerEodScanEndpoint() throws Exception {
        mockMvc.perform(post("/api/v1/rsi-highway/scan/eod"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.rsihighway.controller.RsiHighwayControllerTest`
Expected: Compilation failure.

- [ ] **Step 3: Implement `RsiHighwayController`**

Create Spring `@RestController` with all endpoints and proper error handling.

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.strategy.rsihighway.controller.RsiHighwayControllerTest`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/rsihighway/controller/ src/test/java/com/tradingbot/strategy/rsihighway/controller/
git commit -m "feat(rsihighway): add REST controller for RSI Highway strategy management"
```

---

### Task 9: End-to-End System Integration Test & Verification

**Files:**
- Create: `src/test/java/com/tradingbot/strategy/rsihighway/RsiHighwayIntegrationTest.java`

- [ ] **Step 1: Write complete End-to-End integration test**

Simulate:
1. Nifty 500 scanner with 52W High breadth check opening the highway.
2. Symbol (e.g. `BEL`) passing Monthly RSI > 60, Weekly RSI > 60, Daily RSI 50 bounce + Bullish Engulfing.
3. Signal generated -> Tranche 1 entered at trigger price.
4. Subsequent day -> Price rallies, Daily RSI pulls back to 52 and bounces -> Tranche 2 (Pyramid) filled.
5. Later day -> Price reverses, Daily RSI closes at 47.2 (< 50.0) -> Entire position (Tranches 1 & 2) cleanly exited.
6. State persistence verified on disk.

- [ ] **Step 2: Run integration test and verify it passes**

Run: `./gradlew test --tests com.tradingbot.strategy.rsihighway.RsiHighwayIntegrationTest`
Expected: BUILD SUCCESSFUL (100% passing).

- [ ] **Step 3: Run full project test suite**

Run: `./gradlew test`
Expected: All strategy tests and existing bot tests pass cleanly.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/tradingbot/strategy/rsihighway/RsiHighwayIntegrationTest.java
git commit -m "test(rsihighway): add end-to-end integration test for full RSI Highway lifecycle"
```

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-17-rsi-highway-swing-strategy.md`. Two execution options:

1. **Subagent-Driven (recommended)** - Fresh subagent per task, review between tasks, fast iteration
2. **Inline Execution** - Execute tasks in this session using `executing-plans`, batch execution with checkpoints

Which approach would you like to proceed with?
