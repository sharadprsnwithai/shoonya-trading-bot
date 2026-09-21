# Technical Design: Yahoo Finance OHLC Caching & System Hardening

**Date:** 2026-09-18  
**Status:** Approved  
**Target:** Shoonya Trading Bot (Intraday LVR, RSI Highway Swing, Positional Bollinger Band)

---

## 1. Problem Statement & Motivation

During live market operations on 2026-09-18, the bot generated zero signals and encountered critical failures:
1. **Shoonya Historical API Outage (HTTP 504):** At 15:00 IST, the RSI Highway scan of 501 symbols attempted 501 sequential calls to Shoonya TPSeries for 750 daily candles. Shoonya returned HTTP 504 Server Timeouts (~50s delay per symbol), causing total scan failure and indicator starvation.
2. **Single-Threaded Task Scheduling Starvation:** Spring Boot's default single-threaded `@Scheduled` pool (`scheduling-1`) was occupied for hours by the timing-out RSI Highway scan, completely blocking the 15:00 IST Positional Bollinger Band scan.
3. **Unencoded URL Form Data in Broker SearchScrip:** Searching for symbols containing special characters (e.g., `ARE&M`) produced HTTP 400 Bad Request (`jData is not valid json object`) because unencoded `&` characters corrupted form-urlencoded parameters.

---

## 2. Solution Overview

1. **Yahoo Finance Historical OHLC Ingestion & Local JSON Cache:**
   - At startup and scheduled daily at **08:00 AM IST**, check for local cache `data/historical_ohlc.json`.
   - If missing or stale ($< \text{last completed trading day}$), fetch 2 years of daily data from Yahoo Finance API for all ~520 symbols (Nifty 500 + F&O Universe + NIFTY 50 index).
   - Resample daily data into Weekly (`1W`) and Monthly (`1M`) bars using `CandleResamplingUtil`.
   - Atomically persist to `data/historical_ohlc.json` and keep an in-memory `ConcurrentHashMap` for sub-millisecond lookup.
2. **Multi-Timeframe Strategy Integration:**
   - At 15:00 IST, `RsiHighwaySwingService` and `BollingerHaPositionalService` read 750 daily/weekly/monthly candles instantly from cache, fetch today's live/closing quote, append today's candle, and compute RSI/EMA/ATR across all 501 symbols in under 3 seconds.
3. **System Hardening & Concurrency Configuration:**
   - **`TaskSchedulingConfig`:** Configure `ThreadPoolTaskScheduler` with `poolSize = 5` and custom thread name prefix `trading-task-`.
   - **Strict Broker Timeouts & Circuit Breaker:** Configure 10-second HTTP call timeout; fast-abort if consecutive 504 timeouts are detected.
   - **Form URL Encoding:** Standardize `URLEncoder.encode(..., UTF_8)` for all `jData` and `jKey` payloads across Shoonya API calls.

---

## 3. Detailed Architecture & Components

### 3.1 Data Model (`HistoricalOhlcData`)

Stored at `data/historical_ohlc.json`:
```json
{
  "lastUpdated": "2026-09-18T08:00:00Z",
  "symbolCount": 520,
  "symbols": {
    "RELIANCE": {
      "daily": [
        { "timestamp": 1726617600, "open": 2900.0, "high": 2950.0, "low": 2890.0, "close": 2940.0, "volume": 1200000 }
      ],
      "weekly": [ ... ],
      "monthly": [ ... ]
    },
    "NIFTY 50": {
      "daily": [ ... ],
      "weekly": [ ... ],
      "monthly": [ ... ]
    }
  }
}
```

### 3.2 Yahoo Finance Client (`YahooFinanceService`)
- Endpoint: `https://query1.finance.yahoo.com/v8/finance/chart/{TICKER}?range=2y&interval=1d`
- Symbol Mapping:
  - Equities: `{SYMBOL}.NS` (e.g. `TCS` $\rightarrow$ `TCS.NS`, `M&M` $\rightarrow$ `M%26M.NS`, `ARE&M` $\rightarrow$ `ARE%26M.NS`)
  - Indices: `NIFTY 50` / `NIFTY` $\rightarrow$ `^NSEI`
- Rate-limiting & Resilience:
  - User-Agent header (standard desktop browser emulation).
  - Pacing delay of 50ms between requests (with optional batching).
  - Retry logic for transient 429/5xx responses.

