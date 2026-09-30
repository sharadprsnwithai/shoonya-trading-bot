# Commodity Bullion & Energy Strategies Design Specification

**Document ID:** `2026-09-30-commodity-bullion-and-energy-strategies-design`  
**Subsystem:** `com.tradingbot.strategy.commodity`  
**Status:** `Approved`  
**Date:** `2026-09-30`

---

## 1. Overview & System Goals

The **Commodity Strategy Subsystem** provides automated algorithmic trading for MCX / Global Commodities across two distinct mathematical edges:

1. **Precious Metals Techno-Funda Breakout Strategy (`GOLD`, `SILVER`):**  
   A 15-minute London & US session breakout system backed by inter-market macro tailwinds (US Dollar Index DXY, USD/INR currency trends, and Gold-to-Silver ratio).
2. **Energy Intraday VWAP Pullback Strategy (`CRUDEOIL`, `NATURALGAS`):**  
   A 5-minute lowest volume pullback and VWAP trend-following strategy operating during high-liquidity London/US trading hours with built-in EIA inventory protection.

---

## 2. High-Level Architecture

```
                               ┌────────────────────────────────────────┐
                               │     COMMODITY STRATEGY SUBSYSTEM       │
                               └───────────────────┬────────────────────┘
                                                   │
                ┌──────────────────────────────────┴──────────────────────────────────┐
                │                                                                     │
                ▼                                                                     ▼
┌───────────────────────────────┐                                   ┌───────────────────────────────────┐
│   BULLION TECHNO-FUNDA        │                                   │     ENERGY VWAP PULLBACK (LVR)    │
├───────────────────────────────┤                                   ├───────────────────────────────────┤
│ • Instruments: GOLD, SILVER   │                                   │ • Instruments: CRUDEOIL, NATGAS   │
│ • Timeframe: 15-Minute        │                                   │ • Timeframe: 5-Minute             │
│ • Hours: 13:30 - 21:00 IST    │                                   │ • Hours: 13:30 - 21:00 IST        │
│ • Macro: DXY + USD/INR        │                                   │ • Trigger: Lowest Volume Pullback │
│ • Trigger: Asian Rng Breakout │                                   │ • VWAP & EIA Inventory Filter    │
│ • Exit: 1:2 RR + SuperTrend   │                                   │ • Exit: 1:2 RR + 10 EMA Trailing  │
└───────────────┬───────────────┘                                   └─────────────────┬─────────────────┘
                │                                                                     │
                └──────────────────────────────────┬──────────────────────────────────┘
                                                   │
                                                   ▼
                               ┌────────────────────────────────────────┐
                               │       ReactiveSignalEventBus           │
                               │  (Emits TradeSignal to MCX Consumers)  │
                               └────────────────────────────────────────┘
```

---

## 3. Precious Metals Techno-Funda Breakout (`GOLD`, `SILVER`)

### **3.1 Macro Correlation Filters**
- **US Dollar Index (`DX-Y.NYB` / DXY):**
  - **LONG Filter:** DXY must be below its intraday VWAP or negative on the day.
  - **SHORT Filter:** DXY must be above its intraday VWAP or positive on the day.
- **USD/INR (`USDINR=X`):**
  - Depreciating Rupee ($\text{Spot} \ge \text{EMA}_{20}$) provides domestic MCX bullion tailwind.

### **3.2 Session Breakout Setup (13:30 to 21:00 IST)**
- **Asian Range (09:00 – 13:00 IST):** Calculates $\text{Asian High}$ and $\text{Asian Low}$.
- **LONG Entry Condition:**
  1. 15-Minute Candle Close $> \text{Asian High}$
  2. $\text{Spot Price} > \text{Intraday VWAP}$
  3. $15\text{m RSI}(14) > 52$
  4. $15\text{m SuperTrend}(10, 3) == 1 \text{ (Bullish Green)}$
  5. Bullish Macro Alignment (DXY not strengthening)
- **SHORT Entry Condition:**
  1. 15-Minute Candle Close $< \text{Asian Low}$
  2. $\text{Spot Price} < \text{Intraday VWAP}$
  3. $15\text{m RSI}(14) < 48$
  4. $15\text{m SuperTrend}(10, 3) == -1 \text{ (Bearish Red)}$
  5. Bearish Macro Alignment (DXY not weakening)

### **3.3 Risk & Exit Rules**
- **Stop Loss:** Low of breakout candle (minimum 0.20% noise floor).
- **Target 1:** **1:2 Risk-to-Reward (Book 50%)**, then move Stop Loss to Cost.
- **Runner (50%):** Trailed via **15m SuperTrend(10, 3)**.
- **EOD Exit:** 22:30 IST.

---

## 4. Energy Intraday VWAP Pullback (`CRUDEOIL`, `NATURALGAS`)

### **4.1 Active London & US Hours (13:30 to 21:00 IST)**
- Session sentiment evaluated at 13:30 IST relative to Intraday VWAP.

### **4.2 Lowest Volume Pullback Engine**
1. **Baseline Volume:** Initial 3 London 5-minute bars (13:30–13:45).
2. **Setup Trigger:** Opposite-color candle with volume $< \text{rollingLowestVolume}$.
3. **Trigger Price:** Breakout above/below lowest-volume candle.
4. **VWAP Confirmation:** $\text{Spot} > \text{VWAP}$ for Long, $\text{Spot} < \text{VWAP}$ for Short.
5. **EIA US Inventory Guardrail:** On Wednesdays between **19:45 and 20:15 IST**, skip new entries.

### **4.3 Risk & Exit Rules**
- **Stop Loss:** Low/High of lowest volume candle (minimum 0.35% noise floor).
- **Target 1:** **1:2 Risk-to-Reward (Book 50%)**, then move Stop Loss to Cost.
- **Runner (50%):** Trailed via **10 EMA** on confirmed 5-minute candle close.
- **EOD Exit:** 22:30 IST.

---

## 5. REST Endpoints & Telegram Commands

- `GET /api/v1/strategy/commodity/bullion/status` — Bullion state, active trades, DXY macro status.
- `GET /api/v1/strategy/commodity/energy/status` — Energy state, active trades, VWAP levels.
- `POST /api/v1/strategy/commodity/bullion/cycle` — Force 15m bullion cycle evaluation.
- `POST /api/v1/strategy/commodity/energy/cycle` — Force 5m energy cycle evaluation.
- Telegram command `/commodity` outputs live bullion & energy signals.
