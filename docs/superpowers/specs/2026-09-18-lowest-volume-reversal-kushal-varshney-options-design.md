# Lowest Volume Reversal & Continuation (LVR) Options Strategy Design Specification
*Based on Kushal Varshney Intraday Framework*

## 1. Overview
The Lowest Volume Reversal & Continuation (LVR) strategy is an intraday mechanical trading framework operating on **5-minute candles** across Indian National Stock Exchange (NSE) **F&O eligible equities**. The strategy executes a 3-step pipeline: **Selection $\rightarrow$ Setup $\rightarrow$ Action**.

Trades are executed exclusively via **Stock Options Buying (ATM CE for Longs / ATM PE for Shorts)** with risk management, stop-loss trailing, and target profit-taking driven directly by **underlying spot price action**.

---

## 2. Selection Pipeline (09:15 – 09:30 IST)

### Step 2.1: Opening Settlement (09:15 – 09:25 IST)
- No trading actions or entries are evaluated during the first 10 minutes (Candles 1 and 2: 09:15–09:20, 09:20–09:25) to allow opening market volatility to settle.

### Step 2.2: Market Sentiment Evaluation (09:25 IST)
- At 09:25 IST, inspect the **NIFTY 50 Advance/Decline ratio**:
  - **Bearish Sentiment:** If $\text{Declines} > \text{Advances}$ $\rightarrow$ Trading direction is strictly **BEARISH / SHORT (Buy PE)**.
  - **Bullish Sentiment:** If $\text{Advances} > \text{Declines}$ $\rightarrow$ Trading direction is strictly **BULLISH / LONG (Buy CE)**.

###    (09:25 – 09:26 IST)
- Rank the 11 major NSE Sectoral Indices by percentage change from previous day close:
  1. Nifty Auto
  2. Nifty Bank
  3. Nifty Financial Services
  4. Nifty FMCG
  5. Nifty IT
  6. Nifty Media
  7. Nifty Metal
  8. Nifty Pharma
  9. Nifty Private Bank
  10. Nifty PSU Bank
  11. Nifty Realty
- **Sector Selection:**
  - If Market Sentiment is **Bearish** $\rightarrow$ Select the **Top Loser Sector** (maximum negative % drop).
  - If Market Sentiment is **Bullish** $\rightarrow$ Select the **Top Gainer Sector** (maximum positive % gain).

### Step 2.4: Stock Filtering (09:26 – 09:30 IST)
- Retrieve all **F&O eligible stocks** mapped to the selected sector.
- **Filter & Exclusion Criteria:**
  - **Circuit / Lock Filter:** Exclude any stock with $|\% \text{ change}| \ge 10.0\%$ or locked at circuit limit.
  - **Exhaustion Filter:** Exclude any stock with $|\% \text{ change}| > 5.0\%$ at 09:25 IST (move already overextended).
- **Candidate Pool:**
  - If sector has 1–3 stocks (e.g., Nifty Media with `PVRINOX`, `SUNTV`): Monitor all eligible stocks.
  - If sector has $> 3$ stocks (e.g., IT, Pharma, Auto): Select the **Top 2–3 Stocks** from the Top Gainers / Losers list within that sector.

---

## 3. Setup Identification & Candle Volume Engine (09:30 – 13:00 IST)

### Step 3.1: 5-Minute Baseline (09:15 – 09:30 IST)
- Evaluate candles strictly on the **5-minute timeframe (`5m`)**.
- **Ignore first 3 candles for entry** (09:15–09:20, 09:20–09:25, 09:25–09:30).
- Initialize baseline rolling minimum volume:
  $$\text{dayLowestVolume} = \min(\text{Vol}_{C1}, \text{Vol}_{C2}, \text{Vol}_{C3})$$
- As each subsequent 5-minute candle closes ($C_4, C_5, \dots$):
  $$\text{dayLowestVolume} = \min(\text{dayLowestVolume}, \text{Vol}_{\text{current}})$$

