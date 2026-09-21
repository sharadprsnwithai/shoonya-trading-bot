# Strategy Spec: RSI Highway Multi-Timeframe Swing Strategy

Implementation-ready specification. 
Source: Harneet Singh (Arneet) — "RSI Highway Strategy" (Interview / System Framework).
Every ambiguity in the original has been resolved below as an explicit decision (marked **[DECISION]**). Treat decisions as configurable parameters in code.

**Target Engine / Broker:** Shoonya (Finvasia) API / Positional Swing Engine in `shoonya-trading-bot`.

---

## 1. Executive Summary & Core Concept

The **RSI Highway Strategy** is a top-down, multi-timeframe trend-following & momentum swing trading system designed for cash equity. It counters the traditional misconception that RSI > 60/70 is "overbought" (exit/short), instead treating high RSI on higher timeframes as evidence of institutional strength and multi-month momentum.

### The Highway Metaphor
- **Monthly Chart = The Highway:** Defines the macro structural trend. If the highway is smooth (Monthly RSI > 60), institutional momentum is active.
- **Weekly Chart = The Car:** Defines intermediate health/trend alignment. If the car is sound (Weekly RSI > 60), we have green light to drive.
- **Daily Chart = Refreshment Stop / Entry & Exit:** Minor dips and speed breakers. When the Daily RSI pulls back to ~50 (or crosses above 50) and bounces with bullish price action, we enter.
- **Trailing & Ride:** Positions are held as long as Daily RSI stays above 50, allowing swing trades to naturally turn into multi-bagger posistional runners (30% to 100%+ moves) while automating exits when momentum breaks.

---

## 2. Universe & Market Breadth Filter (Macro Gate)

### 2.1 Trading Universe
- **[DECISION] Universe:** Liquid Indian Equities — **Nifty 500** (or Nifty 200 + F&O Stocks list).
- **Segment:** Cash Equity (CNC / Delivery or MTF).
- **Penny Stock / Illiquidity Filter:**
  - Close Price ≥ ₹50.
  - 20-day Average Daily Volume (ADV) ≥ 100,000 shares.
  - 20-day Average Daily Turnover ≥ ₹5 Crore.

### 2.2 Market Breadth & Regime Gate
Trading momentum in a choppy/bearish market causes "death by a thousand cuts". The strategy enforces a macro market health gate evaluated prior to entering new trades:
- **[DECISION] 52-Week High Breadth Score:**
  - Active Scanning Universe: Count number of stocks in Nifty 500 making fresh 52-Week Highs over the trailing 5 trading sessions.
  - If `Count(52W_Highs) < MIN_MARKET_LEADERS` (Default: 5 stocks): Regime is **BEARISH / CHOPPY**. **No new long entries permitted.**
  - If `Count(52W_Highs) >= MIN_MARKET_LEADERS` (Default: 15+ stocks): Regime is **BULLISH / MOMENTUM**. Full trading allowed.
- **[DECISION] Index Drawdown Filter:**
  - If Nifty Smallcap 250 Index or Nifty Midcap 150 Index is **> 20% below its 52-Week High**, halt all new entries and stay in cash / manage existing runners only.

---

## 3. Multi-Timeframe Indicator Calculations

All indicators use standard Wilder's RSI with Period = 14.

| Timeframe | Indicator | Calculation / Resampling |
|---|---|---|
| **Monthly** | `Monthly RSI(14)` | Resampled from Daily EOD candles or fetched via Monthly OHLC |
| **Weekly** | `Weekly RSI(14)` | Resampled from Daily EOD candles (Friday close) |
| **Daily** | `Daily RSI(14)` | Computed on Daily EOD close prices |
| **Daily** | `Daily ATR(14)` | Computed for volatility buffer and sizing |

### Multi-Timeframe Alignment Criteria:
1. **Monthly Filter (Highway):** `Monthly RSI(14) >= 60.0`
2. **Weekly Filter (Car):** `Weekly RSI(14) >= 60.0`
3. **Daily Setup (Entry Window):** Daily RSI interacting with the **50 level**.

---

## 4. Setup & Entry Logic

A stock is eligible for entry on the **Daily Chart** when both Monthly and Weekly filters are satisfied (`Monthly RSI >= 60` AND `Weekly RSI >= 60`).

