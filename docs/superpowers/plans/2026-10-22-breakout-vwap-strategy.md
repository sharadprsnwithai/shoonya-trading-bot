# Breakout VWAP (BVS) Strategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Dual-Level Breakout VWAP (BVS) intraday futures strategy: 12-stock 09:25 selection, dual-level confluence bias, VWAP-cross trigger candle, futures entry on trigger high/low break, 50% partial at 2.5R with breakeven runner stop, 10-EMA runner exit.

**Architecture:** New package `com.tradingbot.strategy.breakoutvwap` following the SilverOrb/CAR house pattern (config properties class + service + scheduler + models). Reuses existing components read-only: `ShoonyaFeedClient`/`ShoonyaMarketDataService` (quotes, candles, futures resolution), `NseOiSpurtClient`, `NseBanPeriodClient`, `HistoricalOhlcCacheService`, `TechnicalAnalysisService` (VWAP/EMA), `StockFnoRegistry`, `TelegramService`. One minimal shared-code change: a strategy-routing guard in `AbstractTradeExecutionConsumer` plus a dedicated `shoonya-bvs` consumer entry, so BVS's execution ledgers never touch LVR's.

**Tech Stack:** Java 21, Spring Boot, JUnit 5 + Mockito + AssertJ, Spotless (google-java-format AOSP), JaCoCo. Gradle wrapper `.\gradlew.bat` (Windows).

**Spec:** `docs/superpowers/specs/2026-10-22-dual-level-breakout-vwap-design.md` — numbered rules referenced throughout. The spec is the source of truth; this plan argues from it.

## Global Constraints

