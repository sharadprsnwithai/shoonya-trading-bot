# NSE OI Spurts (09:25) Replacement for LVR OI Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Remove the existing quote-based Open Interest logic in the Lowest Volume Reversal (LVR) strategy (the broken `poi`/futures-OI enrichment path that returned 0 OI change all day) and replace it with NSE's live OI spurts API (`https://www.nseindia.com/api/live-analysis-oi-spurts-underlyings`), fetched **once at 09:25 IST**. The top 3 underlyings by % change in OI are merged into the LVR watchlist alongside the sector candidates chosen at 09:25 — the combined watchlist (3 sector + 3 OI = 6) is complete right after the morning scan finishes (~09:27), comfortably before entries unlock at 09:30.

**Architecture:** Two-phase selection, both phases on 09:25. A new scheduler job at **09:25:00** (`0 25 9 ? * MON-FRI`) calls `LowestVolumeReversalService.runNseOiSpurtAugmentation()`, which fetches the NSE payload through a new `NseOiSpurtClient`, ranks by `% change in OI` (`avgInOI`), and stores the ranked list in `pendingOiItems` (sentiment is not yet known — the 09:25 scan has not run). The 09:25:20 candle cycle then runs `runMorningUniverseScan()` (unchanged timing): sentiment + winning sector → up to 3 sector candidates; all inline OI logic (`scanner.scanOiSpurts`, quote `oi`/`poi` reads, NFO futures-OI enrichment) is removed. At the END of a successful scan it consumes `pendingOiItems` and calls the shared, idempotent `mergeOiCandidates(...)`, which applies the sentiment price-alignment band (relaxed fill to always reach 3) and merges the picks into `activeSetups`. If NSE fails after retries, the strategy continues sector-only (WARN + Telegram, no trades blocked). A **mid-morning NSE refresh (enabled by default)** additionally tops up the standby `candidateReservoir` when the existing 10:30+ refresh triggers.

**Tech Stack:** Java 21, Spring Boot 3, Gradle (Spotless/SpotBugs/JaCoCo), JUnit 5, Mockito, Jackson, `java.net.http.HttpClient`.

**Spec:** Supersedes the "OI Spurts Selection" portion of `docs/superpowers/specs/2026-10-03-oi-spurts-vande-bharat-pdh-pdl-lvr-design.md`. The Vande Bharat pattern, PDH/PDL gate, and option-chain PCR/Max-OI S&R gates are **out of scope** and must not be touched.

## Global Constraints

### Verified NSE API contract (probed 2026-10-12 from this machine)
- URL: `https://www.nseindia.com/api/live-analysis-oi-spurts-underlyings` — returned **HTTP 200** with a plain Java `java.net.http.HttpClient` (browser User-Agent + `Referer: https://www.nseindia.com/market-data/oi-spurts`), **no cookie bootstrap required** in the off-market probe. NSE sits behind Akamai bot management (`_abck`/`bm_sz` cookies observed); live-market behavior is UNVERIFIED — cookie bootstrap + retries + fail-soft are therefore mandatory, not optional.
- Top-level shape: `{"data":[ ... ], "timestamp":"12-Oct-2026 15:40:11", "currTradingDate":"12-Oct-2026", "prevTradingDate":"09-Oct-2026"}`
- `data[]` entry (all fields): `{"symbol":"DMART","latestOI":127822,"prevOI":85961,"changeInOI":41861,"avgInOI":48.7,"volume":382076,"futValue":125305.6257,"optValue":188863190280,"total":148495.2285,"premValue":23189.6028,"underlyingValue":3364}`
- 219 entries; payload is already sorted by `avgInOI` DESC (verified: 48.7 → 25.76 → 21.78 → 13.13 → 13 → 10.67 …). `avgInOI` = `(changeInOI / prevOI) * 100` (verified: DMART 41861/85961 = 48.7). Re-sort defensively anyway.
- Index underlyings ARE present and must be excluded: `NIFTY`, `BANKNIFTY`, `FINNIFTY`, `MIDCPNIFTY`, `NIFTYNXT50`, `NIFTYFPI` (+ keep the existing `startsWith("NIFTY ")` guard). Stock symbols like `RBLBANK`/`ICICIBANK` must NOT be excluded by naive substring matching.
- Stocks outside the bot's NIFTY-50/sector universe (e.g. `DMART`, `UJJIVANSFB`, `DELHIVERY`) legitimately appear and are eligible per operator decision — futures/quote/PDH resolution works dynamically for any symbol via `ShoonyaMarketDataService.resolveFuturesContract` → `searchScrip` and `resolveToken` (see `ShoonyaMarketDataService.java:304-362`), not only registry symbols.

