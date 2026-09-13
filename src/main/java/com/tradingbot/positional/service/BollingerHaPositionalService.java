package com.tradingbot.positional.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.positional.config.PositionalStrategyConfig;
import com.tradingbot.positional.execution.PositionalExecutionService;
import com.tradingbot.positional.indicator.BollingerBandSnapshot;
import com.tradingbot.positional.indicator.BollingerHaIndicatorService;
import com.tradingbot.positional.model.PositionalAlert;
import com.tradingbot.positional.model.PositionalState;
import com.tradingbot.positional.model.PositionalStatus;
import com.tradingbot.positional.model.PositionalTrade;
import com.tradingbot.telegram.TelegramService;
import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Core engine for the Bollinger Band (20, 2) & Heikin-Ashi Positional Strategy. Implements ATM
 * Option Selling with 0.20 Delta OTM Protective Hedge (Bull Put Spread / Bear Call Spread). Runs
 * daily evaluation at 3:00 PM IST on Nifty 50 Spot.
 */
@Service
public class BollingerHaPositionalService {

    private static final Logger log = LoggerFactory.getLogger(BollingerHaPositionalService.class);

    private final BollingerHaIndicatorService indicatorService;
    private final ShoonyaMarketDataService marketDataService;
    private final PositionalExecutionService executionService;
    private final TelegramService telegramService;
    private final PositionalStrategyConfig config;
    private final ObjectMapper objectMapper;

    private PositionalState state;

    public BollingerHaPositionalService(
            BollingerHaIndicatorService indicatorService,
            ShoonyaMarketDataService marketDataService,
            PositionalExecutionService executionService,
            TelegramService telegramService,
            PositionalStrategyConfig config,
            ObjectMapper objectMapper) {
        this.indicatorService = indicatorService;
        this.marketDataService = marketDataService;
        this.executionService = executionService;
        this.telegramService = telegramService;
        this.config = config;
        this.objectMapper = objectMapper;
        this.state = new PositionalState();
    }

    @PostConstruct
    public synchronized void loadState() {
        File file = new File(config.getStateFilePath());
        if (file.exists()) {
            try {
                this.state = objectMapper.readValue(file, PositionalState.class);
                log.info(
                        "Loaded Positional Strategy state: status={}, activeAlert={}, activeTrade={}",
                        state.getStatus(),
                        state.getActiveAlert() != null
                                ? state.getActiveAlert().direction()
                                : "NONE",
                        state.getActiveTrade() != null ? state.getActiveTrade().tradeId() : "NONE");
            } catch (Exception e) {
                log.error(
                        "Failed to load positional state from {}, using fresh state",
                        config.getStateFilePath(),
                        e);
                this.state = new PositionalState();
            }
        } else {
            log.info(
                    "No existing state file found at {}. Initialized empty state.",
                    config.getStateFilePath());
            this.state = new PositionalState();
            persistState();
        }
    }

    public synchronized void persistState() {
        try {
            File file = new File(config.getStateFilePath());
            if (file.getParentFile() != null && !file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, state);
            log.debug("Persisted positional state to {}", config.getStateFilePath());
        } catch (IOException e) {
            log.error("Failed to persist positional state to {}", config.getStateFilePath(), e);
        }
    }

    public synchronized PositionalState getState() {
        return state;
    }

    /** Daily scan and evaluation routine executed at 3:00 PM IST or manually triggered. */
    public synchronized void scanAndEvaluate() {
        if (!config.isEnabled()) {
            log.info("Positional Strategy is disabled in configuration.");
            return;
        }

        try {
            log.info("Starting Daily Positional Strategy Scan for {}", config.getSymbol());
            List<Candle> dailyCandles = fetchDailyCandles(config.getSymbol(), 100);
            if (dailyCandles == null || dailyCandles.size() < 25) {
                log.warn(
                        "Insufficient daily candle data for {} (found: {}). Aborting scan.",
                        config.getSymbol(),
                        dailyCandles == null ? 0 : dailyCandles.size());
                return;
            }

            List<BollingerBandSnapshot> snapshots =
                    indicatorService.calculate(dailyCandles, 20, 2.0);
            BigDecimal currentSpot = dailyCandles.get(dailyCandles.size() - 1).close();

            evaluateSnapshots(snapshots, currentSpot);
            persistState();
        } catch (Exception e) {
            log.error("Error during scanAndEvaluate for positional strategy", e);
            telegramService.sendAlert("⚠️ Positional Strategy Scan Error: " + e.getMessage());
        }
    }

