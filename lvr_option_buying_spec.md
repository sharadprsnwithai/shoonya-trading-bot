# Change Spec: Lowest Volume Reversal — Convert to ATM Option Buying

**Date:** 2025-07-28
**Scope:** `LowestVolumeReversalService`, `LowestVolumePaperPosition`, `LowestVolumeSetup`, `TelegramService` (LVR methods), `application.properties`

---

## 1. Problem

The Lowest Volume Reversal strategy is currently implemented as a **cash equity intraday (MIS)** strategy — it buys/sells actual shares of stocks. The original strategy is a **pure option buying** strategy: on a LONG signal, buy an ATM CE; on a SHORT signal, buy an ATM PE.

## 2. What Changes

| Aspect | Current (Equity) | New (Options) |
|---|---|---|
| **Instrument** | Cash equity shares (MIS) | ATM options (CE for LONG, PE for SHORT) |
| **Entry** | Buy/Sell shares at stock price | Buy ATM CE (LONG) or ATM PE (SHORT) at premium |
| **Quantity** | Shares = RPT / SL_distance | Lots = RPT / (option SL_distance) × lotSize |
| **SL / Target** | Based on stock price levels | Based on stock price levels (unchanged) |
| **P&L** | (exit - entry) × qty shares | (exit_premium - entry_premium) × lots × lotSize |
| **Premium source** | N/A | Live option premium via Shoonya market data |

## 3. Detailed Changes

### 3.1 `LowestVolumePaperPosition` — Add Option Fields

Add these fields to the position model:

```
String optionType;          // "CE" or "PE"
BigDecimal atmStrike;       // ATM strike at entry time
String optionSymbol;        // Full option symbol (e.g. "RELIANCE25JUL2700CE")
int lotSize;                // Lot size for this underlying
int lots;                   // Number of lots (not raw quantity)
```

Rename `totalQuantity` → `totalQuantity` stays but now means `lots × lotSize` (total option units).

Update constructor to accept option metadata. Update `executePartialBook()` and `close()` P&L formulas:

```java
// LONG option: P&L = (exitPremium - entryPremium) × totalQuantity
// SHORT option (if ever used): P&L = (entryPremium - exitPremium) × totalQuantity
BigDecimal priceDiff = exitPremium.subtract(entryPremium);
pnl = priceDiff.multiply(BigDecimal.valueOf(totalQuantity));
```

### 3.2 `LowestVolumeReversalService` — Entry Logic

#### 3.2.1 New Config Properties

```properties
# Option Buying Mode (replaces cash equity mode)
trading-bot.strategy.lowest-volume.option-buying-enabled=true
trading-bot.strategy.lowest-volume.lots=1                    # default lots per trade
trading-bot.strategy.lowest-volume.expiry-type=monthly       # always monthly expiry
```

#### 3.2.2 Determine Option Type from Direction

```
LONG  → buy ATM CE
SHORT → buy ATM PE
```

#### 3.2.3 ATM Strike Calculation

At entry time, compute ATM strike using `StockFnoRegistry`:

```java
BigDecimal spotPrice = fillPrice;  // stock price at trigger
String symbol = setup.getSymbol();
BigDecimal atmStrike = StockFnoRegistry.calculateAtmStrike(symbol, spotPrice);
String optionType = (dir == LONG) ? "CE" : "PE";
int lotSize = StockFnoRegistry.getLotSize(symbol);
```

If the symbol is not in `StockFnoRegistry`, fall back to price-based strike step logic already in `StockFnoRegistry.getStrikeStep(symbol, spotPrice)`.

#### 3.2.4 Fetch Option Premium

Fetch live ATM premium from Shoonya market data (reuse `fetchOptionPremium` pattern from `PivotSuperTrendOptionSellingStrategy`):

```java
BigDecimal entryPremium = fetchOptionPremium(atmStrike, optionType);
```

If premium is unavailable or zero, skip the trade (do not default to ₹150 like PivotSuperTrend does).

#### 3.2.5 Position Sizing

```
lotSize = StockFnoRegistry.getLotSize(symbol)
slDistance = |entryPremium - slPremium|   // SL in premium terms
lots = floor(RPT / (slDistance × lotSize))
lots = max(1, lots)
totalQuantity = lots × lotSize
```

Where `slPremium` is derived from the stock's trigger candle SL mapped to an option premium estimate (see §3.3).

### 3.3 Trigger / SL / Target — Stock-Level

**SL and signal logic stay on stock price levels.** No premium-based SL mapping.

- **SL** = low of lowest volume candle (LONG) / high of lowest volume candle (SHORT) — **unchanged from current logic**
- **Trailing exit** = SuperTrend(10,3) flip — **unchanged**
- **Target 1** = stock price hits 1:4 RR level — **changed from 1:2**

When a stock-level trigger fires (SL hit, Target 1 hit, SuperTrend flip), close the option position and fetch the **current option premium** at that moment to compute P&L.

