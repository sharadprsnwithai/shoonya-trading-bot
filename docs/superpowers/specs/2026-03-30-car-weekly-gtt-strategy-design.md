# Cumulative Average Reversal (CAR) Weekly GTT Strategy Specification

**Date:** 2026-03-30  
**Branch:** `feature/car-weekly-gtt`  
**Status:** DRAFT / UNDER REVIEW  
**Authors:** Trading Bot Core Engineering Team  

---

## 1. Executive Summary & Strategy Overview

### 1.1 Core Concept
The **Cumulative Average Reversal (CAR) Weekly GTT Strategy** is a disciplined, low-frequency position accumulation and swing-trading framework designed for large-cap Indian equities. It operates on a weekly cycle (Sunday review) without requiring intraday screen time or manual trade micro-management.

### 1.2 Core Rules & Principles
1. **Capital Unit Allocation (40 Equal Units):**
   - Total trading capital is partitioned into exactly **40 units**:
     $$\text{UNIT} = \frac{\text{Total Capital}}{40}$$
   - No single buy order ever exceeds 1 unit of capital.
   - High-priced stocks where $\text{Price} > \text{UNIT}$ receive a minimum quantity of 1 share via $\lceil \text{UNIT} / \text{Price} \rceil$.
2. **Universe Definition:**
   - **NIFTY 100 Index** (NIFTY 50 + NIFTY Next 50) $\cup$ **Current Portfolio Holdings**.
   - Held stocks remain in the evaluation universe even if they drop out of the index during semi-annual rebalancing.
3. **Cumulative Average Reversal (CAR) Signal:**
   - Anchor Day $H$: The day of the 52-week (252 trading days) highest daily close.
   - Cumulative average of closes from day $H$ to day $t$:
     $$\text{CumAvg}(t) = \frac{1}{t - H + 1} \sum_{i=H}^{t} \text{Close}(i)$$
   - **CAR-Positive Condition:** Strictly **10 consecutive trading days** of rising cumulative average ($\text{CumAvg}(t) > \text{CumAvg}(t-1)$ for all $t \in [T-9, T]$).
4. **Weekly Trigger & Order Placement:**
   - $\text{Trigger Price} = \max(\text{High}_{\text{Mon}}, \text{High}_{\text{Tue}}, \dots, \text{High}_{\text{Fri}})$.
   - $\text{Limit Price} = \text{Trigger Price} + 0.10$ (aligned to ₹0.05 NSE tick size).
   - $\text{Order Quantity} = \lceil \text{UNIT} / \text{Trigger Price} \rceil$.
   - Orders are placed as GTT (Good Till Triggered) buy orders valid for the coming trading week.
5. **Position Accumulation & Averaging:**
   - Stocks already held that remain CAR-positive continue receiving 1 unit of buy GTT every week, automatically averaging down in pullbacks and adding to winners.
6. **Exit Strategy (No Stop-Loss, +6.28% Profit Target):**
   - Fixed target of **+6.28%** calculated against the position's weighted average purchase price:
     $$\bar{P} = \frac{\sum (P_i \times Q_i)}{\sum Q_i}, \quad \text{Target Price} = \bar{P} \times 1.0628$$
   - No stop loss. Losing positions are accumulated as long as CAR is positive, or held until the next reversal cycle.
7. **Compounding Engine:**
   - Realized profits are reinvested into the capital pool, expanding the size of $\text{UNIT}$ over time:
     $$\text{UNIT}_{\text{new}} = \frac{\text{Initial Capital} + \sum \text{Realized PnL}}{40}$$

---

## 2. Mathematical Foundation & CAR Computation

```
 Price / 52W High
      │
      ▼  (Anchor Day H: 52-Week Highest Close)
      ●──────────────────────────┐
       \                         │
        \                        │
         \                       │
          \                      ▼  CumAvg Falling (Downtrend / Correction)
           \              ─────────────
            \            /
             \          /  <─── CAR-Positive Recovery:
              ●────────●        CumAvg(t) rising for 10 consecutive trading days!
              │
              └─── Trigger = Previous Week's High
```

### 2.1 Incremental Identity
$$\text{CumAvg}(t) > \text{CumAvg}(t-1) \iff \text{Close}(t) > \text{CumAvg}(t-1)$$
This identity allows $O(N)$ real-time verification across the entire Nifty 100 universe.

