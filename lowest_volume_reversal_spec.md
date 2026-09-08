# Intraday Strategy Spec: Lowest Volume Reversal & Continuation

Implementation-ready version. Source: "Lowest Volume Reversal & Continuation" by Kushal Varshney & Sagar Kummar.
Every ambiguity in the original has been resolved below as an explicit, stated decision (marked **[DECISION]**) rather than left implicit. Treat each decision as a parameter, not a fixed law — tune later against backtest data.

**Broker/execution:** Shoonya (Finvasia) API, integrated into the existing multi-broker trading bot.

---

## 1. Instrument & Universe

- **[DECISION]** Instrument traded = **cash equity intraday (MIS)**, not futures or options, on the underlying named by the scanner. Rationale: matches "high liquidity cash segment recommended for beginners" and avoids lot-size/margin complexity in position sizing. If you want F&O (futures) instead, swap the position-sizing formula in §6 to use lot size, not share qty.
- Universe = NSE stocks currently in the **Securities in F&O** list, filtered to that day's Top Gainers (long candidates) or Top Losers (short candidates).
- **[DECISION]** Re-scan the gainers/losers list every 5 minutes (on each new candle close), not once at 9:26 AM. A stock already being tracked in an active pullback stays tracked even if it drops off the list mid-setup; only *new* candidates are sourced from the refreshed list.
- **[DECISION]** Data source: **Shoonya REST historical/TPSeries API**, polled per candle close (5-min interval), used for both ATR/volume-window backfill and live pattern detection — over unofficial NSE scraping endpoints. NSE's `live-analysis-variations`, `oi-spurts-underlyings` endpoints are unauthenticated-scrape-only, rate-limited, and liable to break — use them only for a one-time gainers/losers seed list if Shoonya doesn't expose an equivalent screener, not for live candle data.
  - **[OPEN]** Confirm Shoonya's TPSeries rate limits can sustain per-candle polling across the full gainers/losers watchlist (potentially dozens of symbols) without throttling. If not, fall back to websocket tick aggregation for symbols already in `PULLBACK_TRACKING` or later state, keeping REST polling only for the wider scanning universe.
- **[OPEN]** No live (non-pre-open) Nifty advances/declines breadth API has been identified. Until resolved, approximate breadth using % of your Top-Gainers/Top-Losers universe that's currently green vs red, or drop the breadth filter entirely for v1.

## 2. Session Timing

| Time | Action |
|---|---|
| 09:15–09:25 | No trading. Data collection only (candles still logged for volume history). |
| 09:26 onward | Scanner active, setups may begin forming. |
| 09:26–15:00 | Trading window. |
| 15:00 | Hard exit: close all open positions at market. **[DECISION]** No new entries accepted after 14:45 (15-min buffer before hard close) even if a valid trigger fires — a fresh position with only 15 min of runway defeats the RR structure. |

## 3. Setup Definition (Long — mirror for Short)

### 3.1 Initial Leg
**[DECISION]** Initial leg = 2–3 consecutive candles where:
- Each candle is green (close > open), AND
- Cumulative move (last initial-leg candle's close − first initial-leg candle's open) ≥ **0.5 × 5-min ATR(14)** of that stock, computed on the 5-min chart using the prior day's last 14 candles as seed.

This replaces the vague "strong upward momentum" with a measurable, tunable threshold.

### 3.2 Pullback / Lowest-Volume Candle Tracking
**[DECISION]** Volume comparison window = **rolling, not cumulative-since-open**. Compare each pullback candle's volume against the **preceding 10 candles** (or all candles since 09:26 if fewer than 10 have printed). This fixes the original's cumulative-vs-day contradiction and removes the time-of-day bias where afternoon setups could never qualify.

- Once the initial leg completes, watch for a red (opposite) candle.
- **[DECISION]** If the pullback runs multiple red candles before a breakout, re-anchor dynamically: the "trigger candle" is *whichever* red candle currently holds the lowest volume among the tracked window, updated on every new candle close, until either (a) price breaks its high, or (b) the setup is invalidated (§3.4).
- **[DECISION]** Tie-break rule: if two candles have equal volume, the **most recent** one becomes the trigger candle (keeps the SL tighter to current price action).

### 3.3 Range Filter (new — not in original)
**[DECISION]** Reject the trigger candle if its high-low range > **1.2 × ATR(14)** on the 5-min chart. Low volume does not guarantee a tight range (thin book / stop-hunt wicks can produce a wide low-volume candle); this filter protects the "ultra-tight SL" premise the whole strategy depends on.

### 3.4 Trigger, Entry, Stop Loss
- **[DECISION] Entry:** Native **Shoonya SL-M order** (trigger price = trigger candle's High + 1 tick, no limit price — market fill once triggered), placed as soon as the trigger candle is identified. Using the broker-side stop order (rather than bot-side polling) removes bot/network latency between breach detection and order placement.
  - **[OPEN]** If Shoonya's SL-M implementation requires a limit price band (some brokers reject pure SL-M on certain segments), fall back to **SL-L** with limit = trigger price + 0.3% buffer, and treat any non-fill within 1 candle as a missed setup rather than chasing.
- **Stop Loss:** Trigger candle's Low − 1 tick. Placed as a second native Shoonya SL-M order immediately on entry fill (order-on-fill / OCO-style bracket if Shoonya's order API supports it; otherwise bot places the SL order on the fill confirmation callback).
- **[DECISION] Setup timeout:** If the SL-M entry order hasn't triggered within **12 candles (60 min)** of the trigger candle forming, cancel the pending Shoonya order and drop the setup. Prevents indefinitely-stale orders resting on the exchange.
- **[DECISION] Slippage guard:** Cap acceptable fill slippage at 0.15% of trigger price. Since this is a native stop order, slippage is measured from the Shoonya fill confirmation (not bot-estimated) — if fill price exceeds the cap, log it and recompute realized SL distance and RR from the actual fill, not the trigger price.

