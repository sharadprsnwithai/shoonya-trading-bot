# Intraday 1-Minute Bollinger Bands & Heikin-Ashi Option Buying Strategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement an automated intraday option buying strategy engine based on 1-minute Bollinger Bands (20, 2) and Heikin-Ashi reversals for Nifty ATM weekly options, complete with pre-market strike selection, hybrid data feeding (WebSocket + REST fallback), 1:2 R:R partial booking, trailing Cost SL, a strict 2-trade daily cap, and reactive signal bus integration.

**Architecture:** A modular event-driven subsystem under `com.tradingbot.strategy.bollingerha`. Pre-market selector determines ATM CE/PE contracts at 09:07 AM. Real-time 1m candles are aggregated by `BollingerHaCandleBuilder` via `ShoonyaHybridDataFeeder`. `BollingerHaIntradayEngine` processes completed candles, calculates Heikin-Ashi and Bollinger Bands, verifies entry/SL risk filters, manages 1:2 target partial exits & Cost SL, and publishes signals to `ReactiveSignalEventBus` for multi-broker execution and Telegram alerts.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Project Reactor, TA4j, Jackson, JUnit 5, Mockito, AssertJ.

**Spec:** `docs/superpowers/specs/2026-10-02-bollinger-ha-intraday-option-buying-design.md`

## Global Constraints

* All code must conform to Java 21, Spring Boot 3 conventions, and Google Java Format (enforced via `./gradlew spotlessApply`).
* Timeframe is strictly 1-minute (`1m`).
* Max allowed risk (SL points) is ₹20.00; minimum is ₹2.00. Buffer is ₹1.00.
* Risk-to-Reward ratio for Target 1 is 1:2.0.
* Quantity splitting: 50% at Target 1, remaining 50% trailed to Cost (Entry Price).
* Maximum trades per day: 2.
* Morning entry window: 09:15 AM to 10:30 AM IST. Auto square-off at 15:15 IST.
* Signal emission must use `ReactiveSignalEventBus` with standard `TradeSignal` records.

## Review Focus

1. **Synthetic vs Traded Prices**: Heikin-Ashi candle high/low are synthetic averages; trigger validation must check actual tick/LTP crossing against the signal's HA entry level ($HA\_High + 1.0$).
2. **WebSocket Disconnection during 09:15 Rush**: If Shoonya WebSocket drops, REST TPSeries fallback must seamlessly poll and stitch the missing 1m candles without emitting duplicate signals.
3. **Partial Exit Quantity Rounding**: When splitting lots (e.g. 2 lots = 130 qty), half quantity is exactly 1 lot (65 qty). If odd lot counts occur, integer truncation must never produce 0 exit qty.
4. **Opposite Strike Flip on Stop Loss**: When Trade #1 hits SL, the engine must immediately reset scanning state for the opposite contract without resetting daily trade count (must count as 1 completed trade).
5. **Exact 10:30 AM Cutoff**: Signals formed at or after 10:30:00 IST must be rejected for entry, while active positions continue trailing until T1/SL/15:15.

---

