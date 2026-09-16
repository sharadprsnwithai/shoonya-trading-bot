# Specification: LVR Morning Scanner Token Resolution, Startup Pre-Population & Retry Mechanism

**Date:** 2026-09-16  
**Status:** Approved for Implementation  
**Scope:** `StockFnoRegistry`, `Nifty200Registry`, `ShoonyaMarketDataService`, `LowestVolumeReversalService`, `TelegramService`, `application.properties`  
**Related Specs:** `lowest_volume_reversal_spec.md`, `lvr_option_buying_spec.md`

---

## 1. Problem Statement & Root Cause Analysis

During live execution at 09:25 AM IST, the **Lowest Volume Reversal (LVR)** strategy sent a Telegram alert with empty watchlists:
```
🟢 TOP GAINERS (LONG CANDIDATES):
   • None identified

🔴 TOP LOSERS (SHORT CANDIDATES):
   • None identified
```

### Root Cause Breakdown

1. **Invalid Token Resolution (Symbol String Passed as Token):**
   - In `StockFnoRegistry.getToken(symbol)`, if a symbol was not in the 10 hardcoded instruments, the method returned the symbol string itself (`symbol.toUpperCase().trim()`, e.g., `"RELIANCE"`).
   - In `Nifty200Registry`, the static initializer stored these symbol strings as tokens in `StockMetadata`.
   - `ShoonyaMarketDataService.resolveToken(symbol)` read this value and assumed it was a valid token, returning `"RELIANCE"` instead of fetching the numeric instrument token.
   - `fetchQuote("NSE", "RELIANCE")` called Shoonya API's `/NorenWClientAPI/GetQuotes` with `"token": "RELIANCE"`. Shoonya rejected this with `{"stat":"Not_Ok","emsg":"Invalid Token"}` because it requires numeric instrument tokens (e.g., `"2885"`).
   - Consequently, quotes failed for all ~190 Nifty 200 stocks.

2. **Scanner Timeout & Thread Saturation:**
   - For unmapped symbols, fallback `SearchScrip` calls across 100+ stocks flooded the 8-thread pool with 200+ HTTP requests, exceeding the 15-second `CompletableFuture.allOf().get(15, SECONDS)` timeout.

3. **Premature Daily Lock Gate (`universeScanCompletedToday = true`):**
   - Even when `scanUniverse()` failed and returned 0 snapshots, `runMorningUniverseScan()` immediately marked `universeScanCompletedToday = true`.
   - It sent an alert with empty watchlists and never attempted to re-scan on subsequent 5-minute candle cycles (09:30, 09:35, etc.), permanently locking the strategy into monitoring 0 stocks for the day.

---

## 2. Solution Architecture: Startup Pre-Population & Robust Polling

Since the NSE F&O universe (~180 stocks) is fixed and changes only on monthly/quarterly exchange circulars, **all F&O stock metadata (numeric NSE tokens, lot sizes, and strike step sizes) must be fully populated in memory at application startup**.

```
+-----------------------------------------------------------------------------------+
|                            APPLICATION STARTUP                                    |
|                                                                                   |
|  [ StockFnoRegistry / Nifty200Registry ]                                         |
|  - Statically initializes complete active F&O universe (~180 liquid stocks)       |
|  - Maps official numeric NSE tokens (e.g. RELIANCE: 2885, TCS: 11536, INFY: 1594) |
|                                                                                   |
|  [ ShoonyaMarketDataService.@PostConstruct ]                                      |
|  - Warms tokenCache with all F&O tokens from registry                             |
|  - 0 live SearchScrip HTTP API calls required at market open                      |
+-----------------------------------------+-----------------------------------------+
                                          |
                                          v
+-----------------------------------------------------------------------------------+
|                        09:25 AM IST Candle Close Cycle                            |
|                                                                                   |
|  [ LowestVolumeReversalService.runMorningUniverseScan() ]                         |
|  - Fetches live quotes concurrently using pre-warmed numeric tokens (0ms lookup)  |
|  - Completes within ~1-2 seconds for all stocks                                   |
|                                                                                   |
|    +----+-----------------------------+                                           |
|    | Snapshots retrieved successfully?|                                           |
|    +----+-----------------------------+                                           |
|         |                                  |                                      |
|      YES|                                NO| (Broker Network Glitch / 0 stocks)   |
|         v                                  v                                      |
|  1. Rank Top 10 Gainers & Losers    1. Log WARN & keep universeScanCompleted=false|
|  2. Set universeScanCompleted=true  2. Send Telegram Retry Alert                  |
|  3. Lock Daily Fixed Watchlist      3. Next 5-min cycle (09:30, 09:35...)         |
|  4. Send Telegram Success Alert        automatically retries the scan             |
|                                     4. If still failing by Cutoff (10:00 AM),     |
|                                        fallback to 10 Champion Stocks             |
+-----------------------------------------------------------------------------------+
```

