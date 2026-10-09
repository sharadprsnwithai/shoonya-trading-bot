# Implementation Plan: Fix LVR Broker Execution, Stop-Loss Lifecycle & Strategy Logic (Issues 1 & 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix resting broker stop-loss lifecycle (cancel on exit, adjust on partial exit, compliant Option SL limit orders, dynamic exchange resolution) and strategy engine logic (Setup 2 vs 3 precedence, retreating wick entry guard, persistent broker symbols).

**Architecture:** Extend `AbstractTradeExecutionConsumer` with protective SL order tracking, auto-cancellation on EXITs, partial-exit SL replacement, and Option `OrderType.SL` limit buffer. Refine `LowestVolumeReversalService` and `LowestVolumePaperPosition` with setup precedence and persistent broker contract symbols.

**Tech Stack:** Java 21, Spring Boot 3, Gradle, JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-10-03-fix-lvr-broker-execution-and-strategy-logic-design.md`

## Global Constraints

- Protective stop-loss orders on the broker exchange must be cancelled immediately whenever a position is closed or exited.
- Options protective stop-loss orders must use `OrderType.SL` with a limit price buffer (never `SL_MKT`).
- Dynamic exchange resolution must support `"NFO"`, `"NSE"`, `"BFO"`, and `"BSE"`.
- Setup 2 (LVR low volume pullback) takes precedence over Setup 3 (Vande Bharat inside-bar) when both patterns form on the same 5-minute candle.

## Review Focus

1. **Resting SL Cancellation on Target 1 / EOD Exit:** Verify `gateway.cancelOrder(slOrderId)` is called when an EXIT signal is processed.
2. **50% Partial Exit SL Adjustment:** Verify the old 100% SL order is cancelled and a new 50% SL order is placed at Cost SL for the remaining runner.
3. **Option SL Order Type & Limit Price:** Verify Option signals place `OrderType.SL` with `limitPrice = triggerPrice * 0.90` (for Sell SL on Long Option).
4. **Setup Precedence on Dual-Pattern Bar:** Verify that a candle with lowest volume and inside-bar characteristics retains the Setup 2 trigger price at the pullback high.
5. **Persistent Symbol at Exit Time:** Verify that the exit order trading symbol matches the entry order symbol exactly.

---

### Task 1: Protective Stop-Loss Order Tracking & Cancellation on Exit (1.1)

**Files:**
- Modify: `src/main/java/com/tradingbot/execution/consumer/AbstractTradeExecutionConsumer.java`
- Test: `src/test/java/com/tradingbot/execution/consumer/ShoonyaTradeConsumerTest.java`

- [ ] **Step 1: Write the failing test for cancelling resting SL order on exit**

In `src/test/java/com/tradingbot/execution/consumer/ShoonyaTradeConsumerTest.java`:
```java
@Test
@DisplayName("1.1 Fix: Consumer cancels resting protective SL order when position EXIT signal is processed")
void testCancelsRestingProtectiveSlOnExit() {
    BrokerOrderGateway gateway = mock(BrokerOrderGateway.class);
    when(gateway.placeOrder(any())).thenReturn(
            new OrderResponse(true, "ENTRY-101", OrderStatus.COMPLETE, "Entry filled", "RELIANCE", 100, BigDecimal.valueOf(2500)),
            new OrderResponse(true, "SL-201", OrderStatus.PENDING, "SL placed", "RELIANCE", 100, BigDecimal.valueOf(2490)),
            new OrderResponse(true, "EXIT-301", OrderStatus.COMPLETE, "Exit filled", "RELIANCE", 100, BigDecimal.valueOf(2520))
    );
    when(gateway.cancelOrder("SL-201")).thenReturn(
            new OrderResponse(true, "SL-201", OrderStatus.CANCELLED, "SL cancelled on broker", "RELIANCE", 100, BigDecimal.valueOf(2490))
    );

    ShoonyaTradeConsumer consumer = new ShoonyaTradeConsumer("shoonya-test", ExecutionMode.LIVE, 1.0, true, 30L, gateway);

    // 1. Process ENTRY
    TradeSignal entrySignal = TradeSignal.of("LOWEST_VOLUME_REVERSAL", "RELIANCE", "RELIANCE26OCTFUT",
            SignalAction.ENTRY_LONG, BigDecimal.valueOf(2500), BigDecimal.valueOf(2490), BigDecimal.valueOf(2520), 100, "Entry",
            Map.of("instrumentType", "FUTURES", "brokerStopLossPrice", BigDecimal.valueOf(2490)));
    consumer.handleLiveExecution(entrySignal, 100);

    // Verify protective SL was placed
    verify(gateway, times(1)).placeOrder(argThat(req -> req.orderType() == OrderType.SL_MKT && "SL-201".equals("SL-201")));

    // 2. Process EXIT
    TradeSignal exitSignal = TradeSignal.of("LOWEST_VOLUME_REVERSAL", "RELIANCE", "RELIANCE26OCTFUT",
            SignalAction.EXIT_LONG, BigDecimal.valueOf(2520), BigDecimal.valueOf(2500), null, 100, "Target Hit",
            Map.of("instrumentType", "FUTURES"));
    consumer.handleLiveExecution(exitSignal, 100);

    // Verify resting protective SL order was cancelled
    verify(gateway, times(1)).cancelOrder("SL-201");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.execution.consumer.ShoonyaTradeConsumerTest.testCancelsRestingProtectiveSlOnExit`
Expected: FAIL (gateway.cancelOrder was never called).

- [ ] **Step 3: Implement SL orderId tracking and auto-cancellation in `AbstractTradeExecutionConsumer.java`**

1. Add `private final Map<String, String> protectiveSlOrders = new ConcurrentHashMap<>();`.
2. In `placeBestEffortProtectiveStop`: on success, record `protectiveSlOrders.put(key, slResp.orderId())`.
3. In `placeOrderConfirmed`: on `isExitAction(signal.action())`, retrieve and remove `slOrderId = protectiveSlOrders.remove(key)`. If non-null, call `gateway.cancelOrder(slOrderId)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.execution.consumer.ShoonyaTradeConsumerTest.testCancelsRestingProtectiveSlOnExit`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/execution/consumer/AbstractTradeExecutionConsumer.java src/test/java/com/tradingbot/execution/consumer/ShoonyaTradeConsumerTest.java
git commit -m "fix(execution): track and auto-cancel resting protective stop-loss orders on position exit (1.1)"
```

---

### Task 2: Partial Exit (50% Booking) SL Quantity Adjustment (1.2)

**Files:**
- Modify: `src/main/java/com/tradingbot/execution/consumer/AbstractTradeExecutionConsumer.java`
- Test: `src/test/java/com/tradingbot/execution/consumer/ShoonyaTradeConsumerTest.java`

- [ ] **Step 1: Write the failing test for partial exit SL adjustment**

In `src/test/java/com/tradingbot/execution/consumer/ShoonyaTradeConsumerTest.java`:
```java
@Test
@DisplayName("1.2 Fix: Consumer cancels 100% SL and places updated 50% runner SL order on partial profit booking")
void testAdjustsProtectiveSlOnPartialExit() {
    BrokerOrderGateway gateway = mock(BrokerOrderGateway.class);
    when(gateway.placeOrder(any())).thenReturn(
            new OrderResponse(true, "ENTRY-101", OrderStatus.COMPLETE, "Entry filled", "RELIANCE", 200, BigDecimal.valueOf(2500)),
            new OrderResponse(true, "SL-ORIGINAL", OrderStatus.PENDING, "Original 100% SL", "RELIANCE", 200, BigDecimal.valueOf(2490)),
            new OrderResponse(true, "PARTIAL-EXIT-1", OrderStatus.COMPLETE, "50% Partial exit", "RELIANCE", 100, BigDecimal.valueOf(2520)),
            new OrderResponse(true, "SL-RUNNER-NEW", OrderStatus.PENDING, "Runner 50% Cost SL", "RELIANCE", 100, BigDecimal.valueOf(2500))
    );
    when(gateway.cancelOrder("SL-ORIGINAL")).thenReturn(
            new OrderResponse(true, "SL-ORIGINAL", OrderStatus.CANCELLED, "Cancelled original SL", "RELIANCE", 200, BigDecimal.valueOf(2490))
    );

    ShoonyaTradeConsumer consumer = new ShoonyaTradeConsumer("shoonya-test", ExecutionMode.LIVE, 1.0, true, 30L, gateway);

    // 1. Process ENTRY (200 qty)
    TradeSignal entrySignal = TradeSignal.of("LOWEST_VOLUME_REVERSAL", "RELIANCE", "RELIANCE26OCTFUT",
            SignalAction.ENTRY_LONG, BigDecimal.valueOf(2500), BigDecimal.valueOf(2490), BigDecimal.valueOf(2520), 200, "Entry",
            Map.of("instrumentType", "FUTURES", "brokerStopLossPrice", BigDecimal.valueOf(2490)));
    consumer.handleLiveExecution(entrySignal, 200);

    // 2. Process PARTIAL EXIT (100 qty booked, 100 qty runner remaining with Cost SL at 2500)
    TradeSignal partialSignal = TradeSignal.of("LOWEST_VOLUME_REVERSAL", "RELIANCE", "RELIANCE26OCTFUT",
            SignalAction.PARTIAL_EXIT_LONG, BigDecimal.valueOf(2520), BigDecimal.valueOf(2500), BigDecimal.valueOf(2520), 100, "1:2 Target Partial Booked",
            Map.of("instrumentType", "FUTURES", "brokerStopLossPrice", BigDecimal.valueOf(2500), "remainingQuantity", 100));
    consumer.handleLiveExecution(partialSignal, 100);

    // Verify original 100% SL was cancelled
    verify(gateway, times(1)).cancelOrder("SL-ORIGINAL");
    // Verify new runner SL was placed for 100 qty at Cost SL 2500
    verify(gateway, times(1)).placeOrder(argThat(req -> req.orderType() == OrderType.SL_MKT && req.quantity() == 100 && req.triggerPrice().compareTo(BigDecimal.valueOf(2500)) == 0));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.execution.consumer.ShoonyaTradeConsumerTest.testAdjustsProtectiveSlOnPartialExit`
Expected: FAIL.

- [ ] **Step 3: Implement partial exit SL adjustment in `AbstractTradeExecutionConsumer.java`**

1. Check for `SignalAction.PARTIAL_EXIT_LONG` / `SignalAction.PARTIAL_EXIT_SHORT` (or `isPartialExitAction`).
2. Cancel existing SL order from `protectiveSlOrders.remove(key)`.
3. If `signal.metadata().get("remainingQuantity") != null` or `quantity > 0`:
   Place replacement protective SL order for remaining runner quantity with updated `brokerStopLossPrice` and update `protectiveSlOrders.put(key, newSlOrderId)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.execution.consumer.ShoonyaTradeConsumerTest.testAdjustsProtectiveSlOnPartialExit`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/execution/consumer/AbstractTradeExecutionConsumer.java src/test/java/com/tradingbot/execution/consumer/ShoonyaTradeConsumerTest.java
git commit -m "fix(execution): replace and downsize protective SL order on 50% partial exit (1.2)"
```

---

### Task 3: Options `OrderType.SL` Compliance & Dynamic Exchange Resolution (1.3 & 1.4)

**Files:**
- Modify: `src/main/java/com/tradingbot/execution/consumer/AbstractTradeExecutionConsumer.java`
- Modify: `src/main/java/com/tradingbot/execution/consumer/ShoonyaTradeConsumer.java`
- Modify: `src/main/java/com/tradingbot/execution/consumer/ZerodhaTradeConsumer.java`
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Test: `src/test/java/com/tradingbot/execution/consumer/ShoonyaTradeConsumerTest.java`

- [ ] **Step 1: Write failing test for Option `OrderType.SL` limit buffer and dynamic exchange**

In `src/test/java/com/tradingbot/execution/consumer/ShoonyaTradeConsumerTest.java`:
```java
@Test
@DisplayName("1.3 & 1.4 Fix: Option protective SL uses OrderType.SL with limit price buffer and dynamic exchange")
void testOptionProtectiveSlUsesOrderTypeSlWithLimitBuffer() {
    BrokerOrderGateway gateway = mock(BrokerOrderGateway.class);
    when(gateway.placeOrder(any())).thenReturn(
            new OrderResponse(true, "OPT-ENTRY-1", OrderStatus.COMPLETE, "Filled", "SUNPHARMA26OCT1800CE", 350, BigDecimal.valueOf(50.00)),
            new OrderResponse(true, "OPT-SL-1", OrderStatus.PENDING, "SL placed", "SUNPHARMA26OCT1800CE", 350, BigDecimal.valueOf(35.00))
    );

    ShoonyaTradeConsumer consumer = new ShoonyaTradeConsumer("shoonya-test", ExecutionMode.LIVE, 1.0, true, 30L, gateway);

    TradeSignal optSignal = TradeSignal.of("LOWEST_VOLUME_REVERSAL", "SUNPHARMA", "SUNPHARMA26OCT1800CE",
            SignalAction.ENTRY_LONG, BigDecimal.valueOf(50.00), BigDecimal.valueOf(1860.00), BigDecimal.valueOf(1890.00), 350, "Option Entry",
            Map.of("instrumentType", "OPTION", "exchange", "NFO", "brokerStopLossPrice", BigDecimal.valueOf(35.00)));

    consumer.handleLiveExecution(optSignal, 350);

    // Verify Option SL is placed with OrderType.SL (NOT SL_MKT), triggerPrice = 35.00, limitPrice = 31.50 (10% execution buffer)
    verify(gateway, times(1)).placeOrder(argThat(req ->
            req.orderType() == OrderType.SL
                    && req.exchange().equals("NFO")
                    && req.triggerPrice().compareTo(BigDecimal.valueOf(35.00)) == 0
                    && req.price().compareTo(BigDecimal.valueOf(31.50)) == 0));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.execution.consumer.ShoonyaTradeConsumerTest.testOptionProtectiveSlUsesOrderTypeSlWithLimitBuffer`
Expected: FAIL.

- [ ] **Step 3: Implement Option `OrderType.SL` with limit buffer and dynamic exchange**

1. In `AbstractTradeExecutionConsumer.placeBestEffortProtectiveStop`:
   - If `isOptionSignal(signal)`:
     - `OrderType orderType = OrderType.SL;`
     - For SELL SL (Long Option): `limitPrice = roundToTick(slPrice.multiply(BigDecimal.valueOf(0.90))).max(BigDecimal.valueOf(0.05));`
     - For BUY SL (Short Option): `limitPrice = roundToTick(slPrice.multiply(BigDecimal.valueOf(1.10)));`
   - Else (Futures / Equity):
     - `OrderType orderType = OrderType.SL_MKT;`
     - `limitPrice = BigDecimal.ZERO;`
2. In `ShoonyaTradeConsumer` and `ZerodhaTradeConsumer`:
   - Resolve exchange from `signal.metadata().get("exchange")` defaulting to `"NFO"`.
3. In `LowestVolumeReversalService.publishSignal`:
   - Pass `"exchange"` in metadata map.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.execution.consumer.ShoonyaTradeConsumerTest.testOptionProtectiveSlUsesOrderTypeSlWithLimitBuffer`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/execution/consumer/AbstractTradeExecutionConsumer.java src/main/java/com/tradingbot/execution/consumer/ShoonyaTradeConsumer.java src/main/java/com/tradingbot/execution/consumer/ZerodhaTradeConsumer.java src/main/java/com/tradingbot/service/LowestVolumeReversalService.java src/test/java/com/tradingbot/execution/consumer/ShoonyaTradeConsumerTest.java
git commit -m "fix(execution): use compliant OrderType.SL for options and resolve dynamic exchange (1.3, 1.4)"
```

---

### Task 4: Strategy Engine Setup 2 vs Setup 3 Precedence (2.1)

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeCandleEngineTest.java`

- [ ] **Step 1: Write failing test for Setup 2 taking precedence over Setup 3**

In `src/test/java/com/tradingbot/service/LowestVolumeCandleEngineTest.java`:
```java
@Test
@DisplayName("2.1 Fix: Setup 2 LVR low volume pullback takes precedence when candle also satisfies Setup 3 inside bar")
void testSetup2TakesPrecedenceOverSetup3OnSameBar() {
    LowestVolumeReversalService service = new LowestVolumeReversalService(null, null, null, null, null);
    Instant t0 = Instant.parse("2026-09-18T03:45:00Z"); // 09:15 IST
    List<Candle> candles = List.of(
            Candle.of5m("RADICO", t0, BigDecimal.valueOf(1000), BigDecimal.valueOf(1010), BigDecimal.valueOf(995), BigDecimal.valueOf(1008), 50000),
            Candle.of5m("RADICO", t0.plus(5, ChronoUnit.MINUTES), BigDecimal.valueOf(1008), BigDecimal.valueOf(1015), BigDecimal.valueOf(1005), BigDecimal.valueOf(1012), 40000),
            Candle.of5m("RADICO", t0.plus(10, ChronoUnit.MINUTES), BigDecimal.valueOf(1012), BigDecimal.valueOf(1018), BigDecimal.valueOf(1010), BigDecimal.valueOf(1016), 35000), // Day lowest = 35000
            // C4: Green Mother Candle (H=1030, L=1015)
            Candle.of5m("RADICO", t0.plus(15, ChronoUnit.MINUTES), BigDecimal.valueOf(1016), BigDecimal.valueOf(1030), BigDecimal.valueOf(1015), BigDecimal.valueOf(1028), 60000),
            // C5: Red Candle inside C4 (H=1025 <= 1030, L=1018 >= 1015) AND Volume = 20000 (<= dayLowest 35000!)
            Candle.of5m("RADICO", t0.plus(20, ChronoUnit.MINUTES), BigDecimal.valueOf(1024), BigDecimal.valueOf(1025), BigDecimal.valueOf(1018), BigDecimal.valueOf(1020), 20000)
    );

    LowestVolumeSetup setup = service.evaluateCandleSequence("RADICO", LowestVolumeDirection.LONG, candles);

    // Setup 2 (LVR) should take precedence with trigger at Pullback High (1025.05), NOT Mother High (1030.05)
    assertEquals("LVR_VOLUME_PULLBACK", setup.getSetupPattern());
    assertEquals(0, BigDecimal.valueOf(1025.05).compareTo(setup.getTriggerPrice()));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeCandleEngineTest.testSetup2TakesPrecedenceOverSetup3OnSameBar`
Expected: FAIL (Setup 3 overwrote pattern to `VANDE_BHARAT_INSIDE_BAR` and trigger to `1030.05`).

- [ ] **Step 3: Implement Setup 2 precedence in `evaluateCandleSequence`**

In `LowestVolumeReversalService.java`:
```java
boolean lvrMatchedOnThisBar = false;
if (isOppositeCandle && c.volume() > 0 && c.volume() <= rollingLowest) {
    if (candleAllowed) {
        setup.setTriggerCandle(c, triggerPrc, slPrc, target1Prc);
        setup.setSetupPattern("LVR_VOLUME_PULLBACK");
        lvrMatchedOnThisBar = true;
    }
    rollingLowest = c.volume();
    setup.setDayLowestVolume(rollingLowest);
}

// 3. Setup 3: Vande Bharat (Evaluate only if Setup 2 did NOT match on this exact bar)
if (!lvrMatchedOnThisBar && i >= 1) {
    // Vande Bharat logic...
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeCandleEngineTest.testSetup2TakesPrecedenceOverSetup3OnSameBar`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/service/LowestVolumeReversalService.java src/test/java/com/tradingbot/service/LowestVolumeCandleEngineTest.java
git commit -m "fix(lvr): give Setup 2 LVR pullback precedence over Setup 3 on dual-pattern bar (2.1)"
```

---

### Task 5: Fresh-Wick Retreat Guard & Persistent Broker Trading Symbol (2.2 & 2.3)

**Files:**
- Modify: `src/main/java/com/tradingbot/model/strategy/LowestVolumePaperPosition.java`
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

- [ ] **Step 1: Write failing tests for retreating wick guard and persistent symbol**

In `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`:
```java
@Test
@DisplayName("2.2 Fix: Fresh-wick does NOT trigger entry if live spot has retreated below trigger price")
void testFreshWickDoesNotTriggerOnRetreatingPrice() {
    LowestVolumeSetup setup = new LowestVolumeSetup("SUNPHARMA", LowestVolumeDirection.LONG);
    setup.setTriggerCandle(
            Candle.of5m("SUNPHARMA", Instant.now(), BigDecimal.valueOf(1870), BigDecimal.valueOf(1875), BigDecimal.valueOf(1865), BigDecimal.valueOf(1872), 5000),
            BigDecimal.valueOf(1875.05), BigDecimal.valueOf(1864.95), BigDecimal.valueOf(1895.25));
    setup.setSessionHighAtArming(1874.00);
    setup.setLatestVwap(1870.00);
    setup.setPdh(BigDecimal.valueOf(1870.00));
    setup.setPdl(BigDecimal.valueOf(1850.00));
    setup.transitionTo(LowestVolumeSetupState.TRIGGER_ARMED, "Armed trigger");
    service.getActiveSetups().put("SUNPHARMA", setup);

    // Session high advanced to 1876.00 (touched trigger), but spot has now pulled back to 1872.00 (below trigger 1875.05)
    ObjectMapper mapper = new ObjectMapper();
    when(marketDataService.fetchQuote(any(), any()))
            .thenReturn(mapper.createObjectNode().put("lp", "1872.00").put("ap", "1870.00").put("h", "1876.00").put("l", "1868.00"));
    when(marketDataService.resolveToken("SUNPHARMA")).thenReturn("3351");

    service.evaluateLivePriceActions();

    // Entry must be skipped while spot is retreating below trigger
    assertThat(service.getOpenPositions()).doesNotContainKey("SUNPHARMA");
    assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalServiceTest.testFreshWickDoesNotTriggerOnRetreatingPrice`
Expected: FAIL.

- [ ] **Step 3: Implement retreating price check and persistent broker trading symbol**

1. In `LowestVolumePaperPosition.java`:
   - Add `private final String brokerTradingSymbol;` (and update constructors / serialization).
2. In `LowestVolumeReversalService.java`:
   - When creating `LowestVolumePaperPosition`, pass `brokerTradingSymbol`.
   - In `resolveBrokerTradingSymbol(pos)`:
     `if (pos.getBrokerTradingSymbol() != null && !pos.getBrokerTradingSymbol().isBlank()) return pos.getBrokerTradingSymbol();`
3. In `checkSpotTriggerBreach()`:
   - For fresh-wick Long breach: require `spotPrice.compareTo(setup.getTriggerPrice()) >= 0`. If `spotPrice < triggerPrice`, do not set `triggered = true`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.service.LowestVolumeReversalServiceTest.testFreshWickDoesNotTriggerOnRetreatingPrice`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/model/strategy/LowestVolumePaperPosition.java src/main/java/com/tradingbot/service/LowestVolumeReversalService.java src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java
git commit -m "fix(lvr): guard retreating fresh-wicks and persist broker trading symbols on positions (2.2, 2.3)"
```

---

### Task 6: Full System Verification

**Files:**
- Test: All tests in `src/test/java`

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test`
Expected: 100% test pass with zero regressions.

- [ ] **Step 2: Commit final baseline**

```bash
git commit --allow-empty -m "chore(lvr): verify complete test suite after broker execution and logic fixes"
```
