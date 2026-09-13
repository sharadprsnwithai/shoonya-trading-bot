# Design Spec: Bollinger Band & Heikin-Ashi Nifty Positional Strategy with Telegram Control

**Author**: Antigravity  
**Date**: 2026-03-30  
**Status**: APPROVED  
**Target Module**: `com.tradingbot.positional` & `com.tradingbot.telegram`

---

## 1. Overview & Purpose

This subsystem automates the **Bollinger Band (20, 2) & Heikin-Ashi Positional Strategy** on the **Nifty 50 Index (`NIFTY 50`)**:
- **Execution Timing**: Evaluates daily market condition at **3:00 PM IST** (configurable cron).
- **Execution Vehicle**: **Monthly ATM Naked Option Buying (30–45 DTE)** (Buy Monthly ATM Call on Long, Buy Monthly ATM Put on Short) to capture multi-hundred-point trend convexity while strictly capping overnight gap risk at premium paid.
- **Control Interface**: Full **Telegram Bot Command & Interactive Button Control** (`/approve`, `/reject`, `/status`, `/scan`, `/exit`, `/mode`), allowing users to approve signals directly from their phone before the 3:30 PM market close.

---

## 2. Core Architecture & Components

```
┌────────────────────────────────────────────────────────────────────────┐
│             PositionalTradingScheduler (3:00 PM IST MON-FRI)           │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ triggers daily
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│               BollingerHaPositionalService                             │
│  - Fetches Nifty 50 Daily OHLC Candles (Shoonya API)                   │
│  - Converts Daily Candles -> Heikin-Ashi Series (HA_Open, High, etc.)  │
│  - Computes Bollinger Bands (20, 2) on Heikin-Ashi Close               │
│  - Evaluates Alert State Machine (Band Touch -> Inside Close Alert)    │
│  - Checks Entry Trigger vs Spot, SL Hit, Opposite BB Target Hit        │
└──────────────────┬─────────────────────────────────┬───────────────────┘
                   │                                 │
                   ▼                                 ▼
┌──────────────────────────────────┐  ┌──────────────────────────────────┐
│   PositionalExecutionService     │  │   TelegramBotCommandListener     │
│ - Selects Monthly ATM CE/PE      │  │ - Interactive Telegram Buttons   │
│ - Executes Buy via Shoonya API   │  │   ([✅ Approve] / [❌ Reject])    │
│ - Handles PAPER & LIVE modes     │  │ - Commands: /status, /scan,      │
│ - Persists Open Positions        │  │   /approve, /exit, /mode         │
└──────────────────────────────────┘  └──────────────────────────────────┘
```

---

## 3. Detailed Component Specifications

### 3.1 `BollingerHaIndicatorService`
* **Daily Candle Aggregation**: Fetches completed daily candles for Nifty 50 Spot.
* **Heikin-Ashi Conversion**:
  $$HA\_Close = \frac{Open + High + Low + Close}{4}$$
  $$HA\_Open_t = \frac{HA\_Open_{t-1} + HA\_Close_{t-1}}{2}$$
  $$HA\_High = \max(High, HA\_Open, HA\_Close)$$
  $$HA\_Low = \min(Low, HA\_Open, HA\_Close)$$
* **Bollinger Bands**:
  $$SMA20 = \frac{1}{20}\sum_{i=1}^{20} HA\_Close_i$$
  $$\sigma = \text{Standard Deviation}(HA\_Close, 20)$$
  $$BB\_Upper = SMA20 + 2.0 \times \sigma$$
  $$BB\_Lower = SMA20 - 2.0 \times \sigma$$

### 3.2 State Machine & Signal Logic (`BollingerHaPositionalService`)
1. **Upper Band Touch Condition (Sell Setup)**:
   * Heikin-Ashi High $\ge BB\_Upper$. Set `touchedUpper = true`.
   * **Sell Alert Candle**: When a subsequent candle has $HA\_High < BB\_Upper$ and $HA\_Close < BB\_Upper$.
   * Records Normal Candle High ($H_{alert}$ = Stop Loss) and Low ($L_{alert}$ = Entry Level).
