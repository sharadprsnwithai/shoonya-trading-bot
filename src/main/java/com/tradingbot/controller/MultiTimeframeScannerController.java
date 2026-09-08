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
        List<MtfTrendStatus> uptrend = scannerService.getConfluenceUptrendStocks(allScanned);
        List<MtfTrendStatus> downtrend = scannerService.getConfluenceDowntrendStocks(allScanned);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SUCCESS");
        response.put("totalUniverseSize", Nifty200Registry.getAllSymbols().size());
        response.put("totalScanned", allScanned.size());
        response.put("confluenceCount", uptrend.size() + downtrend.size());
        response.put("uptrendCount", uptrend.size());
        response.put("uptrendStocks", uptrend);
        response.put("downtrendCount", downtrend.size());
        response.put("downtrendStocks", downtrend);
        response.put("confluenceStocks", uptrend);

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
        status.put(
                "strategy",
                "Multi-Timeframe Confluence Scanner (Gainers & Losers: Weekly + Daily + Hourly)");
        status.put("universe", "NIFTY 200");
        status.put("universeSize", Nifty200Registry.getAllSymbols().size());
        status.put("hourlySchedulerEnabled", scheduler.isEnabled());
        status.put(
                "scheduleCron",
                "0 15 10-15 ? * MON-FRI (10:15, 11:15, 12:15, 13:15, 14:15, 15:15 IST)");

        return ResponseEntity.ok(status);
    }
}
