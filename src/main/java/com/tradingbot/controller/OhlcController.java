package com.tradingbot.controller;

import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.OhlcResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing endpoints to fetch OHLC candle data for NIFTY 50 and other instruments.
 * Supported intervals: 1m, 5m, 15m, 1h (60m).
 */
@RestController
@RequestMapping("/api/v1/ohlc")
public class OhlcController {

    public static final String DEFAULT_NIFTY_EXCHANGE = "NFO";
    public static final String DEFAULT_NIFTY_TOKEN = "68407";
    public static final String DEFAULT_NIFTY_SYMBOL = "NIFTY50";

    private final ShoonyaMarketDataService marketDataService;

    public OhlcController(ShoonyaMarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    /**
     * Fetch OHLC records for NIFTY 50 with query parameters. Example: GET
     * /api/v1/ohlc/nifty50?interval=15m&days=5
     */
    @GetMapping("/nifty50")
    public ResponseEntity<OhlcResponse> getNifty50Ohlc(
            @RequestParam(defaultValue = "5m") String interval,
            @RequestParam(defaultValue = "5") int days,
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) String token) {
        String exch = (exchange != null && !exchange.isBlank()) ? exchange : DEFAULT_NIFTY_EXCHANGE;
        String tok = (token != null && !token.isBlank()) ? token : DEFAULT_NIFTY_TOKEN;
        String normalizedInterval = normalizeInterval(interval);

        List<Candle> candles =
                marketDataService.fetchHistoricalCandles(
                        exch,
                        tok,
                        DEFAULT_NIFTY_SYMBOL,
                        normalizedInterval,
                        Math.max(1, Math.min(days, 30)));

        return ResponseEntity.ok(
                OhlcResponse.of(DEFAULT_NIFTY_SYMBOL, exch, tok, interval, candles));
    }

    /**
     * Path-variable convenience endpoints for specific timeframes: - GET /api/v1/ohlc/nifty50/1m -
     * GET /api/v1/ohlc/nifty50/5m - GET /api/v1/ohlc/nifty50/15m - GET /api/v1/ohlc/nifty50/1h
     */
    @GetMapping("/nifty50/{interval}")
    public ResponseEntity<OhlcResponse> getNifty50ByTimeframe(
            @PathVariable String interval,
            @RequestParam(defaultValue = "5") int days,
            @RequestParam(required = false) String exchange,
            @RequestParam(required = false) String token) {
        return getNifty50Ohlc(interval, days, exchange, token);
    }

    /**
     * Generic endpoint to fetch OHLC for any instrument token and exchange. Example: GET
     * /api/v1/ohlc?exchange=NSE&token=2885&symbol=RELIANCE&interval=5m&days=5
     */
    @GetMapping
    public ResponseEntity<OhlcResponse> getCustomOhlc(
            @RequestParam String symbol,
            @RequestParam String exchange,
            @RequestParam String token,
            @RequestParam(defaultValue = "5m") String interval,
            @RequestParam(defaultValue = "5") int days) {
        String normalizedInterval = normalizeInterval(interval);
        List<Candle> candles =
                marketDataService.fetchHistoricalCandles(
                        exchange,
                        token,
                        symbol,
                        normalizedInterval,
                        Math.max(1, Math.min(days, 30)));

        return ResponseEntity.ok(OhlcResponse.of(symbol, exchange, token, interval, candles));
    }

    /** Health check endpoint. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(
                Map.of(
                        "status", "UP",
                        "service", "Shoonya Market Data REST Service",
                        "supportedIntervals", List.of("1m", "5m", "15m", "1h", "60m")));
    }

    /**
     * Normalizes timeframe string (e.g. "1m", "5m", "15m", "1h", "60m") to Shoonya API values ("1",
     * "5", "15", "60").
     */
    private String normalizeInterval(String interval) {
        if (interval == null || interval.isBlank()) {
            return "5";
        }
        String clean = interval.trim().toLowerCase();
        return switch (clean) {
            case "1", "1m", "1min", "1minute" -> "1";
            case "5", "5m", "5min", "5minutes" -> "5";
            case "15", "15m", "15min", "15minutes" -> "15";
            case "60", "60m", "1h", "1hour", "60min" -> "60";
            case "d", "1d", "day", "daily" -> "D";
            default ->
                    clean.replaceAll("[^0-9]", "").isEmpty() ? "5" : clean.replaceAll("[^0-9]", "");
        };
    }
}
