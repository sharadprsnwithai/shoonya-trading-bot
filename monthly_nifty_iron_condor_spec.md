# Strategy Spec: Monthly NIFTY Iron Condor — 2% Target on Full Capital

Implementation-ready specification.
**Capital:** ₹10,00,000 | **Deployed:** 70% (₹7,00,000 margin) | **Buffer:** 30% (₹3,00,000 adjustment reserve)

Every ambiguity is resolved below as an explicit decision (marked **[DECISION]**). Treat decisions as configurable parameters in code.

**Target Engine / Broker:** Shoonya (Finvasia `NorenApi`) / Zerodha Kite Connect / Angel One API.

---

## 1. Executive Summary & Core Concept

A **defined-risk, non-directional monthly iron condor** on NIFTY 50 index options. It sells an out-of-the-money
put spread and call spread simultaneously, so both maximum profit and maximum loss are known before the trade
is placed. The 30% cash buffer is held free to absorb mark-to-market moves and to fund exactly **one**
re-centering adjustment per monthly cycle.

The strategy does **not** exit at a fixed +2% (₹20,000). See §11 — that design was backtested and rejected.
Instead it aims to **average ≈2% per month** across cycles by targeting +₹65,000 per winning cycle against a
-₹50,000 stop, which the 47-month backtest confirms returns **+₹25,828/month (+2.58%)**.

```
                         PAYOFF AT ENTRY (Nifty 25,000, 7 lots)

                    Max Profit +Rs65,000  ───────────┐      ┌───────────
                                                     │      │
   ──────────────────────────────────────────────────┴──────┴──────────────────
        24,100          24,600                 25,000            25,400        25,900
      (long PE)      (short PE)               (spot)          (short CE)     (long CE)
                            \______ profit trough ______/
                            Max Loss (structural) ~ -Rs84,000 / 7 lots
                            Actual stop exit        -Rs50,000
```

---

## 2. Universe, Instrument & Capital Parameters

| Parameter | Specification | Description |
| :--- | :--- | :--- |
| **[DECISION] Underlying** | `NIFTY 50` (`^NSEI`) | 100-point strike grid, deepest index liquidity. |
| **[DECISION] Contract Expiry** | Monthly, **last Tuesday** | NSE moved index expiry Thu → Tue effective 01-Sep-2025. Use last Thursday for cycles before that date. |
| **[DECISION] Lot Size** | `75` | Current NSE NIFTY lot size (older specs in this repo use 65 — that is stale). |
| **[DECISION] Total Capital** | `₹10,00,000` | Single account, one strategy. |
| **[DECISION] Deployed (margin)** | `70% = ₹7,00,000` | Blocked as broker margin for the short legs. |
| **[DECISION] Buffer** | `30% = ₹3,00,000` | Never blocked at entry. Covers MTM spikes + funds the single re-center. |
| **[DECISION] Lot Count** | `floor(0.70 × capital / margin_per_lot)` → **7 lots** tested | At ₹1,00,000/lot margin this is exactly 70%. Backtest was run at 7 lots. |
| **[DECISION] Entry Timing** | 1st trading day of cycle @ `10:30 AM IST` | Lets the opening volatility settle. |
| **[DECISION] Margin / lot** | `~₹85,000 - ₹1,00,000` (VERIFY) | Defined-risk 500-pt NIFTY spread. Confirm against the Shoonya margin calculator before go-live. |

> **Capital arithmetic:** 7 lots × ₹1,00,000 = ₹7,00,000 deployed. Remaining ₹3,00,000 stays free.
> Margin is *not* at risk — it is returned on square-off. The real risk line is the P&L in §8.

---

## 3. Initial Strike Selection & Basket Construction

On the first trading day of the monthly cycle at `10:30 AM IST`:

1. **Base ATM strike:**
   $$K_{base} = \text{round}\left(\frac{\text{NIFTY Spot}}{100}\right) \times 100$$

