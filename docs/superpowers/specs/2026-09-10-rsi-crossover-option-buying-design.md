# Technical Specification: NIFTY 5m vs 15m RSI Crossover Option Buying Strategy

## 1. Overview
The **NIFTY 5m vs 15m RSI Crossover Strategy** is an automated intraday option buying strategy. It continuously monitors the 5-minute and 15-minute 14-period Relative Strength Index (RSI) on the NIFTY 50 index (`NSE:10576`).

When a crossover occurs, the bot enters an At-The-Money (ATM) Option Buy (CE or PE) for the current weekly expiry. The trade is held until an opposing RSI crossover occurs or until the 15:05 IST auto-square-off time. A strict limit of **1 trade per day** is enforced.

---

## 2. Core Rules & Parameters

### 2.1 Universe & Timeframes
- **Underlying**: NIFTY 50 Index (`NSE`, Token: `10576`).
- **Timeframes**: 5-Minute and 15-Minute candles.
- **Indicator**: RSI(14) calculated on Close prices.

### 2.2 Schedule & Timing (Asia/Kolkata)
- **09:15:00 IST**: Daily strategy state reset (trade count cleared, open position cleared).
- **09:46:10 IST**: Strategy begins active evaluation (first candle check after 09:45 candle close).
- **09:46:10 to 15:00:10 IST**: Evaluates every 5 minutes on candle close (at 10s offset past boundary: 09:46, 09:50, 09:55, 10:00 ... 15:00).
- **15:05:10 IST**: Intraday Auto-Square-Off for any open position.
- **After 15:05 IST**: No new positions or evaluations.

### 2.3 Crossover Definitions (Candle-Close Confirmed)
Let $RSI_{5m}[t]$ and $RSI_{15m}[t]$ be the latest completed candle values, and $RSI_{5m}[t-1]$ and $RSI_{15m}[t-1]$ be the previous candle values.

- **Bullish Crossover (BUY ATM CE)**:
  $$RSI_{5m}[t-1] \le RSI_{15m}[t-1] \quad \text{AND} \quad RSI_{5m}[t] > RSI_{15m}[t]$$
- **Bearish Crossover (BUY ATM PE)**:
  $$RSI_{5m}[t-1] \ge RSI_{15m}[t-1] \quad \text{AND} \quad RSI_{5m}[t] < RSI_{15m}[t]$$

### 2.4 Trade Management & Exits
- **Max Trades Per Day**: Exactly 1 trade per day. Once a trade is executed (and exited), no new trade is taken until the next trading day.
- **Strike Selection**: Current ATM Strike rounded to nearest 50 (e.g. Nifty LTP = 22,430 $\rightarrow$ 22,450 strike).
- **Expiry**: Current Weekly Expiry (resolved dynamically from Shoonya Option Chain).
- **Position Exit Rules**:
  - If holding **CE**: Exit on Bearish Crossover ($RSI_{5m}$ crosses below $RSI_{15m}$).
  - If holding **PE**: Exit on Bullish Crossover ($RSI_{5m}$ crosses above $RSI_{15m}$).
  - **15:05:10 IST Auto-Square-Off**: Unconditional exit of active position at market.

---

## 3. Architecture & Components

```
+-------------------------------------------------------------+
|                RsiCrossoverScheduler                        |
|  - 09:15 Daily Reset                                       |
|  - 09:46 - 15:00 Every 5-min evaluation                     |
|  - 15:05 EOD Auto Square-off                                |
+------------------------------+------------------------------+
                               |
                               v
+-------------------------------------------------------------+
|              RsiCrossoverStrategyService                    |
|  - Fetches 5m & 15m Candles via ShoonyaMarketDataService    |
|  - Calculates RSI(14) series via TechnicalAnalysisService   |
|  - Detects Strict Crossover                                 |
|  - State: Idle / In_Position / Done_For_Day                 |
|  - Routes Orders (Paper / Live) via ShoonyaOrderService     |
|  - Sends Telegram Notifications via TelegramService         |
+-------------------------------------------------------------+
```

### 3.1 `RsiCrossoverStrategyService`
- **State Fields**:
  - `boolean tradeExecutedToday`: set to `true` upon first entry; prevents subsequent entries.
  - `RsiCrossoverPosition currentPosition`: stores active trade details (option type, strike, entry premium, timestamp, quantity, tradeId).
  - `List<RsiCrossoverPosition> tradeHistory`: daily completed trades record.
