# Design Spec: LVR Drawdown Reduction, Parameter Tuning & Modular Setup Control

## 1. Overview & Objectives

Based on systematic historical backtesting across 57 sessions, this specification formalizes the drawdown-reduction and parameter-tuning enhancements for the Lowest Volume Reversal (LVR) strategy in `shoonya-trading-bot`.

### Key Enhancements:
1. **Modular Setup Control (`vandeBharatEnabled`):**
   * Default `vande-bharat-enabled=false` to trade Pure LVR Volume Pullbacks (Setup 2), which generates +155% higher net profit with 27% lower drawdown. The code for Setup 3 (Vande Bharat) is preserved behind a configurable toggle.
2. **Daily Loss Count Circuit Breaker (`maxDailyLosses`):**
   * Add a `max-daily-losses=2` limit. If the strategy incurs 2 stop-loss exits on the current trading day, new entries are halted for the remainder of the session to eliminate whipsaw drawdowns on choppy days.
3. **Configurable Target Risk-Reward (`targetRr`):**
   * Change default Target 1 from 1:2.0 RR to **1:2.5 RR** (`target-rr=2.5`), increasing the profit factor from 1.10 to 1.41 and reducing max drawdown by 50%.
4. **Fixed Lot Sizing (`lots=2`):**
   * Retain fixed 2-lot position sizing as the default production mode.

---

## 2. Detailed Technical Design

### 2.1 Configuration Properties (`application.properties`)
```properties
# Drawdown Reduction & Strategy Tuning
trading-bot.strategy.lowest-volume.vande-bharat-enabled=${LVR_VANDE_BHARAT_ENABLED:false}
trading-bot.strategy.lowest-volume.target-rr=${LVR_TARGET_RR:2.5}
trading-bot.strategy.lowest-volume.max-daily-losses=${LVR_MAX_DAILY_LOSSES:2}
```

---

### 2.2 Strategy Engine Updates (`LowestVolumeReversalService.java`)

1. **Configurable Setup 3 Evaluation:**
   * In `evaluateCandleSequence()`:
     ```java
     // 3. Setup 3: Vande Bharat (Inside Bar Entry) - gated by vandeBharatEnabled flag
     if (vandeBharatEnabled && !lvrMatchedOnThisBar && i >= 1) {
         // evaluate inside bar pattern...
     }
     ```

2. **Configurable Target 1 Calculation:**
   * In `evaluateCandleSequence()` and `executePositionEntry()`:
     * Long Target 1: `triggerPrc.add(risk.multiply(BigDecimal.valueOf(targetRr)))`
     * Short Target 1: `triggerPrc.subtract(risk.multiply(BigDecimal.valueOf(targetRr)))`

3. **Daily Loss Count Circuit Breaker:**
   * Add `private final AtomicInteger todayLossCount = new AtomicInteger(0);`
   * When an open position closes with `SPOT_SL_HIT` (or negative realized P&L):
     * Increment `todayLossCount.incrementAndGet();`
   * In `evaluateLivePriceActions()` and `evaluateEntryGate()`:
     * If `maxDailyLosses > 0 && todayLossCount.get() >= maxDailyLosses`:
       * Block new trade entries and log/alert: `"Daily max loss count reached (2/2 losses today). Halting entries."`
   * In `resetDaily()`:
     * `todayLossCount.set(0);`

---

## 3. Verification & Testing

* Unit tests in `LowestVolumeCandleEngineTest` to verify `vandeBharatEnabled` toggle and `targetRr` calculation.
* Unit tests in `LowestVolumeReversalServiceTest` to verify `todayLossCount` halts entries after 2 losses.
* Run full regression test suite `./gradlew test`.
