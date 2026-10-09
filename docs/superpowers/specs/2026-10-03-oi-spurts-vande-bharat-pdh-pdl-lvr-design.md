# Intraday Strategy Spec: OI Spurts Stock Selection, Vande Bharat Inside Bar & PDH/PDL Filter Integration

## 1. Overview & Context

This design specification formalizes and integrates the **OI Spurts Selection Method**, the **Vande Bharat Inside Bar Setup (Setup 3)**, and the **Post-Signal Previous Day High/Low (PDH/PDL) Range Filter** into the existing `LowestVolumeReversalService` (LVR) framework within `shoonya-trading-bot`.

### Key Pillars
1. **Stock Selection:** 09:25–09:27 IST screen of NSE F&O stocks sorted by absolute `% Change in Open Interest` ($|\Delta OI\%|$) to identify institutional focus.
2. **Setup Engine:** Dual pattern recognition on 5-minute charts:
   * **Setup 2:** LVR Lowest Volume Pullback (Volume dry-up $\le$ session low).
   * **Setup 3:** Vande Bharat Inside Bar (Mother candle + Inside opposite candle for fast breakouts).
3. **Post-Signal Execution Gate:**
   * **PDH Filter (Long):** Spot price must break out above Previous Day High.
   * **PDL Filter (Short):** Spot price must break down below Previous Day Low.
   * **Range Rejection:** If price is trapped between PDL and PDH (`PDL <= spotPrice <= PDH`), the setup is rejected to prevent whipsaws.
   * **15-Min ORB Filter:** Disabled by default in favor of PDH/PDL.
4. **Trade Management:** 1:2 RR Target 1 (50% booking) $\to$ Cost SL on remaining 50% $\to$ Dynamic trailing via 10 EMA $\to$ 15:00 IST Hard EOD exit.

---

## 2. Mathematical Formalization & Rules

### 2.1 OI Spurts Selection (09:25 – 09:27 IST)
- **Universe:** NSE F&O Underlyings $U_{FNO} \setminus \{\text{NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY}\}$.
- **Formula:**
  $$\Delta OI\% = \frac{\text{OI}_{09:25} - \text{OI}_{\text{prev\_close}}}{\text{OI}_{\text{prev\_close}}} \times 100$$
- **Selection:** Sort universe by $|\Delta OI\%|$ in descending order; take Top $N$ (default $N=5$).
- **Direction:** Neutral at selection. Direction is dictated strictly by Price Action relative to PDH/PDL and candle patterns.

### 2.2 Setup 3: Vande Bharat (Inside Bar) Pattern Rules (5-Min Chart)
Evaluated from Candle 4 onwards ($i \ge 3$):

#### Long Setup (Bullish Momentum):
1. **Mother Candle ($C_{i-1}$):** $Close(C_{i-1}) > Open(C_{i-1})$ (Green).
2. **Inside Candle ($C_i$):** $Close(C_i) < Open(C_i)$ (Red) AND:
   $$High(C_i) \le High(C_{i-1}) \quad \land \quad Low(C_i) \ge Low(C_{i-1})$$
3. **Trigger Price:** $P_{\text{entry}} = \text{roundToTick}(High(C_{i-1}) + 0.05)$.
4. **Stop Loss:** $P_{\text{sl\_raw}} = \text{roundToTick}(Low(C_i) - 0.05)$.
   $$\text{Risk} = \max(P_{\text{entry}} - P_{\text{sl\_raw}}, P_{\text{entry}} \times \frac{\text{minStopLossPct}}{100})$$
   $$P_{\text{sl}} = \text{roundToTick}(P_{\text{entry}} - \text{Risk})$$
5. **Target 1 (1:2 RR):** $P_{\text{t1}} = \text{roundToTick}(P_{\text{entry}} + 2 \times \text{Risk})$.

#### Short Setup (Bearish Momentum):
1. **Mother Candle ($C_{i-1}$):** $Close(C_{i-1}) < Open(C_{i-1})$ (Red).
2. **Inside Candle ($C_i$):** $Close(C_i) > Open(C_i)$ (Green) AND:
   $$High(C_i) \le High(C_{i-1}) \quad \land \quad Low(C_i) \ge Low(C_{i-1})$$
