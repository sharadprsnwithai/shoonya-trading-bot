# Spec: 30-Second Live Price Monitoring — Armed Trigger + SL + Target 1

## Problem
All critical order actions (trigger breach → entry, SL hit → exit, Target 1 hit → partial book) currently run at 5-minute candle-close frequency. A stock that breaches its SL or Target mid-candle won't be detected until the next candle close — up to 5 minutes of latency on entries and exits.

## Goal
Check armed setups **and** open positions every **30 seconds** on live stock price:
- Trigger breach → instant entry
- SL breach → instant exit
- Target 1 breach → instant partial book

The existing 5-minute cycle continues to handle setup-state transitions, armed-timeout counting, and SuperTrend trailing (which requires candle data).

---

## Architecture

| Layer | What | Frequency | Notes |
|-------|------|-----------|-------|
| **5-min cycle** (existing) | `runCycle()` → full state machine + full `evaluateOpenPositions()` | 5 min | State transitions, timeout counter, SuperTrend trailing, hard exit at 15:00. Unchanged. |
| **30-sec cycle** (NEW) | `evaluateLivePriceActions()` | 30 sec | Only armed triggers + open position SL/Target1 checks. Uses live stock quote. Does NOT touch timeout counter. |

---

## Changes

### 1. Scheduler — add 30-second method
**File:** `LowestVolumeReversalScheduler.java`

New method `scheduledLivePriceCheck()`:
- `@Scheduled(fixedRate = 30000)` — every 30 seconds
- Guard: market hours (09:25–14:45 IST)
- Guard: `schedulerEnabled`
- Calls `strategyService.evaluateLivePriceActions()`

---

### 2. Service — new method `evaluateLivePriceActions()`
**File:** `LowestVolumeReversalService.java`

Unified method handling both armed triggers and open position SL/Target1 checks:

```
evaluateLivePriceActions():
  1. Collect unique symbols from:
     a. activeSetups where state == TRIGGER_ARMED
     b. openPositions where not closed

  2. For each unique symbol (concurrently via executor):
     a. Resolve stock token
     b. fetchQuote("NSE", token) → JsonNode with "lp" (LTP), "h" (session high), "l" (session low)
     c. If null/zero → skip (retry next tick, 5-min cycle is fallback)

  3a. ARMED TRIGGER CHECK (for TRIGGER_ARMED setups):
      - LONG:  session high >= trigger price
      - SHORT: session low  <= trigger price
      - If triggered → executePaperTradeEntry(setup, fillPrice, slPrc)
      - Does NOT increment armed timeout counter

  3b. OPEN POSITION CHECK (for each open position):
      Stock price = LTP (last traded price from live quote)

      i.  SL HIT check (runs FIRST, before Target 1):
          - LONG:  LTP <= currentStockSl → executeHardExit(LTP, "SL hit")
          - SHORT: LTP >= currentStockSl → executeHardExit(LTP, "SL hit")

      ii. TARGET 1 PARTIAL BOOK check (only if !partialBooked):
          - LONG:  LTP >= target1StockPrice → executePartialBook(premium from NFO quote)
          - SHORT: LTP <= target1StockPrice → executePartialBook(premium from NFO quote)

          Note: Target 1 partial book also fetches option premium for P&L.
          If option premium fetch fails → skip partial book this tick (5-min cycle retries).

      iii. SL/Target1 breach is on stock price only (not premium).
           Premium is only fetched at fill time for P&L computation.

```

---

### Key difference from `evaluateOpenPositions()` (5-min cycle)

| Check | 30-sec (NEW) | 5-min (existing) |
|-------|--------------|------------------|
| SL hit (stock price) | ✅ Live LTP | ✅ Candle close |
| Target 1 partial book (stock price) | ✅ Live LTP | ✅ Candle close |
| SuperTrend trailing exit | ❌ Requires candle data | ✅ |
| Hard exit at 15:00 | ❌ Handled by 5-min | ✅ |
| Runner exit via SuperTrend | ❌ | ✅ |

The 30-sec check catches SL and Target 1 breaches faster. The 5-min cycle catches SuperTrend trailing and hard exit (which don't need sub-minute timing).

---

### 3. Config
**File:** `application.properties`

```
trading-bot.strategy.lowest-volume.live-breach-check-enabled=${LVR_LIVE_BREACH_CHECK:true}
```

New boolean field + getter/setter in service. When disabled, falls back to 5-min-only behavior (current behavior).

---

### 4. Thread safety
- `evaluateLivePriceActions()` reads `activeSetups` and `openPositions` which are ConcurrentHashMaps
- `executePartialBook()` and `executeHardExit()` are `synchronized` on the position object
- Quote fetches are parallelized via existing `executor` (CompletableFuture)
- No new synchronization needed — same pattern as existing `evaluateOpenPositions()`

---

### 5. Double-action prevention

Both 30-sec and 5-min cycles can detect the same breach. Prevents double-action:

| Scenario | Protection |
|----------|-----------|
| SL hit detected by both cycles | `executeHardExit()` checks `pos.isClosed()` — second call is a no-op |
| Target 1 detected by both cycles | `executePartialBook()` checks `pos.isPartialBooked()` — second call is a no-op |
| Trigger detected by both cycles | `openPositions.putIfAbsent()` — second entry is ignored |

---

### 6. Tests
**File:** `LowestVolumeReversalServiceTest.java`

New test cases:
- `testLiveCheck_TriggersArmedSetup` — LONG armed, session high breaches trigger → entry fires
- `testLiveCheck_ExitsOnSLHit` — open LONG position, LTP drops below SL → exit fires
- `testLiveCheck_PartialBooksOnTarget1` — open LONG position, LTP rises above target1 → partial book fires
- `testLiveCheck_SkipsClosedPosition` — closed position → no action
- `testLiveCheck_SkipsWhenQuoteFails` — null quote → position stays open
- `testLiveCheck_DoesNotIncrementTimeout` — after 30-sec check, `armedCandlesElapsed` unchanged
- `testLiveCheck_ShortSLHit` — open SHORT position, LTP rises above SL → exit fires
- `testLiveCheck_SLCheckedBeforeTarget1` — LTP breaches both SL and Target1 simultaneously → only SL exits (SL takes priority)

---

## What stays unchanged
- All setup-state logic (SCANNING → LEG_CONFIRMED → PULLBACK → TRIGGER_ARMED)
- Option entry/exit/SL/trailing logic
- Position management in `evaluateOpenPositions()` (SuperTrend, hard exit)
- Telegram alerts (same entry/exit messages fire regardless of which scheduler triggered action)
- Existing `evaluateArmedTrigger()` in 5-min cycle (fallback)
