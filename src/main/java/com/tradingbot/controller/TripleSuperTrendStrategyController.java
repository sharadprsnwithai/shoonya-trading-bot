package com.tradingbot.controller;

import com.tradingbot.backtest.BacktestResult;
import com.tradingbot.backtest.TripleSuperTrendBacktestService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.execution.TripleSuperTrendPosition;
import com.tradingbot.service.BasketHealthScoringService;
import com.tradingbot.strategy.TradeSignal;
import com.tradingbot.strategy.impl.TripleSuperTrendRsiOptionBuyingStrategy;
import com.tradingbot.util.StockFnoRegistry;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API Controller for Triple SuperTrend + RSI Directional Options Buying Strategy. Monitors and
 * trades NIFTY 50 and 29 high-liquidity stock underlyings.
 */
@RestController
@RequestMapping("/api/v1/strategy/triple-supertrend")
public class TripleSuperTrendStrategyController {

    private final TripleSuperTrendRsiOptionBuyingStrategy strategy;
    private final TripleSuperTrendBacktestService backtestService;
    private final ShoonyaMarketDataService marketDataService;
    private final BasketHealthScoringService basketHealthScoringService;

    @Autowired
    public TripleSuperTrendStrategyController(
            TripleSuperTrendRsiOptionBuyingStrategy strategy,
            TripleSuperTrendBacktestService backtestService,
            ShoonyaMarketDataService marketDataService,
            BasketHealthScoringService basketHealthScoringService) {
        this.strategy = strategy;
        this.backtestService = backtestService;
        this.marketDataService = marketDataService;
        this.basketHealthScoringService = basketHealthScoringService;
    }