3. **Trigger Price:** $P_{\text{entry}} = \text{roundToTick}(Low(C_{i-1}) - 0.05)$.
4. **Stop Loss:** $P_{\text{sl\_raw}} = \text{roundToTick}(High(C_i) + 0.05)$.
   $$\text{Risk} = \max(P_{\text{sl\_raw}} - P_{\text{entry}}, P_{\text{entry}} \times \frac{\text{minStopLossPct}}{100})$$
   $$P_{\text{sl}} = \text{roundToTick}(P_{\text{entry}} + \text{Risk})$$
5. **Target 1 (1:2 RR):** $P_{\text{t1}} = \text{roundToTick}(P_{\text{entry}} - 2 \times \text{Risk})$.

---

### 2.3 Post-Signal Execution Gate (Safety Checks at Breach Time)
When `spotPrice` reaches `triggerPrice` during 30-second live monitoring:

1. **PDH / PDL Baseline Check (`pdhPdlFilterEnabled = true`):**
   * If Long: `spotPrice > PDH`. If not $\to$ reject with state `REJECTED_EXHAUSTED` (Trapped in range).
   * If Short: `spotPrice < PDL`. If not $\to$ reject with state `REJECTED_EXHAUSTED` (Trapped in range).
2. **VWAP Confirmation (`vwapConfirmationEnabled = true`):**
   * If Long: `spotPrice > VWAP`.
   * If Short: `spotPrice < VWAP`.
3. **15-Min ORB Filter (`opening15mRangeFilterEnabled = false`):**
   * Disabled to prevent blocking early pullback entries above PDH.
4. **Circuit / Exhaustion Check:**
   * Day's absolute move from open must be $< 5.0\%$.

---

## 3. Architecture & Class Modifications

### 3.1 `LowestVolumeSetup.java`
Add fields:
- `private BigDecimal pdh;`
- `private BigDecimal pdl;`
- `private String setupPattern;` // e.g. "LVR_VOLUME_PULLBACK" or "VANDE_BHARAT_INSIDE_BAR"
- `private Double oiChangePct;`

### 3.2 `LowestVolumeReversalScanner.java`
Add method:
- `public List<String> scanOiSpurts(Map<String, StockQuoteSnapshot> fnoQuotes, int topN)`
  - Filters out indices (`NIFTY 50`, `BANKNIFTY`, etc.).
  - Computes `% Change in OI`.
  - Sorts descending by absolute `% Change in OI`.
  - Returns top $N$ symbols.

### 3.3 `LowestVolumeReversalService.java`
- Update `runMorningUniverseScan()`:
  - Support `scannerMode` property: `OI_SPURTS` vs `SECTOR_ROTATION`.
  - Fetch previous day's daily OHLC candle for candidate symbols to initialize `PDH` and `PDL`.
- Update `evaluateCandleSequence()`:
  - Check for Setup 3 (Vande Bharat inside-bar pattern) alongside Setup 2 (LVR low volume).
  - Annotate setup with `setupPattern`.
- Update `evaluateLivePriceActions()`:
  - Add PDH/PDL filter check immediately before order execution.
  - Reject setups where spot is inside `[PDL, PDH]`.
- Configuration flags:
  ```properties
  trading-bot.strategy.lowest-volume.scanner-mode=OI_SPURTS
  trading-bot.strategy.lowest-volume.oi-spurts-top-n=5
  trading-bot.strategy.lowest-volume.pdh-pdl-filter-enabled=true
  trading-bot.strategy.lowest-volume.opening-15m-range-filter-enabled=false
  ```

---

## 4. Verification & Testing

1. **Unit Tests:**
   - `LowestVolumeReversalScannerTest`: Verify OI % sorting and non-index filtering.
   - `LowestVolumeCandleEngineTest`: Verify Vande Bharat Bullish and Bearish inside-bar trigger/SL computation.
   - `LowestVolumeReversalServiceTest`: Verify PDH/PDL post-signal gate blocks inside-range spot prices and passes valid breakouts.
2. **Replay Regression:**
   - `ShoonyaTodayLvrReplayRunnerTest` / `ShoonyaLast5DaysLvrReplayRunnerTest`: Verify full simulation passes with deterministic outputs.
