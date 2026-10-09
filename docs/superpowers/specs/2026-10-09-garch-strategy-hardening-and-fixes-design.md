# Architecture Design: GARCH Monthly Option Range Hardening & Edge-Case Fixes

**Date**: 2026-10-09  
**Status**: DRAFT / SPECIFICATION  
**Author**: Antigravity  
**Domain**: Quantitative Volatility, Numerical Optimization, Calendar Resilience, Market Microstructure  

---

## 1. Executive Summary & Objective

This specification hardens the native Java GARCH(1,1) Monthly Option Range strategy by addressing 8 identified mathematical, calendar, market microstructure, and technical edge cases to ensure rock-solid numerical stability, true calendar holiday safety, and >95% live option selling reliability.

---

## 2. Hardening Scope & Detailed Fixes

### 2.1 Numerical Rescaling in GARCH(1,1) Optimizer (`Garch11Optimizer`)
- **Problem**: Raw decimal returns ($r_t \sim 0.01$) cause variance $\sim 10^{-4}$ and $\omega \sim 10^{-6}$, distorting the 3D Nelder-Mead simplex across 6 orders of magnitude.
- **Fix**: Rescale input returns to percentage space ($R_t = 100 \times r_t$) during optimization so $\omega_{\%}, \alpha, \beta$ are well-conditioned in the $[10^{-2}, 10^{0}]$ range. Convert estimated parameters back to raw decimal space:
  $$\omega_{\text{dec}} = \frac{\omega_{\%}}{10000}, \quad \alpha_{\text{dec}} = \alpha_{\%}, \quad \beta_{\text{dec}} = \beta_{\%}$$

### 2.2 Replay Filter & 1-Step Variance Projection (`GarchVolForecaster`)
- **Problem**: The historical replay loop had a 1-day lag, calculating $\sigma_T^2$ using returns up to $T-2$.
- **Fix**: Replay across all $T$ returns so that terminal conditional variance $\sigma_T^2$ incorporates the latest return shock $\epsilon_{T-1}$. Correctly project 1-step forward variance $\hat{\sigma}_{T+1}^2 = \omega + \alpha \epsilon_T^2 + \beta \sigma_T^2$.

### 2.3 Asymmetric Downside Skew Multipliers (`MonthlyRangeProperties` & `MonthlyRangeCalculator`)
- **Problem**: Equities exhibit negative return skewness (downside drops are sharper than upside rallies).
- **Fix**: Implement asymmetric confidence multipliers:
  - **Normal Months**: $k_{\text{PE}} = 2.10\sigma$ (downside buffer), $k_{\text{CE}} = 1.95\sigma$ (upside buffer).
  - **Event / Earnings Months**: $k_{\text{PE}} = 2.45\sigma$, $k_{\text{CE}} = 2.25\sigma$.

### 2.4 Holiday-Resilient Last-Wednesday Post-Expiry Calendar (`NseTradingCalendarUtil`)
- **Problem**: If the last Wednesday of the month is an NSE exchange holiday, `isLastWednesdayOfMonth` evaluated to false, causing the scheduler to miss running.
- **Fix**: If the last Wednesday is an exchange holiday, roll backward to the preceding trading day (Tuesday or Monday). Update `isLastWednesdayOfMonth(date)` to evaluate `true` on the effective active post-expiry session.

### 2.5 Nearest-Strike ATM Straddle Resolution (`MonthlyRangeCalculator`)
- **Problem**: If `OptionStrike::isAtm` is false due to slight fractional spot differences, straddle calculation returned 0.
- **Fix**: Find the strike with minimum absolute difference $\min |\text{strikePrice} - \text{spotPrice}|$ to accurately compute ATM Call LTP + Put LTP.

### 2.6 Wider Option Chain Strike Lookup for True Institutional OI
- **Problem**: Count of 15 strikes could miss outer institutional OI defense walls.
- **Fix**: Query with $\text{count} = 25$ strikes around ATM, ensuring $\pm 20\%$ moneyness is covered for accurate Max Call OI and Max Put OI detection.

### 2.7 Physical Delivery & Profit Target Alerts in Telegram
- **Fix**: Add clear delivery risk notices and square-off rules to the Telegram advisory message.

---

## 3. Testing & Verification Plan

1. **Numerical Optimizer Tests**:
   - Compare percentage-scaled Nelder-Mead results with synthetic return series with known parameters; verify parameters are identical regardless of return scaling.
2. **Variance Replay & Forecast Tests**:
   - Verify terminal variance $\sigma_T^2$ reflects the exact final bar return shock.
3. **Calendar Holiday Tests**:
   - Test months where last Wednesday is an exchange holiday (e.g. simulate a holiday on last Wednesday); verify rollback to Tuesday.
4. **ATM Nearest-Strike Straddle Tests**:
   - Test with non-exact spot prices (e.g., spot = 2814.60 with strikes 2800 and 2820); verify ATM strike is chosen as 2820 and straddle move is correctly summed.
5. **Full Suite Verification**:
   - Run `./gradlew spotlessApply` and `./gradlew test`.
