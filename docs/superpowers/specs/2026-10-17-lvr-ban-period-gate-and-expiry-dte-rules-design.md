# Design Spec: LVR F&O Ban-Period Gate & Expiry/DTE Rules

## 1. Overview & Objectives

Two compliance-driven additions to the Lowest Volume Reversal (LVR) strategy:

1. **Section 1 — F&O Ban-Period Gate.** Exclude securities placed in the NSE F&O ban period from the LVR universe and entry path. A stock in ban may only be traded if the trade **reduces** the entity's end-of-day Futures-equivalent open interest (FutEq OI); LVR's long option/futures entries always *increase* FutEq OI and therefore constitute a violation, which attracts a monetary penalty that **compounds daily** until resolved.
2. **Section 2 — Expiry/DTE Rules.** Introduce explicit expiry awareness: a minimum-days-to-expiry floor for fresh option entries, a hard no-entry rule on the monthly expiry session, and — as a prerequisite — a fix to the expiry-date computation, which still uses the pre-September-2025 **Thursday** convention while NSE has settled all equity derivatives on **Tuesday**.

Both features are **defaults-on with explicit kill switches**, fail-safe in the documented direction, and are enforced identically in the live and replay paths so `LvrTodayGateReplayAuditTest` reports them for free.

---

## 2. Regulatory Background

### 2.1 Ban period (Section 1)

Per NCL Circular NCL/CMPT/70085 (08-Sep-2025) implementing SEBI circular SEBI/HO/MRD/TPD-1/P/CIR/2025/79 (29-May-2025), effective **01-Oct-2025** (delta-based refinement from **08-Dec-2025**):

- At EOD, if a security's market-wide **FutEq OI exceeds 95% of the applicable MWPL**, the security is placed in ban **from the next trading day**.
- Ban is lifted only when combined FutEq OI across all exchanges falls to **80% of MWPL or below** (checked daily after close).
- During ban, all trades in the security's derivatives must result in a **reduction of the entity's EOD FutEq OI**. Positions are assessed using contract deltas snapshotted at **14:00 IST**; the entity's base position is fixed on entry to ban.
- **Penalty:** 1% of violation value, minimum ₹5,000, maximum ₹1,00,000, **per entity, per stock, per day** — and a violation does not reset the base position, so the penalty repeats every day the breach persists.

A **long CE/PE** adds positive delta and a **long futures** position adds full delta; neither can reduce FutEq OI. There is no configuration of LVR that trades a banned security compliantly. The only correct behaviour is exclusion.

### 2.2 Expiry day (Section 2)

Per SEBI/HO/MRD/MRD-TPD-1/P/CIR/2025/76 (26-May-2025) and NSE circular NSE/FAOP/68747 (25-Jun-2025), effective for contracts expiring on/after **01-Sep-2025**:

| Contract | Expiry day |
|---|---|
| Single-stock derivatives, non-benchmark index derivatives | **Last Tuesday** of the expiry month (minimum tenor 1 month) |
| NIFTY / BANKNIFTY weekly index options | **Tuesday** of the expiry week |
| BSE Sensex / BSE listed derivatives | **Thursday** |
| Any of the above on a trading holiday | Previous trading day |

Implications for LVR:

- Single-stock options are **monthly-only**. There is no weekly series to roll into, so near month-end the only compliant choices are *skip* or *trade the next month's far contract*.
- Near monthly expiry, gamma and theta dominate the spot-driven premise of the LVR setup; the 2.5R spot-space target stops translating into premium-space R.
- `StockFnoRegistry.calculate*TargetExpiry` still assumes **Thursday** (and **Friday** for SENSEX/BSESN) with **no holiday rolling** — a live correctness defect described in §4.1.

---

## 3. Section 1: F&O Ban-Period Gate

### 3.1 New component — `NseBanPeriodClient`

**Package:** `com.tradingbot.marketdata`
**Template:** `NseOiSpurtClient` (cookie warm-up, bounded retry with linear backoff, never-throw public API, testable parse method, injectable `HttpClient`/`ObjectMapper` ctor).