### 3.3 Cache Manager (`HistoricalOhlcCacheService`)
- **Memory Cache:** `ConcurrentHashMap<String, SymbolOhlcBundle>` where `SymbolOhlcBundle` holds:
  - `List<Candle> daily`
  - `List<Candle> weekly`
  - `List<Candle> monthly`
- **Methods:**
  - `List<Candle> getDailyCandles(String symbol)`
  - `List<Candle> getWeeklyCandles(String symbol)`
  - `List<Candle> getMonthlyCandles(String symbol)`
  - `boolean isCacheValidForToday()`
  - `void syncAllIfStaleOrMissing()`
  - `void saveToFile()`
  - `void loadFromFile()`
- **Persistence Safety:**
  - Saves to `data/historical_ohlc.json.tmp` and replaces atomically via `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`.

### 3.4 Schedulers & Runners
- **`HistoricalOhlcScheduler`:**
  - Runs at **08:00 AM IST** Monday–Friday (`@Scheduled(cron = "0 0 8 ? * MON-FRI", zone = "Asia/Kolkata")`).
  - Calls `cacheService.syncAllIfStaleOrMissing()`.
- **`StartupSyncRunner`:**
  - On application startup, verifies cache existence. If missing/stale, runs initial sync in background thread so bot is immediately operational.

### 3.5 Strategy Integration (`RsiHighwaySwingService`)
- Replaces direct network loop to Shoonya `TPSeries` for 501 symbols.
- Evaluates symbols using:
  ```java
  List<Candle> cachedDaily = ohlcCacheService.getDailyCandles(sym);
  // Merge cached history (up to prior trading day) with today's live/closing candle from Shoonya
  List<Candle> fullDaily = appendTodayCandleIfAvailable(cachedDaily, liveQuote);
  MultiTimeframeRsiSnapshot snap = multiTimeframeRsiService.computeSnapshot(sym, fullDaily);
  ```

### 3.6 Task Scheduling Concurrency (`TaskSchedulingConfig`)
```java
@Configuration
public class TaskSchedulingConfig implements SchedulingConfigurer {
    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("trading-task-");
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.initialize();
        taskRegistrar.setTaskScheduler(scheduler);
    }
}
```

### 3.7 URL Form-Encoding Fix (`ShoonyaMarketDataService`)
- Ensure all form POST bodies encode payload and key:
  ```java
  String formBody = "jData=" + URLEncoder.encode(jDataStr, StandardCharsets.UTF_8)
                  + "&jKey=" + URLEncoder.encode(sessionToken, StandardCharsets.UTF_8);
  ```

### 3.8 REST Endpoints (`HistoricalOhlcController`)
- **`POST /api/v1/ohlc/sync`**:
  - Request Params:
    - `force` (boolean, optional, default: `false`) - Force re-download even if cache is fresh.
    - `symbol` (string, optional) - Target a specific symbol or all if omitted.
  - Response:
    ```json
    {
      "status": "SUCCESS",
      "message": "OHLC sync completed successfully",
      "symbolsUpdated": 520,
      "syncTime": "2026-09-18T08:00:00Z"
    }
    ```
- **`GET /api/v1/ohlc/status`**:
  - Response:
    ```json
    {
      "cachedSymbols": 520,
      "lastUpdated": "2026-09-18T08:00:00Z",
      "cacheValidForToday": true,
      "stateFilePath": "data/historical_ohlc.json"
    }
    ```

---

## 4. Testing & Verification Plan

1. **Unit Tests:**
   - `YahooFinanceServiceTest`: Parse Yahoo Finance v8 JSON chart responses for regular stocks and index (`^NSEI`).
   - `HistoricalOhlcCacheServiceTest`: Test cache loading, saving, staleness detection, resampling, and atomic file write.
   - `TaskSchedulingConfigTest`: Verify `ThreadPoolTaskScheduler` is wired with configured pool size.
   - `ShoonyaMarketDataServiceTest`: Verify `searchScrip` and `fetchQuote` handle special characters like `ARE&M` with proper URL encoding.
2. **Integration / E2E Verification:**
   - Verify `RsiHighwaySwingService` executes EOD scan using cached OHLC data without calling Shoonya TPSeries for historical candles.
   - Verify multi-threaded execution runs LVR and Positional/RSI Highway tasks without thread contention.
