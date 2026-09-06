package com.tradingbot.controller;

import com.tradingbot.backtest.BacktestResult;
import com.tradingbot.backtest.PivotSuperTrendBacktestService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.scheduler.PivotSuperTrendScheduler;
import com.tradingbot.strategy.TradeSignal;
import com.tradingbot.strategy.impl.PivotSuperTrendOptionSellingStrategy;
import java.time.Instant;
import java.time.LocalTime;
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
 * REST controller for Intraday Directional Option Selling (Pivot R1/S1 + SuperTrend 7,3) strategy
 * lifecycle, live evaluation, and historical backtesting.
 */
@RestController
@RequestMapping("/api/v1/strategy/pivot-supertrend")
public class PivotSuperTrendStrategyController {

    private final PivotSuperTrendOptionSellingStrategy strategy;
    private final PivotSuperTrendBacktestService backtestService;
    private final ShoonyaMarketDataService marketDataService;
    private final PivotSuperTrendScheduler scheduler;
    private final com.tradingbot.telegram.TelegramService telegramService;

    @Autowired
    public PivotSuperTrendStrategyController(
            PivotSuperTrendOptionSellingStrategy strategy,
            PivotSuperTrendBacktestService backtestService,
            ShoonyaMarketDataService marketDataService,
            @Autowired(required = false) PivotSuperTrendScheduler scheduler,
            @Autowired(required = false) com.tradingbot.telegram.TelegramService telegramService) {
        this.strategy = strategy;
        this.backtestService = backtestService;
        this.marketDataService = marketDataService;
        this.scheduler = scheduler;
        this.telegramService = telegramService;
    }

    public PivotSuperTrendStrategyController(
            PivotSuperTrendOptionSellingStrategy strategy,
            PivotSuperTrendBacktestService backtestService,
            ShoonyaMarketDataService marketDataService) {
        this(strategy, backtestService, marketDataService, null, null);
    }

