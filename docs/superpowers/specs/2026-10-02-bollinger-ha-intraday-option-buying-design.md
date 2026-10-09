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
* **Default Sizing**: 2 Lots ($2 \times 65 = 130$ quantity for Nifty) or dynamic sizing if `risk-mode=FIXED_AMOUNT`. Lot size is resolved at runtime from `StockFnoRegistry` (never a hard-coded constant).
* **Target 1 (50% Quantity Exit at 1:2 R:R)**:
  $$\text{Target 1 Price} = \text{Entry Price} + (2 \times \text{Risk Points})$$
  * As soon as LTP $\ge \text{Target 1 Price}$, book $50\%$ quantity (1 lot) via `PARTIAL_EXIT_LONG`.
  * The exit quantity is **floored to whole lots** ($\lfloor Q/2 \rfloor$ aligned to the lot size). A position below two lots cannot be split — it emits `UPDATE_STOP_LOSS` (trail the whole position to cost) instead of a partial exit.
  * The partial quantity's PnL is booked to the daily state at the moment of the exit.
* **Runner Position (Remaining 50% Quantity)**:
  * Move Stop Loss of the remaining $50\%$ to **Cost (Entry Price)**. For a multi-lot position this rides on the `PARTIAL_EXIT_LONG` signal's stop price (one publish per state change); a single-lot position emits `UPDATE_STOP_LOSS`.
  * Runner is held until:
    1. Trailing Stop Loss is breached.
    2. Intraday auto-square-off at **03:00 PM IST** (`SQUARE_OFF`).
* **SL Hit & Trade #2 Logic**:
  * If Trade #1 hits Initial SL, record trade as completed with loss. When the print closed beyond the stop, book the **worse** of the stop and the observed price — never the trigger.
  * Check opposite strike (if CE was stopped out, evaluate PE; if PE was stopped out, evaluate CE) for the next valid lower-band bounce setup.
  * If a second trade is triggered and closes (either profit or loss), **trading stops for the entire day** (Daily trade count = 2).
* **Event Bus Contract**: every exit is **published before** engine state mutates. If `ReactiveSignalEventBus.publish(...)` returns `false`, the caller rolls back and the position stays open, so a full buffer can never lose track of a live trade.
* **State Persistence**: trade count, realized PnL, lock flag and the open position are written to `state-file-path` (atomic temp-file + move) on every transition. On restart, a snapshot from *today* is restored; a snapshot from a previous day queues its open position for a forced `SQUARE_OFF` on the first live event instead of being silently forgotten.

---

## 3. Subsystem Architecture

```
com.tradingbot.strategy.bollingerha
├── BollingerHaStrikeSelector.java       // Pre-market ATM strike & weekly option resolution
├── config/
│   └── BollingerHaProperties.java       // Configuration & parameter management
├── controller/
│   └── BollingerHaController.java       // REST endpoints (/status, /start, /stop, /reset, /simulate, /square-off)
├── feeder/
│   ├── ShoonyaHybridDataFeeder.java     // Shoonya WebSocket ticks + REST TPSeries fallback + NIFTY spot feed
│   └── BollingerHaCandleBuilder.java    // Real-time 1m OHLC aggregator (multi-minute bucket aware)
├── indicator/
│   └── BollingerHaCalculator.java       // Heikin-Ashi transform, Bollinger Bands, spot EMA
├── model/
│   ├── BollingerHaPosition.java         // Active position state (lots, entry, SL, T1, runner)
│   ├── BollingerHaSetupState.java       // Strike tracking state (CE/PE band touch status)
│   ├── BollingerHaDailyState.java       // Daily trade counter, PnL, lock flags
│   └── HeikinAshiCandle.java            // Immutable HA candle
├── scheduler/
│   └── BollingerHaScheduler.java        // Crons: strike selection, feeder arm, cutoff, square-off poll, summary
└── service/
    └── BollingerHaIntradayEngine.java   // Core state machine, signal generator, position tracker, persistence

com.tradingbot.persistence
└── BollingerHaStateStore.java           // Atomic JSON snapshot of daily state + open position
```

### 3.1 Data Flow Sequence

```
Shoonya WSS (wss://api.shoonya.com/NorenWSTP/)
     │ (Live Ticks for ATM CE & PE, plus NSE|26000 for the NIFTY spot EMA filter)
     ▼
ShoonyaHybridDataFeeder ──[fallback]──► ShoonyaMarketDataService (REST TPSeries / GetQuotes)
     │
     ▼ (1-Minute Completed Candles)      └─► spot ticks ─► engine.updateSpotPrice()
BollingerHaCandleBuilder
     │
     ▼ (Heikin-Ashi + BB 20,2)
BollingerHaIntradayEngine
     │
     ├── Checks Entry / SL / T1 / CSL conditions, auto square-off, daily rollover
     │
     ▼ (Emits TradeSignal via Sinks — publish first, mutate only on success)
ReactiveSignalEventBus
     ├──► ShoonyaTradeConsumer / ZerodhaTradeConsumer (Order Execution)
     └──► TelegramService (Push Notifications)
```

---

## 4. Operational Schedule & State Transitions

