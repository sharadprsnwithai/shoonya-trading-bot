# Architecture Design: Monthly Option Range GARCH(1,1) Strategy

**Date**: 2026-10-08  
**Status**: DRAFT / SPECIFICATION  
**Author**: Antigravity  
**Domain**: Quantitative Volatility Forecasting & Monthly Option Range Selection  

---

## 1. Executive Summary & Objective

Option sellers on the National Stock Exchange of India (NSE) require robust statistical boundaries at the beginning of each monthly expiry cycle to deploy short strangles, credit spreads, or iron condors with high probability of profit ($>95\%$).

This specification designs a self-contained, native Java **GARCH(1,1) (Generalized Autoregressive Conditional Heteroskedasticity)** volatility engine and post-expiry monthly option range forecasting strategy. It runs automatically at **10:00 AM IST on the last Wednesday of each month** (immediately following the monthly Tuesday expiry cycle), as well as on-demand via REST API and Telegram bot commands.

The strategy computes conditional forward volatility, 1-SD (~68%) and 2-SD (~95%) dynamic bands, historical volatility (HV-30), ATR-22, ATM Straddle expected moves, and maps these boundaries to official NSE strike step increments for target equities and indices.

---

## 2. Target Instrument Universe

The default universe consists of high-liquidity NSE F&O equities and benchmark index:
1. `RELIANCE` (Reliance Industries Ltd - Strike Step: ₹20)
2. `TCS` (Tata Consultancy Services - Strike Step: ₹20)
3. `HDFCBANK` (HDFC Bank Ltd - Strike Step: ₹10)
4. `INFY` (Infosys Ltd - Strike Step: ₹10)
5. `ICICIBANK` (ICICI Bank Ltd - Strike Step: ₹10)
6. `SBIN` (State Bank of India - Strike Step: ₹5)
7. `TATAMOTORS` (Tata Motors Ltd - Strike Step: ₹5)
8. `NIFTY` / `NIFTY50` (Nifty 50 Index - Strike Step: ₹50)

*Extensibility*: Fully configurable in `application.properties` via `trading-bot.strategy.monthly-range.symbols`.

---

## 3. Mathematical & Quantitative Architecture

### 3.1 Mean & Variance Process
For daily log returns $r_t = \ln(P_t / P_{t-1}) - \mu$:
$$\epsilon_t = r_t - \mu, \quad \epsilon_t = \sigma_t z_t, \quad z_t \sim \mathcal{N}(0, 1)$$
$$\sigma_t^2 = \omega + \alpha \epsilon_{t-1}^2 + \beta \sigma_{t-1}^2$$

### 3.2 Parameter Constraints & Optimization
- **Positivity & Stability**: $\omega > 0$, $\alpha \ge 0$, $\beta \ge 0$, $\alpha + \beta < 1.0$.
- **Objective Function**: Gaussian Log-Likelihood:
  $$\ln L(\omega, \alpha, \beta) = -\frac{1}{2} \sum_{t=1}^{T} \left( \ln(2\pi) + \ln(\sigma_t^2) + \frac{(r_t - \mu)^2}{\sigma_t^2} \right)$$
- **Optimizer Engine**: Pure Java Nelder-Mead Simplex optimization with soft boundary penalty barriers to guarantee convergence to stationary parameter solutions.

### 3.3 Multi-Period (22 Trading Days) Forward Projection
For forward horizon $H = 22$ trading days:
$$\hat{\sigma}_{t+h}^2 = V_L + (\alpha + \beta)^{h-1} (\hat{\sigma}_{t+1}^2 - V_L)$$
where long-run unconditional variance $V_L = \frac{\omega}{1 - (\alpha + \beta)}$.

Cumulative 1-month forecast volatility:
$$\sigma_{\text{month}} = \sqrt{\sum_{h=1}^{H} \hat{\sigma}_{t+h}^2}$$

### 3.4 Price Boundaries & Strike Selection
Given current underlying spot price $S_0$:
- **1-Standard Deviation (68.3% Confidence)**:
  $$\text{Lower}_{1\sigma} = S_0 \cdot \exp(-\sigma_{\text{month}}), \quad \text{Upper}_{1\sigma} = S_0 \cdot \exp(+\sigma_{\text{month}})$$
- **2-Standard Deviation (95.4% Confidence)**:
  $$\text{Lower}_{2\sigma} = S_0 \cdot \exp(-2\sigma_{\text{month}}), \quad \text{Upper}_{2\sigma} = S_0 \cdot \exp(+2\sigma_{\text{month}})$$
- **Safe Strike Selection**:
  - $\text{Safe PE Strike} = \lfloor \text{Lower}_{2\sigma} / \text{Step} \rfloor \times \text{Step}$
  - $\text{Safe CE Strike} = \lceil \text{Upper}_{2\sigma} / \text{Step} \rceil \times \text{Step}$

