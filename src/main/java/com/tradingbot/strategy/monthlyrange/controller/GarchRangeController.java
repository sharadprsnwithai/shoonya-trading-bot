package com.tradingbot.strategy.monthlyrange.controller;

import com.tradingbot.strategy.monthlyrange.model.GarchRangeForecast;
import com.tradingbot.strategy.monthlyrange.model.GarchRangeReport;
import com.tradingbot.strategy.monthlyrange.service.GarchRangeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller exposing the short-dated (weekly/daily) GARCH(1,1) range forecasts, calibrated
 * against realised out-of-sample excursion coverage.
 */
@RestController
@RequestMapping("/api/v1/monthly-range/weekly")
public class GarchRangeController {

    private final GarchRangeService garchRangeService;

    public GarchRangeController(GarchRangeService garchRangeService) {
        this.garchRangeService = garchRangeService;
    }

    /**
     * Executes the weekly range routine for all configured symbols, dispatches the Telegram alert,
     * and returns the aggregated report.
     */
    @PostMapping("/run")
    public ResponseEntity<GarchRangeReport> runWeeklyRange() {
        return ResponseEntity.ok(garchRangeService.generateWeeklyReport());
    }

    /** Returns the calibrated weekly range forecast for every configured symbol. */
    @GetMapping("/forecast")
    public ResponseEntity<GarchRangeReport> getWeeklyRangeReport() {
        return ResponseEntity.ok(garchRangeService.generateWeeklyReport());
    }

    /**
     * Calculates and returns the weekly range forecast for a single symbol over the current expiry
     * window.
     */
    @GetMapping("/forecast/{symbol}")
    public ResponseEntity<GarchRangeForecast> getForecastForSymbol(@PathVariable String symbol) {
        return ResponseEntity.ok(garchRangeService.generateRangeForSymbol(symbol));
    }
}