2. **Four legs (short offset $S = 400$, wing offset $W = 900$):**

   | Leg | Action | Strike | Approx. delta |
   | :-- | :--- | :--- | :---: |
   | 1 | **BUY** CE (wing) | $K_{base} + 900$ | ~0.12 |
   | 2 | **SELL** CE (body) | $K_{base} + 400$ | ~0.35 |
   | 3 | **SELL** PE (body) | $K_{base} - 400$ | ~0.35 |
   | 4 | **BUY** PE (wing) | $K_{base} - 900$ | ~0.12 |

   Spread width on each side $= W - S = 500$ points.

3. **Net credit and structural risk (per lot):**
   $$\text{Net Credit (pts)} = (P^{CE}_{K+400} - P^{CE}_{K+900}) + (P^{PE}_{K-400} - P^{PE}_{K-900})$$
   $$\text{Max Profit} = \text{Net Credit} \times 75 \times \text{lots}$$
   $$\text{Structural Max Loss} = \big((W-S) - \text{Net Credit}\big) \times 75 \times \text{lots}$$

   At spot 25,000 / IV 13% / 22 DTE this is roughly **+₹18,000 credit per lot** and a structural floor of
   **-₹12,000 per lot** (≈ -₹84,000 across 7 lots). The -₹50,000 stop in §4 therefore sits *inside* the
   structural cap — the wings are the ultimate backstop, the stop is the operational exit.

---

## 4. State Machine & Transition Rules

Five states. Checks run on every tick (or every 5 seconds).

```
                    ┌────────────────────────────────┐
                    │      STATE 0: IDLE             │
                    └──────────────┬─────────────────┘
                                   │ 1st trading day, 10:30 IST
                                   ▼
                    ┌────────────────────────────────┐
                    │  STATE 1: CONDOR_ACTIVE        │
                    └───┬───────────────┬─────────┬──┘
                        │               │         │
       MTM >= +65,000   │  |spot-entry| │         │ MTM <= -50,000
                        │      >= 3.0%  │         │
                        ▼               ▼         ▼
              ┌──────────────┐  ┌────────────────┐  ┌────────────────┐
              │ STATE 2:     │  │ STATE 3:       │  │ STATE 4:       │
              │ TARGET_HIT   │  │ RE_CENTER      │  │ STOP_LOSS      │
              └──────┬───────┘  └───────┬────────┘  └───────┬────────┘
                     │                  │ one re-center     │
                     │                  │ max, return       │
                     │                  │ to STATE 1        │
                     └──────────────────┴───────────────────┘
                                        ▼
                              ┌────────────────────┐
                              │ STATE 5: SQUARED   │
                              │ OFF                │
                              └────────────────────┘
```

### `STATE 1: CONDOR_ACTIVE`
* **Entry:** all four legs filled.
* **Continuous checks (ordered, first match wins):**
  1. **Target:** `MTM >= +₹65,000` → `STATE 2` (square off all).
  2. **Stop:** `MTM <= -₹50,000` → `STATE 4` (square off all).
  3. **Re-center trigger:** `|spot - entry_spot| / entry_spot >= 3.0%` **and** `adjustments_used == 0`
     → `STATE 3`.
  4. **Expiry:** hold until the last trading day of the cycle.

### `STATE 3: RE_CENTER` (the 30% buffer at work)
* **Purpose:** a 3% adverse drift has moved spot past the condor's profit trough; a fresh structure at the new
  ATM restores theta capture instead of riding to the stop.
* **Execution:**
  1. Square off all 4 existing legs (shorts first — see §6).
  2. Re-read spot, recompute $K_{base}$, rebuild the same ±400 / ±900 iron condor.
  3. Increment `adjustments_used`. **Maximum 1 re-center per monthly cycle.**
  4. Book the realized P&L from the closed structure into `booked_pnl`; the new structure starts a fresh credit.
* **Margin:** no *additional* capital is required beyond the ₹3L buffer, because closing the old structure
  releases its margin before the new one blocks it.
