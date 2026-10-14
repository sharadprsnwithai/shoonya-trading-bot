# Silver ORB-Bias + Day-VWAP-Cross — Live Paper-Trading Spec

Status: **PAPER ONLY — forward validation, no real capital until the acceptance criteria below are met.**
Last updated: 2026-10-14.

## 1. Why this variant

Every rule below is the single configuration that stayed positive across all
four datasets tested (real MCX silver and the SI=F proxy, at 5m and 1h, at
every margin setting):

| Backtest (7% risk / 25% target of margin @ 10%) | Trades | Win % | Net R | Profit factor | Max DD |
|---|---|---|---|---|---|
| **Real MCX SILVER04DEC26, 5m, 67 trading days (Jul 13 – Oct 14)** | 34 | 67.6 | **+10.88** | **2.17** | −1.74% |
| Real MCX, same data resampled to 1h | 20 | 55.0 | +2.53 | 1.53 | — |
| yfinance SI=F, 5m, 60 days | 20 | 55.0 | +8.84 | 2.17 | −2.97% |
| yfinance SI=F, 1h, 3 months | 18 | 66.7 | +5.56 | 1.93 | — |
| *Same MCX data, raw breakout entry (rejected baseline)* | *63* | *44.4* | *−0.11* | *1.00* | *−8.37%* |
| *Session-anchored VWAP (18:00) — rejected* | *40* | *45.0* | *+0.91* | *1.07* | *−7.53%* |

The VWAP-confirmation filter with a **calendar-day VWAP anchor** is the edge;
the raw breakout and the session-anchored VWAP are not.

## 2. Instrument

- **MCX SILVER futures, main contract (30 kg, quoted ₹/kg)** — e.g. `SILVER04DEC26`.
  Roll to the next expiry once volume migrates (rule of thumb: 3–5 trading days
  before the 4th/20th-of-month expiry; confirm on the quote page which contract
  carries volume).
- **SILVERM (5 kg mini)** is an acceptable size substitute — identical rules,
  same underlying path, smaller notional.
- Day-session evening block only; all times IST.

## 3. Definitions

- **ORB window:** the 5m bars opening **18:00, 18:05 … 18:25** (the 18:00–18:30
  range). `ORB high` = max high of those bars; `ORB low` = min low.
  - Require ≥ 4 bars in the window; if missing, **skip the day**.
  - Skip the day if `(ORB high − ORB low) / ORB low < 0.05%` (dead range).
- **Day VWAP:** anchored at **00:00 IST (calendar day)**, recomputed on every 5m
  close: `Σ(typical price × volume) / Σ(volume)` where
  `typical = (high + low + close) / 3`, over bars of the current IST date from
  00:00 onward (zero-volume bars weighted 1). This is the *only* VWAP anchor
  that validated; do not substitute session-VWAP.
- **Bias (from ORB):**
  - First 5m close **above ORB high** after 18:30 → LONG bias.
  - First 5m close **below ORB low** → SHORT bias.
  - Bias **persists** until a close beyond the opposite boundary flips it.
  - No close beyond either boundary → **no trade that day**.
- **Entry trigger:** a 5m candle **crossing the day VWAP in the bias direction**:
  - LONG: previous close ≤ previous VWAP **and** this close > this VWAP.
  - SHORT: previous close ≥ previous VWAP **and** this close < this VWAP.
  - The bias-setting candle may itself be the crossing candle.
  - Entries only on candles **opening before 21:00**.
- **One trade per day.** Once exited, no re-entry. No position carried past 23:00.

## 4. Risk (the 7 / 25 unit — confirmed interpretation)

Percentages are **of margin deployed**, default margin assumption **10%**
(leverage 10×) → price distances:

| Level | % of margin | % of price | Example at ₹2,20,000/kg |
|---|---|---|---|
| Stop | −7% | −0.70% | exit 218,460 (loss = 7% of margin) |
| Target | +25% | +2.50% | exit 225,500 (gain = 25% of margin) |

- If your broker's margin differs, rescale: `price distance = capital % × margin %`
  (e.g., 12% margin → SL 0.84% / target 3.00% of price).
- **Same-candle ambiguity: stop wins** (conservative, matches backtest).
- **Square-off:** flat by 23:00 — exit at the close of the first 5m bar opening
  at or after 23:00.
- Breakeven win rate for 7:25 = **21.9%**.

## 5. Sizing

- Risk **1% of capital per trade** (backtest equity convention).
- Lots = `floor(1% of capital / (0.007 × price × 30 kg))`, minimum 1 lot.
  At ₹2,20,000/kg one SILVER lot (₹6.6 L notional) stops out at ≈ ₹4,620 —
  a ₹5,00,000 paper capital therefore trades 1 lot (0.92% risk).

## 6. Daily procedure

1. **17:55** — confirm active contract, note overnight/USD-INR context (context only; no discretion in entries).
2. **18:30** — compute ORB high/low from the six 18:00–18:25 bars; write both into the log. Apply the day's validity checks (≥4 bars, range ≥0.05%).
3. **18:30 → 21:00** — watch closes only (no intrabar entries). Track bias flips and the day VWAP.
4. **On trigger** — record entry bar time, price, side, computed stop and target. Place both (or simulate mentally in paper mode).
5. **Manage** — stop/target/square-off per §4; log the exit.
6. **EOD** — append R-multiple and running equity; note days with bias-but-no-cross (filtered days are part of the strategy, not misses).

### Log columns
`date | ORB low | ORB high | bias set (time/price) | entry (time/price/side) | stop | target | exit (time/price/reason) | price % | R | equity R | notes`

## 7. Acceptance criteria (before any real capital)

- **Minimum 20 paper trades** (expect ≈ 1 every 2 days; ~6 weeks).
- **Go:** net R > 0 **and** profit factor ≥ 1.3 over those ≥20 trades, with no
  single rule being overridden manually even once.
- **Pause & review:** drawdown worse than −5R, or PF < 1.0 at 20 trades →
  re-examine on extended data before continuing.
- Also confirm live fills behave like the model: spread ≤ ~3 ticks entering,
  square-off fills near the bar close.

## 8. Known caveats (read before trusting the numbers)

- **34-trade backtest sample** is small; multiple configurations were tested
  (selection bias), mitigated by replication across two datasets × two
  granularities × four margin settings — but this is *promising*, not proven.
- One market window (Jul–Oct 2026: rally to ₹2.54 L, slide to ₹2.19 L) — two
  trends, still one regime pair.
- Backtest ran on **SILVER04DEC26** for the whole window; in July the liquid
  contract was the then-front month — far-month 5m bars can be slightly gappier
  than what you will trade live.
- **Costs not modeled:** spread, MCX impact, overnight MTM on the margin (all
  exits are intraday, so no carry risk), STT/stamp/GST on futures turnover.
- Options version of this strategy was tested and **rejected** (−8.58R,
  PF 0.50 — premium drag); do not substitute option buying.

## 9. Tooling in this repo

| Tool | Purpose |
|---|---|
| `scripts/shoonya_mcx_pull.py --days 95 --token 495214` | Pull real MCX 5m history to `data/mcx_silver_5m_95d.csv` (works without MCX segment search; after MCX activation `--discover` lists contracts for stitching longer histories) |
| `scripts/silver_orb_vwap_futures_backtest.py [csv]` | The strategy under test (Runs A/B, both VWAP anchors, margin sweep) |
| `scripts/silver_orb_futures_backtest.py [csv]` | Rejected raw-breakout baseline |

Refresh the backtest weekly by re-pulling (`--days N`) and re-checking §7;
re-run with `MARGIN_PCT` set to your actual broker margin.
