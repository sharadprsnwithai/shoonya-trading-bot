package com.tradingbot.controller;

import com.tradingbot.model.strategy.LowestVolumePaperPosition;
import com.tradingbot.model.strategy.LowestVolumeSetup;
import com.tradingbot.scheduler.LowestVolumeReversalScheduler;
import com.tradingbot.service.LowestVolumeReversalService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
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
 * REST Controller exposing monitoring, triggering, and paper trade management endpoints for the
 * Lowest Volume Reversal & Continuation Strategy.
 */
@RestController
@RequestMapping("/api/strategy/lowest-volume")
public class LowestVolumeStrategyController {

    private final LowestVolumeReversalService strategyService;
    private final LowestVolumeReversalScheduler scheduler;

    @Autowired
    public LowestVolumeStrategyController(
            LowestVolumeReversalService strategyService, LowestVolumeReversalScheduler scheduler) {
        this.strategyService = strategyService;
        this.scheduler = scheduler;
    }

    /** Triggers an immediate 5-minute strategy cycle on demand. */
    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> runCycle() {
        strategyService.runCycle();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SUCCESS");
        response.put("sectorState", strategyService.getSectorState());
        response.put("niftyBullish", strategyService.isNiftyBullish());
        response.put("currentTopGainers", strategyService.getCurrentTopGainers());
        response.put("currentTopLosers", strategyService.getCurrentTopLosers());
        response.put("activeSetupsCount", strategyService.getActiveSetups().size());
        response.put("openPositionsCount", strategyService.getOpenPositions().size());

        return ResponseEntity.ok(response);
    }

    /**
     * Triggers the 09:25 AM morning universe scan to fix the daily watchlist and notify Telegram.
     */
    @PostMapping("/morning-scan")
    public ResponseEntity<Map<String, Object>> runMorningScan() {
        if (!strategyService.getOpenPositions().isEmpty()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "REJECTED");
            response.put(
                    "message",
                    "Cannot re-run morning scan while open positions exist. Close active positions first.");
            return ResponseEntity.badRequest().body(response);
        }

        strategyService.runMorningUniverseScan();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SUCCESS");
        response.put("message", "Morning universe scan executed. Watchlist fixed for the day.");
        response.put("niftyBullish", strategyService.isNiftyBullish());
        response.put("universeScanCompletedToday", strategyService.isUniverseScanCompletedToday());
        response.put("currentTopGainers", strategyService.getCurrentTopGainers());
        response.put("currentTopLosers", strategyService.getCurrentTopLosers());
        response.put("activeSetupsCount", strategyService.getActiveSetups().size());