```
com.tradingbot.marketdata.NseBanPeriodClient (@Component)
 ├── fetchBanList()                     : Set<String>   // uppercase symbols; empty set on failure, never throws
 ├── parseBanList(String body)          : Set<String>   // public, testable; supports CSV and HTML table
 ├── isLastFetchSuccessful()            : boolean
 ├── getLastFetchDate()                 : LocalDate     // trade date the list applies to (next trading day)
 └── warmCookies() / setClock() / setRetryBackoffMs()
```

- **Source:** NSE "Security in ban period for the next trade date" (F&O segment). Default URL `https://nsearchives.nseindia.com/content/fo/ban_list_DDMMYYYY.csv`, date-parameterised, overridable via `trading-bot.strategy.lowest-volume.nse-ban.url`. The exact endpoint must be validated against the live NSE site during implementation (same exercise already performed for `LVR_NSE_OI_URL`).
- **Staleness guard:** the ban file is published EOD and applies to the **next** trading day. Record the trade date the payload refers to; a payload naming a date other than the next trading day is rejected as stale and the fetch reports failure.
- **Retry policy:** `nse-ban.max-attempts` (default 3), linear backoff, HTTP 403 → `warmCookies()` then retry, `InterruptedException` restores the interrupt flag.
- **Degradation:** mirrors the OI-spurts precedent — a failed fetch is *never* fatal; it degrades to a documented fail-open/fail-closed policy (§3.3) plus a loud alert.

### 3.2 Universe exclusion (layer 1 of 3)

Applied whenever the ban list is valid for today:

| Hook | Change |
|---|---|
| `runMorningUniverseScan` | Filter `finalActiveList` before the `activeSetups` build loop; log `BAN_EXCLUDED count=<n>`. |
| Candidate reservoir loops (morning scan, `runMidMorningUniverseRefresh`) | Skip banned symbols when staging reserve sectors. |
| `replenishActiveCandidatesIfNeeded` | Skip banned symbols on promotion from reservoir. |
| `mergeOiCandidates` (NSE OI phase-2 merge) | Add banned symbols to the existing `exclude` set alongside `activeSetups`/`exhaustedSymbols`. |

Excluding at universe level (rather than only at the entry gate) is what preserves candidate slots: without it, a banned symbol would occupy one of the ≤3 active slots all day and silently halve the watchlist.

### 3.3 Entry gate — `BAN_PERIOD` (layer 2 of 3)

New `EntryGateInput` fields:

```java
public Boolean banPeriodEnabled;
public Boolean symbolBanned;       // null = list unavailable for today
public Boolean banListUnavailable; // fetch failed or stale
public Boolean banPeriodFailClosed; // policy for an unavailable list (§3.5)
```

Gate position: **first, ahead of `SLIPPAGE`**. Ban status is an absolute regulatory hard-block, independent of price, and evaluating it first avoids burning a 60s slippage cooldown on a symbol that can never be traded.

The fail-open WARN and the degraded-fetch alert are emitted **once**, in `runNseBanListFetch`, rather than per tick from the static gate — the gate itself stays pure.

**Disposition rules** (adds `BAN_PERIOD` to the `evaluateEntryGate` ordering table):

| Condition | Disposition | Rationale |
|---|---|---|
| `banPeriodEnabled == false` | pass | Kill switch. |
| `symbolBanned == TRUE` | `EXHAUST` (gate `BAN_PERIOD`) | Day-static, like `REGISTRY` — the symbol is unusable for the entire session. Consistent with the `testPriceRelativeDenialsNeverExhaust` invariant (ban is not price-relative). |
| `banListUnavailable` and `ban-period.fail-closed = false` (default) | pass, with a WARN + Telegram degraded-fetch alert | A network blip must not shut the strategy down. |
| `banListUnavailable` and `ban-period.fail-closed = true` | `RETRY` (not `EXHAUST`) | We cannot permanently discard symbols on a transient failure; re-attempt the fetch on the next tick. |

### 3.4 State, scheduling, alerting

