# Dual-Level Breakout VWAP (BVS) — Detailed Specification

Date: 2026-10-22
Status: approved by owner (all open items resolved); ready for implementation planning
Package: `com.tradingbot.strategy.breakoutvwap` · config prefix `trading-bot.strategy.breakout-vwap` · env prefix `BREAKOUT_VWAP_` (names are author's call, rename freely)

## Intent

A fourth active intraday strategy: pick 12 stocks at 09:25 from four
"unusual activity" buckets, wait for a **dual-level breakout** (the
09:30–10:00 range *and* the previous day's H/L, both broken in the same
direction), then use a **VWAP-crossing 5-minute candle in the bias
direction as the trigger** and enter futures on the break of that
trigger candle's high/low. Momentum continuation on stocks that were
already separating from the pack before 10:00.

Unlike LVR, this strategy is allowed to do nothing: no dual confluence,
no bias, no trade.

## Decision log

**[owner]** = decided by the partner in conversation.
**[author]** = my call to make it deterministic; change by asking.
**[builder]** = deliberately left to the implementer.

### Universe construction (09:25 IST, same schedule as LVR)

1. **[owner]** Four buckets, 3 stocks each, **12 total**:
   - Bucket A: top 3 **live** gainers — NSE live data as of 09:25
     (first ~10 minutes of the session), **not** previous day. **[owner]**
   - Bucket B: top 3 **live** losers — same source/time. **[owner]**
   - Bucket C: top 3 from the **OI-spurt list** — same NSE OI-spurt feed
     LVR uses ("IO spurt" read as OI spurts). **[owner on the reuse]**
   - Bucket D: 3 stocks matching **"3 days lower high and higher low"**
     — formalized as: for each of the **last 3 completed sessions**, the
     session's high is lower than the prior session's high **and** its
     low is higher than the prior session's low (range contraction on
     both sides; requires 4 sessions of daily candles), scanned over
     **all F&O-eligible equities**. **[author formalization; owner
     approved "all F&O" universe]**
2. **[author]** Ranking universe for buckets A/B is the **NSE F&O
   registry** itself: BVS bulk-quotes the registry at 09:25 (the same
   bulk-quote mechanism LVR already uses) and ranks percent-change
   locally — "top gainer" always means a stock we can actually trade.
   **[author, confirmed at builder check]**
3. **[author]** All buckets intersected with the F&O registry, ranked
   lists de-duplicated across buckets, shortfalls backfilled from the
   next rank of the same bucket.
4. **[author]** Bucket C: BVS injects the shared `NseOiSpurtClient` and
   makes its **own fetch**, scheduled a few seconds after LVR's
   09:25:00 call (e.g. 09:25:05) to avoid a same-second double hit on
   NSE. It does **not** read LVR's staged internals. **[author,
   confirmed at builder check]**