### Operator decisions (confirmed 2026-10-12)
1. **Single morning fetch at 09:25 (revised 2026-10-12):** the NSE job fires at **09:25:00** (`0 25 9 ? * MON-FRI`), i.e. before the 09:25:20 candle cycle; candidates are stored as pending and merged at the end of the morning scan (~09:26-09:27). No 09:30 job exists. Entries unlock at 09:30 (`TIME_EVALUATION_START`, `LowestVolumeReversalService.java:59`) — the full watchlist is therefore ready before trading begins; only a failed/retried morning scan can push the merge later (pending survives until consumed).
2. **Ranking metric:** `% change in OI` (`avgInOI`, descending) — matches NSE's own page ordering.
3. **Universe:** full NSE list minus indices (no intersection with NIFTY-50/sector universe).
4. **Alignment:** apply today's sentiment price band (LONG: `0 < pctChange <= 5`, SHORT: `-5 <= pctChange < 0`); if fewer than 3 qualify, fill remaining slots from unfiltered top-ranked OI stocks so the OI leg always yields 3 when data exists.
5. **Failure mode:** up to 3 attempts with backoff inside the job, then continue **sector-only** with WARN + one Telegram notice. Never fail the day, never block trades.
6. **Mid-morning refresh: ENABLED by default** (`LVR_NSE_OI_MIDMORNING_REFRESH=true`) — when the existing 10:30+ mid-morning refresh triggers, re-fetch NSE OI spurts and top up the standby `candidateReservoir` (no `activeSetups` mutation, no Telegram).

### Behavioral constraints
- Direction for merged HYBRID OI setups = morning sentiment (`sectorState.sentiment()`); direction for OI_SPURTS mode setups = the stock's own price direction (preserves `LowestVolumeReversalService.java:605-608` semantics).
- HYBRID caps: ≤3 sector + ≤3 OI = ≤6 active setups (unchanged). OI_SPURTS mode: OI picks become the active setups; sector picks demote to `candidateReservoir` (mirrors today's OI-only selection); on NSE failure the sector picks simply stay active (mirrors today's "no qualifying spurt" fallback at lines 635-643).
- `activeSetups` is a `ConcurrentHashMap` (`LowestVolumeReversalService.java:194`) — mutation is memory-safe; use CAS guards (`isScanning`, new `isAugmenting`) to keep logical overlap out.
- Keep `StockQuoteSnapshot`'s OI record components (model unchanged) but stop populating them from quotes; keep `LowestVolumeSetup.setOiChangePct` and populate it from NSE `avgInOI` for OI picks only (it is write-only in production today).
- Keep config keys `scanner-mode` (HYBRID/OI_SPURTS still meaningful) and `oi-spurts-top-n` (line 29, still the OI_SPURTS mode count = 5). New HYBRID count comes from `nse-oi.top-n` (default 3).
- Javadoc on every new class/method and TDD (failing test first) per repo convention; `./gradlew spotlessApply` before finishing.

### Day-timeline sanity (verified in code — do not regress)
| Time | Event | State relevant to OI |
|---|---|---|
| 09:15:00 | `scheduledDailyReset` → `resetDaily()` | clears all daily state incl. `pendingOiItems`/`nseOiAppliedToday`; sets `lastScanDate = null` (`:2758`) |
| 09:15:20 / 09:20:20 | candle-cycle cron fires but scheduler guard `now < 09:25` returns (`Scheduler:75`) | untouched |
| **09:25:00** | **NEW: `scheduledNseOiSpurts` → `runNseOiSpurtAugmentation()`** | fetches NSE → stores `pendingOiItems` (scan not run yet) |
| 09:25:20 | `runCycle`: day-detect at `:442` is `lastScanDate != null && …` → **false** (09:15 reset nulled it) → no second `resetDaily` → scan runs | pending survives |
| ~09:26-09:27 | scan completes → `universeScanCompletedToday = true` → consume pending → merge | watchlist = 6 |
| 09:30:00 | entries unlock (`isWithinTradingHours`) | watchlist already complete |
| 10:30+ (conditional) | mid-morning refresh (only when 0 actionable setups, `runCycle:521-527`) | NSE re-fetch tops up `candidateReservoir` |

## Review Focus