    /**
     * Get live state and parameters of the Pivot SuperTrend Option Selling Strategy. Example: GET
     * /api/v1/strategy/pivot-supertrend/status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStrategyStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("strategyId", strategy.getId());
        status.put("name", strategy.getName());
        status.put("enabled", strategy.isEnabled());
        status.put("timeframe", strategy.getTimeframe());
        status.put(
                "pivotPoint", strategy.getPivotPoint() > 0 ? strategy.getPivotPoint() : "PENDING");
        status.put("r1Level", strategy.getR1Level() > 0 ? strategy.getR1Level() : "PENDING");
        status.put("s1Level", strategy.getS1Level() > 0 ? strategy.getS1Level() : "PENDING");
        status.put("inPosition", strategy.isInPosition());
        status.put(
                "positionType",
                strategy.getPositionType() != null ? strategy.getPositionType() : "NONE");
        status.put(
                "positionSymbol", strategy.isInPosition() ? strategy.getPositionSymbol() : "NONE");
        status.put("entryUnderlyingPrice", strategy.getEntryUnderlyingPrice());
        status.put("atmStrike", strategy.getAtmStrike());
        status.put("entryPremium", strategy.getEntryPremium());
        status.put("telegramAlertsEnabled", strategy.isTelegramAlertsEnabled());
        status.put("schedulerEnabled", scheduler != null && scheduler.isSchedulerEnabled());
        status.put("autoExecute", scheduler != null && scheduler.isAutoExecute());
        status.put("activeTradeId", scheduler != null ? scheduler.getActiveTradeId() : null);
        status.put("lots", scheduler != null ? scheduler.getLots() : strategy.getLots());
        status.put("lotSize", scheduler != null ? scheduler.getLotSize() : strategy.getLotSize());
        status.put(
                "lotQuantity",
                scheduler != null
                        ? scheduler.getLotQuantity()
                        : (strategy.getLots() * strategy.getLotSize()));
        status.put("buyHedge", strategy.isBuyHedge());
        status.put("hedgeDistance", strategy.getHedgeDistance());
        status.put("hedgeStrike", strategy.getHedgeStrike());
        status.put("hedgePremium", strategy.getHedgePremium());
        status.put("hedgeSymbol", strategy.getHedgeSymbol());
        status.put("dailyTradesCount", strategy.getDailyTradesCount());
        status.put("maxDailyTrades", PivotSuperTrendOptionSellingStrategy.MAX_DAILY_TRADES);
        status.put(
                "squareOffTime", PivotSuperTrendOptionSellingStrategy.SQUARE_OFF_TIME.toString());
        status.put("oiFilterEnabled", strategy.isOiFilterEnabled());
        status.put("oiDiffThreshold", strategy.getOiDiffThreshold());
        status.put("oiDirectional", strategy.isOiDirectional());
        status.put("lastOtmCallOi", strategy.getLastOtmCallOi());
        status.put("lastOtmPutOi", strategy.getLastOtmPutOi());
        status.put("lastOiDifference", strategy.getLastOiDifference());
        status.put("entryStartTime", strategy.getEntryStartTime().toString());
        status.put("stopLossEnabled", strategy.isStopLossEnabled());
        status.put("stopLossPercent", strategy.getStopLossPercent());
        status.put("targetProfitEnabled", strategy.isTargetProfitEnabled());
        status.put("targetProfitPercent", strategy.getTargetProfitPercent());
        status.put("timestamp", Instant.now());
        return ResponseEntity.ok(status);
    }

    /**
     * Evaluate latest live 5m market candle. Example: POST
     * /api/v1/strategy/pivot-supertrend/evaluate
     */
    @PostMapping("/evaluate")
    public ResponseEntity<TradeSignal> evaluateLatestMarketCandle() {
        if (scheduler != null) {
            return ResponseEntity.ok(scheduler.evaluateCurrentCandle());
        }
        List<Candle> candles =
                marketDataService.fetchHistoricalCandles("NFO", "68407", "NIFTY50", "5", 2);
        if (candles == null || candles.isEmpty()) {
            return ResponseEntity.ok(
                    TradeSignal.hold(strategy.getId(), "NIFTY50", "No market data available"));
        }
        Candle latest = candles.get(candles.size() - 1);
        List<Candle> history = candles.subList(0, candles.size() - 1);

        TradeSignal signal = strategy.onCandle(latest, history);
        return ResponseEntity.ok(signal);
    }

    /** Evaluate a custom candle payload directly. */
    @PostMapping("/evaluate/custom")
    public ResponseEntity<TradeSignal> evaluateCustomCandle(@RequestBody Candle candle) {
        TradeSignal signal = strategy.onCandle(candle, List.of());
        return ResponseEntity.ok(signal);
    }

    /**
     * Run historical backtest over Shoonya 5m candles. Example: POST
     * /api/v1/strategy/pivot-supertrend/backtest?days=90&lots=5&simulateOi=false
     */
    @PostMapping("/backtest")
    public ResponseEntity<BacktestResult> runBacktest(
            @RequestParam(defaultValue = "90") int days,
            @RequestParam(defaultValue = "5") int lots,
            @RequestParam(defaultValue = "false") boolean simulateOi) {
        BacktestResult result =
                backtestService.runBacktest(Math.max(1, Math.min(days, 95)), lots, simulateOi);
        return ResponseEntity.ok(result);
    }

    /**
     * Reset daily intraday state (daily trade counter, pivots, positions). Example: POST
     * /api/v1/strategy/pivot-supertrend/reset
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, String>> resetStrategyState() {
        strategy.onResetDaily();
        if (scheduler != null) {
            scheduler.setActiveTradeId(null);
        }
        return ResponseEntity.ok(
                Map.of(
                        "status", "SUCCESS",
                        "message",
                                "Pivot SuperTrend Option Selling Strategy state reset successfully"));
    }

    /**
     * Enable or pause strategy. Example: POST /api/v1/strategy/pivot-supertrend/enable?enabled=true
     */
    @PostMapping("/enable")
    public ResponseEntity<Map<String, Object>> setEnabled(@RequestParam boolean enabled) {
        strategy.setEnabled(enabled);
        return ResponseEntity.ok(
                Map.of(
                        "strategyId", strategy.getId(),
                        "enabled", strategy.isEnabled()));
    }

