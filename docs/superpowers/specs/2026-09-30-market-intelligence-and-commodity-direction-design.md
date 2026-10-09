# Market Intelligence & Commodity Direction Engine Design Specification

**Document ID:** `2026-09-30-market-intelligence-and-commodity-direction-design`  
**Subsystem:** `com.tradingbot.intelligence`  
**Status:** `Approved`  
**Date:** `2026-09-30`

---

## 1. Executive Summary & Goals

The **Market Intelligence & Commodity Direction Engine** is an automated quantitative synthesis subsystem that continuously analyzes multi-asset market dynamics across two primary domains:
1. **Equity & Index Market Direction (09:45 AM IST):** Synthesizes Put-Call Ratio (PCR), Option Chain Open Interest (OI) distribution, 11 NSE Sectoral rotations, and multi-timeframe technical indicators (15m/1h RSI, VWAP, SuperTrend) to classify market regime and directional bias.
2. **Global Commodity & Inter-Market Direction (13:30 & 17:30 IST):** Evaluates Gold, Silver, Crude Oil, Natural Gas, and Copper against global macro drivers (US Dollar Index DXY, US 10-Year Treasury Yields, Gold/Silver Ratio GSR, and London/New York session breakout momentum).
3. **AI / LLM Prompt Synthesis:** Produces structured prompt payloads and natural-language briefings exportable to LLMs (Gemini, OpenAI, Claude, Ollama) and automated Telegram alert channels.

---

## 2. High-Level Architecture

```
                               ┌────────────────────────────────────────┐
                               │       SPRING SCHEDULER & REST API      │
                               │   (09:45 Equity, 13:30/17:30 Comm)     │
                               └───────────────────┬────────────────────┘
                                                   │
                ┌──────────────────────────────────┴──────────────────────────────────┐
                │                                                                     │
                ▼                                                                     ▼
┌───────────────────────────────┐                                   ┌───────────────────────────────────┐
│   EQUITY DIRECTION ENGINE     │                                   │    COMMODITY DIRECTION ENGINE     │
├───────────────────────────────┤                                   ├───────────────────────────────────┤
│ • Nifty & BankNifty Chains    │                                   │ • Gold, Silver, Crude, NatGas, Cu │
│ • PCR (OI & Volume), Max Pain │                                   │ • DXY (Dollar Index), US 10Y Yield│
│ • 11 NSE Sector Relative %    │                                   │ • Gold/Silver Ratio (GSR)         │
│ • Nifty 50 Advance / Decline  │                                   │ • Asian (09-13h) vs London/NY Rng │
│ • 15m/1h RSI, VWAP, SuperTrend│                                   │ • Intraday VWAP & 1h SuperTrend   │
└───────────────┬───────────────┘                                   └─────────────────┬─────────────────┘
                │                                                                     │
                └──────────────────────────────────┬──────────────────────────────────┘
                                                   │
                                                   ▼
                               ┌────────────────────────────────────────┐
                               │   QUANTITATIVE CONFLUENCE SYNTHESIS    │
                               │      (Composite Score: -100 to +100)   │
                               └───────────────────┬────────────────────┘
                                                   │
                ┌──────────────────────────────────┴──────────────────────────────────┐
                │                                                                     │
                ▼                                                                     ▼
┌───────────────────────────────┐                                   ┌───────────────────────────────────┐
│     TELEGRAM DISPATCHER       │                                   │      AI / LLM PROMPT EXPORT       │
│ • Rich Markdown format        │                                   │ • JSON Snapshot Payload           │
│ • Bot Commands: /market,      │                                   │ • Formatted Prompt for Gemini/GPT │
│   /commodity, /pcr            │                                   │ • REST: /api/v1/intelligence/*    │
└───────────────────────────────┘                                   └───────────────────────────────────┘
```

---

## 3. Equity Market Direction Engine (09:45 AM IST)

Evaluates the market after the first 30 minutes of opening trading (09:15–09:45 IST) to establish institutional positioning.

### **3.1 Pillar 1: Options Open Interest & PCR Analysis**
- **Data Source:** `ShoonyaOptionChainService`
- **Metrics Computed:**
  - **NIFTY 50 & BANKNIFTY PCR (OI & Volume):**
    - $\text{PCR} \ge 1.25 \implies$ Bullish (Heavy Put writing floor)
    - $\text{PCR} \le 0.75 \implies$ Bearish (Heavy Call writing ceiling)
    - $0.80 < \text{PCR} < 1.20 \implies$ Neutral / Rangebound
  - **Max Pain Strike:** The strike price where option writers experience minimum cumulative payout.
  - **Call Resistance Wall:** Strike with highest Call Open Interest ($K_{\text{Call Max}}$).
  - **Put Support Wall:** Strike with highest Put Open Interest ($K_{\text{Put Max}}$).

### **3.2 Pillar 2: Sector Rotation & Market Breadth**
- **Data Source:** `LowestVolumeReversalScanner` & `NiftySectorRegistry`
- **Metrics Computed:**
  - **Nifty 50 Advance / Decline Ratio:** Evaluates overall breadth percentage ($\text{Advances} / 50$).
  - **11 NSE Sector Performance:** Ranks NIFTY IT, BANK, PHARMA, AUTO, METAL, FMCG, REALTY, ENERGY, PSU BANK, MEDIA, FIN SERVICE by average constituent % change.
  - **Relative Strength Spread:** $\text{Top Gainer Sector \%} - \text{Top Loser Sector \%}$.