    /** Core evaluation logic on computed snapshots and current spot level. */
    public synchronized void evaluateSnapshots(
            List<BollingerBandSnapshot> snapshots, BigDecimal currentSpot) {
        if (snapshots == null || snapshots.size() < 2) {
            return;
        }

        BollingerBandSnapshot today = snapshots.get(snapshots.size() - 1);

        // 1. Manage Active Spread
        if (state.getStatus() == PositionalStatus.IN_BULL_PUT_SPREAD
                || state.getStatus() == PositionalStatus.IN_BEAR_CALL_SPREAD
                || state.getStatus() == PositionalStatus.IN_LONG_CE
                || state.getStatus() == PositionalStatus.IN_SHORT_PE) {
            manageActiveTrade(today, currentSpot);
            return;
        }

        // 2. Manage Staged Trade (waiting for manual approval)
        if (state.getStatus() == PositionalStatus.STAGED_FOR_APPROVAL) {
            log.info(
                    "Trade is currently STAGED_FOR_APPROVAL. Awaiting user action or cancellation.");
            return;
        }

        // 3. Manage Active Alert
        if (state.getStatus() == PositionalStatus.ALERT_PENDING && state.getActiveAlert() != null) {
            checkAlertTriggerOrInvalidation(state.getActiveAlert(), today, currentSpot);
            return;
        }

        // 4. If FLAT, look for New Alert Candle
        if (state.getStatus() == PositionalStatus.FLAT) {
            checkNewAlertCandle(snapshots);
        }
    }

    private void checkNewAlertCandle(List<BollingerBandSnapshot> snapshots) {
        int n = snapshots.size();
        BollingerBandSnapshot today = snapshots.get(n - 1);

        // Look back up to 5 bars for a Bollinger Band touch
        for (int i = n - 2; i >= Math.max(0, n - 6); i--) {
            BollingerBandSnapshot prior = snapshots.get(i);
            if (prior.bbUpper() == null || prior.bbLower() == null) continue;

            boolean touchedUpper = prior.haHigh().compareTo(prior.bbUpper()) >= 0;
            boolean touchedLower = prior.haLow().compareTo(prior.bbLower()) <= 0;

            if (touchedUpper) {
                // Today closed inside without touching upper band -> SELL Alert (Bear Call Spread)
                if (today.haHigh().compareTo(today.bbUpper()) < 0) {
                    PositionalAlert alert =
                            new PositionalAlert(
                                    LocalDate.now(),
                                    "SELL",
                                    today.normalHigh(),
                                    today.normalLow(),
                                    today.bbUpper(),
                                    Instant.now());
                    state.setStatus(PositionalStatus.ALERT_PENDING);
                    state.setActiveAlert(alert);

                    BigDecimal plannedAtm = roundToNearestStrike(today.normalLow(), 50);
                    BigDecimal plannedHedge =
                            plannedAtm.add(BigDecimal.valueOf(config.getHedgeOffsetPoints()));

                    log.info(
                            "🚨 New SELL Alert Candle detected on {}. H_alert={}, L_alert={}",
                            alert.alertDate(),
                            alert.highPrice(),
                            alert.lowPrice());
                    telegramService.sendAlert(
                            String.format(
                                    "🚨 *Positional SELL Alert Detected (%s)*\n\n"
                                            + "• Normal Alert High (SL): %.2f\n"
                                            + "• Normal Alert Low (Entry Trigger): %.2f\n"
                                            + "• Target (Lower BB): %.2f\n\n"
                                            + "📦 *Planned Structure (BEAR CALL SPREAD):*\n"
                                            + "• SELL Leg (ATM): %.0f CE\n"
                                            + "• BUY Hedge (0.2 Delta): %.0f CE\n"
                                            + "• Status: Waiting for Spot <= %.2f",
                                    config.getSymbol(),
                                    alert.highPrice(),
                                    alert.lowPrice(),
                                    today.bbLower(),
                                    plannedAtm,
                                    plannedHedge,
                                    alert.lowPrice()));
                    return;
                }
            } else if (touchedLower) {
                // Today closed inside without touching lower band -> BUY Alert (Bull Put Spread)
                if (today.haLow().compareTo(today.bbLower()) > 0) {
                    PositionalAlert alert =
                            new PositionalAlert(
                                    LocalDate.now(),
                                    "BUY",
                                    today.normalHigh(),
                                    today.normalLow(),
                                    today.bbLower(),
                                    Instant.now());
                    state.setStatus(PositionalStatus.ALERT_PENDING);
                    state.setActiveAlert(alert);

                    BigDecimal plannedAtm = roundToNearestStrike(today.normalHigh(), 50);
                    BigDecimal plannedHedge =
                            plannedAtm.subtract(BigDecimal.valueOf(config.getHedgeOffsetPoints()));

                    log.info(
                            "🚨 New BUY Alert Candle detected on {}. H_alert={}, L_alert={}",
                            alert.alertDate(),
                            alert.highPrice(),
                            alert.lowPrice());
                    telegramService.sendAlert(
                            String.format(
                                    "🚨 *Positional BUY Alert Detected (%s)*\n\n"
                                            + "• Normal Alert High (Entry Trigger): %.2f\n"
                                            + "• Normal Alert Low (SL): %.2f\n"
                                            + "• Target (Upper BB): %.2f\n\n"
                                            + "📦 *Planned Structure (BULL PUT SPREAD):*\n"
                                            + "• SELL Leg (ATM): %.0f PE\n"
                                            + "• BUY Hedge (0.2 Delta): %.0f PE\n"
                                            + "• Status: Waiting for Spot >= %.2f",
                                    config.getSymbol(),
                                    alert.highPrice(),
                                    alert.lowPrice(),
                                    today.bbUpper(),
                                    plannedAtm,
                                    plannedHedge,
                                    alert.highPrice()));
                    return;
                }
            }
        }
    }