The entire state machine and exit logic operates on **stock candles**. The option is just the instrument we hold; its P&L is derived from entry premium vs exit premium at the moments we enter/exit.

### 3.4 Entry Execution (`executePaperTradeEntry`)

```java
public synchronized void executePaperTradeEntry(
        LowestVolumeSetup setup, BigDecimal fillPrice, BigDecimal slPrc) {

    String symbol = setup.getSymbol();
    String optionType = (setup.getDirection() == LONG) ? "CE" : "PE";
    BigDecimal spotPrice = fillPrice;
    BigDecimal atmStrike = StockFnoRegistry.calculateAtmStrike(symbol, spotPrice);
    int lotSize = StockFnoRegistry.getLotSize(symbol);

    // Fetch ATM monthly premium
    BigDecimal entryPremium = fetchOptionPremium(atmStrike, optionType);
    if (entryPremium == null || entryPremium.compareTo(BigDecimal.ZERO) <= 0) {
        log.warn("[LVR] [{}] Could not fetch ATM {} premium for {}. Skipping.", symbol, optionType, atmStrike);
        return;
    }

    // SL stays on STOCK PRICE level (slPrc = low/high of lowest volume candle)
    // No premium-based SL derivation needed.

    // Target 1 stays on STOCK PRICE level (setup.getTarget1Price())
    // P&L computed at exit via entryPremium vs exitPremium.

    // Position sizing: based on stock SL distance in premium terms
    // Estimate premium SL distance = entryPremium × (stockSLDistance / spotPrice)
    // This is approximate — real P&L depends on delta, but gives a reasonable lot count.
    BigDecimal stockSLDistance = setup.getTriggerPrice().subtract(slPrc).abs();
    BigDecimal premiumSLDistance = entryPremium.multiply(stockSLDistance).divide(spotPrice, 2, RoundingMode.HALF_UP);
    if (premiumSLDistance.compareTo(BigDecimal.ZERO) <= 0) premiumSLDistance = entryPremium.multiply(BigDecimal.valueOf(0.3));
    int lots = Math.max(1, (int)(getRiskPerTradeAmount() / (premiumSLDistance.doubleValue() * lotSize)));
    int totalQty = lots * lotSize;

    // Build monthly expiry option symbol
    String expiry = resolveMonthlyExpiry(symbol);
    String optionSymbol = symbol + expiry + atmStrike.intValue() + optionType;

    // Position tracks stock-level SL and target, plus option metadata
    LowestVolumePaperPosition pos = new LowestVolumePaperPosition(
        tradeId, symbol, optionType, optionSymbol, atmStrike, lotSize, lots,
        setup.getDirection(), entryPremium,
        slPrc,                    // stock-level SL (unchanged)
        setup.getTarget1Price(),  // stock-level Target 1 (unchanged)
        totalQty, BigDecimal.valueOf(getRiskPerTradeAmount()), Instant.now());

    openPositions.put(symbol, pos);
    setup.transitionTo(IN_POSITION, "Filled option buy entry");
}
```

### 3.5 Position Evaluation (`evaluateOpenPositions`)

**Signal checks remain on stock price.** Option premium is only fetched at entry and exit for P&L.

```java
LowestVolumePaperPosition pos = entry.getValue();
List<Candle> candles = marketDataService.fetch5MinCandles(symbol, 2);
Candle latest = candles.get(candles.size() - 1);
LowestVolumeDirection dir = pos.getDirection();

// 1. Stop Loss — checked on STOCK PRICE (unchanged)
if (dir == LONG && latest.low().compareTo(pos.getCurrentSl()) <= 0) {
    BigDecimal exitPremium = fetchOptionPremium(pos.getAtmStrike(), pos.getOptionType());
    pos.close(exitPremium, "STOP_LOSS_HIT", Instant.now());
}

// 2. Target 1 — checked on STOCK PRICE (unchanged)
if (!pos.isPartialBooked()) {
    boolean targetHit = (dir == LONG)
        ? latest.high().compareTo(pos.getTarget1Price()) >= 0
        : latest.low().compareTo(pos.getTarget1Price()) <= 0;
    if (targetHit) {
        BigDecimal exitPremium = fetchOptionPremium(pos.getAtmStrike(), pos.getOptionType());
        pos.executePartialBook(exitPremium, Instant.now());
    }
}

// 3. Runner — SuperTrend flip on STOCK (unchanged)
evaluateRunnerSuperTrendExit(symbol, pos, candles, latest);
```

### 3.6 `fetchOptionPremium` Method

Port from `PivotSuperTrendOptionSellingStrategy` or create a shared utility. Uses Shoonya market data to get LTP of the option contract:

```java
private BigDecimal fetchOptionPremium(BigDecimal strike, String optionType) {
    // Build option symbol, fetch last traded price from Shoonya
    // Returns null if unavailable
}
```