### 3.5 Exhaustion Disqualifier
**[DECISION]** Reject the stock entirely (no setups tracked for it that session) if:
- The first 5-min candle of the day moves ≥ 5%, **OR**
- The cumulative move across the initial leg (§3.1) alone already exceeds 6% from the day's open.

This closes the original's gap (it only checked candle #1, missing multi-candle exhaustion).

## 4. Short Setup

Exact mirror of §3 with colors and directions inverted: initial leg = red candles, pullback candle = green, entry = stop-sell below trigger candle's Low, SL = trigger candle's High. Same range filter, timeout, tie-break, and exhaustion rules apply.

## 5. Position Sizing & Risk

- Daily risk budget = 1% of account capital.
- **[DECISION]** Max concurrent/total trades per day = 2 (configurable), RPT = daily budget ÷ trade count.
- **[DECISION] Sizing cap:** `qty = min(RPT / SL_distance, max_qty_by_liquidity, max_qty_by_margin)`.
  - `max_qty_by_liquidity` = a fraction (e.g. 1%) of the **real-time best 5-depth ask size** (long) / bid size (short) pulled from **Shoonya's market depth/quote endpoint** at signal time — a live order-book read, not the trigger candle's historical volume. This is a tighter, more realistic cap since it reflects what's actually resting on the book right now rather than volume that already traded.
  - `max_qty_by_margin` = available MIS margin (from Shoonya's margin/limits API) ÷ trigger price.
  - This closes the original's gap where a very tight SL could imply an unrealistically large share quantity relative to the stock's actual liquidity.
- Target 1 = 1:2 RR, book 50% of position, move SL on remainder to breakeven (cost).
- **[DECISION] Runner management (undefined in original):** trail the remaining 50% using a 5-min Supertrend(10,3) — exit runner fully on a Supertrend flip against the position, or at the 15:00 hard close, whichever comes first. (Matches your existing Supertrend-based exit logic used elsewhere — reuse rather than inventing a new trailing method.)
- Hard exit: flatten all open positions at 15:00, market order, no discretion.

## 6. State Machine (per stock, per session)

```
IDLE
  → SCANNING          [stock enters Top Gainers/Losers list, 09:26+]
SCANNING
  → LEG_FORMING        [1st candle in intended direction with sufficient body]
  → SCANNING           [candle breaks pattern before 2 candles complete]
LEG_FORMING
  → LEG_CONFIRMED       [2-3 candles satisfy §3.1 threshold, exhaustion check passes]
  → REJECTED_EXHAUSTED  [exhaustion filter trips, §3.5]
  → SCANNING            [leg fails to confirm]
LEG_CONFIRMED
  → PULLBACK_TRACKING   [opposite-color candle prints]
PULLBACK_TRACKING
  → PULLBACK_TRACKING   [new opposite-color candle; re-anchor if it has lower volume]
  → TRIGGER_ARMED       [current trigger candle passes range filter, §3.3]
  → SCANNING            [same-direction candle prints, invalidating pullback — leg has resumed without a qualifying entry]
TRIGGER_ARMED
  → IN_POSITION         [price breaks trigger candle High(long)/Low(short), order fills within slippage guard]
  → SCANNING            [6-candle timeout elapses without fill, §3.4]
  → PULLBACK_TRACKING   [a new, lower-volume opposite-color candle re-anchors before trigger fires]
IN_POSITION
  → PARTIAL_BOOKED      [price hits 1:2 RR — book 50%, SL to breakeven on remainder]
  → CLOSED_SL           [SL hit before target]
  → CLOSED_TIMEOUT      [15:00 hard exit]
PARTIAL_BOOKED
  → CLOSED_TRAIL_EXIT   [Supertrend flip on remainder]
  → CLOSED_TIMEOUT      [15:00 hard exit]
```

## 7. Explicit Open Items for Backtesting Before Live Use

1. Tune ATR multipliers in §3.1 and §3.3 (0.5× and 1.2× are starting guesses, not derived).
2. Validate the 10-candle rolling volume window (§3.2) — original didn't specify a window at all; this needs sensitivity testing.
3. Resolve the live breadth API gap (§1) or formally drop the Nifty-alignment filter from v1.
4. Backtest whether the exhaustion thresholds (5% first candle / 6% cumulative leg) are appropriately calibrated per stock volatility (a flat % may be wrong for low-vs-high-beta F&O names).
5. Confirm Shoonya TPSeries polling can keep pace across the full watchlist without rate-limit throttling (§1).
6. Confirm whether Shoonya's order API supports a true OCO/bracket (entry SL-M + protective SL-M as one linked pair) or whether the bot must place the protective SL manually on the fill callback — affects worst-case exposure in the gap between fill and SL placement (§3.4).
7. Confirm Shoonya SL-M behavior on the specific exchange segment used (NSE cash) — some brokers require SL-L with a limit band rather than pure SL-M; fallback is noted in §3.4 but needs verifying against live Shoonya docs/behavior.