2. **Lower Band Touch Condition (Buy Setup)**:
   * Heikin-Ashi Low $\le BB\_Lower$. Set `touchedLower = true`.
   * **Buy Alert Candle**: When a subsequent candle has $HA\_Low > BB\_Lower$ and $HA\_Close > BB\_Lower$.
   * Records Normal Candle High ($H_{alert}$ = Entry Level) and Low ($L_{alert}$ = Stop Loss).
3. **Trigger Evaluation at 3:00 PM IST**:
   * **Entry Trigger**: If current Nifty Spot breaks beyond $H_{alert}$ (Buy) or $L_{alert}$ (Sell).
   * **Invalidation**: If current Nifty Spot breaks the opposite side before triggering entry, alert is discarded.
   * **SL Trigger**: If in position and Spot crosses $L_{alert}$ (Long) or $H_{alert}$ (Short).
   * **Target Trigger**: If in position and Spot reaches Opposite Bollinger Band ($BB\_Upper$ for Long, $BB\_Lower$ for Short).

### 3.3 Option Contract Selection (`PositionalExecutionService`)
* **Expiry**: Nearest **Monthly Expiry** with $> 20$ DTE (e.g., last Thursday of month).
* **Strike**: Nearest ATM strike (rounded to nearest 50 points).
* **Instrument**:
  * Bullish Signal $\rightarrow$ Buy `NIFTY...CE`
  * Bearish Signal $\rightarrow$ Buy `NIFTY...PE`
* **Quantity**: Configurable via `trading-bot.positional.lot-size` (default: 1 lot = 65 qty).

### 3.4 Telegram Bot Interactive Command Listener (`TelegramBotCommandListener`)
* Listens via Telegram Bot Long-Polling API (`getUpdates`).
* **Supported Commands**:
  * `/status`: Displays Nifty spot, BB Upper/Lower/SMA values, active alert levels, and open position PnL.
  * `/scan`: Runs an immediate scan on demand and replies with findings.
  * `/approve`: Executes staged pending signal immediately.
  * `/reject`: Cancels staged pending signal.
  * `/exit`: Exits the active positional trade immediately.
  * `/mode <AUTO|MANUAL>`: Toggles execution mode.
  * `/help`: Displays help menu.
* **Interactive Alert Notifications**:
  When a signal triggers at 3:00 PM IST, Telegram receives a rich Markdown message with **Inline Buttons**:
  ```
  🔔 [3:00 PM IST] POSITIONAL SIGNAL GENERATED!
  🎯 Setup: NIFTY 50 - BUY CALL (CE)
  📊 Trigger: Spot (23,120) crossed Alert High (23,090)
  🛑 Stop Loss: 22,850 (Spot Level)
  🎯 Target: Opposite BB (24,200)
  📦 Contract: NIFTY 23100 CE Monthly (LTP: ₹340.00)
  
  [ ✅ Approve & Buy CE ]   [ ❌ Reject Signal ]
  ```

---

## 4. Configuration Schema (`application.yml`)

```yaml
trading-bot:
  positional:
    enabled: true
    cron: "0 0 15 * * MON-FRI" # 3:00 PM IST
    symbol: "NIFTY 50"
    execution-mode: MANUAL_CONFIRMATION # AUTO | MANUAL_CONFIRMATION
    lot-size: 1
    max-risk-rupees: 35000
    state-file-path: "data/bb_rsi_positional_state.json"
```

---

## 5. Testing & Verification Plan

1. **Unit Tests**:
   * `BollingerHaIndicatorServiceTest`: Validates Heikin-Ashi calculation and Bollinger Bands against known test series.
   * `BollingerHaStateMachineTest`: Validates state transitions (touch -> alert candle -> entry trigger -> invalidation -> SL hit -> target hit).
2. **Integration Tests**:
   * `TelegramBotCommandListenerTest`: Tests parsing of Telegram commands (`/status`, `/approve`, `/exit`) and callback button actions.
   * `PositionalExecutionServiceTest`: Tests option contract resolution and paper order execution.
3. **Live Verification**:
   * Trigger `/scan` via Telegram to verify live Nifty daily candle fetching, HA conversion, and status response.
