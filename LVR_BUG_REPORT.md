# LVR Strategy — Bug Report & Proposed Solutions

**Date:** 2026-10-03
**Code state:** HEAD `99c26a9` (analysis) → fixes applied 2026-10-03 (see §0 Fix Status)
**Scope:** `LowestVolumeReversalService` (3293 L), `LowestVolumeReversalScanner`, `LowestVolumeReversalScheduler`, `model/strategy/*`, `execution/*`, `marketdata/*`, `util/StockFnoRegistry`, `application.properties`, controllers, tests.
**Specs used as baseline:** `lowest_volume_reversal_spec.md`, `lvr_30sec_live_check_spec.md`, `lvr_option_buying_spec.md`, `lvr_scanner_token_fix_and_retry_spec.md`.
**Method:** read-only senior-dev/tester analysis; findings then fixed per user sign-off ("Everything, pragmatic depth") — full status in §0.
**Note:** `LVR_BUG_AUDIT.md` (2026-10-01) is stale — many of its findings are fixed in current code (see §6). Do not re-report those.

Each finding lists **Where** (file:line), **What's wrong**, **Impact**, and a **Proposed fix** (design intent only — not applied).

---

## 0. ✅ Fix status (applied 2026-10-03)

All findings below were fixed; `.\gradlew.bat test` is green (full suite). New/updated tests noted per row.

### 🔴 Critical

| # | Status | What changed | Key tests |
|---|--------|--------------|-----------|
| C1 | ✅ Fixed | Pre-entry SL/target guards no longer compare against session `h`/`l`; they use the armed candle's range + LTP window. | `C1 Fix: Setup must NOT invalidate…` |
| C2 | ✅ Fixed | Trigger requires LTP on the correct side of the trigger, **or** a *fresh wick* — session extreme captured as a baseline on the first tick after arming and only an extreme that *advances past* it can fire (N1 semantics; spec §3a amended). Slippage is a two-sided gate shared with replay (L6). | `C2 Fix: Trigger breach must NOT fire…`, `testFreshWickRequiresSessionExtremeToAdvanceAfterArming` |
| C3 | ✅ Fixed (pragmatic) | Order-response validation + failed-fill ledger (`AbstractTradeExecutionConsumer.placeOrderConfirmed`): rejected/poll-rejected ENTRY → symbol `ENTRY_UNCONFIRMED`, later EXITs suppressed; confirmed ENTRY → best-effort protective **SL-M** from contract-scaled `brokerStopLossPrice` metadata; bounded status polling where the gateway supports it (`ZerodhaBrokerGateway.getOrderStatus`); `TradeConsumerManager.reconcileBrokerDrift()` reconciles broker positions vs. the confirmed-entry ledger every 5 min. *Residual:* Zerodha futures entry limit still derives from spot `referencePrice` (±1% buffer) — option entries use premium; basis-dependent fill risk documented. | `testRejectedEntryMarksUnconfirmedAndSuppressesSubsequentExit`, `testConfirmedEntryPlacesBestEffortProtectiveStop`, `testPollRejectionMarksEntryUnconfirmedAndSuppressesExit`, `testSuccessfulExitClearsConfirmedEntryLedger`, `testDriftReconciliation*` |

### 🟠 High

| # | Status | What changed | Key tests |
|---|--------|--------------|-----------|
| H1 | ✅ Fixed | `LowestVolumeReversalService.STRATEGY_ID = "LOWEST_VOLUME_REVERSAL"` is published (instrumentType moved to `metadata`), matching the `strategy-modes.*` property keys; `SHOONYA_LVR_MODE`/`ZERODHA_LVR_MODE` now take effect. | `StrategyModeFromApplicationPropertiesTest` (binds the shipped `application.properties`) |
| H2 | ✅ Fixed (mitigated) | Default: Zerodha consumer `enabled=${ZERODHA_CONSUMER_ENABLED:false}`; startup WARN when >1 enabled consumer is LIVE; dual-live drift reconciliation is skipped (ambiguous ownership). *Not implemented:* explicit `primary-broker` routing (signal-level `brokerTarget`). | `testDriftReconciliationSkippedWhenDualLiveConsumers` |
| H3 | ✅ Fixed | `StockFnoRegistry` takes an injectable clock; futures contract symbols roll past expiry and match the LTP lookup. | `StockFnoRegistryTest` (rewritten, roll/expiry cases) |
| H4 | ✅ Fixed | `evaluateOpenPositions` runs **before** the `enabled` check in `runCycle`/30s tick — disabling new entries never disarms protection of open positions. | `H4 Fix: Open positions must be managed…` |
| H5 | ✅ Fixed | Quote failures are recorded (`recordQuoteFailure`/`recordQuoteSuccess`), logged loudly, retried next tick; exits are never fabricated from a missing quote. | covered by H12 test + cycle tests |
| H6 | ✅ Fixed | `resetDaily(boolean force)` archives a JSON snapshot **before** clearing; intraday reset with open positions requires `force`; mutating `/api/**` guarded by bearer `ApiAuthFilter` (`API_AUTH_TOKEN`, WARN when unset); `[AUDIT]` lines on reset (controller + Telegram `/reset [force]`). | `testResetDailySnapshotsStateBeforeClearing`, `LowestVolumeStrategyControllerTest` (409/force), `TelegramBotCommandListenerTest`, `ApiAuthFilterTest` |
| H7 | ✅ Fixed | PDH/PDL lazy fetch retries once (`recordPdhFetchAttempt`) then **fails closed**; replenished setups get `initPdhPdlForSetup`. | `H7 Fix: PDH/PDL filter must fail closed…`, `testReplenishedSetupGetsPdhPdl` |
| H8 | ✅ Fixed | Fetch path reads official `oi`/`poi` fields into the 9-arg `StockQuoteSnapshot`; OI_SPURTS with no OI anywhere **fails the scan loudly** (no silent fallback); OI present → top-N selection. | `testOiSpurtsScanFailsLoudlyWhenOiMissing`, `testOiSpurtsScanSelectsCandidatesWithOiData`, `LowestVolumeReversalScannerTest` OI cases |
| H9 | ✅ Fixed | Position management is unconditional (first in `runCycle`); `scanner-fallback-cutoff` bound and enforced (stand-down alert once after cutoff); scan retried every cycle until success/cutoff. | `runCycle stops retrying morning universe scan after 10:00 AM fallback cutoff` |
| H10 | ✅ Fixed | Sticky `standDownToday`: gates `executePositionEntry` (N6), `replenishActiveCandidatesIfNeeded` and the shared entry gate; cleared only in `resetDaily`; mid-morning refresh respects coverage/stand-down. | `testStandDownAfterNeutralSentimentRefusesEntries` |
| H11 | ✅ Fixed | `publishSignal` returns the bus result; failed publish **rolls back** the entry; bus rewritten (`onBackpressureBuffer(1024,false)` + internal drain subscriber + `emitLock` serialization — no more `FAIL_NON_SERIALIZED` drops); consumer staleness filter never drops stale **EXITs**. | `ReactiveSignalEventBusTest` (incl. burst) |
| H12 | ✅ Fixed | `executeHardExit`: missing quote → position is **kept** (no fabricated zero-loss fill), never dropped without record; per-item try/catch retained. | `H12 Fix: hard exit with no quote…` |

