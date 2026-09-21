# Design Specification: KISS (Keep It Swing Systematic) Multi-Timeframe Strategy

**Date:** 2026-09-20  
**Status:** Approved  
**Timeframe:** 1-Hour (1H) Heikin-Ashi Execution  
**Asset Scope:** Nifty 200 Futures & Commodities (Crude Oil, Gold, Silver, Copper Futures)  
**Trading Mode:** Both Long (Buy) and Short (Sell) Futures only (No Options)  

---

## 1. Overview & Purpose
The KISS (Keep It Swing Systematic) strategy is a systematic, multi-timeframe trend-following swing trading system adapted from veteran trader Animesh K's masterclass. It uses a **Weekly Heikin-Ashi filter** to establish macro direction, a **1-Hour 55 EMA High/Low Envelope + 55 Directional Slope** to filter sideways consolidation noise, and a **1-Hour MACD (12, 26, 9) Zero-Line Crossover** to pinpoint momentum breakouts.

All trades are strictly executed on **Futures contracts** (with defined lot sizes, tick sizes, and $1:3$ to $1:4$ asymmetric risk-to-reward ratios).

---

## 2. Universe Scope & Instrument Registries

### 2.1 Equities (Nifty 200 Futures)
- Sourced from `Nifty200Registry` (contains stock metadata, F&O lot sizes, and Yahoo tickers).

### 2.2 Commodities (MCX & Global Futures)
- Managed via `CommodityRegistry`:
  - **CRUDEOIL**: Lot size 100 bbl, tick size 1.0, Yahoo ticker `CL=F`
  - **GOLD**: Lot size 100 (or 10 for mini), tick size 1.0, Yahoo ticker `GC=F`
  - **SILVER**: Lot size 30 (or 5 for mini), tick size 1.0, Yahoo ticker `SI=F`
  - **COPPER**: Lot size 2500 kg, tick size 0.05, Yahoo ticker `HG=F`

---

## 3. Mathematical Models & Indicators

### 3.1 Heikin-Ashi Conversion
From standard OHLC bars:
- $\text{HA\_Close} = \frac{\text{Open} + \text{High} + \text{Low} + \text{Close}}{4}$
- $\text{HA\_Open} = \frac{\text{HA\_Open}_{t-1} + \text{HA\_Close}_{t-1}}{2}$
- $\text{HA\_High} = \max(\text{High}, \text{HA\_Open}, \text{HA\_Close})$
- $\text{HA\_Low} = \min(\text{Low}, \text{HA\_Open}, \text{HA\_Close})$

### 3.2 55 EMA Envelope (Sideways Filter)
Calculated on 1-Hour Heikin-Ashi bars:
- $\text{EMA\_High}(55) = \text{EMA}(\text{HA\_High}, 55)$
- $\text{EMA\_Low}(55) = \text{EMA}(\text{HA\_Low}, 55)$
- $\text{EMA\_Slope}(55) = \text{EMA}(\text{HA\_Close}, 55)$
  - *Bullish:* $\text{EMA\_Slope}[t] > \text{EMA\_Slope}[t-1]$
  - *Bearish:* $\text{EMA\_Slope}[t] < \text{EMA\_Slope}[t-1]$
  - *Neutral / No-Trade Zone:* Price oscillating between $\text{EMA\_Low}(55)$ and $\text{EMA\_High}(55)$.

### 3.3 MACD Momentum Trigger
- Fast EMA = 12, Slow EMA = 26, Signal EMA = 9
- $\text{MACD Line} = \text{EMA}(12) - \text{EMA}(26)$
- $\text{Signal Line} = \text{EMA}(\text{MACD Line}, 9)$

---

## 4. Exact Execution Rules

