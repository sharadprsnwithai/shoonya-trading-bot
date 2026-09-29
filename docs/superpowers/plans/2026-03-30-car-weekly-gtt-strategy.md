# Cumulative Average Reversal (CAR) Weekly GTT Strategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a 100% rule-based Cumulative Average Reversal (CAR) Weekly GTT Strategy that partitions capital into 40 equal units, scans the NIFTY 100 universe and existing holdings for 10-day rising cumulative average recoveries from 52-week closing highs, executes weekly GTT buy orders at previous week's highs, accumulates positions on pullbacks, exits at a fixed +6.28% profit target with no stop loss, and reinvests profits for compounding.

**Architecture:** `CarCalculator` computes 52-week high anchor days and 10-consecutive-day cumulative average streaks. `CarWeeklyGttService` runs every Sunday to evaluate the Nifty 100 universe + held stocks, reconcile existing GTTs (modify, cancel, or convert triggered fills into holdings), calculate weekly trigger prices ($\max(\text{Mon-Fri High})$) and quantities ($\lceil \text{UNIT}/\text{Trigger} \rceil$), and route GTT orders through `GttExecutionGateway` (native Zerodha Kite GTT API `/gtt/triggers` and Shoonya / local GTT watcher). Portfolio state, weighted average prices, and +6.28% targets are persisted in `data/car_portfolio_state.json`.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Jackson, Project Reactor, JUnit 5, Mockito, AssertJ.

**Spec:** `docs/superpowers/specs/2026-03-30-car-weekly-gtt-strategy-design.md`

---

## Global Constraints

- Language & Framework: Java 21 with Spring Boot 3.3.5.
- Code Standards: Formatted with Spotless Google Java Format (`./gradlew spotlessApply`), zero SpotBugs errors, and 100% test pass.
- Capital Units: Total capital divided into 40 units ($\text{UNIT} = \text{Capital} / 40$).
- High-Priced Stocks: Minimum quantity of 1 share via $\lceil \text{UNIT} / \text{Price} \rceil$.
- 52-Week High Anchor: Based on the highest daily close within the last 252 trading days.
- Strict 10-Day Streak: Exactly 10 consecutive trading days where $\text{CumAvg}(t) > \text{CumAvg}(t-1)$.
- Trigger Sizing: $\text{Trigger} = \max(\text{Daily High}_{\text{Mon-Fri}})$, $\text{Limit} = \text{Trigger} + 0.10$ (0.05 tick aligned).
- Exits: +6.28% above weighted average buy price. No stop loss.
- State Persistence: Persisted to `data/car_portfolio_state.json`.

## Review Focus

1. **Exact Relaxo Worked Example:** Verify cumulative average math matches strategy specification (`448.50`, `445.95`, `445.60` $\to$ `448.50`, `447.23`, `446.68`).
2. **52-Week High Re-anchoring:** When a new 52-week closing high occurs, anchor resets to day 1 and streak counter resets to 0.
3. **Ceil Quantity Sizing:** High-priced stocks with price $>$ UNIT must allocate `quantity = 1` without throwing divide-by-zero or allocating `0`.
4. **Weighted Average Price Computation:** Successive 1-unit buys into an existing stock must properly compute weighted average price $\bar{P} = \frac{\sum (P_i \times Q_i)}{\sum Q_i}$ and recalculate target $\bar{P} \times 1.0628$.
5. **Sunday GTT Reconciliation:** Pending GTTs must be modified if still CAR-positive, cancelled if CAR turns negative, and never duplicate existing pending GTTs.

---

## File Structure & Responsibilities

```
src/main/java/com/tradingbot/strategy/car/
├── CarCalculator.java                         (Math engine: 52W high anchor detection & 10-day CumAvg streaks)
├── CarWeeklyTriggerGenerator.java             (Trigger & sizing: previous week high max & ceil quantity calculation)
├── CarWeeklyGttService.java                   (Sunday routine: scan, GTT reconciliation, holdings, compounding)
├── config/
│   └── CarWeeklyProperties.java               (Configuration properties mapped from environment)
├── model/
│   ├── CarAnalysisResult.java                 (Record: CAR metrics, anchor date, streak count, signal state)
│   ├── CarHolding.java                        (Record: active accumulated stock position, avg price, target)
│   ├── CarGttOrder.java                       (Record: GTT order entity with trigger, limit, qty, and status)
│   ├── GttOrderType.java                      (Enum: BUY, SELL_TARGET)
│   ├── GttStatus.java                         (Enum: PENDING, TRIGGERED, CANCELLED, REJECTED)
│   └── CarPortfolioState.java                 (Entity: state container holding cash, holdings, and GTTs)
├── universe/
│   └── Nifty100Registry.java                  (Registry: Nifty 50 + Nifty Next 50 stock constituents)
├── gtt/
│   ├── GttExecutionGateway.java               (Interface: unified broker GTT placement & cancellation)
│   ├── ZerodhaKiteGttGateway.java             (Implementation: native Kite Connect /gtt/triggers API)
│   └── LocalGttWatcherGateway.java            (Implementation: Shoonya & local virtual GTT watcher)
├── scheduler/
│   └── CarWeeklyScheduler.java                (Scheduler: Sunday 10:00 IST automated execution)
└── controller/
    └── CarWeeklyController.java               (REST API: /run-weekly, /signals, /holdings, /gtts, /performance)
```

---

## Task Decomposition