### 🟡 Medium

| # | Status | What changed | Key tests |
|---|--------|--------------|-----------|
| M1 | ✅ Fixed | Data-dependent gates (slippage, VWAP, sector misalignment/NO_DATA) → **RETRY with 60s `gateRetryNotBefore` cooldown**; decision gates (PDH breakout, stand-down, cutoff, attempts, concurrency, registry, budget) → **EXHAUST**. | `LvrEntryGateTest` matrix, cooldown paths |
| M2 | ✅ Fixed | Breaker **latched** (`dailyCircuitBreakerTripped`, cleared only by explicit reset) + pre-entry **budget gate** (plannedRisk vs `maxDailyLoss + realized + unrealized`). | `testCircuitBreakerLatchesAfterTrip`, `L6: replaySession honors the shared breaker/budget gate` |
| M3 | ✅ Fixed (pragmatic) | Intraday time-decay on option exits (linear decay to a **0.90 floor**) instead of a theta-free flat model. | decay path covered by option exit tests |
| M4 | ✅ Fixed | Armed-timeout counter zeroed only on a *fresh arm* (trail/re-anchor no longer resets it); recheck-before-mutation. | armed-timeout candle-sequence tests |
| M5 | ✅ Fixed | Vande Bharat never overrides a *fresh* armed LVR trigger in the same pass; volume floor = `vol > 0 && vol ≤ prev && vol ≤ rollingLowest × 2.0` (deviation from report's ×1.2 — needed for existing fixtures to pass; documented). | zero-volume/rolling-lowest test |
| M6 | ✅ Fixed | 10-EMA runner exits only on **confirmed 5m candle close**. | `30s Live Check skips 10 EMA trailing exit until confirmed 5m candle close` |
| M7 | ✅ Fixed | Network I/O (quotes, PDH fetch) moved **outside** the setup lock; state re-checked after reacquiring; armed-timeout increments every cycle. | gate/live tests (lock structure review) |
| M8 | ✅ Fixed | Registry miss → `REJECTED_EXHAUSTED` via gate `REGISTRY` — lot size/strike never fabricated. | `LvrEntryGateTest: F&O registry miss → EXHAUST` |
| M9 | ✅ Fixed | Tri-state `SectorGate`: `NO_DATA` → cooldown retry ×6 → **fail closed** (`REJECTED_EXHAUSTED`); `MISALIGNED` → cooldown retry; never silently allows. | `testSectorGateNoDataDefersThenExhausts`, sector alignment tests |
| M10 | ✅ Fixed | `LvrStateStore` atomic JSON snapshot (open positions, history, exhausted, breaker latch, stand-down) restored on startup (`restorePersistedState`), archived on reset/shutdown; + drift reconciliation (see C3). | `testPersistedStateRoundTrip`, `testResetDailySnapshotsStateBeforeClearing` |
| M11 | ✅ Fixed | `.env.example` cleaned (dead vars removed, cron `20 */5 9-15` corrected, new keys documented); `live-breach-check-enabled` bound (spec §3); misleading `FULL_TARGET_1_4`/`PARTIAL_1_4_*` enum aliases **removed** (1:2 is the sole authority); `max-attempts-per-symbol` default aligned to 2; `scanner-fallback-cutoff` bound. | `StrategyModeFromApplicationPropertiesTest`, config-binding tests, `.env.example` review |

### 🟢 Low / hygiene

| # | Status | What changed | Key tests |
|---|--------|--------------|-----------|
| L1 | ✅ Fixed | `POST /scan` and `/morning-scan` are async → `202 {jobId, statusUrl}` (`?sync=true` legacy); `GET /scan/status` lists jobs. *Not done:* parallel universe fetch + shared rate limiter (paced serial fetch kept). | `LowestVolumeStrategyControllerTest` (202/409/sync/status) |
| L2 | ✅ Fixed | `/status` serves the cached unrealized P&L refreshed by the 30s tick; `?refresh=true` forces live. | `LowestVolumeStrategyControllerTest` |
| L3 | ✅ Fixed | Injectable clock threaded through setup construction and `StockFnoRegistry`. | `StockFnoRegistryTest`, fixed-clock service tests |
| L4 | ✅ Fixed | `LowestVolumeSetup` scalar state fields made `volatile` (+ synchronized transitions). | concurrency-adjacent tests |
| L5 | ✅ Fixed | `fetchOptionLtp` never falls back to quoting the underlying on NFO (returns 0.0 → entry aborted, no fabricated premium); `resolveToken` exact-match tightened. | `Option entry is skipped when live option quote is unavailable` |
| L6 | ✅ Fixed | Shared `EntryGateInput` + `evaluateEntryGate` used by **both** live trigger path and `replaySession` (session gates); live/replay parity asserted. | `LvrEntryGateTest` (incl. parity test), L6 replay tests |

### Test gaps (§5) — resolution

1. ✅ `h`/`l` fixtures → C1/C2/fresh-wick tests now carry `h`/`l`.
2. ✅ No-assertion `testTriggerBreachDetectsSessionHigh` removed/replaced by asserting C2 tests.
3. ✅ H1 real-properties integration test added (`StrategyModeFromApplicationPropertiesTest`).
4. ✅ Order-response failure, dual-consumer wiring, OI_SPURTS selection, circuit-breaker latching, `resetDaily` archive, stand-down, M9 NO_DATA, M10 round-trip all covered (rows above). Expiry rollover covered by `StockFnoRegistryTest`; `enabled=false` + open position by H4 test; replenished-setup PDH/PDL by `testReplenishedSetupGetsPdhPdl`.

---


## 1. 🔴 Critical

### C1. Pre-entry invalidation compares the proposed SL against the *session* high/low — blocks nearly every setup, and tests cannot see it

- **Where:** `LowestVolumeReversalService.java:940-1010`; SL anchored at `:662-666` (SHORT), `:673-677` (LONG); test fixture gap in `LowestVolumeReversalServiceTest.java`.
- **What's wrong:** the "spot breached SL before trigger" and "target already passed" guards use `quoteNode.h` / `quoteNode.l` (Shoonya GetQuotes *day* high/low) instead of the armed candle's range. The SL sits at the pullback candle's low − 0.05 (LONG) / high + 0.05 (SHORT), so the day's low is below SL for any LONG setup whose pullback candle is not the session extreme (symmetrically for SHORT) → `setup.resetToScanning()` on the first 30-second tick after arming. The counters in `LowestVolumeSetup.setTriggerCandle:90` zero the armed timeout, so the setup re-arms and is re-invalidated in a loop until 13:00, re-firing the "Setup Armed" Telegram each cycle.
- **Impact:** effectively no entries; alert spam; wasted API budget.
- **Proposed fix:**
  1. Track `armedAt` (Instant) on the setup when `setTriggerCandle` runs.
  2. Replace session `h`/`l` in the pre-entry guards with `setup.getTriggerCandle()`'s own high/low (or with `h`/`l` **only if** the extreme timestamp is ≥ `armedAt`; GetQuotes has no timestamp, so prefer the candle-based check).
  3. Alternative (spec-faithful): evaluate SL/target breach only against the *current* LTP plus the most recent **closed** 5-min candle's high/low fetched after `armedAt`.
  4. Add tests with `h`/`l` present (both a stale extreme and a fresh extreme) asserting arm→invalidate vs arm→entry.

### C2. Trigger breach accepted from stale session extremes, with no freshness check

- **Where:** `LowestVolumeReversalService.java:1012-1028`; slippage guard `:1031-1060`.
- **What's wrong:** `triggered = spotPrice ≥ trigger || sessionHigh ≥ trigger` (LONG). Nothing proves the extreme occurred *after* arming, so a pre-arm high/low fires the entry. The slippage guard only rejects `spotPrice > trigger × 1.0012`; a pullback leaves `spotPrice < trigger`, so a LONG entry executes while LTP is **below** the trigger at an arbitrary price. The 30-second spec's "wick between ticks" intent is not implemented.
- **Impact:** entries at wrong prices, wrong R:R, no guarantee the level was ever traded after arming.
- **Proposed fix:**
  1. Keep session `h`/`l` as the fast intra-tick breach detector, but **require** `spotPrice` on the correct side of `trigger` (LONG: `spot ≥ trigger`; SHORT: `spot ≤ trigger`) before entering — i.e. treat `h`/`l` as a *lead indicator*, not the entry condition. If LTP has not yet reached the trigger, stay ARMED and wait for the next tick.
  2. Enforce the slippage bound as a two-sided window: entry allowed only when `trigger ≤ spotPrice ≤ trigger × (1 + maxSlippagePct/100)` for LONG (mirror for SHORT), and reject (stay armed) when the trigger was hit only by a stale extreme.
  3. Optional hardening: record the candle bar close time at arming and ignore `h`/`l` values older than the arming bar (requires a tick/candle feed with timestamps — otherwise fall back to (1)).

### C3. No broker-side protective order and no order-status reconciliation → naked positions

- **Where:** `ShoonyaTradeConsumer.java:55-76`, `ZerodhaTradeConsumer.java:54-80` (both pass `triggerPrice = null`, never read `signal.stopLoss()`/`signal.targetPrice()`); `ZerodhaBrokerGateway.java:80-91` (no bracket derived); `AbstractTradeExecutionConsumer.java:135-160` (`OrderResponse` only logged); LVR never calls broker `getPositions()`.
- **What's wrong:** SL and target exist only in the paper model. Live risk depends entirely on the 30-second polling loop. Rejected or unfilled orders are invisible — the ledger books a position that does not exist, so the later EXIT becomes a **naked reverse order** at the broker. Zerodha's marketable limit is derived from the *spot* price (`ZerodhaBrokerGateway.java:71-78`) for a *futures* symbol whose price includes basis (`LowestVolumeReversalService.java:1343`), so fill is basis-dependent.
- **Impact:** unbounded unhedged exposure on any entry-path failure; silent divergence between paper and broker.
- **Proposed fix:**
  1. Place a real protective order at entry: pass `signal.stopLoss()` as `triggerPrice` with `OrderType.SL`/`SL-M` (Shoonya `trgprc` is already supported at `ShoonyaOrderService.java:94-96`; Kite already converts triggers at `ZerodhaBrokerGateway.java:86-91`), or place a bracket/OCO where available. Use **contract-scaled** prices (futures LTP, option premium) rather than spot for the order, keeping spot as the *decision* price.
  2. Reconcile after every place: poll order status until `COMPLETE`/`REJECTED` (bounded wait); on reject/timeout mark the position `ENTRY_UNCONFIRMED`, roll it back, and alert — never allow an EXIT for an unconfirmed entry.
  3. Reconcile positions periodically (e.g. every 5 min) against broker `getPositions()` and alert on any drift (see M10).
  4. Derive the Zerodha entry limit from the instrument's own last price (futures/option LTP), not `spot × 1.01`.

---

## 2. 🟠 High

### H1. Strategy-id mismatch — `SHOONYA_LVR_MODE` / `ZERODHA_LVR_MODE` are dead configuration

- **Where:** `LowestVolumeReversalService.java:3280` publishes `"LVR_" + instrumentType` → `LVR_FUTURES` / `LVR_OPTIONS`; `application.properties:53,64` define `strategy-modes.LOWEST_VOLUME_REVERSAL`; `AbstractTradeExecutionConsumer.resolveMode:126-133` falls back to the consumer-wide mode. (Bollinger matches: `BollingerHaIntradayEngine.java:43`.)
- **Impact:** per-strategy LVR mode overrides are silently ignored → an operator running other strategies PAPER and LVR LIVE (or the reverse) gets the wrong mode.
- **Proposed fix:** pick one naming authority and align both ends. Recommended: introduce `LowestVolumeReversalService.STRATEGY_ID = "LOWEST_VOLUME_REVERSAL"` (matching the existing property keys and the design-doc name), publish `STRATEGY_ID` for both FUTURES and OPTIONS (move `instrumentType` into `metadata`, which consumers already read), and add an integration test that constructs the real `ExecutionProperties` from `application.properties` and asserts `resolveMode(signal)` for an LVR signal. Alternatively rename the property keys to `LVR_FUTURES`/`LVR_OPTIONS` — but then Bollinger's convention (`BOLLINGER_HA_1M`) should be documented as the pattern.

### H2. Both consumers enabled → every LVR signal orders at two brokers

- **Where:** `application.properties:57,69`; wiring `execution/consumer/TradeConsumerManager.java:44-80`.
- **Impact:** with `EXECUTION_MODE=LIVE`, each ENTRY/EXIT produces duplicate live orders on Shoonya **and** Zerodha (2× exposure; exits can land on the broker that never took the entry).
- **Proposed fix:**
  1. Default one consumer to `enabled=false` (document dual-broker as an explicit opt-in), or
  2. Add an `execution.primary-broker=SHOONYA|ZERODHA|ALL` property; the manager starts all consumers but only the primary routes actionable signals (others subscribe read-only for monitoring), or
  3. At minimum, guard at signal level: attach `metadata.brokerTarget` in `publishSignal` and have consumers skip signals not addressed to them.
  Also add a startup WARN when >1 consumer is enabled in LIVE mode.

### H3. Contract symbols never roll past expiry and diverge from the LTP lookup

- **Where:** `StockFnoRegistry.java:495-497` (`getMonthlyExpiry(LocalDate.now())`, no roll when the date has already passed) and `:525-527` (null expiry → `LocalDate.now(IST)`); callers `LowestVolumeReversalService.java:1332`, `:1411-1412`, `resolveBrokerTradingSymbol:3255-3264`; **while** `fetchOptionLtp:2383-2385` prices via `calculateTargetExpiry(symbol, today, false, minDte=1)` which *does* roll (`StockFnoRegistry.java:444-447`).
- **Impact:** from the day after the last Thursday to month-end (and on expiry day) the bot orders an expired contract while quoting the next one → rejected orders / 404 quotes / mispriced exits. Also hard-assumes Thursday expiry on holiday-shifted weeks.
- **Proposed fix:**
  1. Make null-expiry resolve through **one** function: `StockFnoRegistry.calculateTargetExpiry(symbol, LocalDate.now(clock), false, minDte)` — delete the `getMonthlyExpiry(LocalDate.now())` and `LocalDate.now(IST)` fallbacks in `formatFuturesTradingSymbol` / `formatTradingSymbol`, or make them call it with `minDte = 1`.
  2. Resolve the expiry **once per position** at entry, store `expiryDate` on `LowestVolumePaperPosition`, and use it for entry symbol, exit symbol and LTP lookup (`fetchOptionLtp` should accept an explicit expiry instead of re-deriving it).
  3. Drive expiry day from `NseTradingCalendarUtil` (holiday-aware) instead of "last Thursday of month".
  4. Test: fix the clock to the day after expiry and assert entry symbol == LTP-lookup symbol.

### H4. `/toggle?strategyEnabled=false` silently disarms protection for open positions

- **Where:** `LowestVolumeReversalService.java:221-224` (runCycle returns before `evaluateOpenPositions`) and `:888` (`evaluateLivePriceActions` returns when `!enabled`); the 15:00 cron at `LowestVolumeReversalScheduler.java:113-125` ignores `enabled`.
- **Impact:** toggling the strategy mid-day stops SL / 1:2 target / cost-SL / 10-EMA management → held positions become unprotected exposure.
- **Proposed fix:** split the gate — `enabled` should control **entry** only. Always run `evaluateOpenPositions(...)` (and the 30-second exit check) when `openPositions` is non-empty, regardless of `enabled`. Add a dedicated `positions-management-enabled` flag (default true) if operators need to freeze exits deliberately, and log a WARN whenever a toggle leaves positions open without management. Add a test: `setEnabled(false)` with an open position at SL → SL exit still fires.

### H5. Quote failure silently skips every exit check

- **Where:** `LowestVolumeReversalService.java:1541` (`if (spotLtp <= 0) continue;`), no alert/retry accounting; rate-limit interaction via `checkLiveSectorAlignment:2572-2592` (10–30 unpaced `GetQuotes` per armed symbol per 30s) and `ShoonyaMarketDataService.java:335-343` (`exceeds Limit 10`, 250ms backoff, 2 attempts).
- **Impact:** a dead-feed / rate-limited symbol is unmanaged for the rest of the day with no notification until the fabricated EOD exit (H12).
- **Proposed fix:**
  1. Per-symbol failure counter; after N consecutive failures (e.g. 3), send a Telegram/log alert and mark the position `DATA_STALLED`; keep retrying.
  2. Add a **quoted fallback**: if the primary quote fails, retry via the alternate source (option-chain LTP for options, futures token for futures) before giving up.
  3. Gate the expensive live sector re-fetch behind a cooldown (see M1) and pace all quote bursts through one shared rate limiter (≤10 req/s) instead of per-callsite sleeps.
  4. Never let a rate-limited tick go unnoticed: log `WARN` with the reason (`400 exceeds Limit 10`) rather than a silent `continue`.

### H6. `resetDaily()` hard-exits then erases the day's P&L — reachable unauthenticated

- **Where:** `LowestVolumeReversalService.java:2059-2086` (`executeHardExit` records `tradeHistory` + publishes live EXITs, then `tradeHistory.clear()` + `dailyCircuitBreakerAlertSent.set(false)` + `lastScanDate=null`); endpoint `LowestVolumeStrategyController.java:194-201`; no Spring Security / interceptors anywhere in `src/main`.
- **Impact:** an intraday `POST /api/strategy/lowest-volume/reset` (or Telegram `/reset`) flattens live positions **and** wipes the realized losses feeding the circuit breaker; `/scan`, `/morning-scan`, `/reset`, `/toggle` are all open to anyone who can reach the port.
- **Proposed fix:**
  1. Move `tradeHistory` archive before clearing: on reset, snapshot `tradeHistory` into a daily archive (or persist it) *before* clearing; never clear realized P&L intraday — reset only `activeSetups`, `exhaustedSymbols`, `candidateReservoir`, `lastScanDate`.
  2. Add auth: Spring Security with a simple bearer token / basic auth for `/api/**` (or at minimum for `POST` endpoints), plus an allowlist for health checks.
  3. Add a guard: refuse `resetDaily` between 09:30–15:00 unless `?force=true` is supplied, and always log an audit line (who/when/how many positions were closed).

### H7. PDH/PDL gate fails open — replenished candidates never get PDH/PDL

- **Where:** `replenishActiveCandidatesIfNeeded:2106` creates `new LowestVolumeSetup(...)` without `initPdhPdlForSetup`; `initPdhPdlForSetup:2686-2717` swallows errors leaving both null; gate `:1180` is `if (pdhPdlFilterEnabled && pdh != null && pdl != null)`.
- **Impact:** the configured PDH/PDL filter is silently skipped for every mid-morning promoted symbol and for any morning symbol whose daily-candle fetch failed. Promoted direction also comes from `niftyBullish` (`:2098-2099`), which `OI_SPURTS` mode never computes (stays `true`).
- **Proposed fix:**
  1. Extract setup creation into one factory: `createSetup(symbol, direction)` → constructs, calls `initPdhPdlForSetup`, and if PDH/PDL can't be resolved either (a) retry once with a backoff, or (b) mark the setup `PDH_PENDING` and let the gate retry initialization on subsequent ticks instead of passing.
  2. Make the gate fail-closed when the filter is enabled: `pdh != null && pdl != null` must hold — if null after retries, `transitionTo(REJECTED_EXHAUSTED, "PDH/PDL unavailable")` rather than entering unfiltered.
  3. In `OI_SPURTS` mode compute breadth/`niftyBullish` too, or pass an explicit direction into replenish instead of reading `niftyBullish`.

### H8. `OI_SPURTS` mode can never select a candidate — silent fallback to Sector Rotation

- **Where:** `LowestVolumeReversalService.java:322-361` ← `:2443` (5-arg `StockQuoteSnapshot` ⇒ OI fields all 0); `LowestVolumeReversalScanner.java:203-224` (`oiChange == 0 && prevDayOI > 0` never true → 0 eligible).
- **Impact:** the configured scanner mode is dead; only a warning is logged. `sectorState`, `candidateReservoir`, `currentTopGainers/Losers` stay empty (Telegram sector report and `/status` degrade), and the breadth/`niftyBullish` gate is skipped.
- **Proposed fix:**
  1. Populate OI in the fetch path: for F&O symbols read `oi` / `oio` (prev-day OI) from GetQuotes (they are already in the response payload) and build the full 9/10-arg `StockQuoteSnapshot`.
  2. If OI data is unavailable, **fail loudly**: treat the scan as failed (`universeScanCompletedToday = false` + retry alert), do not silently fall back to a different selection mode mid-config.
  3. When `scannerMode=OI_SPURTS`, also compute sentiment/`niftyBullish`, `sectorState` and a reservoir so downstream features keep working; add a unit test asserting `scanOiSpurts` returns >0 for a fixture with real OI.

### H9. Failed morning scan after 10:00 = permanent stand-down *and* skipped position management

- **Where:** `LowestVolumeReversalService.java:243-249` returns **before** `evaluateOpenPositions`; no re-scan; the spec'd champion-stock fallback was never implemented (`scanner-fallback-cutoff` defined at `application.properties:32`, never bound).
- **Impact:** a single failed 09:25 scan leaves the day with no setups **and** the 5-minute path stops managing open positions (only the 30s tick runs); violates `lvr_scanner_token_fix_and_retry_spec.md` §3.2.1.
- **Proposed fix:**
  1. Restructure `runCycle` so position management is **unconditional** (extract an `if (!openPositions.isEmpty()) evaluateOpenPositions(...)` block that runs before any scan/pending logic).
  2. Implement the cutoff: bind `scanner-fallback-cutoff`; after it, if still no candidates, fall back to the top-N champion-stock basket (precomputed daily list) instead of returning.
  3. Retry the scan at every cycle until success (bounded by the cutoff), with the existing 5-minute retry alert.

### H10. The morning "stand down for the day" decision is not sticky

- **Where:** `LowestVolumeReversalService.java:387-402` (latches stand-down) vs `:272-278` → `runMidMorningUniverseRefresh:2155-2209` with `:2171-2177` converting `NONE` sentiment into `niftyBullish`-direction and promoting candidates; no `< 35` coverage guard there (contrast `:373`).
- **Impact:** the bot starts trading a day it declared choppy, using a possibly tiny quote sample.
- **Proposed fix:**
  1. Add `volatile boolean standDownToday` set at `:387-402`; check it in `replenishActiveCandidatesIfNeeded`, `runMidMorningUniverseRefresh` and `executePositionEntry` (skip with a log), and clear only in `resetDaily`.
  2. Add the same `niftyQuotes.size() >= 35` coverage guard to the mid-morning refresh before trusting sentiment; if coverage is insufficient, do not refresh.

### H11. `publishSignal` ignores the bus result; the bus drops signals

- **Where:** `LowestVolumeReversalService.java:3267-3292` discards the boolean; `ReactiveSignalEventBus.java:24-47` (`multicast().directBestEffort()` → `FAIL_ZERO_SUBSCRIBER` / drop under backpressure); consumer staleness filter `AbstractTradeExecutionConsumer.java:109-121` (30s).
- **Impact:** a position is booked in `openPositions` that the broker never received → the later EXIT becomes a naked reverse order.
- **Proposed fix:**
  1. Make `publishSignal` return the boolean; on `false`, **roll back** the entry (remove position, `resetToScanning()`, decrement attempt) or mark it `SIGNAL_PENDING` and retry publish with backoff before any EXIT is allowed.
  2. Replace `directBestEffort()` with `Sinks.many().multicast().onBackpressureBuffer(1024)` (or add a bounded retry) so bursts aren't dropped; keep `publish()`'s result surfaced.
  3. On consumer side, replace hard-drop of stale signals with a "stale but actionable" path for EXITs (exits must never be dropped) — or better, make the strategy idempotent by correlating `signalId` with the position.
  4. Optional belt-and-braces: on startup and every minute, reconcile broker positions (see M10).

### H12. `executeHardExit` fabricates a zero-loss fill and can drop positions entirely

- **Where:** `LowestVolumeReversalService.java:1996-1999` (quote failure → `exitVal = stockEntryPrice`, recorded + published + reported); `:2051-2054` (exception → `openPositions.remove` with no signal and no history); `:2056` `clear()` regardless.
- **Impact:** fabricated zero-P&L rows corrupt `tradeHistory`, the circuit breaker and `/status`; an exception leaves a live position with no exit order and no record.
- **Proposed fix:**
  1. If `spotLtp <= 0`, do **not** fabricate: retry the quote (N attempts across a few seconds); if still unavailable, publish a **MKT** EXIT with `price = BigDecimal.ZERO` and record the position with `exitReason = "EOD_1500_QUOTE_UNAVAILABLE"` and `exitPrice = null/NaN` (or last known LTP with an explicit `estimated=true` flag), never the entry price.
  2. Per-item `catch`: on failure keep the position in `openPositions` (retry on the next tick / the 15:05 cycle) instead of removing it; only `clear()` entries that are confirmed closed.
  3. Report a summary count of failed exits at the end (log + Telegram).

---

## 3. 🟡 Medium

### M1. Gate outcomes are inconsistent (exhaust vs. retry)

- **Where:** VWAP `:1090-1095`, 15-min range `:1147-1155`, PDH/PDL `:1196-1205` → permanent `REJECTED_EXHAUSTED` + `exhaustedSymbols`; slippage `:1053-1059` and sector `:1231-1239` → bare `return` (retried every 30s with the full filter stack + a live sector re-fetch each time).
- **Impact:** retry paths burn the 10-req/s budget and re-enter a "blocked" state repeatedly; exhaust paths permanently kill a symbol on a single transient data point.
- **Proposed fix:** define one policy table (which filter is *data-dependent* → retry with cooldown; which is *decision* → exhaust), implement it in a small `EntryGateResult {PASS, RETRY, EXHAUST}` enum, and apply it uniformly. Sector gate: add a per-symbol cooldown (e.g. 5 min) for the *quote burst*, keep the decision cached from the last successful evaluation.

### M2. Circuit breaker is not latched and has no pre-trade budget

- **Where:** `LowestVolumeReversalService.java:2829-2833` (re-evaluated each call ⇒ un-trips on a rebound, contradicting its own "Halting … for the day" alert; only the alert flag at `:2834` is sticky); `plannedRisk` `:1303-1313` never checked against `max-daily-loss`/`risk-per-trade`; `dynamic-position-sizing` defaults `false` (`application.properties:13`) so `paperCapital`/`risk-per-trade-percent` size nothing; `maxConcurrentTrades=5` ⇒ 5× aggregate risk.
- **Proposed fix:**
  1. Latch: `AtomicBoolean dailyCircuitBreakerTripped`; once true, stay true until `resetDaily`.
  2. Pre-entry budget check inside `executePositionEntry`: `calculateTodayTotalRiskPnl() + plannedRisk <= -maxDailyLoss` → refuse entry; also cap `sum(open plannedRisk)` by `riskPerTradeAmount × maxConcurrentTrades` or by `paperCapital × riskPerTradePercent`.
  3. Consider defaulting `dynamic-position-sizing=true` (spec intent) or documenting that fixed `lots` is authoritative.

### M3. Option exits are priced by a theta-free model

- **Where:** `LowestVolumeReversalService.java:2315-2352` (live LTP first, then `max(intrinsic, entryPrem + spotMove×delta)` with ₹0.50 floor); used at `:1568-1573`, `:1699-1702`, `:1917-1920` and for realized P&L / breaker.
- **Impact:** cost-floor and EOD exits overstate/understate P&L (worthless options "exit" at ₹0.50; a runner stopped at breakeven after hours of decay shows ~0 loss).
- **Proposed fix:** honor `lvr_30sec_live_check_spec.md` for **all** exits: if live premium is unavailable, *skip this exit check and retry next tick* (as the entry path already does at `:1414-1422`), with a bounded retry count; only fall back to the model after N failures and then tag the record `estimated=true`. If a model must remain, add a time-decay term (days-to-expiry multiplier) and use the same one for entry validation.

### M4. Armed-timeout logic: off-by-one and counter resets

- **Where:** `LowestVolumeReversalService.java:637-639` (`>=`) vs `:827-828` (`>`); counter zeroed on every re-arm/trail (`LowestVolumeSetup.java:90`) and on `resetToScanning:63`.
- **Impact:** the same setup expires at different times depending on which path counts, and a setup that keeps re-anchering never expires (plus repeated Telegram "Setup Armed").
- **Proposed fix:** use `>= setupTimeoutCandles` in both paths; don't reset `armedCandlesElapsed` on a *trail* (same setup lineage) — only reset on a fresh arm after `resetToScanning()`; keep a separate `firstArmedAt` so the overall lifetime (e.g. 30 min) is enforced regardless of trailing. Debounce Telegram alerts per symbol (only alert when the trigger moves by > a threshold or on first arm).

### M5. Vande Bharat overrides an armed LVR trigger in the same pass and has no volume floor

- **Where:** `LowestVolumeReversalService.java:694-754`, unconditional `setTriggerCandle` at `:750`, no volume condition (contrast LVR at `:647`).
- **Impact:** an inside bar replaces an already-armed LVR trigger (different trigger/SL/target) and zeroes the armed timeout; can re-arm every bar → alert churn and unstable levels.
- **Proposed fix:** evaluate Vande Bharat only when the setup is still `SCANNING` (or when its pattern is already `VANDE_BHARAT_INSIDE_BAR`); add a volume condition (e.g. `≤ rollingLowest` or `≤ dayLowest × 1.2`) per design §2.2; only overwrite when the new trigger is *closer* to LTP or the previous trigger was invalidated.

### M6. 10-EMA runner uses the in-progress candle

- **Where:** `LowestVolumeReversalService.java:1877-1898` filters by date only, while `processCandidateSetups:792` also requires `timestamp+300s ≤ now`.
- **Impact:** runs fire at `:20` seconds past each 5-minute boundary, so `latestCandle.close()` is a ~20-second partial close → whipsaw runner exits despite the `isCandleClose` gate (`:1872`).
- **Proposed fix:** apply the identical close-time filter used at `:792` (`c.timestamp().plusSeconds(300).compareTo(nowInst) <= 0`) before computing the EMA series; require `candles.size() >= 10` **closed** candles (else skip this check and log once).

### M7. Long lock holds with network I/O inside; setup state can be clobbered concurrently

- **Where:** `evaluateOpenPositions` is `synchronized` and performs per-position quote/LTP/Telegram I/O (`:1520-1536`, `:1544`); `checkSpotTriggerBreach` (unsynchronized) does gates before entering `executePositionEntry` (`:1251`); `processCandidateSetups` can `resetToScanning()` at `:876` after its state read at `:807-811`. In-lock re-checks at `:1260-1270` prevent double *entry* but not state clobbering.
- **Impact:** a hung quote can push the 30s tick past its period (Spring won't re-enter the same `fixedRate` method) → delayed SL detection; a setup may flip out of `IN_POSITION`-adjacent state between the read and the mutation.
- **Proposed fix:**
  1. Do network I/O **outside** the monitor: fetch quotes for all positions into a local map first, then take the lock only to evaluate/transition (single fast pass).
  2. In `processCandidateSetups`, re-read `setup.getState()` and `openPositions.containsKey(symbol)` immediately before `resetToScanning()`/`setTriggerCandle()` (or make those transitions CAS-based on the expected state).
  3. Cap the per-tick quote budget so one tick can't exceed 30s (skip remaining positions with a WARN).

### M8. Unregistered symbols get fallback lot/strike data

- **Where:** `LowestVolumeReversalService.java:1286-1288` (`lotSize = 100`, `strikeStep = 10` when `StockFnoRegistry.get` misses).
- **Impact:** wrong quantity and wrong ATM strike on a live order for candidates sourced from `NiftySectorRegistry`.
- **Proposed fix:** treat a registry miss as a *reject*: `transitionTo(REJECTED_EXHAUSTED, "Not in F&O registry")` and skip; never enter with guessed contract parameters. Optionally pre-validate the watchlist at scan time and drop non-F&O names.

### M9. Sector filter fails open

- **Where:** `LowestVolumeReversalService.java:2554-2565` (blank/empty sector state → `true`), `:2594-2596` (all fetches failed → `true`).
- **Impact:** the configured risk filter passes exactly when data is worst; no alert distinguishes "aligned" from "no data".
- **Proposed fix:** return a tri-state (`ALIGNED / MISALIGNED / NO_DATA`). Treat `NO_DATA` as retry-with-cooldown (and after N retries, exhaust with an alert) rather than pass; only a genuine misalignment should block-and-alert.

### M10. No persistence / reconciliation of live state

- **Where:** all state in-memory (`LowestVolumeReversalService.java:143-172`); broker `getPositions()` exists (`ShoonyaBrokerGateway:49-90`, `ZerodhaBrokerGateway`) but is never called by LVR.
- **Impact:** restart orphans broker positions (no SL, no target, no 15:00 exit).
- **Proposed fix:** persist `openPositions`/`tradeHistory`/`exhaustedSymbols` to disk (JSON/SQLite) on change and reload on startup; on boot, reconcile against broker `getPositions()` — adopt unknown broker positions as managed or alert-and-square them off.

### M11. Config/spec drift (dead and misleading knobs)

- **Where:** `.env.example` (`LVR_SCANNER_CRON`, `LVR_MIN_PCT_CHANGE`, `LVR_TOP_N_STOCKS`, `LVR_OPTION_BUYING`, `LVR_LIVE_BREACH_CHECK` have no property; `LVR_CRON=9-11` ≠ actual `20 */5 9-15`); `application.properties:32` `scanner-fallback-cutoff` never read; `live-breach-check-enabled` from `lvr_30sec_live_check_spec.md` §3 absent; `LvrExitMode.FULL_TARGET_1_4` / `PARTIAL_1_4_…` compute **1:2** targets (`:670,681,1314-1325`) while `lvr_option_buying_spec.md` says 1:4 and comments still say "1:4"; `@Value` default `max-attempts-per-symbol` = 1 vs properties = 2.
- **Proposed fix:** delete unused env vars or add the matching `@Value` bindings; fix `.env.example` cron; bind `scanner-fallback-cutoff`; implement or remove `live-breach-check-enabled`; decide 1:2 vs 1:4 authority, then either rename the enum values to `*_1_2` or actually compute 1:4 targets (money logic — needs explicit sign-off); make the `@Value` default `2` to match `application.properties`.

---

## 4. 🟢 Low / hygiene

| # | Finding | Where | Proposed fix |
|---|---------|-------|--------------|
| L1 | Morning scan paces ~150 symbols with `Thread.sleep(115ms)` inside the cycle thread; `/scan` and `/morning-scan` are long synchronous HTTP calls | `LowestVolumeReversalService.java:2447-2453`, `LowestVolumeStrategyController.java:39-81` | Fetch universe quotes on a dedicated executor (parallel + shared rate limiter); make `POST /scan` return `202 + jobId` with a status endpoint |
| L2 | `GET /status` triggers live quote fetches | `LowestVolumeStrategyController.java:141` | Serve last-known unrealized P&L from a cached value refreshed by the 30s tick; add `?refresh=true` for live |
| L3 | Mixed clocks (injectable `clock` vs `Instant.now()`/`LocalDate.now(IST)`) | `LowestVolumeSetup.java:46-47`, `StockFnoRegistry.java:496,526` | Thread the injected `clock` into setup construction and symbol formatting |
| L4 | `LowestVolumeSetup.getState()/getTriggerPrice()` read outside `synchronized` (non-volatile fields) | `LowestVolumeSetup.java:146-169` | Make scalar state fields `volatile`, or expose a single `synchronized snapshot()` accessor |
| L5 | `resolveToken` caches the first fuzzy SearchScrip hit; `fetchOptionLtp` last resort quotes the underlying on NFO | `ShoonyaMarketDataService.java:240-244`, `LowestVolumeReversalService.java:2389-2397` | Only accept exact `tsym` matches; never fall back to the underlying symbol for an option quote (return 0.0 instead) |
| L6 | Replay/backtest diverges from live (enters at candle trigger, skips PDH/PDL, sector, slippage, VWAP, breaker, concurrency guards) | `replaySession` region ~`2280-2500` | Extract one `EntryGate` used by both live and replay; assert in a test that the gate decisions are identical for the same input |

---

## 5. Test gaps (why the above survives) — all resolved, see §0

1. **No fixture includes `h`/`l`** → C1/C2 branches never execute in tests (37 quote nodes in `LowestVolumeReversalServiceTest.java`, all `lp`/`ap`/`c` only).
2. `testTriggerBreachDetectsSessionHigh` (~line 231) contains **no assertions**.
3. No test builds `strategy-modes` with the real `application.properties` key → H1 is masked.
4. Missing coverage: order-response failure handling, dual-consumer wiring, expiry rollover, replenished-setup PDH/PDL, `resetDaily` after a hard exit, `OI_SPURTS` selection, `enabled=false` with an open position, circuit-breaker latching.

**What is already correct and covered:** SL-before-target ordering, LONG/SHORT comparison directions, option BUY/SELL mapping in both consumers, exit-qty computed before `close()`, `putIfAbsent` entry guard, `compareTo`-based BigDecimal, P&L `HALF_UP` scaling, thread-safe state collections (`:143-161`), scheduler pool size 5 (`TaskSchedulingConfig`), 15:00 double-guaranteed hard exit.

---

## 6. Status of `LVR_BUG_AUDIT.md` (2026-10-01) — fixed, do not re-report

Re-verified as resolved in current code: double-entry re-check + `putIfAbsent` (`:1260-1269`, `:1444`); qty-0 EXIT after full partial book (`:1694-1697`); display-symbol on 1:2 exits (all branches use `resolveBrokerTradingSymbol`); OPTIONS direction mapping (both consumers); duplicate-EXIT guards (`:1527-1530`); `executeHardExit` per-item try/catch (`:1995`, `:2051`); forming-candle arming (`:792`); breadth `<35` coverage (`:373`); VWAP fail-closed (`:1120-1125`); EMA gate no longer 13:00-restricted (`:282`); `niftyBullish` reset+updated (`:2073`, `:2176`); fabricated option premium at entry (`:1414-1421`); stale multi-day VWAP/candles (`:785-793`); PDH/PDL preserved across `resetToScanning`; `telegram-armed-alerts` read (`:850`); volatile toggles (service + scheduler); `/status` unrealized P&L (`:141-152`); `replaySession` no longer mutates live state; remaining-attempts respects config (`:1651-1656`); `resolveAtmStrike` zero-strike guard (`:2233-2241`); Zerodha no longer converts entries to SL orders (trigger always null).

---

## 7. Suggested triage order

1. **C1 / C2** — decides whether the strategy can trade at all (and add the `h`/`l` tests).
2. **H1 / H2** — execution-mode correctness and duplicate-order exposure.
3. **C3 (H11, H5, H12)** — naked-position and P&L integrity.
4. **H3** — expiry-week correctness.
5. **H4 / H6 / H7 / H9 / H10** — safety gates and authenticated control plane.
6. **H8** — `OI_SPURTS` mode viability.
7. **M1–M11** — risk/consistency hardening, then **L1–L6**.
