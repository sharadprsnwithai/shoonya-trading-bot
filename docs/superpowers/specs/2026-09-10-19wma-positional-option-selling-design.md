# Design Specification: 19-Period Daily WMA Positional Hedged Option Selling Strategy

## 1. Overview & Strategy Objective

The **19-Period Daily WMA Positional Hedged Option Selling Strategy** is an institutional-grade, trend-following credit spread system on NIFTY 50 (`NSE:10576`). It harvests multi-week theta decay and directional drift while completely eliminating overnight tail risk through a structural **2.0% OTM long hedge leg**.

### Key Value Propositions:
1. **Macro Trend Alignment**: Uses a 19-period Weighted Moving Average (WMA) on daily spot closes (~1 calendar month cycle) to filter out intraday market noise.
2. **Dynamic Gamma Evasion**: Automatically routes orders to the **Current Month Expiry** on or before the 15th of the month, and switches to the **Next Month Expiry** after the 15th to avoid near-expiry gamma convexity and pin risk.
3. **Strict Premium & Delta Discipline**: Targets short strikes with Delta between **0.20 and 0.25** and an entry premium capped at **$\le ₹105.00$** (falling back to lower deltas if market IV expands).
4. **Tail-Risk Immunity & Margin Relief**: Buys a 2.0% OTM option leg simultaneously, capping maximum loss and reducing margin requirements by **~65%–68%** (from ~₹1,20,000 to ~₹35,000 per lot).
5. **Positional State Durability**: Persists position state to disk (`data/wma_positional_state.json`) so multi-day/multi-week positions seamlessly survive service and Docker container restarts.

---

## 2. Specification & Parameters

| Parameter | Specification | Default Value | Configuration Key |
| :--- | :--- | :--- | :--- |
| **Underlying Asset** | NIFTY 50 Index | `NSE:10576` | `trading-bot.strategy.daily-wma.symbol` |
| **Trend Indicator** | 19-Period WMA on Daily Spot Close | `19` | `trading-bot.strategy.daily-wma.wma-period` |
| **Daily Evaluation Window** | 09:30:10 AM IST (Mon–Fri) | `09:30:10 IST` | `trading-bot.strategy.daily-wma.eval-cron` |
| **Bullish Bias ($Spot > 19\text{ WMA}$)** | Sell OTM PE + Buy 2% OTM PE Hedge | Bull Put Spread | — |
| **Bearish Bias ($Spot < 19\text{ WMA}$)** | Sell OTM CE + Buy 2% OTM CE Hedge | Bear Call Spread | — |
| **Target Delta Range** | 0.20 to 0.25 | `0.22` | `trading-bot.strategy.daily-wma.target-delta` |
| **Max Entry Premium** | Capped at $\le ₹105.00$ | `105.0` | `trading-bot.strategy.daily-wma.max-entry-premium` |
| **Dynamic Expiry Routing** | Day $\le 15$: Current Month / Day $> 15$: Next Month | `15` | `trading-bot.strategy.daily-wma.expiry-switch-day` |
| **Long Hedge Leg** | 2.0% OTM further from Short Strike | `2.0%` | `trading-bot.strategy.daily-wma.hedge-otm-percent` |
| **Structural Stop Loss** | 100% above Entry Premium ($2.0 \times \text{Entry}$) | `100.0%` | `trading-bot.strategy.daily-wma.stop-loss-percent` |
| **Re-Entry Rule** | If SL hit & 19 WMA trend remains valid, re-enter at $\le ₹105$ | `true` (Max 1/day) | `trading-bot.strategy.daily-wma.allow-reentry` |
| **Trend Reversal Exit** | Exit at 09:30 AM if spot crosses 19 WMA | `true` | — |
| **Expiry Closeout** | Force square-off at 15:15:10 PM IST on expiry day | `15:15:10 IST` | `trading-bot.strategy.daily-wma.squareoff-cron` |
| **Execution Mode** | Live Broker vs Paper Trading | `PAPER` | `trading-bot.strategy.daily-wma.mode` |
| **Trading Capital / Lots** | Number of lots to trade | `1` lot (65 qty) | `trading-bot.strategy.daily-wma.lots` |

---

## 3. Dynamic Expiry Routing & Strike Selection Engine

### A. Expiry Date Resolution Logic
```
                      [Trade Date (Day of Month)]
                                   │
                 ┌─────────────────┴─────────────────┐
                 ▼                                   ▼
          [Day <= 15th]                        [Day > 15th]
                 │                                   │
                 ▼                                   ▼
    [Calculate Last Thursday             [Calculate Last Thursday
     of CURRENT Calendar Month]           of NEXT Calendar Month]
                 │                                   │
                 └─────────────────┬─────────────────┘
                                   ▼
                       [Target Expiry Date (YYYY-MM-DD)]
```

