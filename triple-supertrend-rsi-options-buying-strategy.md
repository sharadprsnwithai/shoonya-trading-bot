# Triple SuperTrend + RSI — Directional Options Buying Strategy

**Source**: Extracted from a Hindi-language trading education video (speaker: Bharat Jhunjhunwala). Promotional/mentorship-program content has been stripped out — only the strategy logic is captured below. Timings and a few numeric details in the transcript were spoken quickly/ambiguously; anywhere that happened is flagged explicitly in **Open Ambiguities** at the end rather than silently guessed.

---

## 1. Strategy Summary

A trend-following, **options-buying-only** strategy (no option selling). It uses three SuperTrend indicators of different lengths, stacked for confirmation, plus an RSI momentum filter to avoid low-conviction entries. Trades ATM (at-the-money) CE/PE only — never OTM. Designed for intraday-to-multi-day directional option holds (not same-day scalping).

Core philosophy stated by the speaker:
- Only trade **high-probability, selective** setups — not every SuperTrend flip.
- Options buying has strictly limited/defined risk (premium paid), unlike option selling which needs margin and active management.
- No stock-picking or tips are given in the source video — the strategy is meant to be applied to whatever underlying/stock the trader is already tracking.

---

## 2. Indicators Required

### 2.1 Triple SuperTrend
Three SuperTrend indicators are plotted simultaneously on the same chart:

| Label | ATR Period | ATR Multiplier | Role |
|---|---|---|---|
| Fast SuperTrend | 7 | 2 | Used for **exit** signal (fastest to react) |
| Medium SuperTrend | 10 | 3 | Default/standard SuperTrend settings |
| Slow SuperTrend | 14 | 4 | Slowest, confirms the dominant trend |

SuperTrend mechanics (standard definition, for the coding LLM):
- Computed from ATR (Average True Range) — a volatility-based band around price.
- When price is **above** the SuperTrend line, the line/band is plotted **below** price and is colored **green** → uptrend.
- When price is **below** the SuperTrend line, the line/band is plotted **above** price and is colored **red** → downtrend.
- A "flip" occurs when price crosses to the other side of the band, causing the color/side to switch.

### 2.2 RSI (Relative Strength Index)
- Standard RSI, plotted on price.
- **Timeframe used in the video: 1-hour (hourly) candles.** The speaker explicitly says timeframe choice below hourly (5 min, 15 min, etc.) is untried by him — see Open Ambiguities.
- RSI is used only as a **momentum confirmation filter**, not as an independent entry/exit trigger.

---

## 3. Timeframe

- Primary timeframe demonstrated: **1-hour candles**.
- No fixed rule was given for which timeframe is "correct" — the speaker deflects this question and says the viewer should test it themselves. Treat 1H as the default/backtested timeframe; other timeframes are unvalidated variants.

---

## 4. Entry Rules

### 4.1 Bullish Setup → Buy ATM Call (CE)
All of the following must be true:

1. **Trend confirmation**: Price closes above **all three** SuperTrend lines (Fast, Medium, Slow), and all three have turned/are green.
2. **Momentum confirmation (RSI filter)**: In the period just before this signal candle, hourly RSI must **not have broken below the 50–40 zone** — i.e., RSI has been holding at or above roughly 40–50, showing bullish momentum was already intact before the SuperTrend confirmation.
3. **Strike selection**: Buy the **ATM Call** (at-the-money strike closest to current spot/futures price) — never OTM.
4. **Expiry selection**: See Section 6 (DTE rule).

### 4.2 Bearish Setup → Buy ATM Put (PE)
All of the following must be true:

1. **Trend confirmation**: Price closes below **all three** SuperTrend lines (Fast, Medium, Slow), and all three have turned/are red.
2. **Momentum confirmation (RSI filter)**: In the period just before this signal candle, hourly RSI must **not have broken above the 60–50 zone** — i.e., RSI has been capped at or below roughly 50–60, showing bearish momentum was already intact before the SuperTrend confirmation.
3. **Strike selection**: Buy the **ATM Put** (at-the-money strike closest to current spot/futures price) — never OTM.
4. **Expiry selection**: See Section 6 (DTE rule).

