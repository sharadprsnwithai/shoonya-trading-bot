# Technical Specification: NIFTY 100 OHL-VWAP Paper-Trading Strategy

## 1. Overview

The **OHL-VWAP Strategy** is an intraday **paper-trading** strategy that scans the **NIFTY 100** stock universe for an opening profile of `open=high` (bearish) or `open=low` (bullish), validates a one-minute opening volume surge, and then waits for a **VWAP crossover** on 5-minute candles to signal an ATM option entry (PE for open=high, CE for open=low). Positions exit when price closes on the opposite side of VWAP, or at the mandatory 15:00:10 IST square-off.

Only **F&O-eligible** stocks are traded. All instrument tokens are resolved **at runtime** via the Shoonya SearchScrip API so that new and recently-listed tickers (ENRIN, TMCV, TMPV, LTM, etc.) resolve correctly without stale hardcoded identifiers.

---

## 2. Core Rules & Parameters

### 2.1 Universe
- **Universe**: NIFTY 100 symbols (`Nifty100Registry`, 100 symbols).
- **Eligibility**: A stock is only admitted if it is **F&O-eligible**, verified at scan time by querying the **NFO** exchange via `searchScrip("NFO", symbol)` and finding a matching option symbol (e.g. `RELIANCE25JUL2500CE`). Non-F&O symbols are skipped and logged.
- **Token Resolution**: `resolveToken(symbol)` seeds from `StockFnoRegistry` where verified; all other symbols fall through to Shoonya `SearchScrip` (matching `SYMBOL-EQ`). Unresolvable symbols are skipped with a warning — never silently mapped to the NIFTY index token `10576`.

### 2.2 Schedule & Timing (Asia/Kolkata)

| Time (IST) | Event |
| :--- | :--- |
| 09:15:00 | Daily strategy state reset (watchlist, positions, trade log cleared). |
| 09:31:10 | Morning scan: first 15-minute candle (09:15–09:30) evaluated for open=high / open=low within 0.1% tolerance; volume filter applied; F&O eligibility verified; Telegram watchlist broadcast. |
| 09:35:10 → 14:55:10 | Every 5 minutes: (a) invalidate setups whose marked level is broken, (b) detect VWAP straddle entries, (c) detect opposite-side-VWAP exits. |
| 15:00:10 | Mandatory square-off of all open positions (JIT option premium) + Telegram exit alerts. |

### 2.3 Opening Profile (09:31:10 scan)

For each symbol, the first 15 minutes (09:15–09:30, fifteen 1-min candles) are aggregated into a single candle:
- **open=high (BEARISH → PE)**: `(high − open) / high ≤ tolerance` (default `0.1%`).
- **open=low (BULLISH → CE)**: `(open − low) / open ≤ tolerance` (default `0.1%`).

The candle's high/low become the **marked levels** used for invalidation.

### 2.4 Volume Filter

- The **first 1-min candle (09:15–09:16)** volume must exceed:
  `volumeMultiplier × SMA20(previous trading day's last 20 one-minute candle volumes)`
- Default `volumeMultiplier = 3.0`. If fewer than 20 prior-day bars exist, the average of whatever is available is used; the filter fails when no prior-day volume exists.

### 2.5 5-Minute Monitoring

For each WATCHLIST setup:
- **Invalidation (marked level broken):**
  - BEARISH: a 5-min candle (after 09:30) with `high > markedHigh` → setup INVALIDATED.
  - BULLISH: a 5-min candle (after 09:30) with `low < markedLow` → setup INVALIDATED.
  - Bars inside the first 15-minute candle (≤ 09:30) are skipped for invalidation.
- **VWAP:** session-anchored VWAP computed over today's 5-min candles using `TechnicalAnalysisService.calculateVwapSeries` (typical price = (H+L+C)/3, cumulative, daily reset).
- **Entry straddle:** latest 5-min candle satisfies `high ≥ VWAP` **and** `low ≤ VWAP` (price crossed VWAP during the bar) with the close on the setup's side:
  - BEARISH (PE): `close ≤ VWAP` (cross top → bottom).
  - BULLISH (CE): `close ≥ VWAP` (cross bottom → top).

### 2.6 Paper Entry (VWAP crossover)

On entry straddle:
1. `StockFnoRegistry.calculateAtmStrike(symbol, stockClose)` → ATM strike (price-tier heuristic strike step for unlisted symbols).
2. `StockFnoRegistry.getLotSize(symbol)` → lot size (defaults to `1` when F&O metadata unknown — P&L then per-share).
3. Live option premium fetched via `searchScrip("NFO", symbol+expiry+strike+CE|PE)` + `fetchQuote("NFO", token)`.
4. `OhlvPaperPosition` created (in-memory), Telegram entry alert with symbol, direction, ATM strike, lot, entry premium.
5. Max concurrent positions capped by `maxConcurrentTrades` (default 3).

### 2.7 Exits