5. **[owner]** **Ban-period exclusion**: stocks on the NSE F&O ban
   list are excluded from all buckets at selection time. If the
   ban-list fetch fails, **fail closed** — the affected stock is
   excluded (skip, don't gamble on an unverifiable ban status).
   **[owner decision "exclude ban"; fail-closed is author's reading]**
6. **[author]** A bucket whose feed fails degrades like LVR's hybrid
   scan: proceed with the surviving buckets (minimum viable universe =
   any bucket), log a warning; if *no* bucket can be built, the
   strategy stands down for the day. **[author]**
7. **[builder]** Exact NSE endpoints, DTOs, and throttling for live
   gainers/losers; reuse of the existing OI-spurt fetch as-is.

### Levels and bias

8. **[owner]** Two level pairs, on 5-minute candles:
   - **Session range**: high/low of candles starting **09:30–09:55**
     (the six 5m candles fully inside 09:30–10:00). Watched from
     **10:00:00** onward. **[author on the exact candle set]**
   - **Previous-day high/low (PDH/PDL)** of the last completed session
     via the NSE trading calendar (holidays handled by the existing
     utility).
9. **[owner]** Bias requires **both** pairs broken in the same
   direction, evaluated on completed 5m candle closes after 10:00:
   - **LONG** when `close > max(sessionRangeHigh, PDH)` (above *both*
     highs).
   - **SHORT** when `close < min(sessionRangeLow, PDL)` (below *both*
     lows).
   - otherwise **NONE** — no bias, no trades.
   **[author on max/min formulation and close-based evaluation]**
10. **[owner]** Bias **latches and holds**: once confluence fires, the
    bias persists even if price retreats back between the levels; it
    flips only on **opposite confluence**. Bias gates *new entries
    only* — it never exits an open position. Each stock keeps its own
    bias state. **[owner chose option (a)]**

### Trigger and entry

11. **[owner]** Trigger candle: a completed **5m candle that closes
    beyond session VWAP in the bias direction** (LONG: close > VWAP;
    SHORT: close < VWAP). No body-color or size requirement — reading
    the request literally. **[author on "close" vs intra-candle]**
12. **[author]** Session VWAP = standard anchored VWAP from 09:15,
    typical price `(H+L+C)/3` weighted by volume, via the repo's
    existing `TechnicalAnalysisService` helper. **[builder to reuse]**
13. **[owner]** Entry: LONG fires when price **trades above the trigger
    candle's high** within **5 minutes of the trigger candle's close**;
    SHORT mirrors on the trigger candle's low. Window expires unfilled
    → setup void, wait for the next trigger candle (bias still holds).
    Market order at the break. **[author on "5 minutes" = one candle
    from trigger close]**
14. **[author]** **R = |entry − opposite extreme of the trigger
    candle|**; that opposite extreme is the initial stop for the whole
    position. **[author]**

### Money management (futures)

15. **[owner]** **Futures only**, no options. **[owner]**
16. **[owner]** At **+2.5R** (price distance = 2.5 × R), **sell 50%**
    of the position (odd lots round up in the trader's favor).
    **[owner]**
17. **[owner]** Runner exits when a 5m candle **closes across the 10 EMA
    against the bias** (LONG: close < 10 EMA; SHORT: close > 10 EMA),
    EMA computed on the same 5m series. **[owner]**
18. **[owner]** **Runner stop moves to breakeven (entry) at the moment
    of the 50% partial** — implemented with the existing
    `UPDATE_STOP_LOSS` execution path. **[owner chose breakeven]**
19. **[author]** House session rules reused: **no new entries after
    14:30** (current LVR cutoff), **15:00 hard exit** flattens
    everything. **[author]**
20. **[author]** Re-entry: after any exit, the same stock may arm a new
    setup later (own trigger, own R) until cutoff; one *open* position
    per stock at a time; a bias flip cancels armed-but-unfilled
    triggers. **[author]**
21. **[author]** Trade sizing: same per-trade risk-percent config LVR
    uses (reusing the sizing helper). **[builder to mirror]**

### Execution isolation

22. **[author]** BVS runs its **own execution-consumer lane** (own
    consumer instance / ledgers keyed so BVS and LVR positions on the
    same underlying never share `confirmedEntries`, protective-SL or
    exit bookkeeping). `ReactiveSignalEventBus` broadcast shape stays
    as-is; isolation is achieved by giving BVS a dedicated consumer,
    **not** by rewriting the shared ledger keying. **[author, confirmed
    at builder check — this relaxes "no shared code changes" only to
    the extent of registering one more consumer]**
23. **[builder]** Route through the existing decoupled execution
    consumers (broker = house default; PAPER mode via the same env
    overrides as LVR/CAR).

### Persistence

24. **[owner]** BVS **persists its runtime state to disk like LVR**
    (`lvr-state.json` pattern): bias latches, armed triggers, session
    ranges, PDH/PDL, open positions with partial state and breakeven
    flag — so a mid-day restart resumes rather than forgets.
    **[owner chose "like LVR"]**
25. **[author]** State file `bvs-state.json` under the same directory
    conventions as `lvr-state.json`; corrupt/missing file → clean start
    with a logged warning (same tolerance as LVR store). **[author]**

### Notifications, config

26. **[author]** Telegram: entry, partial, breakeven-stop move, runner
    exit, day summary — reuse `TelegramService` like the other
    strategies. **[author]**
27. **[author]** Config: `enabled`, `risk-per-trade`, `rr-partial`
    (default 2.5), `ema-period` (10), `entry-cutoff` (14:30),
    `hard-exit` (15:00), `bucket-size` (3), `trigger-window-minutes`
    (5), `session-range-start` (09:30), `session-range-end` (10:00),
    `scan-cron` (09:25:00), per-bucket toggles. All `BREAKOUT_VWAP_`
    env-overridable. **[author]**
28. **[builder]** Wiring a new `strategy-modes.bvs` entry and the
    strategyId through config exactly as existing strategies.

## Goals

- Deterministic, test-covered strategy engine: given the 09:25 list,
  daily candles and 5m candles, every entry/exit decision is
  reproducible.
- No behavioral change to LVR/CAR/GARCH — BVS consumes shared feeds via
  their public components only, and holds its own execution ledgers.
- Paper-tradeable on day one via the existing execution-mode switches,
  restart-safe via the state store.

## Non-goals / anti-goals

- No backtest harness in the first cut (natural follow-up; the engine
  must stay backtest-friendly: pure functions over candle series).
- No options variant, no bucket re-scan during the day, no manual
  mid-day list edits.
- No new dependencies; no rewrites of shared feed or execution code —
  extensions (a new consumer registration) only.
- No live trading in the first deployment — paper mode default.

## Constraints

- Java 21 / Spring Boot house structure, Javadoc on public API, tests
  for every new class of behavior (TDD), Spotless/SpotBugs/PMD/JaCoCo
  gates green.
- NSE endpoints are rate-limited and market-hours-only; the 09:25 scan
  must tolerate partial feed failure (rule 6), and BVS's OI-spurt call
  must be staggered from LVR's (rule 4).
- Nothing executes live without the env's paper/live switches — same
  guard rails as today.

## Resolved at builder check (2026-10-22)

- Shared-ledger collision risk with LVR → dedicated consumer lane
  (rule 22).
- OI-spurt fetch ownership → BVS's own staggered call (rule 4).
- Gainers/losers ranking universe → F&O registry bulk-quote (rule 2).
- Ban-period handling → exclude + fail-closed (rule 5).
- Restart persistence → LVR-style disk store (rules 24–25).
- Bias latch, runner breakeven, bucket-D universe → owner answers
  folded in (rules 10, 18, 1).

## What's left to the builder

Implementation sequencing and class layout, NSE endpoint/DTO wiring,
reuse mapping onto existing utilities (VWAP, EMA, sizing, execution,
state store), and the full test plan — via the standard planning
workflow after this specification.