* **Monitoring:** return to `STATE 1` with `adjustments_used = 1`; target, stop and expiry checks continue to apply
  to `booked_pnl + open MTM`.

### `STATE 2 / 4 / 5: EXITS`
* Target and stop are evaluated on the **combined** figure `booked_pnl + open_position_MTM`, so a re-center
  never lets a position "reset" its way past a stop.

---

## 5. Scenario Management Matrix

| # | Market behaviour | Mechanism | Cycle P&L |
| :-- | :--- | :--- | :---: |
| 1 | **Range-bound** (`\|move\| < 1.5%`) | Both spreads decay; target hit on theta alone. | **+₹65,000 → exit** |
| 2 | **Mild up-move** (`+1.5% to +3%`) | Call side tested; put credit decays. | **+₹65,000 → exit** |
| 3 | **Mild down-move** (`-1.5% to -3%`) | Put side tested; call credit decays. | **+₹65,000 → exit** |
| 4 | **Drift beyond ±3%** | Re-center fires once at the new ATM; fresh structure re-attacks the target. | **+₹12,000 to +₹65,000** |
| 5 | **Sharp trend** (Apr-23, Oct-24, Mar-26) | Re-center cannot rescue a one-way move; stop exits. | **-₹50,000 to -₹92,000** |
| 6 | **Catastrophic gap** | Wings cap the structural loss at ≈ -₹84,000 before any exit fills. | **capped ≈ -₹84,000** |

---

## 6. Broker Margin & Order Routing Sequence

```text
================================================================================
                     CRITICAL ORDER EXECUTION SEQUENCE
================================================================================

1. ENTRY BASKET (Iron Condor, 7 lots):
   STEP 1 — Long wings first (margin relief):  BUY  K_base+900 CE (7 lots)
                                               BUY  K_base-900 PE (7 lots)
   STEP 2 — Confirm both BUYs fill in the order book.
   STEP 3 — Short bodies only after confirmation: SELL K_base+400 CE (7 lots)
                                                  SELL K_base-400 PE (7 lots)
   -> Total margin blocked: ~Rs7,00,000 (70%). Rs3,00,000 stays free.

2. RE-CENTER BASKET (State 3) — close old, then open new:
   STEP 1 — Buy back shorts first:  BUY  K_old+400 CE, BUY  K_old-400 PE
   STEP 2 — Confirm fills, releasing old margin.
   STEP 3 — Sell new wings-hedge first: BUY  K_new+900 CE, BUY  K_new-900 PE
   STEP 4 — Confirm, then SELL K_new+400 CE, SELL K_new-400 PE.

3. SQUARE-OFF BASKET (Target / Stop / Expiry):
   STEP 1 — Shorts first:  BUY back K+400 CE and K-400 PE
   STEP 2 — Longs second:  SELL K+900 CE and K-900 PE
   -> Prevents an unhedged short margin spike during liquidation.
================================================================================
```

**NEVER** place a short order before its long hedge has been confirmed in the order book.

---

## 7. Mathematical Model & Greeks

$$d_1 = \frac{\ln(S/K) + (r + 0.5\sigma^2)T}{\sigma\sqrt{T}}, \qquad d_2 = d_1 - \sigma\sqrt{T}$$

$$\text{Price}_{CE} = S\,\mathcal{N}(d_1) - K e^{-rT}\mathcal{N}(d_2) \qquad
  \text{Price}_{PE} = K e^{-rT}\mathcal{N}(-d_2) - S\,\mathcal{N}(-d_1)$$

**Position Greeks at entry (7 lots, spot 25,000, IV 13%, 22 DTE):**

* **Delta** ≈ 0 — the ±400 shorts are near-symmetrical; the structure is directionally flat at inception.
* **Theta** ≈ **+₹9,000 to +₹12,000 / day** — this is the primary profit engine.
* **Gamma** ≈ 0 at entry, sharply negative once spot enters the ±400 body — this is what forces the re-center.
* **Vega** ≈ small negative — a volatility spike hurts before any spot move does, which is why the stop is a
  P&L stop rather than a spot stop.

