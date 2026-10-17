# LVR Two-Sided Sector Selection & 3-Day Triangle Pattern — Design

- **Date:** 2026-10-17
- **Status:** Approved for implementation
- **Scope:** Morning universe selection (`runMorningUniverseScan`), setup arming, sentiment plumbing
- **Not in scope:** Entry/exit gates, risk sizing, ban/DTE exclusion, cost model, persistence schema

---

## 1. Motivation

Selection today is one-directional and index-gated:

1. `evaluateMarketSentiment` reads NIFTY 50 breadth at 09:25 and returns `LONG` / `SHORT` / `NONE`.
2. `NONE` (`< min-breadth-pct`, default 56%) **stands the whole day down** (`standDownToday = true`).
3. `rankSectors` sorts sectors in that one direction; the first sector yielding candidates wins
   (`winningSector`), and `filterCandidateStocks` keeps only that direction's sign.
4. Every watchlist symbol inherits the single sentiment: `new LowestVolumeSetup(symbol, sentiment)`.

Three consequences, all confirmed in code:

- **A single-index view decides the whole book.** A mixed NIFTY kills the session even when two
  sectors are cleanly trending in opposite directions.
- **Only one side is tradeable.** With `LVR_INSTRUMENT_TYPE=FUTURES` and a `LONG` reading, the
  weakest sector is never considered, so half the opportunity set is structurally invisible.
- **The name overpromises.** The candidate filter ranks purely by `%pctChange`; "lowest volume"
  only operates later at trigger-arming time (`evaluateCandleSequence`).

## 2. Decisions (confirmed with the requester)

| # | Decision |
|---|----------|
| D1 | Triangle rule is the **full chain on both sides**: `H(D-3) > H(D-2) > H(D-1)` **and** `L(D-3) < L(D-2) < L(D-1)`. Strict, never non-strict. |
| D2 | Pattern uses the **last 3 completed daily bars** (D-3, D-2, D-1). Today's in-progress bar is **excluded**. |
| D3 | A triangle stock is **considered for both sides and arms one** — whichever forms a valid lowest-volume pullback, re-evaluated every 5-minute cycle. No `symbol+direction` rekey. |
| D4 | NIFTY breadth becomes **reporting-only**: it never selects, never sets direction, and a mixed reading **no longer stands the day down**. |
| D5 | Sector channel widens to **3 bullish + 3 bearish sectors, top 3 stocks each** (≤ 18) plus triangle picks. |
| D6 | Sector sign must match the side: a bullish sector needs `avgPctChange > 0`, a bearish one `< 0`. A side with no qualifying sectors simply contributes nothing that day. |

### D3 — the constraint that shaped this

`activeSetups` and `openPositions` are both `Map<String, …>` keyed by symbol alone
(`LowestVolumeReversalService:224-225`), and ~20 sites — concurrency limits, daily-loss budget,
circuit breaker, per-symbol exhaustion — assume one setup and one position per symbol. True
simultaneous two-way arming would require rekeying both maps and reworking every consumer.

It is also unnecessary in practice: the arming rule looks for an *opposite-colour, rolling-lowest-*
volume* candle, and any single candle is either red or green, so a given bar can only arm one side.
The retained design therefore evaluates both directions and commits to whichever side actually forms
a pullback — capturing the intent at a small fraction of the risk.

## 3. New selection flow (09:25)

```
universeQuotes = fetchMorningQuotesUnified()
niftyQuotes    = universeQuotes ∩ NIFTY_50              → breadth (REPORTING ONLY)
sectorQuotes   = universeQuotes grouped by sectoral index

ranked = scanner.rankSectorsByStrength(sectorQuotes)     // descending by avg % change

bullish = ranked.filter(pct > 0).take(first N)                       // D5/D6
bearish = ranked.filter(pct < 0).sorted(asc).take(first N)           // most negative first

sectorPicks  = ⋃ filterCandidateStocks(sectorQuotes[s], LONG , stocksPerSector)  for s ∈ bullish
             ∪ ⋃ filterCandidateStocks(sectorQuotes[s], SHORT, stocksPerSector)  for s ∈ bearish

trianglePicks = triangleScan(universe, maxCandidates)     // §4, direction = NONE (two-sided)

finalActiveList = dedupe(sectorPicks ∪ trianglePicks) − isExcludedFromUniverse − exhausted
activeSetups[s] = new LowestVolumeSetup(s, direction)     // per-symbol, not global
```

`currentTopGainers` **and** `currentTopLosers` are now both populated, each holding its own side's
symbols and snapshots. `LowestVolumeSectorState` keeps its current shape (single
`winningSector`) — the strongest sector **by absolute move** is reported as a summary field only; it
no longer drives selection.

### 3.1 Two-sided arming

For a `direction == NONE` setup the 5-minute cycle evaluates both sides and latches:

```
longSide  = evaluateCandleSequence(symbol, LONG , candles, lastExit)
shortSide = evaluateCandleSequence(symbol, SHORT, candles, lastExit)

armed = { s ∈ {longSide, shortSide} : s.state == TRIGGER_ARMED }
pick  = armed.empty()          ? NONE                                   // try again next cycle
      : armed.size() == 1      ? the armed side
      : later(getTriggerCandle().timestamp)  , tie → LONG               // freshest pullback wins

setup.setDirection(pick)                                              // latched for the session
```

Sector-picked symbols keep their fixed direction and never enter this path.

## 4. Triangle detection

### 4.1 Rule (D1, D2)

Given the last **3 completed** daily bars in ascending order `d3, d2, d1` (so `d1` is yesterday):

```
triangle ⟺  H(d3) > H(d2) > H(d1)
         ∧  L(d3) < L(d2) < L(d1)
```

Strict inequalities throughout. Fewer than 3 completed bars, or any null high/low → **not** a
triangle (fail-closed, never an exception).

This is the classic contracting range: lower highs plus higher lows converging on a squeeze.

### 4.2 Data source

Reuses the existing PDH/PDL plumbing, in the same order — no new data dependency:

1. `ohlcCacheService.getDailyCandles(symbol)` (in-memory / SQLite, no network)
2. fallback `marketDataService.fetchDailyCandles(symbol, 5)`
3. `filterPastDailyCandles(candles, today)` — already strips today's partial bar (D2 satisfied
   for free) and returns **ascending** order, so the target bars are `n-3, n-2, n-1`.

A symbol whose daily bars cannot be resolved is skipped for the triangle channel only; it remains
eligible via the sector channel.

### 4.3 Ranking and cap

Candidates are ranked by **squeeze ratio** ascending (tightest first), where

```
squeezeRatio = (H(d1) − L(d1)) / (H(d3) − L(d3))
```

A smaller ratio means the range has contracted further, i.e. the pattern is more informative. Ties
break on symbol name so ordering is deterministic. Output is capped by
`triangle-max-candidates` (default **5**) to keep the watchlist bounded; the cap exists because the
triangle universe is every sectoral constituent, far wider than the sector channel.

### 4.4 Deduplication

A symbol picked by both channels appears once. Direction union applies: a sector `LONG` pick that
also matches the triangle stays `LONG` (sector direction wins — it is the more specific signal);
the triangle contributes only the symbol's *presence* in that case.

## 5. Sentiment plumbing (D4)

| Site | Before | After |
|---|---|---|
| `evaluateMarketSentiment` | selects direction | computed for reporting/Telegram/status only |
| `NONE` at 09:25 | `standDownToday = true`, clears setups, returns | **no stand-down**; scan proceeds normally |
| `niftyBullish` | sets setup direction for whole book | observability + Telegram wording only |
| `isWithinSentimentBand` (OI leg) | gates OI picks by index sign | band applied against the **per-pick direction**, not index sentiment |
| `min-breadth-pct` | hard stand-down threshold | logging/label only |

`standDownToday` itself is **kept** — it remains reachable via the circuit-breaker and explicit
stand-down paths, and the entry gate still honours it. Only the breadth trigger is retired.

### 5.1 Revert switch

`trading-bot.strategy.lowest-volume.nifty-bias-enabled` (env `LVR_NIFTY_BIAS_ENABLED`, default
`false`). When set to `true`, the previous one-directional behaviour is restored: sentiment gates
direction, a mixed reading stands the day down, and only the single winning sector is taken. This is
the single-line revert if the new selection underperforms.

## 6. Configuration

| Property | Env | Default | Meaning |
|---|---|---|---|
| `...selection.bullish-sectors` | `LVR_BULLISH_SECTORS` | `3` | Bullish sectors taken (D5) |
| `...selection.bearish-sectors` | `LVR_BEARISH_SECTORS` | `3` | Bearish sectors taken (D5) |
| `...selection.stocks-per-sector` | `LVR_STOCKS_PER_SECTOR` | `3` | Stocks per sector (D5) |
| `...selection.triangle-enabled` | `LVR_TRIANGLE_ENABLED` | `true` | Triangle channel on/off |
| `...selection.triangle-max-candidates` | `LVR_TRIANGLE_MAX` | `5` | Cap for §4.3 |
| `...nifty-bias-enabled` | `LVR_NIFTY_BIAS_ENABLED` | `false` | §5.1 revert switch |
| `...index-alignment-enabled` | `LVR_INDEX_ALIGNMENT_ENABLED` | `true` | §9 index-alignment gate |
| `...index-alignment-buffer-bps` | `LVR_INDEX_ALIGNMENT_BUFFER_BPS` | `10.0` | §9 dead-band |
| `...index-alignment-symbol` | `LVR_INDEX_ALIGNMENT_SYMBOL` | `NIFTY 50` | §9 index quoted (token 26000) |

