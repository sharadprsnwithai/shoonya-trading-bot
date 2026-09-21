package com.tradingbot.controller;

import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.marketdata.repository.SqliteBackupService;
import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** REST Controller for querying, syncing, and managing historical OHLC data and SQLite backups. */
@RestController
@RequestMapping("/api/v1/historical-ohlc")
public class HistoricalOhlcController {

    private static final Logger log = LoggerFactory.getLogger(HistoricalOhlcController.class);

    private final HistoricalOhlcCacheService cacheService;
    private final SqliteBackupService backupService;

    @Autowired
    public HistoricalOhlcController(
            HistoricalOhlcCacheService cacheService,
            @Autowired(required = false) SqliteBackupService backupService) {
        this.cacheService = cacheService;
        this.backupService = backupService;
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
            resp.put(
                    "message",
                    success ? "Symbol OHLC synced successfully" : "Failed to sync symbol");
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

    /** Triggers an immediate atomic backup of the SQLite database. */
    @PostMapping("/backup")
    public ResponseEntity<Map<String, Object>> triggerBackup() {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (backupService == null) {
            resp.put("status", "DISABLED");
            resp.put("message", "SqliteBackupService is not enabled");
            return ResponseEntity.badRequest().body(resp);
        }

        Optional<File> backup = backupService.createDailyBackup();
        if (backup.isPresent()) {
            File f = backup.get();
            resp.put("status", "SUCCESS");
            resp.put("backupFile", f.getName());
            resp.put("backupPath", f.getAbsolutePath());
            resp.put("sizeBytes", f.length());
            resp.put("createdAt", Instant.now());
            return ResponseEntity.ok(resp);
        } else {
            resp.put("status", "FAILED");
            resp.put("message", "Database backup creation failed or database is empty");
            return ResponseEntity.internalServerError().body(resp);
        }
    }

    /** Lists all available SQLite database backups and their sizes. */
    @GetMapping("/backups")
    public ResponseEntity<Map<String, Object>> listBackups() {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (backupService == null) {
            resp.put("backups", List.of());
            return ResponseEntity.ok(resp);
        }

        List<Map<String, Object>> list = new ArrayList<>();
        for (File f : backupService.getAvailableBackups()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", f.getName());
            item.put("sizeBytes", f.length());
            item.put("lastModified", Instant.ofEpochMilli(f.lastModified()));
            list.add(item);
        }
        resp.put("count", list.size());
        resp.put("backups", list);
        return ResponseEntity.ok(resp);
    }
}