### B. Strike Selection with ₹105 Max Premium Constraint
For the resolved target expiry:
1. **Bullish Bias ($S > 19\text{ WMA}$)**:
   - Scan OTM PE strikes ($K < S$) in descending order (step = 50 pts).
   - Evaluate Delta $\Delta \in [0.15, 0.30]$ and Quote LTP $P_{PE}$.
   - Filter candidates where $P_{PE} \le ₹105.00$.
   - Pick the strike closest to target Delta $0.22$.
   - **Hedge Strike**: $K_{PE\_hedge} = \text{round}\left(\frac{K_{PE} \times (1 - 0.02)}{50}\right) \times 50$.
2. **Bearish Bias ($S < 19\text{ WMA}$)**:
   - Scan OTM CE strikes ($K > S$) in ascending order (step = 50 pts).
   - Evaluate Delta $\Delta \in [0.15, 0.30]$ and Quote LTP $P_{CE}$.
   - Filter candidates where $P_{CE} \le ₹105.00$.
   - Pick the strike closest to target Delta $0.22$.
   - **Hedge Strike**: $K_{CE\_hedge} = \text{round}\left(\frac{K_{CE} \times (1 + 0.02)}{50}\right) \times 50$.

---

## 4. Multi-Leg Order Execution & Rollback Flow

```
                      [Generate Hedged Spread Signal]
                                     │
                 ┌───────────────────┴───────────────────┐
                 ▼ (PAPER MODE)                          ▼ (LIVE BROKER MODE)
         [Simulate Fills]                1. Buy 2.0% OTM Hedge Leg First
                 │                          (Guarantees exchange margin relief)
                 │                                       │
                 │                       ┌───────────────┴───────────────┐
                 │                       ▼ (Hedge Filled)                ▼ (Hedge Failed)
                 │               2. Sell Short Leg (ATM/OTM)      [Abort Execution & Log]
                 │                       │
                 │               ┌───────┴───────┐
                 │               ▼ (Short Filled)▼ (Short Failed)
                 │          [Spread Active]  [EMERGENCY ROLLBACK:
                 │               │            Immediately Market Sell Hedge]
                 └───────────────┬───────────────┘
                                 ▼
                     [Persist State to Disk]
                                 │
                     [Send Telegram Alert]
```

---

## 5. Position State Persistence (`DailyWmaPosition`)

To maintain positional integrity across server updates and restarts:
- Active state is stored in `data/wma_positional_state.json`.
- On Spring Boot startup (`@PostConstruct` or `StartupSyncRunner`), the service loads the active position from disk, verifies current broker holdings, and resumes monitoring.

### Position State Schema:
```json
{
  "tradeId": "WMA_20260910_001",
  "symbol": "NIFTY",
  "action": "SELL",
  "optionType": "PE",
  "bias": "BULLISH",
  "entryDate": "2026-09-10",
  "entryTime": "2026-09-10T09:30:10Z",
  "entrySpot": 24850.50,
  "wma19AtEntry": 24620.30,
  "expiryDate": "2026-09-24",
  
  "shortSymbol": "NIFTY24SEP24100PE",
  "shortStrike": 24100.0,
  "shortEntryPrice": 94.50,
  "shortDelta": 0.22,
  "quantity": 65,
  
  "hedgeSymbol": "NIFTY24SEP23600PE",
  "hedgeStrike": 23600.0,
  "hedgeEntryPrice": 12.20,
  "hedgeQuantity": 65,
  
  "netCredit": 82.30,
  "stopLossPrice": 189.00,
  "reEntryCount": 0,
  "isClosed": false,
  "exitDate": null,
  "exitTime": null,
  "exitSpot": null,
  "shortExitPrice": null,
  "hedgeExitPrice": null,
  "realizedPnl": null,
  "exitReason": null
}
```

---

## 6. Lifecycle & Exit Triggers

| Exit Trigger | Timing | Condition | Order Action |
| :--- | :--- | :--- | :--- |
| **1. 19 WMA Trend Reversal** | 09:30:10 AM IST Daily | Active is Bullish & Spot $< 19\text{ WMA}$<br>OR Active is Bearish & Spot $> 19\text{ WMA}$ | Market Close both legs $\rightarrow$ Open opposite spread |
| **2. Structural Stop Loss** | Every 5 mins (09:30–15:30) | Short Leg LTP $\ge 2.0 \times P_{\text{entry}}$ (or $P_{\text{entry}} \times 1.15$ if strict) | Market Close both legs $\rightarrow$ Flag re-entry |
| **3. Expiry Square-Off** | 15:15:10 PM on Expiry Day | `currentDate == expiryDate` and `time >= 15:15:00` | Mandatory Market Close both legs |
| **4. Manual Emergency Exit** | On demand via REST | Triggered via `/api/strategy/daily-wma/close` | Immediate Market Close |

---

## 7. Schedulers & Timers