---

## 3. Detailed Technical Specifications

### 3.1 Item 1: Pre-Populate Active F&O Universe at Startup & Fix Token Resolution

#### 3.1.1 Full In-Memory F&O Universe in `StockFnoRegistry`
Pre-populate the complete active NSE F&O universe with accurate numeric instrument tokens, lot sizes, and strike steps in `StockFnoRegistry.INSTRUMENTS`.

Key instruments include:
- Benchmark Indices: `NIFTY50` ("10576", step 50, lot 65), `BANKNIFTY` ("26009", step 100, lot 30), `FINNIFTY` ("26037", step 50, lot 65), `MIDCPNIFTY` ("26074", step 25, lot 120), `SENSEX` ("1", step 100, lot 20).
- High-Beta & Large-Cap F&O Equities:
  - `RELIANCE` → Token `"2885"`, Lot `250`, Step `20`
  - `TCS` → Token `"11536"`, Lot `175`, Step `20`
  - `INFY` → Token `"1594"`, Lot `400`, Step `10`
  - `HDFCBANK` → Token `"1333"`, Lot `550`, Step `10`
  - `ICICIBANK` → Token `"4963"`, Lot `700`, Step `10`
  - `SBIN` → Token `"3045"`, Lot `750`, Step `5`
  - `BHARTIARTL` → Token `"10604"`, Lot `475`, Step `10`
  - `TATAMOTORS` → Token `"3456"`, Lot `575`, Step `5`
  - `AXISBANK` → Token `"5900"`, Lot `625`, Step `10`
  - `KOTAKBANK` → Token `"1922"`, Lot `400`, Step `10`
  - `LT` → Token `"11483"`, Lot `150`, Step `20`
  - `BAJFINANCE` → Token `"317"`, Lot `125`, Step `50`
  - `MARUTI` → Token `"10999"`, Lot `50`, Step `100`
  - `TITAN` → Token `"3506"`, Lot `175`, Step `20`
  - `SUNPHARMA` → Token `"3351"`, Lot `350`, Step `10`
  - `WIPRO` → Token `"3787"`, Lot `1500`, Step `5`
  - `ITC` → Token `"1660"`, Lot `1600`, Step `2`
  - `TATASTEEL` → Token `"3499"`, Lot `5500`, Step `1`
  - `HINDALCO` → Token `"1363"`, Lot `1400`, Step `10`
  - `BHEL` → Token `"438"`, Lot `2625`, Step `5`
  - `BSE` → Token `"19585"`, Lot `250`, Step `50`
  - `LAURUSLABS` → Token `"19234"`, Lot `1100`, Step `5`
  - `SAIL` → Token `"2963"`, Lot `4700`, Step `2.5`
  - `POLYCAB` → Token `"9590"`, Lot `125`, Step `50`
  - `ADANIENSOL` → Token `"1023"`, Lot `675`, Step `20`
  - `MCX` → Token `"31181"`, Lot `125`, Step `50`
  - `ADANIGREEN` → Token `"3563"`, Lot `500`, Step `20`
  - `TORNTPHARM` → Token `"3518"`, Lot `250`, Step `50`
  - *(All remaining active NSE F&O equities pre-mapped)*

#### 3.1.2 Fix `StockFnoRegistry.getToken(symbol)` Return Contract
* **Rule:** If the symbol is not found in `INSTRUMENTS`, return `null`. Never return `symbol.toUpperCase().trim()`.
* **Implementation (`StockFnoRegistry.java`):**
  ```java
  public static String getToken(String symbol) {
      if (symbol != null) {
          InstrumentInfo info = INSTRUMENTS.get(symbol.toUpperCase().trim());
          if (info != null) {
              return info.token();
          }
      }
      return null;
  }
  ```

#### 3.1.3 Cache Warming at Startup (`ShoonyaMarketDataService`)
* In `ShoonyaMarketDataService`, implement `@PostConstruct` initialization to pre-populate `tokenCache` directly from `StockFnoRegistry` and `Nifty200Registry`:
  ```java
  @PostConstruct
  public void warmTokenCache() {
      for (Map.Entry<String, StockFnoRegistry.InstrumentInfo> entry : StockFnoRegistry.getAllInstruments().entrySet()) {
          if (entry.getValue().token() != null && entry.getValue().token().matches("\\d+")) {
              tokenCache.put(entry.getKey(), entry.getValue().token());
          }
      }
      log.info("[MARKET-DATA] Token cache warmed with {} pre-registered F&O instruments at startup.", tokenCache.size());
  }
  ```

