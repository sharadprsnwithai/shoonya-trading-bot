# RSI (52/32) Monthly Credit Spread Strategy Design Specification

**Document ID:** `2026-09-30-rsi-52-32-monthly-credit-spread-design`  
**Strategy Name:** `RSI 52/32 Monthly Credit Spread`  
**Target Instrument:** `NIFTY 50 Monthly Options (OPTIDX)`  
**Timeframe:** `1-Hour (60-minute) Candles`  
**Status:** `Approved for Implementation Planning`

---

## 1. Overview & Strategy Principles

The **RSI 52/32 Strategy** is a high-probability, defined-risk monthly option credit spread strategy engineered for Nifty 50. It captures steady Theta time decay and directional momentum swings while strictly limiting portfolio drawdowns through mandatory OTM hedge legs.

### Core Architecture:
- **Indicator:** Standard RSI(14) evaluated strictly on 1-hour candle closes.
- **Hourly Schedule with 20s Buffer:** Runs 6 times a day at **10:15:20, 11:15:20, 12:15:20, 13:15:20, 14:15:20, and 15:15:20 IST** (allowing a 20-second buffer for broker candle aggregation).
- **Rollover Handler:** Runs at **15:00:20 IST** on the **2nd-to-last Wednesday** before monthly expiry.
- **Pure Defined-Risk Spreads:**
  - **Bear Call Spread:** Sell OTM Call + Buy further OTM Call (when $\text{RSI} < 32$).
  - **Bull Put Spread:** Sell OTM Put + Buy further OTM Put (when $\text{RSI} > 52$).
- **Fixed Lots:** Configured to a default of **2 lots** (50 quantity) with configurable multiplier support.

---

## 2. Signal Generation & State Machine

```
                   ┌────────────────────────────────────────┐
                   │             START / BOOT               │
                   └──────────────────┬─────────────────────┘
                                      │
                         [Check RSI on 1h Candle]
                                      │
                 ┌────────────────────┴────────────────────┐
                 │                                         │
          RSI < 32 (Bearish)                        RSI > 52 (Bullish)
                 │                                         │
                 ▼                                         ▼
     ┌──────────────────────┐                  ┌──────────────────────┐
     │  BEAR CALL SPREAD    │                  │   BULL PUT SPREAD    │
     │  • Buy Farthest Call │                  │  • Buy Farthest Put  │
     │  • Sell OTM Call     │                  │  • Sell OTM Put      │
     └──────────┬───────────┘                  └──────────┬───────────┘
                │                                         │
          [Hold Spread]                             [Hold Spread]
    (While 32 <= RSI <= 52)                   (While 32 <= RSI <= 52)
                │                                         │
          If RSI > 52                               If RSI < 32
                │                                         │
                ▼                                         ▼
    Square-off Call Spread                    Square-off Put Spread
       & Reverse to Put Spread                   & Reverse to Call Spread
```

### State Definitions:
1. **`BEAR_CALL_SPREAD`**:
   - Entered when 1h candle closes with $\text{RSI} < 32$.
   - Short Call + Long Call Hedge.
2. **`BULL_PUT_SPREAD`**:
   - Entered when 1h candle closes with $\text{RSI} > 52$.
   - Short Put + Long Put Hedge.
3. **`FLAT`**:
   - Initial state before any signal trigger.

---

## 3. Strike Selection Algorithm

When a new spread or reversal is triggered, `Rsi5232StrikeSelector` filters option chain candidates:

1. **Expiry Determination:**
   - If current day of month $\le 15$: Target **Current Month Expiry**. If no strikes satisfy credit criteria, fallback to **Next Month Expiry**.
   - If current day of month $> 15$: Target **Next Month Expiry**.
2. **Strike Multiple Preference:**
   - Multiples of **100** are strongly preferred for liquidity (e.g. $24800, 24900, 25000$). Multiples of $50$ are used only as fallback.
3. **Spread Width Constraint:**
   $$\text{Spread Width} = |\text{Strike}_{\text{Buy}} - \text{Strike}_{\text{Sell}}| \le 2.5\% \times \text{Spot} \quad (\approx 400 \text{ to } 600 \text{ pts})$$
4. **Net Credit Range:**
   $$\text{Net Credit} = (\text{Premium}_{\text{Sell}} - \text{Premium}_{\text{Buy}}) \in [90.0, \; 140.0] \text{ points}$$
5. **Selection Objective:**
   - Select the candidate pair meeting (1)–(4) that **maximizes distance from Spot**:
     $$\text{Maximize } |\text{Strike}_{\text{Sell}} - \text{Spot}|$$

---

## 4. Pre-Expiry Roll-Over Engine

To eliminate gamma explosions during the final expiration week:
- **Trigger Date:** **2nd-to-last Wednesday** before the monthly expiry.
- **Execution Time:** **15:00:20 IST**.
- **Action:**
  1. Check if the active spread's expiry matches the expiring month.
  2. If active, square off both legs (sell hedge, buy back short leg).
  3. Re-open the same directional spread in the **Next Month Expiry** at current market prices.

---

## 5. Execution & Safety Protocol

1. **Broker Leg Sequencing (Crucial for Margin Efficiency):**
   - **Entry:** Always execute **BUY Hedge Leg FIRST**, then **SELL Short Leg SECOND** (prevents broker rejection due to unhedged margin requirement).
   - **Exit:** Always execute **BUY to cover Short Leg FIRST**, then **SELL Hedge Leg SECOND**.
2. **Persistence:**
   - Persist strategy state to `data/rsi5232_state.json` on every state transition.

---

## 6. Backtest Performance & CAGR Analysis

Evaluated over **5,050 1-Hour Nifty Candles** (Oct 2023 – Sep 2026, 2.94 years):

| Metric | Performance (2 Lots / 50 Qty) | Performance (10 Lots / 250 Qty) |
| :--- | :--- | :--- |
| **Total Trades** | 125 trades (~3.5 trades/mo) | 125 trades (~3.5 trades/mo) |
| **Win Rate** | **52.8%** | **52.8%** |
| **Profit Factor** | **1.36** | **1.36** |
| **Points Captured** | **+1,037.8 points** (+353.0 pts/yr) | **+1,037.8 points** (+353.0 pts/yr) |
| **Total Net P&L** | **₹51,888.18** | **₹2,59,440.90** |
| **Max Drawdown** | **-488.4 pts (₹24,418.86)** | **-488.4 pts (₹1,22,094.30)** |
| **CAGR (Margin: ₹1.5L / ₹7.0L)** | **10.64% CAGR** | **11.45% CAGR** |
| **CAGR (Conservative: ₹2.0L / ₹10.0L)**| **8.17% CAGR** | **8.17% CAGR** |

---

## 7. REST API Endpoints

- `GET /api/v1/strategy/rsi5232/status` — Strategy status, active direction, RSI, current MTM P&L.
- `GET /api/v1/strategy/rsi5232/position` — Active spread position details (short/hedge strikes, expiry, rollover date).
- `GET /api/v1/strategy/rsi5232/history` — Closed trade history.
- `POST /api/v1/strategy/rsi5232/evaluate` — Force evaluate current 1h candle.
- `POST /api/v1/strategy/rsi5232/rollover` — Force execute monthly rollover.
- `POST /api/v1/strategy/rsi5232/reset` — Reset state machine.