- **VWAP opposite close:** a 5-min candle closes on the opposite side of VWAP:
  - BEARISH (PE): `close > VWAP` → exit.
  - BULLISH (CE): `close < VWAP` → exit.
- **15:00:10 IST mandatory square-off:** all open positions closed at current option premium; remaining WATCHLIST setups transition to CLOSED (`NO_ENTRY_AT_15_00`).

---

## 3. Architecture & Components

```
+-------------------------------------------------------------+
|                     OhlvVwapScheduler                       |
|  09:15 reset | 09:31:10 scan | 09:35-14:55 every 5m | 15:00 |
+-----------------------------+-------------------------------+
                              |
                              v
+-------------------------------------------------------------+
|                  OhlvVwapStrategyService                    |
|  - runMorningScan(): NIFTY 100 universe, first-15 candle    |
|    OHL check, volume filter, NFO F&O eligibility, Telegram  |
|  - runCycle(): invalidation + VWAP straddle entry + exit    |
|  - executeSquareOff() / resetDaily()                        |
|  - Fetch: ShoonyaMarketDataService (1m/5m candles, quote)   |
|  - VWAP: TechnicalAnalysisService.calculateVwapSeries       |
|  - Strikes/Lots: StockFnoRegistry                           |
|  - Alerts: TelegramService (watchlist / entry / exit)       |
+-------------------------------------------------------------+
```

### 3.1 New / Modified Components
- `util/Nifty100Registry` — immutable 100-symbol list + `getKnownToken` (StockFnoRegistry seeds).
- `model/strategy/OhlvDirection` — `BEARISH` (open=high → PE) / `BULLISH` (open=low → CE).
- `model/strategy/OhlvSetup` — WATCHLIST / ENTERED / INVALIDATED / CLOSED state machine.
- `model/strategy/OhlvPaperPosition` — in-memory paper option position with realized P&L.
- `service/OhlvVwapStrategyService` — core logic above.
- `scheduler/OhlvVwapScheduler` — cron wiring.
- `telegram/TelegramService` — `sendOhlvWatchlistAlert`, `sendOhlvEntryAlert`, `sendOhlvExitAlert`.
- Config: `trading-bot.strategy.ohl-vwap.*` in `application.properties` + `.env.example`.

### 3.2 Configuration Parameters

| Key | Env | Default |
| :--- | :--- | :--- |
| `trading-bot.strategy.ohl-vwap.enabled` | `OHL_VWAP_ENABLED` | `true` |
| `trading-bot.strategy.ohl-vwap.scheduler-enabled` | `OHL_VWAP_SCHEDULER_ENABLED` | `true` |
| `trading-bot.strategy.ohl-vwap.scan-cron` | `OHL_VWAP_SCAN_CRON` | `10 31 9 ? * MON-FRI` |
| `trading-bot.strategy.ohl-vwap.cron` | `OHL_VWAP_CRON` | `10 */5 9-14 ? * MON-FRI` |
| `trading-bot.strategy.ohl-vwap.square-off-cron` | `OHL_VWAP_SQUARE_OFF_CRON` | `10 0 15 ? * MON-FRI` |
| `trading-bot.strategy.ohl-vwap.ohl-tolerance-percent` | `OHL_VWAP_OHL_TOLERANCE` | `0.1` |
| `trading-bot.strategy.ohl-vwap.volume-multiplier` | `OHL_VWAP_VOLUME_MULTIPLIER` | `3.0` |
| `trading-bot.strategy.ohl-vwap.max-concurrent-trades` | `OHL_VWAP_MAX_CONCURRENT_TRADES` | `3` |
| `trading-bot.strategy.ohl-vwap.telegram-alerts` | `OHL_VWAP_TELEGRAM_ALERTS` | `true` |

---

## 4. Testing

- `OhlvVwapStrategyServiceTest` — opening-profile classification (incl. tolerance boundary), 15-min aggregation, prior-day volume SMA, marked-level invalidation, VWAP straddle entry (both directions), opposite-side-VWAP exit, monthly expiry formatting/rollover, morning scan end-to-end (admit / volume-reject / skip non-F&O / once-per-day), square-off closing all positions, window guard.
- `Nifty100RegistryTest` — 100 symbols, duplicates, known seeds, new-listing tickers present.
- `TelegramServiceTest` — watchlist / entry / exit message format and disabled-path safety.

---

## 5. Risks & Mitigations

- **Stale tokens for new listings**: all NSE tokens are runtime-resolved via SearchScrip; unresolved symbols are skipped and warned — never mapped to the NIFTY index token.
- **F&O eligibility drift**: checked against NFO at scan time, not a static list.
- **Broker data latency**: all schedules carry a +10s offset after candle close.
- **Option premium unavailable at exit**: square-off falls back to ZERO premium (flat loss) with a warning rather than leaving the paper position open.
- **First-15 candle data gaps**: if fewer than 2 opening bars exist, the symbol is skipped.