    /** Returns live strategy status, parameters, and open positions. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("strategyId", strategy.getId());
        status.put("strategyName", strategy.getName());
        status.put("mode", strategy.getMode());
        status.put("enabled", strategy.isEnabled());
        status.put("timeframe", strategy.getTimeframe());
        status.put("subscribedSymbolsCount", strategy.getSubscribedSymbols().size());
        status.put("activePositionsCount", strategy.getActivePositions().size());
        status.put("activePositions", strategy.getActivePositions());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("mode", strategy.getMode());
        params.put("creditSpreadEnabled", strategy.isCreditSpreadEnabled());
        params.put("symbolBasket", strategy.getSymbolBasket());
        params.put("adxFilterEnabled", strategy.isAdxFilterEnabled());
        params.put("adxThreshold", strategy.getAdxThreshold());
        params.put("rsiBullishMax", strategy.getRsiBullishMax());
        params.put("rsiBearishMin", strategy.getRsiBearishMin());
        params.put("cooldownBars", strategy.getCooldownBars());
        params.put("hedgeStrikeOffset", strategy.getHedgeStrikeOffset());
        params.put("rejectionWickFilterEnabled", strategy.isRejectionWickFilterEnabled());
        params.put("rejectionWickThreshold", strategy.getRejectionWickThreshold());
        params.put("atrFilterEnabled", strategy.isAtrFilterEnabled());
        params.put("minAtrRatio", strategy.getMinAtrRatio());
        params.put("maxAtrRatio", strategy.getMaxAtrRatio());
        params.put("emaFilterEnabled", strategy.isEmaFilterEnabled());
        params.put("emaPeriod", strategy.getEmaPeriod());
        params.put("volumeFilterEnabled", strategy.isVolumeFilterEnabled());
        params.put("volumeMultiplier", strategy.getVolumeMultiplier());
        params.put("fastSuperTrend", "7, 2.0 (Exit & Confirmation)");
        params.put("mediumSuperTrend", "10, 3.0 (Trend Confirmation)");
        params.put("slowSuperTrend", "14, 4.0 (Dominant Trend)");
        params.put("rsiFilter", "14 Period (Bullish >= 40-50, Bearish <= 50-60)");
        params.put("expirySelection", "Monthly Contract targeting ~30-40 DTE");
        params.put(
                "executionModel",
                strategy.isOptionSelling()
                        ? (strategy.isCreditSpreadEnabled()
                                ? "Defined-Risk Credit Spreads (Bull Put Spread / Bear Call Spread)"
                                : "Directional Option Selling (Short ATM PE on Bullish, Short ATM CE on Bearish)")
                        : "Directional Option Buying (Long ATM CE on Bullish, Long ATM PE on Bearish)");
        status.put("parameters", params);

        return ResponseEntity.ok(status);
    }

    /** Returns all 30 subscribed symbols with standard strike intervals and lot sizes. */
    @GetMapping("/symbols")
    public ResponseEntity<Map<String, Object>> getSubscribedSymbols() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("totalSymbols", StockFnoRegistry.getAllSubscribedSymbols().size());
        res.put("symbols", StockFnoRegistry.getAllSubscribedSymbols());
        res.put("instruments", StockFnoRegistry.getAllInstruments());
        return ResponseEntity.ok(res);
    }

    /** Returns all currently active positions across all instruments. */
    @GetMapping("/positions")
    public ResponseEntity<Map<String, TripleSuperTrendPosition>> getPositions() {
        return ResponseEntity.ok(strategy.getActivePositions());
    }

    /** Evaluates a single candle against the strategy. */
    @PostMapping("/evaluate")
    public ResponseEntity<TradeSignal> evaluateCandle(
            @RequestBody Candle latestCandle, @RequestBody(required = false) List<Candle> history) {
        TradeSignal signal = strategy.onCandle(latestCandle, history != null ? history : List.of());
        return ResponseEntity.ok(signal);
    }

    /** Squares off a specific symbol's position or all active positions. */
    @PostMapping("/square-off")
    public ResponseEntity<Map<String, Object>> squareOff(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) BigDecimal exitPrice,
            @RequestParam(required = false, defaultValue = "Manual REST API Square-Off")
                    String reason) {
        Map<String, Object> res = new LinkedHashMap<>();
        if (symbol != null && !symbol.isBlank()) {
            strategy.squareOffPosition(symbol, exitPrice, reason);
            res.put("status", "SUCCESS");
            res.put("message", "Squared off position for symbol: " + symbol);
        } else {
            strategy.squareOffAll(reason);
            res.put("status", "SUCCESS");
            res.put("message", "Squared off all active positions");
        }
        res.put("remainingPositions", strategy.getActivePositions().size());
        return ResponseEntity.ok(res);
    }

    /** Toggles the strategy enabled state. */
    @PostMapping("/toggle")
    public ResponseEntity<Map<String, Object>> toggleEnabled(@RequestParam boolean enabled) {
        strategy.setEnabled(enabled);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("strategyId", strategy.getId());
        res.put("enabled", strategy.isEnabled());
        return ResponseEntity.ok(res);
    }

    /** Fetches live hourly candles from Shoonya for any of the 30 symbols. */
    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> getHourlyData(
            @RequestParam(defaultValue = "NIFTY50") String symbol,
            @RequestParam(defaultValue = "30") int days) {
        String token = marketDataService.resolveToken(symbol);
        List<Candle> candles = marketDataService.fetchHourlyCandles(symbol, days);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("symbol", symbol);
        res.put("token", token);
        res.put("timeframe", "60m (1h)");
        res.put("days", days);
        res.put("candleCount", candles.size());
        res.put("candles", candles);
        return ResponseEntity.ok(res);
    }

    /** Runs backtest on a specific symbol with provided candles or historical data. */
    @PostMapping("/backtest")
    public ResponseEntity<BacktestResult> runBacktest(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "1") int lots,
            @RequestParam(defaultValue = "true") boolean rsiFilter,
            @RequestParam(defaultValue = "true") boolean optionSelling,
            @RequestParam(defaultValue = "true") boolean adxFilter,
            @RequestParam(defaultValue = "22.0") double adxThreshold,
            @RequestParam(defaultValue = "68.0") double rsiBullishMax,
            @RequestParam(defaultValue = "32.0") double rsiBearishMin,
            @RequestParam(defaultValue = "3") int cooldownBars,
            @RequestParam(defaultValue = "true") boolean creditSpread,
            @RequestParam(defaultValue = "true") boolean rejectionWickFilter,
            @RequestParam(defaultValue = "0.60") double rejectionWickThreshold,
            @RequestParam(defaultValue = "true") boolean atrFilter,
            @RequestParam(defaultValue = "0.60") double minAtrRatio,
            @RequestParam(defaultValue = "2.50") double maxAtrRatio,
            @RequestParam(defaultValue = "true") boolean emaFilter,
            @RequestParam(defaultValue = "50") int emaPeriod,
            @RequestBody(required = false) List<Candle> candles) {
        List<Candle> candleList =
                (candles != null && !candles.isEmpty())
                        ? candles
                        : marketDataService.fetchHourlyCandles(symbol, 30);
        BacktestResult result =
                backtestService.evaluateCandles(
                        symbol,
                        candleList,
                        lots,
                        rsiFilter,
                        optionSelling,
                        adxFilter,
                        adxThreshold,
                        rsiBullishMax,
                        rsiBearishMin,
                        cooldownBars,
                        creditSpread,
                        rejectionWickFilter,
                        rejectionWickThreshold,
                        atrFilter,
                        minAtrRatio,
                        maxAtrRatio,
                        emaFilter,
                        emaPeriod);
        return ResponseEntity.ok(result);
    }

    /**
     * Evaluates and ranks all 30 F&O stocks on the quantitative health scorecard (Win Rate, Profit
     * Factor, ADX, ATR%, and Whipsaw penalty) to determine the optimal Top 10 Curated Basket.
     */
    @GetMapping("/basket-health")
    public ResponseEntity<Map<String, Object>> getBasketHealth(
            @RequestParam(defaultValue = "30") int days) {
        List<BasketHealthScoringService.StockHealthScore> ranked =
                basketHealthScoringService.rankAllSymbols(days);
        List<String> top10 =
                ranked.stream()
                        .filter(BasketHealthScoringService.StockHealthScore::isTop10)
                        .map(BasketHealthScoringService.StockHealthScore::symbol)
                        .toList();

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("totalEvaluated", ranked.size());
        res.put("daysEvaluated", days);
        res.put("recommendedTop10", top10);
        res.put("rankings", ranked);
        return ResponseEntity.ok(res);
    }

    /**
     * Runs the quantitative basket evaluation and dispatches the monthly rebalancing report
     * directly to Telegram.
     */
    @PostMapping("/basket-health/notify")
    public ResponseEntity<Map<String, Object>> notifyBasketHealth(
            @RequestParam(defaultValue = "30") int days) {
        List<BasketHealthScoringService.StockHealthScore> ranked =
                basketHealthScoringService.rankAllSymbols(days);
        boolean sent = basketHealthScoringService.sendRebalanceTelegramReport(ranked);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", sent ? "SUCCESS" : "SKIPPED");
        res.put("message", "Telegram monthly rebalance report dispatched");
        res.put("totalSymbolsRanked", ranked.size());
        return ResponseEntity.ok(res);
    }

    /**
     * Executes the monthly basket rebalancing immediately: ranks candidates, selects top 10,
     * updates active curated basket, and dispatches the Telegram report.
     */
    @PostMapping("/rebalance")
    public ResponseEntity<Map<String, Object>> executeMonthlyRebalance(
            @RequestParam(defaultValue = "30") int days) {
        List<BasketHealthScoringService.StockHealthScore> ranked =
                basketHealthScoringService.rankAllSymbols(days);
        List<String> top10 =
                ranked.stream()
                        .filter(BasketHealthScoringService.StockHealthScore::isTop10)
                        .map(BasketHealthScoringService.StockHealthScore::symbol)
                        .toList();

        if (!top10.isEmpty()) {
            StockFnoRegistry.setCuratedSymbols(top10);
        }
        basketHealthScoringService.sendRebalanceTelegramReport(ranked);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "SUCCESS");
        res.put("message", "Monthly rebalancing complete and Telegram report dispatched");
        res.put("activeCuratedBasket", StockFnoRegistry.getCuratedSymbols());
        res.put("totalRanked", ranked.size());
        return ResponseEntity.ok(res);
    }
}
