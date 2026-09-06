package com.tradingbot.controller;

import com.tradingbot.model.strategy.MtfTrendStatus;
import com.tradingbot.scheduler.HourlyMtfScannerScheduler;
import com.tradingbot.service.MultiTimeframeScannerService;
import com.tradingbot.util.Nifty200Registry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller exposing management and monitoring endpoints for the Multi-Timeframe (Weekly,
 * Daily, Hourly) Uptrend Strategy and Scanner.
 */
@RestController
@RequestMapping("/api/strategy/mtf")
public class MultiTimeframeScannerController {

    private final MultiTimeframeScannerService scannerService;
    private final HourlyMtfScannerScheduler scheduler;

    @Autowired
    public MultiTimeframeScannerController(
            MultiTimeframeScannerService scannerService, HourlyMtfScannerScheduler scheduler) {
        this.scannerService = scannerService;
        this.scheduler = scheduler;
    }

    /** Triggers an immediate scan of NIFTY 200 and returns the results as JSON. */
    @GetMapping("/scan")
    public ResponseEntity<Map<String, Object>> runScan() {
        List<MtfTrendStatus> allScanned = scannerService.scanAllNifty200();
        List<MtfTrendStatus> confluence = scannerService.getConfluenceUptrendStocks(allScanned);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SUCCESS");
        response.put("totalUniverseSize", Nifty200Registry.getAllSymbols().size());
        response.put("totalScanned", allScanned.size());
        response.put("confluenceCount", confluence.size());
        response.put("confluenceStocks", confluence);

        return ResponseEntity.ok(response);
    }

    /** Triggers scan and dispatches the formatted report to Telegram immediately. */
    @PostMapping("/notify")
    public ResponseEntity<Map<String, Object>> scanAndNotify() {
        List<MtfTrendStatus> confluence = scheduler.runScanAndNotify();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SUCCESS");
        response.put("telegramNotificationDispatched", true);
        response.put("confluenceCount", confluence.size());
        response.put("confluenceStocks", confluence);

        return ResponseEntity.ok(response);
    }

    /** Returns the current operational status and configuration of the MTF scanner. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("strategy", "Multi-Timeframe Uptrend Confluence (Weekly + Daily + Hourly)");
        status.put("universe", "NIFTY 200");
        status.put("universeSize", Nifty200Registry.getAllSymbols().size());
        status.put("hourlySchedulerEnabled", scheduler.isEnabled());
        status.put(
                "scheduleCron",
                "0 30 9-15 ? * MON-FRI (09:30, 10:30, 11:30, 12:30, 13:30, 14:30, 15:15 IST)");

        return ResponseEntity.ok(status);
    }
}