- Strategy ID constant: `BreakoutVwapService.STRATEGY_ID = "BREAKOUT_VWAP"` (config key `strategy-modes.BREAKOUT_VWAP`).
- Config prefix `trading-bot.strategy.breakout-vwap`, env prefix `BREAKOUT_VWAP_`, state file `./data/bvs-state.json`.
- Spec rule values, verbatim: `rr-partial=2.5`, `ema-period=10`, `bucket-size=3`, `trigger-window-minutes=5`, entry cutoff `14:30`, hard exit `15:00`, session range `09:30–10:00` (candles starting 09:30–09:55), scan cron `0 25 9 ? * MON-FRI`, OI-spurt cron `5 25 9 ? * MON-FRI` (staggered after LVR's 09:25:00), cycle cron `20 */5 9-15 ? * MON-FRI`, tick `fixedRate=30000`.
- All `@Scheduled` methods zone `Asia/Kolkata`, gated by BVS properties `scheduler-enabled` (default true) and `enabled` (default true).
- No new dependencies. Javadoc on every public class/method. TDD: every task writes its failing test first.
- Verification command after every task: `.\gradlew.bat spotlessCheck` must pass; task tests green before commit.
- Do not modify LVR/CAR/GARCH classes. The only shared-code edit allowed is the consumer guard in Task 6.
- Test files named anything except `LvrStateStoreTest`/`TelegramBotCommandListenerTest` (build.gradle.kts excludes those names at any path).

## Review Focus

Failure modes the spec implies but that a reasonable implementer could get wrong; each is pinned by a test in the owning task:

1. **Ban-list fail-closed (spec rule 5):** if `NseBanPeriodClient` fetch fails or returns invalid, BVS must stand down (empty universe), not trade symbols with unverified ban status. → Task 2 `scannerStandsDownWhenBanFetchFails`.
2. **Session-range membership (spec rule 8):** only candles *starting* 09:30–09:55 form the range; the 10:00 candle must never extend it. → Task 3 `sessionRangeUsesOnlyCandlesStarting0930To0955`.
3. **Entry-window expiry (spec rule 13):** a trigger-level breach 6 minutes after trigger close must not enter. → Task 4 `entryWindowExpiresAfterFiveMinutes`.
4. **Consumer guard regression (spec rule 22):** after the Task 6 guard, LVR and CAR signals must still execute on `shoonya-primary` — grep confirms every published `strategyId` is mapped there. → Task 6 `unmappedStrategySignalsAreIgnored` + verification step.
5. **Restart re-partial (spec rules 16/24):** a restored position already `partialBooked=true` must not partial a second time when price is beyond target. → Task 5 `restoredPartialBookedPositionDoesNotRepartial`.

---

### Task 1: BvsProperties + strategy config block

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/breakoutvwap/config/BvsProperties.java`
- Test: `src/test/java/com/tradingbot/strategy/breakoutvwap/config/BvsPropertiesTest.java`
- Modify: `src/main/resources/application.properties` (append after the LVR block, before `trading-bot.execution`)

**Interfaces:**
- Consumes: nothing.
- Produces: `BvsProperties` (`@Component @ConfigurationProperties(prefix = "trading-bot.strategy.breakout-vwap")`, explicit getters/setters, no Lombok — copy the `SilverOrbProperties` style at `src/main/java/com/tradingbot/strategy/silverorb/config/SilverOrbProperties.java`). Fields with defaults: `boolean enabled=true`, `boolean schedulerEnabled=true`, `int bucketSize=3`, `double rrPartial=2.5`, `int emaPeriod=10`, `int triggerWindowMinutes=5`, `LocalTime entryCutoff=LocalTime.of(14,30)`, `LocalTime hardExit=LocalTime.of(15,0)`, `LocalTime sessionRangeStart=LocalTime.of(9,30)`, `LocalTime sessionRangeEnd=LocalTime.of(10,0)`, `String scanCron="0 25 9 ? * MON-FRI"`, `String oiSpurtCron="5 25 9 ? * MON-FRI"`, `String cycleCron="20 */5 9-15 ? * MON-FRI"`, `long tickRateMs=30000`, `boolean telegramAlerts=true`, `boolean statePersistenceEnabled=true`, `String stateFile="./data/bvs-state.json"`, `double riskPerTradePercent=1.0`, `double paperCapital=500000`, `int lots=1`, `boolean dynamicPositionSizing=true`. Later tasks read these; exact names are contract.

- [ ] **Step 1: Write the failing test**

`BvsPropertiesTest`: plain `new BvsProperties()` — assert defaults: `rrPartial()==2.5`, `emaPeriod()==10`, `bucketSize()==3`, `triggerWindowMinutes()==5`, `entryCutoff()==LocalTime.of(14,30)`, `hardExit()==LocalTime.of(15,0)`, `sessionRangeStart()==LocalTime.of(9,30)`, `sessionRangeEnd()==LocalTime.of(10,0)`, `scanCron().equals("0 25 9 ? * MON-FRI")`, `oiSpurtCron().equals("5 25 9 ? * MON-FRI")`, `cycleCron().equals("20 */5 9-15 ? * MON-FRI")`, `stateFile().equals("./data/bvs-state.json")`, `enabled()` and `schedulerEnabled()` and `statePersistenceEnabled()` and `telegramAlerts()` are true.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests com.tradingbot.strategy.breakoutvwap.config.BvsPropertiesTest`
Expected: compilation failure — class does not exist.

- [ ] **Step 3: Implement `BvsProperties`**

Class as specified in Interfaces; Javadoc one-liner per field naming the env var (e.g. `BVS_RR_PARTIAL`). Follow `SilverOrbProperties` for LocalTime field binding style.

- [ ] **Step 4: Append the strategy block to `application.properties`**

After the existing `trading-bot.strategy.lowest-volume.*` block, add:

```properties
# Breakout VWAP (BVS) strategy
trading-bot.strategy.breakout-vwap.enabled=${BVS_ENABLED:true}
trading-bot.strategy.breakout-vwap.scheduler-enabled=${BVS_SCHEDULER_ENABLED:true}
trading-bot.strategy.breakout-vwap.bucket-size=${BVS_BUCKET_SIZE:3}
trading-bot.strategy.breakout-vwap.rr-partial=${BVS_RR_PARTIAL:2.5}
trading-bot.strategy.breakout-vwap.ema-period=${BVS_EMA_PERIOD:10}
trading-bot.strategy.breakout-vwap.trigger-window-minutes=${BVS_TRIGGER_WINDOW_MINUTES:5}
trading-bot.strategy.breakout-vwap.entry-cutoff=${BVS_ENTRY_CUTOFF:14:30}
trading-bot.strategy.breakout-vwap.hard-exit=${BVS_HARD_EXIT:15:00}
trading-bot.strategy.breakout-vwap.session-range-start=${BVS_SESSION_RANGE_START:09:30}
trading-bot.strategy.breakout-vwap.session-range-end=${BVS_SESSION_RANGE_END:10:00}
trading-bot.strategy.breakout-vwap.scan-cron=${BVS_SCAN_CRON:0 25 9 ? * MON-FRI}
trading-bot.strategy.breakout-vwap.oi-spurt-cron=${BVS_OI_SPURT_CRON:5 25 9 ? * MON-FRI}
trading-bot.strategy.breakout-vwap.cycle-cron=${BVS_CYCLE_CRON:20 */5 9-15 ? * MON-FRI}
trading-bot.strategy.breakout-vwap.tick-rate-ms=${BVS_TICK_RATE_MS:30000}
trading-bot.strategy.breakout-vwap.telegram-alerts=${BVS_TELEGRAM_ALERTS:true}
trading-bot.strategy.breakout-vwap.state-persistence-enabled=${BVS_STATE_PERSISTENCE_ENABLED:true}
trading-bot.strategy.breakout-vwap.state-file=${BVS_STATE_FILE:./data/bvs-state.json}
trading-bot.strategy.breakout-vwap.risk-per-trade-percent=${BVS_RISK_PER_TRADE:1.0}
trading-bot.strategy.breakout-vwap.paper-capital=${BVS_PAPER_CAPITAL:500000}
trading-bot.strategy.breakout-vwap.lots=${BVS_LOTS:1}
trading-bot.strategy.breakout-vwap.dynamic-position-sizing=${BVS_DYNAMIC_POSITION_SIZING:true}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `.\gradlew.bat test --tests com.tradingbot.strategy.breakoutvwap.config.BvsPropertiesTest` — PASS; then `.\gradlew.bat spotlessCheck` — green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/breakoutvwap/config/BvsProperties.java src/test/java/com/tradingbot/strategy/breakoutvwap/config/BvsPropertiesTest.java src/main/resources/application.properties
git commit -m "feat(bvs): add BVS strategy properties and config block"
```

---

### Task 2: Universe scanner

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/breakoutvwap/model/BvsBucket.java` (enum `GAINERS, LOSERS, OI_SPURT, SQUEEZE`)
- Create: `src/main/java/com/tradingbot/strategy/breakoutvwap/model/BvsUniverse.java` (record `BvsUniverse(Map<BvsBucket, List<String>> byBucket)` with `public List<String> allSymbols()` returning deduped, bucket-ordered union)
- Create: `src/main/java/com/tradingbot/strategy/breakoutvwap/service/BvsUniverseScanner.java`
- Test: `src/test/java/com/tradingbot/strategy/breakoutvwap/service/BvsUniverseScannerTest.java`

**Interfaces:**
- Consumes: `BvsProperties.bucketSize()`. Collaborators (all injected via constructor, mocked in tests): `com.tradingbot.marketdata.feed.ShoonyaFeedClient` — `Map<String, StockQuoteSnapshot> snapshots(Collection<String> symbols, Duration maxAge)`; `com.tradingbot.marketdata.ShoonyaMarketDataService` — `JsonNode fetchQuote(String exchange, String token)` and `List<Candle> fetchDailyCandles(String symbol, int daysBack)`; `com.tradingbot.marketdata.NseOiSpurtClient` — `List<NseOiSpurtItem> fetchTopUnderlyings(int topN)`; `com.tradingbot.marketdata.NseBanPeriodClient` — `Set<String> fetchBanList()`, `boolean isLastFetchSuccessful()`; `com.tradingbot.service.HistoricalOhlcCacheService` — `List<Candle> getDailyCandles(String symbol)`; `com.tradingbot.util.StockFnoRegistry` — static `getAllInstruments()` (universe), `get(String)` (token lookup), `isIndex(String)` (filter indices out of the equity universe).
- Produces: `public BvsUniverse scan()` (returns empty-universe `BvsUniverse` on stand-down); pure static helpers `static List<String> topByPctChange(Collection<StockQuoteSnapshot> quotes, int n, boolean gainers)` and `static boolean isThreeDayContraction(List<Candle> completedDailyCandles)` (candles oldest-first, at least 4 completed sessions; true when for each of the last 3 transitions `high[i] < high[i-1] && low[i] > low[i-1]`).

- [ ] **Step 1: Write the failing tests**

`BvsUniverseScannerTest` (Mockito mocks of all five collaborators + real `new BvsProperties()`):

1. `topByPctChangeRanksGainersAndLosers` — static helper: snapshots A=+4%, B=+1%, C=-2%, D=-5%; `topByPctChange(..., 2, true)` returns `[A, B]`; `topByPctChange(..., 2, false)` returns `[D, C]`.
2. `isThreeDayContractionRequiresFourSessions` — 3 sessions → false; 4 sessions where each transition has lower high AND higher low → true; 4 sessions where one transition has a higher high → false; 4 sessions where one transition has a lower low → false.
3. `scanBuildsTwelveStocksAcrossFourBuckets` — feed snapshots give gainers/losers over the F&O equity universe, OI client returns 3 items, squeeze scan (mock `fetchDailyCandles` per candidate) matches 3 symbols; assert `allSymbols()` has 12 distinct symbols and each bucket list has 3.
4. `scanDedupesAcrossBucketsAndBackfills` — symbol X ranks top-3 in both gainers and OI-spurt; assert X appears once (in GAINERS) and OI_SPURT backfills with the 4th-ranked OI name.
5. `scanExcludesBanListedStocks` — `fetchBanList()` returns `{TOPGAINER3}` with `isLastFetchSuccessful()==true`; assert TOPGAINER3 absent, backfill applied.
6. `scannerStandsDownWhenBanFetchFails` — `isLastFetchSuccessful()==false`; assert `scan().allSymbols().isEmpty()`.
7. `scanSkipsIndexSymbols` — feed universe contains NIFTY index entries (via `StockFnoRegistry.isIndex`); assert excluded from ranking.
8. `degradesToSurvivingBucketsWhenOiFails` — OI client returns empty list but feed and squeeze succeed; assert universe non-empty with `byBucket` lacking OI_SPURT (or having it empty) and 9 symbols present (spec rule 6).

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat test --tests com.tradingbot.strategy.breakoutvwap.service.BvsUniverseScannerTest`
Expected: compilation failure.

- [ ] **Step 3: Implement `BvsUniverseScanner`, `BvsBucket`, `BvsUniverse`**

`scan()` flow: (1) ban fetch first — `isLastFetchSuccessful()` false → log warning, return empty universe (spec rule 5, fail-closed); (2) candidate universe = `StockFnoRegistry.getAllInstruments()` keys minus indices; (3) gainers/losers = `feedClient.snapshots(candidates, Duration.ofSeconds(30))` ranked by `pctChange` via `topByPctChange` — symbols missing from the feed map are skipped (no REST backfill in the first cut; the feed covers the subscribed universe — `shortcut: feed-only quotes may drop unsubscribed symbols; upgrade to REST backfill if bucket shortfall repeats in practice`); (4) OI_SPURT = `oiSpurtClient.fetchTopUnderlyings(bucketSize)` symbols (empty list on failure → bucket degrades, spec rule 6); (5) SQUEEZE = candidates whose `historicalOhlcCacheService.getDailyCandles(sym)` (falling back to `marketDataService.fetchDailyCandles(sym, 10)` when the cache returns empty) satisfies `isThreeDayContraction` after stripping today's bar — iterate candidates until `bucketSize` found or list exhausted; (6) remove all ban-listed symbols from every bucket, backfill from next rank; (7) assemble `BvsUniverse` with dedupe in bucket order GAINERS → LOSERS → OI_SPURT → SQUEEZE. Every feed/candle failure: log warning, degrade that bucket (spec rule 6); if all four buckets empty → empty universe (strategy stands down).

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat test --tests com.tradingbot.strategy.breakoutvwap.service.BvsUniverseScannerTest` — PASS; `.\gradlew.bat spotlessCheck` — green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/breakoutvwap src/test/java/com/tradingbot/strategy/breakoutvwap/service/BvsUniverseScannerTest.java
git commit -m "feat(bvs): 12-stock universe scanner with ban exclusion and fail-closed stand-down"
```

---

### Task 3: Bias engine — confluence, latch, session range, VWAP trigger

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/breakoutvwap/model/BvsBias.java` (enum `LONG, SHORT, NONE`)
- Create: `src/main/java/com/tradingbot/strategy/breakoutvwap/model/BvsSetupState.java` (enum `AWAITING_RANGE, AWAITING_CONFLUENCE, BIAS_ARMED, TRIGGER_ARMED`)
- Create: `src/main/java/com/tradingbot/strategy/breakoutvwap/model/BvsSetup.java`
- Test: `src/test/java/com/tradingbot/strategy/breakoutvwap/service/BvsConfluenceTest.java`

**Interfaces:**
- Consumes: `com.tradingbot.indicator.TechnicalAnalysisService` — `double[] calculateVwapSeries(List<Candle> candles)` (typical-price, daily IST reset — today's slice is session-anchored from 09:15) and `double[] calculateEmaSeries(double[] close, int period)`.
- Produces:
  - `BvsSetup` public fields + accessors: `String symbol`, `BvsSetupState state` (initial `AWAITING_RANGE`), `BvsBias bias` (initial `NONE`), `Double sessionRangeHigh`, `Double sessionRangeLow`, `Double pdh`, `Double pdl`, `Double triggerHigh`, `Double triggerLow`, `Instant triggerArmedAt` (trigger candle close time), `boolean entryConsumed`.
  - Pure static methods on `BreakoutVwapService` (new class, engine shell created in this task — full engine in Task 4):
    - `static void applySessionRange(List<Candle> candles5m, BvsSetup setup, LocalTime rangeStart, LocalTime rangeEnd)` — high/low of candles whose **start** time-of-day ∈ [rangeStart, rangeEnd) in IST; sets `sessionRangeHigh/Low`; on completion moves state `AWAITING_RANGE → AWAITING_CONFLUENCE`. Idempotent: recomputing never widens with post-10:00 candles.
    - `static BvsBias evaluateConfluence(double close, BvsSetup setup)` — `close > max(sessionRangeHigh, pdh)` → LONG; `close < min(sessionRangeLow, pdl)` → SHORT; else NONE (spec rule 9).
    - `static BvsBias advanceBias(BvsBias current, BvsBias evaluated)` — latch (spec rule 10): opposite-confluence evaluated bias flips; NONE never clears a latched bias; identical bias is a no-op. Valid only from state `AWAITING_CONFLUENCE`/`BIAS_ARMED` (caller checks state).
    - `static boolean isVwapTrigger(BvsBias bias, double close, double vwap)` — LONG: `close > vwap`; SHORT: `close < vwap`; NONE: false (spec rule 11).
    - `static void armTriggerIfEligible(BvsSetup setup, BvsBias bias, double close, double vwap, double candleHigh, double candleLow, Instant candleCloseTime, LocalTime entryCutoff)` — when bias != NONE, state is `BIAS_ARMED` or `TRIGGER_ARMED` (re-arm allowed after expiry, spec rule 20), `isVwapTrigger` true, and `candleCloseTime` before entry cutoff: set `triggerHigh/low` = candle high/low, `triggerArmedAt` = candleCloseTime, `entryConsumed=false`, state → `TRIGGER_ARMED`.
  - `BreakoutVwapService` constructor shell: `(BvsProperties properties, TechnicalAnalysisService technicalAnalysisService, ...)` — remaining collaborators added in Task 4; class annotated `@Service`, `public static final String STRATEGY_ID = "BREAKOUT_VWAP"`.

- [ ] **Step 1: Write the failing tests**

`BvsConfluenceTest` (static-method tests, no mocks; helper builds `Candle` instances with fixed IST instants):

1. `sessionRangeUsesOnlyCandlesStarting0930To0955` — candles 09:30…09:55 (6) plus a 10:00 candle with a higher high; assert range high/low equal the 09:30–09:55 extremes and the 10:00 candle is ignored.
2. `sessionRangeRequiresSixCompletedCandles` — partial range (only 09:30–09:45 present) → state stays `AWAITING_RANGE`, highs null.
3. `confluenceLongRequiresBothHighsBroken` — rangeHigh 25000, PDH 25100: close 25050 → NONE (above range high but below PDH); close 25101 → LONG.
4. `confluenceShortRequiresBothLowsBroken` — rangeLow 24800, PDL 24700: close 24750 → NONE; close 24699 → SHORT.
5. `biasLatchesUntilOppositeConfluence` — `advanceBias(NONE, LONG)` → LONG; `advanceBias(LONG, NONE)` → LONG (retreat between levels keeps bias); `advanceBias(LONG, SHORT)` → SHORT.
6. `vwapTriggerChecksDirectionOnly` — LONG + close 25010 > vwap 25000 → true; LONG + close 24990 → false; SHORT mirrored; NONE → false.
7. `armTriggerSetsWindowFromCandleClose` — `armTriggerIfEligible` on BIAS_ARMED setup, LONG, close>vwap, cutoff 14:30, candleCloseTime 11:05 IST → state `TRIGGER_ARMED`, `triggerHigh` = candle high, `triggerArmedAt` = 11:05; same call with candleCloseTime 14:31 → state unchanged (no arm).
8. `pdhPdlMissingMeansNoConfluence` — pdh null → `evaluateConfluence` returns NONE regardless of close.

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat test --tests com.tradingbot.strategy.breakoutvwap.service.BvsConfluenceTest`
Expected: compilation failure.

- [ ] **Step 3: Implement models + `BreakoutVwapService` decision methods**

As specified in Interfaces. `Candle` is the existing repo candle model (`com.tradingbot.model.Candle` — verify field names for open time before implementing; convert to IST via `NseTradingCalendarUtil.IST_ZONE`). Null `sessionRangeHigh/Low` or `pdh/pdl` → confluence NONE (defensive, test 8).

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat test --tests com.tradingbot.strategy.breakoutvwap.service.BvsConfluenceTest` — PASS; `.\gradlew.bat spotlessCheck` — green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/breakoutvwap src/test/java/com/tradingbot/strategy/breakoutvwap/service/BvsConfluenceTest.java
git commit -m "feat(bvs): dual-level confluence bias, latch, and VWAP trigger decisions"
```

---

### Task 4: Trade engine — entry, R management, exits, signals

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/breakoutvwap/model/BvsPosition.java`
- Modify: `src/main/java/com/tradingbot/strategy/breakoutvwap/service/BreakoutVwapService.java` (orchestration + trade methods)
- Test: `src/test/java/com/tradingbot/strategy/breakoutvwap/service/BreakoutVwapServiceTest.java`

**Interfaces:**
- Consumes (in addition to Task 3): `ShoonyaMarketDataService` — `List<Candle> fetch5MinCandles(String symbol, int daysBack)` (clamped 5..15), `List<Candle> fetchDailyCandles(String symbol, int daysBack)`, `FuturesContract resolveFuturesContract(String symbol)` (record with `tsym`, `token`); `com.tradingbot.util.StockFnoRegistry` — `int getLotSize(String symbol)`; `com.tradingbot.strategy.TradeSignal` (record: `signalId, strategyId, underlyingSymbol, tradingSymbol, SignalAction action, price, stopLoss, targetPrice, baseQuantity, reason, timestamp, metadata`) with `SignalAction` values `ENTRY_LONG, ENTRY_SHORT, EXIT_LONG, EXIT_SHORT, PARTIAL_EXIT_LONG, PARTIAL_EXIT_SHORT, UPDATE_STOP_LOSS, SQUARE_OFF`; `com.tradingbot.telegram.TelegramService` — `void sendTextMessage(String message)`; `com.tradingbot.strategy.SignalBus` (verify exact publisher API used by LVR — grep how `LowestVolumeReversalService` publishes `TradeSignal`s and copy that call).
- Produces:
  - `BvsPosition` public fields: `String symbol`, `String brokerTradingSymbol`, `String token`, `BvsBias direction`, `double entryPrice`, `double stopPrice`, `double rValue`, `double targetPrice` (entry ± 2.5R per direction), `int totalQuantity`, `int remainingQuantity`, `boolean partialBooked`, `Instant entryTime`, `String tradeId`.
  - Service state: `Map<String, BvsSetup> setups`, `Map<String, BvsPosition> openPositions`, `List<BvsPosition> tradeHistory` (in-memory for now; persistence in Task 5).
  - Service methods:
    - `public void runScan()` — builds `BvsUniverse` via scanner, creates one `BvsSetup` per symbol (fetch `fetchDailyCandles(symbol, 10)`, strip today's bar, set `pdh/pdl` from the last completed session), clears prior-day state; empty universe → log + Telegram "BVS stood down".
    - `public void runCycle()` — 5m pass per setup: fetch `fetch5MinCandles(symbol, 5)`; `applySessionRange`; after range exists (state ≥ AWAITING_CONFLUENCE) compute VWAP series via `calculateVwapSeries`, evaluate/advance bias (state → BIAS_ARMED on first latch; when a latch flip changes the bias, cancel any unfilled `TRIGGER_ARMED` — clear trigger levels, state → `BIAS_ARMED`), then `armTriggerIfEligible` on the latest completed candle (its close time = candle start + 5m); manage open positions: 10-EMA runner exit (`isRunnerExit`: LONG runner exits when candle close < EMA10 value of that candle; SHORT mirrored) and stop-loss check on candle low/high; hard-exit check when `now >= properties.hardExit()`.
    - `public void evaluateLivePriceActions()` — 30s tick: for `TRIGGER_ARMED` setups with `!entryConsumed`: breach (`price >= triggerHigh` LONG / `<= triggerLow` SHORT) and `Duration.between(triggerArmedAt, now) <= triggerWindowMinutes` → `executeEntry`; expired window → state back to `BIAS_ARMED`, `entryConsumed=true`. For open positions: stop hit (price <= stop LONG / >= SHORT) → full exit; `price >= targetPrice` LONG / <= SHORT and `!partialBooked` → `executePartial`.
    - `public synchronized BvsPosition executeEntry(String symbol, BvsSetup setup, double price)` — R = `|price − opposite trigger extreme|` (LONG: `price − triggerLow`; SHORT: `triggerHigh − price`); stop = opposite extreme (spec rule 14); sizing mirrors LVR: `dynamicPositionSizing` → `lots = max(1, min(riskPerTradeAmount() / (rValue × lotSize), properties.lots() × 2))` else `properties.lots()`; `riskPerTradeAmount()` = `paperCapital × riskPerTradePercent / 100`; qty = lots × `getLotSize`; resolve contract once per symbol, cache tsym/token; skip (log) when `now >= entryCutoff`; publish `ENTRY_LONG`/`ENTRY_SHORT` TradeSignal (metadata: `positionSide`, `exchange=NFO`); Telegram entry message; move setup state out of `TRIGGER_ARMED` (`entryConsumed=true`).
    - `private void executePartial(BvsPosition p, double price)` — mark `partialBooked=true`; `PARTIAL_EXIT_LONG/SHORT` signal with `baseQuantity = totalQuantity / 2` rounded up, metadata `partialExitRatio=0.5`, `remainingQuantity`; **then** publish `UPDATE_STOP_LOSS` signal with `stopLoss = entryPrice` (breakeven, spec rule 18), metadata `positionSide`, `exchange=NFO`; Telegram partial message.
    - `private void executeFullExit(BvsPosition p, double price, String reason)` — `EXIT_LONG/EXIT_SHORT` for `remainingQuantity`; history + Telegram; remove from open positions.
    - `public synchronized void executeHardExit(LocalTime now)` — `now >= hardExit` → `SQUARE_OFF` every open position at current price, reason `HARD_EXIT_1500`.
  - Pure static trade decisions (tested directly): `static double rFor(BvsBias direction, double entry, double triggerExtreme)` (opposite extreme: LONG uses triggerLow, SHORT uses triggerHigh), `static double targetFor(BvsBias direction, double entry, double r, double rrPartial)`, `static boolean isRunnerExit(BvsBias direction, double candleClose, double ema10)`, `static boolean isStopHit(BvsBias direction, double price, double stop)`, `static boolean withinEntryWindow(Instant triggerArmedAt, Instant now, int windowMinutes)`.

- [ ] **Step 1: Write the failing tests**

`BreakoutVwapServiceTest` — `@BeforeEach`: `mock(ShoonyaMarketDataService.class)`, `mock(TelegramService.class)`, scanner mock, real `new TechnicalAnalysisService()`, real `new BvsProperties()`, `service.setClock(Clock.fixed(..., IST))` (add a package-visible `setClock` like LVR's for time control).

Pure-decision tests:
1. `rAndTargetMath` — LONG entry 25000, triggerLow 24960 → r=40; target = 25000 + 2.5×40 = 25100; SHORT mirrored (24900 entry, triggerHigh 24940 → target 24800).
2. `entryWindowExpiresAfterFiveMinutes` — triggerArmedAt 11:05, now 11:10 → true; now 11:11 → false (spec rule 13, Review Focus 3).
3. `runnerExitOnEmaCross` — LONG, close 24990 < ema10 25000 → true; close 25010 → false; SHORT mirrored.
4. `stopHitCheck` — LONG price 24959 <= stop 24960 → true; 24961 → false.

Orchestration tests (mocked market data):
5. `entryPublishesSignalAndSetsStop` — armed setup, tick price breaches triggerHigh within window; verify `ENTRY_LONG` TradeSignal published with `stopLoss==triggerLow`, `baseQuantity` = lots×lotSize, strategyId `BREAKOUT_VWAP`; position in `openPositions`.
6. `entryBlockedAfterCutoff` — clock 14:31, breach → no signal, no position.
7. `partialAtTargetMovesStopToBreakeven` — open LONG beyond target on tick → `PARTIAL_EXIT_LONG` (qty = half rounded up) followed by `UPDATE_STOP_LOSS` with `stopLoss == entryPrice`; `partialBooked==true` (spec rule 18, Review Focus 5 precursor).
8. `stopHitExitsFullRemaining` — price through stop → `EXIT_LONG` for `remainingQuantity`, position removed, history entry present.
9. `runnerExitOnFiveMinuteClose` — `runCycle` with close < EMA10 → `EXIT_LONG`, reason `EMA10_CROSS`.
10. `hardExitSquaresEverything` — two open positions, clock 15:00 → two `SQUARE_OFF` signals, `openPositions` empty.
11. `expiredTriggerRearmsAndWaitsForNextVwapCandle` — window expires on tick (no entry); next `runCycle` VWAP-cross candle arms a new trigger with new levels.
12. `noBiasNoTrigger` — NONE bias, VWAP-cross candle → no trigger armed.
13. `biasFlipCancelsArmedTrigger` — LONG setup in `TRIGGER_ARMED` unfilled, next `runCycle` evaluates confluence SHORT (both lows broken) → bias SHORT, state back to `BIAS_ARMED`, no entry from the stale LONG trigger window (spec rule 20).
14. `oneOpenPositionPerStock` — open position exists for symbol X, a new breach occurs for X → no second entry signal until the first position closes.

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat test --tests com.tradingbot.strategy.breakoutvwap.service.BreakoutVwapServiceTest`
Expected: compilation failure.

- [ ] **Step 3: Implement the engine methods**

As specified in Interfaces. Signal publishing: copy LVR's exact publisher call (grep `TradeSignal.of(` usage in `LowestVolumeReversalService` and mirror the bus/publisher invocation — do not touch LVR code). All mutating methods `synchronized` (LVR house pattern).

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat test --tests com.tradingbot.strategy.breakoutvwap.service.BreakoutVwapServiceTest` — PASS; `.\gradlew.bat spotlessCheck` — green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/breakoutvwap src/test/java/com/tradingbot/strategy/breakoutvwap/service/BreakoutVwapServiceTest.java
git commit -m "feat(bvs): futures entry, 2.5R partial with breakeven stop, 10-EMA runner exit"
```

---

### Task 5: State persistence

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/breakoutvwap/persistence/BvsStateStore.java`
- Create: `src/main/java/com/tradingbot/strategy/breakoutvwap/persistence/BvsDailyState.java` (public-field JSON DTO: `LocalDate date`, `Instant savedAt`, `Map<String, BvsSetupSnapshot> setups`, `List<BvsPositionSnapshot> openPositions`, `List<BvsPositionSnapshot> tradeHistory`, `boolean stoodDownToday`, `Map<BvsBucket, List<String>> universeByBucket`)
- Create: `src/main/java/com/tradingbot/strategy/breakoutvwap/persistence/BvsSetupSnapshot.java`, `BvsPositionSnapshot.java` (public fields mirroring the live models + `Instant` timestamps; same pattern as `com.tradingbot.model.strategy.LvrPositionSnapshot`)
- Modify: `src/main/java/com/tradingbot/strategy/breakoutvwap/service/BreakoutVwapService.java` (persist/restore hooks)
- Test: `src/test/java/com/tradingbot/strategy/breakoutvwap/persistence/BvsStateStoreTest.java` (note: must NOT be named `LvrStateStoreTest` — already excluded by build; `BvsStateStoreTest` is fine)

**Interfaces:**
- Consumes: `BvsProperties.stateFile()/statePersistenceEnabled()`; Jackson via `ObjectMapper` as `LvrStateStore` does (copy its atomic tmp+move save and null-on-corrupt load approach — read `src/main/java/com/tradingbot/persistence/LvrStateStore.java` first).
- Produces: `public static void save(Path path, BvsDailyState state) throws IOException`, `public static BvsDailyState load(Path path)` (null on missing/corrupt). Service additions: `void persistState()` (package-private, called after universe scan, entries, partials, exits, bias latches), `@PostConstruct void restorePersistedState()` (rebuild `setups`/`openPositions`/`tradeHistory` from snapshot; skip restore when snapshot date ≠ today), `@PreDestroy void persistStateOnShutdown()` — all gated by `statePersistenceEnabled`.

- [ ] **Step 1: Write the failing tests**

`BvsStateStoreTest`:
1. `saveLoadRoundTrip` — `@TempDir` path; state with one setup (BIAS_ARMED, latched LONG, range/PDH values) + one open partial-booked position + one history position → save → load → assert every field equal.
2. `loadReturnsNullOnMissingFile` — untouched path → null.
3. `loadReturnsNullOnCorruptFile` — write `"not json"` to the path → null (no throw).
4. `saveIsAtomicOverwrite` — save twice to same path → load returns the second state.

Plus in `BreakoutVwapServiceTest` (append to Task 4's file):
5. `restoredPartialBookedPositionDoesNotRepartial` — persist state with a partial-booked LONG below target, restore via `restorePersistedState()`, tick price beyond target → no second `PARTIAL_EXIT_LONG` signal (Review Focus 5).
6. `restoreSkipsStaleDate` — snapshot dated yesterday → `openPositions` empty after restore.

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat test --tests com.tradingbot.strategy.breakoutvwap.persistence.BvsStateStoreTest` — compilation failure.

- [ ] **Step 3: Implement store, DTOs, and service hooks**

As specified. Copy `LvrStateStore`'s ObjectMapper setup and atomic-write pattern verbatim in structure.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat test --tests com.tradingbot.strategy.breakoutvwap.persistence.BvsStateStoreTest --tests com.tradingbot.strategy.breakoutvwap.service.BreakoutVwapServiceTest` — PASS; `.\gradlew.bat spotlessCheck` — green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/breakoutvwap src/test/java/com/tradingbot/strategy/breakoutvwap
git commit -m "feat(bvs): disk-persisted daily state with restart restore"
```

---

### Task 6: Execution lane isolation (shared guard + consumer registration)

**Files:**
- Modify: `src/main/java/com/tradingbot/execution/consumer/AbstractTradeExecutionConsumer.java` (guard in the signal-processing path, around `processSignalSafe`/line ~162)
- Modify: `src/main/resources/application.properties` (append `consumers[2]` block after `consumers[1]`, line ~153)
- Test: `src/test/java/com/tradingbot/execution/consumer/TradeExecutionConsumerRoutingTest.java`

**Interfaces:**
- Consumes: existing `AbstractTradeExecutionConsumer` constructor and `resolveMode(TradeSignal)` (falls back to the consumer's default mode for unmapped strategyIds — the bug this guard fixes); `ExecutionProperties.ConsumerConfig` id/broker/mode/strategy-modes/quantity-multiplier/enabled/max-signal-age-seconds.
- Produces: guard semantics — a consumer **ignores** any signal whose `strategyId` is absent from its (non-empty) `strategyModes` map; consumers with an empty map keep the fallback behavior (manual/ad-hoc signals unaffected). Config: `consumers[2]` = `shoonya-bvs`, broker `SHOONYA`, `mode=${SHOONYA_EXECUTION_MODE:${EXECUTION_MODE:PAPER}}`, `strategy-modes.BREAKOUT_VWAP=${SHOONYA_BVS_MODE:${SHOONYA_EXECUTION_MODE:${EXECUTION_MODE:PAPER}}}`, `quantity-multiplier=1.0`, `enabled=true`, `max-signal-age-seconds=30`. No `zerodha-bvs` lane — `zerodha-primary` is disabled by default; `shortcut: no Zerodha BVS lane; add consumers[3] mirrored when ZERODHA_CONSUMER_ENABLED goes live`.

- [ ] **Step 1: Write the failing test**

`TradeExecutionConsumerRoutingTest` — use a minimal concrete subclass of `AbstractTradeExecutionConsumer` (test double overriding `handleLiveExecution`/`handlePaperExecution` to record calls), constructor with `strategyModes = {LOWEST_VOLUME_REVERSAL: PAPER, CAR_WEEKLY_GTT: PAPER}`, default mode LIVE:
1. `unmappedStrategySignalsAreIgnored` — deliver a `TradeSignal` with `strategyId="BREAKOUT_VWAP"` (and one with `"OTHER"`) → neither `handlePaperExecution` nor `handleLiveExecution` called (Review Focus 4).
2. `mappedStrategySignalsStillRoute` — `LOWEST_VOLUME_REVERSAL` signal → `handlePaperExecution` called; `CAR_WEEKLY_GTT` → same.
3. `emptyStrategyModesKeepsFallback` — second double with empty map, default PAPER → any strategyId signal executes in PAPER.
4. Verify no regression in existing behavior: grep step (see Step 3) confirms every publisher's strategyId is mapped on `shoonya-primary`.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests com.tradingbot.execution.consumer.TradeExecutionConsumerRoutingTest`
Expected: test 1 fails — unmapped signal currently executes.

- [ ] **Step 3: Implement the guard + config, then verify publisher coverage**

Guard: at the top of the signal-processing entry point (`processSignalSafe` or the bus-subscription method that calls it — read both call sites first), `if (!strategyModes.isEmpty() && (signal.strategyId() == null || !strategyModes.containsKey(signal.strategyId()))) { log.debug(...); return; }`. Then grep every `TradeSignal.of(` / publisher in `src/main/java` and confirm each published `strategyId` ∈ {`LOWEST_VOLUME_REVERSAL`, `CAR_WEEKLY_GTT`}; report any other publisher's strategyId in the commit message if found (it would stop executing on `shoonya-primary`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat test --tests com.tradingbot.execution.consumer.TradeExecutionConsumerRoutingTest` — PASS. Then the regression gate: `.\gradlew.bat test` — full suite green (835+ tests, 0 failures); `.\gradlew.bat spotlessCheck` — green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/execution/consumer/AbstractTradeExecutionConsumer.java src/main/resources/application.properties src/test/java/com/tradingbot/execution/consumer/TradeExecutionConsumerRoutingTest.java
git commit -m "feat(bvs): dedicated execution lane — consumers ignore unmapped strategies"
```

---

### Task 7: Scheduler, orchestration wiring, Telegram

**Files:**
- Create: `src/main/java/com/tradingbot/strategy/breakoutvwap/scheduler/BreakoutVwapScheduler.java`
- Modify: `src/main/java/com/tradingbot/strategy/breakoutvwap/service/BreakoutVwapService.java` (OI-spurt prefetch method, Telegram messages wiring)
- Test: `src/test/java/com/tradingbot/strategy/breakoutvwap/scheduler/BreakoutVwapSchedulerTest.java`

**Interfaces:**
- Consumes: `NseOiSpurtClient.fetchTopUnderlyings(int)` (BVS's own staggered call — spec rule 4); service methods `runScan()`, `runCycle()`, `evaluateLivePriceActions()`, `executeHardExit(LocalTime)`; `TelegramService.sendTextMessage(String)`.
- Produces: `public void prefetchOiSpurts()` on the service (calls `oiSpurtClient.fetchTopUnderlyings(properties.bucketSize())`, stages result for the scanner — scanner accepts a staged list supplier before falling back to its own fetch; wire the staged list as the scanner's OI input). `BreakoutVwapScheduler` (`@Service`, constructor `(BreakoutVwapService service)`):
  - `@Scheduled(cron = "${trading-bot.strategy.breakout-vwap.oi-spurt-cron:5 25 9 ? * MON-FRI}", zone = "Asia/Kolkata") public void scheduledOiSpurtPrefetch()` → `prefetchOiSpurts()`
  - `@Scheduled(cron = "${trading-bot.strategy.breakout-vwap.scan-cron:0 25 9 ? * MON-FRI}", zone = "Asia/Kolkata") public void scheduledScan()` → `runScan()`
  - `@Scheduled(cron = "${trading-bot.strategy.breakout-vwap.cycle-cron:20 */5 9-15 ? * MON-FRI}", zone = "Asia/Kolkata") public void scheduledCycle()` → `runCycle()`
  - `@Scheduled(fixedRateString = "${trading-bot.strategy.breakout-vwap.tick-rate-ms:30000}") public void scheduledTick()` → `evaluateLivePriceActions()`
  - `@Scheduled(cron = "0 0 15 ? * MON-FRI", zone = "Asia/Kolkata") public void scheduledHardExit()` → `executeHardExit(LocalTime.of(15,0))`
  - `@Scheduled(cron = "0 1-4 15 * * MON-FRI", zone = "Asia/Kolkata") public void scheduledHardExitRetry()` → `executeHardExit(LocalTime.of(15,0))` (mirrors LVR retry pattern)
  - Every method guarded by `properties.isEnabled() && properties.isSchedulerEnabled()` (follow `LowestVolumeReversalScheduler`'s `@Value`-toggle style, but read the flags from injected `BvsProperties`).
  - Telegram messages (all prefixed `BVS |`): stood-down warning, scan summary (12 symbols by bucket), trade entry, partial + breakeven, exits with reason, hard-exit summary. Only when `properties.isTelegramAlerts()`.

- [ ] **Step 1: Write the failing test**

`BreakoutVwapSchedulerTest` (constructor-instantiated, mocked service, no Spring context — copy `LowestVolumeReversalSchedulerTest` style):
1. `scheduledMethodsDelegateWhenEnabled` — all five methods → verify mocked service methods called once each.
2. `schedulerDisabledSuppressesAllJobs` — `schedulerEnabled=false` → verify no service interaction.
3. `strategyDisabledSuppressesAllJobs` — `enabled=false` → no service interaction.
4. `hardExitUsesFifteenHundred` — verify `executeHardExit(LocalTime.of(15,0))`.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests com.tradingbot.strategy.breakoutvwap.scheduler.BreakoutVwapSchedulerTest` — compilation failure.

- [ ] **Step 3: Implement scheduler + prefetch + Telegram messages**

As specified. Scanner staged-OI wiring: add an overridable `Supplier<List<NseOiSpurtItem>>` (or a setter-staged list cleared after use — copy LVR's staged-items pattern) consumed by `BvsUniverseScanner` before its own fetch.

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat test --tests com.tradingbot.strategy.breakoutvwap.scheduler.BreakoutVwapSchedulerTest --tests com.tradingbot.strategy.breakoutvwap.service.BvsUniverseScannerTest` — PASS; `.\gradlew.bat spotlessCheck` — green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/breakoutvwap src/test/java/com/tradingbot/strategy/breakoutvwap/scheduler
git commit -m "feat(bvs): scheduler jobs, staggered OI prefetch, Telegram alerts"
```

---

### Task 8: README + full verification

**Files:**
- Modify: `README.md` (add BVS row/section under "Active Strategies", after LVR; one paragraph + the rule list; document `BVS_*` env knobs briefly)
- Test: none new — full-suite verification.

**Interfaces:**
- Consumes: everything from Tasks 1–7.
- Produces: nothing code-side.

- [ ] **Step 1: Add the README section**

Follow the LVR section's format (README lines ~46–55): "### Breakout VWAP (BVS)" — 09:25 four-bucket selection (3 live gainers / 3 live losers / 3 OI spurts / 3 three-day contractions, F&O-only, ban-list excluded), dual-level confluence (09:30–10:00 range + PDH/PDL both broken), VWAP-cross 5m trigger candle, futures entry on trigger high/low break within 5 minutes, 50% at 1:2.5R with breakeven runner stop, runner until 10-EMA close cross, 14:30 entry cutoff, 15:00 hard exit, restart-safe state, dedicated execution lane.

- [ ] **Step 2: Full verification**

Run: `.\gradlew.bat clean build` — Spotless, SpotBugs, PMD, JaCoCo and the full test suite (840+ tests) all green. Fix anything it flags before proceeding.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs(bvs): document Breakout VWAP strategy in README"
```

---

## Notes for executors

- Work on a fresh branch off `feature/lvr-quote-feed-hardening` (current HEAD `2af06f0` contains the GARCH fix; the user's LVR work is already committed). Do NOT touch `LowestVolumeReversalService`, `LowestVolumeReversalScheduler`, CAR, or GARCH classes.
- `Candle` model field names (open-time accessor for the 09:30–09:55 filter) must be verified in `com.tradingbot.model.Candle` before Task 3 Step 3.
- The exact `TradeSignal` publisher call in LVR (bus vs template) must be copied, not invented — grep `TradeSignal.of(` in Task 4 Step 3.
- Paper first: nothing in this plan enables live trading; `SHOONYA_BVS_MODE` defaults to the global `EXECUTION_MODE` (PAPER).