```java
// 1. Daily Trend & Position Evaluation (Mon-Fri at 09:30:10 IST)
@Scheduled(cron = "10 30 9 * * MON-FRI", zone = "Asia/Kolkata")
public void evaluateDailyTrendCycle()

// 2. Intraday Stop-Loss & Risk Monitor (Every 5 mins from 09:30 to 15:30 IST)
@Scheduled(cron = "10 35-55/5 9 * * MON-FRI", zone = "Asia/Kolkata")
@Scheduled(cron = "10 */5 10-14 * * MON-FRI", zone = "Asia/Kolkata")
@Scheduled(cron = "10 0-10/5 15 * * MON-FRI", zone = "Asia/Kolkata")
public void monitorIntradayStopLoss()

// 3. Mandatory Expiry Day Square-Off (Mon-Fri at 15:15:10 IST)
@Scheduled(cron = "10 15 15 * * MON-FRI", zone = "Asia/Kolkata")
public void checkExpiryDaySquareOff()
```

---

## 8. Telegram Alert Templates

### A. Position Entry Alert
```
⚡ 19 WMA POSITIONAL STRATEGY ENTRY ⚡
━━━━━━━━━━━━━━━━━━━━━━━━━━
Strategy: 19-Period Daily WMA Credit Spread
Direction: BULLISH (Spot > 19 WMA)
Evaluation Spot: ₹24,850.50 | 19 WMA: ₹24,620.30
Expiry: 2026-09-24 (Current Month)

🔴 Short Leg (Delta ~0.22): NIFTY24SEP24100PE @ ₹94.50 (Qty: 65)
🟢 Hedge Leg (2.0% OTM): NIFTY24SEP23600PE @ ₹12.20 (Qty: 65)

Net Credit: ₹82.30 / share (₹5,349.50 / lot)
Max Margin Required: ~₹35,000 (Hedged)
Structural Stop Loss: ₹189.00 (2.0x Entry)
Evaluation Time: 2026-09-10 09:30:10 IST
━━━━━━━━━━━━━━━━━━━━━━━━━━
```

### B. Position Exit Alert
```
🚨 19 WMA POSITIONAL STRATEGY EXIT 🚨
━━━━━━━━━━━━━━━━━━━━━━━━━━
Strategy: 19-Period Daily WMA Credit Spread
Exit Reason: 19WMA_TREND_REVERSAL
Hold Duration: 4 Days, 5 Hours

🔴 Short Leg: NIFTY24SEP24100PE (₹94.50 ➔ ₹18.00) | P&L: +₹4,972.50
🟢 Hedge Leg: NIFTY24SEP23600PE (₹12.20 ➔ ₹1.50)  | P&L: -₹695.50

Total Realized P&L: +₹4,277.00
Exit Spot: ₹25,120.00
Exit Time: 2026-09-14 09:30:10 IST
━━━━━━━━━━━━━━━━━━━━━━━━━━
```

---

## 9. Architectural Components & Files

| Component | Target File | Responsibility |
| :--- | :--- | :--- |
| **Indicator Service** | `TechnicalAnalysisService.java` | TA-Lib `wma(close, 19)` series calculation |
| **Strategy Model** | `DailyWmaPosition.java` | Domain model for hedged positional spread state |
| **Strategy Service** | `DailyWmaStrategyService.java` | Core strategy engine, expiry routing, strike selection $\le ₹105$, multi-leg execution |
| **Scheduler** | `DailyWmaScheduler.java` | Spring `@Scheduled` cron jobs (09:30:10, 5m SL, 15:15:10 Expiry) |
| **REST Controller** | `DailyWmaStrategyController.java` | REST endpoints to view status, history, trigger cycle, or force square-off |
| **Backtest Engine** | `DailyWmaBacktestService.java` | Java backtesting engine for historical simulation |
| **Telegram Notifier** | `TelegramService.java` | Formatted alerts for entry, exit, SL, and expiry closeout |
| **Configuration** | `application.properties`, `.env` | Parameter keys and environment configuration |

---

## 10. Verification Plan

1. **Unit Tests**:
   - `TechnicalAnalysisServiceTest.java`: Validate TA-Lib 19 WMA calculation against reference series.
   - `DailyWmaPositionTest.java`: Verify spread P&L, net credit, and state transitions.
   - `DailyWmaStrategyServiceTest.java`: Test bullish/bearish signal generation, $\le ₹105$ strike filtering, dynamic expiry routing, 2% OTM hedge calculation, and 09:30 AM trend reversal exits.
   - `DailyWmaSchedulerTest.java`: Test cron triggers and execution timing with mock clocks.
   - `DailyWmaBacktestServiceTest.java`: Verify backtest calculations over multi-day candles.
2. **Build & Integration Test**:
   - Full Gradle clean build & rerun test execution: `./gradlew test --rerun-tasks`
   - Application JAR compilation: `./gradlew bootJar`
   - Docker Container verification: `docker build -t sharadprsn/shoonya-trading-bot:latest .`
