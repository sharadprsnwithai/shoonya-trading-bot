package com.tradingbot.strategy.ttcstraddle.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.bus.SignalPublisher;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import com.tradingbot.strategy.ttcstraddle.config.IntradayStraddleProperties;
import com.tradingbot.strategy.ttcstraddle.model.StraddleDailyState;
import com.tradingbot.strategy.ttcstraddle.model.StraddleLeg;
import com.tradingbot.strategy.ttcstraddle.model.StraddleSessionPhase;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.BotLocale;
import com.tradingbot.util.StockFnoRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Core Execution Engine for the 10:00 AM Intraday Straddle Strategy with 30% Stop-Loss. Execution:
 * - 09:45 AM: Buy 20-strikes OTM Call and Put hedges - 10:00 AM: Sell ATM Call and ATM Put with
 * independent 30% Stop-Loss - 10:00 AM - 15:00 PM: Monitor 30% SL breaches (if other leg rises 30%,
 * exit that as well) - 15:00 PM: Square off all remaining positions (surviving short leg + hedges)
 */
@Service
public class IntradayStraddleService {

    private static final Logger log = LoggerFactory.getLogger(IntradayStraddleService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public static final String STRATEGY_ID = "INTRADAY_STRADDLE";

    private final IntradayStraddleProperties properties;
    private final ShoonyaMarketDataService marketDataService;
    private final SignalPublisher signalPublisher;
    private final TelegramService telegramService;

    private Clock clock = Clock.system(IST);
    private volatile StraddleDailyState state;

    @Autowired
    public IntradayStraddleService(
            IntradayStraddleProperties properties,
            @Autowired(required = false) ShoonyaMarketDataService marketDataService,
            @Autowired(required = false) SignalPublisher signalPublisher,
            @Autowired(required = false) TelegramService telegramService) {
        this.properties = properties;
        this.marketDataService = marketDataService;
        this.signalPublisher = signalPublisher;
        this.telegramService = telegramService;
        resetDaily(LocalDate.now(clock));
    }

    public synchronized void resetDaily() {
        resetDaily(LocalDate.now(clock));
    }

    public synchronized void resetDaily(LocalDate date) {
        String activeInstrument = resolveActiveInstrument(date);
        this.state = new StraddleDailyState(date, activeInstrument);
        log.info(
                "[STRADDLE] Daily session reset for {}. Active instrument: {}",
                date,
                activeInstrument != null ? activeInstrument : "NONE (Rest/Off Day)");
    }

    public String resolveActiveInstrument(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.MONDAY || dow == DayOfWeek.TUESDAY) {
            return "NIFTY";
        } else if (dow == DayOfWeek.WEDNESDAY || dow == DayOfWeek.THURSDAY) {
            return "SENSEX";
        }
        return null;
    }

    /** 09:45 AM: Buys 20 strikes OTM Call and Put protection wings. */
    public synchronized void executeHedgeWingEntry(LocalTime now) {
        if (!isEligibleToTrade()) {
            return;
        }
        if (state.getPhase() != StraddleSessionPhase.IDLE) {
            return;
        }

        String instrument = state.getInstrument();
        BigDecimal spot = fetchSpotPrice(instrument);
        if (spot.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn(
                    "[STRADDLE] Unable to resolve spot price for {} at 09:45. Skipping hedge entry.",
                    instrument);
            return;
        }

        BigDecimal strikeStep = resolveStrikeStep(instrument, spot);
        BigDecimal atm = snapToStep(spot, strikeStep);

        // 20 strikes offset: 1,000 pts on NIFTY (20*50), 2,000 pts on SENSEX (20*100)
        BigDecimal hedgeOffset = strikeStep.multiply(BigDecimal.valueOf(20));

        BigDecimal longCeStrike = atm.add(hedgeOffset);
        BigDecimal longPeStrike = atm.subtract(hedgeOffset);

        int qty = calculateQuantity(instrument);
        LocalDate expiry = resolveTargetExpiry(instrument, state.getTradeDate());

        String ceTradingSymbol =
                StockFnoRegistry.formatTradingSymbol(instrument, expiry, longCeStrike, "CE", true);
        String peTradingSymbol =
                StockFnoRegistry.formatTradingSymbol(instrument, expiry, longPeStrike, "PE", true);

        BigDecimal cePrice = fetchOptionLtp(instrument, ceTradingSymbol, BigDecimal.valueOf(2.0));
        BigDecimal pePrice = fetchOptionLtp(instrument, peTradingSymbol, BigDecimal.valueOf(2.0));

        // Publish BUY signals for hedges
        publishSignal(
                instrument,
                ceTradingSymbol,
                SignalAction.ENTRY_LONG,
                cePrice,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                qty,
                "09:45 20-OTM Hedge Buy");
        publishSignal(
                instrument,
                peTradingSymbol,
                SignalAction.ENTRY_LONG,
                pePrice,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                qty,
                "09:45 20-OTM Hedge Buy");

        StraddleLeg ceLeg =
                new StraddleLeg(
                        ceTradingSymbol, "CE", longCeStrike, cePrice, BigDecimal.ZERO, qty, true);
        StraddleLeg peLeg =
                new StraddleLeg(
                        peTradingSymbol, "PE", longPeStrike, pePrice, BigDecimal.ZERO, qty, true);

        state.setHedgeLongCe(ceLeg);
        state.setHedgeLongPe(peLeg);
        state.setPhase(StraddleSessionPhase.HEDGES_BOUGHT);

        log.info(
                "[STRADDLE] 20-OTM Hedges bought for {}: Long CE {} @ {}, Long PE {} @ {}",
                instrument,
                ceTradingSymbol,
                cePrice,
                peTradingSymbol,
                pePrice);

        sendTelegramAlert(
                String.format(
                        BotLocale.INSTANCE,
                        "🛡️ *Straddle 20-OTM Hedges Bought*\n• Underlying: *%s*\n• CE Hedge: `%s` @ ₹%.2f\n• PE Hedge: `%s` @ ₹%.2f",
                        instrument,
                        ceTradingSymbol,
                        cePrice.doubleValue(),
                        peTradingSymbol,
                        pePrice.doubleValue()));
    }

