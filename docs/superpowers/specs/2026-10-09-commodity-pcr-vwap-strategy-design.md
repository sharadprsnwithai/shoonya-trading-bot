# Design Spec: 1:30 PM MCX Commodity Directional PCR & VWAP Breakout Strategy

**Target Strategy Package**: `com.tradingbot.strategy.commodity`  
**Target Exchange**: `MCX` (Multi Commodity Exchange of India)  
**Supported Commodities**: `GOLD` / `GOLDM`, `SILVER` / `SILVERM`, `CRUDEOIL` / `CRUDEOILM`  
**Execution Instrument**: MCX Mini Futures  

---

## 1. Overview & Business Intent

This strategy trades high-probability intraday breakouts on MCX Commodities (`GOLD`, `SILVER`, `CRUDEOIL`). It establishes a macro directional bias at **1:30 PM IST** using Open Interest Put-Call Ratio (PCR) from the options chain, monitors 15-minute price action relative to intraday Volume-Weighted Average Price (VWAP), and enters trades when price breaks out of the crossover candle extreme with a **1:2 Risk-to-Reward (RR)** target anchored to VWAP.

---

## 2. Strategy Rules & Math Specification

### 2.1 Directional Bias Determination (1:30 PM IST)
At 1:30 PM IST every weekday, the strategy queries the near-month option chain for each configured commodity:
* $\text{PCR} = \frac{\sum \text{Put Open Interest (ATM } \pm 5 \text{ strikes)}}{\sum \text{Call Open Interest (ATM } \pm 5 \text{ strikes)}}$

**Directional Classification**:
* $\text{PCR} \ge 1.15 \implies \mathbf{BULLISH}$ (Significant put accumulation / support)
* $\text{PCR} \le 0.85 \implies \mathbf{BEARISH}$ (Significant call accumulation / resistance)
* $0.85 < \text{PCR} < 1.15 \implies \mathbf{NEUTRAL}$ (Indecision / sideways chop; no trades for the day)

### 2.2 15-Minute VWAP Crossover Check (13:30 – 22:30 IST)
Every 15 minutes, the strategy evaluates the latest completed 15-minute candle against the intraday VWAP:
* **Bullish Setup Trigger**:
  * Bias is $\mathbf{BULLISH}$.
  * Previous 15m candle closed below or touched VWAP, and current 15m candle closes strictly **above VWAP** ($\text{Close} > \text{VWAP}$).
  * **Arm Trigger**: Record $\text{Trigger High} = \text{High of Crossover Candle}$.
* **Bearish Setup Trigger**:
  * Bias is $\mathbf{BEARISH}$.
  * Previous 15m candle closed above or touched VWAP, and current 15m candle closes strictly **below VWAP** ($\text{Close} < \text{VWAP}$).
  * **Arm Trigger**: Record $\text{Trigger Low} = \text{Low of Crossover Candle}$.

### 2.3 Breakout Execution & Risk Management (1:2 RR)
* **Long Entry Condition**:
  * State is `ARMED_LONG`.
  * Live $\text{LTP} \ge \text{Trigger High}$.
  * $\text{Entry Price} = \text{Trigger High}$ (or live LTP).
  * $\text{Stop Loss (SL)} = \text{VWAP at entry}$.
  * $\text{Risk} = \text{Entry Price} - \text{SL}$.
  * $\text{Target} = \text{Entry Price} + (2.0 \times \text{Risk})$.
* **Short Entry Condition**:
  * State is `ARMED_SHORT`.
  * Live $\text{LTP} \le \text{Trigger Low}$.
  * $\text{Entry Price} = \text{Trigger Low}$ (or live LTP).
  * $\text{Stop Loss (SL)} = \text{VWAP at entry}$.
  * $\text{Risk} = \text{SL} - \text{Entry Price}$.
  * $\text{Target} = \text{Entry Price} - (2.0 \times \text{Risk})$.

### 2.4 Safety Guards & Daily Limits
* **Max Trades per Symbol**: Max 1 trade per commodity per trading session. Once a trade concludes (target or SL), that symbol is locked for the remainder of the day.
* **Entry Cutoff**: **22:30 IST**. No new setups armed after 22:30 IST.
* **EOD Square-Off**: **23:15 IST**. Any open position is automatically closed at market price.

---

## 3. Architecture & Data Flow

```
[Shoonya / Broker API]
      │
      ├─ (13:30 IST) ──► ShoonyaOptionChainService ──► CommodityVwapStrategyService (Computes Bias)
      │
      └─ (15m Cycles) ─► TechnicalAnalysisService ─► CommodityVwapStrategyService (Computes VWAP)
                                │
                                ├─► Signal Event / Order Gateway (Shoonya/Zerodha)
                                └─► TelegramService (Dispatches Instant Alerts)
```

---

## 4. REST Endpoints & Commands

* `GET /api/v1/strategy/commodity-vwap/status` — Returns state of all commodities (Bias, PCR, Setup State, Active Positions).
* `POST /api/v1/strategy/commodity-vwap/scan` — Triggers an on-demand strategy evaluation cycle.
* `POST /api/v1/strategy/commodity-vwap/reset` — Resets state for the session (clears trades/locks).
* `Telegram Command`: `/commodity` — Outputs a live status report of commodity biases, active triggers, and open positions.

---

## 5. Configuration Properties

```properties
trading-bot.strategy.commodity-vwap.enabled=${COMMODITY_VWAP_ENABLED:true}
trading-bot.strategy.commodity-vwap.symbols=${COMMODITY_VWAP_SYMBOLS:GOLD,SILVER,CRUDEOIL}
trading-bot.strategy.commodity-vwap.pcr-bullish-min=${COMMODITY_PCR_BULLISH:1.15}
trading-bot.strategy.commodity-vwap.pcr-bearish-max=${COMMODITY_PCR_BEARISH:0.85}
trading-bot.strategy.commodity-vwap.risk-reward-ratio=${COMMODITY_RR:2.0}
trading-bot.strategy.commodity-vwap.entry-cutoff=${COMMODITY_ENTRY_CUTOFF:22:30}
trading-bot.strategy.commodity-vwap.eod-square-off-time=${COMMODITY_EOD_SQUAREOFF:23:15}
trading-bot.strategy.commodity-vwap.max-trades-per-symbol=${COMMODITY_MAX_TRADES_PER_SYMBOL:1}
trading-bot.strategy.commodity-vwap.telegram-alerts-enabled=${COMMODITY_TELEGRAM_ALERTS:true}
```

---

## 6. Testing Strategy

1. **Unit Tests (`CommodityVwapStrategyServiceTest`)**:
   * PCR Bias calculations across bullish, bearish, and neutral scenarios.
   * 15-minute VWAP crossover identification matching bias.
   * High/Low breakout state transitions and trade trigger.
   * Risk-Reward calculations ($1:2$ RR with SL anchored to VWAP).
   * Daily limit constraints (max 1 trade per commodity, 22:30 cutoff, 23:15 EOD square-off).
2. **Controller Tests (`CommodityVwapControllerTest`)**:
   * REST endpoint validation and response serialization.
3. **Telegram Tests (`TelegramBotCommandListenerTest`)**:
   * Parsing and formatting of `/commodity` Telegram command.