### Step 3.2: The "Empty Train" (Volume Dry-Up Pullback) Setup Signal
- Scanning begins from Candle 4 (09:30–09:35 IST) onwards until 13:00 IST cutoff.
- **For Bearish (Short / Buy PE) Setup:**
  - Candle must be **GREEN** ($Close > Open$).
  - Candle volume must be the **New Day's Lowest Volume**: $\text{Volume} < \text{dayLowestVolume}$.
  - **Armed State:**
    - Entry Trigger Level = $\text{Low} - 0.05$ (1 tick below green candle low).
    - Stop Loss Level = $\text{High} + 0.05$ (1 tick above green candle high).
- **For Bullish (Long / Buy CE) Setup:**
  - Candle must be **RED** ($Close < Open$).
  - Candle volume must be the **New Day's Lowest Volume**: $\text{Volume} < \text{dayLowestVolume}$.
  - **Armed State:**
    - Entry Trigger Level = $\text{High} + 0.05$ (1 tick above red candle high).
    - Stop Loss Level = $\text{Low} - 0.05$ (1 tick below red candle low).

### Step 3.3: Dynamic Order Trailing & Replacement
- When an armed setup exists:
  - If price breaches the trigger price in the trend direction $\rightarrow$ **Trigger Option Entry**.
  - If price moves away in the opposite direction (e.g., price continues higher during a short setup):
    - If a **newer opposite-color candle forms with an even lower volume**, update the armed state to the new candle's Low/High.
    - If a newer candle has higher volume, retain the existing armed levels without moving.

---

## 4. Option Execution & Position Management

### Step 4.1: Option Strike Selection & Entry
- Upon spot trigger breach:
  - Identify the **nearest Monthly ATM Strike** for the underlying equity.
  - Execute:
    - **Bearish Setup:** Buy ATM PE at market/LTP.
    - **Bullish Setup:** Buy ATM CE at market/LTP.
  - **Position Sizing:** Configurable lots (default: `2 lots`).

### Step 4.2: Spot-Based Risk-Reward & Partial Exit (1:4 RR)
- Calculate underlying spot risk:
  $$\text{Spot Risk Points} = |\text{Entry Spot} - \text{SL Spot}|$$
- **Stop Loss Breach:**
  - If underlying spot breaches $\text{SL Spot}$ before target:
    - Immediately close 100% of open option contracts.
    - Archive trade as SL Hit.
- **Target 1 Reach (1:4 Risk-Reward):**
  - When underlying spot reaches $4 \times \text{Spot Risk Points}$ in profit:
    - **Book 50% of the position** (close 1 of 2 lots at current option LTP).
    - **Move Stop Loss of remaining 50% to Entry Spot (Cost SL / Breakeven).**

### Step 4.3: Runner Trailing & Hard EOD Square-Off
- The remaining 50% option position is held risk-free:
  - **10 EMA Trailing Exit:** If a 5-minute spot candle closes across the 10 EMA (above 10 EMA for Short, below 10 EMA for Long), exit on subsequent breach.
  - **15:15 IST Hard EOD Exit:** Square off all open intraday option positions at 15:15 IST.
- **Max Attempts per Stock:** Maximum **2 trade attempts** per stock per day.

---

## 5. Architecture & Components

```
 com.tradingbot.service
 ├── NiftySectorRegistry.java            (Maps 11 NSE sectors to F&O constituents)
 ├── LowestVolumeReversalScanner.java    (09:25 IST Market Sentiment & Sector Ranker)
 ├── LowestVolumeReversalService.java    (5m Candle Engine, Order State, Spot SL/Target Monitor)
 ├── LowestVolumeOptionExecutor.java     (ATM Strike Resolution & Option Buying Execution)
 └── TelegramService.java                (Real-time formatted trade lifecycle alerts)
```

---

## 6. Verification & Testing Strategy
1. **Unit Tests for Sector Ranking:** Mock 11 sector % changes and verify Top Loser/Gainer selection.
2. **Unit Tests for 5-Minute Lowest Volume Engine:** Feed 5-minute candle sequences with varying volumes and verify setup arming, order trailing, and trigger conditions.
3. **Unit Tests for Spot-Based Option Position Manager:** Verify 1:4 RR partial profit taking (50% lots), Cost SL adjustment, 10 EMA trailing, and 15:15 IST hard exit.
4. **Full Regression Suite:** Ensure `./gradlew test` remains 100% green.
