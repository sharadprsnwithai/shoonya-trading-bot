package com.tradingbot.strategy.bollingerha.scheduler;

import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.bollingerha.config.BollingerHaProperties;
import com.tradingbot.strategy.bollingerha.feeder.ShoonyaHybridDataFeeder;
import com.tradingbot.strategy.bollingerha.model.SelectedStrikes;
import com.tradingbot.strategy.bollingerha.service.BollingerHaIntradayEngine;
import com.tradingbot.strategy.bollingerha.service.BollingerHaStrikeSelector;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled lifecycle orchestrator for the 1-minute Bollinger Bands Heikin-Ashi Intraday Strategy.
 */
@Component
public class BollingerHaScheduler {

    private static final Logger log = LoggerFactory.getLogger(BollingerHaScheduler.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final BollingerHaProperties properties;
    private final BollingerHaStrikeSelector strikeSelector;
    private final ShoonyaHybridDataFeeder dataFeeder;
    private final BollingerHaIntradayEngine engine;
    private final ShoonyaMarketDataService marketDataService;
    private final TelegramService telegramService;

    private SelectedStrikes currentStrikes;

    @Autowired
    public BollingerHaScheduler(
            BollingerHaProperties properties,
            BollingerHaStrikeSelector strikeSelector,
            ShoonyaHybridDataFeeder dataFeeder,
            BollingerHaIntradayEngine engine,
            ShoonyaMarketDataService marketDataService,
            TelegramService telegramService) {
        this.properties = properties;
        this.strikeSelector = strikeSelector;
        this.dataFeeder = dataFeeder;
        this.engine = engine;
        this.marketDataService = marketDataService;
        this.telegramService = telegramService;
    }

    /**
     * 09:07 AM IST: Queries pre-market Nifty spot, computes ATM strike, resolves CE/PE contracts.
     */
    @Scheduled(cron = "0 7 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void runPreMarketStrikeSelection() {
        if (!properties.isEnabled()) {
            return;
        }

        log.info("[BOLLINGER-HA-SCHEDULER] 🌅 Executing pre-market strike selection...");
        try {
            BigDecimal spotPrice = fetchNiftySpot();
            this.currentStrikes = strikeSelector.selectWeeklyAtmStrikes(spotPrice);

            engine.initStrike(
                    "CE",
                    currentStrikes.ceToken(),
                    currentStrikes.ceSymbol(),
                    currentStrikes.atmStrike());
            engine.initStrike(
                    "PE",
                    currentStrikes.peToken(),
                    currentStrikes.peSymbol(),
                    currentStrikes.atmStrike());

            String message =
                    String.format(
                            "🎯 *[09:07 AM] BOLLINGER HA STRIKE SELECTION*\n"
                                    + "📈 *Nifty Spot:* ₹%s\n"
                                    + "🎯 *ATM Strike:* %s\n"
                                    + "🟢 *CE Strike:* `%s` (%s)\n"
                                    + "🔴 *PE Strike:* `%s` (%s)",
                            spotPrice,
                            currentStrikes.atmStrike(),
                            currentStrikes.ceSymbol(),
                            currentStrikes.ceToken(),
                            currentStrikes.peSymbol(),
                            currentStrikes.peToken());
            telegramService.sendAlert(message);
        } catch (Exception e) {
            log.error(
                    "[BOLLINGER-HA-SCHEDULER] Failed pre-market strike selection: {}",
                    e.getMessage(),
                    e);
            telegramService.sendAlert(
                    "⚠️ *[BOLLINGER HA ERROR]* Pre-market strike selection failed: "
                            + e.getMessage());
        }
    }

    /** 09:14 AM IST: Connects WebSocket tick feeder to begin streaming at 09:15 open. */
    @Scheduled(cron = "0 14 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void runMarketOpenStreaming() {
        if (!properties.isEnabled() || currentStrikes == null) {
            return;
        }

        log.info(
                "[BOLLINGER-HA-SCHEDULER] 🚀 Initializing real-time data stream for 09:15 open...");
        dataFeeder.initialize(currentStrikes, engine::onCandleCompleted);
        telegramService.sendAlert(
                "🚀 *[09:14 AM]* Bollinger HA Feeder armed for 09:15 market open.");
    }

    /** 10:30 AM IST: Morning entry cutoff. */
    @Scheduled(cron = "0 30 10 * * MON-FRI", zone = "Asia/Kolkata")
    public void runMorningCutoff() {
        if (!properties.isEnabled()) {
            return;
        }
        log.info("[BOLLINGER-HA-SCHEDULER] ⏰ Morning entry cutoff reached (10:30 AM IST).");
        telegramService.sendAlert(
                "⏰ *[10:30 AM]* Bollinger HA Morning entry cutoff reached. No new entries; managing active trades.");
    }

    /** 03:15 PM IST: Intraday Auto Square-Off. */
    @Scheduled(cron = "0 15 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runAutoSquareOff() {
        if (!properties.isEnabled()) {
            return;
        }
        log.info("[BOLLINGER-HA-SCHEDULER] 🔔 03:15 PM Intraday Auto Square-off executing...");
        engine.squareOffAll("Intraday Auto Square-Off at 15:15 IST");
    }

    /** 03:30 PM IST: Sends daily performance summary. */
    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runDailySummary() {
        if (!properties.isEnabled()) {
            return;
        }

        var daily = engine.getDailyState();
        String summary =
                String.format(
                        "📊 *[03:30 PM] BOLLINGER HA DAILY SUMMARY*\n"
                                + "📅 *Date:* %s\n"
                                + "🔢 *Trades Taken:* %d / %d\n"
                                + "💰 *Realized PnL:* ₹%s\n"
                                + "🔒 *Locked:* %s",
                        LocalDate.now(IST),
                        daily.getTradeCount(),
                        properties.getMaxDailyTrades(),
                        daily.getRealizedPnl(),
                        daily.isLocked() ? "YES" : "NO");
        telegramService.sendAlert(summary);
    }

    private BigDecimal fetchNiftySpot() {
        try {
            List<Candle> candles = marketDataService.fetchHourlyCandles("NIFTY50", 1);
            if (candles != null && !candles.isEmpty()) {
                return candles.get(candles.size() - 1).close();
            }
        } catch (Exception e) {
            log.warn("[BOLLINGER-HA-SCHEDULER] Hourly candle fetch failed: {}", e.getMessage());
        }
        return new BigDecimal("25000.00"); // Safe fallback if offline
    }

    public SelectedStrikes getCurrentStrikes() {
        return currentStrikes;
    }

    public void setCurrentStrikes(SelectedStrikes currentStrikes) {
        this.currentStrikes = currentStrikes;
    }
}
