# Technical Specification: Lowest Volume Reversal (LVR) Futures Execution & 100% Full Exit at 1:4

**Document ID:** SPEC-2026-09-21-LVR-FUTURES-FULL-EXIT  
**Author:** DeepMind Antigravity Pair Programmer  
**Date:** September 21, 2026  
**Status:** PROPOSED (Pending User Review)  
**Target Module:** `com.tradingbot.service.LowestVolumeReversalService` / `LowestVolumePaperPosition`

---

## 1. Executive Summary & Rationale

### 1.1 Motivation
The current Lowest Volume Reversal (LVR) strategy trades simulated ATM Long Options (`CE`/`PE`) with a two-phase exit:
1. **Target 1:4**: Book 50% partial profit and move Stop Loss to cost.
2. **Runner**: Trail remaining 50% using the 5m 10 EMA.

**Observed Bottlenecks in Live Intraday Performance:**
- **Option Greeks Drag**: ATM Options exhibit ~0.50 Delta, Theta time decay, and IV crush, preventing true realization of the theoretical $1:4$ Risk-to-Reward on stock price moves.
- **Diluted Expectancy on Runners**: Morning momentum moves in single-stock F&O often exhaust or mean-revert around $1:3$ to $1:4$. Trailing the runner back to the 10 EMA frequently gives back profits, reducing overall trade expectancy from $+4.0\text{R}$ down to $+2.2\text{R}$.
- **Capital Turnover**: Holding runners locks up concurrent trade slots (default: 3) for hours instead of freeing capacity for other fresh morning setups.

### 1.2 Proposed Enhancement
1. **Primary Instrument Mode**: **Stock Futures (`FUTURES`)** as the default instrument type (with constant $1.0$ Delta and direct rupee P&L calculation).
2. **Primary Exit Strategy**: **100% Full Exit at 1:4 Risk-Reward (`FULL_TARGET_1_4`)**. Once the target price is reached, the entire position is squared off immediately in one atomic operation.
3. **Backward Compatibility**: Configurable flags `instrument-type` (`FUTURES` vs `OPTIONS`) and `exit-mode` (`FULL_TARGET_1_4` vs `PARTIAL_RUNNER_10EMA`) so users can switch modes seamlessly via `application.properties` or environment variables.

---

## 2. Mathematical Model & Position Sizing

### 2.1 Futures Risk & Reward Calculations

For an active setup with Trigger Price $P_{\text{trigger}}$ and Stop Loss Price $P_{\text{SL}}$:

$$\text{Unit Risk} = |P_{\text{trigger}} - P_{\text{SL}}|$$

#### Long Setup:
$$P_{\text{entry}} = P_{\text{trigger}}$$
$$P_{\text{SL}} = P_{\text{low}} - 0.05$$
$$P_{\text{target}} = P_{\text{entry}} + (4.0 \times \text{Unit Risk})$$

#### Short Setup:
$$P_{\text{entry}} = P_{\text{trigger}}$$
$$P_{\text{SL}} = P_{\text{high}} + 0.05$$
$$P_{\text{target}} = P_{\text{entry}} - (4.0 \times \text{Unit Risk})$$

### 2.2 P&L Accounting

Given:
- $Q_{\text{total}} = \text{defaultLots} \times \text{lotSize}$ (from `StockFnoRegistry`)
- $P_{\text{exit}} = \text{execution price at exit}$

$$\text{Gross P&L (Long)} = (P_{\text{exit}} - P_{\text{entry}}) \times Q_{\text{total}}$$
$$\text{Gross P&L (Short)} = (P_{\text{entry}} - P_{\text{exit}}) \times Q_{\text{total}}$$

| Event | Outcome ($R$) | Long Realized P&L | Short Realized P&L |
| :--- | :--- | :--- | :--- |
| **Stop Loss Hit** | **$-1.0\text{R}$** | $-(P_{\text{entry}} - P_{\text{SL}}) \times Q_{\text{total}}$ | $-(P_{\text{SL}} - P_{\text{entry}}) \times Q_{\text{total}}$ |
| **Target 1:4 Hit** | **$+4.0\text{R}$** | $+(4 \times \text{Unit Risk}) \times Q_{\text{total}}$ | $+(4 \times \text{Unit Risk}) \times Q_{\text{total}}$ |
| **Hard EOD Exit (15:15 IST)**| MTM $(R)$ | $(P_{15:15} - P_{\text{entry}}) \times Q_{\text{total}}$ | $(P_{\text{entry}} - P_{15:15}) \times Q_{\text{total}}$ |

---

## 3. Architecture & Data Flow