> Note: The RSI filter is explicitly described as an *enhancement/refinement* layer on top of the core Triple-SuperTrend trend signal, not a mandatory gate in the most "lemanish" (bare-bones) version of the strategy the speaker first demonstrates. Implement it as a **configurable filter** (on/off flag) so both the "SuperTrend-only" and "SuperTrend+RSI" variants can be backtested. See examples in Section 8 — some trades in the video are shown without the RSI check applied, others with it.

---

## 5. Exit Rule

- Exit the **entire position** as soon as the **Fast SuperTrend (7-period, 2-multiplier)** flips color:
 - For a long Call position: exit when Fast SuperTrend turns **bearish (red)**, i.e. reverses against the trade.
 - For a long Put position: exit when Fast SuperTrend turns **bullish (green)**, i.e. reverses against the trade.
- This is the **only exit signal** described — it acts as the trailing/dynamic stop for the position. The Medium and Slow SuperTrends are not used for exit, only for entry confirmation.
- The speaker mentions optionally scaling out ("partial booking") and adding to the position on pullbacks, but gives no concrete rule for either — flagged in Open Ambiguities.

---

## 6. Expiry / DTE (Days-To-Expiry) Selection Rule

- When entering a trade, check the days remaining to the current month's expiry.
- **If the current month's expiry is close (i.e., entering the trade would leave too few days to expiry), roll to the next month's contract instead.**
- Target **~30–40 days-to-expiry (DTE)** at time of entry, to avoid excessive time-decay (theta) eating into the long option premium.
- Always trade the **ATM strike of the expiry actually selected** (not necessarily the nearest/current-month expiry).

---

## 7. Position Sizing / Stop-Loss — NOT specified

The video does **not** give:
- A percentage-of-capital or fixed-lot position sizing rule.
- A hard monetary or point-based stop-loss beyond the Fast-SuperTrend-flip exit.

The speaker's claim is that the SuperTrend flip itself acts as a "built-in" stop-loss/exit mechanism — no separate numeric SL is defined. Implementations should treat this as a real gap: consider adding a hard % stop-loss on option premium as a safety net, and flag this decision to the user rather than assuming a number.

---

## 8. Worked Examples From the Video (for backtesting sanity checks)

> Dates/times are as spoken in the video. A couple of clock-times were spoken ambiguously (e.g. "सवा5 बजे") — treat exact intraday timestamps in these examples as approximate; the date and price levels are the reliable anchors.

