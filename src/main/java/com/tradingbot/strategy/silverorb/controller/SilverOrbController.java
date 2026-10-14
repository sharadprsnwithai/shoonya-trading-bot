package com.tradingbot.strategy.silverorb.controller;

import com.tradingbot.strategy.silverorb.config.SilverOrbProperties;
import com.tradingbot.strategy.silverorb.model.SilverOrbDayState;
import com.tradingbot.strategy.silverorb.model.SilverOrbState;
import com.tradingbot.strategy.silverorb.model.SilverOrbTrade;
import com.tradingbot.strategy.silverorb.persistence.SilverOrbStateStore;
import com.tradingbot.strategy.silverorb.service.SilverOrbService;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Monitoring endpoints for the Silver ORB paper run: current state + cumulative ledger, and a
 * manual cycle trigger for spot checks (e.g. verifying telegram delivery outside the cron window).
 */
@RestController
@RequestMapping("/api/strategy/silver-orb")
public class SilverOrbController {

    private final SilverOrbService strategyService;
    private final SilverOrbProperties props;

    /** Wires the controller with the strategy service and its configuration. */
    @Autowired
    public SilverOrbController(SilverOrbService strategyService, SilverOrbProperties props) {
        this.strategyService = strategyService;
        this.props = props;
    }

    /** Returns the persisted run state: config summary, today's session, closed-trade ledger. */
    @GetMapping("/state")
    public ResponseEntity<Map<String, Object>> state() {
        SilverOrbState state = SilverOrbStateStore.load(Path.of(props.getStateFile()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", props.isEnabled());
        body.put("schedulerEnabled", props.isSchedulerEnabled());
        body.put("runUntil", props.getRunUntil());
        body.put(
                "contract",
                props.getExch() + ":" + props.getSymbol() + " (token " + props.getToken() + ")");
        body.put("sizing", props.getLots() + " lot(s) x " + (int) props.getKgPerLot() + " kg");
        body.put(
                "riskTarget",
                String.format(
                        "SL -%.0f%% / TGT +%.0f%% of margin @ %.0f%% margin",
                        props.getRiskPct(), props.getTargetPct(), props.getMarginPct()));
        if (state == null) {
            body.put("state", "no state yet (no cycle has run)");
            return ResponseEntity.ok(body);
        }
        body.put("totalPnlRs", state.totalPnlRs());
        body.put("totalR", state.totalR());
        body.put("trades", state.tradeCount());
        body.put("winRatePct", state.winRatePct());
        body.put("finalSummarySent", state.finalSummarySent);
        SilverOrbDayState day = state.currentDay;
        if (day != null) {
            Map<String, Object> today = new LinkedHashMap<>();
            today.put("date", day.date);
            today.put("orbHigh", day.orbHigh);
            today.put("orbLow", day.orbLow);
            today.put("orbBarCount", day.orbBarCount);
            today.put("skipReason", day.skipReason);
            today.put("bias", day.bias);
            today.put("entryClosed", day.entryClosed);
            today.put("dayComplete", day.dayComplete);
            today.put("lastProcessedBarOpen", day.lastProcessedBarOpen);
            SilverOrbTrade t = day.trade;
            if (t != null) {
                Map<String, Object> trade = new LinkedHashMap<>();
                trade.put("side", t.side);
                trade.put("entryTime", t.entryTime);
                trade.put("entryPrice", t.entryPrice);
                trade.put("stopPrice", t.stopPrice);
                trade.put("targetPrice", t.targetPrice);
                trade.put("open", t.isOpen());
                trade.put("exitReason", t.exitReason);
                trade.put("exitPrice", t.exitPrice);
                trade.put("rMultiple", t.rMultiple);
                trade.put("pnlRs", t.pnlRs);
                today.put("trade", trade);
            }
            body.put("today", today);
        }
        return ResponseEntity.ok(body);
    }

    /** Triggers one evaluation cycle synchronously (spot check / catch-up after downtime). */
    @PostMapping("/run")
    public ResponseEntity<Map<String, String>> runNow() {
        strategyService.runCycle();
        return ResponseEntity.ok(Map.of("status", "cycle executed"));
    }
}