#### 3.1.4 Numeric Token Validation in `resolveToken`
* Ensure `resolveToken` accepts only valid numeric tokens:
  ```java
  private boolean isValidNumericToken(String token) {
      return token != null && !token.isBlank() && token.matches("\\d+");
  }
  ```

---

### 3.2 Item 3: Conditional Lock, 5-Min Scheduled Retry & Telegram Alerts

#### 3.2.1 Conditional Watchlist Locking in `LowestVolumeReversalService`
* `universeScanCompletedToday` is set to `true` **only** if `!currentTopGainers.isEmpty() || !currentTopLosers.isEmpty()`.
* If 0 stocks qualify or network fails:
  1. `universeScanCompletedToday` remains `false`.
  2. If current time is before `10:00 AM IST` (`scannerFallbackCutoff`), send a Telegram retry notification:
     ```markdown
     ⚠️ *[LOWEST VOLUME REVERSAL: SCAN RETRY PENDING]* ⚠️

     🕒 *Scan Time:* 16-Sep-2026 09:25:11 IST
     🧭 *NIFTY 50 Direction:* 🟢 BULLISH (Longs Eligible)
     📊 *Status:* ⚠️ Data snapshot incomplete (0 stocks qualified)
     🔄 *Action:* Daily watchlist not fixed yet. Strategy will automatically retry at *09:30:10 IST*.
     ```
  3. If current time is past cutoff (e.g. >= 10:00 AM), apply the 10 Champion stocks fallback and lock the list.

#### 3.2.2 Automatic Retry via 5-Minute Scheduler
* In `runCycle(LocalTime nowTime)`:
  ```java
  if (!universeScanCompletedToday) {
      log.info("[LVR] Daily watchlist not yet established. Running universe scan at {} IST...", nowTime);
      runMorningUniverseScan();
      if (!universeScanCompletedToday) {
          log.info("[LVR] Universe scan retry pending. Skipping setup processing for this cycle.");
          return;
      }
  }
  ```
* At `09:30:10`, `09:35:10`, `09:40:10`... until 11:00 AM cutoff, each 5-minute candle trigger retries the universe scan until quotes are captured and the watchlist is established.

---

## 4. Configuration Properties

```properties
# Scanner Fallback Cutoff Time (HH:mm) after which champion basket is applied if scans keep failing
trading-bot.strategy.lowest-volume.scanner-fallback-cutoff=10:00

# Scanner Snapshot Batch Timeout in seconds
trading-bot.strategy.lowest-volume.scanner-timeout-seconds=30
```

---

## 5. Acceptance Test Cases

| Test Case | Description | Expected Outcome |
|---|---|---|
| `testTokenCache_WarmedAtStartup` | Check `tokenCache` after `warmTokenCache()` | Contains all major F&O symbols mapped to numeric tokens |
| `testStockFnoRegistry_ReturnsNumericOrNull` | Query `getToken("RELIANCE")` and `getToken("UNKNOWN")` | Returns `"2885"` and `null` respectively |
| `testUniverseScan_FastExecutionWithPrewarmedTokens` | Run `scanUniverse()` with pre-warmed tokens | Completes without any `SearchScrip` API calls |
| `testMorningScan_RetryOnZeroSnapshots` | Cycle 1 produces 0 snapshots; Cycle 2 produces quotes | Cycle 1 keeps `completed=false` & sends retry alert; Cycle 2 establishes list & sets `completed=true` |
| `testMorningScan_FallbackAtCutoff` | Time >= 10:00 AM with empty quotes | Locks watchlist with 10 Champion stocks |

---

## 6. Execution Tasks

- [ ] **Task 1:** Expand `StockFnoRegistry` with complete active F&O universe tokens, lots, and steps.
- [ ] **Task 2:** Fix `StockFnoRegistry.getToken` to return `null` for unknown symbols.
- [ ] **Task 3:** Add `@PostConstruct` cache warming and numeric token validation to `ShoonyaMarketDataService`.
- [ ] **Task 4:** Refactor `LowestVolumeReversalService.runMorningUniverseScan()` and `runCycle()` with conditional latching and 10:00 AM fallback.
- [ ] **Task 5:** Add `sendLvrScanRetryAlert` to `TelegramService`.
- [ ] **Task 6:** Add comprehensive unit tests in `StockFnoRegistryTest`, `LowestVolumeReversalServiceTest`, and `ShoonyaMarketDataServiceTest`.