### 4.1 Entry Setups (Two Setup Variants)

#### Setup A: RSI 50 Pullback & Bounce (Preferred)
1. **Pullback:** Daily RSI(14) pulls back from higher levels into the **48.0 – 55.0 zone**.
2. **Bounce Confirmation:** Daily RSI turns upward (`RSI[t] > RSI[t-1]`), and `RSI[t] >= 50.0`.
3. **Price Action Confirmation:** A bullish candle forms on day `t` (see §5 for Candlestick definitions).

#### Setup B: RSI 50 Fresh Crossover
1. **Underneath to Above:** Daily RSI was `< 50.0` on day `t-1` or `t-2` and cleanly crosses above `50.0` on day `t` (`RSI[t] >= 50.5`).
2. **Price Action Confirmation:** Strong bullish breakout candle or rejection wick on day `t`.

### 4.2 Candlestick & Price Action Confirmation
**[DECISION]** The trigger candle (day `t`) must satisfy at least ONE of the following price action patterns:
1. **Bullish Engulfing:** `Close[t] > Open[t-1]` AND `Open[t] <= Close[t-1]` AND `Close[t] > Open[t]` (Green body engulfs prior red body).
2. **Hammer / Bullish Pinbar:** Lower shadow/wick ≥ `2.0 × Body Length`, and Upper wick ≤ `0.5 × Body Length`.
3. **Strong Momentum Green Candle (Marubozu/Expansion):** `Close[t] > Open[t]`, Candle Range (`High[t] - Low[t]`) ≥ `1.2 × Daily ATR(14)`, and Close in the top 25% of the daily range.
4. **Horizontal Consolidation Breakout:** `Close[t] > Max(High[t-1] .. High[t-5])`.

### 4.3 Order Execution & Trigger
- **Signal Candle:** Day `t` (completed daily candle).
- **Trigger Price:** `TriggerPrice = High[t] + 1 Tick` (₹0.05).
- **Execution Mode Options:**
  - **Option 1 (EOD / AMO Stop-Buy - Recommended):** At 15:15 - 15:25 on Day `t` if candle is closing strong, or place an AMO / Pre-market Stop-Buy order for Day `t+1` at `High[t] + 1 Tick`.
  - **Option 2 (Market on Confirmed Close):** Buy directly at 15:20 on Day `t` if Daily RSI >= 50 and bullish pattern is confirmed.
- **[DECISION] Order Timeout:** If price does not trigger above `High[t]` within **2 trading sessions**, the setup is invalidated and the resting order is cancelled.

---

## 5. Stop Loss & Invalidation Rules

### 5.1 Initial Stop Loss (SL)
When a new trade is filled, the initial stop loss is the maximum (tighter) of:
1. **Price Action SL:** `Low of Signal Candle[t] - 1 Tick` (or Swing Low of the last 5 candles).
2. **Maximum Permissible Risk SL:** `Entry Price × (1 - MAX_SL_PCT)` where `MAX_SL_PCT = 8.0%` (guards against abnormally wide trigger candles).

### 5.2 Dynamic & Daily RSI Exit Rule (The Core Trailing Engine)
The strategy does NOT use arbitrary fixed profit targets (1:1 or 1:2). It trails until momentum dies.
- **[DECISION] EOD RSI Exit:** Evaluated daily at **15:15 – 15:25 IST**.
  - If `Daily RSI(14) Close < 50.0`: **Exit 100% of position at Market.**
- **[DECISION] Morning Emergency Circuit-Breaker (09:30 – 09:45 IST):**
  - If a stock gaps down heavily on bad news/earnings and the live Daily RSI plunges below **45.0** during the 09:30–09:45 check window, trigger an immediate market exit to protect capital from catastrophic gap-downs.

---

## 6. Pyramiding / Multi-Entry Rules

The strategy actively encourages adding to winning positions (pyramiding) rather than taking small profits.

- **[DECISION] Max Tranches:** Maximum **3 entries (1 Initial + 2 Additions)** per stock.
- **Pyramid Trigger Conditions:**
  - Stock must already be in profit (`Current Price > Initial Entry Price`).
  - Monthly RSI and Weekly RSI must still be `> 60.0`.
  - Daily RSI experiences a fresh pullback to ~50 and delivers a new bounce / breakout candle.