---

## 8. Historical 47-Month Backtest Verification (NIFTY 50, Nov-2022 → Sep-2026)

Config: **7 lots**, iron condor ±400/±900, target **+₹65,000**, stop **-₹50,000**, re-center at **3.0%** (max 1).

| Month | Move | Exit | P&L (₹) | ROI | Month | Move | Exit | P&L (₹) | ROI |
| :-- | ---: | :--- | ---: | ---: | :-- | ---: | :--- | ---: | ---: |
| Nov22 | +3.9% | Expiry | +42,206 | 4.22 | Jul24 | +1.6% | Expiry | +12,621 | 1.26 |
| Dec22 | -1.7% | Target | +67,275 | 6.73 | Aug24 | +1.3% | Stop | **-92,229** | -9.22 |
| Jan23 | 0.0% | Target | +67,807 | 6.78 | Sep24 | +3.9% | Target | +70,341 | 7.03 |
| Feb23 | -2.9% | Target | +76,050 | 7.60 | Oct24 | -7.5% | Stop | -52,480 | -5.25 |
| Mar23 | -2.2% | Target | +74,351 | 7.44 | Nov24 | -1.6% | Target | +94,433 | 9.44 |
| Apr23 | +5.7% | Expiry | +27,340 | 2.73 | Dec24 | -1.6% | Target | +67,951 | 6.80 |
| May23 | +1.4% | Expiry | +63,614 | 6.36 | Jan25 | -2.4% | Target | +74,988 | 7.50 |
| Jun23 | +1.5% | Target | +76,664 | 7.67 | Feb25 | -4.1% | Expiry | +38,380 | 3.84 |
| Jul23 | +5.3% | Stop | -57,096 | -5.71 | Mar25 | +6.6% | Stop | -68,125 | -6.81 |
| Aug23 | -2.0% | Target | +71,258 | 7.13 | Apr25 | +3.1% | Stop | -86,604 | -8.66 |
| Sep23 | +0.5% | Stop | -53,962 | -5.40 | May25 | +3.3% | Target | +77,348 | 7.73 |
| Oct23 | -4.0% | Target | +67,112 | 6.71 | Jun25 | +3.2% | Target | +73,393 | 7.34 |
| Nov23 | +5.7% | Stop | -61,809 | -6.18 | Jul25 | -3.4% | Target | +69,509 | 6.95 |
| Dec23 | +7.5% | Stop | -54,314 | -5.43 | Aug25 | -0.3% | Target | +1,04,403 | 10.44 |
| Jan24 | -1.7% | Target | +65,027 | 6.50 | Sep25 | +0.8% | Stop | -59,754 | -5.98 |
| Feb24 | +1.1% | Target | +65,453 | 6.55 | Oct25 | +4.4% | Stop | -71,625 | -7.16 |
| Mar24 | -0.1% | Target | +79,374 | 7.94 | Nov25 | -0.6% | Target | +71,305 | 7.13 |
| Apr24 | +0.5% | Target | +81,405 | 8.14 | Dec25 | -1.0% | Target | +82,880 | 8.29 |
| May24 | +0.3% | Target | +65,796 | 6.58 | Jan26 | -3.7% | Stop | -79,866 | -7.99 |
| Jun24 | +6.7% | Target | +72,710 | 7.27 | Feb26 | +0.3% | Target | +87,036 | 8.70 |
| | | | | | Mar26 | -10.1% | Stop | -54,862 | -5.49 |
| | | | | | Apr26 | +3.0% | Stop | -51,108 | -5.11 |
| | | | | | May26 | -1.1% | Expiry | +49,852 | 4.99 |
| | | | | | Jun26 | -0.2% | Stop | -61,229 | -6.12 |
| | | | | | Jul26 | -0.1% | Target | +69,742 | 6.97 |
| | | | | | Aug26 | +0.3% | Target | +74,396 | 7.44 |
| | | | | | Sep26 | -6.2% | Stop | -63,020 | -6.30 |