        return ResponseEntity.ok(response);
    }

    /** Scans the universe and immediately sends the identified F&O stocks report to Telegram. */
    @PostMapping("/notify")
    public ResponseEntity<Map<String, Object>> scanAndNotifyTelegram() {
        boolean dispatched = strategyService.isTelegramAlerts();
        if (dispatched) {
            strategyService.sendScanTelegramReport();
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SUCCESS");
        response.put(
                "message",
                dispatched
                        ? "Identified F&O stocks report dispatched to Telegram."
                        : "Telegram notifications currently disabled in strategy configuration.");
        response.put("niftyBullish", strategyService.isNiftyBullish());
        response.put("topGainersCount", strategyService.getCurrentTopGainerSnapshots().size());
        response.put("topGainers", strategyService.getCurrentTopGainerSnapshots());
        response.put("topLosersCount", strategyService.getCurrentTopLoserSnapshots().size());
        response.put("topLosers", strategyService.getCurrentTopLoserSnapshots());
        response.put("telegramNotificationDispatched", dispatched);

        return ResponseEntity.ok(response);
    }

    /** Returns current strategy health, risk metrics, and market alignment. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("strategy", "Lowest Volume Reversal & Continuation");
        status.put("enabled", strategyService.isEnabled());
        status.put("schedulerEnabled", scheduler.isSchedulerEnabled());
        status.put("universeScanCompletedToday", strategyService.isUniverseScanCompletedToday());
        status.put("sectorState", strategyService.getSectorState());
        status.put("paperCapital", strategyService.getPaperCapital());
        status.put("riskPerTradePercent", strategyService.getRiskPerTradePercent());
        status.put("riskPerTradeAmount", strategyService.getRiskPerTradeAmount());
        status.put("vwapConfirmationEnabled", strategyService.isVwapConfirmationEnabled());
        status.put("sectorMomentumFilterEnabled", strategyService.isSectorMomentumFilterEnabled());
        status.put("lots", strategyService.getDefaultLots());
        status.put("dynamicPositionSizing", strategyService.isDynamicPositionSizing());
        status.put("maxAttemptsPerSymbol", strategyService.getMaxAttemptsPerSymbol());
        status.put("minBreadthPct", strategyService.getMinBreadthPct());
        status.put("minStopLossPct", strategyService.getMinStopLossPct());
        status.put("maxDailyLoss", strategyService.getMaxDailyLoss());
        status.put("dailyCircuitBreakerTripped", strategyService.isDailyCircuitBreakerTripped());
        status.put("maxConcurrentTrades", strategyService.getMaxConcurrentTrades());
        status.put("niftyBullish", strategyService.isNiftyBullish());
        status.put("topGainers", strategyService.getCurrentTopGainers());
        status.put("topLosers", strategyService.getCurrentTopLosers());
        status.put("exhaustedSymbolsCount", strategyService.getExhaustedSymbols().size());
        status.put("reservoirCount", strategyService.getCandidateReservoir().size());
        status.put("candidateReservoir", strategyService.getCandidateReservoir());
        status.put("activeSetupsCount", strategyService.getActiveSetups().size());
        status.put("openPositionsCount", strategyService.getOpenPositions().size());
        status.put("closedTradesCount", strategyService.getTradeHistory().size());

        // Calculate Total Realized & Unrealized P&L
        double unrealizedPnl = strategyService.calculateOpenPositionsUnrealizedPnl();
        BigDecimal totalRealized =
                strategyService.getTradeHistory().stream()
                        .map(LowestVolumePaperPosition::getTotalRealizedPnl)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalUnrealized =
                BigDecimal.valueOf(unrealizedPnl).setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalNetPnl = totalRealized.add(totalUnrealized);

        status.put("totalRealizedPnl", totalRealized);
        status.put("totalUnrealizedPnl", totalUnrealized);
        status.put("totalNetPnl", totalNetPnl);

        return ResponseEntity.ok(status);
    }

    /** Returns all tracked setups and their state machine progression. */
    @GetMapping("/setups")
    public ResponseEntity<Map<String, Object>> getSetups() {
        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, LowestVolumeSetup> setups = strategyService.getActiveSetups();
        response.put("count", setups.size());
        response.put("setups", setups);
        return ResponseEntity.ok(response);
    }

    /** Returns all active paper trading positions. */
    @GetMapping("/positions")
    public ResponseEntity<Map<String, Object>> getOpenPositions() {
        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, LowestVolumePaperPosition> positions = strategyService.getOpenPositions();
        response.put("count", positions.size());
        response.put("positions", positions.values());
        return ResponseEntity.ok(response);
    }

    /** Returns the history of closed paper trades for the session. */
    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> getTradeHistory() {
        Map<String, Object> response = new LinkedHashMap<>();
        List<LowestVolumePaperPosition> history = strategyService.getTradeHistory();
        BigDecimal totalPnl =
                history.stream()
                        .map(LowestVolumePaperPosition::getTotalRealizedPnl)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        response.put("count", history.size());
        response.put("totalRealizedPnl", totalPnl);
        response.put("trades", history);
        return ResponseEntity.ok(response);
    }

    /** Resets the daily session state manually. */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetDaily() {
        strategyService.resetDaily();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SUCCESS");
        response.put("message", "Daily strategy state successfully reset.");
        return ResponseEntity.ok(response);
    }

    /** Toggles the strategy or scheduler on/off. */
    @PostMapping("/toggle")
    public ResponseEntity<Map<String, Object>> toggle(
            @RequestParam(required = false) Boolean strategyEnabled,
            @RequestParam(required = false) Boolean schedulerEnabled) {
        if (strategyEnabled != null) {
            strategyService.setEnabled(strategyEnabled);
        }
        if (schedulerEnabled != null) {
            scheduler.setSchedulerEnabled(schedulerEnabled);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("strategyEnabled", strategyService.isEnabled());
        response.put("schedulerEnabled", scheduler.isSchedulerEnabled());
        return ResponseEntity.ok(response);
    }
}
