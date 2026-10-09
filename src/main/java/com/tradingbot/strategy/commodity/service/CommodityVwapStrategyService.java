package com.tradingbot.strategy.commodity.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.strategy.commodity.config.CommodityVwapProperties;
import com.tradingbot.strategy.commodity.model.CommodityBias;
import com.tradingbot.strategy.commodity.model.CommoditySetup;
import com.tradingbot.strategy.commodity.model.CommoditySetupState;
import com.tradingbot.strategy.commodity.model.CommodityStatusReport;
import com.tradingbot.strategy.commodity.model.CommodityTradePosition;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.CommodityRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Service orchestrating the 1:30 PM MCX Commodity Directional PCR & 15m VWAP Breakout Strategy. */
@Service
public class CommodityVwapStrategyService {

    private static final Logger log = LoggerFactory.getLogger(CommodityVwapStrategyService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final CommodityVwapProperties properties;
    private final ShoonyaOptionChainService optionChainService;
    private final ShoonyaMarketDataService marketDataService;
    private final TechnicalAnalysisService taService;
    private final TelegramService telegramService;

    private final Map<String, CommoditySetup> setups = new ConcurrentHashMap<>();
    private final List<CommodityTradePosition> closedTrades =
            Collections.synchronizedList(new ArrayList<>());

    @Autowired
    public CommodityVwapStrategyService(
            CommodityVwapProperties properties,
            ShoonyaOptionChainService optionChainService,
            ShoonyaMarketDataService marketDataService,
            TechnicalAnalysisService taService,
            @Autowired(required = false) TelegramService telegramService) {
        this.properties = properties;
        this.optionChainService = optionChainService;
        this.marketDataService = marketDataService;
        this.taService = taService;
        this.telegramService = telegramService;
        initializeSetups();
    }

    private void initializeSetups() {
        if (properties.getSymbols() != null) {
            for (String sym : properties.getSymbols()) {
                String clean = sym.trim().toUpperCase();
                setups.put(clean, new CommoditySetup(clean));
            }
        }
    }

    public CommoditySetup getSetup(String symbol) {
        if (symbol == null) return null;
        return setups.computeIfAbsent(symbol.trim().toUpperCase(), CommoditySetup::new);
    }

    /** Triggered at 1:30 PM IST to determine macro directional bias from Put-Call Ratio (PCR). */
    public void evaluateDailyBias() {
        if (!properties.isEnabled()) {
            log.info("[COMMODITY-VWAP] Strategy disabled. Skipping 1:30 PM bias evaluation.");
            return;
        }

        log.info(
                "[COMMODITY-VWAP] Evaluating 1:30 PM daily directional bias across configured commodities...");
        StringBuilder telegramMsg =
                new StringBuilder("📊 *[MCX Commodity Directional Bias - 1:30 PM IST]*\n");
        telegramMsg.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        for (String symbol : properties.getSymbols()) {
            String clean = symbol.trim().toUpperCase();
            CommoditySetup setup = getSetup(clean);

            try {
                ShoonyaMarketDataService.FuturesContract fut =
                        marketDataService != null
                                ? marketDataService.resolveFuturesContract(clean)
                                : null;
                String futTsym = fut != null ? fut.tsym() : clean;
                String futToken = fut != null ? fut.token() : "";

                OptionChainResponse chain =
                        optionChainService != null
                                ? optionChainService.getOptionChain(
                                        clean, futTsym, futToken, null, 5, true)
                                : null;

                if (chain != null && (chain.totalCallOi() > 0 || chain.totalPutOi() > 0)) {
                    double pcr = chain.pcr();
                    CommodityBias bias =
                            CommodityBias.fromPcr(
                                    pcr,
                                    properties.getPcrBullishMin(),
                                    properties.getPcrBearishMax());
                    setup.updateBias(bias, pcr);

                    String icon =
                            bias == CommodityBias.BULLISH
                                    ? "🟢"
                                    : (bias == CommodityBias.BEARISH ? "🔴" : "⚪");
                    telegramMsg.append(
                            String.format("%s *%s*: PCR `%.2f` ➔ *%s*\n", icon, clean, pcr, bias));
                    log.info("[COMMODITY-VWAP] {} PCR: {} -> Bias: {}", clean, pcr, bias);
                } else {
                    setup.updateBias(CommodityBias.NEUTRAL, 0.0);
                    telegramMsg.append(
                            String.format("⚪ *%s*: Option chain unavailable ➔ *NEUTRAL*\n", clean));
                    log.warn(
                            "[COMMODITY-VWAP] Option chain unavailable for {}. Defaulted to NEUTRAL.",
                            clean);
                }
            } catch (Exception e) {
                setup.updateBias(CommodityBias.NEUTRAL, 0.0);
                telegramMsg.append(
                        String.format("⚠️ *%s*: Error reading PCR ➔ *NEUTRAL*\n", clean));
                log.error(
                        "[COMMODITY-VWAP] Error evaluating bias for {}: {}",
                        clean,
                        e.getMessage(),
                        e);
            }
        }

        telegramMsg.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        telegramMsg
                .append("15-min VWAP crossover scanner active until ")
                .append(properties.getEntryCutoff())
                .append(" IST.");

        if (properties.isTelegramAlertsEnabled() && telegramService != null) {
            telegramService.sendTextMessage(telegramMsg.toString());
        }
    }

    /**
     * Executes the strategy cycle for all configured symbols (typically run on 15m candle close).
     */
    public void evaluateStrategyCycle() {
        if (!properties.isEnabled()) return;
        LocalTime nowTime = LocalTime.now(IST);
        for (String symbol : properties.getSymbols()) {
            evaluateSymbolCycle(symbol.trim().toUpperCase(), nowTime);
        }
    }

    /**
     * Evaluates a single symbol for crossover arming, breakout entry, or active position
     * management.
     */
    public void evaluateSymbolCycle(String symbol, LocalTime nowTime) {
        CommoditySetup setup = getSetup(symbol);
        if (setup == null || setup.getState() == CommoditySetupState.SKIPPED) {
            return;
        }

        // 1. Manage active in-trade position
        if (setup.getState() == CommoditySetupState.IN_TRADE) {
            manageActiveTrade(setup);
            return;
        }

        // 2. Check if already completed max trades for the day
        if (setup.getTradesToday() >= properties.getMaxTradesPerSymbol()
                || setup.getState() == CommoditySetupState.COMPLETED) {
            return;
        }

        // 3. Breakout Execution check if ARMED
        if (setup.getState() == CommoditySetupState.ARMED_LONG
                || setup.getState() == CommoditySetupState.ARMED_SHORT) {
            checkBreakoutExecution(setup);
            if (setup.getState() == CommoditySetupState.IN_TRADE) {
                return;
            }
        }

        // 4. VWAP Crossover Check (only if before cutoff time and not in-trade)
        if (nowTime.isBefore(properties.getEntryCutoff())) {
            checkVwapCrossover(setup);
        }
    }

    private void checkVwapCrossover(CommoditySetup setup) {
        if (setup.getBias() == CommodityBias.NEUTRAL) return;

        try {
            List<Candle> candles =
                    marketDataService != null
                            ? marketDataService.fetch15MinCandles(setup.getSymbol(), 2)
                            : null;
            if (candles == null || candles.size() < 2) {
                log.debug("[COMMODITY-VWAP] Insufficient 15m candles for {}", setup.getSymbol());
                return;
            }

            double[] vwapSeries = taService != null ? taService.calculateVwapSeries(candles) : null;
            if (vwapSeries == null || vwapSeries.length < 2) return;

            int lastIdx = candles.size() - 1;
            Candle prevCandle = candles.get(lastIdx - 1);
            Candle currCandle = candles.get(lastIdx);

            double prevVwap = vwapSeries[lastIdx - 1];
            double currVwap = vwapSeries[lastIdx];

            if (Double.isNaN(prevVwap) || Double.isNaN(currVwap)) return;

            // Bullish Crossover: Prev close <= Prev VWAP, Curr close > Curr VWAP
            if (setup.getBias() == CommodityBias.BULLISH) {
                if (prevCandle.close().doubleValue() <= prevVwap
                        && currCandle.close().doubleValue() > currVwap) {
                    setup.armLong(currCandle.high(), BigDecimal.valueOf(currVwap), Instant.now());
                    log.info(
                            "[COMMODITY-VWAP] {} ARMED LONG: Trigger High={}, VWAP={}",
                            setup.getSymbol(),
                            currCandle.high(),
                            currVwap);
                    sendTelegramArmedAlert(
                            setup, currCandle.high(), BigDecimal.valueOf(currVwap), "LONG");
                }
            }
            // Bearish Crossover: Prev close >= Prev VWAP, Curr close < Curr VWAP
            else if (setup.getBias() == CommodityBias.BEARISH) {
                if (prevCandle.close().doubleValue() >= prevVwap
                        && currCandle.close().doubleValue() < currVwap) {
                    setup.armShort(currCandle.low(), BigDecimal.valueOf(currVwap), Instant.now());
                    log.info(
                            "[COMMODITY-VWAP] {} ARMED SHORT: Trigger Low={}, VWAP={}",
                            setup.getSymbol(),
                            currCandle.low(),
                            currVwap);
                    sendTelegramArmedAlert(
                            setup, currCandle.low(), BigDecimal.valueOf(currVwap), "SHORT");
                }
            }
        } catch (Exception e) {
            log.error(
                    "[COMMODITY-VWAP] Error checking VWAP crossover for {}: {}",
                    setup.getSymbol(),
                    e.getMessage(),
                    e);
        }
    }

    private void checkBreakoutExecution(CommoditySetup setup) {
        BigDecimal liveLtp = fetchLiveLtp(setup.getSymbol());
        if (liveLtp == null || liveLtp.compareTo(BigDecimal.ZERO) <= 0) return;

        String miniSymbol = CommodityRegistry.toMiniSymbol(setup.getSymbol());
        CommodityRegistry.CommodityMetadata meta = CommodityRegistry.getMetadata(miniSymbol);
        int lotSize = meta != null ? meta.lotSize() : 1;
        BigDecimal rr = BigDecimal.valueOf(properties.getRiskRewardRatio());

        if (setup.getState() == CommoditySetupState.ARMED_LONG && setup.getTriggerHigh() != null) {
            if (liveLtp.compareTo(setup.getTriggerHigh()) >= 0) {
                BigDecimal entryPrice = setup.getTriggerHigh();
                BigDecimal stopLoss =
                        setup.getVwapAtSetup() != null
                                ? setup.getVwapAtSetup()
                                : liveLtp.subtract(BigDecimal.ONE);
                CommodityTradePosition pos =
                        CommodityTradePosition.createLong(
                                miniSymbol, entryPrice, stopLoss, rr, lotSize, Instant.now());
                setup.enterTrade(pos);

                log.info(
                        "[COMMODITY-VWAP] {} ENTERED LONG @ {}: SL={}, Target={}",
                        miniSymbol,
                        entryPrice,
                        pos.stopLoss(),
                        pos.targetPrice());
                sendTelegramEntryAlert(pos);
            }
        } else if (setup.getState() == CommoditySetupState.ARMED_SHORT
                && setup.getTriggerLow() != null) {
            if (liveLtp.compareTo(setup.getTriggerLow()) <= 0) {
                BigDecimal entryPrice = setup.getTriggerLow();
                BigDecimal stopLoss =
                        setup.getVwapAtSetup() != null
                                ? setup.getVwapAtSetup()
                                : liveLtp.add(BigDecimal.ONE);
                CommodityTradePosition pos =
                        CommodityTradePosition.createShort(
                                miniSymbol, entryPrice, stopLoss, rr, lotSize, Instant.now());
                setup.enterTrade(pos);

                log.info(
                        "[COMMODITY-VWAP] {} ENTERED SHORT @ {}: SL={}, Target={}",
                        miniSymbol,
                        entryPrice,
                        pos.stopLoss(),
                        pos.targetPrice());
                sendTelegramEntryAlert(pos);
            }
        }
    }

    private void manageActiveTrade(CommoditySetup setup) {
        CommodityTradePosition pos = setup.getActivePosition();
        if (pos == null || pos.isClosed()) return;

        BigDecimal liveLtp = fetchLiveLtp(pos.symbol());
        if (liveLtp == null || liveLtp.compareTo(BigDecimal.ZERO) <= 0) return;

        double unitMultiplier = CommodityRegistry.getUnitMultiplier(pos.symbol());

        // Phase 1: Not yet partial booked -> Check Target 1 (1:2.5 RR) or Initial Stop Loss
        if (!pos.isPartialBooked()) {
            boolean hitTarget =
                    "LONG".equalsIgnoreCase(pos.side())
                            ? liveLtp.compareTo(pos.targetPrice()) >= 0
                            : liveLtp.compareTo(pos.targetPrice()) <= 0;

            boolean hitSl =
                    "LONG".equalsIgnoreCase(pos.side())
                            ? liveLtp.compareTo(pos.stopLoss()) <= 0
                            : liveLtp.compareTo(pos.stopLoss()) >= 0;

            if (hitTarget) {
                pos.executePartialBook(liveLtp, Instant.now(), unitMultiplier);
                log.info(
                        "[COMMODITY-VWAP] {} TARGET HIT @ {}: 50% booked (PnL=₹{}), SL moved to Cost (₹{})",
                        pos.symbol(), liveLtp, pos.partialPnl(), pos.entryPrice());
                sendTelegramPartialBookAlert(pos);

                if (pos.isClosed()) {
                    setup.completeTrade(pos);
                    closedTrades.add(pos);
                    sendTelegramExitAlert(pos);
                }
                return;
            } else if (hitSl) {
                closeTrade(setup, pos, liveLtp, "STOP_LOSS_HIT", unitMultiplier);
                return;
            }
        }

        // Phase 2: Partial booked -> Dynamic 10 EMA Trailing for Runner
        if (pos.isPartialBooked() && !pos.isClosed()) {
            updateRunner10EmaTrailing(pos);

            boolean hitDynamicSl =
                    "LONG".equalsIgnoreCase(pos.side())
                            ? liveLtp.compareTo(pos.currentStopLoss()) <= 0
                            : liveLtp.compareTo(pos.currentStopLoss()) >= 0;

            if (hitDynamicSl) {
                String reason =
                        pos.currentStopLoss().compareTo(pos.entryPrice()) == 0
                                ? "COST_BREAKEVEN_EXIT"
                                : "RUNNER_10EMA_TRAIL_EXIT";
                closeTrade(setup, pos, liveLtp, reason, unitMultiplier);
            }
        }
    }

    private void updateRunner10EmaTrailing(CommodityTradePosition pos) {
        try {
            List<Candle> candles =
                    marketDataService != null
                            ? marketDataService.fetch15MinCandles(pos.symbol(), 5)
                            : null;
            if (candles != null && candles.size() >= 10 && taService != null) {
                double[] closes =
                        candles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
                double[] ema10Series = taService.calculateEmaSeries(closes, 10);
                if (ema10Series.length > 0 && !Double.isNaN(ema10Series[ema10Series.length - 1])) {
                    double latestEma = ema10Series[ema10Series.length - 1];
                    pos.updateDynamicStopLoss(BigDecimal.valueOf(latestEma));
                }
            }
        } catch (Exception e) {
            log.debug(
                    "[COMMODITY-VWAP] Could not update 10 EMA trailing SL for {}: {}",
                    pos.symbol(),
                    e.getMessage());
        }
    }

    private void closeTrade(
            CommoditySetup setup,
            CommodityTradePosition pos,
            BigDecimal exitPrice,
            String reason,
            double unitMultiplier) {
        CommodityTradePosition closed = pos.close(exitPrice, Instant.now(), reason, unitMultiplier);
        setup.completeTrade(closed);
        closedTrades.add(closed);

        log.info(
                "[COMMODITY-VWAP] {} TRADE CLOSED: Reason={}, Entry={}, Exit={}, PnL={}",
                closed.symbol(),
                reason,
                closed.entryPrice(),
                closed.exitPrice(),
                closed.pnl());
        sendTelegramExitAlert(closed);
    }

    /** Squares off all active open positions at 23:15 IST EOD. */
    public void squareOffAllPositions(String reason) {
        log.info("[COMMODITY-VWAP] Squaring off all active positions with reason: {}", reason);
        for (CommoditySetup setup : setups.values()) {
            if (setup.getState() == CommoditySetupState.IN_TRADE
                    && setup.getActivePosition() != null) {
                CommodityTradePosition pos = setup.getActivePosition();
                BigDecimal ltp = fetchLiveLtp(pos.symbol());
                BigDecimal exitPrice =
                        (ltp != null && ltp.compareTo(BigDecimal.ZERO) > 0)
                                ? ltp
                                : pos.entryPrice();
                double unitMultiplier = CommodityRegistry.getUnitMultiplier(pos.symbol());
                closeTrade(
                        setup,
                        pos,
                        exitPrice,
                        reason != null ? reason : "EOD_SQUARE_OFF",
                        unitMultiplier);
            }
        }
    }

    private BigDecimal fetchLiveLtp(String symbol) {
        try {
            if (marketDataService == null) return null;
            String clean = symbol != null ? symbol.trim().toUpperCase() : "";
            String exchange = marketDataService.resolveExchange(clean);
            String token = marketDataService.resolveToken(clean);
            if (token == null || token.isBlank()) {
                String parent =
                        clean.endsWith("M") ? clean.substring(0, clean.length() - 1) : clean;
                token = marketDataService.resolveToken(parent);
            }
            if (token != null && !token.isBlank()) {
                JsonNode quote = marketDataService.fetchQuote(exchange, token);
                if (quote != null && quote.has("lp")) {
                    return new BigDecimal(quote.path("lp").asText());
                }
            }
        } catch (Exception e) {
            log.debug("[COMMODITY-VWAP] Error fetching LTP for {}: {}", symbol, e.getMessage());
        }
        return null;
    }

    public CommodityStatusReport getStatusReport() {
        Map<String, CommodityStatusReport.CommoditySetupSummary> summaries = new LinkedHashMap<>();
        int activeTradesCount = 0;

        for (Map.Entry<String, CommoditySetup> entry : setups.entrySet()) {
            CommoditySetup s = entry.getValue();
            if (s.getState() == CommoditySetupState.IN_TRADE) {
                activeTradesCount++;
            }
            summaries.put(
                    entry.getKey(),
                    new CommodityStatusReport.CommoditySetupSummary(
                            s.getSymbol(),
                            s.getBias(),
                            s.getPcr(),
                            s.getState(),
                            s.getTriggerHigh() != null ? s.getTriggerHigh().doubleValue() : null,
                            s.getTriggerLow() != null ? s.getTriggerLow().doubleValue() : null,
                            s.getVwapAtSetup() != null ? s.getVwapAtSetup().doubleValue() : null,
                            s.getTradesToday(),
                            s.getActivePosition()));
        }

        return new CommodityStatusReport(
                Instant.now(),
                properties.isEnabled(),
                setups.size(),
                activeTradesCount,
                summaries,
                List.copyOf(closedTrades));
    }

    public void resetSession(boolean force) {
        log.info("[COMMODITY-VWAP] Resetting strategy session (force={})", force);
        for (CommoditySetup setup : setups.values()) {
            setup.reset();
        }
        closedTrades.clear();
    }

    public String formatTelegramReport() {
        CommodityStatusReport report = getStatusReport();
        StringBuilder sb = new StringBuilder("⚡ *[MCX Commodity VWAP Strategy Status]* ⚡\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        for (CommodityStatusReport.CommoditySetupSummary summary : report.setups().values()) {
            String stateIcon =
                    switch (summary.state()) {
                        case IN_TRADE -> "🚀";
                        case ARMED_LONG -> "🎯 🟢";
                        case ARMED_SHORT -> "🎯 🔴";
                        case BIAS_IDENTIFIED -> "👀";
                        case COMPLETED -> "✅";
                        case SKIPPED -> "⚪";
                        default -> "⏳";
                    };
            sb.append(
                    String.format(
                            "%s *%s*: State=`%s` | Bias=`%s` | PCR=`%.2f`\n",
                            stateIcon,
                            summary.symbol(),
                            summary.state(),
                            summary.bias(),
                            summary.pcr() != null ? summary.pcr() : 0.0));
            if (summary.activePosition() != null) {
                CommodityTradePosition p = summary.activePosition();
                sb.append(
                        String.format(
                                "   └ %s @ ₹%.2f | SL: ₹%.2f | Tgt: ₹%.2f\n",
                                p.side(), p.entryPrice(), p.stopLoss(), p.targetPrice()));
            }
        }
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("Active Trades: *")
                .append(report.activeTradesCount())
                .append("* | Closed Today: *")
                .append(report.closedTrades().size())
                .append("*\n");
        return sb.toString();
    }

    private void sendTelegramArmedAlert(
            CommoditySetup setup, BigDecimal trigger, BigDecimal vwap, String side) {
        if (!properties.isTelegramAlertsEnabled() || telegramService == null) return;
        String emoji = "LONG".equals(side) ? "🟢" : "🔴";
        String msg =
                String.format(
                        "🔔 *[COMMODITY VWAP SETUP ARMED]* 🔔\n"
                                + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                                + "Instrument: *%s*\n"
                                + "Direction: *%s %s*\n"
                                + "Breakout Trigger: *₹%.2f*\n"
                                + "Intraday VWAP (SL Anchor): *₹%.2f*\n"
                                + "Target RR: *1:2*\n"
                                + "Awaiting breakout to execute trade.",
                        setup.getSymbol(), side, emoji, trigger, vwap);
        telegramService.sendTextMessage(msg);
    }

    private void sendTelegramEntryAlert(CommodityTradePosition pos) {
        if (!properties.isTelegramAlertsEnabled() || telegramService == null) return;
        String emoji = "LONG".equalsIgnoreCase(pos.side()) ? "🟢 🚀" : "🔴 🚀";
        String msg =
                String.format(
                        "⚡ *[COMMODITY TRADE ENTERED]* ⚡\n"
                                + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                                + "Contract: *%s*\n"
                                + "Side: *%s %s*\n"
                                + "Entry Price: *₹%.2f*\n"
                                + "Stop Loss: *₹%.2f*\n"
                                + "Target (1:2 RR): *₹%.2f*\n"
                                + "Risk: *₹%.2f* | Qty: *%d*",
                        pos.symbol(),
                        pos.side(),
                        emoji,
                        pos.entryPrice(),
                        pos.stopLoss(),
                        pos.targetPrice(),
                        pos.risk(),
                        pos.quantity());
        telegramService.sendTextMessage(msg);
    }

    private void sendTelegramPartialBookAlert(CommodityTradePosition pos) {
        if (!properties.isTelegramAlertsEnabled() || telegramService == null) return;
        String msg =
                String.format(
                        "💰 *[COMMODITY TARGET HIT — 50%% BOOKED]* 💰\n"
                                + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                                + "Contract: *%s* (%s)\n"
                                + "Target 1 (1:2.5 RR) Reached @ *₹%.2f*\n"
                                + "Booked P&L: *₹%.2f*\n"
                                + "SL Moved to Cost: *₹%.2f* (Risk-free)\n"
                                + "Remaining Runner: *%d lots* trailing with 10 EMA.",
                        pos.symbol(),
                        pos.side(),
                        pos.partialExitPrice(),
                        pos.partialPnl(),
                        pos.entryPrice(),
                        pos.remainingQuantity());
        telegramService.sendTextMessage(msg);
    }

    private void sendTelegramExitAlert(CommodityTradePosition pos) {
        if (!properties.isTelegramAlertsEnabled() || telegramService == null) return;
        String emoji = pos.pnl().compareTo(BigDecimal.ZERO) >= 0 ? "🎯 💰" : "🛑";
        String msg =
                String.format(
                        "🏁 *[COMMODITY TRADE EXIT]* 🏁\n"
                                + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                                + "Contract: *%s* (%s)\n"
                                + "Reason: *%s %s*\n"
                                + "Entry: *₹%.2f* ➔ Exit: *₹%.2f*\n"
                                + "Realized P&L: *₹%.2f*",
                        pos.symbol(),
                        pos.side(),
                        pos.exitReason(),
                        emoji,
                        pos.entryPrice(),
                        pos.exitPrice(),
                        pos.pnl());
        telegramService.sendTextMessage(msg);
    }
}