1. **Akamai live-market behavior at 09:25:00** — the API was verified off-market (CDN `cache: HIT`). The client must send browser UA/Referer, hold a `CookieManager`, retry with backoff, re-bootstrap cookies from `https://www.nseindia.com` (ignore that homepage may 403) before the final attempt, and degrade to sector-only on total failure. Re-verify on the first live trading morning (log lines in Task 9).
2. **09:25:00-job / 09:25:20-scan ordering** — fetch-before-scan is the NORMAL path now; the pending-cache protocol covers it: scan consumes `pendingOiItems.getAndSet(null)` only AFTER `universeScanCompletedToday = true`; the job stores then re-checks `universeScanCompletedToday` and consumes-once itself if a manual/out-of-order scan won the race. Both paths call the same idempotent `mergeOiCandidates` (skip symbols already in `activeSetups`/`exhaustedSymbols`). The converse race (reset wiping the fetch) is excluded by the timeline above: only the 09:15 new-day reset clears pending, and it runs 10 minutes before the fetch. Same-day soft `/reset` must **preserve** `pendingOiItems` (and only clear them on new-day/force resets) so an intraday reset can still consume them on the rebuilt scan — see Task 3 Step 3.
3. **Stale payload guard** — `currTradingDate` must equal today's date (`dd-MMM-yyyy`, e.g. `12-Oct-2026`); a previous trading date means NSE served cached data → treat as failure (WARN + retry/fail-soft). At 09:25 the day's OI change vs `prevOI` (yesterday's close) is already meaningful.
4. **Index exclusion correctness** — exact-match set + `NIFTY ` prefix guard; never substring-match `BANK` (would drop `ICICIBANK`).
5. **Sentiment availability** — the 09:25:00 job must NOT require sentiment (sectorState is still `empty()`); sentiment validation belongs inside `mergeOiCandidates`, which only runs at/after scan completion. A NONE/stand-down scan returns before the pending-consume step (stand-down path at `:669-689` exits before `:887`), so pending is simply never consumed that day.

---

### Task 1: `NseOiSpurtClient` — new NSE HTTP client + model + fixture + config

**Files:**
- Create: `src/main/java/com/tradingbot/model/NseOiSpurtItem.java`
- Create: `src/main/java/com/tradingbot/marketdata/NseOiSpurtClient.java`
- Modify: `src/main/resources/application.properties` (LVR block, after line 29)
- Create: `src/test/resources/nse/oi-spurts-sample.json`
- Create: `src/test/java/com/tradingbot/marketdata/NseOiSpurtClientTest.java`

**Interfaces:**
- Produces `NseOiSpurtItem(String symbol, long latestOi, long prevOi, long changeInOi, double avgInOi, double underlyingValue)`
- Produces `List<NseOiSpurtItem> NseOiSpurtClient.fetchTopUnderlyings(int topN)` — stock underlyings only, sorted `avgInOi` DESC, `topN` items; **empty list on any failure (never throws)**.
- Follows `YahooFinanceService` patterns: constructor `(ObjectMapper, HttpClient)` injectable for tests (`YahooFinanceService.java:40-53`), 10 s connect/request timeout, 2-attempt-style retry with backoff (`YahooFinanceService.java:206-253`), browser User-Agent constant.
- Follows `KiteAuthService` cookie pattern: `HttpClient.newBuilder().cookieHandler(new CookieManager())` (`KiteAuthService.java:117-123`).

- [x] **Step 1: Save the real payload as a test fixture**
Call the API (verified reachable) or reuse a captured response; trim `data[]` to ~12 entries keeping at least: 3 high-`avgInOI` stocks (`DMART`, `HCLTECH`, `UJJIVANSFB`), 2 index entries (`NIFTY`, `BANKNIFTY`), 1 negative-`avgInOI` stock, plus the three top-level metadata keys. Write to `src/test/resources/nse/oi-spurts-sample.json`.

- [x] **Step 2: Write failing tests in `NseOiSpurtClientTest`**
Mock `HttpClient`/`HttpClient.HttpResponse` with Mockito (same approach as `YahooFinanceServiceTest`) returning the fixture:
  1. `parsesPayloadSortsByPctOiChangeAndExcludesIndices` — asserts order `DMART, HCLTECH, UJJIVANSFB…`, no `NIFTY`/`BANKNIFTY`, `avgInOi`/`changeInOi` mapped correctly, metadata preserved for the stale check.
  2. `respectsTopNLimit` — `fetchTopUnderlyings(3)` returns 3.
  3. `returnsEmptyOnHttpError` (403) — client retries `max-attempts` times then returns empty; assert `WARN` emitted (capture with Logback list appender or simply assert empty + retry count on mock).
  4. `returnsEmptyOnMalformedJson`.
  5. `treatsStaleTradingDateAsFailure` — fixture with `currTradingDate` = yesterday → empty.
  6. `returnsEmptyOnTimeoutException`.
- [x] **Step 3: Run tests to confirm failure** — `./gradlew test --tests "*NseOiSpurtClientTest*"`
- [x] **Step 4: Implement `NseOiSpurtItem` + `NseOiSpurtClient`**
  - `@Component`, Javadoc on class/methods.
  - Request headers: `User-Agent` (Chrome UA string), `Accept: application/json, text/plain, */*`, `Accept-Language: en-US,en;q=0.9`, `Referer: https://www.nseindia.com/market-data/oi-spurts`; `.timeout(10s)`.
  - Attempt loop up to `max-attempts` (default 3): on non-200/exception sleep `500ms * attempt`; before the **last** attempt, warm cookies with a GET to `https://www.nseindia.com` (tolerate 403 — cookies may still be set) then retry the API.
  - Parse with injected `ObjectMapper`: read `currTradingDate`, compare `LocalDate.now()` formatted `dd-MMM-yyyy` (Locale.US) → mismatch = stale failure. Map `data[]` → `NseOiSpurtItem`, filter indices via `EXCLUDED_INDICES` (move/adapt the set currently in `LowestVolumeReversalScanner.java:23-34`, adding `NIFTYNXT50` and `NIFTYFPI`), re-sort by `avgInOi` DESC, then `limit(topN)`.
  - All failures log `[NSE-OI] ...` WARN and return `Collections.emptyList()`.
- [x] **Step 5: Add config keys to `application.properties` (after line 29)**
```properties
trading-bot.strategy.lowest-volume.nse-oi.enabled=${LVR_NSE_OI_ENABLED:true}
trading-bot.strategy.lowest-volume.nse-oi.url=${LVR_NSE_OI_URL:https://www.nseindia.com/api/live-analysis-oi-spurts-underlyings}
trading-bot.strategy.lowest-volume.nse-oi.top-n=${LVR_NSE_OI_TOP_N:3}
trading-bot.strategy.lowest-volume.nse-oi.max-attempts=${LVR_NSE_OI_ATTEMPTS:3}
trading-bot.strategy.lowest-volume.nse-oi.cron=${LVR_NSE_OI_CRON:0 25 9 ? * MON-FRI}
trading-bot.strategy.lowest-volume.nse-oi.mid-morning-refresh-enabled=${LVR_NSE_OI_MIDMORNING_REFRESH:true}
```
- [x] **Step 6: Run client tests to green** — `./gradlew test --tests "*NseOiSpurtClientTest*"`

---

### Task 2: Scanner — replace `scanOiSpurts` with `selectOiSpurtCandidates`

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalScanner.java`
- Modify: `src/test/java/com/tradingbot/service/LowestVolumeReversalScannerTest.java`

**Interfaces:**
- Removes: `scanOiSpurts(Map, LowestVolumeDirection, int)` (lines 192-306), `scanOiSpurts(Map, int)` (lines 308-372), `EXCLUDED_INDICES` (lines 23-34, moved to client in Task 1).
- Produces: `List<String> selectOiSpurtCandidates(List<NseOiSpurtItem> rankedItems, Map<String, StockQuoteSnapshot> quotes, LowestVolumeDirection sentiment, int topN, Set<String> excludeSymbols)` — pure selection logic; input list must already be ranked (`avgInOi` DESC) and index-free.

- [x] **Step 1: Write failing tests in `LowestVolumeReversalScannerTest`**
  1. `selectsAlignedOiCandidatesWithinSentimentBand` — LONG sentiment: items ranked [A(+2% price), B(-1% price), C(+7% price), D(+3% price)], quotes provided → returns `[A, D]` when `topN=2` (B wrong side, C outside band).
  2. `relaxesAlignmentBandWhenFewerThanTopNQualify` — only 1 aligned of `topN=3` → returns that 1 plus the next 2 unfiltered ranked symbols (per decision 4).
  3. `skipsCandidatesWithoutQuotes`, `skipsSymbolsInExcludeSymbols`, `returnsEmptyOnNullOrEmptyInputs`.
  4. `shortSentimentUsesNegativeBand` — SHORT: `-5 <= pct < 0` eligible.
- [x] **Step 2: Run tests to confirm failure**
- [x] **Step 3: Implement `selectOiSpurtCandidates`** (Javadoc'd): iterate ranked items → skip `excludeSymbols` → look up quote (`pctChange` band check vs sentiment) → collect aligned in order → if `aligned.size() < topN`, append unfiltered non-duplicate ranked items until `topN` → log chosen list as `[NSE-OI] Selected …` (mirrors old `scanOiSpurts` log style) → return.
- [x] **Step 4: Delete both `scanOiSpurts` overloads and `EXCLUDED_INDICES` from the scanner**; delete their 4 tests: `testScanOiSpurtsRankingAndFiltering` (~line 143), `testScanOiSpurtsDirectionalBuildup` (~line 198), `testScanOiSpurtsNoAlignmentWithSentiment` (~line 238), `testScanOiSpurtsEdgeCases` (~line 258).
- [x] **Step 5: Run scanner tests to green** — `./gradlew test --tests "*LowestVolumeReversalScannerTest*"`

---

### Task 3: Service — `runNseOiSpurtAugmentation()` + merge protocol + daily state

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java`
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

**Interfaces:**
- Consumes: `NseOiSpurtClient.fetchTopUnderlyings(...)`, `marketDataService.resolveToken/fetchQuote` (quote resolution pattern from `fetchMorningQuotesUnified`, lines 3273-3281), `scanner.selectOiSpurtCandidates(...)`, `initPdhPdlForSetup` (line 4062).
- Produces: `public void runNseOiSpurtAugmentation()`; private `mergeOiCandidates(List<NseOiSpurtItem>)`; private `Map<String, StockQuoteSnapshot> fetchSnapshotsFor(List<String>)`.
- New state (near line 219): `AtomicBoolean isAugmenting`, `AtomicReference<List<NseOiSpurtItem>> pendingOiItems`, `volatile LocalDate pendingOiFetchedDate`, `volatile boolean nseOiAppliedToday`, plus `@Value` injections for the six `nse-oi.*` keys (pattern: lines 154-158) with accessors (pattern: lines 4046-4060).

- [x] **Step 1: Write failing tests in `LowestVolumeReversalServiceTest`** (setup: mock `NseOiSpurtClient` bean/field, `setMorningScanDelayMs(0)`, existing fixed clock)
  1. `augmentationStoresPendingItemsWhenRunBeforeScan` — call `runNseOiSpurtAugmentation()` with NO prior scan (sectorState empty): fetch called, `pendingOiItems` populated with ranked items, no exception, **no sentiment required**, `nseOiAppliedToday == false`, `activeSetups` untouched. (This is the normal 09:25:00 path.)
  2. `scanCompletesAndConsumesPendingOiMergingTopThree` — augmentation first (mock client, incl. a non-universe symbol `DMART`), then `runMorningUniverseScan()` → `activeSetups` size 6, contains `DMART`, `getOiChangePct() ≈ 48.7`, `getPdh() != null`, `pendingOiItems` empty, `nseOiAppliedToday == true`, `sectorState.candidateSymbols()` contains merged names.
  3. `augmentationDirectMergesWhenScanAlreadyComplete` — scan first, then augmentation (manual/out-of-order case) → same merged result; second invocation is a no-op (`nseOiAppliedToday` guard).
  4. `augmentationUsesAlignmentBandAndRelaxesWhenShort` — client top-3 where only 1 is in band → watchlist still gains 3 OI names (band pick + 2 relaxed).
  5. `augmentationFailsSoftToSectorOnlyWhenNseReturnsEmpty` — client → empty: scan + watchlist unchanged (3 sector), `nseOiAppliedToday == false`, one WARN (+ Telegram when enabled).
  6. `augmentationSkipsWhenDisabledOrStandDown`.
  7. `augmentationSkipsExhaustedAndDuplicateSymbols` — OI pick equal to a sector pick doesn't duplicate; exhausted symbol skipped, next-ranked taken.
  8. `oiSpurtsModePromotesOiPicksAndDemotesSectorPicksToReservoir`.
  9. `newDayResetClearsPendingOiAndAppliedFlag` **and** `sameDaySoftResetPreservesPendingOiItems`.
- [x] **Step 2: Run tests to confirm failure**
- [x] **Step 3: Implement state + `fetchSnapshotsFor` + reset wiring**
  - `fetchSnapshotsFor(symbols)`: per symbol resolve token via `StockFnoRegistry.get(sym)` else `marketDataService.resolveToken(sym)`, exchange `info?.exchange() ?: "NSE"` (exact pattern of lines 3273-3281); build `StockQuoteSnapshot(sym, lp, c, o, pct, v, ap, 0L, 0L)`; skip symbols whose quote fails (`lp<=0 || c<=0`).
  - In `resetDaily(boolean)` (~line 2726): **only on new-day/force reset** (i.e. in the `else` branch where `lastScanDate` is nulled, ~line 2758, and unconditionally for `force`): `pendingOiItems.set(null); pendingOiFetchedDate = null; nseOiAppliedToday = false;`. A same-day soft reset must PRESERVE them so the rebuilt scan can still consume pending items (Review Focus #2).
- [x] **Step 4: Implement `runNseOiSpurtAugmentation()`** (public, Javadoc — "09:25 IST phase 2 of candidate selection: fetch NSE OI spurts before the morning scan, merge at scan completion", idempotent)
  1. Guards: `!enabled || standDownToday || nseOiAppliedToday || !nseOiEnabled` → return; CAS `isAugmenting`. (**No sentiment check here** — sectorState is `empty()` at 09:25:00; Review Focus #5.)
  2. `topN` = HYBRID ? `nseOiTopN`(3) : `oiSpurtsTopN`(5); fetch depth = `topN * 4`.
  3. `List<NseOiSpurtItem> items = nseOiSpurtClient.fetchTopUnderlyings(topN * 4)`; if empty → WARN `[NSE-OI] OI spurts fetch failed/empty — continuing sector-only today.` + one Telegram notice (respect `telegramAlerts`) → return (flag stays false).
  4. Ordering protocol: if `universeScanCompletedToday` → `mergeOiCandidates(items)`; else → `pendingOiItems.set(items); pendingOiFetchedDate = today;` then **re-check** `universeScanCompletedToday`; if now true → `List<...> p = pendingOiItems.getAndSet(null); if (p != null) mergeOiCandidates(p);` → return.
  5. `finally { isAugmenting.set(false); }`
- [x] **Step 5: Implement `mergeOiCandidates(items)`** (private, idempotent)
  1. Sentiment = `sectorState.sentiment()`; if NONE/null → WARN + return WITHOUT setting the flag (defensive; stand-down scan never reaches the consume point anyway).
  2. Build `excludeSymbols` = `activeSetups.keySet() ∪ exhaustedSymbols`.
  3. `selected = scanner.selectOiSpurtCandidates(items, fetchSnapshotsFor(candidateSymbols), sentiment, topN, excludeSymbols)`.
  4. HYBRID: for each selected → `new LowestVolumeSetup(sym, sentiment)`, `setOiChangePct(item.avgInOi())`, `initPdhPdlForSetup`, `activeSetups.put`. OI_SPURTS: demote current active setups to `candidateReservoir` (skip exhausted), `activeSetups.clear()`, then create setups with **own price direction** (`pctChange < 0 ? SHORT : LONG`, per lines 605-608).
  5. Leftover selected items → `candidateReservoir` (pattern of lines 859-865).
  6. Rebuild `sectorState` with combined `candidateSymbols` (`new LowestVolumeSectorState(s.advances(), s.declines(), s.sentiment(), s.topSector(), s.sectorPctChange(), combined)`) so `/status` and Telegram show the true watchlist.
  7. Log `[LVR] NSE OI Spurt merge complete: added {} candidates: {} (final active {})` + Telegram block (below), set `nseOiAppliedToday = true`.
```
📊 *LVR 09:25 NSE OI Spurt Leaders*
• Sentiment: *{LONG/SHORT}*
• OI Leaders ({n}): `SYMBOL (+48.7% OI), …`
• Active Watchlist ({m}): `…`
```
- [x] **Step 6: Run new tests to green** — `./gradlew test --tests "*LowestVolumeReversalServiceTest*augmentation*" --tests "*PendingOi*" --tests "*OiSpurtsMode*" --tests "*SoftReset*"` (old OI-dependent tests remain red until Tasks 4/5/7)

---

### Task 4: Rewire the 09:25 `runMorningUniverseScan` (remove inline OI, consume pending)

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java` (lines 571-644, 751-834, 857-926)
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

- [x] **Step 1: Write failing tests**
  1. `hybridMorningScanIsSectorOnlyUntilPendingOiConsumed` — scannerMode=HYBRID: after `runMorningUniverseScan()` with no pending → `activeSetups.size() <= 3`, scan completed; with pending stored first → 6 after scan (covered by Task 3 test 2, keep this as the sector-only negative).
  2. `oiSpurtsModeFallsBackToSectorSelectionAtScanTime` — scannerMode=OI_SPURTS: scan completes via sector flow (previously it required quote OI or failed), `universeScanCompletedToday == true`.
- [x] **Step 2: Run tests to confirm failure**
- [x] **Step 3: Delete the OI_SPURTS quote-gate branch (lines 571-644)**
  The mode now always runs the sector flow at scan time (this IS the previously-implemented fallback at lines 635-643, minus the "no qualifying spurt" pre-Telegram — that notice moved to the NSE job's failure path). Remove `oiCount`/`oiDataAvailable` logic, `scanner.scanOiSpurts(universeQuotes, oiSpurtsTopN)` and its direct `activeSetups` build/Telegram/return.
- [x] **Step 4: Replace the HYBRID merge block (lines 751-803) with sector-only assembly**
  - Delete `hybridQuotesWithOi` counter log, the `scanner.scanOiSpurts(universeQuotes, sentiment, 3)` try/catch (lines 754-780) and the OI fill loops (788-800).
  - `finalActiveList` = first `min(3, candidateStocks.size())` sector symbols (keep lines 783-786).
  - Update the "Morning Scan Result" log (814-822) and hybrid Telegram (891-911): drop the inline OI-leaders line — the post-scan merge Telegram (Task 3 Step 5) now carries the OI leaders; the 09:25 scan message stays sector-formatted.
- [x] **Step 5: Remove `setup.setOiChangePct(snap.oiPctChange())` at line 830** (snapshot OI no longer populated; OI% is set from NSE `avgInOi` in `mergeOiCandidates`).
- [x] **Step 6: Consume pending OI items at the END of a successful scan**
  Immediately after `universeScanCompletedToday = true; lastScanDate = …` (lines 887-888):
```java
List<NseOiSpurtItem> pending = pendingOiItems.getAndSet(null);
if (pending != null && !pending.isEmpty()) {
    mergeOiCandidates(pending);   // idempotent; normal path for the 09:25:00 fetch
}
```
  (Also covers scan retries at 09:30:20+ and manual `/morning-scan`; on a NONE/stand-down day the scan returns before this point and pending is never consumed — by design.)
- [x] **Step 7: Run the new tests + full service suite** — `./gradlew test --tests "*LowestVolumeReversalServiceTest*"` (remaining failures should now be exactly the obsolete tests listed in Task 7)

---

### Task 5: Strip quote-based OI from `fetchMorningQuotesUnified` and mid-morning refresh

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java` (lines 2902-2937, 3252-3375)
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

- [x] **Step 1: Write failing test** `morningQuoteFetchNoLongerEnrichesFuturesOi` — mock `marketDataService`: NSE cash quote returns `oi=0/poi=0`; run `fetchMorningQuotesUnified()`; `verify(marketDataService, never()).resolveFuturesToken(any())`; snapshot `openInterest()==0 && prevDayOpenInterest()==0`.
- [x] **Step 2: Confirm failure**, then remove from `fetchMorningQuotesUnified`:
  - Counters `cashOiQuotes/futuresEnrichedQuotes/futuresEnrichMisses` (3267-3269) and all their updates (3293-3295, 3305-3329).
  - `oi`/`poi` reads (3288-3292) and the whole NFO enrichment block (3297-3330).
  - Snapshot construction: pass `0L, 0L` (lines 3335-3336) or the 5-arg convenience ctor.
  - Coverage summary log + ZERO-coverage WARN (3356-3373) → replace with a plain `[LVR] Morning quotes fetched for {}/{} universe symbols.` info log.
- [x] **Step 3: Remove the mid-morning OI rescan block (lines 2902-2937)** in `runMidMorningUniverseRefresh` — delete the `hybridQuotesWithOi`/`midOi`/`scanner.scanOiSpurts(universeQuotes, sentiment, 3)` section; keep sector-reserve replenishment (2939-2950) intact. (Its NSE replacement lands in Task 8.)
- [x] **Step 4: Run tests to green** — `./gradlew test --tests "*LowestVolumeReversalServiceTest*"` (all pre-existing OI tests should now be at exactly the Task 7 list)

---

### Task 6: Scheduler — 09:25 job

**Files:**
- Modify: `src/main/java/com/tradingbot/scheduler/LowestVolumeReversalScheduler.java`
- Create: `src/test/java/com/tradingbot/scheduler/LowestVolumeReversalSchedulerTest.java`

**Interfaces:**
- Produces: `@Scheduled(cron = "${trading-bot.strategy.lowest-volume.nse-oi.cron:0 25 9 ? * MON-FRI}", zone = "Asia/Kolkata") public void scheduledNseOiSpurts()` → guard `schedulerEnabled` → try/catch → `strategyService.runNseOiSpurtAugmentation()` (mirrors `scheduledDailyReset`, lines 40-57).

- [x] **Step 1: Write failing test** — with mocked `LowestVolumeReversalService`: disabled scheduler → `verifyNoInteractions(service)`; enabled → invoke `scheduledNseOiSpurts()` → `verify(service).runNseOiSpurtAugmentation()`; service throws → no exception propagates.
- [x] **Step 2: Confirm failure**, then implement the method. Javadoc: "09:25 IST — fires 20 s BEFORE the 09:25:20 candle cycle so NSE OI candidates are pending when the morning scan completes; single morning fetch (no 09:30 job). Thread pool: `spring.task.scheduling.pool.size=5` (`application.properties:3`) already serves 14 scheduled methods — augmentation is short-lived; raise via env only if contention appears."
- [x] **Step 3: Run test to green** — `./gradlew test --tests "*LowestVolumeReversalSchedulerTest*"`

---

### Task 7: Disposition of the 7 obsolete OI-dependent service tests

**Files:**
- Modify: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

- [x] **Step 1: Delete outright**
  - `testFetchMorningQuotesUnifiedEnrichesFuturesOi` (~2814) — feature removed; superseded by Task 5 Step 1 test.
  - `testOiSpurtsScanFailsLoudlyWhenOiMissing` (~2798) — fail-loud semantics replaced by operator decision 5 (sector-only fail-soft); superseded by Task 3 test 5.
- [x] **Step 2: Rewrite to the two-phase model**
  - `testHybridMorningScanSelectsCandidatesEnrichedFromFuturesOi` (~2853) → pending-first flow: augmentation stores pending, scan completes sector-only, merge adds mocked NSE picks; OI names have `getOiChangePct() > 0`, sector names don't.
  - `testHybridMorningScanSelectsShortCandidatesWhenMarketBearish` (~2895) → assert `activeSetups.size() <= 3` post-scan (comment at ~2932 updated: cap is now 3 at scan time, 6 after merge).
  - `testOiSpurtsMorningScanSelectsCandidatesEnrichedFromFuturesOi` (~2936) and `testOiSpurtsScanSelectsCandidatesWithOiData` (~2978) → OI_SPURTS: sector scan at 09:25 + pending consumption → `activeSetups.hasSize(5)` (= `oiSpurtsTopN`), `candidateReservoir` holds demoted sector picks, OI% from `avgInOi`.
  - `testHybridMorningScanCombinesSectorAndOiSpurts` (~3559) → two-phase combination: ≤3 setups after scan alone, 6 with pending, `standDown == false`.
- [x] **Step 3: Run full service test class to green** — `./gradlew test --tests "*LowestVolumeReversalServiceTest*"`

---

### Task 8: Mid-morning NSE reservoir refresh (ENABLED by default)

**Files:**
- Modify: `src/main/java/com/tradingbot/service/LowestVolumeReversalService.java` (`runMidMorningUniverseRefresh`, after sector replenishment ~line 2950)
- Test: `src/test/java/com/tradingbot/service/LowestVolumeReversalServiceTest.java`

**Interfaces:**
- Consumes: `nseOiSpurtClient.fetchTopUnderlyings(6)`, config `nse-oi.mid-morning-refresh-enabled` (default `true`, Task 1 Step 5).
- Behavior: guard `enabled && midMorningRefreshEnabled && !standDownToday` → fetch top 6 → add non-active, non-exhausted symbols to `candidateReservoir` (dedupe) → log `[LVR] Midday NSE OI refresh: added {} reservoir candidates: {}`. No `activeSetups` mutation, no Telegram, does NOT touch `nseOiAppliedToday`. Fetch failure → silent WARN + continue (the refresh must never abort sector replenishment).

- [x] **Step 1: Write failing tests**
  1. `midMorningRefreshAddsNseOiSymbolsToReservoirWhenEnabled` — client returns 6 → reservoir gains the non-active symbols.
  2. `midMorningRefreshSkippedWhenDisabled` (set flag false → client never called) and `midMorningRefreshContinuesOnNseFailure` (client empty → sector reserves still replenished, refresh timestamp still set).
- [x] **Step 2: Confirm failure**, then implement the block (fetch only when `countActionableSetups() == 0` path already holds — ride the existing trigger at `runCycle:521-527`; place the NSE fetch after sector-reserve replenishment so sector reserves win slot conflicts).
- [x] **Step 3: Run tests to green** — `./gradlew test --tests "*MidMorning*"`

---

### Task 9: Docs + full verification

**Files:**
- Modify: `lowest_volume_reversal_spec.md` (root) — update the OI-selection section to the NSE 09:25 flow if present (grep for `OI Spurt`/`Open Interest`).
- Modify: `README.md` — LVR feature bullets/env vars if they document OI spurts or `LVR_OI_SPURTS_TOP_N`.

- [x] **Step 1: Full suite + static analysis**
```
./gradlew spotlessApply
./gradlew test
./gradlew spotbugsMain
```
- [x] **Step 2: Grep for leftovers** — `scanOiSpurts`, `resolveFuturesToken` usage in LVR, `poi` reads, `futuresEnrichedQuotes`: only `ShoonyaMarketDataService` (other strategies) and tests may remain. Also grep `09:30` in the plan/README to ensure no stale references to the removed job remain.
- [ ] **Step 3: Live smoke on the next trading morning**
  1. **09:25:0x** log shows `[NSE-OI] Fetched …` (HTTP 200, N entries) + pending store line; on failure: WARN → retries → cookie warm-up → sector-only Telegram notice, no exception escaping the scheduler.
  2. **~09:26-09:27** (scan completion) log shows `Morning Scan Result … Final Active=[3 sector]` followed by `[LVR] NSE OI Spurt merge complete … (final active 6)`; Telegram `09:25 NSE OI Spurt Leaders` arrives with `avgInOi` values.
  3. **Before 09:30** verify via `/status` or Telegram `/lvr_status` that `sectorState.candidateSymbols()` lists all 6.
  4. **Mid-morning (only if a day with 0 actionable setups after 10:30):** `Midday NSE OI refresh` line + reservoir top-up.
  5. If a non-universe pick appears: confirm futures/quote/PDH resolve and it can arm without gate errors.
- [x] **Step 4: Update plan checkboxes** and record any live-market deviation (especially HTTP status at 09:25) in this file.


---

## Implementation log (2026-10-12)

All 9 tasks implemented. Deviations from the original plan, recorded per Task 9 Step 4:

- **Task 3/5 test disposition:** the planned standalone test `morningQuoteFetchNoLongerEnrichesFuturesOi` was folded into two rewrites instead: `testHybridMorningScanPhaseOneCarriesNoQuoteOi` and `testOiSpurtsScanCompletesWithoutQuoteOi` both assert `getOiChangePct()` is null after the scan (quote OI is ignored end-to-end). The dedicated `testFetchMorningQuotesUnifiedEnrichesFuturesOi`, `testOiSpurtsMorningScanSelectsCandidatesEnrichedFromFuturesOi` and `testOiSpurtsScanSelectsCandidatesWithOiData` tests were deleted (enrichment and quote-OI selection no longer exist).
- **Task 6 naming:** the scheduled method is `` scheduledNseOiSpurtAugmentation`` (not `` scheduledNseOiSpurts``), tested in the new `` LowestVolumeReversalSchedulerTest`` (delegation + scheduler-disabled flag).
- **Task 7 rewrites:** `` testOiSpurtsScanFailsLoudlyWhenOiMissing`` → `` testOiSpurtsScanCompletesWithoutQuoteOi`` (loud-fail no longer applies: quote OI is simply not required); `` testHybridMorningScanSelectsCandidatesEnrichedFromFuturesOi`` → `` testHybridMorningScanPhaseOneCarriesNoQuoteOi``; tail hybrid test → `` testHybridMorningScanPhaseOne`` (≤3 sector picks, phase separation). All 4 old `` scanOiSpurts`` scanner tests and both `` scanOiSpurts`` methods (plus `` EXCLUDED_INDICES``) were deleted — index exclusion now lives solely in `` NseOiSpurtClient.parsePayload``.
- **Task 8:** the mid-morning HYBRID/OI_SPURTS quote-OI rescan block was replaced by the NSE reservoir top-up (top 6, reservoir-only, gated by `` LVR_NSE_OI_MIDMORNING_REFRESH``); covered by `` testMidMorningRefreshTopsUpReservoirFromNseOiFeed``.
- **Test suite:** 517 tests, 516 pass. The single failure (`` testFreshWickRequiresSessionExtremeToAdvanceAfterArming``) was verified to fail at HEAD with a clean stash — it is **pre-existing and unrelated** to this plan.
- **SpotBugs:** 1 finding in new code (`` EI_EXPOSE_REP2`` on `` NseOiSpurtClient`` storing `` ObjectMapper``) — the same pattern already exists in 65 other findings repo-wide (``ignoreFailures=true``); consistent with codebase, left as-is.
- **Still open:** Task 9 Step 3 — live smoke on the next trading morning (09:25:0x fetch log, ~09:26-09:27 merge line, watchlist = 6 before 09:30). Top residual risk: Akamai behavior on the live-market 09:25 request (off-market probe was a CDN cache HIT).
