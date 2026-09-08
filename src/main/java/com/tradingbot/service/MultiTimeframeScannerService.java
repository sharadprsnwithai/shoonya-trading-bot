package com.tradingbot.service;

import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.strategy.MtfTrendStatus;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.CandleResamplingUtil;
import com.tradingbot.util.Nifty200Registry;
import com.tradingbot.util.StockFnoRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service that scans the NIFTY 200 universe for multi-timeframe uptrend confluence (Weekly, Daily,
 * and Hourly) and dispatches actionable trade recommendations to Telegram.
 */
@Service
public class MultiTimeframeScannerService {

    private static final Logger log = LoggerFactory.getLogger(MultiTimeframeScannerService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(IST);
    private static final DateTimeFormatter EXPIRY_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    private final MultiTimeframeTrendService trendService;
    private final ShoonyaMarketDataService marketDataService;
    private final TelegramService telegramService;
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    @Autowired
    public MultiTimeframeScannerService(
            MultiTimeframeTrendService trendService,
            ShoonyaMarketDataService marketDataService,
            TelegramService telegramService) {
        this.trendService = trendService;
        this.marketDataService = marketDataService;
        this.telegramService = telegramService;
    }

    /** Scans a single symbol across Weekly, Daily, and Hourly timeframes. */
    public MtfTrendStatus scanSymbol(String symbol) {
        try {
            List<Candle> hourlyCandles = marketDataService.fetchHourlyCandles(symbol, 30);
            if (hourlyCandles == null || hourlyCandles.size() < 25) {
                return null;
            }

            List<Candle> dailyCandles = marketDataService.fetchDailyCandles(symbol, 300);
            List<Candle> weeklyCandles = CandleResamplingUtil.resampleDailyToWeekly(dailyCandles);

            return trendService.evaluateTrend(symbol, weeklyCandles, dailyCandles, hourlyCandles);
        } catch (Exception e) {
            log.warn("[MTF-SCAN] Failed to evaluate {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    /**
     * Concurrently scans the full NIFTY 200 universe.
     *
     * @return List of all successfully evaluated symbols, sorted by confluence and momentum
     */
    public List<MtfTrendStatus> scanAllNifty200() {
        List<String> symbols = Nifty200Registry.getAllSymbols();
        List<CompletableFuture<MtfTrendStatus>> futures = new ArrayList<>();

        for (String sym : symbols) {
            futures.add(CompletableFuture.supplyAsync(() -> scanSymbol(sym), executor));
        }

        List<MtfTrendStatus> results = new ArrayList<>();
        for (CompletableFuture<MtfTrendStatus> future : futures) {
            try {
                MtfTrendStatus status = future.join();
                if (status != null) {
                    results.add(status);
                }
            } catch (Exception e) {
                log.debug("[MTF-SCAN] Error joining future: {}", e.getMessage());
            }
        }

        // Sort: Any confluence (bullish or bearish) first, then fresh triggers, then highest hourly
        // ADX
        results.sort(
                Comparator.comparing(
                                (MtfTrendStatus s) ->
                                        s.isFullConfluence() || s.isBearishConfluence())
                        .thenComparing(
                                s -> s.isFreshHourlyTrigger() || s.isFreshHourlyBearishTrigger())
                        .thenComparingDouble(MtfTrendStatus::hourlyAdx)
                        .reversed());

        return results;
    }

    /**
     * Filters for symbols that exhibit full 3-timeframe confluence (Weekly, Daily, and Hourly
     * Bullish).
     */
    public List<MtfTrendStatus> getConfluenceUptrendStocks(List<MtfTrendStatus> scanResults) {
        if (scanResults == null) return List.of();
        return scanResults.stream()
                .filter(MtfTrendStatus::isFullConfluence)
                .sorted(
                        Comparator.comparing(MtfTrendStatus::isFreshHourlyTrigger)
                                .thenComparingDouble(MtfTrendStatus::hourlyAdx)
                                .reversed())
                .toList();
    }

    /**
     * Filters for symbols that exhibit full bearish confluence (Loser stocks: Weekly and Daily
     * price below SuperTrend, and Hourly crossed below / below SuperTrend).
     */
    public List<MtfTrendStatus> getConfluenceDowntrendStocks(List<MtfTrendStatus> scanResults) {
        if (scanResults == null) return List.of();
        return scanResults.stream()
                .filter(MtfTrendStatus::isBearishConfluence)
                .sorted(
                        Comparator.comparing(MtfTrendStatus::isFreshHourlyBearishTrigger)
                                .thenComparingDouble(MtfTrendStatus::hourlyAdx)
                                .reversed())
                .toList();
    }

    /**
     * Dispatches the Multi-Timeframe Confluence Report (Gainers & Losers) to Telegram.
     *
     * @param uptrendStocks list of stocks with bullish confluence
     * @param downtrendStocks list of stocks with bearish confluence
     * @param totalScanned total number of stocks evaluated
     * @return true if notification was dispatched
     */
    public boolean sendTelegramReport(
            List<MtfTrendStatus> uptrendStocks,
            List<MtfTrendStatus> downtrendStocks,
            int totalScanned) {
        if (uptrendStocks == null) {
            uptrendStocks = Collections.emptyList();
        }
        if (downtrendStocks == null) {
            downtrendStocks = Collections.emptyList();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📊 *[MULTI-TIMEFRAME SCANNER: GAINERS & LOSERS]* 📊\n\n");
        sb.append("🕒 *Scan Time:* ")
                .append(TIME_FMT.format(Instant.now()))
                .append(" IST (Hourly Run)\n");
        sb.append("📊 *Scope:* NIFTY 200 Universe (").append(totalScanned).append(" Scanned)\n\n");

        if (uptrendStocks.isEmpty() && downtrendStocks.isEmpty()) {
            sb.append(
                    "ℹ️ *No stocks currently exhibit multi-timeframe confluence (Gainers or Losers).*\n");
            sb.append("Market is in consolidation or mixed trend. Bot will re-scan next hour.");
            telegramService.sendAsync(sb.toString());
            return true;
        }

        LocalDate now = LocalDate.now(IST);

        // Section 1: GAINERS / UPTREND LEADERS
        if (!uptrendStocks.isEmpty()) {
            sb.append("🟢 *TOP GAINERS (UPTREND CONFLUENCE - CALL BUYING)* 🟢\n");
            sb.append("• _Weekly, Daily & Hourly SuperTrend Bullish_\n\n");

            int count = Math.min(6, uptrendStocks.size());
            for (int i = 0; i < count; i++) {
                MtfTrendStatus s = uptrendStocks.get(i);
                LocalDate expiry =
                        StockFnoRegistry.calculateTargetExpiry(s.symbol(), now, false, 8);
                String expiryStr = expiry.format(EXPIRY_FMT).toUpperCase();

                String freshTag = s.isFreshHourlyTrigger() ? " ⚡ *[FRESH TRIGGER]*" : "";
                sb.append(
                        String.format(
                                "%d. *%s*%s ➔ Spot: *₹%.2f*\n",
                                (i + 1), s.symbol(), freshTag, s.currentPrice()));
                sb.append(
                        String.format(
                                "   • *Target ATM:* `%.1f CE` | Exp: `%s`\n",
                                s.atmCallStrike(), expiryStr));
                sb.append(String.format("   • *Confluence:* %s\n", s.getTrendSummary()));
                sb.append(
                        String.format(
                                "   • *Metrics:* Daily RSI: `%.1f` | Hourly ADX: `%.1f`\n",
                                s.dailyRsi(), s.hourlyAdx()));
                sb.append(
                        String.format(
                                "   • *Trailing SL Anchor:* ₹%.2f (Hourly ST)\n\n",
                                s.trailingStopLoss()));
            }
        }

        // Section 2: LOSERS / DOWNTREND LEADERS
        if (!downtrendStocks.isEmpty()) {
            sb.append("🔴 *TOP LOSERS (DOWNTREND CONFLUENCE - PUT BUYING)* 🔴\n");
            sb.append("• _Weekly & Daily Below SuperTrend + Hourly Crossed Below SuperTrend_\n\n");

            int count = Math.min(6, downtrendStocks.size());
            for (int i = 0; i < count; i++) {
                MtfTrendStatus s = downtrendStocks.get(i);
                LocalDate expiry =
                        StockFnoRegistry.calculateTargetExpiry(s.symbol(), now, false, 8);
                String expiryStr = expiry.format(EXPIRY_FMT).toUpperCase();

                String freshTag = s.isFreshHourlyBearishTrigger() ? " ⚡ *[FRESH BREAKDOWN]*" : "";
                sb.append(
                        String.format(
                                "%d. *%s*%s ➔ Spot: *₹%.2f*\n",
                                (i + 1), s.symbol(), freshTag, s.currentPrice()));
                sb.append(
                        String.format(
                                "   • *Target ATM:* `%.1f PE` | Exp: `%s`\n",
                                s.atmPutStrike(), expiryStr));
                sb.append(String.format("   • *Confluence:* %s\n", s.getTrendSummary()));
                sb.append(
                        String.format(
                                "   • *Metrics:* Daily RSI: `%.1f` | Hourly ADX: `%.1f`\n",
                                s.dailyRsi(), s.hourlyAdx()));
                sb.append(
                        String.format(
                                "   • *Trailing SL Anchor:* ₹%.2f (Hourly ST Resistance)\n\n",
                                s.trailingStopLoss()));
            }
        }

        sb.append("💡 *Summary:* `")
                .append(uptrendStocks.size())
                .append("` Gainers (CE) | `")
                .append(downtrendStocks.size())
                .append("` Losers (PE) / ")
                .append(totalScanned)
                .append(" Scanned\n\n");

        sb.append("🤖 *Bot Automation:* Monitoring hourly during market hours (09:15 - 15:30 IST)");

        telegramService.sendAsync(sb.toString());
        return true;
    }

    /** Backwards-compatible overload. */
    public boolean sendTelegramReport(List<MtfTrendStatus> confluenceStocks, int totalScanned) {
        return sendTelegramReport(confluenceStocks, Collections.emptyList(), totalScanned);
    }
}