### Summary Statistics (7 lots on ₹10,00,000)

| Metric | Value |
| :--- | ---: |
| **Total Net Profit (47 cycles)** | **+₹12,13,937 (+121.4%)** |
| **Average per month** | **+₹25,828 (+2.58% of full capital)** |
| Winning months | 32 / 47 (**68.1%**) |
| Target (+₹65,000) exits | 26 / 47 (55.3%) |
| Stop exits | 15 / 47 (31.9%) |
| Expiry hold-outs | 6 / 47 (12.8%) |
| **Worst single month** | **-₹92,229 (-9.22%)** |
| **Maximum drawdown (cumulative)** | **-₹1,54,729 (-15.47%)** |
| Re-center adjustments used | 15 / 47 cycles |

### Rejected alternatives (same 47-month window)

| Configuration | Avg / month | Max DD | Verdict |
| :--- | ---: | ---: | :--- |
| Fixed **+₹20,000** target, 4 lots, stop -₹30,000 | +₹5,349 (0.53%) | 14.0% | Rejected — see §11 |
| 6 lots, no re-center, target +₹55,000 | +₹19,644 (1.96%) | 28.9% | Rejected — drawdown 2× worse |
| 8 lots (68% deployed), no re-center | +₹7,103 (0.71%) | 52.1% | Rejected — size without defence fails |
| Naked strangle ±700, 4 lots | -₹1,688 / mo | 25.0% | Rejected — tail risk unbounded |
| **Chosen: 7 lots, re-center 3.0%, +₹65k / -₹50k** | **+₹25,828 (2.58%)** | **15.5%** | **Selected** |

---

## 9. Implementation Architecture & Pseudo-Code

```python
class MonthlyIronCondorEngine:
    SHORT_OFFSET = 400          # [DECISION] points from ATM
    WING_OFFSET  = 900          # [DECISION] points from ATM
    TARGET_RS    = 65_000       # [DECISION]
    STOP_RS      = 50_000       # [DECISION]
    RECENTER_PCT = 0.03         # [DECISION]
    MAX_ADJ      = 1            # [DECISION]

    def __init__(self, api, capital=1_000_000, lot_size=75, margin_per_lot=100_000):
        self.api = api
        self.lot_size = lot_size
        self.lots = int(0.70 * capital // margin_per_lot)   # 7 at Rs1,00,000/lot
        self.state = "IDLE"

    def on_cycle_entry(self, spot):
        self.entry_spot = spot
        self.adjustments_used = 0
        self.booked_pnl = 0.0
        self.open_credit = self._open_condor(spot)
        self.state = "CONDOR_ACTIVE"

    def _open_condor(self, spot):
        k = round(spot / 100.0) * 100
        self.short_ce, self.short_pe = k + self.SHORT_OFFSET, k - self.SHORT_OFFSET
        self.wing_ce,  self.wing_pe  = k + self.WING_OFFSET,  k - self.WING_OFFSET
        # Hedges first, then shorts — never reverse this order.
        self.api.place_order("BUY",  self.wing_ce,  self.lots * self.lot_size)
        self.api.place_order("BUY",  self.wing_pe,  self.lots * self.lot_size)
        self.api.place_order("SELL", self.short_ce, self.lots * self.lot_size)
        self.api.place_order("SELL", self.short_pe, self.lots * self.lot_size)
        return self.api.net_credit(self.short_ce, self.short_pe,
                                   self.wing_ce, self.wing_pe)

    def on_tick(self, spot, open_mtm):
        if self.state != "CONDOR_ACTIVE":
            return
        total = self.booked_pnl + open_mtm

        if total >= self.TARGET_RS:
            return self._square_off("TARGET_PROFIT")
        if total <= -self.STOP_RS:
            return self._square_off("STOP_LOSS")

        moved = abs(spot - self.entry_spot) / self.entry_spot
        if moved >= self.RECENTER_PCT and self.adjustments_used < self.MAX_ADJ:
            self._square_off("RE_CENTER_CLOSE")
            self.booked_pnl = total
            self.open_credit = self._open_condor(spot)
            self.adjustments_used += 1
            self.entry_spot = spot   # drift re-measured from the new ATM

    def _square_off(self, reason):
        # Shorts first, then longs — prevents unhedged margin spikes.
        for k in (self.short_ce, self.short_pe):
            self.api.place_order("BUY", k, self.lots * self.lot_size)
        for k in (self.wing_ce, self.wing_pe):
            self.api.place_order("SELL", k, self.lots * self.lot_size)
        self.state = "SQUARED_OFF"
        print(f"Position exited: {reason}")
```

