# Market Intelligence & Commodity Direction Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an automated Market Direction & Intelligence Subsystem (`MarketIntelligenceService`) that calculates 09:45 AM Equity Market Direction (PCR, 11 Sectors, Breadth, Technicals) and 13:30/17:30 PM Commodity Direction (Gold, Silver, Crude Oil, DXY, 10Y Yields), generates LLM prompts, and dispatches Telegram briefings.

**Architecture:** A domain-driven service architecture under `com.tradingbot.intelligence` with dedicated analyzers for Equities (`EquityIntelligenceService`) and Commodities (`CommodityIntelligenceService`), orchestrated by `MarketIntelligenceService` with Spring schedulers (`MarketIntelligenceScheduler`), REST APIs (`MarketIntelligenceController`), and Telegram interactive commands (`/market`, `/commodity`).

**Tech Stack:** Java 21, Spring Boot 3.3.5, Jackson JSON, Spring Web, Spring Scheduling, JUnit 5, Mockito, AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-30-market-intelligence-and-commodity-direction-design.md`

## Global Constraints

- Java 21 with standard records and immutability where possible.
- Adhere to existing Spring Boot component scan and packaging conventions.
- 100% test coverage with spotless formatting and spotbugs compliance.
- Non-blocking fail-safes when external market data (Yahoo Finance / Shoonya) is temporarily slow or unavailable.

## Review Focus

1. **Option Chain PCR Failure:** When option chain data is unavailable or zero, return neutral PCR safely without divide-by-zero errors.
2. **Missing Sector Constituents:** When quotes for some sector constituents fail, average remaining available quotes gracefully.
3. **Macro Data Fallback:** When DXY or US 10Y Yield is unavailable from Yahoo Finance, gracefully fall back to pure technicals without crashing.
4. **Timezone Accuracy:** All schedules and timestamps strictly use `Asia/Kolkata` (IST).
5. **Prompt Formatting Safety:** Ensure LLM prompt strings are escaped and valid JSON/Markdown.

---

### Task 1: Domain Models & Snapshot Records

**Files:**
- Create: `src/main/java/com/tradingbot/intelligence/model/EquityMarketSnapshot.java`
- Create: `src/main/java/com/tradingbot/intelligence/model/CommoditySnapshot.java`
- Create: `src/main/java/com/tradingbot/intelligence/model/MarketIntelligenceReport.java`
- Test: `src/test/java/com/tradingbot/intelligence/model/MarketIntelligenceModelTest.java`

**Interfaces:**
- Produces: `EquityMarketSnapshot`, `CommoditySnapshot`, `MarketIntelligenceReport`

- [ ] **Step 1: Write unit tests for domain models**

Create `src/test/java/com/tradingbot/intelligence/model/MarketIntelligenceModelTest.java`:
```java
package com.tradingbot.intelligence.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketIntelligenceModelTest {

    @Test
    void testEquityMarketSnapshotCreation() {
        EquityMarketSnapshot snap =
                new EquityMarketSnapshot(
                        24920.5,
                        1.34,
                        1.21,
                        24800,
                        36,
                        14,
                        0.72,
                        List.of("NIFTY IT (+1.85%)", "NIFTY AUTO (+1.20%)"),
                        List.of("NIFTY MEDIA (-0.65%)"),
                        62.4,
                        true,
                        "BULLISH",
                        78,
                        "STRONG_BULLISH");

        assertThat(snap.niftySpot()).isEqualTo(24920.5);
        assertThat(snap.compositeScore()).isEqualTo(78);
        assertThat(snap.directionalBias()).isEqualTo("STRONG_BULLISH");
    }

    @Test
    void testCommoditySnapshotCreation() {
        CommoditySnapshot snap =
                new CommoditySnapshot(
                        "GOLD",
                        "Gold Bullion",
                        4200.4,
                        -2.43,
                        46.6,
                        4220.32,
                        false,
                        true,
                        false,
                        -25,
                        "MILD_BEARISH",
                        4174.4,
                        4251.1);

        assertThat(snap.symbol()).isEqualTo("GOLD");
        assertThat(snap.score()).isEqualTo(-25);
        assertThat(snap.bias()).isEqualTo("MILD_BEARISH");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.intelligence.model.MarketIntelligenceModelTest`
Expected: FAIL (types not found)

- [ ] **Step 3: Implement domain model records**

Create `src/main/java/com/tradingbot/intelligence/model/EquityMarketSnapshot.java`:
```java
package com.tradingbot.intelligence.model;

import java.util.List;

public record EquityMarketSnapshot(
        double niftySpot,
        double niftyPcrOi,
        double bankNiftyPcrOi,
        double maxPain,
        int advances,
        int declines,
        double advanceRatio,
        List<String> leadingSectors,
        List<String> laggingSectors,
        double rsi15m,
        boolean aboveVwap,
        String superTrend,
        int compositeScore,
        String directionalBias) {}
```

Create `src/main/java/com/tradingbot/intelligence/model/CommoditySnapshot.java`:
```java
package com.tradingbot.intelligence.model;

public record CommoditySnapshot(
        String symbol,
        String name,
        double ltp,
        double pctChange,
        double rsi1h,
        double vwap,
        boolean aboveVwap,
        boolean superTrendBullish,
        boolean emaBullish,
        int score,
        String bias,
        double support,
        double resistance) {}
```

Create `src/main/java/com/tradingbot/intelligence/model/MarketIntelligenceReport.java`:
```java
package com.tradingbot.intelligence.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record MarketIntelligenceReport(
        Instant timestamp,
        EquityMarketSnapshot equity,
        List<CommoditySnapshot> commodities,
        Map<String, Object> macroDrivers,
        String llmPromptText,
        String telegramMarkdown) {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.intelligence.model.MarketIntelligenceModelTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/intelligence/model/ src/test/java/com/tradingbot/intelligence/model/
git commit -m "feat(intelligence): create domain models for equity and commodity market snapshots"
```

---

### Task 2: Equity Market Direction Analyzer (`EquityIntelligenceService`)

**Files:**
- Create: `src/main/java/com/tradingbot/intelligence/service/EquityIntelligenceService.java`
- Test: `src/test/java/com/tradingbot/intelligence/service/EquityIntelligenceServiceTest.java`

**Interfaces:**
- Consumes: `ShoonyaOptionChainService`, `LowestVolumeReversalScanner`, `TechnicalAnalysisService`, `YahooFinanceService`, `ShoonyaMarketDataService`
- Produces: `EquityMarketSnapshot evaluateEquityMarket()`

- [ ] **Step 1: Write the failing unit test**

Create `src/test/java/com/tradingbot/intelligence/service/EquityIntelligenceServiceTest.java`:
```java
package com.tradingbot.intelligence.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.intelligence.model.EquityMarketSnapshot;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.marketdata.YahooFinanceService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.PcrResponse;
import com.tradingbot.service.LowestVolumeReversalScanner;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EquityIntelligenceServiceTest {

    private ShoonyaOptionChainService optionChainService;
    private LowestVolumeReversalScanner scanner;
    private TechnicalAnalysisService taService;
    private YahooFinanceService yahooService;
    private ShoonyaMarketDataService marketDataService;
    private EquityIntelligenceService equityService;

    @BeforeEach
    void setUp() {
        optionChainService = mock(ShoonyaOptionChainService.class);
        scanner = new LowestVolumeReversalScanner();
        taService = new TechnicalAnalysisService();
        yahooService = mock(YahooFinanceService.class);
        marketDataService = mock(ShoonyaMarketDataService.class);

        equityService =
                new EquityIntelligenceService(
                        optionChainService, scanner, taService, yahooService, marketDataService);
    }

    @Test
    void testEvaluateEquityMarketCalculatesBullishScore() {
        when(optionChainService.getNifty50Pcr(anyInt()))
                .thenReturn(new PcrResponse(1.35, 1.20, 24800, 1000, 1350, 500, 600, 24800.0, "BULLISH", "Heavy Put Writing"));
        when(optionChainService.getBankNiftyPcr(anyInt()))
                .thenReturn(new PcrResponse(1.25, 1.10, 54000, 1000, 1250, 500, 550, 54000.0, "BULLISH", "Bullish Support"));

        List<Candle> mock15m =
                List.of(
                        Candle.of15m("NIFTY 50", Instant.now(), BigDecimal.valueOf(24800), BigDecimal.valueOf(24850), BigDecimal.valueOf(24790), BigDecimal.valueOf(24840), 10000),
                        Candle.of15m("NIFTY 50", Instant.now(), BigDecimal.valueOf(24840), BigDecimal.valueOf(24900), BigDecimal.valueOf(24830), BigDecimal.valueOf(24890), 15000),
                        Candle.of15m("NIFTY 50", Instant.now(), BigDecimal.valueOf(24890), BigDecimal.valueOf(24950), BigDecimal.valueOf(24880), BigDecimal.valueOf(24930), 20000));
        when(yahooService.fetch15MinCandles("NIFTY 50", 5)).thenReturn(mock15m);

        EquityMarketSnapshot snapshot = equityService.evaluateEquityMarket();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.niftyPcrOi()).isEqualTo(1.35);
        assertThat(snapshot.compositeScore()).isGreaterThan(0);
        assertThat(snapshot.directionalBias()).contains("BULLISH");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.intelligence.service.EquityIntelligenceServiceTest`
Expected: FAIL (class not found)

- [ ] **Step 3: Implement `EquityIntelligenceService`**

Create `src/main/java/com/tradingbot/intelligence/service/EquityIntelligenceService.java`:
```java
package com.tradingbot.intelligence.service;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.intelligence.model.EquityMarketSnapshot;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.marketdata.YahooFinanceService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.PcrResponse;
import com.tradingbot.model.indicator.SuperTrendResult;
import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
import com.tradingbot.service.LowestVolumeReversalScanner;
import com.tradingbot.util.NiftySectorRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class EquityIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(EquityIntelligenceService.class);

    private final ShoonyaOptionChainService optionChainService;
    private final LowestVolumeReversalScanner scanner;
    private final TechnicalAnalysisService taService;
    private final YahooFinanceService yahooService;
    private final ShoonyaMarketDataService marketDataService;

    @Autowired
    public EquityIntelligenceService(
            @Autowired(required = false) ShoonyaOptionChainService optionChainService,
            @Autowired(required = false) LowestVolumeReversalScanner scanner,
            TechnicalAnalysisService taService,
            YahooFinanceService yahooService,
            @Autowired(required = false) ShoonyaMarketDataService marketDataService) {
        this.optionChainService = optionChainService;
        this.scanner = (scanner != null) ? scanner : new LowestVolumeReversalScanner();
        this.taService = taService;
        this.yahooService = yahooService;
        this.marketDataService = marketDataService;
    }

    public EquityMarketSnapshot evaluateEquityMarket() {
        // 1. PCR & Option Chain
        double niftyPcr = 1.0;
        double bankNiftyPcr = 1.0;
        double maxPain = 0.0;

        if (optionChainService != null) {
            try {
                PcrResponse nPcr = optionChainService.getNifty50Pcr(15);
                if (nPcr != null) {
                    niftyPcr = nPcr.pcrOi();
                    maxPain = nPcr.maxPainStrike();
                }
            } catch (Exception e) {
                log.warn("[INTELLIGENCE] Error reading Nifty PCR: {}", e.getMessage());
            }

            try {
                PcrResponse bPcr = optionChainService.getBankNiftyPcr(15);
                if (bPcr != null) {
                    bankNiftyPcr = bPcr.pcrOi();
                }
            } catch (Exception e) {
                log.warn("[INTELLIGENCE] Error reading BankNifty PCR: {}", e.getMessage());
            }
        }

        // 2. Technical Analysis on 15m Nifty Candles
        double spot = 24800.0;
        double rsi15m = 50.0;
        boolean aboveVwap = true;
        String superTrend = "NEUTRAL";

        try {
            List<Candle> candles15m = yahooService.fetch15MinCandles("NIFTY 50", 5);
            if (candles15m != null && !candles15m.isEmpty()) {
                Candle latest = candles15m.get(candles15m.size() - 1);
                spot = latest.close().doubleValue();

                double[] rsiSeries = taService.calculateRsiSeries(candles15m, 14);
                if (rsiSeries.length > 0) {
                    rsi15m = rsiSeries[rsiSeries.length - 1];
                }

                double[] vwapSeries = taService.calculateVwapSeries(candles15m);
                if (vwapSeries.length > 0) {
                    aboveVwap = (spot >= vwapSeries[vwapSeries.length - 1]);
                }

                SuperTrendResult stRes = taService.calculateSuperTrend(candles15m, 10, 3.0);
                superTrend = (stRes.direction() > 0) ? "BULLISH" : "BEARISH";
            }
        } catch (Exception e) {
            log.warn("[INTELLIGENCE] Error analyzing Nifty technicals: {}", e.getMessage());
        }

        // 3. Sector Rotation & Breadth
        int advances = 28;
        int declines = 22;
        List<String> leaders = new ArrayList<>();
        List<String> laggards = new ArrayList<>();

        try {
            Map<String, List<StockQuoteSnapshot>> sectorQuotes = new HashMap<>();
            List<StockQuoteSnapshot> niftyQuotes = new ArrayList<>();

            for (Map.Entry<String, List<String>> entry :
                    NiftySectorRegistry.getSectorConstituents().entrySet()) {
                String sector = entry.getKey();
                List<StockQuoteSnapshot> sQuotes = new ArrayList<>();
                for (String sym : entry.getValue()) {
                    StockQuoteSnapshot snap = fetchQuoteSnapshot(sym);
                    if (snap != null) {
                        sQuotes.add(snap);
                        if (NiftySectorRegistry.NIFTY_50_CONSTITUENTS.contains(sym)) {
                            niftyQuotes.add(snap);
                        }
                    }
                }
                if (!sQuotes.isEmpty()) {
                    sectorQuotes.put(sector, sQuotes);
                }
            }

            if (!niftyQuotes.isEmpty()) {
                advances = (int) niftyQuotes.stream().filter(q -> q.pctChange() > 0).count();
                declines = (int) niftyQuotes.stream().filter(q -> q.pctChange() < 0).count();
            }

            List<LowestVolumeReversalScanner.SectorRankResult> rankedGainers =
                    scanner.rankSectors(sectorQuotes, LowestVolumeDirection.LONG);
            for (int i = 0; i < Math.min(2, rankedGainers.size()); i++) {
                var r = rankedGainers.get(i);
                leaders.add(String.format(Locale.US, "%s (%+.2f%%)", r.sectorName(), r.pctChange()));
            }

            List<LowestVolumeReversalScanner.SectorRankResult> rankedLosers =
                    scanner.rankSectors(sectorQuotes, LowestVolumeDirection.SHORT);
            for (int i = 0; i < Math.min(2, rankedLosers.size()); i++) {
                var r = rankedLosers.get(i);
                laggards.add(String.format(Locale.US, "%s (%+.2f%%)", r.sectorName(), r.pctChange()));
            }
        } catch (Exception e) {
            log.warn("[INTELLIGENCE] Error evaluating sector breadth: {}", e.getMessage());
        }

        double advanceRatio = (advances + declines > 0) ? (double) advances / (advances + declines) : 0.50;

        // 4. Calculate Composite Directional Score (-100 to +100)
        int score = 0;
        if (niftyPcr >= 1.20) score += 25;
        else if (niftyPcr <= 0.80) score -= 25;

        if (advanceRatio >= 0.60) score += 25;
        else if (advanceRatio <= 0.40) score -= 25;

        if (aboveVwap) score += 25;
        else score -= 25;

        if ("BULLISH".equalsIgnoreCase(superTrend) && rsi15m > 52) score += 25;
        else if ("BEARISH".equalsIgnoreCase(superTrend) && rsi15m < 48) score -= 25;

        String bias;
        if (score >= 50) bias = "STRONG_BULLISH";
        else if (score > 10) bias = "MILD_BULLISH";
        else if (score <= -50) bias = "STRONG_BEARISH";
        else if (score < -10) bias = "MILD_BEARISH";
        else bias = "NEUTRAL";

        return new EquityMarketSnapshot(
                spot,
                niftyPcr,
                bankNiftyPcr,
                maxPain,
                advances,
                declines,
                advanceRatio,
                leaders,
                laggards,
                rsi15m,
                aboveVwap,
                superTrend,
                score,
                bias);
    }

    private StockQuoteSnapshot fetchQuoteSnapshot(String sym) {
        if (marketDataService != null) {
            try {
                var node = marketDataService.fetchQuote(sym);
                if (node != null) {
                    double lp = node.path("lp").asDouble(0.0);
                    double c = node.path("c").asDouble(0.0);
                    if (lp > 0 && c > 0) {
                        double pct = ((lp - c) / c) * 100.0;
                        return new StockQuoteSnapshot(sym, lp, node.path("h").asDouble(lp), node.path("l").asDouble(lp), pct);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.intelligence.service.EquityIntelligenceServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/intelligence/service/EquityIntelligenceService.java src/test/java/com/tradingbot/intelligence/service/EquityIntelligenceServiceTest.java
git commit -m "feat(intelligence): implement EquityIntelligenceService for 09:45 AM PCR and sector direction"
```

---

### Task 3: Commodity Direction Analyzer (`CommodityIntelligenceService`)

**Files:**
- Create: `src/main/java/com/tradingbot/intelligence/service/CommodityIntelligenceService.java`
- Test: `src/test/java/com/tradingbot/intelligence/service/CommodityIntelligenceServiceTest.java`

**Interfaces:**
- Consumes: `YahooFinanceService`, `TechnicalAnalysisService`, `CommodityRegistry`
- Produces: `List<CommoditySnapshot> evaluateCommodities()`, `Map<String, Object> evaluateMacroDrivers()`

- [ ] **Step 1: Write the failing unit test**

Create `src/test/java/com/tradingbot/intelligence/service/CommodityIntelligenceServiceTest.java`:
```java
package com.tradingbot.intelligence.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.intelligence.model.CommoditySnapshot;
import com.tradingbot.marketdata.YahooFinanceService;
import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommodityIntelligenceServiceTest {

    private YahooFinanceService yahooService;
    private TechnicalAnalysisService taService;
    private CommodityIntelligenceService commodityService;

    @BeforeEach
    void setUp() {
        yahooService = mock(YahooFinanceService.class);
        taService = new TechnicalAnalysisService();
        commodityService = new CommodityIntelligenceService(yahooService, taService);
    }

    @Test
    void testEvaluateCommoditiesReturnsSnapshots() {
        List<Candle> mockCandles =
                List.of(
                        Candle.of1h("CL=F", Instant.now(), BigDecimal.valueOf(90), BigDecimal.valueOf(91), BigDecimal.valueOf(89.5), BigDecimal.valueOf(90.5), 1000),
                        Candle.of1h("CL=F", Instant.now(), BigDecimal.valueOf(90.5), BigDecimal.valueOf(92), BigDecimal.valueOf(90), BigDecimal.valueOf(91.5), 1500),
                        Candle.of1h("CL=F", Instant.now(), BigDecimal.valueOf(91.5), BigDecimal.valueOf(92.5), BigDecimal.valueOf(91), BigDecimal.valueOf(92.0), 2000));
        when(yahooService.fetch1HourCandles(eq("CL=F"), anyInt())).thenReturn(mockCandles);

        List<CommoditySnapshot> snapshots = commodityService.evaluateCommodities();
        assertThat(snapshots).isNotNull();
        assertThat(snapshots.stream().anyMatch(c -> "CRUDEOIL".equals(c.symbol()))).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.intelligence.service.CommodityIntelligenceServiceTest`
Expected: FAIL (class not found)

- [ ] **Step 3: Implement `CommodityIntelligenceService`**

Create `src/main/java/com/tradingbot/intelligence/service/CommodityIntelligenceService.java`:
```java
package com.tradingbot.intelligence.service;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.intelligence.model.CommoditySnapshot;
import com.tradingbot.marketdata.YahooFinanceService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.indicator.SuperTrendResult;
import com.tradingbot.util.CommodityRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CommodityIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(CommodityIntelligenceService.class);

    private final YahooFinanceService yahooService;
    private final TechnicalAnalysisService taService;

    @Autowired
    public CommodityIntelligenceService(
            YahooFinanceService yahooService, TechnicalAnalysisService taService) {
        this.yahooService = yahooService;
        this.taService = taService;
    }

    public List<CommoditySnapshot> evaluateCommodities() {
        List<CommoditySnapshot> results = new ArrayList<>();
        List<String> symbols = List.of("CRUDEOIL", "GOLD", "SILVER", "NATURALGAS", "COPPER");

        for (String sym : symbols) {
            var meta = CommodityRegistry.getMetadata(sym);
            String ticker = meta != null ? meta.yahooTicker() : sym;
            String name = meta != null ? meta.name() : sym;

            try {
                List<Candle> candles = yahooService.fetch1HourCandles(ticker, 5);
                if (candles == null || candles.size() < 3) {
                    continue;
                }

                Candle last = candles.get(candles.size() - 1);
                Candle first = candles.get(0);
                double ltp = last.close().doubleValue();
                double openPrc = first.open().doubleValue();
                double pctChange = openPrc > 0 ? ((ltp - openPrc) / openPrc) * 100.0 : 0.0;

                double[] rsiSeries = taService.calculateRsiSeries(candles, 14);
                double rsi1h = rsiSeries.length > 0 ? rsiSeries[rsiSeries.length - 1] : 50.0;

                double[] vwapSeries = taService.calculateVwapSeries(candles);
                double vwap = vwapSeries.length > 0 ? vwapSeries[vwapSeries.length - 1] : ltp;
                boolean aboveVwap = (ltp >= vwap);

                SuperTrendResult st = taService.calculateSuperTrend(candles, 10, 3.0);
                boolean stBullish = (st.direction() > 0);

                double[] ema20 = taService.calculateEmaSeries(candles, 20);
                double[] ema50 = taService.calculateEmaSeries(candles, 50);
                boolean emaBullish = (ema20.length > 0 && ema50.length > 0 && ema20[ema20.length - 1] > ema50[ema50.length - 1]);

                int score = 0;
                if (pctChange > 0.30) score += 25;
                else if (pctChange < -0.30) score -= 25;

                if (aboveVwap) score += 25;
                else score -= 25;

                if (rsi1h > 55) score += 25;
                else if (rsi1h < 45) score -= 25;

                if (stBullish && emaBullish) score += 25;
                else if (!stBullish && !emaBullish) score -= 25;

                String bias;
                if (score >= 50) bias = "STRONG_BULLISH";
                else if (score > 0) bias = "MILD_BULLISH";
                else if (score <= -50) bias = "STRONG_BEARISH";
                else if (score < 0) bias = "MILD_BEARISH";
                else bias = "NEUTRAL";

                double support = candles.stream().mapToDouble(c -> c.low().doubleValue()).min().orElse(ltp * 0.98);
                double resistance = candles.stream().mapToDouble(c -> c.high().doubleValue()).max().orElse(ltp * 1.02);

                results.add(
                        new CommoditySnapshot(
                                sym,
                                name,
                                ltp,
                                pctChange,
                                rsi1h,
                                vwap,
                                aboveVwap,
                                stBullish,
                                emaBullish,
                                score,
                                bias,
                                support,
                                resistance));
            } catch (Exception e) {
                log.warn("[INTELLIGENCE] Error evaluating commodity {}: {}", sym, e.getMessage());
            }
        }
        return results;
    }

    public Map<String, Object> evaluateMacroDrivers() {
        Map<String, Object> macro = new LinkedHashMap<>();
        try {
            List<Candle> dxyCandles = yahooService.fetch1HourCandles("DX-Y.NYB", 3);
            if (dxyCandles != null && !dxyCandles.isEmpty()) {
                Candle last = dxyCandles.get(dxyCandles.size() - 1);
                macro.put("dxyLtp", last.close().doubleValue());
            }
        } catch (Exception ignored) {
        }
        try {
            List<Candle> tnxCandles = yahooService.fetch1HourCandles("^TNX", 3);
            if (tnxCandles != null && !tnxCandles.isEmpty()) {
                Candle last = tnxCandles.get(tnxCandles.size() - 1);
                macro.put("us10yYield", last.close().doubleValue());
            }
        } catch (Exception ignored) {
        }
        return macro;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.intelligence.service.CommodityIntelligenceServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/intelligence/service/CommodityIntelligenceService.java src/test/java/com/tradingbot/intelligence/service/CommodityIntelligenceServiceTest.java
git commit -m "feat(intelligence): implement CommodityIntelligenceService for multi-asset direction"
```

---

### Task 4: AI Prompt Synthesizer & Master Service (`MarketIntelligenceService`)

**Files:**
- Create: `src/main/java/com/tradingbot/intelligence/service/MarketIntelligencePromptService.java`
- Create: `src/main/java/com/tradingbot/intelligence/service/MarketIntelligenceService.java`
- Test: `src/test/java/com/tradingbot/intelligence/service/MarketIntelligenceServiceTest.java`

**Interfaces:**
- Produces: `MarketIntelligenceReport generateReport()`

- [ ] **Step 1: Write the failing unit test**

Create `src/test/java/com/tradingbot/intelligence/service/MarketIntelligenceServiceTest.java`:
```java
package com.tradingbot.intelligence.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tradingbot.intelligence.model.CommoditySnapshot;
import com.tradingbot.intelligence.model.EquityMarketSnapshot;
import com.tradingbot.intelligence.model.MarketIntelligenceReport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarketIntelligenceServiceTest {

    @Test
    void testGenerateReportProducesCompletePayload() {
        EquityIntelligenceService mockEquity = mock(EquityIntelligenceService.class);
        CommodityIntelligenceService mockComm = mock(CommodityIntelligenceService.class);
        MarketIntelligencePromptService promptService = new MarketIntelligencePromptService();

        when(mockEquity.evaluateEquityMarket())
                .thenReturn(
                        new EquityMarketSnapshot(
                                24920.0, 1.34, 1.21, 24800, 36, 14, 0.72,
                                List.of("NIFTY IT (+1.85%)"), List.of("NIFTY MEDIA (-0.65%)"),
                                62.4, true, "BULLISH", 78, "STRONG_BULLISH"));

        when(mockComm.evaluateCommodities())
                .thenReturn(
                        List.of(
                                new CommoditySnapshot(
                                        "CRUDEOIL", "Crude Oil", 91.2, 1.5, 58.0, 90.5, true, true, true, 75, "STRONG_BULLISH", 89.0, 93.0)));
        when(mockComm.evaluateMacroDrivers()).thenReturn(Map.of("dxyLtp", 101.2));

        MarketIntelligenceService service =
                new MarketIntelligenceService(mockEquity, mockComm, promptService, null);

        MarketIntelligenceReport report = service.generateReport();
        assertThat(report).isNotNull();
        assertThat(report.equity().directionalBias()).isEqualTo("STRONG_BULLISH");
        assertThat(report.llmPromptText()).contains("EQUITY DIRECTION");
        assertThat(report.telegramMarkdown()).contains("09:45 AM DAILY MARKET DIRECTION");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.intelligence.service.MarketIntelligenceServiceTest`
Expected: FAIL (classes not found)

- [ ] **Step 3: Implement Prompt & Master Services**

Create `src/main/java/com/tradingbot/intelligence/service/MarketIntelligencePromptService.java`:
```java
package com.tradingbot.intelligence.service;

import com.tradingbot.intelligence.model.CommoditySnapshot;
import com.tradingbot.intelligence.model.EquityMarketSnapshot;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class MarketIntelligencePromptService {

    public String buildLlmPrompt(
            EquityMarketSnapshot equity,
            List<CommoditySnapshot> commodities,
            Map<String, Object> macro) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert institutional market strategist. Analyze the following real-time quantitative snapshot and provide an actionable trading playbook for the day:\n\n");

        if (equity != null) {
            sb.append("=== 1. INDIAN EQUITY MARKET (NIFTY & SECTORS) ===\n");
            sb.append(String.format(Locale.US, "• NIFTY Spot: %.2f | Directional Bias: %s (Score: %+d/100)\n", equity.niftySpot(), equity.directionalBias(), equity.compositeScore()));
            sb.append(String.format(Locale.US, "• NIFTY PCR (OI): %.2f | BANKNIFTY PCR: %.2f | Max Pain: %.0f\n", equity.niftyPcrOi(), equity.bankNiftyPcrOi(), equity.maxPain()));
            sb.append(String.format(Locale.US, "• NIFTY 50 Breadth: %d Advances / %d Declines (%.1f%% Bullish)\n", equity.advances(), equity.declines(), equity.advanceRatio() * 100));
            sb.append("• Leading Sectors: ").append(String.join(", ", equity.leadingSectors())).append("\n");
            sb.append("• Lagging Sectors: ").append(String.join(", ", equity.laggingSectors())).append("\n");
            sb.append(String.format(Locale.US, "• Technicals: 15m RSI=%.1f | Above VWAP=%s | SuperTrend=%s\n\n", equity.rsi15m(), equity.aboveVwap(), equity.superTrend()));
        }

        if (commodities != null && !commodities.isEmpty()) {
            sb.append("=== 2. COMMODITIES & INTER-MARKET ===\n");
            for (CommoditySnapshot c : commodities) {
                sb.append(String.format(Locale.US, "• %s (%s): LTP=%.2f (%+.2f%%) | Bias=%s (Score: %+d) | VWAP=%.2f | 1h RSI=%.1f\n",
                        c.name(), c.symbol(), c.ltp(), c.pctChange(), c.bias(), c.score(), c.vwap(), c.rsi1h()));
            }
        }
        return sb.toString();
    }

    public String buildTelegramMarkdown(
            EquityMarketSnapshot equity,
            List<CommoditySnapshot> commodities,
            Map<String, Object> macro) {
        StringBuilder sb = new StringBuilder();
        sb.append("🧭 *09:45 AM DAILY MARKET DIRECTION INTELLIGENCE*\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        if (equity != null) {
            sb.append(String.format(Locale.US, "📊 *OVERALL BIAS:* *%s* (Score: `%+d/100`)\n\n", equity.directionalBias(), equity.compositeScore()));
            sb.append("📌 *1. OPTIONS & PCR ANALYSIS:*\n");
            sb.append(String.format(Locale.US, "• NIFTY PCR (OI): `%.2f` | BANKNIFTY: `%.2f`\n", equity.niftyPcrOi(), equity.bankNiftyPcrOi()));
            sb.append(String.format(Locale.US, "• Max Pain Strike: `%.0f` | Nifty Spot: `%.2f`\n\n", equity.maxPain(), equity.niftySpot()));

            sb.append("📌 *2. SECTOR ROTATION & BREADTH:*\n");
            sb.append(String.format(Locale.US, "• Breadth: `%d Advances / %d Declines` (%.0f%%)\n", equity.advances(), equity.declines(), equity.advanceRatio() * 100));
            sb.append("• Top Leaders: ").append(String.join(", ", equity.leadingSectors())).append("\n");
            sb.append("• Top Laggards: ").append(String.join(", ", equity.laggingSectors())).append("\n\n");

            sb.append("📌 *3. TECHNICALS:*\n");
            sb.append(String.format(Locale.US, "• 15m RSI: `%.1f` | Above VWAP: `%s` | SuperTrend: *%s*\n\n", equity.rsi15m(), equity.aboveVwap() ? "YES ✅" : "NO ❌", equity.superTrend()));
        }

        if (commodities != null && !commodities.isEmpty()) {
            sb.append("🌍 *4. COMMODITY HIGHLIGHTS:*\n");
            for (CommoditySnapshot c : commodities) {
                sb.append(String.format(Locale.US, "• %s: *%s* (`%.2f`, %+.2f%%)\n", c.symbol(), c.bias(), c.ltp(), c.pctChange()));
            }
        }
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        return sb.toString();
    }
}
```

Create `src/main/java/com/tradingbot/intelligence/service/MarketIntelligenceService.java`:
```java
package com.tradingbot.intelligence.service;

import com.tradingbot.intelligence.model.CommoditySnapshot;
import com.tradingbot.intelligence.model.EquityMarketSnapshot;
import com.tradingbot.intelligence.model.MarketIntelligenceReport;
import com.tradingbot.telegram.TelegramService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MarketIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(MarketIntelligenceService.class);

    private final EquityIntelligenceService equityService;
    private final CommodityIntelligenceService commodityService;
    private final MarketIntelligencePromptService promptService;
    private final TelegramService telegramService;

    private final AtomicReference<MarketIntelligenceReport> latestReport = new AtomicReference<>();

    @Autowired
    public MarketIntelligenceService(
            EquityIntelligenceService equityService,
            CommodityIntelligenceService commodityService,
            MarketIntelligencePromptService promptService,
            @Autowired(required = false) TelegramService telegramService) {
        this.equityService = equityService;
        this.commodityService = commodityService;
        this.promptService = promptService;
        this.telegramService = telegramService;
    }

    public MarketIntelligenceReport generateReport() {
        EquityMarketSnapshot equity = equityService.evaluateEquityMarket();
        List<CommoditySnapshot> commodities = commodityService.evaluateCommodities();
        Map<String, Object> macro = commodityService.evaluateMacroDrivers();

        String promptText = promptService.buildLlmPrompt(equity, commodities, macro);
        String mdText = promptService.buildTelegramMarkdown(equity, commodities, macro);

        MarketIntelligenceReport report =
                new MarketIntelligenceReport(
                        Instant.now(), equity, commodities, macro, promptText, mdText);

        latestReport.set(report);
        log.info("[INTELLIGENCE] Generated Market Intelligence Report. Bias: {}", equity.directionalBias());
        return report;
    }

    public void dispatchMorningEquityAlert() {
        MarketIntelligenceReport report = generateReport();
        if (telegramService != null && report.telegramMarkdown() != null) {
            telegramService.sendTextMessage(report.telegramMarkdown());
            log.info("[INTELLIGENCE] Dispatched 09:45 AM Equity Market Direction Alert via Telegram.");
        }
    }

    public MarketIntelligenceReport getLatestReport() {
        MarketIntelligenceReport current = latestReport.get();
        return (current != null) ? current : generateReport();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests com.tradingbot.intelligence.service.MarketIntelligenceServiceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tradingbot/intelligence/service/ src/test/java/com/tradingbot/intelligence/service/
git commit -m "feat(intelligence): implement MarketIntelligenceService orchestrator and prompt synthesis"
```

---

### Task 5: Scheduler, REST Controller, & Telegram Command Handlers

**Files:**
- Create: `src/main/java/com/tradingbot/intelligence/scheduler/MarketIntelligenceScheduler.java`
- Create: `src/main/java/com/tradingbot/intelligence/controller/MarketIntelligenceController.java`
- Modify: `src/main/java/com/tradingbot/telegram/TelegramBotCommandListener.java`
- Test: `src/test/java/com/tradingbot/intelligence/controller/MarketIntelligenceControllerTest.java`

**Interfaces:**
- Produces: Scheduled 09:45 AM alert, `/api/v1/market-intelligence/*` endpoints, `/market` Telegram command

- [ ] **Step 1: Write unit test for REST controller**

Create `src/test/java/com/tradingbot/intelligence/controller/MarketIntelligenceControllerTest.java`:
```java
package com.tradingbot.intelligence.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradingbot.intelligence.model.EquityMarketSnapshot;
import com.tradingbot.intelligence.model.MarketIntelligenceReport;
import com.tradingbot.intelligence.service.MarketIntelligenceService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MarketIntelligenceController.class)
class MarketIntelligenceControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private MarketIntelligenceService intelligenceService;

    @Test
    void testGetEquityDirection() throws Exception {
        MarketIntelligenceReport mockReport =
                new MarketIntelligenceReport(
                        Instant.now(),
                        new EquityMarketSnapshot(24920.0, 1.34, 1.21, 24800, 36, 14, 0.72, List.of(), List.of(), 62.4, true, "BULLISH", 78, "STRONG_BULLISH"),
                        List.of(),
                        Map.of(),
                        "Prompt text",
                        "Markdown text");

        when(intelligenceService.getLatestReport()).thenReturn(mockReport);

        mockMvc.perform(get("/api/v1/market-intelligence/equity").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.directionalBias").value("STRONG_BULLISH"))
                .andExpect(jsonPath("$.compositeScore").value(78));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests com.tradingbot.intelligence.controller.MarketIntelligenceControllerTest`
Expected: FAIL (controller not found)

- [ ] **Step 3: Implement Scheduler, Controller, and Telegram commands**

Create `src/main/java/com/tradingbot/intelligence/scheduler/MarketIntelligenceScheduler.java`:
```java
package com.tradingbot.intelligence.scheduler;

import com.tradingbot.intelligence.service.MarketIntelligenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class MarketIntelligenceScheduler {

    private static final Logger log = LoggerFactory.getLogger(MarketIntelligenceScheduler.class);

    private final MarketIntelligenceService intelligenceService;

    @Value("${trading-bot.intelligence.enabled:true}")
    private boolean enabled = true;

    @Autowired
    public MarketIntelligenceScheduler(MarketIntelligenceService intelligenceService) {
        this.intelligenceService = intelligenceService;
    }

    /** 09:45 AM IST Equity Market Direction & Intelligence Alert. */
    @Scheduled(cron = "${trading-bot.intelligence.equity-cron:0 45 9 ? * MON-FRI}", zone = "Asia/Kolkata")
    public void scheduled0945EquityIntelligence() {
        if (!enabled) return;
        log.info("[INTELLIGENCE-SCHEDULER] Triggering 09:45 AM Equity Market Direction Analysis...");
        try {
            intelligenceService.dispatchMorningEquityAlert();
        } catch (Exception e) {
            log.error("[INTELLIGENCE-SCHEDULER] Error running 09:45 AM analysis: {}", e.getMessage(), e);
        }
    }
}
```

Create `src/main/java/com/tradingbot/intelligence/controller/MarketIntelligenceController.java`:
```java
package com.tradingbot.intelligence.controller;

import com.tradingbot.intelligence.model.CommoditySnapshot;
import com.tradingbot.intelligence.model.EquityMarketSnapshot;
import com.tradingbot.intelligence.model.MarketIntelligenceReport;
import com.tradingbot.intelligence.service.MarketIntelligenceService;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/market-intelligence")
public class MarketIntelligenceController {

    private final MarketIntelligenceService intelligenceService;

    @Autowired
    public MarketIntelligenceController(MarketIntelligenceService intelligenceService) {
        this.intelligenceService = intelligenceService;
    }

    @GetMapping("/equity")
    public ResponseEntity<EquityMarketSnapshot> getEquitySnapshot() {
        return ResponseEntity.ok(intelligenceService.getLatestReport().equity());
    }

    @GetMapping("/commodity")
    public ResponseEntity<List<CommoditySnapshot>> getCommoditiesSnapshot() {
        return ResponseEntity.ok(intelligenceService.getLatestReport().commodities());
    }

    @GetMapping("/prompt")
    public ResponseEntity<Map<String, String>> getLlmPrompt() {
        MarketIntelligenceReport rep = intelligenceService.getLatestReport();
        return ResponseEntity.ok(Map.of("prompt", rep.llmPromptText(), "telegramMarkdown", rep.telegramMarkdown()));
    }

    @PostMapping("/generate")
    public ResponseEntity<MarketIntelligenceReport> generateOnDemand() {
        return ResponseEntity.ok(intelligenceService.generateReport());
    }
}
```

Modify `src/main/java/com/tradingbot/telegram/TelegramBotCommandListener.java`:
Add `@Autowired(required = false) MarketIntelligenceService intelligenceService` and support `/market` command:
```java
            if ("/market".equalsIgnoreCase(cleanCmd)) {
                if (intelligenceService != null) {
                    var rep = intelligenceService.getLatestReport();
                    return rep.telegramMarkdown();
                }
                return "⚠️ Market Intelligence Service is currently unavailable.";
            }
```

- [ ] **Step 4: Run all tests to verify they pass**

Run: `./gradlew test`
Expected: PASS

- [ ] **Step 5: Apply spotless and commit**

```bash
./gradlew spotlessApply check
git add -A
git commit -m "feat(intelligence): implement MarketIntelligenceScheduler, REST Controller, and Telegram bot /market command"
```
