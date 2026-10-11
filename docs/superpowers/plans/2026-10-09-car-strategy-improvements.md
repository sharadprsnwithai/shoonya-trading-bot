# CAR Strategy Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 16 identified bugs and improvement items in the CAR Weekly GTT strategy — covering candidate ranking, per-symbol cap, drawdown circuit breaker, sell GTT buffer, field-name correction, Telegram accuracy, concurrent-map consistency, map exposure, health endpoint, and report completeness.

**Architecture:** All changes are contained within `src/main/java/com/tradingbot/strategy/car/` and its test mirror. No new Spring beans are introduced; new behaviour is enabled through `CarWeeklyProperties` config knobs so nothing breaks in production without opt-in.

**Tech Stack:** Java 21, Spring Boot 3.3.5, JUnit 5, Mockito, AssertJ.

**Spec:** Analysis enumerated in the brainstorming session (16 items, numbered 1–16).

---

## Global Constraints

- Java 21, Spring Boot 3.3.5, Spotless Google Java Format, zero SpotBugs errors, 100 % test pass.
- Every new config key lives under `trading-bot.strategy.car-weekly.*`.
- No changes to existing GTT gateway interfaces or broker-facing call sites — only the service and properties layers change.
- Existing tests must continue to pass unchanged (except where the test itself is the fix).

## Review Focus

1. **Drawdown halts buys but never cancels existing pending buy GTTs** — confirm cancelBuyGttsForNonPositive is not called for the drawdown path, only new placements are blocked.
2. **`maxUnitsPerStock = 1` (default) must not prevent the accumulation-on-fill path** — the cap only guards *new buy GTT placements*, not `addFill()` triggered by actual broker fills.
3. **Ranking sort is stable** — two stocks with equal streak and equal daysSinceAnchor keep their original scan order.
4. **Sell GTT limit = target − 0.05 still rounds to a valid NSE tick** — verified in the trigger generator rounding helper.
5. **`getHoldings()` and `getGttOrders()` returning unmodifiable views does not break Jackson serialisation of `CarPortfolioState`** — Jackson must still be able to read the maps via the setters or the concrete type.

---

## Task 1 — Rank CAR-positive candidates before placement

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/car/CarWeeklyGttService.java`
- Modify: `src/test/java/com/tradingbot/strategy/car/CarWeeklyGttServiceTest.java`

**Interfaces:**
- Consumes: `ScanResult.results()` — `List<CarAnalysisResult>` (already available)
- Produces: same list, sorted by `consecutivePositiveDays` DESC, then `daysSinceAnchor` ASC before being handed to `reconcileBuyGtts`

- [ ] **Step 1: Add a ranking assertion to `CarWeeklyGttServiceTest`**

Add `testCandidatesAreRankedByStreakThenFreshnessBeforePlacement()`: mock `ohlcService` to return candle lists for three symbols (ALPHA streak=9, BETA streak=7, GAMMA streak=9 but `daysSinceAnchor` larger); capture the order `placeGtt` is called and assert ALPHA before GAMMA before BETA.

- [ ] **Step 2: Run test — expect FAIL**

`./gradlew test --tests "com.tradingbot.strategy.car.CarWeeklyGttServiceTest.testCandidatesAreRankedByStreak*" --rerun`

- [ ] **Step 3: In `CarWeeklyGttService.scanUniverse()`, sort `positives` before returning**

```java
positives.sort(Comparator
    .comparingInt(CarAnalysisResult::consecutivePositiveDays).reversed()
    .thenComparingInt(CarAnalysisResult::daysSinceAnchor));
```

- [ ] **Step 4: Run test — expect PASS**

`./gradlew test --tests "com.tradingbot.strategy.car.CarWeeklyGttServiceTest" --rerun`

- [ ] **Step 5: Commit**

`git commit -am "feat(car): rank CAR-positive candidates by streak desc, daysSinceAnchor asc"`

---

## Task 2 — Per-symbol accumulation cap

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/car/config/CarWeeklyProperties.java`
- Modify: `src/main/java/com/tradingbot/strategy/car/CarWeeklyGttService.java`
- Modify: `src/test/java/com/tradingbot/strategy/car/CarWeeklyGttServiceTest.java`