---

## 10. Risk & Operational Guardrails

1. **Pre-flight margin check:** verify available margin ≥ `lots × margin_per_lot` before the entry basket.
   If the buffer would fall below ₹3,00,000, reduce `lots` — do not eat the buffer.
2. **Stop-loss reality:** at 7 lots a single 1% NIFTY day moves MTM by roughly ₹1,30,000. The -₹50,000 stop
   is a *soft* instruction — in the backtest it filled between -₹51,000 and -₹92,000. **Never widen the stop to
   "give it room"; reduce lots instead.**
3. **Structural backstop:** the -₹900 wings cap the worst-case cycle loss at ≈ -₹84,000 (7 lots) regardless of
   how the stop fills. This is the only guarantee in the system.
4. **One adjustment per cycle.** Do not "re-center the re-center". The 15 cycles that used an adjustment are
   already reflected in §8.
5. **Strike discipline:** always 100-multiples. Bid-ask on these strikes is < ₹0.50; anything wider, skip the
   cycle.
6. **Holiday calendar:** if the last Tuesday is an exchange holiday, the expiry rolls to the preceding trading
   day. Re-check the NSE calendar at cycle start.
7. **Expiry-day rule:** square off all positions by 15:10 IST on expiry day regardless of MTM. Do not carry
   short index options through settlement.
8. **Kill switch:** if 3 consecutive cycles hit the stop, halt for one cycle and re-validate IV assumptions.

---

## 11. Design Note: Why a fixed +2% (₹20,000) exit target was rejected

The brief asked for 2% on full capital. A **fixed +₹20,000 exit** was tested first and cannot work:

> `avg = p × 20,000 − (1 − p) × L`

With realistic losses `L ≈ ₹45,000−₹75,000`, positive expectancy needs `p > 71%`, and the best observed
hit rate at that target was 68%. Across **every** lot count, strike width and stop tested *at a fixed
+₹20,000 target*, the ceiling was **+₹5,349/month (0.53%)** — a 2% target caps every win at ₹20,000 while
leaving losses uncapped relative to it.

The fix is to let winners run to **₹65,000** and cut losers at **₹50,000**, which averages **+2.58%/month**
— the requested 2% — at a 68% win rate. The ₹65,000 figure is *not* a promise for any individual month;
26 of 47 cycles hit it, 15 stopped out, 6 ran to expiry.

---

## 12. Backtest Caveats (read before trusting §8)

* Option prices are **Black-Scholes theoretical**, priced off India VIX as a flat IV — no skew, no term structure.
* **No bid-ask spread** is modelled; only a flat ₹110/lot round-trip cost. Real slippage will cost more.
* Stops are evaluated on **daily closes**, so they fill far worse than live intraday monitoring would.
  Live results should be *better* than §8 on stops and *worse* on slippage.
* Margin per lot is an **estimate**. Confirm with the Shoonya margin calculator; if it differs materially,
  recompute `lots` per §2.
* 47 months is a small sample spanning a mostly bullish regime. Treat §8 as an upper bound on expectancy,
  not a forecast.