    /**
     * Enable or disable Telegram notifications for strategy signals. Example: POST
     * /api/v1/strategy/pivot-supertrend/telegram?enabled=true
     */
    @PostMapping("/telegram")
    public ResponseEntity<Map<String, Object>> setTelegramAlertsEnabled(
            @RequestParam boolean enabled) {
        strategy.setTelegramAlertsEnabled(enabled);
        return ResponseEntity.ok(
                Map.of(
                        "strategyId", strategy.getId(),
                        "telegramAlertsEnabled", strategy.isTelegramAlertsEnabled()));
    }

    /**
     * Send an instant test Telegram notification to verify alert delivery. Example: POST
     * /api/v1/strategy/pivot-supertrend/telegram/test
     */
    @PostMapping("/telegram/test")
    public ResponseEntity<Map<String, Object>> testTelegramAlert() {
        boolean sent = false;
        if (telegramService != null) {
            sent =
                    telegramService.sendTestAlert(
                            PivotSuperTrendOptionSellingStrategy.STRATEGY_NAME);
        }
        return ResponseEntity.ok(
                Map.of(
                        "strategyId",
                        strategy.getId(),
                        "telegramAlertsEnabled",
                        strategy.isTelegramAlertsEnabled(),
                        "testAlertSent",
                        sent,
                        "message",
                        sent
                                ? "Test alert dispatched to Telegram"
                                : "Telegram service unavailable, disabled, or missing credentials"));
    }

    /**
     * Enable or disable the production 5m background scheduler. Example: POST
     * /api/v1/strategy/pivot-supertrend/scheduler?enabled=true
     */
    @PostMapping("/scheduler")
    public ResponseEntity<Map<String, Object>> setSchedulerEnabled(@RequestParam boolean enabled) {
        if (scheduler != null) {
            scheduler.setSchedulerEnabled(enabled);
        }
        return ResponseEntity.ok(
                Map.of(
                        "strategyId",
                        strategy.getId(),
                        "schedulerEnabled",
                        scheduler != null && scheduler.isSchedulerEnabled()));
    }

    /**
     * Toggle auto-execution mode (true = auto-place orders via ExecutionManager, false = Telegram
     * alerts only). Example: POST /api/v1/strategy/pivot-supertrend/auto-execute?enabled=true
     */
    @PostMapping("/auto-execute")
    public ResponseEntity<Map<String, Object>> setAutoExecute(@RequestParam boolean enabled) {
        if (scheduler != null) {
            scheduler.setAutoExecute(enabled);
        }
        return ResponseEntity.ok(
                Map.of(
                        "strategyId",
                        strategy.getId(),
                        "autoExecute",
                        scheduler != null && scheduler.isAutoExecute()));
    }

    /**
     * Manual on-demand trigger of the 15:14 IST Auto Square-Off. Example: POST
     * /api/v1/strategy/pivot-supertrend/square-off
     */
    @PostMapping("/square-off")
    public ResponseEntity<Map<String, Object>> triggerSquareOff(
            @RequestParam(defaultValue = "MANUAL_SQUARE_OFF") String reason) {
        if (scheduler != null) {
            scheduler.triggerAutoSquareOff(reason);
        }
        return ResponseEntity.ok(
                Map.of(
                        "strategyId",
                        strategy.getId(),
                        "status",
                        "SUCCESS",
                        "message",
                        "Auto square-off triggered: " + reason));
    }