- **`LvrStateStore.DailyState`** — add `List<String> bannedSymbols`, `LocalDate banListFetchedDate`, `boolean banListFetchUnavailable`. Backward compatible per the existing javadoc contract (absent fields keep Java defaults; no migration).
- **`LowestVolumeReversalScheduler`** — new job `scheduledNseBanListFetch()` modelled on `scheduledNseOiSpurtAugmentation`, cron `${...nse-ban.cron:0 10 9 ? * MON-FRI}` (pre-market; the EOD-published file for the next session is already available). Guarded try/catch that never throws. No holiday check required — the existing crons are `MON-FRI` only and a stale list is handled by the staleness guard.
- **`TelegramService`** — two alerts: (a) daily summary `LVR BAN LIST: <n> symbols excluded (<SYM>, <SYM>, …)`; (b) degraded-fetch warning naming the policy in force.
- **`resetDaily`** — clears the in-memory ban set, `banListFetchedDate`, and the degraded-fetch latch.

### 3.5 Configuration

| Property | Env | Default |
|---|---|---|
| `…lowest-volume.ban-period-enabled` | `LVR_BAN_PERIOD_ENABLED` | `true` |
| `…lowest-volume.ban-period-log-only` | `LVR_BAN_LOG_ONLY` | `true` (P1 validation mode — see §7) |
| `…lowest-volume.ban-period-fail-closed` | `LVR_BAN_FAIL_CLOSED` | `false` |
| `…lowest-volume.nse-ban.url` | `LVR_NSE_BAN_URL` | `https://nsearchives.nseindia.com/content/fo/ban_list_{date}.csv` |
| `…lowest-volume.nse-ban.max-attempts` | `LVR_NSE_BAN_ATTEMPTS` | `3` |
| `…lowest-volume.nse-ban.cron` | `LVR_NSE_BAN_CRON` | `0 10 9 ? * MON-FRI` |

**Staleness is prevented structurally, not by a flag.** The ban file is named for the trade date it
applies to, so the client requests *today's* file (`{date}` → `ddMMyyyy`); a pre-market fetch either
retrieves the current list or fails. There is deliberately no `allow-stale` knob: reusing a previous
day's ban list would be the one failure mode that produces a false "this symbol is tradeable".

**Validity guard (why "empty" is not "failure").** A ban list with zero symbols is a legitimate
state, so a 200 with an unrecognisable body (error page, captcha interstitial, truncated download)
must not be reported as "nothing is banned". `parseBanList` only accepts a payload whose *symbol
column* carries a `SYMBOL` header; anything else is invalid and drives the fail-open/fail-closed
policy instead. The header check is scoped to the symbol column because the free-text details column
legitimately contains the word "symbol" ("…have crossed 95% of the market-wide position limit").

---

## 4. Section 2: Expiry/DTE Rules

### 4.1 Prerequisite fix (P0) — expiry-date computation is on the wrong weekday

**Defect.** `StockFnoRegistry` computes the target contract series on the pre-2025 convention with no holiday handling:

- `calculateMonthlyTargetExpiry` — `DayOfWeek.THURSDAY` for NIFTY/stocks (`FRIDAY` for SENSEX/BSESN), no holiday rollback.
- `calculateWeeklyTargetExpiry` — `THURSDAY` for NIFTY/BANKNIFTY weekly (`FRIDAY` for SENSEX).

LVR's options path **computes** the trading symbol rather than reading the contract master: `executePositionEntry` (options branch) and `fetchOptionLtp` both call `calculateTargetExpiry(symbol, today, false, 1)` and then `formatTradingSymbol`, e.g. `RELIANCE26MAR2500CE`.

**Impact.** The monthly trading symbol carries **no day-of-month** (`RELIANCE26OCT2500CE`), so the
month/yy part stays correct mid-month and the defect is not a month-long failure. It bites in two
places:

1. **The month-end blind window.** Between the real (Tuesday) expiry and the computed (Thursday)
   expiry — the last two trading sessions of every month — the code resolves a contract that has
   already expired. `marketDataService.resolveToken` returns null, the entry is skipped via the
   fail-closed `recordQuoteFailure` path, and **LVR trades no options at all** through that window.
2. **Holiday-rolled expiries.** There is no holiday rollback, so when the last Tuesday is an exchange
   holiday the computed date is wrong for the *entire* month. In the shipped 2026 holiday set this
   occurs in November 2026: the last Tuesday (Nov 24) is Gurunanak Jayanti, so the true expiry is
   Monday Nov 23 while the old code produced Nov 24.

