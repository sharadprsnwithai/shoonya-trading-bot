package com.tradingbot.controller;

import com.tradingbot.backtest.BacktestResult;
import com.tradingbot.backtest.DailyWmaBacktestService;
import com.tradingbot.model.strategy.DailyWmaPosition;
import com.tradingbot.service.DailyWmaStrategyService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API endpoints for monitoring and controlling the 19-period Daily WMA Positional Option Selling strategy.
 */
@RestController
@RequestMapping("/api/strategy/daily-wma")
public class DailyWmaStrategyController {

    private final DailyWmaStrategyService strategyService;
    private final DailyWmaBacktestService backtestService;

    @Autowired
    public DailyWmaStrategyController(
            DailyWmaStrategyService strategyService, DailyWmaBacktestService backtestService) {
        this.strategyService = strategyService;
        this.backtestService = backtestService;
    }

    /** Returns current positional status and metrics. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        DailyWmaPosition openPos = strategyService.getOpenPosition();
        List<DailyWmaPosition> history = strategyService.getTradeHistory();

        Map<String, Object> response = new HashMap<>();
        response.put("strategy", "19-Period Daily WMA Positional Option Selling");
        response.put("hasActivePosition", openPos != null);
        response.put("activePosition", openPos);
        response.put("totalClosedTrades", history.size());

        return ResponseEntity.ok(response);
    }

    /** Returns history of closed trades. */
    @GetMapping("/history")
    public ResponseEntity<List<DailyWmaPosition>> getHistory() {
        return ResponseEntity.ok(strategyService.getTradeHistory());
    }

    /** Manually triggers 09:30 AM Daily Evaluation cycle. */
    @PostMapping("/trigger")
    public ResponseEntity<Map<String, String>> triggerDailyCycle() {
        strategyService.evaluateDailyCycle();
        return ResponseEntity.ok(Map.of("message", "Daily 19-WMA evaluation cycle triggered successfully"));
    }

    /** Forces square-off of active positional trade. */
    @PostMapping("/close")
    public ResponseEntity<Map<String, String>> forceSquareOff() {
        strategyService.executeExpirySquareOff("FORCED_SQUARE_OFF");
        return ResponseEntity.ok(Map.of("message", "Square-off triggered for active position"));
    }

    /** Runs backtest over historical daily candles. */
    @PostMapping("/backtest")
    public ResponseEntity<BacktestResult> runBacktest(@RequestParam(defaultValue = "60") int days) {
        BacktestResult result = backtestService.runBacktest(days);
        return ResponseEntity.ok(result);
    }
}
