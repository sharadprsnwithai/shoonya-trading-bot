package com.tradingbot.controller;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.indicator.IndicatorResponse;
import com.tradingbot.model.indicator.IndicatorSnapshot;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for Technical Indicators (SuperTrend, RSI, VWAP) computed over Shoonya market
 * data.
 */
@RestController
@RequestMapping("/api/v1/indicators")
public class IndicatorController {

    private final ShoonyaMarketDataService marketDataService;
    private final TechnicalAnalysisService taService;

    public IndicatorController(
            ShoonyaMarketDataService marketDataService, TechnicalAnalysisService taService) {
        this.marketDataService = marketDataService;
        this.taService = taService;
    }

    /**
     * Get combined Technical Indicators (SuperTrend, RSI, VWAP) for NIFTY 50. Example: GET
     * /api/v1/indicators/nifty50?interval=5m&days=5&atrPeriod=7&multiplier=3.0&rsiPeriod=14
     */
    @GetMapping("/nifty50")
    public ResponseEntity<IndicatorResponse> getNifty50Indicators(
            @RequestParam(defaultValue = "5m") String interval,
            @RequestParam(defaultValue = "5") int days,
            @RequestParam(defaultValue = "7") int atrPeriod,
            @RequestParam(defaultValue = "3.0") double multiplier,
            @RequestParam(defaultValue = "14") int rsiPeriod) {
        String normalizedInterval = normalizeInterval(interval);
        List<Candle> candles =
                marketDataService.fetchHistoricalCandles(
                        OhlcController.DEFAULT_NIFTY_EXCHANGE,
                        OhlcController.DEFAULT_NIFTY_TOKEN,
                        OhlcController.DEFAULT_NIFTY_SYMBOL,
                        normalizedInterval,
                        Math.max(1, Math.min(days, 30)));

        IndicatorResponse response =
                taService.analyze(
                        OhlcController.DEFAULT_NIFTY_SYMBOL,
                        interval,
                        candles,
                        atrPeriod,
                        multiplier,
                        rsiPeriod);
        return ResponseEntity.ok(response);
    }

    /**
     * Path-variable convenience endpoint for NIFTY 50 timeframes. Example: GET
     * /api/v1/indicators/nifty50/15m
     */
    @GetMapping("/nifty50/{interval}")
    public ResponseEntity<IndicatorResponse> getNifty50IndicatorsByTimeframe(
            @PathVariable String interval,
            @RequestParam(defaultValue = "5") int days,
            @RequestParam(defaultValue = "7") int atrPeriod,
            @RequestParam(defaultValue = "3.0") double multiplier,
            @RequestParam(defaultValue = "14") int rsiPeriod) {
        return getNifty50Indicators(interval, days, atrPeriod, multiplier, rsiPeriod);
    }

    /**
     * Get latest indicator snapshot for NIFTY 50. Example: GET
     * /api/v1/indicators/nifty50/latest?interval=5m
     */
    @GetMapping("/nifty50/latest")
    public ResponseEntity<Map<String, Object>> getNifty50LatestIndicators(
            @RequestParam(defaultValue = "5m") String interval,
            @RequestParam(defaultValue = "5") int days,
            @RequestParam(defaultValue = "7") int atrPeriod,
            @RequestParam(defaultValue = "3.0") double multiplier,
            @RequestParam(defaultValue = "14") int rsiPeriod) {
        IndicatorResponse response =
                getNifty50Indicators(interval, days, atrPeriod, multiplier, rsiPeriod).getBody();
        IndicatorSnapshot latest = (response != null) ? response.latest() : null;

        if (latest == null) {
            return ResponseEntity.ok(Map.of("symbol", "NIFTY50", "status", "NO_DATA"));
        }

        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("symbol", "NIFTY50");
        map.put("interval", interval);
        map.put("timestamp", latest.timestamp());
        map.put("formattedTime", latest.formattedTime());
        map.put("close", latest.close());
        map.put("supertrend", latest.supertrend() != null ? latest.supertrend() : "N/A");
        map.put("supertrendSignal", latest.supertrendSignal());
        map.put("rsi", latest.rsi() != null ? latest.rsi() : "N/A");
        map.put("rsiSignal", latest.rsiSignal());
        map.put("vwap", latest.vwap() != null ? latest.vwap() : "N/A");
        map.put("vwapDiff", latest.vwapDiff() != null ? latest.vwapDiff() : "N/A");
        return ResponseEntity.ok(map);
    }

    /**
     * Generic endpoint to compute technical indicators for any custom instrument. Example: GET
     * /api/v1/indicators?exchange=NSE&token=2885&symbol=RELIANCE&interval=5m&days=5
     */
    @GetMapping
    public ResponseEntity<IndicatorResponse> getCustomIndicators(
            @RequestParam String symbol,
            @RequestParam String exchange,
            @RequestParam String token,
            @RequestParam(defaultValue = "5m") String interval,
            @RequestParam(defaultValue = "5") int days,
            @RequestParam(defaultValue = "7") int atrPeriod,
            @RequestParam(defaultValue = "3.0") double multiplier,
            @RequestParam(defaultValue = "14") int rsiPeriod) {
        String normalizedInterval = normalizeInterval(interval);
        List<Candle> candles =
                marketDataService.fetchHistoricalCandles(
                        exchange,
                        token,
                        symbol,
                        normalizedInterval,
                        Math.max(1, Math.min(days, 30)));

        IndicatorResponse response =
                taService.analyze(symbol, interval, candles, atrPeriod, multiplier, rsiPeriod);
        return ResponseEntity.ok(response);
    }

    private String normalizeInterval(String interval) {
        if (interval == null || interval.isBlank()) return "5";
        String clean = interval.trim().toLowerCase();
        return switch (clean) {
            case "1", "1m", "1min" -> "1";
            case "5", "5m", "5min" -> "5";
            case "15", "15m", "15min" -> "15";
            case "60", "60m", "1h", "1hour" -> "60";
            case "d", "1d", "day", "daily" -> "D";
            default ->
                    clean.replaceAll("[^0-9]", "").isEmpty() ? "5" : clean.replaceAll("[^0-9]", "");
        };
    }
}
