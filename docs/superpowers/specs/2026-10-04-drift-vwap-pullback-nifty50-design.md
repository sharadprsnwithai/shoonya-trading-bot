# Strategy Spec: Drift VWAP Pullback Strategy (NIFTY 50 Index Edition)

## 1. Overview & Institutional Edge

Based on the institutional execution framework by **Matio Kanti** (former market maker at Nordea Markets & CIO at SQR Capital), this strategy exploits institutional order execution microstructure around the Volume Weighted Average Price (VWAP).

### Core Edge
* Institutional portfolio managers execute 90–95% of large orders through **VWAP execution algorithms**.
* When a strong directional momentum drift establishes itself in the market and price pulls back towards the VWAP, institutional execution algorithms intensify their participation.
* The order imbalance between aggressive institutional buyers and sellers exposes itself, triggering sharp, high-probability continuation bounces.
* **Target Profile:** High win rate (60%–65%), asymmetric probability with fixed stop-loss/take-profit, single-day intraday holding, strict 2-loss daily circuit breaker.

---

## 2. Market Timing (IST — Indian Standard Time)

| Time Window | Phase | Action / Rules |
|---|---|---|
| **09:15 – 10:15** | **Settlement Window (First 1 Hour)** | **No Trading.** VWAP anchor initializes at 09:15 market open; allows institutional volume profile and baseline drift to stabilize. |
| **10:15 – 14:55** | **Active Trading Window** | Active 15m drift evaluation and 5m pullback trigger execution. |
| **14:55** | **Entry Cutoff** | **No new trades allowed after 14:55 IST.** |
| **15:10** | **Hard EOD Exit** | **Square off all open positions at market price.** |

---

## 3. Mathematical Strategy Rules (NIFTY 50)

### 3.1 Timeframes & Anchor
* **Macro Drift Timeframe:** 15-minute OHLCV chart (`Timeframe = 15m`).
* **Trigger & Execution Timeframe:** 5-minute OHLCV chart (`Timeframe = 5m`).
* **VWAP Anchor:** Daily Session Open at **09:15 IST**, computed on 15-minute candles:
  $$\text{Typical Price}(t) = \frac{High(t) + Low(t) + Close(t)}{3}$$
  $$VWAP_{15m}(t) = \frac{\sum (\text{Typical Price} \times Volume)}{\sum Volume}$$

---

### 3.2 Trend / Drift Filter (Evaluated on 15-Minute Chart)
Before any trade is considered, **all 3 conditions** must hold simultaneously on the closed 15-minute bar:

#### LONG Setup (Bullish Drift):
1. **Price Level:** $Close_{15m}(t) > VWAP_{15m}(t)$ (Price above 15m VWAP).
2. **VWAP Direction:** $VWAP_{15m}(t) > VWAP_{15m}(t - 1)$ (15m VWAP is rising).
3. **1-Hour Momentum Speed:** NIFTY 50 price increased by at least **$+0.10\%$** over the last 4 fifteen-minute bars (1 hour):
   $$\Delta P_{1hr} = \frac{Close_{15m}(t) - Close_{15m}(t - 4)}{Close_{15m}(t - 4)} \ge +0.10\%$$

#### SHORT Setup (Bearish Drift):
1. **Price Level:** $Close_{15m}(t) < VWAP_{15m}(t)$ (Price below 15m VWAP).
2. **VWAP Direction:** $VWAP_{15m}(t) < VWAP_{15m}(t - 1)$ (15m VWAP is falling).
3. **1-Hour Momentum Speed:** NIFTY 50 price decreased by at least **$-0.10\%$** over the last 4 fifteen-minute bars (1 hour):
   $$\Delta P_{1hr} = \frac{Close_{15m}(t) - Close_{15m}(t - 4)}{Close_{15m}(t - 4)} \le -0.10\%$$

---

### 3.3 Entry Trigger (5-Minute Chart)
Once the 15-minute drift conditions are met:
* **LONG Trigger:** Wait for the **first RED (bearish: $Close < Open$) 5-minute candle** pulling back towards VWAP.
  * **Entry Execution:** Enter a **Market Buy order at the Open of the very next 5-minute candle** (as soon as the red pullback candle closes).
* **SHORT Trigger:** Wait for the **first GREEN (bullish: $Close > Open$) 5-minute candle** pulling back towards VWAP.
  * **Entry Execution:** Enter a **Market Sell order at the Open of the very next 5-minute candle** (as soon as the green pullback candle closes).

---

### 3.4 Risk Management & Profit Targets (NIFTY 50 Index Points)

| Direction | Stop Loss (SL) | Take Profit (TP / Target) | Risk-to-Reward Ratio |
|---|---|---|---|
| **LONG** | **-50 points** below entry | **+25 points** above entry | $0.50 : 1$ |
| **SHORT** | **-50 points** above entry | **+30 points** below entry | $0.60 : 1$ |

*(Note: In NQ futures trading at ~20,000, Matio Kanti used 40/80 and 50/80 points. For NIFTY 50 trading at ~25,000, 25/50 and 30/50 points represent the equivalent 0.10% / 0.20% index moves).*

---

### 3.5 System Guardrails
1. **Max Concurrent Positions:** Maximum **1 open trade** at any time.
2. **Max Daily Trades:** Maximum **4 trades per day**.
3. **Daily Loss Circuit Breaker:** Maximum **2 losses per day** (if 2 losing trades occur on the same day, stop trading for the remainder of the session).
4. **Hard Exit:** Flatten any open position at **15:10 IST** market order.

---

## 4. Determinism & Verification

* **Determinism:** 100% mathematical, zero discretion.
* **Test Strategy:** Backtest against historical 5m & 15m OHLCV data for NIFTY 50 Index over the last 1 month in Python.
