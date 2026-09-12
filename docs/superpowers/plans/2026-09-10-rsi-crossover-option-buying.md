# NIFTY 5m vs 15m RSI Crossover Strategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an automated intraday option buying strategy that tracks 5m vs 15m RSI(14) on NIFTY 50, buying ATM CE/PE on crossover and exiting on reverse or 15:05 IST with a 1 trade/day limit.

**Architecture:** A dedicated service (`RsiCrossoverStrategyService`) evaluates completed 5m/15m RSI series on NIFTY 50 fetched from Shoonya, executes ATM weekly option purchases (Paper or Live), exits on opposite RSI crossover or at 15:05:10 IST, driven by a precision Spring `@Scheduled` scheduler (`RsiCrossoverScheduler`).

**Tech Stack:** Java 21, Spring Boot 3.4.3, TA-Lib Core (`TechnicalAnalysisService`), JUnit 5, Mockito, AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-10-rsi-crossover-option-buying-design.md`

## Global Constraints
- **Underlying:** NIFTY 50 Index (`NSE`, Token: `10576`).
- **Timing:** First cycle at 09:45:10 IST, active every 5 mins until 15:00:10 IST, square-off at 15:05:10 IST, daily reset at 09:15:00 IST.
- **RSI Period:** 14 on Close prices for both 5m and 15m timeframes.
- **Warmup Lookback:** 5 trading days ($\ge 350$ 5m bars) to guarantee accurate RSI(14) at 09:45:10 IST.
- **Trade Limit:** Max 1 trade per day.
- **Strike:** ATM rounded to nearest 50 strike (current weekly expiry).
- **Execution:** Paper simulation by default, configurable Live broker routing via `ShoonyaOrderService`.

---

### Task 1: 5-Minute to 15-Minute Candle Resampling Utility

**Files:**
- Modify: `src/main/java/com/tradingbot/util/CandleResamplingUtil.java`
- Modify: `src/test/java/com/tradingbot/util/CandleResamplingUtilTest.java`

**Interfaces:**
- Produces: `CandleResamplingUtil.resample5MinTo15Min(List<Candle> fiveMinCandles) -> List<Candle>`

- [ ] **Step 1: Write the failing test for 5m to 15m resampling**

Add tests to `src/test/java/com/tradingbot/util/CandleResamplingUtilTest.java`:
```java
@Test
void testResample5MinTo15Min() {
    LocalDate today = LocalDate.of(2026, 9, 10);
    Instant t1 = today.atTime(9, 15).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
    Instant t2 = today.atTime(9, 20).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
    Instant t3 = today.atTime(9, 25).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
    Instant t4 = today.atTime(9, 30).atZone(ZoneId.of("Asia/Kolkata")).toInstant();

    Candle c1 = new Candle("NIFTY 50", "5", t1, BigDecimal.valueOf(100), BigDecimal.valueOf(105), BigDecimal.valueOf(98), BigDecimal.valueOf(102), 1000);
    Candle c2 = new Candle("NIFTY 50", "5", t2, BigDecimal.valueOf(102), BigDecimal.valueOf(108), BigDecimal.valueOf(101), BigDecimal.valueOf(107), 1500);
    Candle c3 = new Candle("NIFTY 50", "5", t3, BigDecimal.valueOf(107), BigDecimal.valueOf(110), BigDecimal.valueOf(104), BigDecimal.valueOf(106), 2000);
    Candle c4 = new Candle("NIFTY 50", "5", t4, BigDecimal.valueOf(106), BigDecimal.valueOf(112), BigDecimal.valueOf(105), BigDecimal.valueOf(111), 1200);

    List<Candle> resampled = CandleResamplingUtil.resample5MinTo15Min(List.of(c1, c2, c3, c4));

    assertThat(resampled).hasSize(2);
    // First 15m candle (09:15 - 09:30)
    Candle b1 = resampled.get(0);
    assertThat(b1.timeframe()).isEqualTo("15");
    assertThat(b1.open()).isEqualByComparingTo(BigDecimal.valueOf(100));
    assertThat(b1.high()).isEqualByComparingTo(BigDecimal.valueOf(110));
    assertThat(b1.low()).isEqualByComparingTo(BigDecimal.valueOf(98));
    assertThat(b1.close()).isEqualByComparingTo(BigDecimal.valueOf(106));
    assertThat(b1.volume()).isEqualTo(4500);

    // Second 15m candle (09:30)
    Candle b2 = resampled.get(1);
    assertThat(b2.timeframe()).isEqualTo("15");
    assertThat(b2.open()).isEqualByComparingTo(BigDecimal.valueOf(106));
    assertThat(b2.close()).isEqualByComparingTo(BigDecimal.valueOf(111));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.util.CandleResamplingUtilTest`
Expected: FAIL (method `resample5MinTo15Min` not defined).

- [ ] **Step 3: Implement `resample5MinTo15Min` in `CandleResamplingUtil.java`**

Implement grouping by 15-minute intervals:
```java
public static List<Candle> resample5MinTo15Min(List<Candle> fiveMinCandles) {
    if (fiveMinCandles == null || fiveMinCandles.isEmpty()) {
        return List.of();
    }

    Map<Long, List<Candle>> groupedBy15Min = new LinkedHashMap<>();

    for (Candle c : fiveMinCandles) {
        if (c == null || c.timestamp() == null) continue;
        long epochMinutes = c.timestamp().getEpochSecond() / 60;
        long intervalKey = (epochMinutes / 15) * 15;
        groupedBy15Min.computeIfAbsent(intervalKey, k -> new ArrayList<>()).add(c);
    }

    List<Candle> resampled = new ArrayList<>();
    for (List<Candle> bucket : groupedBy15Min.values()) {
        if (bucket.isEmpty()) continue;
        bucket.sort(Comparator.comparing(Candle::timestamp));
        Candle first = bucket.get(0);
        Candle last = bucket.get(bucket.size() - 1);

        BigDecimal open = first.open();
        BigDecimal close = last.close();
        BigDecimal high = first.high();
        BigDecimal low = first.low();
        long totalVolume = 0;

        for (Candle b : bucket) {
            if (b.high().compareTo(high) > 0) high = b.high();
            if (b.low().compareTo(low) < 0) low = b.low();
            totalVolume += b.volume();
        }

        resampled.add(new Candle(
                first.symbol(),
                "15",
                last.timestamp(),
                open,
                high,
                low,
                close,
                totalVolume
        ));
    }
    resampled.sort(Comparator.comparing(Candle::timestamp));
    return resampled;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.util.CandleResamplingUtilTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/util/CandleResamplingUtil.java src/test/java/com/tradingbot/util/CandleResamplingUtilTest.java
git commit -m "feat(util): add resample5MinTo15Min to CandleResamplingUtil"
```

---

### Task 2: Strategy Models & Position Tracking

**Files:**
- Create: `src/main/java/com/tradingbot/model/strategy/RsiCrossoverPosition.java`
- Create: `src/main/java/com/tradingbot/model/strategy/RsiCrossoverSignal.java`
- Test: `src/test/java/com/tradingbot/model/strategy/RsiCrossoverPositionTest.java`

**Interfaces:**
- Produces: `RsiCrossoverPosition` record/class with helper methods `calculatePnl(BigDecimal currentPrice)`, `close(BigDecimal exitPrice, String reason, Instant time)`.
- Produces: `RsiCrossoverSignal` enum (`NONE`, `BUY_CALL`, `BUY_PUT`, `EXIT_CALL`, `EXIT_PUT`).

- [ ] **Step 1: Write the failing unit test for `RsiCrossoverPosition`**

Create `src/test/java/com/tradingbot/model/strategy/RsiCrossoverPositionTest.java`:
```java
package com.tradingbot.model.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RsiCrossoverPositionTest {

    @Test
    void testPositionLifecycleAndPnlCalculation() {
        Instant now = Instant.now();
        RsiCrossoverPosition pos = new RsiCrossoverPosition(
                "TRD_001",
                "NIFTY24OCT22500CE",
                "CE",
                BigDecimal.valueOf(22500),
                BigDecimal.valueOf(150.0),
                65,
                now
        );

        assertThat(pos.isClosed()).isFalse();
        assertThat(pos.calculatePnl(BigDecimal.valueOf(180.0)))
                .isEqualByComparingTo(BigDecimal.valueOf(1950.0)); // (180 - 150) * 65

        pos.close(BigDecimal.valueOf(180.0), "RSI_REVERSAL", now.plusSeconds(300));
        assertThat(pos.isClosed()).isTrue();
        assertThat(pos.getExitPrice()).isEqualByComparingTo(BigDecimal.valueOf(180.0));
        assertThat(pos.getExitReason()).isEqualTo("RSI_REVERSAL");
        assertThat(pos.getPnl()).isEqualByComparingTo(BigDecimal.valueOf(1950.0));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.model.strategy.RsiCrossoverPositionTest`
Expected: FAIL.

- [ ] **Step 3: Create `RsiCrossoverSignal` and `RsiCrossoverPosition`**

Create `src/main/java/com/tradingbot/model/strategy/RsiCrossoverSignal.java`:
```java
package com.tradingbot.model.strategy;

public enum RsiCrossoverSignal {
    NONE,
    BUY_CALL,
    BUY_PUT,
    EXIT_CALL,
    EXIT_PUT
}
```

Create `src/main/java/com/tradingbot/model/strategy/RsiCrossoverPosition.java`:
```java
package com.tradingbot.model.strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

public class RsiCrossoverPosition {
    private final String tradeId;
    private final String symbol;
    private final String optionType; // CE or PE
    private final BigDecimal strike;
    private final BigDecimal entryPrice;
    private final int quantity;
    private final Instant entryTime;

    private BigDecimal exitPrice;
    private Instant exitTime;
    private String exitReason;
    private BigDecimal pnl;
    private boolean closed;

    public RsiCrossoverPosition(
            String tradeId,
            String symbol,
            String optionType,
            BigDecimal strike,
            BigDecimal entryPrice,
            int quantity,
            Instant entryTime) {
        this.tradeId = tradeId;
        this.symbol = symbol;
        this.optionType = optionType;
        this.strike = strike;
        this.entryPrice = entryPrice;
        this.quantity = quantity;
        this.entryTime = entryTime;
        this.closed = false;
    }

    public BigDecimal calculatePnl(BigDecimal currentPrice) {
        if (currentPrice == null || entryPrice == null) return BigDecimal.ZERO;
        return currentPrice.subtract(entryPrice)
                .multiply(BigDecimal.valueOf(quantity))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public void close(BigDecimal exitPrice, String exitReason, Instant exitTime) {
        this.exitPrice = exitPrice;
        this.exitReason = exitReason;
        this.exitTime = exitTime;
        this.pnl = calculatePnl(exitPrice);
        this.closed = true;
    }

    // Getters
    public String getTradeId() { return tradeId; }
    public String getSymbol() { return symbol; }
    public String getOptionType() { return optionType; }
    public BigDecimal getStrike() { return strike; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public int getQuantity() { return quantity; }
    public Instant getEntryTime() { return entryTime; }
    public BigDecimal getExitPrice() { return exitPrice; }
    public Instant getExitTime() { return exitTime; }
    public String getExitReason() { return exitReason; }
    public BigDecimal getPnl() { return pnl; }
    public boolean isClosed() { return closed; }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.model.strategy.RsiCrossoverPositionTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/model/strategy/RsiCrossoverSignal.java src/main/java/com/tradingbot/model/strategy/RsiCrossoverPosition.java src/test/java/com/tradingbot/model/strategy/RsiCrossoverPositionTest.java
git commit -m "feat(model): add RsiCrossoverPosition and RsiCrossoverSignal models"
```

---

### Task 3: Telegram Service Notifications for RSI Crossover

**Files:**
- Modify: `src/main/java/com/tradingbot/telegram/TelegramService.java`
- Modify: `src/test/java/com/tradingbot/telegram/TelegramServiceTest.java`

**Interfaces:**
- Produces: `sendRsiCrossoverEntryAlert(RsiCrossoverPosition position, double rsi5, double rsi15, double prevRsi5, double prevRsi15)`
- Produces: `sendRsiCrossoverExitAlert(RsiCrossoverPosition position, String reason)`

- [ ] **Step 1: Write unit tests in `TelegramServiceTest.java`**

Add tests asserting formatting of RSI crossover entry and exit messages.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.telegram.TelegramServiceTest`
Expected: FAIL.

- [ ] **Step 3: Implement methods in `TelegramService.java`**

Add formatted HTML/markdown Telegram alerts:
- `sendRsiCrossoverEntryAlert`
- `sendRsiCrossoverExitAlert`

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.telegram.TelegramServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/telegram/TelegramService.java src/test/java/com/tradingbot/telegram/TelegramServiceTest.java
git commit -m "feat(telegram): add RSI Crossover strategy notification alerts"
```

---

### Task 4: Core Engine - `RsiCrossoverStrategyService`

**Files:**
- Create: `src/main/java/com/tradingbot/service/RsiCrossoverStrategyService.java`
- Create: `src/test/java/com/tradingbot/service/RsiCrossoverStrategyServiceTest.java`

**Interfaces:**
- Consumes: `ShoonyaMarketDataService`, `TechnicalAnalysisService`, `ShoonyaOptionChainService`, `ShoonyaOrderService`, `TelegramService`, `ShoonyaConfig`.
- Produces: `runCycle()`, `evaluateCrossover()`, `executeSquareOff(String reason)`, `resetDaily()`, `getStatus()`.

- [ ] **Step 1: Write comprehensive unit tests for `RsiCrossoverStrategyServiceTest`**

Cover:
1. Long crossover trigger: $RSI_{5m}[t-1] \le RSI_{15m}[t-1]$ and $RSI_{5m}[t] > RSI_{15m}[t]$ enters CE buy.
2. Short crossover trigger: $RSI_{5m}[t-1] \ge RSI_{15m}[t-1]$ and $RSI_{5m}[t] < RSI_{15m}[t]$ enters PE buy.
3. 1 trade/day enforcement: once in position or trade closed, subsequent crossovers on the same day are ignored.
4. Exit on reversal: holding CE and receiving $RSI_{5m} < RSI_{15m}$ closes position.
5. Intraday square-off at 15:05 IST: closes any open position.
6. Daily reset at 09:15: clears trade count and state.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.service.RsiCrossoverStrategyServiceTest`
Expected: FAIL.

- [ ] **Step 3: Implement `RsiCrossoverStrategyService.java`**

Implementation details:
- Default values: `enabled=true`, `lots=1`, `lotSize=65`, `autoExecute=false`, `rsiPeriod=14`.
- Injectable `java.time.Clock clock` (default `ZoneId.of("Asia/Kolkata")`).
- Fetch 5-day 5m candles (`fetchHistoricalCandles("NSE", "10576", "NIFTY 50", "5", 5)`).
- Resample 15m candles via `CandleResamplingUtil.resample5MinTo15Min(fiveMinCandles)`.
- Compute RSI series for both using `taService.calculateRsiSeries`.
- Identify ATM strike: `StockFnoRegistry.calculateAtmStrike("NIFTY50", BigDecimal.valueOf(ltp))` (or nearest 50).
- Find weekly option contract via `ShoonyaOptionChainService` / `StockFnoRegistry`.
- Manage state thread-safely with `AtomicBoolean tradeExecutedToday` and `AtomicReference<RsiCrossoverPosition> openPosition`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.service.RsiCrossoverStrategyServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/service/RsiCrossoverStrategyService.java src/test/java/com/tradingbot/service/RsiCrossoverStrategyServiceTest.java
git commit -m "feat(strategy): implement RsiCrossoverStrategyService with 1 trade/day and reversal exit"
```

---

### Task 5: Precision Automation Scheduler - `RsiCrossoverScheduler`

**Files:**
- Create: `src/main/java/com/tradingbot/scheduler/RsiCrossoverScheduler.java`
- Create: `src/test/java/com/tradingbot/scheduler/RsiCrossoverSchedulerTest.java`

**Interfaces:**
- Consumes: `RsiCrossoverStrategyService`.
- Produces: `@Scheduled` methods for 09:15 reset, 09:45:10 - 15:00:10 5m evaluation, 15:05:10 EOD square-off.

- [ ] **Step 1: Write failing unit test for `RsiCrossoverSchedulerTest`**

Test that scheduler methods properly delegate to `service.runCycle()`, `service.resetDaily()`, and `service.executeSquareOff("EOD_1505_SQUARE_OFF")`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.scheduler.RsiCrossoverSchedulerTest`
Expected: FAIL.

- [ ] **Step 3: Implement `RsiCrossoverScheduler.java`**

```java
package com.tradingbot.scheduler;

import com.tradingbot.service.RsiCrossoverStrategyService;
import java.time.LocalTime;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RsiCrossoverScheduler {

    private static final Logger log = LoggerFactory.getLogger(RsiCrossoverScheduler.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final RsiCrossoverStrategyService strategyService;

    @Value("${trading-bot.strategy.rsi-crossover.scheduler-enabled:true}")
    private boolean schedulerEnabled = true;

    @Autowired
    public RsiCrossoverScheduler(RsiCrossoverStrategyService strategyService) {
        this.strategyService = strategyService;
    }

    /** 09:15:00 IST: Daily Strategy Reset */
    @Scheduled(cron = "0 15 9 ? * MON-FRI", zone = "Asia/Kolkata")
    public void scheduleDailyReset() {
        if (!schedulerEnabled) return;
        log.info("[RSI-SCHEDULER] 09:15 IST Market Open: Resetting daily RSI Crossover state.");
        strategyService.resetDaily();
    }

    /** 09:45:10, 09:50:10, 09:55:10 IST Morning Checks */
    @Scheduled(cron = "10 45,50,55 9 ? * MON-FRI", zone = "Asia/Kolkata")
    public void scheduleMorningCandleCycle() {
        if (!schedulerEnabled) return;
        strategyService.runCycle();
    }

    /** 10:00:10 to 14:55:10 IST Regular 5-Min Candle Checks */
    @Scheduled(cron = "10 */5 10-14 ? * MON-FRI", zone = "Asia/Kolkata")
    public void scheduleIntradayCandleCycle() {
        if (!schedulerEnabled) return;
        strategyService.runCycle();
    }

    /** 15:00:10 IST Final 5-Min Candle Check */
    @Scheduled(cron = "10 0 15 ? * MON-FRI", zone = "Asia/Kolkata")
    public void scheduleFinalCandleCycle() {
        if (!schedulerEnabled) return;
        strategyService.runCycle();
    }

    /** 15:05:10 IST Mandatory EOD Square-Off */
    @Scheduled(cron = "10 5 15 ? * MON-FRI", zone = "Asia/Kolkata")
    public void scheduleEodSquareOff() {
        if (!schedulerEnabled) return;
        log.info("[RSI-SCHEDULER] 15:05 IST EOD: Triggering mandatory square-off for any active position.");
        strategyService.executeSquareOff("EOD_1505_SQUARE_OFF");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.scheduler.RsiCrossoverSchedulerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/scheduler/RsiCrossoverScheduler.java src/test/java/com/tradingbot/scheduler/RsiCrossoverSchedulerTest.java
git commit -m "feat(scheduler): implement RsiCrossoverScheduler with automated precision cron jobs"
```

---

### Task 6: Configuration Properties & Environment Setup

**Files:**
- Modify: `src/main/resources/application.properties`
- Modify: `.env.example`
- Modify: `.env`

- [ ] **Step 1: Add configuration properties to `src/main/resources/application.properties`**

```properties
# NIFTY 5m vs 15m RSI Crossover Strategy
trading-bot.strategy.rsi-crossover.enabled=${RSI_CROSSOVER_ENABLED:true}
trading-bot.strategy.rsi-crossover.scheduler-enabled=${RSI_CROSSOVER_SCHEDULER_ENABLED:true}
trading-bot.strategy.rsi-crossover.auto-execute=${RSI_CROSSOVER_AUTO_EXECUTE:false}
trading-bot.strategy.rsi-crossover.lots=${RSI_CROSSOVER_LOTS:1}
trading-bot.strategy.rsi-crossover.lot-size=${RSI_CROSSOVER_LOT_SIZE:65}
trading-bot.strategy.rsi-crossover.rsi-period=${RSI_CROSSOVER_PERIOD:14}
trading-bot.strategy.rsi-crossover.telegram-alerts=${RSI_CROSSOVER_TELEGRAM_ALERTS:true}
```

- [ ] **Step 2: Add environment variables to `.env.example` and `.env`**

```env
# ------------------------------------------------------------------------------
# NIFTY 5m vs 15m RSI Crossover Strategy (Intraday Option Buying)
# ------------------------------------------------------------------------------
RSI_CROSSOVER_ENABLED=true
RSI_CROSSOVER_SCHEDULER_ENABLED=true
RSI_CROSSOVER_AUTO_EXECUTE=false
RSI_CROSSOVER_LOTS=1
RSI_CROSSOVER_LOT_SIZE=65
RSI_CROSSOVER_PERIOD=14
RSI_CROSSOVER_TELEGRAM_ALERTS=true
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.properties .env.example .env
git commit -m "config(rsi-crossover): add application properties and environment variable defaults"
```

---

### Task 7: Full Verification & Docker Build

**Files:**
- All created and modified files.

- [ ] **Step 1: Run complete test suite**

Run: `./gradlew test --rerun-tasks`
Expected: ALL test suites pass (100% success).

- [ ] **Step 2: Build Spring Boot Jar**

Run: `./gradlew bootJar`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Build and push Docker image**

Run:
```bash
docker build -t sharadprsn/shoonya-trading-bot:latest .
docker push sharadprsn/shoonya-trading-bot:latest
```
Expected: Pushed successfully to Docker Hub.