`NseTradingCalendarUtil` already contains the correct, holiday-aware implementation (`getMonthlyExpiryTuesday`, backward rollback on holiday) and is already consumed elsewhere (`MonthlyRangeScheduler`). The fix is to converge `StockFnoRegistry` on it rather than to add a third calendar.

**Change.**

1. `calculateMonthlyTargetExpiry` delegates to `NseTradingCalendarUtil.getMonthlyExpiryTuesday(y, m)` (holiday rollback included); SENSEX/BSESN → last Thursday with the same rollback.
2. `calculateWeeklyTargetExpiry` → Tuesday of the week for NSE indices; Thursday for SENSEX/BSESN.
3. Preserve the existing `minDteThreshold` roll semantics.
4. `getLastDayOfWeekOfMonth` becomes unused by these two methods — retain only if still referenced elsewhere, otherwise remove.
5. Add `NseTradingCalendarUtil.isMonthlyExpiryDay(LocalDate)` (true when `date` equals the computed monthly expiry for its month) so the expiry-day rule has one canonical implementation.

**Compatibility.** All existing callers of `calculateTargetExpiry` / `formatTradingSymbol` (LVR options entry, LVR `fetchOptionLtp`, monthly-range strategy) must be regression-tested; only the produced *dates* change, and only in the Thursday-vs-Tuesday window.

### 4.2 Expiry/DTE entry gate — `EXPIRY_DTE`

New `EntryGateInput` fields: `Boolean expiryDteEnabled`, `LvrInstrumentType instrumentType`, `Integer optionDteSessions` (trading sessions remaining in the *current* monthly series, `0` on the expiry session), `Boolean expiryDayToday`, `Integer optionMinDteSessions`, `Integer futuresMinDteSessions`. The two floors travel with the input so the gate stays a pure function of its arguments.

**Position:** after `CUTOFF`, before `ATTEMPTS` — a session-level, non-price gate. A gate floor of `<= 0` (the FUTURES default) makes the whole block inert, which is what makes `futures-min-dte-sessions = 0` mean "today's behaviour", expiry day included.

| Condition | Disposition | Rationale |
|---|---|---|
| applicable floor `<= 0` | pass | Kill switch for the instrument: `option-min-dte-sessions = 0` or `futures-min-dte-sessions = 0` (the default) restores legacy behaviour. |
| `expiryDayToday` | `EXHAUST` (gate `EXPIRY_DTE`) | No fresh entry on the monthly expiry session, regardless of DTE. |
| `optionDteSessions` is null | `EXHAUST` (gate `EXPIRY_DTE`, + WARN) | The series could not be resolved for this symbol, so its contract cannot be traded — the exact failure that made an unresolvable option series skip entries silently before §4.1. Mirrors `REGISTRY`. |
| `optionDteSessions < applicable floor` | `EXHAUST` (gate `EXPIRY_DTE`) | Day-static — DTE cannot improve intraday — so permanent discard is correct and matches the `BAN_PERIOD`/`REGISTRY` precedent. |

**Where the numbers come from.** Both call sites derive `optionDteSessions` and `expiryDayToday` from one private reading, `readDte(symbol)`, built on two new primitives:

- `StockFnoRegistry.getMonthlyContractExpiry(symbol, date)` — the **contractual** monthly expiry of the series containing `date`, with no DTE-driven roll. Deliberately *not* `calculateTargetExpiry(...)`, which would already have advanced to next month on the last session and reported a healthy DTE for a series with none left.
- `NseTradingCalendarUtil.tradingSessionsRemaining(from, through)` — exchange trading sessions strictly after `from` through `through` (expiry day reads `0`), skipping weekends and holidays so a holiday-rolled expiry needs no special case.

`calculateMonthlyTargetExpiry` now delegates its first step to `getMonthlyContractExpiry`, so screening, gating and contract-symbol generation all read the same date.