    /** 10:00 AM: Sells ATM Straddle (1x CE, 1x PE) with 30% Stop Loss. */
    public synchronized void executeStraddleEntry(LocalTime now) {
        if (!isEligibleToTrade()) {
            return;
        }
        if (state.getPhase() != StraddleSessionPhase.HEDGES_BOUGHT) {
            return;
        }

        String instrument = state.getInstrument();
        BigDecimal spot = fetchSpotPrice(instrument);
        BigDecimal strikeStep = resolveStrikeStep(instrument, spot);
        BigDecimal atm = snapToStep(spot, strikeStep);

        int qty = calculateQuantity(instrument);
        LocalDate expiry = resolveTargetExpiry(instrument, state.getTradeDate());

        String ceTradingSymbol =
                StockFnoRegistry.formatTradingSymbol(instrument, expiry, atm, "CE", true);
        String peTradingSymbol =
                StockFnoRegistry.formatTradingSymbol(instrument, expiry, atm, "PE", true);

        BigDecimal cePrice = fetchOptionLtp(instrument, ceTradingSymbol, BigDecimal.valueOf(100.0));
        BigDecimal pePrice = fetchOptionLtp(instrument, peTradingSymbol, BigDecimal.valueOf(100.0));

        BigDecimal ceSl = StraddleLeg.computeStopLoss(cePrice, properties.getSlPercentage());
        BigDecimal peSl = StraddleLeg.computeStopLoss(pePrice, properties.getSlPercentage());

        publishSignal(
                instrument,
                ceTradingSymbol,
                SignalAction.ENTRY_SHORT,
                cePrice,
                ceSl,
                BigDecimal.ZERO,
                qty,
                "10:00 AM ATM Straddle Sell");
        publishSignal(
                instrument,
                peTradingSymbol,
                SignalAction.ENTRY_SHORT,
                pePrice,
                peSl,
                BigDecimal.ZERO,
                qty,
                "10:00 AM ATM Straddle Sell");

        StraddleLeg shortCe = new StraddleLeg(ceTradingSymbol, "CE", atm, cePrice, ceSl, qty, true);
        StraddleLeg shortPe = new StraddleLeg(peTradingSymbol, "PE", atm, pePrice, peSl, qty, true);

        state.setShortCe(shortCe);
        state.setShortPe(shortPe);
        state.setPhase(StraddleSessionPhase.STRADDLE_ACTIVE);

        log.info(
                "[STRADDLE] 10:00 AM Straddle sold for {}: CE {} @ {} (SL 30%: {}), PE {} @ {} (SL 30%: {})",
                instrument, ceTradingSymbol, cePrice, ceSl, peTradingSymbol, pePrice, peSl);

        sendTelegramAlert(
                String.format(
                        BotLocale.INSTANCE,
                        "⚡ *10:00 AM ATM Straddle Sold (30%% SL)*\n• Underlying: *%s*\n• Short CE: `%s` @ ₹%.2f (SL: ₹%.2f)\n• Short PE: `%s` @ ₹%.2f (SL: ₹%.2f)\n• Target Exit: 15:00 IST",
                        instrument,
                        ceTradingSymbol,
                        cePrice.doubleValue(),
                        ceSl.doubleValue(),
                        peTradingSymbol,
                        pePrice.doubleValue(),
                        peSl.doubleValue()));
    }