### Task 1: Configuration & Domain Models

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/bollingerha/config/BollingerHaProperties.java`
- Create: `src/main/java/com/tradingbot/strategy/bollingerha/model/BollingerHaPosition.java`
- Create: `src/main/java/com/tradingbot/strategy/bollingerha/model/BollingerHaDailyState.java`
- Create: `src/main/java/com/tradingbot/strategy/bollingerha/model/BollingerHaSetupState.java`
- Test: `src/test/java/com/tradingbot/strategy/bollingerha/config/BollingerHaPropertiesTest.java`

**Interfaces:**
- Produces: `BollingerHaProperties`, `BollingerHaPosition`, `BollingerHaDailyState`, `BollingerHaSetupState`

- [ ] **Step 1: Write the failing test**

```java
package com.tradingbot.strategy.bollingerha.config;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BollingerHaPropertiesTest {
    @Test
    void testDefaultPropertyValues() {
        BollingerHaProperties props = new BollingerHaProperties();
        assertTrue(props.isEnabled());
        assertEquals("NIFTY", props.getUnderlying());
        assertEquals(1, props.getTimeframeMinutes());
        assertEquals(20, props.getBbPeriod());
        assertEquals(2.0, props.getBbStdDev());
        assertEquals(new BigDecimal("20.0"), props.getMaxSlPoints());
        assertEquals(new BigDecimal("2.0"), props.getMinSlPoints());
        assertEquals(new BigDecimal("1.0"), props.getBufferPoints());
        assertEquals(new BigDecimal("2.0"), props.getRiskRewardRatio());
        assertEquals(2, props.getMaxDailyTrades());
        assertEquals(2, props.getDefaultLots());
        assertEquals("09:15", props.getEntryWindowStart());
        assertEquals("10:30", props.getEntryWindowCutoff());
        assertEquals("15:15", props.getAutoSquareOffTime());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.bollingerha.config.BollingerHaPropertiesTest`
Expected: FAIL (class not found)

- [ ] **Step 3: Implement domain models and properties**

Implement `BollingerHaProperties` with `@ConfigurationProperties(prefix = "trading-bot.strategy.bollinger-ha")`, `BollingerHaPosition` (representing active trade state with entryPrice, stopLoss, targetPrice, totalQuantity, remainingQuantity, targetHit, costSlActive), `BollingerHaDailyState` (tradeCount, realizedPnl, isLocked), and `BollingerHaSetupState` (token, symbol, strike, optionType, touchedLowerBand, touchCandleIndex, pendingSignal).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.strategy.bollingerha.config.BollingerHaPropertiesTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/bollingerha/ src/test/java/com/tradingbot/strategy/bollingerha/config/
git commit -m "feat(bollinger-ha): add configuration properties and domain models"
```

---

### Task 2: Heikin-Ashi & Bollinger Bands Indicator Calculator

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/bollingerha/indicator/BollingerHaCalculator.java`
- Create: `src/main/java/com/tradingbot/strategy/bollingerha/model/HeikinAshiCandle.java`
- Create: `src/main/java/com/tradingbot/strategy/bollingerha/model/BollingerBandResult.java`
- Test: `src/test/java/com/tradingbot/strategy/bollingerha/indicator/BollingerHaCalculatorTest.java`

**Interfaces:**
- Consumes: `com.tradingbot.model.Candle`
- Produces: `HeikinAshiCandle`, `BollingerBandResult`, `BollingerHaCalculator.calculateHeikinAshi(List<Candle>)`, `BollingerHaCalculator.calculateBollingerBands(List<HeikinAshiCandle>, int period, double stdDev)`

- [ ] **Step 1: Write the failing test**

```java
package com.tradingbot.strategy.bollingerha.indicator;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.bollingerha.model.BollingerBandResult;
import com.tradingbot.strategy.bollingerha.model.HeikinAshiCandle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BollingerHaCalculatorTest {

    @Test
    void testHeikinAshiTransformation() {
        List<Candle> regularCandles = List.of(
            new Candle(Instant.now(), new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("95"), new BigDecimal("105"), 1000L),
            new Candle(Instant.now().plusSeconds(60), new BigDecimal("105"), new BigDecimal("115"), new BigDecimal("102"), new BigDecimal("112"), 1500L)
        );

        List<HeikinAshiCandle> haCandles = BollingerHaCalculator.calculateHeikinAshi(regularCandles);
        assertEquals(2, haCandles.size());
        
        // Candle 0: HA_Close = (100+110+95+105)/4 = 102.5, HA_Open = (100+105)/2 = 102.5
        assertEquals(new BigDecimal("102.50"), haCandles.get(0).close());
        assertEquals(new BigDecimal("102.50"), haCandles.get(0).open());
        
        // Candle 1: HA_Open = (102.5 + 102.5)/2 = 102.5, HA_Close = (105+115+102+112)/4 = 108.5
        assertEquals(new BigDecimal("102.50"), haCandles.get(1).open());
        assertEquals(new BigDecimal("108.50"), haCandles.get(1).close());
        assertTrue(haCandles.get(1).isGreen());
    }

    @Test
    void testBollingerBandsOnHeikinAshi() {
        List<HeikinAshiCandle> haSeries = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < 25; i++) {
            BigDecimal price = new BigDecimal(100 + i);
            haSeries.add(new HeikinAshiCandle(now.plusSeconds(i * 60), price, price.add(BigDecimal.ONE), price.subtract(BigDecimal.ONE), price, true, 1000L));
        }

        BollingerBandResult bb = BollingerHaCalculator.calculateBollingerBands(haSeries, 20, 2.0);
        assertNotNull(bb);
        assertTrue(bb.upper().compareTo(bb.middle()) > 0);
        assertTrue(bb.middle().compareTo(bb.lower()) > 0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.bollingerha.indicator.BollingerHaCalculatorTest`
Expected: FAIL (classes not found)

- [ ] **Step 3: Implement `BollingerHaCalculator`, `HeikinAshiCandle`, and `BollingerBandResult`**

Implement standard Heikin-Ashi formulas and 20-period moving average with 2.0 standard deviation using `BigDecimal` with 2 decimal places.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.strategy.bollingerha.indicator.BollingerHaCalculatorTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/bollingerha/indicator/ src/main/java/com/tradingbot/strategy/bollingerha/model/ src/test/java/com/tradingbot/strategy/bollingerha/indicator/
git commit -m "feat(bollinger-ha): implement heikin-ashi and bollinger bands indicator calculator"
```

---

### Task 3: Pre-Market ATM Strike & Contract Selector

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/bollingerha/service/BollingerHaStrikeSelector.java`
- Create: `src/main/java/com/tradingbot/strategy/bollingerha/model/SelectedStrikes.java`
- Test: `src/test/java/com/tradingbot/strategy/bollingerha/service/BollingerHaStrikeSelectorTest.java`

**Interfaces:**
- Consumes: `ShoonyaMarketDataService`, `ShoonyaOptionChainService`
- Produces: `SelectedStrikes` (atmStrike, ceToken, ceSymbol, peToken, peSymbol), `BollingerHaStrikeSelector.selectWeeklyAtmStrikes(BigDecimal spotPrice)`

- [ ] **Step 1: Write the failing test**

```java
package com.tradingbot.strategy.bollingerha.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.model.OptionContract;
import com.tradingbot.model.OptionStrike;
import com.tradingbot.strategy.bollingerha.model.SelectedStrikes;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BollingerHaStrikeSelectorTest {

    @Mock
    private ShoonyaOptionChainService optionChainService;

    @InjectMocks
    private BollingerHaStrikeSelector strikeSelector;

    @Test
    void testAtmRoundingAndSelection() {
        BigDecimal spot = new BigDecimal("25948.35");
        BigDecimal expectedAtm = new BigDecimal("25950");
        
        OptionStrike strike = new OptionStrike(
            expectedAtm,
            new OptionContract("NIFTY26OCT25950CE", "12345", "CE", expectedAtm, BigDecimal.ZERO, 0, 0, 0, BigDecimal.ZERO),
            new OptionContract("NIFTY26OCT25950PE", "67890", "PE", expectedAtm, BigDecimal.ZERO, 0, 0, 0, BigDecimal.ZERO)
        );
        OptionChainResponse chainResp = new OptionChainResponse("NIFTY", expectedAtm, List.of(strike), null, null);
        when(optionChainService.getNifty50OptionChain(eq(expectedAtm), eq(1), eq(false))).thenReturn(chainResp);

        SelectedStrikes result = strikeSelector.selectWeeklyAtmStrikes(spot);
        assertNotNull(result);
        assertEquals(expectedAtm, result.atmStrike());
        assertEquals("12345", result.ceToken());
        assertEquals("NIFTY26OCT25950CE", result.ceSymbol());
        assertEquals("67890", result.peToken());
        assertEquals("NIFTY26OCT25950PE", result.peSymbol());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.bollingerha.service.BollingerHaStrikeSelectorTest`
Expected: FAIL (class not found)

- [ ] **Step 3: Implement `BollingerHaStrikeSelector`**

Calculate ATM strike via `spot.divide(new BigDecimal("50"), 0, RoundingMode.HALF_UP).multiply(new BigDecimal("50"))`, call `ShoonyaOptionChainService.getNifty50OptionChain` to obtain the weekly CE & PE contract tokens.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.strategy.bollingerha.service.BollingerHaStrikeSelectorTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/bollingerha/service/BollingerHaStrikeSelector.java src/main/java/com/tradingbot/strategy/bollingerha/model/SelectedStrikes.java src/test/java/com/tradingbot/strategy/bollingerha/service/
git commit -m "feat(bollinger-ha): implement pre-market atm strike selector"
```

---

### Task 4: Real-Time Candle Builder & Hybrid Data Feeder

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/bollingerha/feeder/BollingerHaCandleBuilder.java`
- Create: `src/main/java/com/tradingbot/strategy/bollingerha/feeder/ShoonyaHybridDataFeeder.java`
- Create: `src/main/java/com/tradingbot/strategy/bollingerha/model/CompletedCandleEvent.java`
- Test: `src/test/java/com/tradingbot/strategy/bollingerha/feeder/BollingerHaCandleBuilderTest.java`

**Interfaces:**
- Consumes: Real-time price ticks / REST `Candle` objects
- Produces: `CompletedCandleEvent` (token, symbol, candle, completedMinuteTimestamp), `Consumer<CompletedCandleEvent>` callbacks

- [ ] **Step 1: Write the failing test**

```java
package com.tradingbot.strategy.bollingerha.feeder;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.bollingerha.model.CompletedCandleEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BollingerHaCandleBuilderTest {

    @Test
    void testMinuteTickAggregationAndCandleCompletion() {
        List<CompletedCandleEvent> completedEvents = new ArrayList<>();
        BollingerHaCandleBuilder builder = new BollingerHaCandleBuilder("12345", "NIFTY26OCT25950CE", completedEvents::add);

        // Minute 09:15:00 to 09:15:59 ticks
        Instant t1 = Instant.parse("2026-10-02T03:45:05Z"); // 09:15:05 IST
        builder.onTick(new BigDecimal("150.0"), 50L, t1);
        builder.onTick(new BigDecimal("155.0"), 50L, t1.plusSeconds(10));
        builder.onTick(new BigDecimal("148.0"), 50L, t1.plusSeconds(20));
        builder.onTick(new BigDecimal("152.0"), 50L, t1.plusSeconds(40));

        // Minute 09:16:01 tick triggers completion of 09:15 minute candle
        Instant t2 = Instant.parse("2026-10-02T03:46:01Z");
        builder.onTick(new BigDecimal("153.0"), 50L, t2);

        assertEquals(1, completedEvents.size());
        Candle c = completedEvents.get(0).candle();
        assertEquals(new BigDecimal("150.0"), c.open());
        assertEquals(new BigDecimal("155.0"), c.high());
        assertEquals(new BigDecimal("148.0"), c.low());
        assertEquals(new BigDecimal("152.0"), c.close());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.bollingerha.feeder.BollingerHaCandleBuilderTest`
Expected: FAIL (class not found)

- [ ] **Step 3: Implement `BollingerHaCandleBuilder` and `ShoonyaHybridDataFeeder`**

Implement minute boundary grouping, OHLC tick accumulation, and `ShoonyaHybridDataFeeder` (with Java 21 WebSocket client to Shoonya `NorenWSTP` and REST `ShoonyaMarketDataService` polling fallback on socket error).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.strategy.bollingerha.feeder.BollingerHaCandleBuilderTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/bollingerha/feeder/ src/main/java/com/tradingbot/strategy/bollingerha/model/CompletedCandleEvent.java src/test/java/com/tradingbot/strategy/bollingerha/feeder/
git commit -m "feat(bollinger-ha): implement 1-minute candle aggregator and hybrid feeder"
```

---

### Task 5: Intraday Strategy Engine & State Machine

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/bollingerha/service/BollingerHaIntradayEngine.java`
- Test: `src/test/java/com/tradingbot/strategy/bollingerha/service/BollingerHaIntradayEngineTest.java`

**Interfaces:**
- Consumes: `BollingerHaProperties`, `ReactiveSignalEventBus`, `CompletedCandleEvent`
- Produces: `TradeSignal` emissions (`BUY`/`ENTRY_LONG`, `PARTIAL_EXIT_LONG`, `UPDATE_STOP_LOSS`, `EXIT_LONG`, `SQUARE_OFF`), state getters

- [ ] **Step 1: Write the failing test**

```java
package com.tradingbot.strategy.bollingerha.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tradingbot.bus.ReactiveSignalEventBus;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import com.tradingbot.strategy.bollingerha.config.BollingerHaProperties;
import com.tradingbot.strategy.bollingerha.model.BollingerHaDailyState;
import com.tradingbot.strategy.bollingerha.model.BollingerHaPosition;
import com.tradingbot.strategy.bollingerha.model.CompletedCandleEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BollingerHaIntradayEngineTest {

    private ReactiveSignalEventBus eventBus;
    private BollingerHaProperties properties;
    private BollingerHaIntradayEngine engine;

    @BeforeEach
    void setUp() {
        eventBus = mock(ReactiveSignalEventBus.class);
        properties = new BollingerHaProperties();
        engine = new BollingerHaIntradayEngine(properties, eventBus);
    }

    @Test
    void testLowerBandBounceTriggersEntrySignalAndRiskFilter() {
        String token = "12345";
        String symbol = "NIFTY26OCT25950CE";
        engine.initStrike("CE", token, symbol);

        // Feed 20 candles to warm up Bollinger Bands
        Instant baseTime = Instant.parse("2026-10-02T03:45:00Z");
        for (int i = 0; i < 20; i++) {
            Candle c = new Candle(baseTime.plusSeconds(i * 60), new BigDecimal("150"), new BigDecimal("152"), new BigDecimal("148"), new BigDecimal("150"), 1000L);
            engine.onCandleCompleted(new CompletedCandleEvent(token, symbol, c, baseTime.plusSeconds(i * 60)));
        }

        // Candle 21: Low dips sharply touching lower band
        Candle touchCandle = new Candle(baseTime.plusSeconds(20 * 60), new BigDecimal("145"), new BigDecimal("146"), new BigDecimal("130"), new BigDecimal("132"), 2000L);
        engine.onCandleCompleted(new CompletedCandleEvent(token, symbol, touchCandle, baseTime.plusSeconds(20 * 60)));

        // Candle 22: Green HA reversal candle (High = 138, Low = 131) -> Risk = (138+1) - (131-1) = 139 - 130 = 9 pts <= 20 pts
        Candle revCandle = new Candle(baseTime.plusSeconds(21 * 60), new BigDecimal("132"), new BigDecimal("138"), new BigDecimal("131"), new BigDecimal("137"), 2500L);
        engine.onCandleCompleted(new CompletedCandleEvent(token, symbol, revCandle, baseTime.plusSeconds(21 * 60)));

        ArgumentCaptor<TradeSignal> captor = ArgumentCaptor.forClass(TradeSignal.class);
        verify(eventBus, atLeastOnce()).publish(captor.capture());
        
        TradeSignal sig = captor.getValue();
        assertEquals(SignalAction.ENTRY_LONG, sig.action());
        assertEquals(new BigDecimal("139.00"), sig.price());
        assertEquals(new BigDecimal("130.00"), sig.stopLoss());
        assertEquals(new BigDecimal("157.00"), sig.targetPrice()); // Entry + 2 * (139 - 130) = 139 + 18 = 157
    }

    @Test
    void testMaxSlFilterRejectsOversizedCandle() {
        String token = "12345";
        String symbol = "NIFTY26OCT25950CE";
        engine.initStrike("CE", token, symbol);

        Instant baseTime = Instant.parse("2026-10-02T03:45:00Z");
        for (int i = 0; i < 20; i++) {
            Candle c = new Candle(baseTime.plusSeconds(i * 60), new BigDecimal("150"), new BigDecimal("152"), new BigDecimal("148"), new BigDecimal("150"), 1000L);
            engine.onCandleCompleted(new CompletedCandleEvent(token, symbol, c, baseTime.plusSeconds(i * 60)));
        }

        // Touch candle
        Candle touchCandle = new Candle(baseTime.plusSeconds(20 * 60), new BigDecimal("145"), new BigDecimal("146"), new BigDecimal("100"), new BigDecimal("105"), 2000L);
        engine.onCandleCompleted(new CompletedCandleEvent(token, symbol, touchCandle, baseTime.plusSeconds(20 * 60)));

        // Reversal candle with huge range: High = 150, Low = 105 -> Risk = 151 - 104 = 47 pts > 20 pts
        Candle bigCandle = new Candle(baseTime.plusSeconds(21 * 60), new BigDecimal("105"), new BigDecimal("150"), new BigDecimal("105"), new BigDecimal("148"), 2500L);
        engine.onCandleCompleted(new CompletedCandleEvent(token, symbol, bigCandle, baseTime.plusSeconds(21 * 60)));

        verify(eventBus, never()).publish(any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.bollingerha.service.BollingerHaIntradayEngineTest`
Expected: FAIL (class not found)

- [ ] **Step 3: Implement `BollingerHaIntradayEngine`**

Implement complete signal evaluation, lower band touch detection within 3 candles, Green HA reversal validation, 20-point max SL filter, 1:2 R:R calculation, partial booking (50% exit) and Cost SL trail on target hit, SL exit and opposite-strike flip, 2-trade daily cap, and morning 10:30 AM entry cutoff.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.strategy.bollingerha.service.BollingerHaIntradayEngineTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/bollingerha/service/BollingerHaIntradayEngine.java src/test/java/com/tradingbot/strategy/bollingerha/service/
git commit -m "feat(bollinger-ha): implement intraday strategy engine and state machine"
```

---

### Task 6: Scheduler, REST Controller & Telegram Alerts

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/bollingerha/scheduler/BollingerHaScheduler.java`
- Create: `src/main/java/com/tradingbot/strategy/bollingerha/controller/BollingerHaController.java`
- Test: `src/test/java/com/tradingbot/strategy/bollingerha/controller/BollingerHaControllerTest.java`

**Interfaces:**
- Consumes: `BollingerHaIntradayEngine`, `BollingerHaStrikeSelector`, `ShoonyaHybridDataFeeder`, `TelegramService`
- Produces: Scheduled daily routines (09:07, 09:15, 10:30, 15:15) and REST endpoints (`/api/v1/strategy/bollinger-ha/status`, `/start`, `/stop`, `/reset`, `/simulate-candle`)

- [ ] **Step 1: Write the failing test**

```java
package com.tradingbot.strategy.bollingerha.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradingbot.strategy.bollingerha.service.BollingerHaIntradayEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class BollingerHaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BollingerHaIntradayEngine engine;

    @Test
    void testGetStatusEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/strategy/bollinger-ha/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.strategy.bollingerha.controller.BollingerHaControllerTest`
Expected: FAIL (controller / endpoint 404)

- [ ] **Step 3: Implement `BollingerHaScheduler` and `BollingerHaController`**

Implement Spring `@Scheduled` cron jobs for pre-market strike selection (09:07 IST), stream initialization (09:15 IST), morning entry cutoff (10:30 IST), and auto square-off (15:15 IST). Expose REST management endpoints and dispatch Telegram notifications on state changes.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.strategy.bollingerha.controller.BollingerHaControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/bollingerha/scheduler/ src/main/java/com/tradingbot/strategy/bollingerha/controller/ src/test/java/com/tradingbot/strategy/bollingerha/controller/
git commit -m "feat(bollinger-ha): add strategy scheduler, rest controller and telegram alerts"
```

---

### Task 7: End-to-End System Integration Test

**Files:**
- Create: `src/test/java/com/tradingbot/strategy/bollingerha/BollingerHaIntegrationTest.java`

**Interfaces:**
- Consumes: All components from Tasks 1–6, `ReactiveSignalEventBus`, `ShoonyaTradeConsumer`

- [ ] **Step 1: Write the integration test**

```java
package com.tradingbot.strategy.bollingerha;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.bus.ReactiveSignalEventBus;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import com.tradingbot.strategy.bollingerha.config.BollingerHaProperties;
import com.tradingbot.strategy.bollingerha.model.CompletedCandleEvent;
import com.tradingbot.strategy.bollingerha.service.BollingerHaIntradayEngine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class BollingerHaIntegrationTest {

    @Autowired
    private ReactiveSignalEventBus signalBus;

    @Autowired
    private BollingerHaProperties properties;

    @Test
    void testEndToEndSignalPipeline() {
        List<TradeSignal> signalsReceived = new ArrayList<>();
        signalBus.getSignalStream().subscribe(signalsReceived::add);

        BollingerHaIntradayEngine engine = new BollingerHaIntradayEngine(properties, signalBus);
        String token = "99001";
        String symbol = "NIFTY26OCT25950CE";
        engine.initStrike("CE", token, symbol);

        Instant t0 = Instant.parse("2026-10-02T03:45:00Z");
        for (int i = 0; i < 20; i++) {
            engine.onCandleCompleted(new CompletedCandleEvent(token, symbol,
                new Candle(t0.plusSeconds(i * 60), new BigDecimal("100"), new BigDecimal("102"), new BigDecimal("98"), new BigDecimal("100"), 1000L),
                t0.plusSeconds(i * 60)));
        }

        // Lower band dip
        engine.onCandleCompleted(new CompletedCandleEvent(token, symbol,
            new Candle(t0.plusSeconds(20 * 60), new BigDecimal("95"), new BigDecimal("96"), new BigDecimal("80"), new BigDecimal("82"), 1500L),
            t0.plusSeconds(20 * 60)));

        // Reversal green candle (High = 88, Low = 81) -> Entry = 89, SL = 80, Risk = 9 pts
        engine.onCandleCompleted(new CompletedCandleEvent(token, symbol,
            new Candle(t0.plusSeconds(21 * 60), new BigDecimal("82"), new BigDecimal("88"), new BigDecimal("81"), new BigDecimal("87"), 2000L),
            t0.plusSeconds(21 * 60)));

        assertFalse(signalsReceived.isEmpty());
        TradeSignal entrySig = signalsReceived.get(0);
        assertEquals(SignalAction.ENTRY_LONG, entrySig.action());
        assertEquals("NIFTY26OCT25950CE", entrySig.tradingSymbol());
        assertEquals(new BigDecimal("89.00"), entrySig.price());
    }
}
```

- [ ] **Step 2: Run integration test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.strategy.bollingerha.BollingerHaIntegrationTest`
Expected: PASS

- [ ] **Step 3: Run full spotless and build verification**

Run: `./gradlew spotlessApply check test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/tradingbot/strategy/bollingerha/BollingerHaIntegrationTest.java
git commit -m "test(bollinger-ha): add full end-to-end integration test"
```