### 2.2 Re-anchoring on New 52-Week High
If a stock prints a new 52-week closing high at time $t_{\text{new}} > H$:
- Anchor resets to $t_{\text{new}}$.
- Day counter resets to 1 ($\text{CumAvg}(t_{\text{new}}) = \text{Close}(t_{\text{new}})$).
- CAR counter resets to 0 and must build a fresh 10-day rising streak from the new peak.

---

## 3. Weekly Sunday Routine Workflow

Every Sunday at 10:00 IST (and on-demand via REST/Scheduler):

```
                        ┌─────────────────────────────────┐
                        │      Sunday Routine Starts      │
                        └────────────────┬────────────────┘
                                         │
                                         ▼
                        ┌─────────────────────────────────┐
                        │  Step 1: Reconcile Prior GTTs   │
                        │ • Filled GTTs -> Update Holdings│
                        │ • Unfilled GTTs -> Keep/Modify/ │
                        │   Cancel based on CAR state     │
                        └────────────────┬────────────────┘
                                         │
                                         ▼
                        ┌─────────────────────────────────┐
                        │  Step 2: Compute CAR on Universe│
                        │  (Nifty 100 + Current Holdings) │
                        │  • Find 52W High Anchor         │
                        │  • Calculate 10-day CumAvg      │
                        └────────────────┬────────────────┘
                                         │
                                         ▼
                        ┌─────────────────────────────────┐
                        │  Step 3: Capital & Unit Sizing  │
                        │  • Available Units = 40 - Open  │
                        │  • Compute Trigger & Quantity   │
                        └────────────────┬────────────────┘
                                         │
                                         ▼
                        ┌─────────────────────────────────┐
                        │  Step 4: Dispatch GTT Orders    │
                        │  • Zerodha Native Kite GTT API  │
                        │  • Shoonya / Local Watcher GTT  │
                        └────────────────┬────────────────┘
                                         │
                                         ▼
                        ┌─────────────────────────────────┐
                        │  Step 5: Telegram Summary Alert │
                        │  • Holdings, Targets, New GTTs  │
                        └─────────────────────────────────┘
```

### 3.1 Step-by-Step State Transitions for Pending GTTs
| Status on Sunday | CAR Signal on Sunday | Action Required |
|---|---|---|
| **Triggered during week** | Any | Shares added to holdings. Recompute weighted average price $\bar{P}$ and update +6.28% sell target GTT. |
| **Not triggered** | **CAR-Positive** | **Modify GTT:** Update trigger price to the *latest completed week's high* and recompute quantity. |
| **Not triggered** | **CAR Not Positive** | **Delete / Cancel GTT:** Order is removed so stale triggers are never left hanging in market. |
| **No GTT active** | **CAR-Positive** | **Place Fresh Buy GTT:** Trigger at last week's high, limit = trigger + ₹0.10, qty = $\lceil \text{UNIT} / \text{Trigger} \rceil$. |

---

## 4. Multi-Broker GTT Execution Architecture

```
                                 ┌────────────────────────────────┐
                                 │     CarWeeklyGttService        │
                                 │  (Universe, CAR Engine, Sizer) │
                                 └───────────────┬────────────────┘
                                                 │
                                                 ▼
                                 ┌────────────────────────────────┐
                                 │     GttExecutionGateway        │
                                 │  (Unified GTT Order Interface) │
                                 └───────┬────────────────┬───────┘
                                         │                │
                        ┌────────────────┘                └────────────────┐
                        ▼                                                  ▼
         ┌───────────────────────────────┐                  ┌───────────────────────────────┐
         │     ZerodhaGttGateway         │                  │      ShoonyaGttGateway        │
         │  (Kite /gtt/triggers API)     │                  │   (Local Watcher + Broker API)│
         └──────────────┬────────────────┘                  └──────────────┬────────────────┘
                        │                                                  │
                        ▼                                                  ▼
         ┌───────────────────────────────┐                  ┌───────────────────────────────┐
         │   Zerodha Broker Exchange     │                  │    Shoonya Broker Exchange    │
         └───────────────────────────────┘                  └───────────────────────────────┘
```