**Interfaces:**
- Produces: `CarWeeklyProperties.getMaxUnitsPerStock(): int` (default 3); `reconcileBuyGttFor` skips if `holding.accumulatedUnits() >= maxUnitsPerStock`

- [ ] **Step 1: Add `testMaxUnitsPerStockPreventsFurtherBuyGtts()` to `CarWeeklyGttServiceTest`**

Set `props.setMaxUnitsPerStock(2)`, add a holding for RELIANCE with `accumulatedUnits = 2`, make RELIANCE CAR-positive, run the routine, assert `placeGtt` is never called for RELIANCE.

- [ ] **Step 2: Run test — expect FAIL**

`./gradlew test --tests "com.tradingbot.strategy.car.CarWeeklyGttServiceTest.testMaxUnits*" --rerun`

- [ ] **Step 3: Add field to `CarWeeklyProperties`**

```java
private int maxUnitsPerStock = 3;
// + getter/setter
```

- [ ] **Step 4: Guard in `reconcileBuyGttFor`**

At the top of the method, after retrieving `existing`:

```java
CarHolding existingHolding = portfolioState.getHoldings().get(candidate.symbol());
if (existingHolding != null
        && existingHolding.accumulatedUnits() >= properties.getMaxUnitsPerStock()) {
    log.info("[CAR-WEEKLY] {} already at max {} units. Skipping new buy GTT.",
            candidate.symbol(), properties.getMaxUnitsPerStock());
    return;
}
```

- [ ] **Step 5: Run test — expect PASS**

`./gradlew test --tests "com.tradingbot.strategy.car.CarWeeklyGttServiceTest" --rerun`

- [ ] **Step 6: Commit**

`git commit -am "feat(car): add maxUnitsPerStock cap to prevent unlimited accumulation"`

---