    /** Continuous 10-second tick check for 30% Stop Loss breaches on active short legs. */
    public synchronized void evaluateStopLossBreaches(LocalTime now) {
        if (!properties.isEnabled()
                || state == null
                || state.isKillSwitchTripped()
                || state.getPhase() != StraddleSessionPhase.STRADDLE_ACTIVE) {
            return;
        }

        checkAndExitShortLegIfSlHit(state.getShortCe(), "Short CE");
        checkAndExitShortLegIfSlHit(state.getShortPe(), "Short PE");

        // Check if cumulative daily loss exceeds maxDailyLoss
        if (state.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0
                && state.getRealizedPnl().abs().doubleValue() >= properties.getMaxDailyLoss()) {
            state.setKillSwitchTripped(true);
            String reason =
                    String.format(
                            BotLocale.INSTANCE,
                            "Daily loss limit ₹%.0f breached (Realized: ₹%.2f)",
                            properties.getMaxDailyLoss(),
                            state.getRealizedPnl().doubleValue());
            log.error(
                    "[STRADDLE] KILL-SWITCH TRIPPED: {}. Initiating emergency square-off.", reason);
            executeHardSquareOff(reason);
        }
    }

    private void checkAndExitShortLegIfSlHit(StraddleLeg leg, String legLabel) {
        if (leg == null || !leg.isActive()) {
            return;
        }

        BigDecimal currentLtp = fetchOptionLtp(state.getInstrument(), leg.getTradingSymbol(), null);
        if (currentLtp == null || currentLtp.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        if (currentLtp.compareTo(leg.getStopLossPrice()) >= 0) {
            log.warn(
                    "[STRADDLE] 30% Stop Loss hit for {}: Current LTP {} >= SL {}. Exiting leg.",
                    legLabel, currentLtp, leg.getStopLossPrice());

            publishSignal(
                    state.getInstrument(),
                    leg.getTradingSymbol(),
                    SignalAction.EXIT_SHORT,
                    currentLtp,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    leg.getQuantity(),
                    legLabel + " 30% SL Triggered");

            leg.setActive(false);

            BigDecimal pointsLost = currentLtp.subtract(leg.getEntryPrice());
            BigDecimal legLoss =
                    pointsLost.multiply(BigDecimal.valueOf(leg.getQuantity())).negate();
            state.addRealizedPnl(legLoss);

            sendTelegramAlert(
                    String.format(
                            BotLocale.INSTANCE,
                            "🛑 *Straddle Leg 30%% Stop-Loss Hit*\n• Leg: *%s*\n• Contract: `%s`\n• Trigger LTP: `₹%.2f` (SL: `₹%.2f`)\n• Leg Loss: `-₹%.2f`\n• Surviving leg continues running till 15:00 IST",
                            legLabel,
                            leg.getTradingSymbol(),
                            currentLtp.doubleValue(),
                            leg.getStopLossPrice().doubleValue(),
                            legLoss.abs().doubleValue()));
        }
    }

    /** 15:00 PM: Closes surviving short leg and protection hedges completely (100% Flat EOD). */
    public synchronized void executeHardSquareOff(String reason) {
        if (state == null || state.getPhase() == StraddleSessionPhase.ALL_CLOSED) {
            return;
        }

        log.info("[STRADDLE] Square-off initiated: {}", reason);

        closeShortLeg(state.getShortCe(), "Short CE Square-Off");
        closeShortLeg(state.getShortPe(), "Short PE Square-Off");

        closeLongLeg(state.getHedgeLongCe(), "Long CE Hedge Close");
        closeLongLeg(state.getHedgeLongPe(), "Long PE Hedge Close");

        state.setPhase(StraddleSessionPhase.ALL_CLOSED);

        sendTelegramAlert(
                String.format(
                        BotLocale.INSTANCE,
                        "🏁 *Straddle Positions Fully Closed*\n• Reason: %s\n• Final Realized P&L: `₹%.2f`",
                        reason,
                        state.getRealizedPnl().doubleValue()));
    }

    private void closeShortLeg(StraddleLeg leg, String label) {
        if (leg != null && leg.isActive()) {
            BigDecimal ltp =
                    fetchOptionLtp(
                            state.getInstrument(), leg.getTradingSymbol(), leg.getEntryPrice());
            publishSignal(
                    state.getInstrument(),
                    leg.getTradingSymbol(),
                    SignalAction.EXIT_SHORT,
                    ltp,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    leg.getQuantity(),
                    label);
            leg.setActive(false);
            BigDecimal pts = leg.getEntryPrice().subtract(ltp);
            state.addRealizedPnl(pts.multiply(BigDecimal.valueOf(leg.getQuantity())));
        }
    }

    private void closeLongLeg(StraddleLeg leg, String label) {
        if (leg != null && leg.isActive()) {
            BigDecimal ltp =
                    fetchOptionLtp(
                            state.getInstrument(),
                            leg.getTradingSymbol(),
                            BigDecimal.valueOf(0.05));
            publishSignal(
                    state.getInstrument(),
                    leg.getTradingSymbol(),
                    SignalAction.EXIT_LONG,
                    ltp,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    leg.getQuantity(),
                    label);
            leg.setActive(false);
            BigDecimal pts = ltp.subtract(leg.getEntryPrice());
            state.addRealizedPnl(pts.multiply(BigDecimal.valueOf(leg.getQuantity())));
        }
    }

    private boolean isEligibleToTrade() {
        if (!properties.isEnabled() || state == null) {
            return false;
        }
        if (state.isKillSwitchTripped()) {
            log.debug("[STRADDLE] Daily kill-switch tripped; trading halted.");
            return false;
        }
        return state.getInstrument() != null;
    }

    private BigDecimal fetchSpotPrice(String instrument) {
        if (marketDataService != null) {
            try {
                String exch = "SENSEX".equalsIgnoreCase(instrument) ? "BSE" : "NSE";
                String token = "SENSEX".equalsIgnoreCase(instrument) ? "1" : "26000";
                JsonNode quote = marketDataService.fetchQuote(exch, token);
                if (quote != null && quote.has("lp")) {
                    double lp = quote.get("lp").asDouble(0.0);
                    if (lp > 0.0) {
                        return BigDecimal.valueOf(lp);
                    }
                }
            } catch (Exception e) {
                log.warn(
                        "[STRADDLE] Failed to fetch spot quote for {}: {}",
                        instrument,
                        e.getMessage());
            }
        }
        return "SENSEX".equalsIgnoreCase(instrument)
                ? BigDecimal.valueOf(80000.0)
                : BigDecimal.valueOf(23000.0);
    }

    private BigDecimal fetchOptionLtp(
            String instrument, String tradingSymbol, BigDecimal fallback) {
        if (marketDataService != null) {
            try {
                String exch = "SENSEX".equalsIgnoreCase(instrument) ? "BFO" : "NFO";
                String token = marketDataService.resolveToken(tradingSymbol);
                if (token != null) {
                    JsonNode quote = marketDataService.fetchQuote(exch, token);
                    if (quote != null && quote.has("lp")) {
                        double lp = quote.get("lp").asDouble(0.0);
                        if (lp > 0.0) {
                            return BigDecimal.valueOf(lp);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug(
                        "[STRADDLE] Option LTP quote lookup failed for {}: {}",
                        tradingSymbol,
                        e.getMessage());
            }
        }
        return fallback != null ? fallback : BigDecimal.ZERO;
    }

    private BigDecimal resolveStrikeStep(String instrument, BigDecimal spot) {
        BigDecimal step = StockFnoRegistry.getStrikeStep(instrument, spot);
        return step != null && step.compareTo(BigDecimal.ZERO) > 0
                ? step
                : ("SENSEX".equalsIgnoreCase(instrument)
                        ? BigDecimal.valueOf(100)
                        : BigDecimal.valueOf(50));
    }

    private BigDecimal snapToStep(BigDecimal price, BigDecimal step) {
        return price.divide(step, 0, RoundingMode.HALF_UP).multiply(step);
    }

    private int calculateQuantity(String instrument) {
        int lotSize = StockFnoRegistry.getLotSize(instrument);
        if (lotSize <= 0) {
            lotSize = "SENSEX".equalsIgnoreCase(instrument) ? 10 : 25;
        }
        return properties.getLots() * lotSize;
    }

    private LocalDate resolveTargetExpiry(String instrument, LocalDate tradeDate) {
        return StockFnoRegistry.calculateTargetExpiry(instrument, tradeDate, true, 1);
    }

    private void publishSignal(
            String underlying,
            String contract,
            SignalAction action,
            BigDecimal price,
            BigDecimal sl,
            BigDecimal target,
            int quantity,
            String reason) {
        if (signalPublisher != null) {
            TradeSignal signal =
                    TradeSignal.of(
                            STRATEGY_ID,
                            underlying,
                            contract,
                            action,
                            price,
                            sl,
                            target,
                            quantity,
                            reason,
                            Map.of("lots", properties.getLots()));
            signalPublisher.publish(signal);
        }
    }

    private void sendTelegramAlert(String message) {
        if (properties.isTelegramAlerts() && telegramService != null) {
            telegramService.sendTextMessage(message);
        }
    }

    public StraddleDailyState getState() {
        return state;
    }

    public void setClock(Clock clock) {
        this.clock = clock;
    }
}