### 4.1 Zerodha Native Kite GTT Integration
* Uses Zerodha's `/gtt/triggers` REST API:
  - `type`: `single`
  - `condition`: `{"exchange":"NSE", "tradingsymbol":"RELIANCE", "trigger_values":[triggerPrice]}`
  - `orders`: `[{"transaction_type":"BUY", "quantity":qty, "price":limitPrice, "order_type":"LIMIT", "product":"CNC"}]`

### 4.2 Shoonya & Virtual GTT Watcher Engine
* For brokers without native server-side GTTs, `LocalGttWatcherService` tracks daily high-water marks and triggers limit orders automatically when spot breaches the weekly trigger price.

---

## 5. Domain Models & Entities

### 5.1 `CarHolding`
```java
public record CarHolding(
    String symbol,
    long totalQuantity,
    BigDecimal averageBuyPrice,
    BigDecimal targetPrice,       // averageBuyPrice * 1.0628
    int accumulatedUnits,         // Number of 1-unit tranches purchased
    Instant firstEntryTime,
    Instant lastEntryTime
)
```

### 5.2 `CarGttOrder`
```java
public record CarGttOrder(
    String gttId,
    String broker,
    String symbol,
    GttOrderType type,            // BUY or SELL_TARGET
    BigDecimal triggerPrice,
    BigDecimal limitPrice,
    int quantity,
    GttStatus status,             // PENDING, TRIGGERED, CANCELLED, REJECTED
    LocalDate weekStartDate,      // Monday of the active week
    Instant createdAt
)
```

### 5.3 `CarSignal`
```java
public record CarSignal(
    String symbol,
    boolean isCarPositive,
    int consecutivePositiveDays,
    BigDecimal 52WeekHighClose,
    LocalDate 52WeekHighDate,
    BigDecimal latestClose,
    BigDecimal previousWeekHigh,
    BigDecimal triggerPrice,
    int plannedQuantity,
    String statusReason
)
```

---

## 6. Configuration Schema (`application.properties`)

```properties
# ==============================================================================
# Cumulative Average Reversal (CAR) Weekly GTT Strategy
# ==============================================================================
trading-bot.strategy.car-weekly.enabled=true
trading-bot.strategy.car-weekly.total-capital=1000000.0
trading-bot.strategy.car-weekly.num-parts=40
trading-bot.strategy.car-weekly.profit-target-pct=6.28
trading-bot.strategy.car-weekly.trigger-buffer=0.10
trading-bot.strategy.car-weekly.car-positive-days=10
trading-bot.strategy.car-weekly.high-lookback-days=252
trading-bot.strategy.car-weekly.sunday-cron=0 0 10 ? * SUN
trading-bot.strategy.car-weekly.telegram-alerts=true
```

---

## 7. REST API Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/car/run-weekly` | Manually triggers the Sunday CAR scan & GTT reconciliation |
| `GET` | `/api/v1/car/signals` | Lists current CAR status and 10-day streak across all Nifty 100 stocks |
| `GET` | `/api/v1/car/holdings` | Returns all active accumulated portfolio holdings with average prices and targets |
| `GET` | `/api/v1/car/gtts` | Returns all active, pending, and triggered GTT orders |
| `GET` | `/api/v1/car/performance` | Returns total capital, invested capital, available units, and realized compounding PnL |

---

## 8. Testing & Quality Assurance Plan

1. **`CarCalculatorTest`:**
   - Mathematical unit test validating the exact Relaxo worked example from the strategy:
     - Closes: `448.50`, `445.95`, `445.60` $\to$ Cumulative Averages: `448.50`, `447.23`, `446.68`.
   - Tests 52-week close-based anchor detection.
   - Tests 10-consecutive-day streak calculation.
2. **`CarGttReconciliationTest`:**
   - Tests Sunday reconciliation logic:
     - Modifying pending GTT when CAR remains positive.
     - Cancelling pending GTT when CAR turns negative.
     - Creating fresh GTT for new CAR-positive stocks.
3. **`CarCompoundingTest`:**
   - Verifies that realized gains from a +6.28% target fill increase total capital and grow the $\text{UNIT}$ size.
4. **Code Quality:**
   - Spotless formatting clean (`./gradlew spotlessApply`), SpotBugs analysis clean, and 100% test pass.