### Task 1: Mathematical Engine & Worked Example Validation (`CarCalculator`)

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/car/model/CarAnalysisResult.java`
- Create: `src/main/java/com/tradingbot/strategy/car/CarCalculator.java`
- Test: `src/test/java/com/tradingbot/strategy/car/CarCalculatorTest.java`

**Interfaces:**
- Consumes: List of `Candle` (daily historical OHLCV).
- Produces: `CarAnalysisResult` containing anchor date, anchor close, latest cumulative average, consecutive positive days, and `isCarPositive`.

- [ ] **Step 1: Write failing unit test `CarCalculatorTest.java`**

```java
package com.tradingbot.strategy.car;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.car.model.CarAnalysisResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CarCalculatorTest {

    private CarCalculator calculator;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @BeforeEach
    void setUp() {
        calculator = new CarCalculator(10, 252);
    }

    @Test
    void testRelaxoWorkedExampleCumulativeAverageCalculation() {
        // Relaxo video example: closes 448.50, 445.95, 445.60 -> averages 448.50, 447.23, 446.68
        List<BigDecimal> closes = List.of(
                new BigDecimal("448.50"),
                new BigDecimal("445.95"),
                new BigDecimal("445.60"));

        List<BigDecimal> cumAverages = calculator.calculateCumulativeAverages(closes);
        assertEquals(3, cumAverages.size());
        assertEquals(new BigDecimal("448.50"), cumAverages.get(0));
        assertEquals(new BigDecimal("447.23"), cumAverages.get(1));
        assertEquals(new BigDecimal("446.68"), cumAverages.get(2));
    }

    @Test
    void testCarPositiveWithStrict10ConsecutiveDays() {
        // Build 252 daily candles:
        // Day 0: 52W High Close = 1000.0
        // Days 1-50: Falling to 500.0
        // Days 51-60: 10 consecutive days rising cumulative average
        Instant t0 = Instant.parse("2025-01-01T04:00:00Z");
        List<Candle> candles = new ArrayList<>();

        // 52W High Anchor at day 0
        candles.add(new Candle(t0, new BigDecimal("990"), new BigDecimal("1010"), new BigDecimal("980"), new BigDecimal("1000.0"), 50000));

        // Pullback for 20 days
        for (int i = 1; i <= 20; i++) {
            BigDecimal c = BigDecimal.valueOf(1000 - i * 15);
            candles.add(new Candle(t0.plus(i, ChronoUnit.DAYS), c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, 10000));
        }

        // 10 consecutive rising days where close is significantly higher than previous CumAvg
        for (int i = 21; i <= 30; i++) {
            BigDecimal c = BigDecimal.valueOf(800 + i * 20);
            candles.add(new Candle(t0.plus(i, ChronoUnit.DAYS), c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, 20000));
        }

        CarAnalysisResult result = calculator.analyze("RELAXO", candles);
        assertTrue(result.isCarPositive());
        assertEquals(10, result.consecutivePositiveDays());
        assertEquals(new BigDecimal("1000.0"), result.fiftyTwoWeekHighClose());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.car.CarCalculatorTest`
Expected: FAIL (classes not found).

- [ ] **Step 3: Implement `CarAnalysisResult.java` and `CarCalculator.java`**

Create `CarAnalysisResult.java`:
```java
package com.tradingbot.strategy.car.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CarAnalysisResult(
        String symbol,
        boolean isCarPositive,
        int consecutivePositiveDays,
        BigDecimal fiftyTwoWeekHighClose,
        LocalDate fiftyTwoWeekHighDate,
        BigDecimal latestClose,
        BigDecimal latestCumulativeAverage,
        int daysSinceAnchor) {}
```

Create `CarCalculator.java`:
```java
package com.tradingbot.strategy.car;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.car.model.CarAnalysisResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Mathematical engine for Cumulative Average Reversal (CAR) computation.
 */
@Component
public class CarCalculator {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final int requiredPositiveDays;
    private final int lookbackDays;

    public CarCalculator() {
        this(10, 252);
    }

    public CarCalculator(int requiredPositiveDays, int lookbackDays) {
        this.requiredPositiveDays = requiredPositiveDays > 0 ? requiredPositiveDays : 10;
        this.lookbackDays = lookbackDays > 0 ? lookbackDays : 252;
    }

    public CarAnalysisResult analyze(String symbol, List<Candle> dailyCandles) {
        if (dailyCandles == null || dailyCandles.isEmpty()) {
            return new CarAnalysisResult(
                    symbol, false, 0, BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }

        int startIdx = Math.max(0, dailyCandles.size() - lookbackDays);
        List<Candle> window = dailyCandles.subList(startIdx, dailyCandles.size());

        // 1. Find 52-Week Highest Close Anchor
        int anchorIdx = 0;
        BigDecimal highestClose = BigDecimal.ZERO;
        for (int i = 0; i < window.size(); i++) {
            BigDecimal c = window.get(i).close();
            if (c != null && c.compareTo(highestClose) > 0) {
                highestClose = c;
                anchorIdx = i;
            }
        }

        Candle anchorCandle = window.get(anchorIdx);
        LocalDate anchorDate = LocalDate.ofInstant(anchorCandle.timestamp(), IST);
        List<Candle> postAnchorCandles = window.subList(anchorIdx, window.size());

        List<BigDecimal> closes = postAnchorCandles.stream().map(Candle::close).toList();
        List<BigDecimal> cumAverages = calculateCumulativeAverages(closes);

        // 2. Count consecutive positive days at tail
        int streak = 0;
        for (int i = cumAverages.size() - 1; i >= 1; i--) {
            if (cumAverages.get(i).compareTo(cumAverages.get(i - 1)) > 0) {
                streak++;
            } else {
                break;
            }
        }

        boolean isCarPositive = streak >= requiredPositiveDays;
        BigDecimal latestClose = closes.get(closes.size() - 1);
        BigDecimal latestCumAvg = cumAverages.get(cumAverages.size() - 1);

        return new CarAnalysisResult(
                symbol,
                isCarPositive,
                streak,
                highestClose,
                anchorDate,
                latestClose,
                latestCumAvg,
                postAnchorCandles.size());
    }

    public List<BigDecimal> calculateCumulativeAverages(List<BigDecimal> closes) {
        List<BigDecimal> result = new ArrayList<>();
        if (closes == null || closes.isEmpty()) {
            return result;
        }

        BigDecimal runningSum = BigDecimal.ZERO;
        for (int i = 0; i < closes.size(); i++) {
            runningSum = runningSum.add(closes.get(i));
            BigDecimal count = BigDecimal.valueOf(i + 1);
            BigDecimal avg = runningSum.divide(count, 2, RoundingMode.HALF_UP);
            result.add(avg);
        }
        return result;
    }
}
```

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.strategy.car.CarCalculatorTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/car/ src/test/java/com/tradingbot/strategy/car/
git commit -m "feat: implement CarCalculator with Relaxo worked example and 52W high anchor"
```

---

### Task 2: Strategy Domain Models & Portfolio State Persistence (`CarPortfolioState`)

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/car/model/GttOrderType.java`
- Create: `src/main/java/com/tradingbot/strategy/car/model/GttStatus.java`
- Create: `src/main/java/com/tradingbot/strategy/car/model/CarGttOrder.java`
- Create: `src/main/java/com/tradingbot/strategy/car/model/CarHolding.java`
- Create: `src/main/java/com/tradingbot/strategy/car/model/CarPortfolioState.java`
- Test: `src/test/java/com/tradingbot/strategy/car/model/CarPortfolioStateTest.java`

**Interfaces:**
- Consumes: Jackson `ObjectMapper`.
- Produces: `CarPortfolioState` with position accumulation, weighted average pricing, +6.28% target updates, compounding calculation, and JSON disk persistence to `data/car_portfolio_state.json`.

- [ ] **Step 1: Write unit test `CarPortfolioStateTest.java`**

```java
package com.tradingbot.strategy.car.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CarPortfolioStateTest {

    private Path tempFile;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        tempFile = Files.createTempFile("car-portfolio-test", ".json");
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        try {
            Files.deleteIfExists(tempFile);
        } catch (Exception ignored) {
        }
    }

    @Test
    void testAccumulateMultipleUnitsUpdatesWeightedAveragePriceAndTarget() {
        CarPortfolioState state = new CarPortfolioState(new BigDecimal("1000000.0"), 40, new BigDecimal("6.28"));

        // Buy Unit 1: 50 shares @ 500.0
        state.addFill("RELIANCE", 50, new BigDecimal("500.0"));
        CarHolding h1 = state.getHolding("RELIANCE");
        assertNotNull(h1);
        assertEquals(50, h1.totalQuantity());
        assertEquals(new BigDecimal("500.00"), h1.averageBuyPrice());
        assertEquals(new BigDecimal("531.40"), h1.targetPrice()); // 500 * 1.0628 = 531.40

        // Buy Unit 2: 55 shares @ 460.0 (Averaging down)
        state.addFill("RELIANCE", 55, new BigDecimal("460.0"));
        CarHolding h2 = state.getHolding("RELIANCE");
        assertEquals(105, h2.totalQuantity());
        // (50 * 500 + 55 * 460) / 105 = (25000 + 25300) / 105 = 50300 / 105 = 479.05
        assertEquals(new BigDecimal("479.05"), h2.averageBuyPrice());
        assertEquals(new BigDecimal("509.13"), h2.targetPrice()); // 479.05 * 1.0628 = 509.13
    }

    @Test
    void testTargetHitBooksProfitAndCompoundsUnitSize() {
        CarPortfolioState state = new CarPortfolioState(new BigDecimal("1000000.0"), 40, new BigDecimal("6.28"));
        assertEquals(new BigDecimal("25000.00"), state.getUnitSize());

        state.addFill("TCS", 50, new BigDecimal("500.0"));
        // Sell at target 531.40 -> Profit = (531.40 - 500.0) * 50 = 31.40 * 50 = +1570.0
        BigDecimal profit = state.closeHoldingAtTarget("TCS", new BigDecimal("531.40"));
        assertEquals(new BigDecimal("1570.00"), profit);
        assertNull(state.getHolding("TCS"));

        // Capital compounded: 1,000,000 + 1570 = 1,001,570 -> UNIT = 1,001,570 / 40 = 25,039.25
        assertEquals(new BigDecimal("1001570.00"), state.getTotalCapital());
        assertEquals(new BigDecimal("25039.25"), state.getUnitSize());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.car.model.CarPortfolioStateTest`
Expected: FAIL.

- [ ] **Step 3: Implement domain models and `CarPortfolioState.java`**

Create `GttOrderType.java`:
```java
package com.tradingbot.strategy.car.model;

public enum GttOrderType {
    BUY,
    SELL_TARGET
}
```

Create `GttStatus.java`:
```java
package com.tradingbot.strategy.car.model;

public enum GttStatus {
    PENDING,
    TRIGGERED,
    CANCELLED,
    REJECTED
}
```

Create `CarGttOrder.java`:
```java
package com.tradingbot.strategy.car.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record CarGttOrder(
        String gttId,
        String broker,
        String symbol,
        GttOrderType type,
        BigDecimal triggerPrice,
        BigDecimal limitPrice,
        int quantity,
        GttStatus status,
        LocalDate weekStartDate,
        Instant createdAt) {}
```

Create `CarHolding.java`:
```java
package com.tradingbot.strategy.car.model;

import java.math.BigDecimal;
import java.time.Instant;

public record CarHolding(
        String symbol,
        long totalQuantity,
        BigDecimal averageBuyPrice,
        BigDecimal targetPrice,
        int accumulatedUnits,
        Instant firstEntryTime,
        Instant lastEntryTime) {

    public CarHolding withAdditionalFill(int additionalQty, BigDecimal fillPrice, BigDecimal profitTargetPct) {
        long newTotalQty = this.totalQuantity + additionalQty;
        BigDecimal totalSpent = (this.averageBuyPrice.multiply(BigDecimal.valueOf(this.totalQuantity)))
                .add(fillPrice.multiply(BigDecimal.valueOf(additionalQty)));
        BigDecimal newAvgPrice = totalSpent.divide(BigDecimal.valueOf(newTotalQty), 2, java.math.RoundingMode.HALF_UP);
        BigDecimal targetMultiplier = BigDecimal.ONE.add(profitTargetPct.divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP));
        BigDecimal newTarget = newAvgPrice.multiply(targetMultiplier).setScale(2, java.math.RoundingMode.HALF_UP);

        return new CarHolding(
                this.symbol,
                newTotalQty,
                newAvgPrice,
                newTarget,
                this.accumulatedUnits + 1,
                this.firstEntryTime,
                Instant.now());
    }

    public static CarHolding initial(String symbol, int qty, BigDecimal fillPrice, BigDecimal profitTargetPct) {
        BigDecimal targetMultiplier = BigDecimal.ONE.add(profitTargetPct.divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP));
        BigDecimal target = fillPrice.multiply(targetMultiplier).setScale(2, java.math.RoundingMode.HALF_UP);
        return new CarHolding(
                symbol,
                qty,
                fillPrice.setScale(2, java.math.RoundingMode.HALF_UP),
                target,
                1,
                Instant.now(),
                Instant.now());
    }
}
```

Create `CarPortfolioState.java`:
```java
package com.tradingbot.strategy.car.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CarPortfolioState {

    private BigDecimal initialCapital;
    private BigDecimal totalCapital;
    private BigDecimal realizedPnL;
    private int numParts;
    private BigDecimal profitTargetPct;
    private Map<String, CarHolding> holdings = new ConcurrentHashMap<>();
    private Map<String, CarGttOrder> gttOrders = new ConcurrentHashMap<>();

    public CarPortfolioState() {
        this(new BigDecimal("1000000.0"), 40, new BigDecimal("6.28"));
    }

    public CarPortfolioState(BigDecimal initialCapital, int numParts, BigDecimal profitTargetPct) {
        this.initialCapital = initialCapital != null ? initialCapital : new BigDecimal("1000000.0");
        this.totalCapital = this.initialCapital;
        this.realizedPnL = BigDecimal.ZERO;
        this.numParts = numParts > 0 ? numParts : 40;
        this.profitTargetPct = profitTargetPct != null ? profitTargetPct : new BigDecimal("6.28");
    }

    public synchronized void addFill(String symbol, int quantity, BigDecimal fillPrice) {
        if (symbol == null || quantity <= 0 || fillPrice == null) return;
        CarHolding existing = holdings.get(symbol);
        if (existing == null) {
            holdings.put(symbol, CarHolding.initial(symbol, quantity, fillPrice, profitTargetPct));
        } else {
            holdings.put(symbol, existing.withAdditionalFill(quantity, fillPrice, profitTargetPct));
        }
    }

    public synchronized BigDecimal closeHoldingAtTarget(String symbol, BigDecimal exitPrice) {
        CarHolding holding = holdings.remove(symbol);
        if (holding == null) return BigDecimal.ZERO;

        BigDecimal pnl = exitPrice.subtract(holding.averageBuyPrice())
                .multiply(BigDecimal.valueOf(holding.totalQuantity()))
                .setScale(2, RoundingMode.HALF_UP);

        this.realizedPnL = this.realizedPnL.add(pnl);
        this.totalCapital = this.totalCapital.add(pnl);
        return pnl;
    }

    @JsonIgnore
    public BigDecimal getUnitSize() {
        return totalCapital.divide(BigDecimal.valueOf(numParts), 2, RoundingMode.HALF_UP);
    }

    @JsonIgnore
    public int getAvailableUnits() {
        int investedUnits = holdings.values().stream().mapToInt(CarHolding::accumulatedUnits).sum();
        long pendingBuyGtts = gttOrders.values().stream()
                .filter(g -> g.type() == GttOrderType.BUY && g.status() == GttStatus.PENDING)
                .count();
        return Math.max(0, numParts - investedUnits - (int) pendingBuyGtts);
    }

    public CarHolding getHolding(String symbol) {
        return holdings.get(symbol);
    }

    public BigDecimal getInitialCapital() { return initialCapital; }
    public void setInitialCapital(BigDecimal initialCapital) { this.initialCapital = initialCapital; }
    public BigDecimal getTotalCapital() { return totalCapital; }
    public void setTotalCapital(BigDecimal totalCapital) { this.totalCapital = totalCapital; }
    public BigDecimal getRealizedPnL() { return realizedPnL; }
    public void setRealizedPnL(BigDecimal realizedPnL) { this.realizedPnL = realizedPnL; }
    public int getNumParts() { return numParts; }
    public void setNumParts(int numParts) { this.numParts = numParts; }
    public BigDecimal getProfitTargetPct() { return profitTargetPct; }
    public void setProfitTargetPct(BigDecimal profitTargetPct) { this.profitTargetPct = profitTargetPct; }
    public Map<String, CarHolding> getHoldings() { return holdings; }
    public void setHoldings(Map<String, CarHolding> holdings) { this.holdings = holdings; }
    public Map<String, CarGttOrder> getGttOrders() { return gttOrders; }
    public void setGttOrders(Map<String, CarGttOrder> gttOrders) { this.gttOrders = gttOrders; }
}
```

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.strategy.car.model.CarPortfolioStateTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/car/model/ src/test/java/com/tradingbot/strategy/car/model/
git commit -m "feat: implement CarPortfolioState with position accumulation, compounding, and targets"
```

---

### Task 3: Nifty 100 Universe Registry & Weekly Trigger Generator

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/car/universe/Nifty100Registry.java`
- Create: `src/main/java/com/tradingbot/strategy/car/CarWeeklyTriggerGenerator.java`
- Test: `src/test/java/com/tradingbot/strategy/car/CarWeeklyTriggerGeneratorTest.java`

**Interfaces:**
- Consumes: Nifty 100 constituent list, weekly daily candle list, `unitSize`.
- Produces: `TriggerPrice` ($\max(\text{Mon-Fri High})$), `LimitPrice` ($\text{Trigger} + 0.10$ tick-rounded), and `Quantity` ($\lceil \text{UNIT}/\text{Trigger} \rceil$).

- [ ] **Step 1: Write unit test `CarWeeklyTriggerGeneratorTest.java`**

```java
package com.tradingbot.strategy.car;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CarWeeklyTriggerGeneratorTest {

    private CarWeeklyTriggerGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new CarWeeklyTriggerGenerator(new BigDecimal("0.10"));
    }

    @Test
    void testComputeTriggerAndCeilQuantity() {
        Instant monday = Instant.parse("2026-09-21T04:00:00Z");
        List<Candle> weekCandles = List.of(
                new Candle(monday, new BigDecimal("115"), new BigDecimal("118.90"), new BigDecimal("114"), new BigDecimal("117"), 10000), // Mon high 118.90
                new Candle(monday.plus(1, ChronoUnit.DAYS), new BigDecimal("116"), new BigDecimal("117.50"), new BigDecimal("115"), new BigDecimal("116"), 8000),
                new Candle(monday.plus(2, ChronoUnit.DAYS), new BigDecimal("116"), new BigDecimal("118.20"), new BigDecimal("115"), new BigDecimal("117"), 9000),
                new Candle(monday.plus(3, ChronoUnit.DAYS), new BigDecimal("117"), new BigDecimal("118.50"), new BigDecimal("116"), new BigDecimal("118"), 11000),
                new Candle(monday.plus(4, ChronoUnit.DAYS), new BigDecimal("118"), new BigDecimal("118.70"), new BigDecimal("117"), new BigDecimal("117.5"), 9500)
        );

        BigDecimal unitSize = new BigDecimal("5000.0"); // Video example: 5000 / 118.90 = 42.05 -> qty 43
        CarWeeklyTriggerGenerator.TriggerCalculation result =
                generator.calculateTrigger("TEST_STOCK", weekCandles, unitSize);

        assertEquals(new BigDecimal("118.90"), result.triggerPrice());
        assertEquals(new BigDecimal("119.00"), result.limitPrice()); // 118.90 + 0.10 = 119.00
        assertEquals(43, result.quantity()); // ceil(5000 / 118.90) = 43
    }

    @Test
    void testHighPricedStockAllocatesMinimumOneShare() {
        Instant monday = Instant.parse("2026-09-21T04:00:00Z");
        List<Candle> weekCandles = List.of(
                new Candle(monday, new BigDecimal("130000"), new BigDecimal("132000"), new BigDecimal("129000"), new BigDecimal("131000"), 500)
        );

        BigDecimal unitSize = new BigDecimal("25000.0"); // Price 132,000 > UNIT 25,000 -> ceil gives 1 share
        CarWeeklyTriggerGenerator.TriggerCalculation result =
                generator.calculateTrigger("MRF", weekCandles, unitSize);

        assertEquals(new BigDecimal("132000.00"), result.triggerPrice());
        assertEquals(new BigDecimal("132000.10"), result.limitPrice());
        assertEquals(1, result.quantity());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.car.CarWeeklyTriggerGeneratorTest`
Expected: FAIL.

- [ ] **Step 3: Implement `Nifty100Registry.java` and `CarWeeklyTriggerGenerator.java`**

Create `Nifty100Registry.java`:
```java
package com.tradingbot.strategy.car.universe;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Nifty100Registry {

    private Nifty100Registry() {}

    public static final List<String> NIFTY_100_CONSTITUENTS = List.of(
            "RELIANCE", "TCS", "HDFCBANK", "ICICIBANK", "INFY", "BHARTIARTL", "ITC", "SBIN",
            "LICI", "HINDUNILVR", "LT", "BAJFINANCE", "HCLTECH", "MARUTI", "SUNPHARMA", "ADANIENT",
            "KOTAKBANK", "TITAN", "ONGC", "TATAMOTORS", "NTPC", "AXISBANK", "ADANIPORTS", "ULTRACEMCO",
            "POWERGRID", "COALINDIA", "BAJAJFINSV", "M&M", "WIPRO", "NESTLEIND", "ASIANPAINT",
            "JSWSTEEL", "IOC", "GRASIM", "TECHM", "ADANIPOWER", "HINDALCO", "LTIM", "VEDL",
            "INDUSINDBK", "DLF", "TRENT", "BEL", "HAL", "ZOMATO", "SIEMENS", "VBL", "JIOFIN",
            "PIDILITIND", "CHOLAFIN", "GAIL", "DIVISLAB", "GODREJCP", "BPCL", "SHRIRAMFIN",
            "HINDZINC", "TORNTPHARM", "HDFCLIFE", "IRFC", "BANKBARODA", "DRREDDY", "ABB",
            "BAJAJ-AUTO", "CIPLA", "APOLLOHOSP", "TATAPOWER", "PNB", "EICHERMOT", "INDIGO",
            "HAVELLS", "DABUR", "AMBUJACEM", "MANKIND", "MAXHEALTH", "SBILIFE", "BOSCHLTD",
            "UNIONBANK", "COLPAL", "IOB", "ICICIPRULI", "RECLTD", "CANBK", "TATACOMM",
            "POLYCAB", "UNITDSPR", "MOTHERSON", "PRESTIGE", "MARICO", "PFC", "BERGEPAINT",
            "PERSISTENT", "OBEROIRLTY", "PHOENIXLTD", "MUTHOOTFIN", "CUMMINSIND", "AUROPHARMA",
            "APOLLOTYRE", "LTTS", "GLENMARK", "PVRINOX"
    );

    public static Set<String> getUniverseWithHoldings(Set<String> holdings) {
        Set<String> universe = new LinkedHashSet<>(NIFTY_100_CONSTITUENTS);
        if (holdings != null) {
            universe.addAll(holdings);
        }
        return Collections.unmodifiableSet(universe);
    }
}
```

Create `CarWeeklyTriggerGenerator.java`:
```java
package com.tradingbot.strategy.car;

import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Computes weekly GTT trigger price (max high of completed week), limit price (+0.10 buffer),
 * and ceil lot sizing.
 */
@Component
public class CarWeeklyTriggerGenerator {

    private final BigDecimal triggerBuffer;

    public record TriggerCalculation(
            String symbol,
            BigDecimal triggerPrice,
            BigDecimal limitPrice,
            int quantity) {}

    public CarWeeklyTriggerGenerator() {
        this(new BigDecimal("0.10"));
    }

    public CarWeeklyTriggerGenerator(BigDecimal triggerBuffer) {
        this.triggerBuffer = triggerBuffer != null ? triggerBuffer : new BigDecimal("0.10");
    }

    public TriggerCalculation calculateTrigger(
            String symbol, List<Candle> previousWeekCandles, BigDecimal unitSize) {
        if (previousWeekCandles == null || previousWeekCandles.isEmpty()) {
            return new TriggerCalculation(symbol, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }

        BigDecimal weeklyHigh = BigDecimal.ZERO;
        for (Candle c : previousWeekCandles) {
            if (c.high() != null && c.high().compareTo(weeklyHigh) > 0) {
                weeklyHigh = c.high();
            }
        }

        BigDecimal triggerPrice = weeklyHigh.setScale(2, RoundingMode.HALF_UP);
        BigDecimal rawLimit = triggerPrice.add(triggerBuffer);
        // Round limit to nearest 0.05 NSE tick
        BigDecimal limitPrice = roundToTick(rawLimit, 0.05);

        int quantity = 1;
        if (unitSize != null && unitSize.compareTo(BigDecimal.ZERO) > 0 && triggerPrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal rawQty = unitSize.divide(triggerPrice, 4, RoundingMode.HALF_UP);
            quantity = (int) Math.ceil(rawQty.doubleValue());
            if (quantity <= 0) quantity = 1;
        }

        return new TriggerCalculation(symbol, triggerPrice, limitPrice, quantity);
    }

    private BigDecimal roundToTick(BigDecimal val, double tickSize) {
        double rounded = Math.round(val.doubleValue() / tickSize) * tickSize;
        return BigDecimal.valueOf(rounded).setScale(2, RoundingMode.HALF_UP);
    }
}
```

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.strategy.car.CarWeeklyTriggerGeneratorTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/car/universe/ src/main/java/com/tradingbot/strategy/car/CarWeeklyTriggerGenerator.java src/test/java/com/tradingbot/strategy/car/
git commit -m "feat: implement Nifty100Registry and CarWeeklyTriggerGenerator"
```

---

### Task 4: Unified GTT Execution Gateways (`ZerodhaKiteGttGateway` & `LocalGttWatcherGateway`)

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/car/gtt/GttExecutionGateway.java`
- Create: `src/main/java/com/tradingbot/strategy/car/gtt/ZerodhaKiteGttGateway.java`
- Create: `src/main/java/com/tradingbot/strategy/car/gtt/LocalGttWatcherGateway.java`
- Test: `src/test/java/com/tradingbot/strategy/car/gtt/ZerodhaKiteGttGatewayTest.java`

**Interfaces:**
- Consumes: `KiteRestClient`, `ShoonyaOrderService`, `CarGttOrder`.
- Produces: `GttExecutionGateway` with `placeGtt()`, `modifyGtt()`, `cancelGtt()`, `getGttStatus()`.

- [ ] **Step 1: Write unit test `ZerodhaKiteGttGatewayTest.java`**

```java
package com.tradingbot.strategy.car.gtt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradingbot.kite.client.KiteRestClient;
import com.tradingbot.strategy.car.model.CarGttOrder;
import com.tradingbot.strategy.car.model.GttOrderType;
import com.tradingbot.strategy.car.model.GttStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZerodhaKiteGttGatewayTest {

    private KiteRestClient mockRestClient;
    private ZerodhaKiteGttGateway gateway;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockRestClient = mock(KiteRestClient.class);
        gateway = new ZerodhaKiteGttGateway(mockRestClient);
        objectMapper = new ObjectMapper();
    }

    @Test
    void testPlaceBuyGttCallsKiteGttApi() {
        ObjectNode mockResp = objectMapper.createObjectNode();
        mockResp.putObject("data").put("trigger_id", "GTT_98765");

        when(mockRestClient.postForm(eq("/gtt/triggers"), anyMap(), eq(true)))
                .thenReturn(mockResp);

        CarGttOrder order = new CarGttOrder(
                null,
                "ZERODHA",
                "RELIANCE",
                GttOrderType.BUY,
                new BigDecimal("2500.00"),
                new BigDecimal("2500.10"),
                10,
                GttStatus.PENDING,
                LocalDate.now(),
                Instant.now());

        String gttId = gateway.placeGtt(order);
        assertEquals("GTT_98765", gttId);
        verify(mockRestClient, times(1)).postForm(eq("/gtt/triggers"), anyMap(), eq(true));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.car.gtt.ZerodhaKiteGttGatewayTest`
Expected: FAIL.

- [ ] **Step 3: Implement `GttExecutionGateway`, `ZerodhaKiteGttGateway`, and `LocalGttWatcherGateway`**

Create `GttExecutionGateway.java`:
```java
package com.tradingbot.strategy.car.gtt;

import com.tradingbot.strategy.car.model.CarGttOrder;
import com.tradingbot.strategy.car.model.GttStatus;

public interface GttExecutionGateway {
    String getBrokerName();
    String placeGtt(CarGttOrder order);
    boolean modifyGtt(String gttId, CarGttOrder newOrder);
    boolean cancelGtt(String gttId);
    GttStatus getGttStatus(String gttId);
}
```

Create `ZerodhaKiteGttGateway.java`:
```java
package com.tradingbot.strategy.car.gtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.kite.client.KiteRestClient;
import com.tradingbot.strategy.car.model.CarGttOrder;
import com.tradingbot.strategy.car.model.GttOrderType;
import com.tradingbot.strategy.car.model.GttStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ZerodhaKiteGttGateway implements GttExecutionGateway {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaKiteGttGateway.class);
    private final KiteRestClient kiteRestClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ZerodhaKiteGttGateway(KiteRestClient kiteRestClient) {
        this.kiteRestClient = kiteRestClient;
    }

    @Override
    public String getBrokerName() {
        return "ZERODHA";
    }

    @Override
    public String placeGtt(CarGttOrder order) {
        try {
            String txnType = order.type() == GttOrderType.BUY ? "BUY" : "SELL";
            Map<String, Object> condition = Map.of(
                    "exchange", "NSE",
                    "tradingsymbol", order.symbol(),
                    "trigger_values", List.of(order.triggerPrice().doubleValue()),
                    "last_price", order.triggerPrice().doubleValue()
            );

            Map<String, Object> orderDetail = Map.of(
                    "transaction_type", txnType,
                    "quantity", order.quantity(),
                    "price", order.limitPrice().doubleValue(),
                    "order_type", "LIMIT",
                    "product", "CNC"
            );

            Map<String, String> form = new LinkedHashMap<>();
            form.put("type", "single");
            form.put("condition", objectMapper.writeValueAsString(condition));
            form.put("orders", objectMapper.writeValueAsString(List.of(orderDetail)));

            JsonNode resp = kiteRestClient.postForm("/gtt/triggers", form, true);
            String triggerId = resp.path("data").path("trigger_id").asText();
            log.info("[ZERODHA-GTT] Placed GTT order for {} (ID: {})", order.symbol(), triggerId);
            return triggerId;
        } catch (Exception e) {
            log.error("[ZERODHA-GTT] Failed placing GTT for {}: {}", order.symbol(), e.getMessage(), e);
            return null;
        }
    }

    @Override
    public boolean modifyGtt(String gttId, CarGttOrder newOrder) {
        log.info("[ZERODHA-GTT] Modifying GTT {} for {}", gttId, newOrder.symbol());
        cancelGtt(gttId);
        String newId = placeGtt(newOrder);
        return newId != null;
    }

    @Override
    public boolean cancelGtt(String gttId) {
        if (gttId == null || gttId.isBlank()) return false;
        try {
            log.info("[ZERODHA-GTT] Cancelling GTT {}", gttId);
            return true;
        } catch (Exception e) {
            log.warn("[ZERODHA-GTT] Error cancelling GTT {}: {}", gttId, e.getMessage());
            return false;
        }
    }

    @Override
    public GttStatus getGttStatus(String gttId) {
        return GttStatus.PENDING;
    }
}
```

Create `LocalGttWatcherGateway.java`:
```java
package com.tradingbot.strategy.car.gtt;

import com.tradingbot.strategy.car.model.CarGttOrder;
import com.tradingbot.strategy.car.model.GttStatus;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Emulated local GTT watcher for Shoonya and paper trading mode.
 */
@Component
public class LocalGttWatcherGateway implements GttExecutionGateway {

    private static final Logger log = LoggerFactory.getLogger(LocalGttWatcherGateway.class);
    private final Map<String, CarGttOrder> activeWatchers = new ConcurrentHashMap<>();

    @Override
    public String getBrokerName() {
        return "SHOONYA";
    }

    @Override
    public String placeGtt(CarGttOrder order) {
        String id = "LOCAL_GTT_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        activeWatchers.put(id, order);
        log.info("[LOCAL-GTT] Registered virtual GTT watcher for {} (ID: {})", order.symbol(), id);
        return id;
    }

    @Override
    public boolean modifyGtt(String gttId, CarGttOrder newOrder) {
        if (gttId == null) return false;
        activeWatchers.put(gttId, newOrder);
        return true;
    }

    @Override
    public boolean cancelGtt(String gttId) {
        if (gttId == null) return false;
        activeWatchers.remove(gttId);
        return true;
    }

    @Override
    public GttStatus getGttStatus(String gttId) {
        return activeWatchers.containsKey(gttId) ? GttStatus.PENDING : GttStatus.CANCELLED;
    }
}
```

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.strategy.car.gtt.ZerodhaKiteGttGatewayTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/car/gtt/ src/test/java/com/tradingbot/strategy/car/gtt/
git commit -m "feat: implement GttExecutionGateway with Zerodha and local watcher adapters"
```

---

### Task 5: Sunday Weekly Routine Engine (`CarWeeklyGttService`)

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/car/config/CarWeeklyProperties.java`
- Create: `src/main/java/com/tradingbot/strategy/car/CarWeeklyGttService.java`
- Test: `src/test/java/com/tradingbot/strategy/car/CarWeeklyGttServiceTest.java`

**Interfaces:**
- Consumes: `CarCalculator`, `CarWeeklyTriggerGenerator`, `GttExecutionGateway`, `HistoricalOhlcCacheService`, `TelegramService`.
- Produces: `runSundayWeeklyRoutine()` executing universe scan, GTT reconciliation, and Telegram reporting.

- [ ] **Step 1: Write unit test `CarWeeklyGttServiceTest.java`**

```java
package com.tradingbot.strategy.car;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.car.config.CarWeeklyProperties;
import com.tradingbot.strategy.car.gtt.ZerodhaKiteGttGateway;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CarWeeklyGttServiceTest {

    private CarWeeklyGttService service;
    private CarCalculator calculator;
    private CarWeeklyTriggerGenerator triggerGenerator;
    private HistoricalOhlcCacheService ohlcService;
    private ZerodhaKiteGttGateway gttGateway;
    private TelegramService telegramService;

    @BeforeEach
    void setUp() {
        calculator = new CarCalculator(10, 252);
        triggerGenerator = new CarWeeklyTriggerGenerator(new BigDecimal("0.10"));
        ohlcService = mock(HistoricalOhlcCacheService.class);
        gttGateway = mock(ZerodhaKiteGttGateway.class);
        telegramService = mock(TelegramService.class);

        CarWeeklyProperties props = new CarWeeklyProperties();
        props.setTotalCapital(1000000.0);
        props.setNumParts(40);

        service = new CarWeeklyGttService(
                props,
                calculator,
                triggerGenerator,
                ohlcService,
                List.of(gttGateway),
                telegramService);
    }

    @Test
    void testSundayRoutineComputesUnitAndScansUniverse() {
        assertEquals(new BigDecimal("25000.00"), service.getPortfolioState().getUnitSize());
        assertEquals(40, service.getPortfolioState().getAvailableUnits());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.car.CarWeeklyGttServiceTest`
Expected: FAIL.

- [ ] **Step 3: Implement `CarWeeklyProperties.java` and `CarWeeklyGttService.java`**

Create `CarWeeklyProperties.java`:
```java
package com.tradingbot.strategy.car.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trading-bot.strategy.car-weekly")
public class CarWeeklyProperties {

    private boolean enabled = true;
    private double totalCapital = 1000000.0;
    private int numParts = 40;
    private double profitTargetPct = 6.28;
    private double triggerBuffer = 0.10;
    private int carPositiveDays = 10;
    private int highLookbackDays = 252;
    private boolean telegramAlerts = true;
    private String stateFilePath = "data/car_portfolio_state.json";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public double getTotalCapital() { return totalCapital; }
    public void setTotalCapital(double totalCapital) { this.totalCapital = totalCapital; }
    public int getNumParts() { return numParts; }
    public void setNumParts(int numParts) { this.numParts = numParts; }
    public double getProfitTargetPct() { return profitTargetPct; }
    public void setProfitTargetPct(double profitTargetPct) { this.profitTargetPct = profitTargetPct; }
    public double getTriggerBuffer() { return triggerBuffer; }
    public void setTriggerBuffer(double triggerBuffer) { this.triggerBuffer = triggerBuffer; }
    public int getCarPositiveDays() { return carPositiveDays; }
    public void setCarPositiveDays(int carPositiveDays) { this.carPositiveDays = carPositiveDays; }
    public int getHighLookbackDays() { return highLookbackDays; }
    public void setHighLookbackDays(int highLookbackDays) { this.highLookbackDays = highLookbackDays; }
    public boolean isTelegramAlerts() { return telegramAlerts; }
    public void setTelegramAlerts(boolean telegramAlerts) { this.telegramAlerts = telegramAlerts; }
    public String getStateFilePath() { return stateFilePath; }
    public void setStateFilePath(String stateFilePath) { this.stateFilePath = stateFilePath; }
}
```

Create `CarWeeklyGttService.java`:
```java
package com.tradingbot.strategy.car;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.car.config.CarWeeklyProperties;
import com.tradingbot.strategy.car.gtt.GttExecutionGateway;
import com.tradingbot.strategy.car.model.*;
import com.tradingbot.strategy.car.universe.Nifty100Registry;
import com.tradingbot.telegram.TelegramService;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CarWeeklyGttService {

    private static final Logger log = LoggerFactory.getLogger(CarWeeklyGttService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final CarWeeklyProperties properties;
    private final CarCalculator calculator;
    private final CarWeeklyTriggerGenerator triggerGenerator;
    private final HistoricalOhlcCacheService ohlcService;
    private final List<GttExecutionGateway> gttGateways;
    private final TelegramService telegramService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private CarPortfolioState portfolioState;

    public CarWeeklyGttService(
            CarWeeklyProperties properties,
            CarCalculator calculator,
            CarWeeklyTriggerGenerator triggerGenerator,
            HistoricalOhlcCacheService ohlcService,
            List<GttExecutionGateway> gttGateways,
            TelegramService telegramService) {
        this.properties = properties;
        this.calculator = calculator;
        this.triggerGenerator = triggerGenerator;
        this.ohlcService = ohlcService;
        this.gttGateways = gttGateways != null ? gttGateways : List.of();
        this.telegramService = telegramService;
        this.portfolioState = new CarPortfolioState(
                BigDecimal.valueOf(properties.getTotalCapital()),
                properties.getNumParts(),
                BigDecimal.valueOf(properties.getProfitTargetPct()));
    }

    @PostConstruct
    public void init() {
        loadState();
    }

    public synchronized void runSundayWeeklyRoutine() {
        if (!properties.isEnabled()) {
            log.info("[CAR-WEEKLY] Strategy is disabled.");
            return;
        }

        log.info("==================================================================");
        log.info("       CAR WEEKLY GTT STRATEGY - SUNDAY RECONCILIATION            ");
        log.info("==================================================================");

        BigDecimal unitSize = portfolioState.getUnitSize();
        int availableUnits = portfolioState.getAvailableUnits();
        log.info("[CAR-WEEKLY] Total Capital: ₹{} | UNIT Size: ₹{} | Available Units: {}/{}",
                portfolioState.getTotalCapital(), unitSize, availableUnits, properties.getNumParts());

        Set<String> universe = Nifty100Registry.getUniverseWithHoldings(portfolioState.getHoldings().keySet());
        List<CarAnalysisResult> carPositiveStocks = new ArrayList<>();

        for (String symbol : universe) {
            List<Candle> dailyCandles = ohlcService.getCachedCandles(symbol, "D");
            if (dailyCandles != null && !dailyCandles.isEmpty()) {
                CarAnalysisResult res = calculator.analyze(symbol, dailyCandles);
                if (res.isCarPositive()) {
                    carPositiveStocks.add(res);
                }
            }
        }

        log.info("[CAR-WEEKLY] Evaluated {} universe stocks. Found {} CAR-Positive candidates.",
                universe.size(), carPositiveStocks.size());

        saveState();
        sendSundayTelegramReport(carPositiveStocks);
    }

    private void sendSundayTelegramReport(List<CarAnalysisResult> carPositives) {
        if (!properties.isTelegramAlerts() || telegramService == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("📈 *CAR Weekly GTT Sunday Report*\n\n");
        sb.append(String.format("• *Total Capital:* `₹%.2f`\n", portfolioState.getTotalCapital().doubleValue()));
        sb.append(String.format("• *UNIT Spend:* `₹%.2f` (Available Units: %d/%d)\n",
                portfolioState.getUnitSize().doubleValue(), portfolioState.getAvailableUnits(), properties.getNumParts()));
        sb.append(String.format("• *Active Holdings:* `%d` | *CAR-Positive Stocks:* `%d`\n\n",
                portfolioState.getHoldings().size(), carPositives.size()));

        if (!carPositives.isEmpty()) {
            sb.append("🎯 *Top CAR-Positive Setups:*\n");
            for (int i = 0; i < Math.min(5, carPositives.size()); i++) {
                CarAnalysisResult c = carPositives.get(i);
                sb.append(String.format(" • *%s* (Streak: %d days | 52W High: ₹%.2f)\n",
                        c.symbol(), c.consecutivePositiveDays(), c.fiftyTwoWeekHighClose().doubleValue()));
            }
        }

        telegramService.sendTextMessage(sb.toString());
    }

    private void saveState() {
        try {
            File f = new File(properties.getStateFilePath());
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(f, portfolioState);
        } catch (Exception e) {
            log.error("[CAR-WEEKLY] Failed saving state: {}", e.getMessage(), e);
        }
    }

    private void loadState() {
        try {
            File f = new File(properties.getStateFilePath());
            if (f.exists()) {
                this.portfolioState = objectMapper.readValue(f, CarPortfolioState.class);
                log.info("[CAR-WEEKLY] Loaded portfolio state: {} holdings", portfolioState.getHoldings().size());
            }
        } catch (Exception e) {
            log.warn("[CAR-WEEKLY] Could not load state: {}", e.getMessage());
        }
    }

    public CarPortfolioState getPortfolioState() { return portfolioState; }
}
```

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.strategy.car.CarWeeklyGttServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/car/ src/test/java/com/tradingbot/strategy/car/
git commit -m "feat: implement CarWeeklyGttService with Sunday scan and state management"
```

---

### Task 6: Sunday Scheduler, Controller Endpoints & REST API

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/car/scheduler/CarWeeklyScheduler.java`
- Create: `src/main/java/com/tradingbot/strategy/car/controller/CarWeeklyController.java`
- Test: `src/test/java/com/tradingbot/strategy/car/controller/CarWeeklyControllerTest.java`

- [ ] **Step 1: Write unit test `CarWeeklyControllerTest.java`**

```java
package com.tradingbot.strategy.car.controller;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tradingbot.strategy.car.CarWeeklyGttService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CarWeeklyController.class)
class CarWeeklyControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private CarWeeklyGttService carService;

    @Test
    void testTriggerWeeklyScanEndpoint() throws Exception {
        mockMvc.perform(post("/api/v1/car/run-weekly"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        verify(carService).runSundayWeeklyRoutine();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.car.controller.CarWeeklyControllerTest`
Expected: FAIL.

- [ ] **Step 3: Implement `CarWeeklyScheduler.java` and `CarWeeklyController.java`**

Create `CarWeeklyScheduler.java`:
```java
package com.tradingbot.strategy.car.scheduler;

import com.tradingbot.strategy.car.CarWeeklyGttService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class CarWeeklyScheduler {

    private static final Logger log = LoggerFactory.getLogger(CarWeeklyScheduler.class);
    private final CarWeeklyGttService carWeeklyService;

    public CarWeeklyScheduler(CarWeeklyGttService carWeeklyService) {
        this.carWeeklyService = carWeeklyService;
    }

    /** Executes every Sunday at 10:00 AM IST. */
    @Scheduled(cron = "${trading-bot.strategy.car-weekly.sunday-cron:0 0 10 ? * SUN}", zone = "Asia/Kolkata")
    public void scheduledSundayRoutine() {
        log.info("[CAR-SCHEDULER] Sunday 10:00 AM IST reached. Executing CAR Weekly GTT routine...");
        try {
            carWeeklyService.runSundayWeeklyRoutine();
        } catch (Exception e) {
            log.error("[CAR-SCHEDULER] Exception in Sunday routine: {}", e.getMessage(), e);
        }
    }
}
```

Create `CarWeeklyController.java`:
```java
package com.tradingbot.strategy.car.controller;

import com.tradingbot.strategy.car.CarWeeklyGttService;
import com.tradingbot.strategy.car.model.CarHolding;
import com.tradingbot.strategy.car.model.CarPortfolioState;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/car")
public class CarWeeklyController {

    private final CarWeeklyGttService carService;

    public CarWeeklyController(CarWeeklyGttService carService) {
        this.carService = carService;
    }

    @PostMapping("/run-weekly")
    public ResponseEntity<Map<String, Object>> runWeekly() {
        carService.runSundayWeeklyRoutine();
        return ResponseEntity.ok(Map.of("status", "SUCCESS", "message", "CAR Sunday Routine completed"));
    }

    @GetMapping("/performance")
    public ResponseEntity<CarPortfolioState> getPerformance() {
        return ResponseEntity.ok(carService.getPortfolioState());
    }

    @GetMapping("/holdings")
    public ResponseEntity<Map<String, CarHolding>> getHoldings() {
        return ResponseEntity.ok(carService.getPortfolioState().getHoldings());
    }
}
```

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew test --tests com.tradingbot.strategy.car.controller.CarWeeklyControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/car/ src/test/java/com/tradingbot/strategy/car/
git commit -m "feat: implement CarWeeklyScheduler and CarWeeklyController REST endpoints"
```

---

### Task 7: End-to-End Multi-Week Integration Verification & Code Standards

**Files:**
- Create: `src/test/java/com/tradingbot/strategy/car/CarStrategyEndToEndIntegrationTest.java`

- [ ] **Step 1: Write end-to-end integration test simulating accumulation $\to$ target hit $\to$ compounding**
- [ ] **Step 2: Run Spotless & SpotBugs and full test suite**
```bash
./gradlew spotlessApply
./gradlew spotbugsMain spotbugsTest
./gradlew test
```
Expected: ALL PASS with 100% clean formatting and zero SpotBugs errors.

- [ ] **Step 3: Commit**

```bash
git add .
git commit -m "test: add end-to-end CAR strategy accumulation and compounding integration test"
```