- **Pyramid Allocation:**
  - Tranche 1 (Base): 100% of standard unit size.
  - Tranche 2 (Add 1): 50% of standard unit size.
  - Tranche 3 (Add 2): 25% of standard unit size (Inverted pyramid to keep average price low).
- **Consolidated Trailing SL:** Once a pyramid tranche is added, **ALL tranches share the same exit rule** — Daily RSI closing `< 50.0` or Swing Low breach.

---

## 7. Position Sizing & Portfolio Risk Management

- **Portfolio Capital Allocation:**
  - Maximum Open Positions: `10` concurrent stocks (10% max allocation per stock).
  - Risk Per Trade (RPT): `1.0%` of total trading account equity.
- **Position Sizing Formula:**
  $$\text{Shares to Buy} = \min \left( \frac{\text{Account Capital} \times \text{RPT\%}}{\text{Entry Price} - \text{Stop Loss Price}}, \frac{\text{Account Capital} \times \text{Max Capital Per Stock}}{\text{Entry Price}} \right)$$
- **Max Portfolio Exposure:** Up to 100% cash allocation. No leverage or margin borrowing required.

---

## 8. Operating Routine & Time Schedule

To eliminate screen addiction and psychological errors, the bot operates on a strict schedule:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          DAILY BOT SCHEDULE                             │
├─────────────────┬───────────────────────────────────────────────────────┤
│ 09:15 - 09:30   │ NO TRADING. Initial volatility cooldown.              │
├─────────────────┼───────────────────────────────────────────────────────┤
│ 09:30 - 09:45   │ MORNING HEALTH CHECK:                                 │
│                 │ 1. Scan open positions for emergency plunge/gap-down  │
│                 │ 2. Verify resting GTT/SL/AMO orders on Shoonya        │
├─────────────────┼───────────────────────────────────────────────────────┤
│ 09:45 - 15:00   │ BOT IDLE / ZERO SCREEN TIME (No active polling)       │
├─────────────────┼───────────────────────────────────────────────────────┤
│ 15:00 - 15:25   │ AFTERNOON EXECUTION & SCAN:                           │
│                 │ 1. Evaluate EOD Daily RSI on open positions (<50 exit)│
│                 │ 2. Execute exits on confirmed violations              │
│                 │ 3. Check Market Breadth Gate (52W High count)         │
│                 │ 4. Scan Universe for Monthly>60, Weekly>60, Daily~50  │
│                 │ 5. Generate Buy Signals & place EOD/AMO orders        │
├─────────────────┼───────────────────────────────────────────────────────┤
│ 18:00 (Post-Mkt)│ EOD Reporting, Telegram summaries, Database sync      │
└─────────────────┴───────────────────────────────────────────────────────┘
```

---

## 9. State Machine Model (Per Stock)

```
                     ┌────────────────────────┐
                     │          IDLE          │
                     └───────────┬────────────┘
                                 │
                                 ▼
                     ┌────────────────────────┐
                     │   WATCHLIST_MONITOR    │ ◄── Monthly RSI >= 60 & Weekly RSI >= 60
                     └───────────┬────────────┘
                                 │
                                 ▼
                     ┌────────────────────────┐
                     │   DAILY_SETUP_ARMED    │ ◄── Daily RSI Pullback (48-55) or Cross > 50
                     └───────────┬────────────┘     + Bullish Price Action Candle
                                 │
                                 ▼
                     ┌────────────────────────┐
                     │    ORDER_SUBMITTED     │ ◄── Stop-Buy at Candle High + 1 tick
                     └───────────┬────────────┘
                                 │
                   Triggered? ───┴───► No (2 days) ──► Cancel Order & Return to IDLE
                                 │ Yes
                                 ▼
                     ┌────────────────────────┐
                     │     POSITION_OPEN      │ ◄── Tranche 1 Filled (Initial SL set)
                     └───────────┬────────────┘
                                 │
       ┌─────────────────────────┴─────────────────────────┐
       │                                                   │
       ▼                                                   ▼
┌───────────────┐                                 ┌──────────────────┐
│ PYRAMID_ARMED │ ◄── In profit & new RSI bounce  │ TRAILING_MONITOR │
└──────┬────────┘                                 └────────┬─────────┘
       │ Tranche 2/3 Filled                                │
       ▼                                                   │
