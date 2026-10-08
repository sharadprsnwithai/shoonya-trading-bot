# Architecture Design: GARCH Post-Expiry Calendar & Option Chain Hardening

**Date**: 2026-10-09  
**Status**: DRAFT / SPECIFICATION  
**Author**: Antigravity  
**Domain**: Quantitative Calendar Logic, Scheduler Resilience, Strike Snapping Positivity  

---

## 1. Executive Summary & Impact Analysis

This specification addresses the calendar rollover edge case, holiday scheduling trap, strike snapping positivity, and Shoonya futures symbol resolution.

### Impact on Existing Strategies (Specifically LVR):
- **Zero Adverse Impact**:
  - `NseTradingCalendarUtil` changes are additive (`isFirstTradingDayPostMonthlyTuesdayExpiry`).
  - LVR uses its own 5-minute scheduler (`20 */5 9-15 ? * MON-FRI`) and intraday state store.
  - Option Chain enhancements in `ShoonyaOptionChainService` resolve live futures `tsym` seamlessly for both LVR and Monthly Range without changing method signatures.

---

## 2. Hardening Scope

### 2.1 Post-Expiry Trading Session Determination (`NseTradingCalendarUtil`)
Instead of calculating the "last Wednesday of the calendar month" (which fails when a month ends on Tuesday like June 30 or March 31):
- For any given `LocalDate date`:
  - Derive the nearest preceding monthly Tuesday expiry date ($E$).
  - Derive the first active exchange trading day following $E$: $D_{\text{post}} = E + 1 \text{ day (skipping weekends and holidays)}$.
  - `isFirstTradingDayPostMonthlyTuesdayExpiry(date)` returns `true` if and only if `date.equals(D_post)`.

### 2.2 Weekday Scheduler (`MonthlyRangeScheduler`)
- Update default cron to `0 0 10 ? * MON-FRI`.
- Evaluates `isFirstTradingDayPostMonthlyTuesdayExpiry(today)` every weekday at 10:00 AM IST.
- Guarantees execution on the exact post-expiry session even when the last Wednesday is an exchange holiday or rolls over into the 1st of the next month.

### 2.3 Strike Snapping Positivity Clamp (`MonthlyRangeCalculator`)
- In `snapStrikeDown`: ensure the computed strike is clamped to `Math.max(step, flooredStrike)`.
- In `snapStrikeUp`: ensure the computed strike is clamped to `Math.max(step, ceiledStrike)`.

### 2.4 Futures Symbol Resolution for Single Stocks (`ShoonyaOptionChainService`)
- When resolving stock options chains in `getIndexOptionChain`, resolve active near-month futures `tsym` via `marketDataService.resolveFuturesContract(cleanUnderlying)` to avoid "Invalid Trading Symbol" rejection.

---

## 3. Verification Plan

1. **Calendar Unit Tests**:
   - Test June 2026 (June 30 is Tuesday expiry $\rightarrow$ post-expiry is July 1).
   - Test March 2026 (March 31 is Tuesday expiry $\rightarrow$ post-expiry is April 1).
   - Test October 2026 (Oct 27 is Tuesday expiry $\rightarrow$ post-expiry is Oct 28).
   - Test Expiry Tuesday with Wednesday Holiday (Wednesday is holiday $\rightarrow$ post-expiry is Thursday).
2. **Scheduler Tests**:
   - Verify scheduler executes only on `D_post` and skips all other weekdays.
3. **Strike Positivity Tests**:
   - Verify low prices never snap to zero or negative.
4. **LVR & Project-Wide Suite**:
   - Run full project test suite (`./gradlew test`) to verify zero regressions in LVR, CAR, or execution engines.