## Task 3 — Portfolio drawdown circuit breaker

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/car/config/CarWeeklyProperties.java`
- Modify: `src/main/java/com/tradingbot/strategy/car/CarWeeklyGttService.java`
- Modify: `src/test/java/com/tradingbot/strategy/car/CarWeeklyGttServiceTest.java`

**Interfaces:**
- Produces: `CarWeeklyProperties.getMaxDrawdownPct(): double` (default 30.0); new private `isDrawdownBreached()` in service; when breached, new buy GTTs are skipped and a Telegram alert fires

- [ ] **Step 1: Add `testDrawdownCircuitBreakerBlocksNewBuys()` to `CarWeeklyGttServiceTest`**

Set initial capital 1_000_000, set `maxDrawdownPct = 20`, set total capital to 790_000 (21 % down), make one stock CAR-positive, run routine with `force=true`, assert `placeGtt` is never called for a BUY.

- [ ] **Step 2: Run test — expect FAIL**

- [ ] **Step 3: Add field to `CarWeeklyProperties`**

```java
private double maxDrawdownPct = 30.0;
// + getter/setter
```

- [ ] **Step 4: Add `isDrawdownBreached()` to `CarWeeklyGttService` and call it in `reconcileBuyGtts`**

```java
private boolean isDrawdownBreached() {
    BigDecimal initial = portfolioState.getInitialCapital();
    if (initial == null || initial.signum() <= 0) return false;
    BigDecimal drawdown = initial.subtract(portfolioState.getTotalCapital())
            .divide(initial, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
    return drawdown.compareTo(BigDecimal.valueOf(properties.getMaxDrawdownPct())) > 0;
}
```

In `reconcileBuyGtts`, before the candidate loop:

```java
if (isDrawdownBreached()) {
    log.warn("[CAR-WEEKLY] Drawdown breached {:.1f}% threshold. Halting new buy placements.",
            properties.getMaxDrawdownPct());
    alert("[CAR] ⚠️ Drawdown circuit breaker active — no new buys this week.");
    return;
}
```

- [ ] **Step 5: Run test — expect PASS**

`./gradlew test --tests "com.tradingbot.strategy.car.CarWeeklyGttServiceTest" --rerun`

- [ ] **Step 6: Commit**

`git commit -am "feat(car): add portfolio drawdown circuit breaker for new buy placements"`

---

## Task 4 — Sell GTT limit price buffer

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/car/CarWeeklyGttService.java`
- Modify: `src/test/java/com/tradingbot/strategy/car/CarWeeklyGttServiceTest.java`

**Interfaces:**
- Change: in `placeOrRefreshSellTarget`, `limitPrice = target - 0.05` (rounded to NSE tick via the existing rounding helper in `CarWeeklyTriggerGenerator`)
- The trigger price stays at `target` (exact); only the limit drops by one tick

- [ ] **Step 1: Add `testSellGttLimitPriceIsOneBelowTarget()` to `CarWeeklyGttServiceTest`**

Add a holding (100 shares @ 1000, target = 1062.80), run routine, capture `placeGtt` argument and assert `triggerPrice == 1062.80` and `limitPrice == 1062.75` (target − 0.05).

- [ ] **Step 2: Run test — expect FAIL**

- [ ] **Step 3: In `placeOrRefreshSellTarget`, compute limit price**

```java
BigDecimal limitPrice = roundToNseTick(target.subtract(new BigDecimal("0.05")));
```

Add private helper in `CarWeeklyGttService`:

```java
private static BigDecimal roundToNseTick(BigDecimal val) {
    double rounded = Math.round(val.doubleValue() / 0.05) * 0.05;
    return BigDecimal.valueOf(rounded).setScale(2, RoundingMode.HALF_UP);
}
```

Pass `limitPrice` (not `target`) as the second price argument to `CarGttOrder`.

- [ ] **Step 4: Update `upToDate` check** — include `existing.limitPrice().compareTo(limitPrice) == 0`

- [ ] **Step 5: Run test — expect PASS**

`./gradlew test --tests "com.tradingbot.strategy.car.CarWeeklyGttServiceTest" --rerun`

- [ ] **Step 6: Commit**

`git commit -am "fix(car): sell GTT limit price is target − 0.05 to survive fast-market gaps"`

---

## Task 5 — Fix `fiftyTwoWeekHighClose` field name to match anchor-on-high semantics

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/car/model/CarAnalysisResult.java`
- Modify: `src/main/java/com/tradingbot/strategy/car/CarCalculator.java`
- Modify: `src/test/java/com/tradingbot/strategy/car/CarCalculatorTest.java`

**Interfaces:**
- Rename record component `fiftyTwoWeekHighClose` → `fiftyTwoWeekAnchorHigh` (the value is the candle's `.high()`, not `.close()`)
- 8-arg compact constructor that sets `lastWeekHigh = latestClose` remains unchanged

- [ ] **Step 1: Rename in `CarAnalysisResult`**

Both the full constructor and the compact 8-arg one; update `Javadoc`.

- [ ] **Step 2: Rename all call-sites in `CarCalculator` and tests**

`CarCalculatorTest` currently asserts `result.fiftyTwoWeekHighClose()` — rename to `result.fiftyTwoWeekAnchorHigh()`.

- [ ] **Step 3: Run full CAR test suite**

`./gradlew test --tests "com.tradingbot.strategy.car.*" --rerun`
Expected: PASS

- [ ] **Step 4: Commit**

`git commit -am "refactor(car): rename fiftyTwoWeekHighClose to fiftyTwoWeekAnchorHigh (anchor is the candle high)"`

---

## Task 6 — Use stored order quantity in Telegram report

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/car/CarWeeklyGttService.java`

**Interfaces:**
- In `sendSundayTelegramReport`, look up the stored `CarGttOrder` for each symbol and use `order.quantity()` if present; fall back to the re-derived calculation only when no order exists yet (first-run race)

- [ ] **Step 1: Replace the inline trigQty calculation in `sendSundayTelegramReport`**

```java
CarGttOrder placedOrder = portfolioState.getGttOrders().get(c.symbol());
int trigQty = (placedOrder != null && placedOrder.quantity() > 0)
        ? placedOrder.quantity()
        : /* existing fallback ceil calculation */;
```

- [ ] **Step 2: Run tests**

`./gradlew test --tests "com.tradingbot.strategy.car.*" --rerun`
Expected: PASS (no behaviour change detectable by existing tests; verified by code inspection)

- [ ] **Step 3: Add "…and N more" line to report**

After the top-10 loop:

```java
if (carPositives.size() > 10) {
    sb.append(String.format("  _…and %d more candidates not shown_\\n", carPositives.size() - 10));
}
```

- [ ] **Step 4: Commit**

`git commit -am "fix(car): telegram report uses stored order qty; shows overflow candidate count"`

---

## Task 7 — Fix ConcurrentHashMap / synchronized inconsistency

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/car/model/CarPortfolioState.java`

**Interfaces:**
- Change map type from `ConcurrentHashMap` → `LinkedHashMap` (iteration order is deterministic now)
- `getHoldings()` and `getGttOrders()` return `Collections.unmodifiableMap(...)` views
- Add package-private `getMutableHoldings()` and `getMutableGttOrders()` for the service's internal mutation (or keep mutation only through the dedicated mutating methods and add `removeGttOrder` / `putGttOrder` helpers)

- [ ] **Step 1: Add helpers to `CarPortfolioState`**

```java
public synchronized void putGttOrder(String key, CarGttOrder order) { gttOrders.put(key, order); }
public synchronized void removeGttOrder(String key) { gttOrders.remove(key); }
```

- [ ] **Step 2: Change field declarations**

```java
private Map<String, CarHolding> holdings = new LinkedHashMap<>();
private Map<String, CarGttOrder> gttOrders = new LinkedHashMap<>();
```

Wrap both getters in `Collections.unmodifiableMap(...)`.

- [ ] **Step 3: Update `CarWeeklyGttService`** — replace all `portfolioState.getGttOrders().put/remove(...)` with `portfolioState.putGttOrder(...)`/`portfolioState.removeGttOrder(...)`. The service already calls `getGttOrders()` purely for reads in many places — those are fine with the unmodifiable view.

- [ ] **Step 4: Verify Jackson serialisation still works**

`CarPortfolioState` is serialised/deserialised by `objectMapper.readValue(f, CarPortfolioState.class)`. Jackson uses the setters (`setHoldings`, `setGttOrders`) which receive the mutable map from the JSON — the field itself gets replaced, so the `unmodifiableMap` wrapper is on the *returned* map, not on the field. Confirm by running all tests.

- [ ] **Step 5: Run tests**

`./gradlew test --tests "com.tradingbot.strategy.car.*" --rerun`
Expected: PASS

- [ ] **Step 6: Commit**

`git commit -am "refactor(car): replace ConcurrentHashMap with synchronized LinkedHashMap; expose unmodifiable views"`

---

## Task 8 — Health endpoint + portfolio milestone alerts

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/car/controller/CarWeeklyController.java`
- Modify: `src/main/java/com/tradingbot/strategy/car/CarWeeklyGttService.java`
- Modify: `src/test/java/com/tradingbot/strategy/car/controller/CarWeeklyControllerTest.java`

**Interfaces:**
- `GET /api/v1/car/health` → `Map<String,Object>` with keys `status`, `lastRunWeek`, `availableUnits`, `totalParts`, `capital`, `gatewayBroker`
- Alert fires in `runSundayWeeklyRoutine` when `portfolioState.getAvailableUnits() == 0` (fully invested)
- Alert fires when total capital increased by ≥ 10 % above initial (milestone)

- [ ] **Step 1: Add `testHealthEndpointReturnsExpectedFields()` to `CarWeeklyControllerTest`**

MockMvc GET `/api/v1/car/health`, assert 200, body contains `status`, `capital`, `availableUnits`.

- [ ] **Step 2: Add the endpoint to `CarWeeklyController`**

```java
@GetMapping("/health")
public ResponseEntity<Map<String, Object>> health() {
    CarPortfolioState s = carService.getPortfolioState();
    return ResponseEntity.ok(Map.of(
            "status", "ok",
            "lastRunWeek", String.valueOf(s.getLastRunWeek()),
            "availableUnits", s.getAvailableUnits(),
            "totalParts", s.getNumParts(),
            "capital", s.getTotalCapital(),
            "gatewayBroker", String.valueOf(carService.resolveGatewayBrokerName())));
}
```

- [ ] **Step 3: Add milestone alert in `CarWeeklyGttService.runSundayWeeklyRoutine`**

After step 7 (buy GTTs):

```java
if (portfolioState.getAvailableUnits() == 0) {
    alert("[CAR] 📊 Portfolio fully deployed — all " + properties.getNumParts() + " units allocated.");
}
BigDecimal initial = portfolioState.getInitialCapital();
if (initial != null && initial.signum() > 0) {
    BigDecimal gainPct = portfolioState.getTotalCapital()
            .subtract(initial)
            .divide(initial, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
    if (gainPct.compareTo(BigDecimal.TEN) >= 0) {
        alert(String.format("[CAR] 🎉 Portfolio up %.1f%% from initial capital!", gainPct.doubleValue()));
    }
}
```

- [ ] **Step 4: Run tests**

`./gradlew test --tests "com.tradingbot.strategy.car.*" --rerun`
Expected: PASS

- [ ] **Step 5: Commit**

`git commit -am "feat(car): add /health endpoint and portfolio milestone alerts"`

---

## Task 9 — Fix `loadState` PostConstruct threading and expose `requiredIncreases` in result

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/car/CarWeeklyGttService.java`
- Modify: `src/main/java/com/tradingbot/strategy/car/model/CarAnalysisResult.java`
- Modify: `src/main/java/com/tradingbot/strategy/car/CarCalculator.java`

**Interfaces:**
- Move `loadState()` call into the constructor (before the constructor returns) and remove `@PostConstruct init()`; add a comment explaining why
- Add `int requiredIncreases` component to `CarAnalysisResult` (appended last; 8-arg compact constructor keeps defaulting it to `Math.max(1, requiredPositiveDays - 1)`)

- [ ] **Step 1: Move `loadState()` into end of constructor in `CarWeeklyGttService`**

Remove `@PostConstruct void init()`. Call `loadState()` as the last line of the `@Autowired` constructor. Add comment: `// Load before any Spring lifecycle callbacks can fire the scheduler`.

- [ ] **Step 2: Add `requiredIncreases` to `CarAnalysisResult`**

Append as the last record component; update both constructors in `CarCalculator.analyze()`.  
The 8-arg constructor defaults it via `Math.max(1, 10 - 1)` is not clean — instead pass it through from `CarCalculator` to `CarAnalysisResult` so the value reflects the configured `requiredPositiveDays`.

- [ ] **Step 3: Run tests**

`./gradlew test --tests "com.tradingbot.strategy.car.*" --rerun`
Expected: PASS

- [ ] **Step 4: Commit**

`git commit -am "fix(car): loadState in constructor prevents scheduler race; expose requiredIncreases in result"`

---

## Task 10 — Final Spotless + full test run

- [ ] **Step 1: Apply formatting**

`./gradlew spotlessApply`

- [ ] **Step 2: Full test suite**

`./gradlew test -x spotlessCheck --rerun`
Expected: BUILD SUCCESSFUL, 0 failures

- [ ] **Step 3: Commit if anything changed from spotless**

`git commit -am "style: spotless format after CAR improvements"`
