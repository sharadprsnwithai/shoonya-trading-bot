# BOLLINGER_HA_1M — Backtest Findings & Disposal Decision

**Date:** 2026-10-04
**Status:** Strategy **disabled by default** (`BOLLINGER_HA_ENABLED=false`). Code retained, not deleted.
**Decision owner:** manual review of backtest evidence below.

## Decision

BOLLINGER_HA_1M (NIFTY weekly ATM option buying on 1-minute Bollinger Band breakouts) was
backtested on ~7 months of real Shoonya 1-minute index data (2026-03-10 → 2026-10-01).
It was tuned on the most recent 18 sessions, then validated out-of-sample on the preceding
122 sessions. **The out-of-sample result was negative across every parameter variant tested.**
The strategy is therefore not tradable with real money and has been disabled by default.

Do not re-tune it on the same data — that would only refit the noise that just failed validation.

## What was tested

- **Signal** (mirrors `com.tradingbot.strategy.bollingerha.*`): 1m Heikin-Ashi close crosses
  above/below BB(20, 2.0) on the NIFTY index, price touching the band on the signal candle,
  EMA(20) trend filter, entry window 09:15–10:30, max 2 trades/day, 2 lots (130 qty).
- **Risk/exits:** entry − EMA-slow-stop with 2pt buffer (2–20pt clamp), 50% partial at 1R (RR 1:2),
  cost-based trail on the remainder, gap-aware stop on the underlying, hard square-off 15:00.
- **Premiums:** Black-Scholes synthesized ATM weekly option (r = 6.5%, tick 0.05) from the real
  index 1m path and real India VIX 1m. **Real historical weekly premiums are not retrievable** —
  Shoonya and Kite both purge expired contracts from their instrument masters (verified: earliest
  NIFTY expiry on Kite = 2026-10-06). Results are model-based, not fill-accurate.
- **Costs:** ₹25 per order, ₹0.05/share slippage.

**Reproduce:** `python scripts/backtest_bollinger_ha_1m_shoonya.py --fetch --start 2026-09-07 --end 2026-10-01`
(`--fetch` downloads 1m NIFTY + VIX history into `data/backtest/`, needs `.env` Shoonya credentials).

## Results

### In-sample (2026-09-07 → 2026-10-01, 18 sessions — where parameters were picked)

| Variant | Trades | Win % | Net ₹ | Max DD ₹ | T1 hits |
|---|---|---|---|---|---|
| Baseline (RR 1:2) | 17 | 41.2 | **+12,661** | 6,115 | 7 |
| RR 1:1 | 17 | 23.5 | −2,857 | 8,215 | 7 |
| RR 1:3 | 17 | 17.6 | +3,244 | 9,810 | 3 |
| HA body ≥ 0.5×range | 13 | 53.8 | +15,303 | 3,500 | 7 |
| Index moved ≥10 pts | 10 | 60.0 | +15,261 | 2,592 | 6 |
| body≥0.5 + max-risk 12 | 12 | 58.3 | +17,656 | 2,474 | 7 |

The profitable variant looked clean: win rate 41–58%, controlled drawdown, and the standard
reasoning about 1:2 RR holding up.

### Why in-sample profit existed at all

Exit-reason decomposition of the baseline IS run:

| Exit reason | Trades | Net ₹ |
|---|---|---|
| INITIAL_SL (loss) | 10 | −9,560 |
| T1_PARTIAL | 7 | +7,903 |
| AUTO_SQUARE_OFF (runners) | 2 | **+15,574** |
| GAP_STOP | 1 | −10 |
| COST_SL | 4 | 0 |

**93% of profit came from 2 runner trades.** Excluding them the same period is −₹2,913 gross.
The base system (entries that never became runners) was already losing before validation.

### Out-of-sample (2026-03-10 → 2026-09-04, 122 sessions — never used for tuning)

| Variant | Trades | Win % | Net ₹ | Max DD ₹ |
|---|---|---|---|---|
| Baseline (RR 1:2) | 102 | 27.5 | **−40,320** | 48,322 |
| HA body ≥ 0.5 | 84 | 31.0 | −22,078 | 33,029 |
| Index moved ≥10 pts | 71 | 25.4 | −44,177 | 45,219 |
| Max risk 12 pts | 80 | 26.2 | −26,390 | 23,819 |
| body≥0.5 + max-risk 12 | 65 | 29.2 | **−12,915** | 12,876 |
| idx≥10 + body≥0.5 + risk12 | 53 | 34.0 | −21,721 | 19,131 |

NIFTY itself fell −1.23% over the window (a mild down-drift regime), so the losses are not simply
"the market dropped." Expectancy ≈ **−₹395/trade** (baseline) and ≈ **−₹199/trade** even for the
best-looking variant.

Monthly net, baseline: Mar +12,248 · Apr −9,586 · May −12,351 · Jun −20,773 · Jul −5,558 ·
Aug −1,232 · Sep 1–4 −2,585 → **negative in 6 of 7 months**. The best variant
(body≥0.5 + risk12) was negative in 4 of 7.

**The single profitable window (Sep 7–Oct 1) is exactly the window the parameters were chosen on** —
selection bias, not edge. The `index-move ≥10` filter, which looked strong in-sample, made things
*worse* out-of-sample than no filter at all.

## Defects found (open)

These were identified during validation and are not fixed (strategy is off):

1. **Stop can go ≤ 0.** `stop = EMA_low − buffer` for a near-worthless premium (reproduced
   2026-09-29: entry ₹1.10 → stop −₹0.92) → broker rejects the `SL_LMT` and the position sits
   unprotected. Needs a floor/`min-entry` guard in `BollingerHaIntradayEngine`.
2. **`.env` square-off drift.** `.env` still has `BOLLINGER_HA_AUTO_SQUARE_OFF_TIME=15:15` while
   properties/spec say `15:00`.
3. **`min-sl-points = 2` is vacuous** — entry − stop is ≥ 2 by construction; only `max-sl-points`
   ever binds.

## What was retained in code, and why

The strategy **code stays in the repo** (disabled): it is reviewed, tested (456 tests green), and
deleting ~2,500 lines preserves nothing git does not already keep. Two categories of pending work
shipped with it:

- **Strategy-specific** (`strategy/bollingerha/**`, `BollingerHaStateStore`, design doc) — kept as-is.
- **Shared execution hardening** (`AbstractTradeExecutionConsumer` stale-signal bypass,
  protective-stop re-placement, `ShoonyaTradeConsumer`/`ZerodhaTradeConsumer` MKT orders + gap-aware
  stop maintenance) — **benefits every strategy** (CAR, condor, LVR) and must not be reverted
  if the BOLLINGER_HA files are ever deleted.

Execution mode was, and remains, `PAPER` on both brokers (`SHOONYA_EXECUTION_MODE=PAPER`,
`ZERODHA_EXECUTION_MODE=PAPER`); no real orders were ever placed by this strategy.
