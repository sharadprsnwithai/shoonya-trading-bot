# Design Spec: LVR Bug Fixes, Broker Risk Protection & Engine Hardening

## 1. Overview

This design specification formalizes the fixes for all Critical (C1–C3), High (H1–H8, H10, H12), and Medium (M4–M6) findings documented in `LVR_BUG_REPORT.md` for the Lowest Volume Reversal & Continuation (LVR) strategy in `shoonya-trading-bot`.

---

## 2. Detailed Fix Specifications

### 2.1 Critical Engine Fixes (C1 & C2: Pre-Entry Invalidation & Trigger Breach)
* **Problem:** In `LowestVolumeReversalService.checkSpotTriggerBreach()`, the guards check `quoteNode.get("l")` / `quoteNode.get("h")` (Day's Session Low and High since 09:15).
  * For Longs: Day's low since 09:15 is almost always $< SL$, triggering premature invalidation on the first 30s tick.
  * For Longs: Day's high from 09:15 is $\ge Trigger$, triggering premature entry while current LTP is still below trigger.
* **Fix:**
  1. **Pre-entry SL Invalidation:** Only evaluate against live `spotPrice`:
     * Long: Invalidate if `spotPrice <= setup.getStopLossPrice()`.
     * Short: Invalidate if `spotPrice >= setup.getStopLossPrice()`.
     * (Remove the comparison against `lowPrc` / `highPrc` session extremes).
  2. **Pre-entry Target Passed Invalidation:**
     * Long: Invalidate if `spotPrice >= setup.getTarget1Price()`.
     * Short: Invalidate if `spotPrice <= setup.getTarget1Price()`.
  3. **Trigger Breach:** Require live `spotPrice` to actually reach the trigger level within the slippage boundary:
     * Long: `spotPrice >= triggerPrice && spotPrice <= triggerPrice * (1 + maxSlippagePct / 100)`.
     * Short: `spotPrice <= triggerPrice && spotPrice >= triggerPrice * (1 - maxSlippagePct / 100)`.
     * If `spotPrice < triggerPrice` for Long (or `spotPrice > triggerPrice` for Short), setup stays in `TRIGGER_ARMED` state and waits for real breach.

---

### 2.2 Broker Protective Orders & Strategy ID Alignment (C3 & H1 & H2)
* **Strategy ID Alignment (H1):**
  * `LowestVolumeReversalService.java` defines `public static final String STRATEGY_ID = "LOWEST_VOLUME_REVERSAL";`
  * Published signals use `STRATEGY_ID` for all instrument types (`FUTURES`, `OPTIONS`, `MIS_CASH`), with `instrumentType` stored in `signal.metadata()`.
  * Allows `trading-bot.execution.consumers.shoonya.strategy-modes.LOWEST_VOLUME_REVERSAL` and `trading-bot.execution.consumers.zerodha.strategy-modes.LOWEST_VOLUME_REVERSAL` to resolve correctly.
* **Dual Consumer Safety (H2):**
  * Default `trading-bot.execution.consumers.zerodha.enabled=false` in `application.properties` to prevent accidental double order placement across two brokers in LIVE mode.
* **Broker Protective Order / Status Check (C3):**
  * In `ShoonyaTradeConsumer` and `ZerodhaTradeConsumer`, log warning if order response is rejected, and do not track unconfirmed fills.

---

### 2.3 Expiry Calculation & Symbol Rolling (H3)
* **Problem:** `StockFnoRegistry.getMonthlyExpiry(LocalDate.now())` does not roll to the next month once the current month's last Thursday has passed.
* **Fix:**
  * In `StockFnoRegistry.java`:
    * Update `formatFuturesTradingSymbol(symbol, expiry)` and `formatTradingSymbol(symbol, expiry, strike, optType)`:
      * When `expiry == null`, call `calculateTargetExpiry(symbol, LocalDate.now(clock), false, 1)` to dynamically select the active next valid monthly expiry.
    * Ensure expiry date calculation is consistent across entry order, exit order, and option LTP fetching.

---

### 2.4 Open Position Protection Decoupling (H4)
* **Problem:** When `enabled == false` (e.g. toggled via `/toggle?strategyEnabled=false`), `runCycle()` and `evaluateLivePriceActions()` return immediately, leaving open positions completely unmanaged.
* **Fix:**
  * In `LowestVolumeReversalService.java`:
    * Decouple entry scanning from position management:
      * If `!enabled`, skip morning scan, candidate processing, and new entry trigger evaluation.
      * **Always** run `evaluateOpenPositions()` if `!openPositions.isEmpty()`, ensuring Stop Loss, 1:2 Target partial booking, 10 EMA trailing, and 15:00 EOD hard exits execute regardless of `enabled` flag.

---

### 2.5 PDH/PDL Gate Fail-Closed & Replenishment (H7)
* **Fix:**
  * In `replenishActiveCandidatesIfNeeded()`: Call `initPdhPdlForSetup(setup)` whenever a candidate is promoted from the reservoir.
  * In `evaluateLivePriceActions()`: If `pdhPdlFilterEnabled == true` and `setup.getPdh() == null`, fail closed by attempting a lazy fetch of daily candles; if still null, log a warning and reject the trade with `REJECTED_EXHAUSTED` instead of passing unfiltered.

---

### 2.6 OI Spurts Live Quote Metrics (H8)
* **Fix:**
  * In `fetchMorningQuotesUnified()`: Extract `"oi"` (current open interest) and `"oio"` (open interest open / previous day OI) from Shoonya `GetQuotes` response JSON.
  * Construct `StockQuoteSnapshot(sym, lp, c, o, pct, volume, vwap, oi, oio)` so `oiPctChange()` computes non-zero values for F&O stocks.

---

### 2.7 Engine Consistency & Hygiene (H10, H12, M4, M5, M6)
* **Sticky Stand-Down (H10):** Add `private volatile boolean standDownToday = false;`. If morning breadth $< 56\%$, set `standDownToday = true`. `runMidMorningUniverseRefresh()` and `replenishActiveCandidatesIfNeeded()` check `if (standDownToday) return;`.
* **Hard Exit Hygiene (H12):** When quote fails at 15:00 EOD exit, do not fabricate entry price as exit price. Use `price = BigDecimal.ZERO` (Market exit) and record `exitReason = "EOD_1500_HARD_EXIT"`.
* **Timeout Consistency (M4):** Use `>= setupTimeoutCandles` in both 5m and 30s checks.
* **Vande Bharat Clean Precedence (M5):** In `evaluateCandleSequence()`, only evaluate Vande Bharat if no trigger is armed on the current candle, or if Vande Bharat is the active setup pattern.
* **10-EMA Closed Candle Filter (M6):** In `evaluateOpenPositions()`, filter 5m candles by `timestamp + 300s <= now` before computing 10 EMA to avoid intra-candle wicks triggering false runner exits.

---

## 3. Verification Plan

1. **Unit Tests:**
   * `LowestVolumeReversalServiceTest`:
     * Test pre-entry SL check uses live spot price and does NOT invalidate due to session day low.
     * Test trigger breach rejects when spot price is below trigger (even if day high is above trigger).
     * Test open positions are managed when `enabled = false`.
     * Test PDH/PDL filter initializes and fails closed on null.
     * Test OI Spurts extraction from quote JSON.
   * `StockFnoRegistryTest`:
     * Test monthly futures symbol rolls to next month after expiry.
2. **Full Suite Regression:**
   * Run `./gradlew test` to ensure 100% pass across the bot.
