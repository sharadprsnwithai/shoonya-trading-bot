# Design Doc: Monthly Asymmetric Put Condor Strategy Subsystem

**Date:** 2026-10-02  
**Status:** Approved for Implementation  
**Target Platform:** Java 21 / Spring Boot 3 (`com.tradingbot`)  
**Data Feeder:** Shoonya API (`ShoonyaMarketDataService` / `ShoonyaOptionChainService`)  
**Execution Broker:** Zerodha Kite Connect API (`ZerodhaBrokerGateway` / `KiteRestClient`)  
**Target Account Size:** ~₹1 Crore (Pledged Collateral Margin)

---

## 1. System Overview & Objectives

The **Monthly Asymmetric Put Condor Subsystem** automates a defined-risk, high-probability positional options trading strategy on **NIFTY 50 index options**. 

It exploits structural **Put Implied Volatility (IV) Skew** to achieve an asymmetric risk-reward ratio (1:8 to 1:10 initial peak potential) with strictly capped initial debit risk ($\approx 1.5\% - 1.8\%$). 

### Core Objectives:
1. **Zero Unlimited Tail Risk:** 100% defined risk on Day 1. No naked short legs, preventing overnight gap catastrophes.
2. **Dynamic Multi-Scenario Adjustments:**
   - **Upside Rally (Adjustment A):** Adds an OTM Bull Put Spread when Nifty rallies $+0.75\%$ (or stays flat past Day 7) to collect $+₹25-30$ points, fully funding the initial condor debit and transforming upside rallies into $\ge 0.00\%$ (Zero Loss / Break-Even).
   - **Sweet Spot Dip (Adjustment B):** Rolls $K_1$ Long Put down 100 points when Spot reaches $K_2$, banking intrinsic cash and elevating the profit trough.
   - **Deep Crash Defense (Adjustment C):** Early exit when Spot drops below $K_4 - 100$, cutting crash losses at $-1.0\%$ to $-1.2\%$.
3. **Dual Execution Modes (`PAPER` vs `LIVE`):** Configurable in `.env` for seamless forward-testing or live Zerodha execution.
4. **NSE Freeze Limit Order Slicing:** Automatically slices orders $> 1,800$ quantity (e.g. 50 lots = 3,250 qty) into compliant sub-orders sent with 200ms spacing and margin priority (Buys before Sells).
5. **State Persistence & Fault Tolerance:** Positions and state are persisted in SQLite (`trading_bot.db`), ensuring full recovery across application restarts.

---

## 2. Configuration & Properties

Properties are managed via `MonthlyPutCondorProperties.java` and bound to `.env` / `application.properties`:

```properties
# Monthly Asymmetric Put Condor Strategy Configuration
trading-bot.strategy.put-condor.enabled=${CONDOR_ENABLED:true}
trading-bot.strategy.put-condor.execution-mode=${CONDOR_EXECUTION_MODE:PAPER}
trading-bot.strategy.put-condor.lots=${CONDOR_LOTS:50}
trading-bot.strategy.put-condor.lot-size=${CONDOR_LOT_SIZE:65}
trading-bot.strategy.put-condor.strike-width=${CONDOR_STRIKE_WIDTH:200}
trading-bot.strategy.put-condor.target-profit-pct=${CONDOR_TARGET_PROFIT_PCT:6.0}
trading-bot.strategy.put-condor.stop-loss-pct=${CONDOR_STOP_LOSS_PCT:3.0}
trading-bot.strategy.put-condor.upside-trigger-pts=${CONDOR_UPSIDE_TRIGGER_PTS:150}
trading-bot.strategy.put-condor.entry-time=${CONDOR_ENTRY_TIME:10:30}
trading-bot.strategy.put-condor.monitor-interval-seconds=${CONDOR_MONITOR_INTERVAL_SECONDS:60}
trading-bot.strategy.put-condor.telegram-alerts=${CONDOR_TELEGRAM_ALERTS:true}
trading-bot.strategy.put-condor.max-freeze-limit=${CONDOR_MAX_FREEZE_LIMIT:1800}
```

---

## 3. State Machine & Lifecycle

The strategy operates a 5-state finite automaton:

```
                      ┌──────────────────────────────────────┐
                      │             STATE 0: IDLE            │
                      └──────────────────┬───────────────────┘
                                         │ Entry Trigger (1st Day 10:30 AM)
                                         ▼
                      ┌──────────────────────────────────────┐
                      │         STATE 1: CONDOR_ACTIVE       │
                      └──────┬───────────┼───────────┬───────┘
                             │           │           │
     Spot >= ATM + 150       │           │           │ Spot <= K2 (24,600)
     (Upside Rally)          │           │           │ (Downside Sweet Spot)
                             ▼           │           ▼
  ┌─────────────────────────────┐        │        ┌─────────────────────────────┐
  │   STATE 2: UPSIDE_FINANCED  │        │        │   STATE 3: SWEET_SPOT_LOCK  │
  └──────────────┬──────────────┘        │        └──────────────┬──────────────┘
                 │                       │                       │
                 │                       │ MTM >= +6.0% (Target) │ Spot <= K4 - 100
                 │                       │ or Expiry Settled     │ (Deep Crash)
                 ▼                       ▼                       ▼
  ┌─────────────────────────────────────────────────────────────────────────────┐
  │                             STATE 4: SQUARED_OFF                            │
  └─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Component Architecture & Package Structure

New components reside in `com.tradingbot.strategy.condor`:

```
src/main/java/com/tradingbot/strategy/condor/
├── config/
│   └── MonthlyPutCondorProperties.java
├── model/
│   ├── PutCondorState.java
│   ├── PutCondorPosition.java
│   ├── PutCondorAdjustmentType.java
│   └── PutCondorCycleHistory.java
├── repository/
│   └── SqlitePutCondorRepository.java
├── service/
│   ├── MonthlyPutCondorService.java
│   └── PutCondorOrderSlicer.java
├── scheduler/
│   └── MonthlyPutCondorScheduler.java
└── controller/
    └── MonthlyPutCondorController.java
```

### Component Roles:
1. **`MonthlyPutCondorProperties`:** Type-safe configuration binder.
2. **`PutCondorPosition`:** JSON-serializable state containing strikes, order IDs, tradingsymbols, entry prices, realized adjustments, and MTM history.
3. **`SqlitePutCondorRepository`:** CRUD operations against SQLite table `put_condor_state` and `put_condor_history`.
4. **`PutCondorOrderSlicer`:** Splits orders exceeding `max-freeze-limit` (1,800 qty) into compliant child orders and enforces Buy-before-Sell sequencing.
5. **`MonthlyPutCondorService`:** Core decision engine, evaluates ticks against MTM thresholds and triggers adjustments or liquidation.
6. **`MonthlyPutCondorScheduler`:** Spring `@Scheduled` cron jobs for morning entry, 60-second tick polling, 15:10 expiry settlement, and daily EOD Telegram digest.
7. **`MonthlyPutCondorController`:** REST API exposing `/api/strategy/put-condor/status`, `/enter`, `/adjust-upside`, `/adjust-sweet-spot`, and `/exit`.

---

## 5. Execution Logic & Order Routing

### 5.1 Entry Basket Sequence
1. Compute Base ATM: $K_{base} = \text{round}(\text{Nifty Spot} / 100) \times 100$.
2. Compute Strikes:
   - $K_1 = K_{base} - 200$ (Long Put 1)
   - $K_2 = K_{base} - 400$ (Short Put 1)
   - $K_3 = K_{base} - 600$ (Short Put 2)
   - $K_4 = K_{base} - 800$ (Long Put 2)
3. Resolve Tradingsymbols via Shoonya symbol registry or Zerodha format (`NIFTY<YY><MMM><STRIKE>PE`).
4. **Routing:**
   - **Step 1:** Submit BUY slices for $K_1$ and $K_4$. Await confirmation in order book.
   - **Step 2:** Submit SELL slices for $K_2$ and $K_3$.
5. Save state to SQLite and broadcast entry message via `TelegramService`.

### 5.2 Liquidation Sequence (Square-Off)
1. **Step 1:** Submit BUY slices for all active Short legs ($K_2, K_3$, and $K_{up\_sell}$).
2. **Step 2:** Submit SELL slices for all active Long hedge legs ($K_1, K_4$, and $K_{up\_buy}$).
3. Update state to `SQUARED_OFF` and send PnL report to Telegram.

---

## 6. REST API Endpoints

| Endpoint | Method | Response | Purpose |
| :--- | :---: | :--- | :--- |
| `/api/strategy/put-condor/status` | `GET` | `ResponseEntity<PutCondorPosition>` | View active state, MTM, and strike details. |
| `/api/strategy/put-condor/enter` | `POST` | `ResponseEntity<String>` | Force cycle deployment at current spot. |
| `/api/strategy/put-condor/adjust-upside` | `POST` | `ResponseEntity<String>` | Manually trigger Adjustment A. |
| `/api/strategy/put-condor/adjust-sweet-spot` | `POST` | `ResponseEntity<String>` | Manually trigger Adjustment B. |
| `/api/strategy/put-condor/exit` | `POST` | `ResponseEntity<String>` | Emergency square-off of all active legs. |
| `/api/strategy/put-condor/history` | `GET` | `ResponseEntity<List<PutCondorCycleHistory>>` | View historical monthly performance. |

---

## 7. Testing Strategy

1. **Unit Testing (`MonthlyPutCondorServiceTest.java`):**
   - Strike calculation and rounding logic.
   - State machine transition triggers (Upside rally, Sweet spot dip, Deep crash guard).
   - Order slicing calculation for various lot sizes (5, 10, 50, 70 lots).
2. **Integration Testing (`MonthlyPutCondorControllerTest.java`):**
   - REST API endpoints status, manual entry, and emergency exit.
3. **Paper Forward-Testing:**
   - Verify 60-second polling and Shoonya LTP resolution without live order placement.
