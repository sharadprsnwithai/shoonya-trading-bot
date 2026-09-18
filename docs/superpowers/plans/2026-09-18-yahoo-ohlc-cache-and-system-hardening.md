# Yahoo Finance OHLC Caching & System Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Yahoo Finance historical daily/weekly/monthly OHLC ingestion and local JSON caching, expose a REST trigger endpoint, integrate cached data into multi-timeframe swing/positional strategies, and harden system scheduling and broker URL encoding.

**Architecture:** A standalone `YahooFinanceService` queries Yahoo Finance v8 chart API; `HistoricalOhlcCacheService` resamples bars into Weekly/Monthly series and persists them atomically to `data/historical_ohlc.json`. A scheduled 08:00 AM job and `HistoricalOhlcController` REST API trigger syncs, while `RsiHighwaySwingService` consumes the cache to perform instant sub-3-second EOD scans. `TaskSchedulingConfig` configures a 5-thread scheduler pool, and `ShoonyaMarketDataService` enforces UTF-8 URL encoding and HTTP timeouts.

**Tech Stack:** Spring Boot 3.3.4 (Java 21), Jackson JSON, OkHttp / HttpURLConnection, JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-09-18-yahoo-ohlc-cache-and-system-hardening-design.md`

## Global Constraints
- Target Java version: 21
- Keep all existing tests green (`./gradlew test`)
- Use UTF-8 encoding for URL form data
- Atomic file writes using temporary file and `Files.move` with `ATOMIC_MOVE, REPLACE_EXISTING`
- No hardcoded absolute disk paths (use configured `data/historical_ohlc.json` path)

---

### Task 1: System Hardening — URL Form-Encoding & Strict Timeouts in Shoonya Services

**Files:**
- Modify: `src/main/java/com/tradingbot/marketdata/ShoonyaMarketDataService.java`
- Modify: `src/main/java/com/tradingbot/auth/ShoonyaAuthenticator.java`
- Modify: `src/main/java/com/tradingbot/service/ShoonyaOrderService.java`
- Test: `src/test/java/com/tradingbot/marketdata/ShoonyaMarketDataServiceEncodingTest.java`

**Interfaces:**
- Produces: Correctly URL-encoded POST request bodies for all Shoonya API endpoints, handling special characters like `&` in `ARE&M`.

- [ ] **Step 1: Write failing unit test for URL encoding with special characters**

```java
package com.tradingbot.marketdata;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class ShoonyaMarketDataServiceEncodingTest {

    @Test
    void testFormBodyEncodingHandlesAmpersand() {
        String jData = "{\"uid\":\"FA12345\",\"stext\":\"ARE&M\"}";
        String sessionToken = "TOKEN123";
        String formBody = ShoonyaMarketDataService.buildFormBody(jData, sessionToken);

        assertTrue(formBody.contains("jData="));
        assertTrue(formBody.contains("jKey="));
        // Verify jData doesn't contain raw '&' breaking key-value pairs
        String[] parts = formBody.split("&jKey=");
        assertEquals(2, parts.length);
        String decodedJData = URLDecoder.decode(parts[0].replace("jData=", ""), StandardCharsets.UTF_8);
        assertEquals(jData, decodedJData);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.marketdata.ShoonyaMarketDataServiceEncodingTest`
Expected: FAIL (method `buildFormBody` does not exist)

- [ ] **Step 3: Implement helper `buildFormBody` and update all form body building in Shoonya services**

In `ShoonyaMarketDataService.java`, add `public static String buildFormBody(String jDataStr, String sessionToken)` using `URLEncoder.encode(..., StandardCharsets.UTF_8)` and replace all direct `formBody` string concatenations. Ensure HTTP connection timeout is set to 10 seconds.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.marketdata.ShoonyaMarketDataServiceEncodingTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/marketdata/ShoonyaMarketDataService.java src/test/java/com/tradingbot/marketdata/ShoonyaMarketDataServiceEncodingTest.java
git commit -m "fix(marketdata): URL-encode jData and jKey in Shoonya API requests and add 10s timeout"
```

---

### Task 2: Multi-Threaded Task Scheduling Configuration

**Files:**
- Create: `src/main/java/com/tradingbot/config/TaskSchedulingConfig.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/com/tradingbot/config/TaskSchedulingConfigTest.java`

**Interfaces:**
- Produces: `ThreadPoolTaskScheduler` bean with pool size 5 to prevent single-threaded task starvation.

- [ ] **Step 1: Write test for ThreadPoolTaskScheduler configuration**

```java
package com.tradingbot.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@SpringBootTest
public class TaskSchedulingConfigTest {

    @Autowired
    private ThreadPoolTaskScheduler taskScheduler;

    @Test
    void testTaskSchedulerPoolSize() {
        assertNotNull(taskScheduler);
        assertEquals(5, taskScheduler.getPoolSize());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.config.TaskSchedulingConfigTest`
Expected: FAIL (ThreadPoolTaskScheduler not found or pool size != 5)

- [ ] **Step 3: Create `TaskSchedulingConfig.java` and update `application.properties`**

Create `src/main/java/com/tradingbot/config/TaskSchedulingConfig.java`:
```java
package com.tradingbot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
public class TaskSchedulingConfig implements SchedulingConfigurer {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("trading-task-");
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setTaskScheduler(taskScheduler());
    }
}
```
Add `spring.task.scheduling.pool.size=5` in `src/main/resources/application.properties`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.config.TaskSchedulingConfigTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/config/TaskSchedulingConfig.java src/main/resources/application.properties src/test/java/com/tradingbot/config/TaskSchedulingConfigTest.java
git commit -m "feat(config): configure ThreadPoolTaskScheduler with pool size of 5"
```

---

### Task 3: Yahoo Finance Data Service (`YahooFinanceService`)

**Files:**
- Create: `src/main/java/com/tradingbot/marketdata/YahooFinanceService.java`
- Test: `src/test/java/com/tradingbot/marketdata/YahooFinanceServiceTest.java`

**Interfaces:**
- Produces: `List<Candle> fetchDailyCandles(String symbol, int yearsBack)` and ticker conversion logic (`RELIANCE` $\rightarrow$ `RELIANCE.NS`, `NIFTY 50` $\rightarrow$ `^NSEI`).

- [ ] **Step 1: Write unit test with mock Yahoo v8 JSON response**

```java
package com.tradingbot.marketdata;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.model.Candle;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class YahooFinanceServiceTest {

    private YahooFinanceService service;

    @BeforeEach
    void setUp() {
        service = new YahooFinanceService(new ObjectMapper());
    }

    @Test
    void testToYahooTicker() {
        assertEquals("RELIANCE.NS", service.toYahooTicker("RELIANCE"));
        assertEquals("M%26M.NS", service.toYahooTicker("M&M"));
        assertEquals("^NSEI", service.toYahooTicker("NIFTY 50"));
        assertEquals("^NSEI", service.toYahooTicker("NIFTY50"));
    }

    @Test
    void testParseYahooChartResponse() throws Exception {
        String json = """
        {
          "chart": {
            "result": [
              {
                "meta": { "symbol": "RELIANCE.NS" },
                "timestamp": [1726531200, 1726617600],
                "indicators": {
                  "quote": [
                    {
                      "open": [2900.0, 2920.0],
                      "high": [2950.0, 2960.0],
                      "low": [2890.0, 2910.0],
                      "close": [2940.0, 2955.0],
                      "volume": [1000000, 1500000]
                    }
                  ]
                }
              }
            ],
            "error": null
          }
        }
        """;

        List<Candle> candles = service.parseChartResponse("RELIANCE", json);
        assertEquals(2, candles.size());
        assertEquals("RELIANCE", candles.get(0).symbol());
        assertEquals(2900.0, candles.get(0).open().doubleValue(), 0.01);
        assertEquals(2955.0, candles.get(1).close().doubleValue(), 0.01);
        assertEquals(1500000L, candles.get(1).volume());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.marketdata.YahooFinanceServiceTest`
Expected: FAIL (Class not found)

- [ ] **Step 3: Implement `YahooFinanceService`**

Implement `YahooFinanceService` with User-Agent header, ticker mapping, HTTP query with timeout, and JSON response parsing.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.marketdata.YahooFinanceServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/marketdata/YahooFinanceService.java src/test/java/com/tradingbot/marketdata/YahooFinanceServiceTest.java
git commit -m "feat(marketdata): add YahooFinanceService for historical daily OHLC fetching and ticker mapping"
```

---

### Task 4: Historical OHLC Local Cache Service (`HistoricalOhlcCacheService`)

**Files:**
- Create: `src/main/java/com/tradingbot/marketdata/HistoricalOhlcCacheService.java`
- Create: `src/main/java/com/tradingbot/marketdata/model/HistoricalOhlcData.java`
- Create: `src/main/java/com/tradingbot/marketdata/model/SymbolOhlcBundle.java`
- Test: `src/test/java/com/tradingbot/marketdata/HistoricalOhlcCacheServiceTest.java`

**Interfaces:**
- Consumes: `YahooFinanceService`, `CandleResamplingUtil`, Universe providers (Nifty 500, F&O).
- Produces: `getDailyCandles(symbol)`, `getWeeklyCandles(symbol)`, `getMonthlyCandles(symbol)`, `syncAll(boolean force)`, `isCacheValidForToday()`.

- [ ] **Step 1: Write failing unit test for cache loading, saving, and resampling**

```java
package com.tradingbot.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.model.Candle;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class HistoricalOhlcCacheServiceTest {

    @TempDir
    File tempDir;

    private YahooFinanceService yahooService;
    private HistoricalOhlcCacheService cacheService;
    private File cacheFile;

    @BeforeEach
    void setUp() {
        yahooService = mock(YahooFinanceService.class);
        cacheFile = new File(tempDir, "historical_ohlc.json");
        cacheService = new HistoricalOhlcCacheService(yahooService, new ObjectMapper(), cacheFile.getAbsolutePath());
    }

    @Test
    void testSyncAndPersistSymbol() {
        Candle c1 = new Candle("TCS", "D", Instant.parse("2026-09-17T03:45:00Z"), BigDecimal.valueOf(3500), BigDecimal.valueOf(3550), BigDecimal.valueOf(3480), BigDecimal.valueOf(3540), 100000L);
        when(yahooService.fetchDailyCandles("TCS", 2)).thenReturn(List.of(c1));

        cacheService.syncSymbol("TCS", 2);

        List<Candle> daily = cacheService.getDailyCandles("TCS");
        assertEquals(1, daily.size());
        assertEquals("TCS", daily.get(0).symbol());
        assertTrue(cacheFile.exists());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.marketdata.HistoricalOhlcCacheServiceTest`
Expected: FAIL

- [ ] **Step 3: Implement `HistoricalOhlcCacheService` and data models**

Implement models and cache service with atomic disk persistence, weekly/monthly resampling via `CandleResamplingUtil`, and universe resolution.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.marketdata.HistoricalOhlcCacheServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/marketdata/HistoricalOhlcCacheService.java src/main/java/com/tradingbot/marketdata/model/ src/test/java/com/tradingbot/marketdata/HistoricalOhlcCacheServiceTest.java
git commit -m "feat(marketdata): add HistoricalOhlcCacheService with local JSON persistence and multi-timeframe resampling"
```

---

### Task 5: REST Controller, 08:00 AM Scheduler & Startup Sync Runner

**Files:**
- Create: `src/main/java/com/tradingbot/controller/HistoricalOhlcController.java`
- Create: `src/main/java/com/tradingbot/scheduler/HistoricalOhlcScheduler.java`
- Modify: `src/main/java/com/tradingbot/runner/StartupSyncRunner.java`
- Test: `src/test/java/com/tradingbot/controller/HistoricalOhlcControllerTest.java`

**Interfaces:**
- Produces: `POST /api/v1/ohlc/sync` and `GET /api/v1/ohlc/status` REST endpoints; `@Scheduled(cron = "0 0 8 ? * MON-FRI", zone = "Asia/Kolkata")` runner.

- [ ] **Step 1: Write WebMvc test for HistoricalOhlcController**

```java
package com.tradingbot.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
public class HistoricalOhlcControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HistoricalOhlcCacheService cacheService;

    @Test
    void testGetOhlcStatus() throws Exception {
        when(cacheService.getCachedSymbolCount()).thenReturn(520);
        when(cacheService.isCacheValidForToday()).thenReturn(true);

        mockMvc.perform(get("/api/v1/ohlc/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cachedSymbols").value(520))
                .andExpect(jsonPath("$.cacheValidForToday").value(true));
    }

    @Test
    void testTriggerSync() throws Exception {
        mockMvc.perform(post("/api/v1/ohlc/sync?force=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        verify(cacheService, atLeastOnce()).syncAll(true);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.controller.HistoricalOhlcControllerTest`
Expected: FAIL (Controller not mapped)

- [ ] **Step 3: Implement `HistoricalOhlcController`, `HistoricalOhlcScheduler`, and update `StartupSyncRunner`**

Implement the REST controller, schedule the 08:00 AM weekday job, and ensure `StartupSyncRunner` initializes cache on startup.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.controller.HistoricalOhlcControllerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/controller/HistoricalOhlcController.java src/main/java/com/tradingbot/scheduler/HistoricalOhlcScheduler.java src/main/java/com/tradingbot/runner/StartupSyncRunner.java src/test/java/com/tradingbot/controller/HistoricalOhlcControllerTest.java
git commit -m "feat(api): expose REST endpoints and 08:00 AM scheduler for Yahoo Finance OHLC sync"
```

---

### Task 6: Strategy Integration & End-to-End Verification

**Files:**
- Modify: `src/main/java/com/tradingbot/strategy/rsihighway/service/RsiHighwaySwingService.java`
- Modify: `src/main/java/com/tradingbot/positional/service/BollingerHaPositionalService.java`
- Test: `src/test/java/com/tradingbot/strategy/rsihighway/service/RsiHighwaySwingServiceCacheTest.java`

**Interfaces:**
- Consumes: `HistoricalOhlcCacheService` to instantly fetch daily/weekly/monthly candles.
- Produces: Instant EOD scans (< 3 seconds for 501 symbols) resilient to broker API outages.

- [ ] **Step 1: Write unit test verifying RsiHighwaySwingService uses cache without Shoonya TPSeries calls**

```java
package com.tradingbot.strategy.rsihighway.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

public class RsiHighwaySwingServiceCacheTest {

    @Test
    void testServiceFetchesFromCacheRatherThanShoonyaTPSeries() {
        // Test that evaluateEodScan retrieves candles from HistoricalOhlcCacheService
        HistoricalOhlcCacheService cacheService = mock(HistoricalOhlcCacheService.class);
        ShoonyaMarketDataService marketDataService = mock(ShoonyaMarketDataService.class);

        List<Candle> dummyCandles = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            dummyCandles.add(new Candle("RELIANCE", "D", Instant.now().minusSeconds(86400L * (250 - i)),
                    BigDecimal.valueOf(2500 + i), BigDecimal.valueOf(2520 + i), BigDecimal.valueOf(2490 + i), BigDecimal.valueOf(2510 + i), 500000L));
        }

        when(cacheService.getDailyCandles("RELIANCE")).thenReturn(dummyCandles);
        List<Candle> result = cacheService.getDailyCandles("RELIANCE");
        assertEquals(250, result.size());
        verify(marketDataService, never()).fetchHistoricalCandles(any(), any(), any(), any(), anyInt());
    }
}
```

- [ ] **Step 2: Update `RsiHighwaySwingService` and `BollingerHaPositionalService` to use `HistoricalOhlcCacheService`**

Wire `HistoricalOhlcCacheService` in both services. For historical daily/weekly candles, query cache first. Fall back to broker only if cache is unavailable.

- [ ] **Step 3: Run all test suites across the whole project**

Run: `./gradlew test`
Expected: ALL TESTS PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/tradingbot/strategy/rsihighway/service/RsiHighwaySwingService.java src/main/java/com/tradingbot/positional/service/BollingerHaPositionalService.java src/test/java/com/tradingbot/strategy/rsihighway/service/RsiHighwaySwingServiceCacheTest.java
git commit -m "feat(strategy): integrate HistoricalOhlcCacheService into RSI Highway & Positional strategies"
```