### Example 1 — Bearish / Buy PE (Bharat Dynamics Ltd, BDL)
- Setup: All 3 SuperTrends turn red, price closes below all three.
- Entry: **22 July 2025, ~10:15 AM**, spot/futures around **₹607**.
- Strike bought: **600 PE** (next month's expiry — August — chosen because July expiry was too near).
- Entry premium: **₹13.75**.
- Exit trigger: Fast SuperTrend turns green.
- Exit: **7 August 2025**, PE premium **₹61.10**.

### Example 2 — Bearish / Buy PE, with RSI confirmation (also BDL, per transcript)
- Setup: All 3 SuperTrends red; RSI was repeatedly failing to break above the 60 level just before the signal (bearish momentum confirmation).
- Entry: **10 July**, spot/futures around **₹1900**.
- Strike bought: **1900 PE** (ATM).
- Entry premium: **₹65**.
- Exit trigger: Fast SuperTrend turns green.
- Exit: **21 July, ~1:00 PM**, PE premium **~₹185**.

### Example 3 — Bullish / Buy CE, with RSI confirmation (IIFL Finance)
- Setup: All 3 SuperTrends turn green, price closes above all three; RSI was holding above the 40 level just before the signal (bullish momentum confirmation).
- Entry: **7 July**, spot/futures around **₹488**.
- Strike bought: **490 CE** (ATM).
- Entry premium: **₹16.88**.
- Exit trigger: Fast SuperTrend turns red.
- Exit: **22 July, ~10:15 AM**, CE premium **~₹50**.

Use these three as regression/sanity-check cases once the indicator + signal logic is coded — the entry/exit dates, strikes, and approximate premiums should reproduce close to what's listed above (allowing for slight differences based on exact broker/data-vendor SuperTrend and RSI calculation conventions).

---

## 9. Suggested State Machine (pseudocode for the coding LLM)

```
STATE: FLAT, LONG_CALL, LONG_PUT

for each new candle (1H):
    compute ST_fast(7,2), ST_medium(10,3), ST_slow(14,4)
    compute RSI(14) on price   # period assumed standard 14; not stated in video — CONFIRM with user

    all_bullish = (price > ST_fast) and (price > ST_medium) and (price > ST_slow)
    all_bearish = (price < ST_fast) and (price < ST_medium) and (price < ST_slow)

    rsi_bullish_ok = RSI has not closed below ~40-50 in the lookback window before this candle
    rsi_bearish_ok = RSI has not closed above ~50-60 in the lookback window before this candle

    if STATE == FLAT:
        if all_bullish and (rsi_filter_enabled == false or rsi_bullish_ok):
            select_expiry_by_dte_rule()
            strike = get_ATM_strike(current_price)
            BUY CE at strike
            STATE = LONG_CALL

        elif all_bearish and (rsi_filter_enabled == false or rsi_bearish_ok):
            select_expiry_by_dte_rule()
            strike = get_ATM_strike(current_price)
            BUY PE at strike
            STATE = LONG_PUT

    elif STATE == LONG_CALL:
        if ST_fast turns bearish (price closes below ST_fast, color flips red):
            SELL to close CE position
            STATE = FLAT

    elif STATE == LONG_PUT:
        if ST_fast turns bullish (price closes above ST_fast, color flips green):
            SELL to close PE position
            STATE = FLAT
```

Notes for implementation:
- `select_expiry_by_dte_rule()` = pick current month's expiry unless DTE < some threshold (video implies "too close" without an exact day count beyond the 30–40 DTE target — treat 30 days as a reasonable minimum-DTE cutoff to roll to next month, but confirm with the user).
- `get_ATM_strike()` should round to the nearest available strike interval for the instrument (varies by underlying).
- The "lookback window before this candle" for the RSI filter is not numerically defined in the video (how many candles back to check for a 40/60 breach) — needs to be a configurable parameter (e.g., N candles, or "since the last SuperTrend flip on the fast line").

---

## 10. Open Ambiguities (do not assume — confirm with user before hardcoding)

1. **RSI period** — not stated in the video (standard 14 assumed above; confirm).
2. **RSI lookback window** for the "hasn't broken 40/60" check — not quantified (e.g., last 5 candles? since last trend flip?).
3. **Exact intraday entry/exit timestamps** in the worked examples are spoken ambiguously in the source audio ("सवा5 बजे" etc.) — only the dates and price/premium levels should be treated as reliable for backtest validation.
4. **No hard stop-loss** is defined beyond the Fast-SuperTrend-flip exit — decide whether to add a safety-net % stop-loss on premium.
5. **No position sizing rule** (capital %, number of lots) is given.
6. **Partial profit booking and pullback re-entries** are mentioned as possibilities by the speaker but with zero concrete rules — treat as an optional, separately-specified enhancement, not part of the core system.
7. **Timeframe below 1H** (5 min, 15 min) is explicitly untested by the speaker — if building a lower-timeframe variant, treat it as a separate, unvalidated configuration.
8. **DTE roll threshold**: "too close to expiry" is not given an exact day-count trigger; only the 30–40 DTE *target* is stated.
9. Whether the RSI filter is mandatory or optional in the "final" version of the system is ambiguous — the speaker demonstrates both a bare SuperTrend-only entry and an RSI-refined entry. Recommend implementing it as a toggle rather than picking one.