- **Methods**:
  - `runCycle()`: Main execution cycle called every 5 minutes.
  - `evaluateEntry(double rsi5Prev, double rsi5Curr, double rsi15Prev, double rsi15Curr, double niftyLtp)`
  - `evaluateExit(double rsi5Prev, double rsi5Curr, double rsi15Prev, double rsi15Curr)`
  - `executeSquareOff(String reason)`: Closes active position.
  - `resetDaily()`: Resets `tradeExecutedToday = false` and clears state at 09:15.

### 3.2 `RsiCrossoverScheduler`
- Annotated with `@Component` / `@Service` and `@Scheduled`.
- Triggers:
  1. `cron = "0 15 9 ? * MON-FRI"`: invokes `service.resetDaily()`.
  2. `cron = "10 46,50,55 9 ? * MON-FRI"`: morning 09:46 - 09:55 window.
  3. `cron = "10 */5 10-14 ? * MON-FRI"`: 10:00 to 14:55 window.
  4. `cron = "10 0 15 ? * MON-FRI"`: final 15:00 evaluation.
  5. `cron = "10 5 15 ? * MON-FRI"`: 15:05 EOD auto-square-off.

### 3.3 Models & DTOs
- `RsiCrossoverPosition`:
  - `String tradeId`
  - `String symbol` (e.g. `NIFTY24OCT22500CE`)
  - `String optionType` (`CE` or `PE`)
  - `BigDecimal strike`
  - `BigDecimal entryPrice`
  - `BigDecimal exitPrice`
  - `int quantity`
  - `Instant entryTime`
  - `Instant exitTime`
  - `String exitReason` (`RSI_REVERSAL`, `EOD_SQUARE_OFF`)
  - `BigDecimal pnl`
  - `boolean isClosed`

---

## 4. Configuration Properties (`application.properties`)

```properties
# NIFTY 5m vs 15m RSI Crossover Strategy
trading-bot.strategy.rsi-crossover.enabled=${RSI_CROSSOVER_ENABLED:true}
trading-bot.strategy.rsi-crossover.scheduler-enabled=${RSI_CROSSOVER_SCHEDULER_ENABLED:true}
trading-bot.strategy.rsi-crossover.auto-execute=${RSI_CROSSOVER_AUTO_EXECUTE:false}
trading-bot.strategy.rsi-crossover.lots=${RSI_CROSSOVER_LOTS:1}
trading-bot.strategy.rsi-crossover.lot-size=${RSI_CROSSOVER_LOT_SIZE:65}
trading-bot.strategy.rsi-crossover.rsi-period=${RSI_CROSSOVER_PERIOD:14}
trading-bot.strategy.rsi-crossover.telegram-alerts=${RSI_CROSSOVER_TELEGRAM_ALERTS:true}
```

---

## 5. Telegram Alert Formats

1. **Option Buy Entry Alert**:
   ```
   🚀 [RSI CROSSOVER] NIFTY OPTION BUY FILLED
   • Direction: BULLISH (5m RSI crossed above 15m RSI)
   • Instrument: NIFTY 22450 CE (Weekly)
   • Entry Premium: ₹142.50
   • 5m RSI: 58.4 (Prev: 46.2) | 15m RSI: 51.2 (Prev: 50.8)
   • Lots: 1 (65 Qty)
   • Time: 09:46 IST
   ```

2. **Reversal Exit Alert**:
   ```
   🏁 [RSI CROSSOVER] TRADE EXITED
   • Reason: RSI Reversal (5m crossed below 15m)
   • Instrument: NIFTY 22450 CE
   • Entry: ₹142.50 | Exit: ₹188.00
   • P&L: +₹2,957.50 (+31.9%)
   • Holding Time: 45 mins
   ```

3. **EOD 15:05 Auto-Square-Off Alert**:
   ```
   ⏰ [RSI CROSSOVER] EOD 15:05 AUTO SQUARE-OFF
   • Instrument: NIFTY 22450 CE
   • Exit Premium: ₹165.20
   • P&L: +₹1,475.50 (+15.9%)
   ```

---

## 6. Testing & Quality Strategy
- **Unit Tests (`RsiCrossoverStrategyServiceTest`)**:
  - Test Bullish crossover triggers CE buy.
  - Test Bearish crossover triggers PE buy.
  - Test 1 trade/day enforcement (ignores second crossover after first trade completed).
  - Test Reversal exit on opposing crossover.
  - Test 15:05 EOD square-off.
  - Test No-trade if no crossover occurred.
- **Scheduler Test (`RsiCrossoverSchedulerTest`)**:
  - Test scheduler wiring and execution boundaries.