| Time (IST) | Action / Lifecycle State |
| :--- | :--- |
| **09:07 AM** | `BollingerHaStrikeSelector` resolves Nifty spot (hourly candles → live quote API → **fail loudly**, never a hard-coded level), calculates the ATM strike, resolves weekly CE & PE tokens. Skipped on non-trading days. |
| **09:14 AM** | Feeder armed for the open. If strike selection never ran, an error alert is pushed and the feeder stays **disarmed** instead of streaming against unknown contracts. |
| **09:15 AM** | Market opens. `ShoonyaHybridDataFeeder` receives ticks (contracts + NIFTY spot for the trend filter), `BollingerHaCandleBuilder` accumulates candles, engine state `SCANNING`. |
| **09:16 – 10:30 AM** | Engine evaluates completed candles. Enters on a valid setup, manages target/stop with gap-aware ordering (open beyond stop → stop; else target; else intrabar stop). |
| **10:30 AM** | **Entry Cutoff**: no new entries. Active positions remain managed. State `MANAGING_ONLY`. |
| **Every minute ≥ 03:00 PM** | **Auto square-off poller**: reads `auto-square-off-time` each minute so a restart or a paused scheduler can never miss the hard exit. Position is flattened at market. |
| **03:30 PM** | Daily performance report sent to Telegram; `ShoonyaHybridDataFeeder.disconnect()` closes the socket and its polls. |
| **Next session, first event** | Daily rollover: state resets for the new date (after forcing out any position left open), spot history and setup touches clear. |

---

## 5. Configuration (`application.properties`)

```properties
trading-bot.strategy.bollinger-ha.enabled=true
trading-bot.strategy.bollinger-ha.underlying=NIFTY
trading-bot.strategy.bollinger-ha.timeframe-minutes=1
trading-bot.strategy.bollinger-ha.bb-period=20
trading-bot.strategy.bollinger-ha.bb-std-dev=2.0
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
trading-bot.strategy.bollinger-ha.auto-square-off-time=15:00
trading-bot.strategy.bollinger-ha.trend-filter-enabled=true
trading-bot.strategy.bollinger-ha.trend-ema-period=20
trading-bot.strategy.bollinger-ha.state-file-path=data/bollinger_ha_state.json
```

Every key is overridable through the environment (`BOLLINGER_HA_*`, see `.env.example`).
The trend filter requires the NIFTY spot EMA(20): CE entries are rejected while spot trades below
it, PE entries while spot trades above it. `state-file-path` is where the restart snapshot lives —
unit tests point it at a temp directory so no test can read or write the repository's `data/`.

---

## 6. Testing & Validation Strategy

1. **Indicator Tests (`BollingerHaCalculatorTest`)**:
   * Verify Heikin-Ashi formulas against known OHLC series.
   * Verify Bollinger Bands ordering on the HA series.
   * Verify a doji (HA close == HA open) is **not** read as a green reversal.
2. **State Machine Tests (`BollingerHaIntradayEngineTest`)**:
   * Lower-band touch followed by a green candle triggers exactly one `ENTRY_LONG`.
   * $>20$ point risk filter rejects oversized candles (nothing is published).
   * Trend filter rejects CE below / PE above the spot EMA(20) before anything reaches the bus.
   * Partial exit at 1:2 R:R books the lot-aligned half and trails the runner to cost; a
     single-lot position emits `UPDATE_STOP_LOSS` instead.
   * Publish-before-mutate: when the bus rejects a signal the position and PnL are unchanged.
   * PnL booked on partial exit, stop hit, and square-off; tick-driven target/stop paths.
   * Auto square-off at the configured cutoff, forced exit on daily rollover.
   * Restart: same-day snapshot restores the open position; a stale snapshot forces an exit of the
     orphan on the first live event.
3. **Consumer Tests (`ShoonyaTradeConsumerTest`, `ZerodhaTradeConsumerTest`)**:
   * `UPDATE_STOP_LOSS` cancels the resting stop and re-places it without a position order.
   * Option protective SL falls back to the signal stop when the broker returns no price.
   * Zerodha uses market orders for entry/exit while the protective stop stays `SL_LMT`.
4. **Scheduler / Controller Tests**:
   * Spot resolution falls back to the quote API and fails loudly instead of guessing.
   * Feeder arm alerts when strikes are missing; crons skip non-trading days and disabled runs.
   * `/reset` returns `409` while a position is open; `/stop` squares off before disabling.
5. **Integration Test (`BollingerHaIntegrationTest`)**:
   * Full cycle: candles $\rightarrow$ engine $\rightarrow$ `ReactiveSignalEventBus` $\rightarrow$ `ShoonyaTradeConsumer` (PAPER mode).

---

## 7. Spec Self-Review Checklist

* [x] **Placeholder scan**: No TODOs, TBDs, or vague placeholders.
* [x] **Internal consistency**: Data models, mathematical formulas, and event actions strictly align with existing codebase conventions.
* [x] **Scope check**: Focused solely on the 1m Bollinger HA Intraday Option Buying strategy and its data/execution pipeline.
* [x] **Ambiguity check**: Clear buffer values (₹1.00), exact cutoff times, exact partial booking percentages (50%), and exact daily limit rules (2 max).