**Roll behaviour.** When the DTE floor blocks a symbol, LVR **skips that symbol for the session**. It does *not* roll into the next month's series and enter there: next-month stock options have ≥ ~20 DTE, so the trigger/SL geometry of a setup formed in the expiring series is not comparable, and the intraday setup does not survive the roll. Skipping is the honest behaviour and it frees the candidate slot for `replenishActiveCandidatesIfNeeded` to promote a different symbol.

**DTE is measured in trading sessions**, not calendar days, using `NseTradingCalendarUtil.isTradingDay` — so month-end weekends and holidays (e.g. a Tuesday expiry that rolls to Monday because of a holiday) are handled without special cases.

### 4.3 Universe exclusion (OPTIONS mode only)

When `instrument-type = OPTIONS` and `EXPIRY_DTE` would block a symbol, exclude it in `runMorningUniverseScan` / reservoir / promotion, exactly as in §3.2. This avoids arming and holding setups that can never be acted on and keeps the ≤3 active slots productive. In FUTURES mode this exclusion is a no-op.

Both screening reasons are served by **one** predicate, `isExcludedFromUniverse(symbol)` = `isBannedForTrading(symbol) || isDteExcluded(symbol)`, applied at all four selection points — the morning watchlist, the morning reservoir, the mid-morning reservoir top-up and `replenishActiveCandidatesIfNeeded` — and to the OI-spurt candidate set in `mergeOiCandidates`. One predicate means the ban and DTE rules cannot drift apart across sites.

### 4.4 Persist the entry contract on the position

Capture the contract at entry and reuse it on the exit side (`resolveBrokerTradingSymbol`, LTP fetch, premium valuation) rather than recomputing it, so an intraday month roll or a DTE-driven series change can never make entry and exit reference different contracts.

**As built, this narrowed to one field.** `atmStrike` and `optionType` were already on `LowestVolumePaperPosition`; what was missing was `expiryDate`, and — the sharper half of the defect — **neither `expiryDate` nor `brokerTradingSymbol` survived the disk snapshot** (`LvrPositionSnapshot` carried neither). A restart therefore silently fell back to recomputing the series from the clock, which is exactly the entry/exit mismatch §4.4 exists to prevent. Both now travel on `LvrPositionSnapshot`; pre-§4.4 snapshots load with `expiryDate == null` and keep today's recompute behaviour, per the existing backward-compatibility contract.

The entry options branch resolves the series **once**, prices the premium against that same contract (`fetchOptionLtp` gained an explicit-expiry overload), and stores it on the position; `resolveBrokerTradingSymbol` prefers the persisted expiry over resolving today's series.

### 4.5 Configuration

| Property | Env | Default |
|---|---|---|
| `…lowest-volume.expiry-dte-enabled` | `LVR_EXPIRY_DTE_ENABLED` | `true` |
| `…lowest-volume.option-min-dte-sessions` | `LVR_OPTION_MIN_DTE_SESSIONS` | `2` |
| `…lowest-volume.futures-min-dte-sessions` | `LVR_FUTURES_MIN_DTE_SESSIONS` | `0` |

`option-min-dte-sessions = 2` blocks fresh option entries in the **last two trading sessions** of the monthly series (including expiry day). Setting it to `0` restores today's behaviour with the §4.1 date fix still in force.

---

## 5. Replay & Audit Parity

- Populate the new `EntryGateInput` fields at **both** gate call sites: the live builder in `checkSpotTriggerBreach` and the replay builder in `replaySession`. Fields are null-safe and default to "gate inactive", so existing replay callers and the Python backtests are unaffected.
- `LvrTodayGateReplayAuditTest` buckets EXHAUST denials by gate name via a logback appender; `BAN_PERIOD` and `EXPIRY_DTE` appear in that audit with no change to the harness.

---

## 6. Verification & Testing Strategy