---

## 4. Subsystem Components & Class Structure

The strategy is located in package `com.tradingbot.strategy.monthlyrange`:

### 4.1 Configuration: `MonthlyRangeProperties`
```properties
trading-bot.strategy.monthly-range.enabled=true
trading-bot.strategy.monthly-range.cron=0 0 10 ? * WED
trading-bot.strategy.monthly-range.symbols=RELIANCE,TCS,HDFCBANK,INFY,ICICIBANK,SBIN,TATAMOTORS,NIFTY50
trading-bot.strategy.monthly-range.forecast-horizon-days=22
trading-bot.strategy.monthly-range.history-lookback-days=504
trading-bot.strategy.monthly-range.telegram-alerts-enabled=true
```

### 4.2 Data Models
1. `GarchModelParams`: Record containing $\omega, \alpha, \beta$, persistence $\alpha + \beta$, long-run variance $V_L$, log-likelihood, and convergence status.
2. `MonthlyRangeForecast`: Record containing symbol, spot price, monthly volatility %, annualized volatility %, 1-SD lower/upper, 2-SD lower/upper, safe PE/CE strikes, HV-30, ATR-22, and strike buffer %.
3. `MonthlyRangeReport`: Container holding execution timestamp, expiry cycle identifier, list of `MonthlyRangeForecast`, and summary remarks.

### 4.3 Quantitative Engine
1. `Garch11Optimizer`: Pure Java Nelder-Mead simplex optimizer to fit $(\omega, \alpha, \beta)$ on daily return time series.
2. `GarchVolForecaster`: Computes forward variance series and aggregates cumulative monthly standard deviation.
3. `MonthlyRangeCalculator`: Assembles spot price, daily candles, GARCH forecasts, ATR-22, HV-30, and snaps price bands to NSE strike step intervals using `StockFnoRegistry`.

### 4.4 Service & Scheduling
1. `MonthlyRangeService`:
   - Fetches historical daily OHLC candles via `YahooFinanceService` or SQLite store.
   - Evaluates all configured symbols in parallel/sequence.
   - Formats advisory reports.
   - Dispatches structured Telegram messages via `TelegramService`.
2. `MonthlyRangeScheduler`:
   - Scheduled at `0 0 10 ? * WED`.
   - Verifies whether the date is the **last Wednesday of the month** (post last-Tuesday expiry) before executing the monthly routine.
3. `MonthlyRangeController`:
   - `POST /api/v1/monthly-range/run` (runs scheduled routine, sends Telegram alert, returns report)
   - `GET /api/v1/monthly-range/forecast` (returns JSON forecast report for all symbols)
   - `GET /api/v1/monthly-range/forecast/{symbol}` (returns JSON forecast report for single symbol)

### 4.5 Telegram Bot Integration
Register command `/monthlyrange` in `TelegramBotCommandListener.java` to trigger on-demand generation and send the formatted table directly to Telegram.

---

## 5. Telegram Report Format

```
📊 *MONTHLY OPTION RANGE FORECAST (GARCH-1,1)*
*Cycle*: OCT 2026 (Post-Expiry Forecast)
*Horizon*: 22 Trading Days | Confidence: 95.4% (2-SD)

🔹 *RELIANCE* (Spot: ₹2,880.00)
• GARCH Monthly Vol: 5.82% (Ann: 19.6%)
• 1-SD Range (68%): ₹2,712 – ₹3,047
• 2-SD Range (95%): ₹2,554 – ₹3,225
• 🛡️ *Safe PE Strike*: ₹2,540 (Sell Below) [-11.8%]
• 🛡️ *Safe CE Strike*: ₹3,240 (Sell Above) [+12.5%]
• ATR-22: ₹42.50 | HV-30: 18.2%

🔹 *TCS* (Spot: ₹3,950.00)
...
```

---

## 6. Testing & Quality Assurance Plan

1. **Unit Tests for Math Engine**:
   - Verify `Garch11Optimizer` converges to expected parameters on standard synthetic return series with known volatility clustering.
   - Verify non-negativity and stationarity constraints ($\alpha + \beta < 1.0$).
   - Verify multi-step variance propagation and boundary calculations.
2. **Strike Step & Registry Tests**:
   - Verify exact strike rounding for RELIANCE (20), TCS (20), HDFCBANK (10), INFY (10), SBIN (5), TATAMOTORS (5), NIFTY (50).
3. **Calendar Verification Tests**:
   - Verify last-Wednesday calculation logic across multiple months and leap years.
4. **End-to-End Service Tests**:
   - Mock candle data and verify full `MonthlyRangeService` execution, report generation, and Telegram dispatch.
5. **Code Standards**:
   - Spotless formatting (`./gradlew spotlessApply`) and build verification (`./gradlew test`).
