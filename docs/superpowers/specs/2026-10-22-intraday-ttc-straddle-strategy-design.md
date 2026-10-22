# Intraday Double-Session Straddle Strategy (1 & 0 NS 916 TTC) Specification

**Date:** 2026-10-22  
**Target Module:** `com.tradingbot.strategy.ttcstraddle`  
**Execution Bus Strategy ID:** `INTRADAY_STRADDLE`  

---

## 1. Executive Summary & Core Concept

The **1 & 0 NS 916 TTC Strategy** is an automated, clock-driven, non-directional option selling system based on the framework taught at Traders Conclave 2026 (Atul Jain / IBBM):
* **No Charts / No Indicators:** Purely price, time, and volatility-driven.
* **Double-Session Execution:**
  * **Session 1 (Morning, 09:22 – 10:16/10:30 IST):** Captures morning opening implied volatility crush and rapid theta decay.
  * **Mid-Day Break (10:30 – 14:17 IST):** Sits in cash during European open whipsaws and lunch-hour chop.
  * **Session 2 (Afternoon, 14:17 – 15:35 IST):** Captures the steep afternoon theta collapse before market close.
* **Margin Optimization & Black-Swan Shield:** Buys deep OTM protection wings at 09:16 AM (6 minutes before selling), reducing broker margin requirements from ~₹1.3 Lakhs/lot to ~₹50,000–₹55,000/lot.
* **Hard Stop Loss:** 50% SL on each sold leg independently. When SL is hit, exit that leg immediately with zero re-entry during that session.
* **Hard Daily Loss Kill-Switch:** Maximum daily loss latch (default ₹8,000 on 10 lots) to prevent double-whipsaw drawdown.

---

## 2. Trading Calendar & Instrument Schedule

Based on statistical 3-year backtesting across all 5-minute sessions, Thursday Sensex 0-DTE is excluded due to violent gamma whipsaws:

| Day of Week | Instrument | Segment | Contract Expiry | Target Expiry Mode | Active Window |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Monday** | **NIFTY** | `NFO` | Next-day Tuesday expiry | **1 DTE** | 09:16 – 15:35 IST |
| **Tuesday** | **NIFTY** | `NFO` | Same-day Tuesday expiry | **0 DTE** | 09:16 – 15:35 IST |
| **Wednesday**| **SENSEX** | `BFO` | Next-day Thursday expiry | **1 DTE** | 09:16 – 15:35 IST |
| **Thursday** | *OFF* | — | — | Cash Protected | No Trades |
| **Friday** | *OFF* | — | — | Cash Protected | No Trades |

*(Configurable: Thursday Sensex can be enabled via `trading-bot.strategy.intraday-straddle.thursday-enabled=false`)*.

---

## 3. Clock-Driven Execution Workflow

```
[09:15:00] Daily State Reset & Connectivity Check
     │
[09:16:00] BUY Protection Wings (Long CE & Long PE)
     │      • NIFTY: ATM ± 1,000 pts (20 strikes out)
     │      • SENSEX: Nearest OTM strike with premium >= ₹5.00
     │
[09:22:00] SELL Morning ATM Straddle (Short CE & Short PE)
     │      • Calculate ATM strike where |P_CE - P_PE| is minimized
     │      • Set 50% SL on each leg independently: SL = Entry * 1.50
     │
[09:22 – 10:16] Monitor 50% SL breaches on 10-second loop
     │      • If CE touched SL -> Exit CE, keep PE running.
     │      • If PE touched SL -> Exit PE, keep CE running.
     │
[10:16:00] SQUARE OFF Morning Short Straddle (NIFTY @ 10:16, SENSEX @ 10:30)
     │      • Close surviving short legs
     │      • KEEP Long Protection Wings OPEN
     │
[10:16 – 14:17] MID-DAY STANDBY (Cash Protected)
     │
[14:17:00] SELL Afternoon Fresh ATM Straddle
     │      • Rescan spot and sell fresh ATM CE & ATM PE
     │      • Set 50% SL on each leg independently: SL = NewEntry * 1.50
     │
[14:17 – 15:35] Monitor 50% SL breaches on 10-second loop
     │
[15:35:00] FINAL HARD EXIT: Close ALL 4 Open Legs (100% Flat EOD)
```

---

## 4. Risk Controls & Institutional Failsafes

1. **50% Leg Stop Loss**:
   * Evaluated continuously on the 10-second ticker loop.
   * SL Trigger price = $\text{Entry Price} \times 1.50$.
   * Exited with `SL-LMT` (trigger with 2% buffer) or `MKT` fallback.
2. **Hard Daily Loss Kill-Switch (`maxDailyLoss = ₹8,000.0`)**:
   * If cumulative realized + unrealized M2M loss exceeds the threshold, the bot **immediately executes market square-off of all 4 legs** and sets `killSwitchTripped = true` for the rest of the day.
3. **Market Regime Pre-Flight Filters**:
   * **VIX Guard**: Block entries if India VIX $< 11.5$ (insufficient premium) or $> 22.0$ (extreme volatility).
   * **Opening Gap Guard**: Block entries if NIFTY opening gap $> 0.85\%$ (trending day risk).
4. **Broker Agnostic Reactive Bus Integration**:
   * Emits standard `TradeSignal` objects to `ReactiveSignalEventBus`.
   * Executable on **Zerodha Kite** (`ZERODHA_STRADDLE_MODE=PAPER/LIVE`) and **Shoonya** (`SHOONYA_STRADDLE_MODE=PAPER/LIVE`).
5. **Telegram Command Listener**:
   * `/straddle_status`: Sends live M2M P&L, strike details, and SL levels.
   * `/straddle_exit`: Emergency manual kill switch to force-close all open straddle legs from Telegram.

---

## 5. Configuration Properties

```properties
trading-bot.strategy.intraday-straddle.enabled=${INTRADAY_STRADDLE_ENABLED:true}
trading-bot.strategy.intraday-straddle.scheduler-enabled=${INTRADAY_STRADDLE_SCHEDULER_ENABLED:true}
trading-bot.strategy.intraday-straddle.lots=${INTRADAY_STRADDLE_LOTS:1}
trading-bot.strategy.intraday-straddle.max-daily-loss=${INTRADAY_STRADDLE_MAX_DAILY_LOSS:8000.0}
trading-bot.strategy.intraday-straddle.sl-percentage=${INTRADAY_STRADDLE_SL_PCT:50.0}
trading-bot.strategy.intraday-straddle.thursday-enabled=${INTRADAY_STRADDLE_THURSDAY_ENABLED:false}
trading-bot.strategy.intraday-straddle.telegram-alerts=${INTRADAY_STRADDLE_TELEGRAM_ALERTS:true}
trading-bot.strategy.intraday-straddle.state-file=${INTRADAY_STRADDLE_STATE_FILE:./data/straddle-state.json}
```
