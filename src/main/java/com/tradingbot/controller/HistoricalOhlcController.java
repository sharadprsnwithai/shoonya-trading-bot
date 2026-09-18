package com.tradingbot.controller;

import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST Controller for querying and triggering Yahoo Finance historical OHLC cache synchronizations. */
@RestController
@RequestMapping("/api/v1/historical-ohlc")
public class HistoricalOhlcController {

    private static final Logger log = LoggerFactory.getLogger(HistoricalOhlcController.class);

    private final HistoricalOhlcCacheService cacheService;

    @org.springframework.beans.factory.annotation.Autowired
    public HistoricalOhlcController(HistoricalOhlcCacheService cacheService) {
        this.cacheService = cacheService;
    }

    /** Returns the current status of the historical OHLC cache. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("cachedSymbols", cacheService.getCachedSymbolCount());
        resp.put("lastUpdated", cacheService.getLastUpdated());
        resp.put("cacheValidForToday", cacheService.isCacheValidForToday());
        resp.put("stateFilePath", cacheService.getStateFilePath());
        return ResponseEntity.ok(resp);
    }

    /** Triggers a synchronous download and refresh of OHLC candles from Yahoo Finance. */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> triggerSync(
            @RequestParam(name = "force", defaultValue = "false") boolean force,
            @RequestParam(name = "symbol", required = false) String symbol) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Instant start = Instant.now();

        if (symbol != null && !symbol.isBlank()) {
            boolean success = cacheService.syncSymbol(symbol, 2);
            cacheService.saveToFile();
            resp.put("status", success ? "SUCCESS" : "FAILED");
            resp.put("symbol", symbol.toUpperCase().trim());
            resp.put("message", success ? "Symbol OHLC synced successfully" : "Failed to sync symbol");
            resp.put("syncTime", Instant.now());
            return ResponseEntity.ok(resp);
        }

        int count = cacheService.syncAll(force);
        resp.put("status", "SUCCESS");
        resp.put("message", "Universe OHLC sync completed");
        resp.put("symbolsUpdated", count);
        resp.put("syncTime", Instant.now());
        return ResponseEntity.ok(resp);
    }
}
