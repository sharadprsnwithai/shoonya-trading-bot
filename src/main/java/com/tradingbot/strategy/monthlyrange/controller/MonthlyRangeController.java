package com.tradingbot.strategy.monthlyrange.controller;

import com.tradingbot.strategy.monthlyrange.model.MonthlyRangeForecast;
import com.tradingbot.strategy.monthlyrange.model.MonthlyRangeReport;
import com.tradingbot.strategy.monthlyrange.service.MonthlyRangeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller exposing endpoints for GARCH(1,1) Monthly Option Range calculations, report
 * retrieval, and on-demand post-expiry execution.
 */
@RestController
@RequestMapping("/api/v1/monthly-range")
public class MonthlyRangeController {

    private final MonthlyRangeService monthlyRangeService;

    public MonthlyRangeController(MonthlyRangeService monthlyRangeService) {
        this.monthlyRangeService = monthlyRangeService;
    }

    /**
     * Executes the monthly forecast routine for all configured symbols, dispatches Telegram alert,
     * and returns the full aggregated report.
     */
    @PostMapping("/run")
    public ResponseEntity<MonthlyRangeReport> runMonthlyRange() {
        MonthlyRangeReport report = monthlyRangeService.generateMonthlyReport();
        return ResponseEntity.ok(report);
    }

    /** Returns the latest GARCH monthly range forecast report for all configured symbols. */
    @GetMapping("/forecast")
    public ResponseEntity<MonthlyRangeReport> getMonthlyRangeReport() {
        MonthlyRangeReport report = monthlyRangeService.generateMonthlyReport();
        return ResponseEntity.ok(report);
    }

    /** Calculates and returns the monthly range forecast for a single specified symbol. */
    @GetMapping("/forecast/{symbol}")
    public ResponseEntity<MonthlyRangeForecast> getForecastForSymbol(
            @PathVariable String symbol, @RequestParam(defaultValue = "22") int horizonDays) {
        MonthlyRangeForecast forecast =
                monthlyRangeService.generateForecastForSymbol(symbol, horizonDays);
        return ResponseEntity.ok(forecast);
    }
}
