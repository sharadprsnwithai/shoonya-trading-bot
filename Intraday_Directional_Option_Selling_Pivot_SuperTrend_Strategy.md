# Strategy Specification: Intraday Directional Option Selling (Daily Pivot Points + SuperTrend)

## 1. Executive Summary & Objective

This is a systematic, intraday directional **Option Selling** strategy designed to capture directional momentum ($\Delta$) and accelerated time decay ($\theta$) on Indian indices (**NIFTY 50** and **SENSEX**):

1. **Level Filter (Standard Daily Pivot Points):** Uses previous session's High ($H_{prev}$), Low ($L_{prev}$), and Close ($C_{prev}$) to establish breakout thresholds $R1$ (Resistance 1) and $S1$ (Support 1).
2. **Momentum Indicator (SuperTrend 7, 3):** Uses a fast SuperTrend (Period = 7, Multiplier = 3.0) for early trend confirmation and trailing exits.
3. **Execution Mode (Directional Option Selling):**
   - **Bullish Confluence:** Sells ATM Put (PE) option on candle close.
   - **Bearish Confluence:** Sells ATM Call (CE) option on candle close.
4. **Exit Rules:**
   - **Trailing Stop / Signal Exit:** Liquidate position instantly when SuperTrend (7, 3) flips direction.
   - **Time Exit:** 15:15 – 15:20 IST mandatory intraday square-off.
   - **Daily Trade Cap:** Maximum 3 trades per day.

---

## 2. Core Parameters & Indicators

| Parameter | Setting | Description |
| --- | --- | --- |
| **Underlying Instrument** | NIFTY 50 / SENSEX (Futures / Index) | Reference underlying for technical triggers |
| **Timeframe** | 5-Minute (5m) | Bar timeframe for indicator evaluation |
| **Indicator 1: Pivot Points** | Standard Daily Pivot Points | $P = \frac{H+L+C}{3}$, $R1 = 2P - L$, $S1 = 2P - H$ |
| **Indicator 2: SuperTrend** | SuperTrend (7, 3.0) | Fast ATR lookback = 7, Multiplier = 3.0 |
| **Options Traded** | Current Weekly Expiry ATM Strike | Nearest ATM strike at the time of entry |
| **Trading Style** | Naked Short (Directional Option Selling) | Short ATM PE (Bullish), Short ATM CE (Bearish) |
| **Daily Trade Cap** | Max 3 trades per day | Disables new entries once 3 trades are filled |
| **Cutoff & Square-Off** | 15:15 – 15:20 IST | Hard end-of-day liquidation |

---

## 3. Algorithmic Trading Rules & Lifecycle

```text
                     ┌───────────────────────────┐
                     │ 09:15 IST - Market Open   │
                     │ Compute Daily Pivot,R1,S1 │
                     └─────────────┬─────────────┘
                                   │
                     ┌─────────────▼─────────────┐
                     │ Incoming 5-Minute Candle  │
                     │  Evaluate on Bar Close    │
                     └─────────────┬─────────────┘
                                   │
              ┌────────────────────┴────────────────────┐
              │                                         │
 ┌────────────▼────────────┐               ┌────────────▼────────────┐
 │     BULLISH ENTRY       │               │      BEARISH ENTRY      │
 │  Close > SuperTrend(7,3)│               │  Close < SuperTrend(7,3)│
 │          AND            │               │          AND            │
 │       Close > R1        │               │       Close < S1        │
 └────────────┬────────────┘               └────────────┬────────────┘
              │                                         │
 ┌────────────▼────────────┐               ┌────────────▼────────────┐
 │ Instantly SELL 1 Lot    │               │ Instantly SELL 1 Lot    │
 │ ATM Put (PE) Option     │               │ ATM Call (CE) Option    │
 └────────────┬────────────┘               └────────────┬────────────┘
              │                                         │
              └────────────────────┬────────────────────┘
                                   │
                      ┌────────────▼────────────┐
                      │   Exit Condition Check  │
                      │ 1. SuperTrend (7,3) Flip│
                      │    (PE: Green -> Red)   │
                      │    (CE: Red -> Green)   │
                      │ 2. Time: 15:15-15:20 IST│
                      │ 3. Max 3 trades / day   │
                      └─────────────────────────┘
```

### 1. Daily Pivot Point Calculation (Computed at 09:15 IST)

From previous day's daily candle ($H_{prev}, L_{prev}, C_{prev}$):
$$\text{Pivot } (P) = \frac{H_{prev} + L_{prev} + C_{prev}}{3}$$
$$R1 = 2 \times P - L_{prev}$$
$$S1 = 2 \times P - H_{prev}$$

### 2. Long Directional Bias (Bullish Entry: Short ATM PE)

- **Trigger Condition:** Evaluated on the close of any 5-minute candle:
  $$\text{Close} > \text{SuperTrend}(7, 3) \quad \text{AND} \quad \text{Close} > R1$$
- **Action:** Instantly sell 1 lot of the nearest ATM Put (PE) option at market price upon candle close. No breakout wait needed.

### 3. Short Directional Bias (Bearish Entry: Short ATM CE)

- **Trigger Condition:** Evaluated on the close of any 5-minute candle:
  $$\text{Close} < \text{SuperTrend}(7, 3) \quad \text{AND} \quad \text{Close} < S1$$
- **Action:** Instantly sell 1 lot of the nearest ATM Call (CE) option at market price upon candle close. No breakdown wait needed.

### 4. Exit & Risk Management

- **SuperTrend Flip Signal Exit:**
  - For Short PE: Exit immediately when SuperTrend (7, 3) flips from Bullish (Green) to Bearish (Red).
  - For Short CE: Exit immediately when SuperTrend (7, 3) flips from Bearish (Red) to Bullish (Green).
- **Time Stop:** Liquidate all open positions at 15:15 – 15:20 IST.
- **Daily Trade Cap:** Stop taking new entries once 3 trades are completed in a single session.