### 3.7 Expiry Resolution — Monthly

```java
private String resolveMonthlyExpiry(String symbol) {
    LocalDate today = LocalDate.now(IST);
    // Always use current month expiry
    // If today is past this month's expiry day (last Thursday), use next month
    YearMonth currentMonth = YearMonth.from(today);
    LocalDate expiryDay = currentMonth.with(TemporalAdjusters.lastDayOfWeek());
    // Find last Thursday of month
    while (expiryDay.getDayOfWeek() != DayOfWeek.THURSDAY) {
        expiryDay = expiryDay.minusDays(1);
    }
    if (today.isAfter(expiryDay)) {
        currentMonth = currentMonth.plusMonths(1);
    }
    // Format: "25JUL" per Shoonya convention (YY + MMM)
    return currentMonth.getYear() % 100 + currentMonth.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
}
```

### 3.8 Telegram Alerts — Update Fields

All LVR telegram methods need updating to show option details:

**Entry Alert:**
```
📈 *Strategy:* Lowest Volume Reversal (Option Buy)
📌 *Symbol:* RELIANCE
🎯 *Option:* ATM CE (Strike: ₹2700)
📊 *Entry Premium:* ₹45.30
📦 *Lots:* 2 × 250 = 500 units
🛑 *SL Premium:* ₹31.71 (30% decay)
🎯 *Target 1 (1:4 RR):* ₹58.88
```

**Partial Book Alert:**
```
💰 *TARGET 1 HIT: 50% PROFIT BOOKED* 💰
📌 *Symbol:* RELIANCE CE ₹2700
🎯 *Target Premium:* ₹58.88
✅ *Booked:* 250 units @ ₹58.88
🟢 *Partial P&L:* +₹3,397.50
🛡️ *Remaining:* 250 units, SL → Entry Premium (Breakeven)
```

**Exit Alert:**
```
🔻 *POSITION CLOSED* 🔻
📌 *Symbol:* RELIANCE CE ₹2700
❌ *Reason:* SUPERTREND_FLIP / STOP_LOSS / HARD_EXIT
💰 *Total P&L:* +₹5,200.00
```

### 3.9 `application.properties` Additions

```properties
# Lowest Volume Reversal — Option Buying
trading-bot.strategy.lowest-volume.option-buying-enabled=true
trading-bot.strategy.lowest-volume.lots=1
trading-bot.strategy.lowest-volume.expiry-type=monthly
```

## 4. Files Modified

| File | Change |
|---|---|
| `LowestVolumePaperPosition.java` | Add `optionType`, `atmStrike`, `optionSymbol`, `lotSize`, `lots` fields. Update P&L calc. |
| `LowestVolumeReversalService.java` | Add `fetchOptionPremium()`, `resolveExpiry()`, `deriveSlPremium()`. Rewrite `executePaperTradeEntry()`. Update `evaluateOpenPositions()` to track option premiums. Add config fields. |
| `TelegramService.java` | Update `sendLvrTradeEntryAlert()`, `sendLvrPartialBookAlert()`, `sendLvrTradeExitAlert()` to show option details. |
| `application.properties` | Add option-buying config properties. |
| `LowestVolumeReversalServiceTest.java` | Update tests for option-based positions. |

## 5. What Does NOT Change

- **State machine** (SCANNING → LEG_FORMING → LEG_CONFIRMED → PULLBACK_TRACKING → TRIGGER_ARMED → IN_POSITION) — unchanged
- **Signal detection logic** (initial leg, pullback, lowest volume candle, range filter, exhaustion) — unchanged, all based on stock candles
- **SL logic** — low of lowest volume candle (LONG) / high (SHORT) — unchanged, checked on stock price
- **Target 1 logic** — 1:4 RR on stock price — changed from 1:2
- **SuperTrend runner exit** — unchanged, signal is on stock price
- **Session timing** (09:15 start, 14:45 cutoff, 15:00 hard exit) — unchanged
- **Morning universe scan** — unchanged
- **NIFTY direction filter** — unchanged

**Summary: Only the instrument (shares → options), entry execution, position model, and P&L calculation change. All signal/exit logic stays on stock candles.**

## 6. Resolved Decisions

1. **SL = Stock Price Level.** SL is the **low of the lowest volume candle** (LONG) / **high of the lowest volume candle** (SHORT). Trail via SuperTrend flip. SL is checked on **stock price**, not option premium. When stock hits SL, close the option position at the current option premium.
2. **Expiry = Monthly.** All option trades use **monthly expiry** (not weekly). Use current month expiry; if today is past expiry, use next month.
3. **Premium fetch failure = Hold.** If option premium cannot be fetched mid-position, hold and retry next 5-min cycle. Only exit on stock-level triggers (SL hit, SuperTrend flip, 15:00 hard exit).