### **3.3 Pillar 3: Multi-Timeframe Technical Indicators**
- **Data Source:** `TechnicalAnalysisService` & 15m/1h Candles
- **Metrics Computed:**
  - Spot Price vs Intraday VWAP (Institutional bias: Above = Demand, Below = Supply).
  - 15-Minute RSI(14) (RSI > 55 = Bullish expansion, RSI < 45 = Bearish breakdown).
  - 15-Minute SuperTrend(10, 3) (Green/Red state).
  - 20 EMA vs 50 EMA on 15m.

### **3.4 Composite Equity Directional Scoring**
- **Score Range:** $-100 \text{ to } +100$
  - $\text{Score} \ge +50 \implies$ **🟢 STRONG BULLISH**
  - $+15 \le \text{Score} < +50 \implies$ **🟢 MILD BULLISH**
  - $-15 < \text{Score} < +15 \implies$ **⚪ NEUTRAL / RANGEBOUND**
  - $-50 < \text{Score} \le -15 \implies$ **🔴 MILD BEARISH**
  - $\text{Score} \le -50 \implies$ **🔴 STRONG BEARISH**

---

## 4. Commodity Direction & Inter-Market Engine (13:30 & 17:30 IST)

Evaluates global commodities across London Open (13:30 IST) and Pre-US Open (17:30 IST).

### **4.1 Tracked Instruments**
1. **`GOLD` / `GC=F`:** Gold Futures (Bullion)
2. **`SILVER` / `SI=F`:** Silver Futures
3. **`CRUDEOIL` / `CL=F`:** WTI / MCX Crude Oil
4. **`NATURALGAS` / `NG=F`:** Natural Gas
5. **`COPPER` / `HG=F`:** Copper

### **4.2 Global Macro & Inter-Market Drivers**
1. **US Dollar Index (`DX-Y.NYB` / DXY):**
   - Inversely correlated with Gold & Silver. DXY $< \text{VWAP}$ or negative on day $\implies$ Bullish tailwind.
2. **US 10-Year Treasury Yield (`^TNX`):**
   - Inversely correlated with Gold. Falling yields boost non-yielding precious metals.
3. **Gold-to-Silver Ratio ($\text{GSR} = \text{Gold LTP} / \text{Silver LTP}$):**
   - $\text{GSR} < 75 \implies$ Silver leading (Risk-On / Industrial expansion $\implies$ Silver preferred LONG).
   - $\text{GSR} > 85 \implies$ Gold leading (Risk-Off / Safe Haven fear $\implies$ Gold preferred LONG).
4. **Session Breakout Analysis:**
   - **Asian Base Range (09:00 – 13:00 IST):** Calculates Asian High and Low.
   - **London Breakout (13:30 – 15:30 IST):** Breakout above Asian High indicates institutional long continuation.
   - **US Open (18:30+ IST):** High-volume EIA inventory (Wednesdays) & macroeconomic data release tracking.

### **4.3 Technical Indicators per Commodity**
- Spot Price vs Intraday VWAP
- 1-Hour RSI(14)
- 1-Hour SuperTrend(10, 3)
- 20 EMA vs 50 EMA on 1-Hour

---

## 5. AI / LLM Prompt Synthesis & Export

The engine generates standardized, rich prompt payloads that can be queried via REST or sent to AI models (Google Gemini, OpenAI, Claude, local Ollama):

```json
{
  "timestamp": "2026-09-30T04:15:00Z",
  "assetClass": "EQUITY",
  "niftySpot": 24920.5,
  "niftyPcrOi": 1.34,
  "bankNiftyPcrOi": 1.21,
  "maxPain": 24800,
  "marketBreadth": { "advances": 36, "declines": 14, "advanceRatio": 0.72 },
  "leadingSectors": [{ "sector": "NIFTY IT", "change": 1.85 }, { "sector": "NIFTY AUTO", "change": 1.20 }],
  "laggingSectors": [{ "sector": "NIFTY MEDIA", "change": -0.65 }, { "sector": "NIFTY PHARMA", "change": -0.30 }],
  "technicals": { "rsi15m": 62.4, "aboveVwap": true, "supertrend": "BULLISH" },
  "compositeScore": 78,
  "directionalBias": "STRONG_BULLISH"
}
```

---

## 6. REST API Endpoints

- `GET /api/v1/market-intelligence/equity` — Current equity market direction snapshot.
- `GET /api/v1/market-intelligence/commodity` — Current commodity direction snapshot.
- `GET /api/v1/market-intelligence/prompt?asset=EQUITY|COMMODITY` — Ready-to-use formatted prompt for LLMs.
- `POST /api/v1/market-intelligence/generate` — Force on-demand re-evaluation.

---

## 7. Telegram Bot Commands & Alerts

- **Automatic Scheduled Alerts:**
  - `09:45 AM IST`: Daily Equity & Index Intelligence Report.
  - `13:30 PM IST`: London Open Commodity Intelligence Report.
  - `17:30 PM IST`: Pre-US Open Commodity Intelligence Report.
- **Interactive Commands:**
  - `/market` — Displays instant 09:45 Equity & Index Direction Report.
  - `/commodity` — Displays instant Gold, Silver, Crude Oil Direction Report.
  - `/pcr` — Displays Option Chain PCR & Max Pain for Nifty & BankNifty.
  - `/sectors` — Displays 11 Sector rankings and breadth.
