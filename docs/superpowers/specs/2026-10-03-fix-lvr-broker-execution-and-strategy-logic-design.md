# Design Spec: Fix LVR Broker Execution, Stop-Loss Lifecycle & Strategy Logic (Issues 1 & 2)

## 1. Overview & Objectives

This specification details the structural and algorithmic fixes for:
1. **Section 1: Broker Execution & Risk Protection (1.1, 1.2, 1.3, 1.4)**
   * Track broker protective stop-loss `orderId` in `AbstractTradeExecutionConsumer`.
   * Automatically cancel resting protective stop-loss orders on broker upon any position exit (Target 1, 10 EMA trail, EOD Hard Exit, or manual square-off).
   * Modify / replace the protective SL order on 50% partial exit at 1:2 RR to match the remaining 50% runner quantity and updated Cost SL level.
   * Use `OrderType.SL` (Stop Loss Limit with limit price buffer) for Options segment to comply with NSE/BSE exchange regulations where `SL-M` is blocked.
   * Dynamically resolve exchange (`NFO`, `NSE`, `BFO`, `BSE`) instead of hardcoding `"NFO"`.
2. **Section 2: Logical & Strategy Engine (2.1, 2.2, 2.3)**
   * Establish explicit deterministic precedence between Setup 2 (LVR volume dry-up) and Setup 3 (Vande Bharat inside-bar) on the same 5-minute candle.
   * Require live `spotPrice >= triggerPrice` (Long) / `spotPrice <= triggerPrice` (Short) before entering on fresh-wick breaches to avoid entering below trigger on a retreating price.
   * Persist `brokerTradingSymbol` on `LowestVolumePaperPosition` at entry time and reuse it during exits to prevent symbol mismatch during month roll.

---

## 2. Detailed Technical Design

### 2.1 Section 1: Broker Stop-Loss Lifecycle & Order Management

#### 2.1.1 Protective Stop Order Tracking & Cancellation (1.1)
* In `AbstractTradeExecutionConsumer.java`:
  * Add `private final Map<String, String> protectiveSlOrders = new ConcurrentHashMap<>();` mapping `ledgerKey` (symbol) $\to$ broker `slOrderId`.
  * When `placeBestEffortProtectiveStop` succeeds:
    ```java
    if (slResp != null && slResp.success() && slResp.orderId() != null) {
        protectiveSlOrders.put(key, slResp.orderId());
    }
    ```
  * In `placeOrderConfirmed()`:
    * When `isExitAction(signal.action())`:
      * Look up and cancel the resting SL order:
        ```java
        String slOrderId = protectiveSlOrders.remove(key);
        if (slOrderId != null && !slOrderId.isBlank()) {
            gateway.cancelOrder(slOrderId);
            log.info("[CONSUMER:{}] Cancelled resting protective SL order {} for {} on exit.", consumerId, slOrderId, key);
        }
        ```

#### 2.1.2 Partial Exit (50% Booking) SL Quantity Adjustment (1.2)
* When `signal.action() == SignalAction.PARTIAL_EXIT_LONG` or `PARTIAL_EXIT_SHORT`:
  1. Cancel the existing 100% protective SL order via `gateway.cancelOrder(slOrderId)`.
  2. If the position has remaining runner quantity ($> 0$), place a new protective SL order for the remaining quantity (`signal.baseQuantity()`), using the Cost SL price from `signal.stopLoss()`.
  3. Update `protectiveSlOrders.put(key, newSlOrderId)`.

#### 2.1.3 Compliance for Options SL Orders (1.3)
* In `placeBestEffortProtectiveStop()`:
  * Detect if `isOptionSignal(signal)`:
    * If **Option**:
      * Order Type: `OrderType.SL` (Stop Loss Limit).
      * Trigger Price: `slPrice`.
      * Limit Price: `roundToTick(slPrice.multiply(BigDecimal.valueOf(0.90)))` (10% market execution buffer, floored at `0.05`).
    * If **Futures / Equity**:
      * Order Type: `OrderType.SL_MKT` with trigger price `slPrice` and limit price `BigDecimal.ZERO`.

#### 2.1.4 Dynamic Exchange Resolution (1.4)
* In `LowestVolumeReversalService.publishSignal()`:
  * Include `"exchange"` in signal metadata (e.g. `fno.exchange()` or `"NSE"` for cash / `"BFO"` for Sensex / `"NFO"` for stock futures).
* In `ShoonyaTradeConsumer` & `ZerodhaTradeConsumer`:
  * Resolve exchange:
    ```java
    String exchange = "NFO";
    if (signal.metadata() != null && signal.metadata().get("exchange") != null) {
        exchange = String.valueOf(signal.metadata().get("exchange"));
    }
    ```

---

### 2.2 Section 2: Strategy Engine & Logic Fixes

#### 2.2.1 Setup 2 vs Setup 3 Precedence (2.1)
* In `LowestVolumeReversalService.evaluateCandleSequence()`:
  * Check Setup 2 (LVR low volume pullback). If candle $C_i$ qualifies for Setup 2:
    * Arm trigger on $C_i$ (Pullback candle High/Low) and tag `setupPattern = "LVR_VOLUME_PULLBACK"`.
  * For Setup 3 (Vande Bharat):
    * Only evaluate Setup 3 if Setup 2 did **not** match on candle $C_i$.
    * If Setup 3 matches: arm trigger on $C_{i-1}$ (Mother candle High/Low) and tag `setupPattern = "VANDE_BHARAT_INSIDE_BAR"`.

#### 2.2.2 Fresh-Wick Breach Guard (2.2)
* In `LowestVolumeReversalService.checkSpotTriggerBreach()`:
  * For Long: Trigger is valid only if `spotPrice >= triggerPrice` (or `sessionHigh >= triggerPrice && spotPrice >= triggerPrice * 0.9995` within tight 0.05% touch window).
  * If price has dropped significantly below trigger (e.g. $> 0.10\%$ below trigger), remain `TRIGGER_ARMED` and do not execute entry at a retreating price.

#### 2.2.3 Persistent Broker Trading Symbol (2.3)
* In `LowestVolumePaperPosition`:
  * Add field `private final String brokerTradingSymbol;`.
  * Store the exact `brokerTradingSymbol` generated at entry time.
  * In `resolveBrokerTradingSymbol(pos)`:
    * `if (pos.getBrokerTradingSymbol() != null && !pos.getBrokerTradingSymbol().isBlank()) return pos.getBrokerTradingSymbol();`

---

## 3. Verification & Testing

* Unit tests in `ShoonyaTradeConsumerTest`, `ZerodhaTradeConsumerTest`, `LowestVolumeCandleEngineTest`, and `LowestVolumeReversalServiceTest`.
* Verify cancellation of protective SL on full exit, partial exit adjustment, options `OrderType.SL` limit buffer, exchange resolution, and setup precedence.
* Run full test suite `./gradlew test`.