    private void checkAlertTriggerOrInvalidation(
            PositionalAlert alert, BollingerBandSnapshot today, BigDecimal currentSpot) {
        if ("BUY".equalsIgnoreCase(alert.direction())) {
            // Invalidation: Spot drops below Alert Low
            if (currentSpot.compareTo(alert.lowPrice()) < 0) {
                log.info(
                        "Positional BUY Alert invalidated. Spot {} < Low {}",
                        currentSpot,
                        alert.lowPrice());
                telegramService.sendAlert(
                        String.format(
                                "❌ BUY Alert invalidated (Spot %.2f < Low %.2f). State reset to FLAT.",
                                currentSpot, alert.lowPrice()));
                state.setStatus(PositionalStatus.FLAT);
                state.setActiveAlert(null);
                return;
            }

            // Entry Trigger: Spot crosses above Alert High
            if (currentSpot.compareTo(alert.highPrice()) >= 0) {
                log.info(
                        "Positional BUY Alert TRIGGERED! Spot {} >= High {}",
                        currentSpot,
                        alert.highPrice());
                stageOrExecuteSpread(
                        "BULL_PUT_SPREAD", "PE", currentSpot, alert.lowPrice(), today.bbUpper());
            }
        } else if ("SELL".equalsIgnoreCase(alert.direction())) {
            // Invalidation: Spot breaks above Alert High
            if (currentSpot.compareTo(alert.highPrice()) > 0) {
                log.info(
                        "Positional SELL Alert invalidated. Spot {} > High {}",
                        currentSpot,
                        alert.highPrice());
                telegramService.sendAlert(
                        String.format(
                                "❌ SELL Alert invalidated (Spot %.2f > High %.2f). State reset to FLAT.",
                                currentSpot, alert.highPrice()));
                state.setStatus(PositionalStatus.FLAT);
                state.setActiveAlert(null);
                return;
            }

            // Entry Trigger: Spot crosses below Alert Low
            if (currentSpot.compareTo(alert.lowPrice()) <= 0) {
                log.info(
                        "Positional SELL Alert TRIGGERED! Spot {} <= Low {}",
                        currentSpot,
                        alert.lowPrice());
                stageOrExecuteSpread(
                        "BEAR_CALL_SPREAD", "CE", currentSpot, alert.highPrice(), today.bbLower());
            }
        }
    }