1. **`StockFnoRegistryTest`** — monthly expiry resolves to the last **Tuesday** of the month; rolls backward when that Tuesday is an NSE holiday; weekly NIFTY expiry resolves to Tuesday of the week; SENSEX variants resolve to Thursday; `minDteThreshold` roll behaviour unchanged. Assert against known 2026 months from `NseTradingCalendarUtil`'s holiday set.
2. **`NseTradingCalendarUtilTest`** — `isMonthlyExpiryDay` true on the computed expiry, false on the day before; month-rollover case.
3. **`NseBanPeriodClientTest`** — template `NseOiSpurtClientTest`: classpath fixture payloads (CSV and HTML), mocked JDK `HttpClient` transport, 403 → `warmCookies()` → retry verified with `verify(http, times(3))`, stale-payload guard rejected, malformed body → empty set, never throws.
4. **`LvrEntryGateTest`** — `baseInput()` extended; one test per `BAN_PERIOD` row in §3.3 and per `EXPIRY_DTE` row in §4.2, `@DisplayName` `"Condition → DISPOSITION (gate NAME)"`, plus: `BAN_PERIOD` evaluated before `SLIPPAGE`; `FUTURES` passes the DTE gate; extended `testPriceRelativeDenialsNeverExhaust` table; extended `testLiveReplayInputParity`.
5. **`LowestVolumeReversalServiceTest`** — mock `NseBanPeriodClient` via `service.setNseBanPeriodClient(mock)` (same injection pattern as `setNseOiSpurtClient`): banned symbol dropped from `finalActiveList` and from reservoir promotion; OPTIONS-mode DTE block drops the candidate; FUTURES mode unaffected; fail-open vs fail-closed dispositions; `resetDaily` clears ban state; persisted contract fields survive `LvrStateStore` round-trip.
6. **`LvrStateStoreTest`** — `DailyState` round-trip with the three new fields; a legacy snapshot without them still loads.
7. **Full regression:** `./gradlew test` remains 100% green (Spotless, SpotBugs, JaCoCo gates intact).

---

## 7. Rollout Plan

| Phase | Content | Risk | Status |
|---|---|---|---|
| **P0** | §4.1 Tuesday-expiry fix (`StockFnoRegistry` + `NseTradingCalendarUtil.getMonthlyExpiry` / `getWeeklyExpiry` / `isMonthlyExpiryDay`) | Correctness fix; behaviour changes only in the Thursday-vs-Tuesday month-end window and on holiday-rolled expiries. | **Done** |
| **P1** | `NseBanPeriodClient` + universe exclusion (§3.1, §3.2, §3.4) + state + scheduler + config | Additive; log-only by default so no symbol is denied until the endpoint is validated. | **Done** |
| **P2** | `BAN_PERIOD` entry gate (§3.3) | Turns on the hard block. Kill switch: `LVR_BAN_PERIOD_ENABLED=false`. | **Done** |
| **P3** | `EXPIRY_DTE` gate + universe exclusion + persisted contract (§4.2–§4.4) | Kill switch: `LVR_EXPIRY_DTE_ENABLED=false`, `LVR_OPTION_MIN_DTE_SESSIONS=0`. | **Done** |

P1 ships with `LVR_BAN_LOG_ONLY=true` (the default): the pre-market job fetches, logs and
Telegram-alerts the ban list, and `getBannedSymbols()` is populated, but the universe filter,
reservoir promotions and OI merge all consult `banPeriodEnforced()` — which is false in log-only
mode — so nothing is excluded. Validating against a live session looks like:

1. Run with `LVR_BAN_LOG_ONLY=true`; confirm `[LVR] [NSE-BAN] Ban list loaded for <date> — <n>
   symbol(s) in ban: [...]` and compare against NSE's ban-period report page.
2. Confirm the degraded path by observing an alert on a deliberate failure (e.g. an unreachable
   `LVR_NSE_BAN_URL`), and confirm the strategy continues unfiltered.
3. Flip `LVR_BAN_LOG_ONLY=false` (P2's universe exclusion takes effect immediately; the `BAN_PERIOD`
   entry gate lands separately).

---

## 8. Out of Scope

- Debit spreads, delta/liquidity gates, IV-percentile filtering, and index-lane (NIFTY/BANKNIFTY weekly) support — separate specs.
- MWPL utilisation-based pre-warning (approaching, but not yet in, ban) — a candidate follow-up once the ban gate is proven.
- Reconciling the broader spec/doc drift (`15:15` vs `15:00` hard exit, `1:4` vs `2.5R`, stale `TODO.md`, README's "direction from 1st 5m candle") — tracked separately; note that §4.1 makes `TODO.md`'s "options path unimplemented" claim doubly stale.
