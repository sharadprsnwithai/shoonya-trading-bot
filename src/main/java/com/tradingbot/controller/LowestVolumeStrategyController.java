package com.tradingbot.controller;

import com.tradingbot.model.strategy.LowestVolumePaperPosition;
import com.tradingbot.model.strategy.LowestVolumeSetup;
import com.tradingbot.scheduler.LowestVolumeReversalScheduler;
import com.tradingbot.service.LowestVolumeReversalService;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * REST Controller exposing monitoring, triggering, and paper trade management endpoints for the
 * Lowest Volume Reversal & Continuation Strategy.
 */
@RestController
@RequestMapping("/api/strategy/lowest-volume")
public class LowestVolumeStrategyController {

    private static final Logger log = LoggerFactory.getLogger(LowestVolumeStrategyController.class);

    private final LowestVolumeReversalService strategyService;
    private final LowestVolumeReversalScheduler scheduler;

    // L1: scans are long (paced quote fetches); run them off the HTTP threads and let callers
    // poll the job status instead of holding the request open.
    private final ExecutorService scanExecutor =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread t = new Thread(runnable, "lvr-scan-job");
                        t.setDaemon(true);
                        return t;
                    });
    private final Map<String, ScanJob> scanJobs = new ConcurrentHashMap<>();
    private final Map<String, ScanJob> currentJobByKind = new ConcurrentHashMap<>();
    private final Object jobClaimLock = new Object();
    private static final int MAX_RETAINED_JOBS = 10;

    @Autowired
    public LowestVolumeStrategyController(
            LowestVolumeReversalService strategyService, LowestVolumeReversalScheduler scheduler) {
        this.strategyService = strategyService;
        this.scheduler = scheduler;
    }

    @PreDestroy
    public void shutdownExecutor() {
        scanExecutor.shutdownNow();
    }

    /** L1: async scan job tracked for polling via {@code GET /scan/status}. */
    private static final class ScanJob {
        final String jobId = UUID.randomUUID().toString();
        final String kind;
        final Instant submittedAt = Instant.now();
        volatile String state = "QUEUED";
        volatile Instant startedAt;
        volatile Instant finishedAt;
        volatile String error;
        volatile Map<String, Object> result;

        ScanJob(String kind) {
            this.kind = kind;
        }
    }

    /**
     * Triggers an immediate 5-minute strategy cycle. By default the cycle runs asynchronously and
     * the call returns {@code 202 + jobId} (L1); poll {@code GET /scan/status} for the result. Pass
     * {@code ?sync=true} to block until completion (legacy behaviour).
     */
    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> runCycle(
            @RequestParam(required = false, defaultValue = "false") boolean sync) {
        Supplier<Map<String, Object>> work =
                () -> {
                    strategyService.runCycle();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "SUCCESS");
                    result.put("sectorState", strategyService.getSectorState());
                    result.put("niftyBullish", strategyService.isNiftyBullish());
                    result.put("currentTopGainers", strategyService.getCurrentTopGainers());
                    result.put("currentTopLosers", strategyService.getCurrentTopLosers());
                    result.put("activeSetupsCount", strategyService.getActiveSetups().size());
                    result.put("openPositionsCount", strategyService.getOpenPositions().size());
                    return result;
                };
        return submitOrRunSync("CYCLE", sync, work);
    }

    /**
     * Triggers the 09:25 AM morning universe scan to fix the daily watchlist and notify Telegram.
     * Runs asynchronously by default (L1) — poll {@code GET /scan/status}.
     */
    @PostMapping("/morning-scan")
    public ResponseEntity<Map<String, Object>> runMorningScan(
            @RequestParam(required = false, defaultValue = "false") boolean sync) {
        if (!strategyService.getOpenPositions().isEmpty()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "REJECTED");
            response.put(
                    "message",
                    "Cannot re-run morning scan while open positions exist. Close active positions first.");
            return ResponseEntity.badRequest().body(response);
        }

        Supplier<Map<String, Object>> work =
                () -> {
                    strategyService.runMorningUniverseScan();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "SUCCESS");
                    result.put(
                            "message",
                            "Morning universe scan executed. Watchlist fixed for the day.");
                    result.put("niftyBullish", strategyService.isNiftyBullish());
                    result.put(
                            "universeScanCompletedToday",
                            strategyService.isUniverseScanCompletedToday());
                    result.put("currentTopGainers", strategyService.getCurrentTopGainers());
                    result.put("currentTopLosers", strategyService.getCurrentTopLosers());
                    result.put("activeSetupsCount", strategyService.getActiveSetups().size());
                    return result;
                };
        return submitOrRunSync("MORNING_SCAN", sync, work);
    }

    /** L1: status of async scan jobs (latest first, max 10 retained). */
    @GetMapping("/scan/status")
    public ResponseEntity<Map<String, Object>> getScanStatus() {
        List<ScanJob> jobs = new ArrayList<>(scanJobs.values());
        jobs.sort(Comparator.comparing((ScanJob j) -> j.submittedAt).reversed());
        if (jobs.size() > MAX_RETAINED_JOBS) {
            jobs = jobs.subList(0, MAX_RETAINED_JOBS);
        }

        boolean running =
                currentJobByKind.values().stream()
                        .anyMatch(j -> "QUEUED".equals(j.state) || "RUNNING".equals(j.state));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("running", running);
        response.put("count", jobs.size());
        List<Map<String, Object>> jobViews = new ArrayList<>();
        for (ScanJob job : jobs) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("jobId", job.jobId);
            view.put("kind", job.kind);
            view.put("state", job.state);
            view.put("submittedAt", job.submittedAt.toString());
            view.put("startedAt", job.startedAt != null ? job.startedAt.toString() : null);
            view.put("finishedAt", job.finishedAt != null ? job.finishedAt.toString() : null);
            if (job.startedAt != null && job.finishedAt != null) {
                view.put(
                        "durationMs", job.finishedAt.toEpochMilli() - job.startedAt.toEpochMilli());
            }
            view.put("error", job.error);
            view.put("result", job.result);
            jobViews.add(view);
        }
        response.put("jobs", jobViews);
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<Map<String, Object>> submitOrRunSync(
            String kind, boolean sync, Supplier<Map<String, Object>> work) {
        if (sync) {
            Map<String, Object> response = new LinkedHashMap<>(work.get());
            response.putIfAbsent("mode", "SYNC");
            return ResponseEntity.ok(response);
        }

        ScanJob job;
        synchronized (jobClaimLock) {
            ScanJob current = currentJobByKind.get(kind);
            if (current != null
                    && ("QUEUED".equals(current.state) || "RUNNING".equals(current.state))) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("status", "REJECTED");
                response.put("message", kind + " scan already in progress.");
                response.put("jobId", current.jobId);
                response.put("statusUrl", "/api/strategy/lowest-volume/scan/status");
                return ResponseEntity.status(409).body(response);
            }
            job = new ScanJob(kind);
            scanJobs.put(job.jobId, job);
            currentJobByKind.put(kind, job);
            trimJobs();
        }

        final ScanJob submitted = job;
        scanExecutor.submit(
                () -> {
                    submitted.state = "RUNNING";
                    submitted.startedAt = Instant.now();
                    try {
                        submitted.result = work.get();
                        submitted.state = "COMPLETED";
                    } catch (Exception e) {
                        submitted.state = "FAILED";
                        submitted.error = e.getMessage();
                        log.error(
                                "[LVR-SCAN] {} scan job {} failed: {}",
                                submitted.kind,
                                submitted.jobId,
                                e.getMessage(),
                                e);
                    } finally {
                        submitted.finishedAt = Instant.now();
                    }
                });

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ACCEPTED");
        response.put("mode", "ASYNC");
        response.put("kind", kind);
        response.put("jobId", job.jobId);
        response.put("statusUrl", "/api/strategy/lowest-volume/scan/status");
        response.put("message", kind + " scan submitted. Poll statusUrl for completion (L1).");
        return ResponseEntity.accepted().body(response);
    }

    private void trimJobs() {
        if (scanJobs.size() <= MAX_RETAINED_JOBS) {
            return;
        }
        scanJobs.values().stream()
                .filter(j -> "COMPLETED".equals(j.state) || "FAILED".equals(j.state))
                .sorted(
                        Comparator.comparing(
                                        (ScanJob j) ->
                                                j.finishedAt != null ? j.finishedAt : Instant.MAX)
                                .reversed())
                .skip(MAX_RETAINED_JOBS)
                .forEach(j -> scanJobs.remove(j.jobId));
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

    /**
     * Returns current strategy health, risk metrics, and market alignment. Serves the last-known
     * unrealized P&amp;L (L2) — pass {@code ?refresh=true} to force live quote fetches.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(
            @RequestParam(required = false, defaultValue = "false") boolean refresh) {
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

        // Calculate Total Realized & Unrealized P&L (L2: cached by default, live on refresh)
        double unrealizedPnl =
                refresh
                        ? strategyService.calculateOpenPositionsUnrealizedPnl()
                        : strategyService.getCachedUnrealizedPnl();
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

    /**
     * Resets the daily session state manually.
     *
     * <p>H6: refused during market hours (09:30–15:00 IST) unless {@code ?force=true} — an intraday
     * reset hard-exits open (possibly live) positions. Realized P&amp;L is never erased (soft
     * same-day reset / archive carry in the service). Every attempt writes an audit line.
     */
    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetDaily(
            @RequestParam(required = false, defaultValue = "false") boolean force) {
        boolean duringTradingHours = strategyService.isWithinTradingHours();
        int openPositions = strategyService.getOpenPositions().size();
        String caller = currentCallerAddress();

        if (duringTradingHours && !force) {
            log.warn(
                    "[AUDIT] LVR /reset REFUSED: caller={}, openPositions={},"
                            + " duringTradingHours=true, force=false",
                    caller,
                    openPositions);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "REJECTED");
            response.put(
                    "message",
                    "Intraday reset refused — market is open and positions may be live."
                            + " Re-run with ?force=true to flatten positions and reset (realized"
                            + " P&L is preserved).");
            return ResponseEntity.status(409).body(response);
        }

        strategyService.resetDaily(force);
        log.warn(
                "[AUDIT] LVR /reset EXECUTED: caller={}, force={}, duringTradingHours={},"
                        + " openPositionsAtCall={}",
                caller,
                force,
                duringTradingHours,
                openPositions);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "SUCCESS");
        response.put("message", "Daily strategy state successfully reset.");
        response.put("force", force);
        response.put("duringTradingHours", duringTradingHours);
        response.put("openPositionsFlattened", openPositions);
        return ResponseEntity.ok(response);
    }

    private static String currentCallerAddress() {
        try {
            var attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servletAttrs) {
                return servletAttrs.getRequest().getRemoteAddr();
            }
        } catch (Exception ignored) {
            // non-HTTP context (direct method invocation in tests)
        }
        return "unknown";
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