    /**
     * Configure OI filter settings. Example: POST
     * /api/v1/strategy/pivot-supertrend/oi-filter?enabled=true&threshold=5000000&directional=true
     */
    @PostMapping("/oi-filter")
    public ResponseEntity<Map<String, Object>> configureOiFilter(
            @RequestParam boolean enabled,
            @RequestParam(defaultValue = "5000000") long threshold,
            @RequestParam(defaultValue = "true") boolean directional) {
        strategy.setOiFilterEnabled(enabled);
        strategy.setOiDiffThreshold(threshold);
        strategy.setOiDirectional(directional);
        return ResponseEntity.ok(
                Map.of(
                        "strategyId", strategy.getId(),
                        "oiFilterEnabled", strategy.isOiFilterEnabled(),
                        "oiDiffThreshold", strategy.getOiDiffThreshold(),
                        "oiDirectional", strategy.isOiDirectional()));
    }

    /**
     * Configure Morning Filter, Hard Stop Loss, and Target Profit parameters. Example: POST
     * /api/v1/strategy/pivot-supertrend/risk-config?entryStartTime=09:30:00&stopLossEnabled=true&stopLossPercent=30.0&targetProfitEnabled=true&targetProfitPercent=50.0
     */
    @PostMapping("/risk-config")
    public ResponseEntity<Map<String, Object>> configureRisk(
            @RequestParam(required = false) String entryStartTime,
            @RequestParam(required = false) Boolean stopLossEnabled,
            @RequestParam(required = false) Double stopLossPercent,
            @RequestParam(required = false) Boolean targetProfitEnabled,
            @RequestParam(required = false) Double targetProfitPercent) {
        if (entryStartTime != null && !entryStartTime.isBlank()) {
            strategy.setEntryStartTime(LocalTime.parse(entryStartTime));
        }
        if (stopLossEnabled != null) {
            strategy.setStopLossEnabled(stopLossEnabled);
        }
        if (stopLossPercent != null && stopLossPercent > 0) {
            strategy.setStopLossPercent(stopLossPercent);
        }
        if (targetProfitEnabled != null) {
            strategy.setTargetProfitEnabled(targetProfitEnabled);
        }
        if (targetProfitPercent != null && targetProfitPercent > 0) {
            strategy.setTargetProfitPercent(targetProfitPercent);
        }
        return ResponseEntity.ok(
                Map.of(
                        "strategyId", strategy.getId(),
                        "entryStartTime", strategy.getEntryStartTime().toString(),
                        "stopLossEnabled", strategy.isStopLossEnabled(),
                        "stopLossPercent", strategy.getStopLossPercent(),
                        "targetProfitEnabled", strategy.isTargetProfitEnabled(),
                        "targetProfitPercent", strategy.getTargetProfitPercent()));
    }

    /**
     * Configure Defined-Risk Credit Spread and Position Sizing parameters. Example: POST
     * /api/v1/strategy/pivot-supertrend/spread-config?buyHedge=true&hedgeDistance=150&lots=1
     */
    @PostMapping("/spread-config")
    public ResponseEntity<Map<String, Object>> configureSpread(
            @RequestParam(required = false) Boolean buyHedge,
            @RequestParam(required = false) Integer hedgeDistance,
            @RequestParam(required = false) Integer lots) {
        if (buyHedge != null) {
            strategy.setBuyHedge(buyHedge);
            if (scheduler != null) {
                scheduler.setBuyHedge(buyHedge);
            }
        }
        if (hedgeDistance != null && hedgeDistance > 0) {
            strategy.setHedgeDistance(hedgeDistance);
        }
        if (lots != null && lots > 0) {
            strategy.setLots(lots);
            if (scheduler != null) {
                scheduler.setLots(lots);
            }
        }
        return ResponseEntity.ok(
                Map.of(
                        "strategyId", strategy.getId(),
                        "buyHedge", strategy.isBuyHedge(),
                        "hedgeDistance", strategy.getHedgeDistance(),
                        "lots", strategy.getLots(),
                        "lotQuantity", strategy.getLots() * strategy.getLotSize()));
    }
}