`...max-concurrent-trades` (5) and the HYBRID merge cap are **raised in step** with D5, otherwise
the wider watchlist is throttled by limits sized for the old 6-symbol shape. Both remain
configurable; the defaults move to `8` and `12` respectively.

## 7. Testing

Implemented in the existing suites rather than the four new classes first sketched here — the
shared `evaluateEntryGate`, the scanner and the service already had homes for each concern.

| Test | Covers |
|---|---|
| `LowestVolumeReversalScannerTest` | D1 full chain both sides; strictness (equal high/low rejected); one-sided contraction rejected; <3 bars and null fail closed; only the last three bars examined; squeeze ratio + NaN cases; `rankSectorsByStrength` keeps both sides and sorts descending; direction-scoped `filterCandidateStocks` with a cap |
| `LowestVolumeReversalServiceTest` | D5 both sides populated from a mixed index; D6 sign gating (flat sectors excluded); breadth NONE no longer stands down (D4); legacy stand-down still holds with the bias on; per-symbol direction; two-sided arming — single armed side wins, both armed → freshest wins, tie → LONG, neither → stays neutral |
| `LvrEntryGateTest` | §9 index-alignment gate — misaligned LONG/SHORT denied, dead-band vetoes a near-miss and admits the boundary, fail-open on missing/zero previous close, flag off skips, live/replay parity |

Replay parity: `replaySession` reads the same per-symbol direction, so live and replay cannot
diverge on which side a symbol was armed.

## 8. Risks

- **Wider watchlist vs. risk limits.** 18+ symbols against `max-concurrent-trades` means the cap,
  not the thesis, will bind. Acceptable — it is a throttle, not a failure.
- **Triangle universe cost.** Triangle detection reads daily bars for every sectoral constituent.
  Primary source is the SQLite/in-memory OHLC cache (no network), so this is a local read; the
  Shoonya fallback is only hit on cache misses and is bounded by the cap.
- **Removing the breadth stand-down removes a chop filter.** This is the intended trade (D4) and is
  reversible via `LVR_NIFTY_BIAS_ENABLED=true`. If chop losses rise, the right fix is a
  volatility/VIX guard, not restoring an index-breadth gate.
- **Both-sides-in-one-symbol risk is unchanged** — a symbol still holds at most one position, so
  D3 cannot produce opposing exposure.

## 9. Index-alignment entry gate (added after design review)

The motivating complaint — "NIFTY reverses after 10am and the opportunity is missed" — has two
distinct halves. §3–§5 recover the *missed* opportunity by trading both sides. This gate addresses
the other half: entering a direction the index has already turned against.

**Placement.** `evaluateEntryGate`, immediately after `SLIPPAGE` and before `VWAP` — i.e. evaluated
at the armed→triggered transition, on every 30-second tick, never at scan time. The 09:25 watchlist
is therefore *unaffected*: a symbol stays armed and keeps re-evaluating, and the gate only vetoes
the individual tick on which its trigger fired.

**Rule.** `LONG` requires the index level to be above its previous close by the dead-band;
`SHORT` below it by the dead-band.

**Why previous close, not a 09:25 print.** The previous close is stationary, computed once by the
exchange, and never ambiguous about its own timestamp. An intraday anchor introduces a second
decision (when exactly to sample) that is not obviously better and cannot be reproduced in replay.

**Why a dead-band.** Without one, a sub-0.05% wobble around the reference flip-flops and randomly
vetoes valid breakouts — the filter degenerates into noise. Default is 10 bps; `0` reverts to a
naked cross for anyone who wants it.

**Fail-open.** A missing index quote, an unresolvable symbol, or a non-positive previous close all
skip the gate. A data outage must never block trading, mirroring the ban-list policy.

**Denial is `RETRY_COOLDOWN`, not `EXHAUST`.** The index crosses its previous close routinely, so
"wrong side at this tick" is not grounds to discard the symbol for the day — same reasoning as the
other price-relative confirmations.

**Data.** `NIFTY 50` resolves to NSE token 26000 and a single `fetchQuote` supplies both the live
level (`lp`) and the exchange previous close (`c`).

**Replay limitation.** `replaySession` is handed one stock's candles and has no index series, so it
leaves the index fields null and the gate is skipped there — the standard "null = not evaluated"
mechanism every other optional input uses. The consequence is that replay results are
*optimistic* relative to live for this gate: replay admits trades live would have vetoed. The gate
logic itself is covered by direct tests on the shared pure function.

**Honest scope.** This gate only *avoids* losses; it does not recover the missed 10am opportunity
(that is §3–§5). It reintroduces index coupling into entry decisions, which D4 removed from
selection — deliberately, and only as a veto, never as a selector or a direction source.