    private void stageOrExecuteSpread(
            String strategyType,
            String optionType,
            BigDecimal spotPrice,
            BigDecimal slSpot,
            BigDecimal targetSpot) {
        BigDecimal atmStrike = roundToNearestStrike(spotPrice, 50);
        BigDecimal hedgeStrike =
                "BULL_PUT_SPREAD".equalsIgnoreCase(strategyType)
                        ? atmStrike.subtract(BigDecimal.valueOf(config.getHedgeOffsetPoints()))
                        : atmStrike.add(BigDecimal.valueOf(config.getHedgeOffsetPoints()));

        String tradeId = "POS_" + System.currentTimeMillis();
        ExecutionMode mode =
                ExecutionMode.valueOf(
                        config.getExecutionMode().toUpperCase().contains("AUTO")
                                ? "PAPER"
                                : "PAPER");

        int totalQty = config.getTotalQuantity();

        PositionalTrade stagedTrade =
                new PositionalTrade(
                        tradeId,
                        config.getSymbol(),
                        strategyType,
                        optionType,
                        atmStrike,
                        optionType,
                        hedgeStrike,
                        "MONTHLY",
                        LocalDate.now(),
                        spotPrice,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        slSpot,
                        targetSpot,
                        config.getNumLots(),
                        totalQty,
                        mode,
                        "STAGED",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        state.setActiveTrade(stagedTrade);

        if (config.isAutoExecution()) {
            log.info("Auto execution enabled. Executing spread immediately...");
            executeTrade(stagedTrade);
        } else {
            state.setStatus(PositionalStatus.STAGED_FOR_APPROVAL);
            log.info("Manual confirmation mode. Spread staged for approval: {}", stagedTrade);
            sendStagedApprovalAlert(stagedTrade);
        }
    }

    private void sendStagedApprovalAlert(PositionalTrade trade) {
        BigDecimal estAtm =
                trade.entrySpot()
                        .multiply(BigDecimal.valueOf(0.015))
                        .setScale(2, RoundingMode.HALF_UP);
        BigDecimal estHedge =
                trade.entrySpot()
                        .multiply(BigDecimal.valueOf(0.0038))
                        .setScale(2, RoundingMode.HALF_UP);
        BigDecimal estNetCredit = estAtm.subtract(estHedge);
        BigDecimal estTotalCredit =
                estNetCredit
                        .multiply(BigDecimal.valueOf(trade.quantity()))
                        .setScale(2, RoundingMode.HALF_UP);

        String msg =
                String.format(
                        "🎯 *Positional Trade Triggered: %s*\n\n"
                                + "🟢 *SELL Leg (ATM):* %d Lots %s %.0f %s (Est: ₹%.2f)\n"
                                + "🛡️ *BUY Hedge Leg (0.2 Delta):* %d Lots %s %.0f %s (Est: ₹%.2f)\n\n"
                                + "💰 *Estimated Net Credit:* ~₹%.2f / unit (₹%.2f total)\n"
                                + "📊 *Total Quantity:* %d Qty (%d Lots)\n"
                                + "🛑 *Spot SL:* %.2f (Risk: %.2f pts)\n"
                                + "🎯 *Target (Opposite BB):* %.2f\n\n"
                                + "Click below or send `/approve` to confirm entry:",
                        trade.strategyType(),
                        trade.numLots(),
                        trade.underlying(),
                        trade.sellStrike(),
                        trade.sellOptionType(),
                        estAtm,
                        trade.numLots(),
                        trade.underlying(),
                        trade.buyHedgeStrike(),
                        trade.buyHedgeOptionType(),
                        estHedge,
                        estNetCredit,
                        estTotalCredit,
                        trade.quantity(),
                        trade.numLots(),
                        trade.slSpot(),
                        trade.entrySpot().subtract(trade.slSpot()).abs(),
                        trade.targetSpot());

        telegramService.sendInteractiveMessage(
                msg, "✅ Approve Spread", "pos_approve", "❌ Reject Trade", "pos_reject");
    }

    public synchronized boolean approveStagedTrade() {
        if (state.getStatus() != PositionalStatus.STAGED_FOR_APPROVAL
                || state.getActiveTrade() == null) {
            log.warn("Cannot approve trade: No trade staged for approval.");
            return false;
        }

        executeTrade(state.getActiveTrade());
        persistState();
        return true;
    }

    public synchronized boolean rejectStagedTrade() {
        if (state.getStatus() != PositionalStatus.STAGED_FOR_APPROVAL
                || state.getActiveTrade() == null) {
            log.warn("Cannot reject trade: No trade staged for approval.");
            return false;
        }

        log.info("Rejecting staged trade: {}", state.getActiveTrade().tradeId());
        state.setActiveTrade(null);
        state.setStatus(PositionalStatus.FLAT);
        persistState();
        telegramService.sendAlert("🛑 Staged positional spread was rejected. State reset to FLAT.");
        return true;
    }

    private void executeTrade(PositionalTrade trade) {
        PositionalTrade filledTrade = executionService.executeEntry(trade);
        state.setActiveTrade(filledTrade);
        state.setStatus(
                "BULL_PUT_SPREAD".equalsIgnoreCase(filledTrade.strategyType())
                        ? PositionalStatus.IN_BULL_PUT_SPREAD
                        : PositionalStatus.IN_BEAR_CALL_SPREAD);
        state.setActiveAlert(null);
        persistState();

        BigDecimal totalCreditCollected =
                filledTrade
                        .netCredit()
                        .multiply(BigDecimal.valueOf(filledTrade.quantity()))
                        .setScale(2, RoundingMode.HALF_UP);

        String alertMsg =
                String.format(
                        "🚀 *Positional Spread Executed: %s*\n\n"
                                + "🟢 *SOLD (ATM):* %d Lots %s %.0f %s @ ₹%.2f\n"
                                + "🛡️ *BOUGHT (0.2 Delta):* %d Lots %s %.0f %s @ ₹%.2f\n\n"
                                + "💰 *Net Credit Collected:* ₹%.2f / unit (₹%.2f total)\n"
                                + "📈 *Spot Entry:* %.2f\n"
                                + "🛑 *Spot SL:* %.2f\n"
                                + "🎯 *Target (Opposite BB):* %.2f\n"
                                + "⚙️ *Mode:* %s",
                        filledTrade.strategyType(),
                        filledTrade.numLots(),
                        filledTrade.underlying(),
                        filledTrade.sellStrike(),
                        filledTrade.sellOptionType(),
                        filledTrade.sellEntryPremium(),
                        filledTrade.numLots(),
                        filledTrade.underlying(),
                        filledTrade.buyHedgeStrike(),
                        filledTrade.buyHedgeOptionType(),
                        filledTrade.buyHedgeEntryPremium(),
                        filledTrade.netCredit(),
                        totalCreditCollected,
                        filledTrade.entrySpot(),
                        filledTrade.slSpot(),
                        filledTrade.targetSpot(),
                        filledTrade.mode());

        telegramService.sendAlert(alertMsg);
    }

    private void manageActiveTrade(BollingerBandSnapshot today, BigDecimal currentSpot) {
        PositionalTrade trade = state.getActiveTrade();
        if (trade == null) return;

        boolean isBullPut = "BULL_PUT_SPREAD".equalsIgnoreCase(trade.strategyType());

        // 1. Check Stop Loss Hit
        boolean slHit =
                isBullPut
                        ? currentSpot.compareTo(trade.slSpot()) <= 0
                        : currentSpot.compareTo(trade.slSpot()) >= 0;
        if (slHit) {
            log.info(
                    "SL Hit for active spread {}. Spot={}, SL={}",
                    trade.tradeId(),
                    currentSpot,
                    trade.slSpot());
            exitActiveTrade(currentSpot, "STOP_LOSS");
            return;
        }

        // 2. Check Opposite Bollinger Band Target Hit
        BigDecimal targetBand = isBullPut ? today.bbUpper() : today.bbLower();
        boolean targetHit = false;
        if (targetBand != null) {
            targetHit =
                    isBullPut
                            ? currentSpot.compareTo(targetBand) >= 0
                            : currentSpot.compareTo(targetBand) <= 0;
        }

        if (targetHit) {
            log.info(
                    "Opposite BB Target Hit for active spread {}. Spot={}, Target={}",
                    trade.tradeId(),
                    currentSpot,
                    targetBand);
            exitActiveTrade(currentSpot, "TARGET_OPPOSITE_BAND");
            return;
        }

        // Log daily status
        log.info(
                "Active positional spread {} holding. Spot={}, SL={}, Target={}",
                trade.strategyType(),
                currentSpot,
                trade.slSpot(),
                trade.targetSpot());
    }

    public synchronized void forceExitCurrentPosition(String exitReason) {
        if (state.getActiveTrade() == null) {
            log.warn("No active positional spread to exit.");
            return;
        }
        BigDecimal currentSpot = state.getActiveTrade().entrySpot();
        try {
            List<Candle> recent = fetchDailyCandles(config.getSymbol(), 5);
            if (recent != null && !recent.isEmpty()) {
                currentSpot = recent.get(recent.size() - 1).close();
            }
        } catch (Exception ignored) {
        }

        exitActiveTrade(currentSpot, exitReason != null ? exitReason : "MANUAL_EXIT");
        persistState();
    }

    private void exitActiveTrade(BigDecimal exitSpot, String reason) {
        PositionalTrade trade = state.getActiveTrade();
        if (trade == null) return;

        PositionalTrade completedTrade = executionService.executeExit(trade, exitSpot, reason);
        state.addHistoricalTrade(completedTrade);
        state.setActiveTrade(null);
        state.setStatus(PositionalStatus.FLAT);
        persistState();

        String exitMsg =
                String.format(
                        "🏁 *Positional Spread Closed: %s*\n\n"
                                + "• *Exit Reason:* %s\n"
                                + "• *Exit Spot:* %.2f\n"
                                + "• *Covered SELL Leg (%.0f %s):* ₹%.2f\n"
                                + "• *Closed BUY Hedge (%.0f %s):* ₹%.2f\n"
                                + "• *Net Realized PnL:* ₹%.2f\n"
                                + "• *Strategy Status:* FLAT",
                        completedTrade.strategyType(),
                        completedTrade.exitReason(),
                        completedTrade.exitSpot(),
                        completedTrade.sellStrike(),
                        completedTrade.sellOptionType(),
                        completedTrade.sellExitPremium(),
                        completedTrade.buyHedgeStrike(),
                        completedTrade.buyHedgeOptionType(),
                        completedTrade.buyHedgeExitPremium(),
                        completedTrade.pnl());

        telegramService.sendAlert(exitMsg);
    }

    public synchronized String getSummaryStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 *Positional Strategy Status*\n");
        sb.append("• Symbol: ").append(config.getSymbol()).append("\n");
        sb.append("• Mode: ").append(config.getExecutionMode()).append("\n");
        sb.append("• Position Sizing: ")
                .append(config.getNumLots())
                .append(" Lots (")
                .append(config.getTotalQuantity())
                .append(" Qty)\n");
        sb.append("• Current State: *").append(state.getStatus()).append("*\n");

        if (state.getActiveAlert() != null) {
            PositionalAlert alert = state.getActiveAlert();
            sb.append("\n🚨 *Active Alert Candle:*\n");
            sb.append("• Direction: ").append(alert.direction()).append("\n");
            sb.append("• Date: ").append(alert.alertDate()).append("\n");
            sb.append("• Normal High: ").append(alert.highPrice()).append("\n");
            sb.append("• Normal Low: ").append(alert.lowPrice()).append("\n");
        }

        if (state.getActiveTrade() != null) {
            PositionalTrade trade = state.getActiveTrade();
            sb.append("\n📈 *Active / Staged Spread:*\n");
            sb.append("• Strategy: ").append(trade.strategyType()).append("\n");
            sb.append("• SELL ATM Leg: ")
                    .append(trade.sellStrike())
                    .append(" ")
                    .append(trade.sellOptionType())
                    .append("\n");
            sb.append("• BUY Hedge Leg: ")
                    .append(trade.buyHedgeStrike())
                    .append(" ")
                    .append(trade.buyHedgeOptionType())
                    .append("\n");
            sb.append("• Net Entry Credit: ₹").append(trade.netCredit()).append(" / unit\n");
            sb.append("• Entry Spot: ").append(trade.entrySpot()).append("\n");
            sb.append("• Spot SL: ").append(trade.slSpot()).append("\n");
            sb.append("• Target BB: ").append(trade.targetSpot()).append("\n");
            sb.append("• Status: ").append(trade.status()).append("\n");
        }

        sb.append("\n• Total Completed Trades: ").append(state.getHistoricalTrades().size());
        return sb.toString();
    }

    private BigDecimal roundToNearestStrike(BigDecimal spot, int interval) {
        double val = spot.doubleValue();
        double rounded = Math.round(val / interval) * interval;
        return BigDecimal.valueOf(rounded).setScale(2, RoundingMode.HALF_UP);
    }

    private List<Candle> fetchDailyCandles(String symbol, int count) {
        if (marketDataService != null) {
            try {
                return marketDataService.fetchDailyCandles(symbol, count);
            } catch (Exception e) {
                log.warn(
                        "ShoonyaMarketDataService fetchDailyCandles threw exception, falling back.",
                        e);
            }
        }
        return List.of();
    }
}