┌────────────────────────┐                                 │
│  POSITION_PYRAMIDED    │                                 │
└───────────┬────────────┘                                 │
            │                                              │
            └──────────────────────┬───────────────────────┘
                                   │ Daily RSI < 50 Close OR Emergency SL
                                   ▼
                     ┌────────────────────────┐
                     │     EXIT_PENDING       │
                     └───────────┬────────────┘
                                 │
                                 ▼
                     ┌────────────────────────┐
                     │     POSITION_CLOSED    │
                     └────────────────────────┘
```

---

## 10. Data Structures & Class Design (Spring Boot / Kotlin / Java)

### 10.1 Model Definitions

```java
public enum RsiHighwayState {
    IDLE,
    WATCHLIST_QUALIFIED,
    SETUP_ARMED,
    ORDER_PENDING,
    POSITION_ACTIVE,
    PYRAMID_ARMED,
    EXIT_TRIGGERED,
    CLOSED
}

public record MultiTimeframeRsiSnapshot(
    String symbol,
    double monthlyRsi,
    double weeklyRsi,
    double dailyRsi,
    double dailyAtr,
    double currentPrice,
    double signalCandleHigh,
    double signalCandleLow,
    boolean isBullishPattern,
    Instant timestamp
) {}

public record RsiHighwayTrade(
    String tradeId,
    String symbol,
    int trancheNumber, // 1, 2, or 3
    double entryPrice,
    int quantity,
    double initialSlPrice,
    double currentSlPrice,
    double investedAmount,
    Instant entryTime,
    Instant exitTime,
    Double exitPrice,
    String exitReason
) {}
```

### 10.2 Service Architecture

1. **`RsiHighwayMarketBreadthService`**:
   - Computes daily 52-week highs and index drawdowns.
   - Sets market state: `BULLISH_TRENDING`, `NEUTRAL`, `BEARISH_SIDEWAYS`.
2. **`RsiHighwayScannerService`**:
   - Evaluates Universe (Nifty 500) using Shoonya historical candles.
   - Computes Monthly RSI(14), Weekly RSI(14), and Daily RSI(14).
   - Filters candidate list.
3. **`RsiHighwaySignalService`**:
   - Detects RSI 50 bounce/cross + candlestick confirmation (Engulfing, Hammer, Expansion).
   - Generates entry and pyramid orders.
4. **`RsiHighwayExecutionService`**:
   - Submits Shoonya CNC / Delivery orders.
   - Manages trailing exits on Daily RSI < 50 breach.
5. **`RsiHighwayScheduler`**:
   - Morning cron: `0 30 9 * * MON-FRI`
   - Afternoon cron: `0 15 15 * * MON-FRI`

---

## 11. Edge Cases & Resilience Strategy

| Edge Case | Failure Mode | Mitigation Rule |
|---|---|---|
| **Stock hits Upper Circuit on breakout** | Order rejected or slippage | Do not chase > 2% beyond trigger price. If circuit locked, cancel order. |
| **Severe Gap Down on Negative Earnings** | Large loss beyond initial SL | Morning 09:30 check detects Daily RSI < 45 and executes market exit immediately. |
| **Choppy Whipsaws around RSI 50** | Frequent false entries | Price Action filter mandates bullish candle close + trigger above signal candle High. |
| **Shoonya API Downtime during 15:15 scan** | Missed EOD exit/entry | Automatic 3-stage retry with exponential backoff; alert via Telegram Bot. |
| **Stock Split / Bonus / Dividend** | RSI distortion on unadjusted series | Use adjusted historical series or recalculate RSI upon corporate action. |

---

## 12. Verification & Backtest Metrics

To validate the implementation against the strategy thesis:
- **Win Rate:** Expected 40% – 50% (Trend-following).
- **Profit Factor:** Expected ≥ 2.5 (Driven by large right-tail runners).
- **Average Win to Average Loss (Risk-Reward Realized):** ≥ 3.5 : 1.
- **Max Drawdown:** < 15% (Protected by Market Breadth Filter and RSI 50 cutoffs).
- **Holding Period:** 2 weeks to 6 months per runner.
