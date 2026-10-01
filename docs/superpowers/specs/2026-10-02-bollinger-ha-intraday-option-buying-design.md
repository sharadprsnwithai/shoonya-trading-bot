# Design Spec: Intraday 1-Minute Bollinger Bands & Heikin-Ashi Option Buying Strategy

**Author**: Antigravity  
**Date**: 2026-10-02  
**Status**: DRAFT / PENDING REVIEW  
**Target Package**: `com.tradingbot.strategy.bollingerha`

---

## 1. Overview & Strategy Intent

This subsystem automates Kushal Varshney's **1-Minute Bollinger Bands (20, 2) & Heikin-Ashi Intraday Option Buying Strategy** for **NIFTY 50 Weekly Options**.

### Core Philosophy
* **Capture High-Probability Morning Momentum**: Capitalize on opening volatility during the first 10–75 minutes of the trading day (09:15 AM – 10:30 AM IST).
* **Strict Risk Asymmetry**: Risk ₹5–₹15 per trade (capped at max ₹20) with a 1:2 Risk-to-Reward ratio on the first half and unlimited runner upside on the second half with Cost SL (CSL).
* **Disciplined Trade Cap**: Strictly limited to a maximum of **2 trades per day**.

---

## 2. Quantitative & Mathematical Specifications

### 2.1 Strike Selection (09:07 AM – 09:14 AM IST)
* Pre-market close of Nifty 50 Spot is sampled at 09:07 AM.
* **ATM Strike Calculation**:
  $$\text{ATM Strike} = \text{round}\left(\frac{\text{Nifty Spot Price}}{50}\right) \times 50$$
* Current weekly expiry Call (`NIFTY <EXPIRY> <ATM> CE`) and Put (`NIFTY <EXPIRY> <ATM> PE`) instrument tokens are resolved and tracked simultaneously.

### 2.2 Candle Transformation & Indicator Calculation
* **Timeframe**: 1-Minute (`1m`).
* **Heikin-Ashi (HA) Transformation**:
  For candle at time $t$:
  $$HA\_Close_t = \frac{Open_t + High_t + Low_t + Close_t}{4}$$
  $$HA\_Open_t = \begin{cases} \frac{Open_0 + Close_0}{2} & t = 0 \\ \frac{HA\_Open_{t-1} + HA\_Close_{t-1}}{2} & t > 0 \end{cases}$$
  $$HA\_High_t = \max(High_t, HA\_Open_t, HA\_Close_t)$$
  $$HA\_Low_t = \min(Low_t, HA\_Open_t, HA\_Close_t)$$
* **Bollinger Bands (20, 2)**:
  Computed on $HA\_Close$ using standard deviation $\sigma$:
  $$SMA_{20} = \frac{1}{20}\sum_{i=0}^{19} HA\_Close_{t-i}$$
  $$\sigma = \sqrt{\frac{1}{20}\sum_{i=0}^{19} (HA\_Close_{t-i} - SMA_{20})^2}$$
  $$BB\_Upper = SMA_{20} + 2.0 \times \sigma$$
  $$BB\_Lower = SMA_{20} - 2.0 \times \sigma$$

### 2.3 Signal & Entry Rules
1. **Lower Band Touch**: Within the last 3 candles, $HA\_Low \le BB\_Lower$.
2. **Reversal Trigger**: Current 1-minute HA candle closes **GREEN** ($HA\_Close > HA\_Open$).
3. **Price Trigger**:
   $$\text{Entry Price} = HA\_High_{\text{signal}} + ₹1.00$$
4. **Stop Loss**:
   $$\text{Initial SL} = HA\_Low_{\text{signal}} - ₹1.00$$
5. **Risk Filter**:
   $$\text{Risk Points} = \text{Entry Price} - \text{Initial SL}$$
   * **Rule**: If $\text{Risk Points} > 20.0$, the setup is **REJECTED** (risk is too large).
   * Trade is only accepted if $2.0 \le \text{Risk Points} \le 20.0$.

### 2.4 Position Sizing & Trade Management
* **Default Sizing**: 2 Lots ($2 \times 65 = 130$ quantity for Nifty) or dynamic sizing if `risk-mode=FIXED_AMOUNT`.
* **Target 1 (50% Quantity Exit at 1:2 R:R)**:
  $$\text{Target 1 Price} = \text{Entry Price} + (2 \times \text{Risk Points})$$
  * As soon as LTP $\ge \text{Target 1 Price}$, book $50\%$ quantity (1 lot) via `PARTIAL_EXIT_LONG`.
* **Runner Position (Remaining 50% Quantity)**:
  * Move Stop Loss of the remaining $50\%$ to **Cost (Entry Price)** (`UPDATE_STOP_LOSS`).
  * Runner is held until:
    1. Trailing Stop Loss is breached.
    2. Intraday auto-square-off at 03:15 PM IST (`SQUARE_OFF`).
* **SL Hit & Trade #2 Logic**:
  * If Trade #1 hits Initial SL, record trade as completed with loss.
  * Check opposite strike (if CE was stopped out, evaluate PE; if PE was stopped out, evaluate CE) for the next valid lower-band bounce setup.
  * If a second trade is triggered and closes (either profit or loss), **trading stops for the entire day** (Daily trade count = 2).

---

## 3. Subsystem Architecture