### 4.1 🟢 LONG (Buy Futures) Entry
All conditions must be satisfied on the completed 1-Hour candle:
1. **Weekly HA Filter:** Completed Weekly Heikin-Ashi candle is **Green** ($\text{HA\_Close} > \text{HA\_Open}$).
2. **Band Breakout:** 1-Hour $\text{HA\_Close} > \text{EMA\_High}(55)$.
3. **Slope Alignment:** 55 Directional EMA is **Rising** ($\text{Slope}[t] > \text{Slope}[t-1]$).
4. **MACD Trigger:** $\text{MACD Line} > \text{Signal Line}$ AND $\text{MACD Line} > 0$ (Bullish cross above zero line).
5. **Execution:** Buy 1 or more Futures Lots at current Real Market LTP on candle close.
6. **Stop Loss (SL):** $\min(\text{Lowest Low of last 3 HA candles}, \text{EMA\_Low}(55)) - \text{Tick Buffer}$.
7. **Take Profit (TP):** $\text{Entry} + (3.0 \times \text{Risk Amount})$.

### 4.2 🔴 SHORT (Sell Futures) Entry
All conditions must be satisfied on the completed 1-Hour candle:
1. **Weekly HA Filter:** Completed Weekly Heikin-Ashi candle is **Red** ($\text{HA\_Close} < \text{HA\_Open}$).
2. **Band Breakdown:** 1-Hour $\text{HA\_Close} < \text{EMA\_Low}(55)$.
3. **Slope Alignment:** 55 Directional EMA is **Falling** ($\text{Slope}[t] < \text{Slope}[t-1]$).
4. **MACD Trigger:** $\text{MACD Line} < \text{Signal Line}$ AND $\text{MACD Line} < 0$ (Bearish cross below zero line).
5. **Execution:** Sell/Short 1 or more Futures Lots at current Real Market LTP on candle close.
6. **Stop Loss (SL):** $\max(\text{Highest High of last 3 HA candles}, \text{EMA\_High}(55)) + \text{Tick Buffer}$.
7. **Take Profit (TP):** $\text{Entry} - (3.0 \times \text{Risk Amount})$.

### 4.3 🚪 Complete Exit Signals (4 Trigger Conditions)
An active position is closed immediately when ANY of the following occurs:
1. **Target Hit:** LTP reaches Target 1 ($1:3$ R:R).
2. **Stop Loss Hit:** 1-Hour candle closes beyond Stop Loss level.
3. **MACD Momentum Reversal:**
   - For Longs: 1-Hour $\text{MACD Line}$ crosses below $\text{Signal Line}$.
   - For Shorts: 1-Hour $\text{MACD Line}$ crosses above $\text{Signal Line}$.
4. **Friday Weekend Exit:** Friday 15:15 IST (for Equities) to eliminate weekend event risk.

---

## 5. Position Sizing & Money Management
- **Risk Budget:** $1.5\%$ maximum account risk per trade.
- **Futures Lot Sizing:**
  $$\text{Lots} = \max\left(1, \left\lfloor \frac{\text{Account Capital} \times 0.015}{\text{Risk Per Unit} \times \text{Lot Size}} \right\rfloor\right)$$
- **Max Open Positions:** 10 concurrent futures trades.

---

## 6. System Architecture & Components
- **Package:** `com.tradingbot.strategy.kiss`
  - `KissStrategyConfig`: Configurable parameters (`enabled`, `emaPeriod`, `macdFast`, `riskRewardRatio`, etc.).
  - `KissSignal` / `KissSignalType`: Signal event structures with rich metadata.
  - `KissPosition` / `KissState`: Active position tracking, PnL calculations, and JSON persistence.
  - `KissSnapshot`: Technical snapshot holding Weekly HA, 55 Bands, and MACD metrics.
  - `KissIndicatorService`: Multi-timeframe resampling and technical calculations.
  - `KissSwingService`: Core orchestration, universe evaluation, and position management.
  - `KissScheduler`: Periodic cron triggers (hourly scans during market hours + Friday close).
  - `KissController`: REST endpoints (`GET /api/v1/kiss/status`, `POST /api/v1/kiss/scan`, etc.).
- **Registries & Utilities:**
  - `CommodityRegistry`: Contract specifications for Crude, Gold, Silver, Copper.
  - `CandleResamplingUtil`: Added `resample5MinTo1Hour` and `resample5MinTo4Hour`.
  - `YahooFinanceService`: Commodity ticker mapping support (`CL=F`, `GC=F`, `SI=F`, `HG=F`) + hourly data fetching.
