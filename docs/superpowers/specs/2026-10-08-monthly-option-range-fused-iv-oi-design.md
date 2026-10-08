# Architecture Design: Fused IV, OI & Event-Month GARCH(1,1) Option Range Strategy

**Date**: 2026-10-08  
**Status**: DRAFT / SPECIFICATION  
**Author**: Antigravity  
**Domain**: Quantitative Volatility, Derivatives Open Interest, Earnings Risk Adjustment  

---

## 1. Executive Summary & Objective

To achieve a true **>95% win-rate edge** in monthly stock option selling across both quiet and volatile quarterly earnings months, the statistical GARCH(1,1) engine is enhanced into a **Fused Multi-Layer Decision Engine**:

1. **Layer 1 (Statistical GARCH)**: Baseline multi-period conditional volatility forecast ($\sigma_{\text{month}}$).
2. **Layer 2 (Market Implied Move & IV)**: ATM Straddle pricing ($\text{Call}_{\text{ATM}} + \text{Put}_{\text{ATM}}$) and Implied Volatility ($IV$).
3. **Layer 3 (Institutional OI Walls)**: Max Call OI Strike (institutional resistance) and Max Put OI Strike (institutional support).
4. **Layer 4 (Event / Earnings Regime)**:
   - Identifies whether the current monthly cycle falls in an **Earnings Month** (January, April, July, October) or exhibits an **IV Spike** ($IV > 1.25 \times HV$).
   - Dynamically expands standard deviation multiplier from $2.00\sigma \rightarrow 2.35\sigma$ in event months.
   - Enforces conservative outer bounds combining GARCH and Institutional OI walls:
     $$\text{Final Safe PE} = \min(\text{GARCH Lower Bound}, \; \text{Highest Put OI Strike})$$
     $$\text{Final Safe CE} = \max(\text{GARCH Upper Bound}, \; \text{Highest Call OI Strike})$$

---

## 2. Event & Earnings Detection Engine

### 2.1 Calendar Earnings Cycle Detection
Standard Indian corporate quarterly results cycles:
- **Q4 Results (FY Ending)**: April, May
- **Q1 Results**: July, August
- **Q2 Results**: October, November
- **Q3 Results**: January, February

Target F&O stocks (RELIANCE, TCS, HDFCBANK, INFY, ICICIBANK, SBIN, TATAMOTORS) report earnings in these quarterly cycles. When evaluating on post-expiry Wednesday:
- If current calendar month is **Jan, Apr, Jul, or Oct** (or May, Aug, Nov, Feb), the cycle is tagged as `EARNINGS_CYCLE`.

### 2.2 Volatility Ratio Trigger ($IV / HV$)
- If $\frac{IV_{\text{ATM}}}{HV_{30}} \ge 1.25$, market is pricing elevated upcoming risk/events $\rightarrow$ tagged as `IV_SPIKE`.

---

## 3. Mathematical & Structural Fusion

### 3.1 Dynamic Confidence Multiplier ($k$)
- **Normal Months**: $k = 2.00$ ($\approx 95.45\%$ statistical normal confidence).
- **Event / Earnings Months**: $k = 2.35$ ($\approx 98.12\%$ normal / $>95\%$ fat-tailed empirical confidence).

$$\text{Lower}_{\text{GARCH}} = S_0 \cdot \exp(-k \cdot \sigma_{\text{month}})$$
$$\text{Upper}_{\text{GARCH}} = S_0 \cdot \exp(+k \cdot \sigma_{\text{month}})$$

### 3.2 Implied Move from ATM Straddle
$$\text{ATM Straddle Move} \approx \text{LTP}(\text{CE}_{\text{ATM}}) + \text{LTP}(\text{PE}_{\text{ATM}})$$
$$\text{Implied Lower} = S_0 - 1.25 \times \text{Straddle Move}$$
$$\text{Implied Upper} = S_0 + 1.25 \times \text{Straddle Move}$$

### 3.3 Institutional Open Interest (OI) Fusion
From the active monthly option chain (or Shoonya / theoretical registry):
- $\text{Max Put OI Strike} = \text{Strike with largest Put Open Interest (Institutional Support)}$
- $\text{Max Call OI Strike} = \text{Strike with largest Call Open Interest (Institutional Resistance)}$

### 3.4 Final Strike Selection (Conservative Outer Boundary)
- $\text{GARCH PE Strike} = \lfloor \text{Lower}_{\text{GARCH}} / \text{Step} \rfloor \times \text{Step}$
- $\text{GARCH CE Strike} = \lceil \text{Upper}_{\text{GARCH}} / \text{Step} \rceil \times \text{Step}$
- $\text{Final Safe PE} = \min(\text{GARCH PE Strike}, \; \text{Max Put OI Strike (if valid)})$
- $\text{Final Safe CE} = \max(\text{GARCH CE Strike}, \; \text{Max Call OI Strike (if valid)})$

---

## 4. Telegram Advisory Alert Output Format

```
📊 *MONTHLY OPTION RANGE FORECAST (GARCH + IV + OI FUSION)*
📅 *Cycle:* `2026-10` | *Horizon:* 22 Trading Days
⚡ *Regime:* ⚠️ *EARNINGS MONTH* (2.35σ Multiplier Applied)

🔹 *RELIANCE* (Spot: ₹2,880.00)
 • *Regime:* ⚠️ Earnings Cycle | Conf: 2.35σ
 • *GARCH Monthly Vol:* 5.82% (Ann: 19.6%)
 • *GARCH Band (2.35σ):* ₹2,510 – ₹3,275
 • *ATM Straddle Expected Move:* ±₹175.00
 • 🛡️ *Institutional OI Support:* ₹2,500 (Max Put OI)
 • 🛡️ *Institutional OI Resistance:* ₹3,200 (Max Call OI)
 • 🎯 *FINAL SAFE PE STRIKE:* `₹2,500` [ -13.2% Buffer ]
 • 🎯 *FINAL SAFE CE STRIKE:* `₹3,280` [ +13.9% Buffer ]
 • *ATR-22:* ₹42.50 | *HV-30:* 18.2%
```

---

## 5. Testing Plan

1. **Event Detection Tests**:
   - Verify Jan, Apr, Jul, Oct trigger `isEventMonth = true` and $k = 2.35$.
   - Verify non-earnings months (e.g. Mar, Jun, Sep, Dec) default to $k = 2.00$ unless IV/HV ratio $\ge 1.25$.
2. **OI Fusion & Strike Snapping Tests**:
   - Verify that if Max Put OI Strike is below GARCH band, final PE snaps to the lower Max Put OI.
   - Verify that if GARCH band is further out, final PE retains the safer outer GARCH strike.
   - Symmetric verification for CE strikes.
3. **End-to-End Service & Telegram Tests**:
   - Verify option chain integration, formatting, and graceful fallback when option chain is offline.