```
com.tradingbot.strategy.bollingerha
├── BollingerHaStrikeSelector.java       // Pre-market ATM strike & weekly option resolution
├── ShoonyaHybridDataFeeder.java         // Shoonya WebSocket ticks + REST TPSeries fallback
├── BollingerHaCandleBuilder.java        // Real-time 1m OHLC & Heikin-Ashi aggregator
├── BollingerHaIntradayEngine.java       // Core state machine, signal generator, and position tracker
├── BollingerHaProperties.java           // Configuration & parameter management
├── model/
│   ├── BollingerHaPosition.java         // Active position state (lots, entry, SL, T1, runner)
│   ├── BollingerHaSetupState.java       // Strike tracking state (CE/PE band touch status)
│   └── BollingerHaDailyState.java       // Daily trade counter, PnL, lock flags
└── controller/
    └── BollingerHaController.java       // REST endpoints (/status, /start, /stop, /reset, /simulate)
```

### 3.1 Data Flow Sequence

```
Shoonya WSS (wss://api.shoonya.com/NorenWSTP/)
     │ (Live Ticks for ATM CE & PE)
     ▼
ShoonyaHybridDataFeeder ──[fallback]──► ShoonyaMarketDataService (REST TPSeries)
     │
     ▼ (1-Minute Completed Candles)
BollingerHaCandleBuilder
     │
     ▼ (Heikin-Ashi + BB 20,2)
BollingerHaIntradayEngine
     │
     ├── Checks Entry / SL / T1 / CSL conditions
     │
     ▼ (Emits TradeSignal via Sinks)
ReactiveSignalEventBus
     ├──► ShoonyaTradeConsumer / ZerodhaTradeConsumer (Order Execution)
     └──► TelegramService (Push Notifications)
```

---

## 4. Operational Schedule & State Transitions

| Time (IST) | Action / Lifecycle State |
| :--- | :--- |
| **09:07 AM** | `BollingerHaStrikeSelector` queries Nifty 50 pre-market close, calculates ATM strike, resolves weekly CE & PE tokens, and initializes the data feeder. |
| **09:15 AM** | Market opens. `ShoonyaHybridDataFeeder` starts receiving ticks, `BollingerHaCandleBuilder` begins accumulating 1m candles. Strategy state changes to `SCANNING`. |
| **09:16 – 10:30 AM** | Strategy evaluates completed 1m candles. Enters trades on valid setup. Manages targets and trailing stops. |
| **10:30 AM** | **Entry Cutoff**: No new trade entries allowed. Active positions remain actively managed. Strategy state transitions to `MANAGING_ONLY`. |
| **03:15 PM** | **Intraday Square-Off**: Any active open runner positions are exited at market. Daily state is locked. |
| **03:30 PM** | Daily performance report sent to Telegram. WebSocket connection cleanly closed. |

---

## 5. Configuration (`application.properties`)

```properties
trading-bot.strategy.bollinger-ha.enabled=true
trading-bot.strategy.bollinger-ha.underlying=NIFTY
trading-bot.strategy.bollinger-ha.timeframe-minutes=1
trading-bot.strategy.bollinger-ha.bb-period=20
trading-bot.strategy.bollinger-ha.bb-stddev=2.0
trading-bot.strategy.bollinger-ha.max-sl-points=20.0
trading-bot.strategy.bollinger-ha.min-sl-points=2.0
trading-bot.strategy.bollinger-ha.buffer-points=1.0
trading-bot.strategy.bollinger-ha.risk-reward-ratio=2.0
trading-bot.strategy.bollinger-ha.max-daily-trades=2
trading-bot.strategy.bollinger-ha.sizing-mode=FIXED_LOTS
trading-bot.strategy.bollinger-ha.default-lots=2
trading-bot.strategy.bollinger-ha.fixed-risk-amount=2000.0
trading-bot.strategy.bollinger-ha.entry-window-start=09:15
trading-bot.strategy.bollinger-ha.entry-window-cutoff=10:30
trading-bot.strategy.bollinger-ha.auto-square-off-time=15:15
```

---

## 6. Testing & Validation Strategy

1. **Unit Tests (`BollingerHaIndicatorTest`)**:
   * Verify Heikin-Ashi formulas against known historical tick series.
   * Verify Bollinger Bands calculations against TA4j / TA-Lib baseline.
2. **State Machine Tests (`BollingerHaIntradayEngineTest`)**:
   * Verify lower band touch detection followed by green candle trigger.
   * Verify $>20$ point risk filter rejects oversized candles.
   * Verify partial exit at 1:2 R:R (50% quantity square-off).
   * Verify Cost SL (CSL) movement on remaining runner.
   * Verify 2-trade daily cap prevents any 3rd trade.
   * Verify morning entry cutoff (no trades after 10:30 AM).
3. **Integration Tests (`BollingerHaIntegrationTest`)**:
   * Test full cycle: simulated 1m ticks $\rightarrow$ Candle Builder $\rightarrow$ Engine $\rightarrow$ `ReactiveSignalEventBus` $\rightarrow$ `ShoonyaTradeConsumer` (PAPER mode).

---

## 7. Spec Self-Review Checklist

* [x] **Placeholder scan**: No TODOs, TBDs, or vague placeholders.
* [x] **Internal consistency**: Data models, mathematical formulas, and event actions strictly align with existing codebase conventions.
* [x] **Scope check**: Focused solely on the 1m Bollinger HA Intraday Option Buying strategy and its data/execution pipeline.
* [x] **Ambiguity check**: Clear buffer values (₹1.00), exact cutoff times, exact partial booking percentages (50%), and exact daily limit rules (2 max).