```
+-------------------------------------------------------------+
|               Market Data & Scanner Engine                  |
|  - 09:30 AM Sector Breadth Filter                           |
|  - 5m Candle Ingestion (C1-C3 baseline, C4+ pullback)       |
+------------------------------+------------------------------+
                               |
                               v
+-------------------------------------------------------------+
|                LowestVolumeReversalService                  |
|  - Trigger Armed (lowest volume candle)                     |
|  - Invalidation: SL breach or Target reached before entry   |
+------------------------------+------------------------------+
                               |
                     Spot Trigger Breached
                               |
                               v
+-------------------------------------------------------------+
|                    Execution Dispatcher                     |
|  IF instrument-type == FUTURES:                             |
|     - Instrument: "<SYMBOL> FUT" (e.g., SUNPHARMA FUT)      |
|     - Entry Price: Live Spot / Futures Price                |
|     - Quantity: lots * StockFnoRegistry.lotSize             |
|     - Stop Loss: Spot SL level                              |
|     - Target 1: Spot Target 1 (Exact 1:4 R:R)               |
+------------------------------+------------------------------+
                               |
                               v
+-------------------------------------------------------------+
|               Live Position Monitoring Loop                 |
|                     (30s High-Res Tick)                     |
|                                                             |
|  [Case A] Spot Breaches SL:                                 |
|     -> 100% Exit Position (-1R)                             |
|     -> Attempt < 2 ? Reset to Scanning : Exhaust Stock      |
|                                                             |
|  [Case B] Spot Breaches Target 1 (1:4 RR):                  |
|     -> 100% FULL EXIT (+4R)                                 |
|     -> Setup State -> CLOSED_TARGET                         |
|     -> Stock Added to exhaustedSymbols                      |
|                                                             |
|  [Case C] Clock >= 15:15 IST (Hard EOD Cutoff):             |
|     -> 100% Force Square-off at MTM                         |
|     -> Setup State -> CLOSED_TIMEOUT                        |
+-------------------------------------------------------------+
```

---

## 4. Configuration Schema

Added to `application.properties`:

```properties
# ==============================================================================
# Lowest Volume Reversal (LVR) Execution & Exit Configuration
# ==============================================================================
trading-bot.strategy.lowest-volume.enabled=true
trading-bot.strategy.lowest-volume.instrument-type=FUTURES       # [FUTURES, OPTIONS]
trading-bot.strategy.lowest-volume.exit-mode=FULL_TARGET_1_4     # [FULL_TARGET_1_4, PARTIAL_RUNNER_10EMA]
trading-bot.strategy.lowest-volume.default-lots=1
trading-bot.strategy.lowest-volume.max-concurrent-trades=3
trading-bot.strategy.lowest-volume.telegram-alerts=true
```

---

## 5. Telegram Notification Enhancements

### 5.1 Futures Entry Alert
```text
🚀 LVR Futures Entry Triggered
• Symbol: SUNPHARMA (LONG)
• Contract: SUNPHARMA FUT
• Entry Price: ₹1875.00 (1 lot / 350 qty)
• Initial Stop Loss: ₹1872.05 (Risk: ₹2.95 / ₹1,032.50)
• 1:4 Target Price: ₹1886.80 (Reward: ₹11.80 / +₹4,130.00)
```

### 5.2 100% Target Hit Exit Alert
```text
🎯 LVR 1:4 Target Reached (100% Full Exit)
• Symbol: SUNPHARMA (LONG)
• Exit Price: ₹1886.80
• Total Points: +11.80 pts (+4.00 R)
• Realized P&L: +₹4,130.00
• Duration: 36 mins
```

### 5.3 Stop Loss Exit Alert
```text
🛑 LVR Stop Loss Hit (100% Exit)
• Symbol: SUNPHARMA (LONG)
• Exit Price: ₹1872.00
• Total Points: -3.00 pts (-1.00 R)
• Realized P&L: -₹1,050.00
• Remaining Day Attempts: 1
```

---

## 6. Testing & Quality Assurance Plan

1. **Unit Tests**:
   - `LowestVolumeFuturesExecutionTest`: Verify 1.0 Delta P&L calculation, lot multiplier, and precision rounding.
   - `LowestVolumeFullExitTest`: Verify position transitions directly to `CLOSED_TARGET` upon 1:4 breach without creating runner state.
   - `LowestVolumeConfigSwitchTest`: Verify smooth fallback to `OPTIONS` mode when `instrument-type=OPTIONS`.
2. **Replay Validation**:
   - Run `ShoonyaLast5DaysLvrReplayRunnerTest` and `ShoonyaTodayLvrReplayRunnerTest` under `FUTURES` + `FULL_TARGET_1_4` mode to verify positive expectancy and clean exit sequencing.
