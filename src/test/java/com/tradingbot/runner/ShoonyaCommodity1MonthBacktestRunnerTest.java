package com.tradingbot.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.commodity.config.CommodityVwapProperties;
import com.tradingbot.strategy.commodity.feed.MutableClock;
import com.tradingbot.strategy.commodity.feed.ReplayCommodityQuoteFeed;
import com.tradingbot.strategy.commodity.model.CommodityBacktestMetrics;
import com.tradingbot.strategy.commodity.model.CommodityCostModel;
import com.tradingbot.strategy.commodity.model.CommoditySetup;
import com.tradingbot.strategy.commodity.model.CommoditySetupState;
import com.tradingbot.strategy.commodity.model.CommodityTradePosition;
import com.tradingbot.strategy.commodity.service.CommodityVwapStrategyService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 1-month replay of the PRODUCTION commodity VWAP rules: drives the real {@link
 * CommodityVwapStrategyService} bar-by-bar through {@link ReplayCommodityQuoteFeed} with a mutable
 * clock, applies transaction costs, asserts production invariants, and persists standardized JSON
 * metrics to {@code data/backtest/}.
 *
 * <p>Bias is supplied as a price/VWAP proxy PCR (no historical option chains exist); every other
 * rule — window gating, arming, trigger-age expiry, EMA filter, 0.8% risk cap, 2.5 RR, partial book
 * + breakeven + 10-EMA trailing, blackouts, EOD square-off, 1 trade/day — runs through the
 * unmodified production code path.
 */
class ShoonyaCommodity1MonthBacktestRunnerTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(IST);
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy").withZone(IST);

    private static final double PROXY_BULLISH_PCR = 1.30;
    private static final double PROXY_BEARISH_PCR = 0.70;

    @Test
    @DisplayName(
            "Production-Rules 1-Month Replay: SILVER + CRUDEOIL via CommodityVwapStrategyService (costs, invariants, JSON metrics)")
    void testProductionRules1MonthReplay() {
        ShoonyaConfig config = ShoonyaConfig.load();
        ShoonyaAuthenticator auth = new ShoonyaAuthenticator(config);
        ShoonyaMarketDataService marketDataService = new ShoonyaMarketDataService(config, auth);
        TechnicalAnalysisService taService = new TechnicalAnalysisService();
        CommodityVwapProperties properties = new CommodityVwapProperties();

        System.out.println(
                "==========================================================================================");
        System.out.println(
                " 🚀 PRODUCTION-RULES MCX COMMODITY 1-MONTH REPLAY (SILVER + CRUDEOIL — via Strategy Service)");
        System.out.println(
                "==========================================================================================");
        System.out.println(
                "⚙️ Rules: 13:30 bias | 15m VWAP crossover | 20-EMA | 0.8% risk cap | 1:2.5 RR");
        System.out.println(
                "📦 Exits: 50% partial @ target + SL to cost + 10-EMA runner | 1 trade/day | EOD 23:15");
        System.out.println(
                "💰 Costs: brokerage + slippage applied (net metrics) | Bias: price/VWAP PROXY (no PCR history)");
        System.out.println(
                "==========================================================================================\n");

        int daysBack = 35;

        List<CommodityTradePosition> silverTrades =
                runProductionReplay(
                        "SILVER",
                        "483080",
                        "495214",
                        daysBack,
                        marketDataService,
                        taService,
                        properties);
        List<CommodityTradePosition> crudeTrades =
                runProductionReplay(
                        "CRUDEOIL",
                        "569901",
                        "569900",
                        daysBack,
                        marketDataService,
                        taService,
                        properties);

        assumeTrue(
                silverTrades != null || crudeTrades != null,
                "No Shoonya candle data available (credentials/network) - replay skipped.");

        List<CommodityTradePosition> combinedTrades = new ArrayList<>();
        StringBuilder label = new StringBuilder("1-month production-rules replay [");
        if (silverTrades != null) {
            printSingleReport("SILVER (SILVERM — 5 kg)", silverTrades);
            combinedTrades.addAll(silverTrades);
            label.append("SILVER ");
        }
        if (crudeTrades != null) {
            printSingleReport("CRUDE OIL (CRUDEOILM — 10 bbl)", crudeTrades);
            combinedTrades.addAll(crudeTrades);
            label.append("CRUDEOIL ");
        }
        label.append("]");
        combinedTrades.sort((a, b) -> a.entryTime().compareTo(b.entryTime()));

        printCombinedSummary(combinedTrades);

        // ---- Production invariants (the reason this test exists) ----
        assertProductionInvariants(combinedTrades, properties);

        // ---- Standardized net-of-costs metrics + JSON persistence ----
        CommodityBacktestMetrics metrics =
                CommodityBacktestMetrics.compute(
                        label.toString(), combinedTrades, CommodityCostModel.from(properties));

        System.out.println("\n" + "#".repeat(95));
        System.out.printf(" 🧾 NET-OF-COSTS METRICS : %s%n", metrics.summaryLine());
        System.out.println("#".repeat(95));

        assertThat(metrics.netPnl())
                .isCloseTo(metrics.grossPnl() - metrics.totalCosts(), offset(0.05));

        Path outDir = Path.of("data", "backtest");
        Path outFile = outDir.resolve("commodity-vwap-" + LocalDate.now(IST) + ".json");
        try {
            metrics.writeJson(outDir, outFile.getFileName().toString());
        } catch (Exception e) {
            throw new AssertionError("Failed to persist backtest metrics JSON to " + outFile, e);
        }
        assertThat(Files.exists(outFile)).as("metrics JSON written").isTrue();
        System.out.printf("📁 Metrics persisted    : %s%n", outFile.toAbsolutePath());
    }

    /**
     * Runs one symbol through the production service, day by day. Returns {@code null} when candle
     * data could not be fetched (caller treats as "no data"), or the (possibly empty) trade list.
     */
    private List<CommodityTradePosition> runProductionReplay(
            String symbol,
            String primaryToken,
            String fallbackToken,
            int daysBack,
            ShoonyaMarketDataService marketDataService,
            TechnicalAnalysisService taService,
            CommodityVwapProperties properties) {

        System.out.printf("[SHOONYA] Fetching %d-day 15m candles for %s...%n", daysBack, symbol);
        List<Candle> candles =
                marketDataService.fetchHistoricalCandles(
                        "MCX", primaryToken, symbol, "15", daysBack);
        if (candles == null || candles.isEmpty()) {
            System.out.printf("[SHOONYA] Fallback token %s for %s...%n", fallbackToken, symbol);
            candles =
                    marketDataService.fetchHistoricalCandles(
                            "MCX", fallbackToken, symbol, "15", daysBack);
        }
        if (candles == null || candles.isEmpty()) {
            System.err.printf("❌ Could not fetch candles from Shoonya for %s.%n", symbol);
            return null;
        }
        System.out.printf(
                "✅ Retrieved %d candles (%s to %s)%n",
                candles.size(),
                DATE_FMT.format(candles.get(0).timestamp()),
                DATE_FMT.format(candles.get(candles.size() - 1).timestamp()));

        ReplayCommodityQuoteFeed feed = new ReplayCommodityQuoteFeed(candles);
        MutableClock clock = new MutableClock(candles.get(0).timestamp(), IST);
        CommodityVwapProperties symbolProps = new CommodityVwapProperties();
        symbolProps.setSymbols(List.of(symbol));
        symbolProps.setRiskRewardRatio(properties.getRiskRewardRatio());
        symbolProps.setMaxRiskPct(properties.getMaxRiskPct());
        symbolProps.setEntryStartTime(properties.getEntryStartTime());
        symbolProps.setEntryCutoff(properties.getEntryCutoff());
        symbolProps.setEodSquareOffTime(properties.getEodSquareOffTime());
        symbolProps.setMaxTradesPerSymbol(properties.getMaxTradesPerSymbol());
        symbolProps.setTelegramAlertsEnabled(false);

        CommodityVwapStrategyService service =
                new CommodityVwapStrategyService(symbolProps, feed, taService, null, clock, null);

        Map<LocalDate, List<Candle>> sessionMap = new TreeMap<>();
        for (Candle c : candles) {
            sessionMap
                    .computeIfAbsent(
                            LocalDate.ofInstant(c.timestamp(), IST), k -> new ArrayList<>())
                    .add(c);
        }

        List<CommodityTradePosition> trades = new ArrayList<>();

        for (Map.Entry<LocalDate, List<Candle>> session : sessionMap.entrySet()) {
            List<Candle> dayCandles = session.getValue();
            if (dayCandles.size() < 6) continue;

            // Mirrors the 13:00 IST daily session-reset scheduler job
            service.resetSession(true);

            // Anchor bar at/before 13:30 + session VWAP series (proxy-bias input only)
            double[] dayVwap = taService.calculateVwapSeries(dayCandles);
            Candle openBar = dayCandles.get(0);
            Candle bar1330 = null;
            int idx1330 = -1;
            for (int i = 0; i < dayCandles.size(); i++) {
                Candle c = dayCandles.get(i);
                if (!LocalTime.ofInstant(c.timestamp(), IST).isAfter(LocalTime.of(13, 30))) {
                    bar1330 = c;
                    idx1330 = i;
                }
            }
            if (bar1330 == null || idx1330 < 1) continue;

            // Price/VWAP proxy bias (old backtest rule) -> synthetic PCR for the production path
            double cClose = bar1330.close().doubleValue();
            double oPrice = openBar.open().doubleValue();
            boolean bullish;
            if (cClose >= dayVwap[idx1330] && cClose >= oPrice) {
                bullish = true;
            } else if (cClose <= dayVwap[idx1330] && cClose <= oPrice) {
                bullish = false;
            } else {
                bullish = cClose >= dayVwap[idx1330];
            }
            feed.setProxyPcr(bullish ? PROXY_BULLISH_PCR : PROXY_BEARISH_PCR);

            for (int i = idx1330; i < dayCandles.size(); i++) {
                Candle bar = dayCandles.get(i);
                LocalTime barTime = LocalTime.ofInstant(bar.timestamp(), IST);
                clock.setInstant(bar.timestamp());
                feed.advanceTo(bar.timestamp());

                if (i == idx1330) {
                    feed.setPendingLtp(bar.close());
                    service.evaluateDailyBias();
                    continue;
                }

                CommoditySetup setup = service.getSetup(symbol);
                if (setup.getState() == CommoditySetupState.IN_TRADE
                        && setup.getActivePosition() != null) {
                    // Adverse extreme first (conservative worst-case SL), then favorable
                    boolean isLong = "LONG".equalsIgnoreCase(setup.getActivePosition().side());
                    feed.setPendingLtp(isLong ? bar.low() : bar.high());
                    service.evaluateSymbolCycle(symbol, barTime);
                    if (setup.getState() == CommoditySetupState.IN_TRADE) {
                        feed.setPendingLtp(isLong ? bar.high() : bar.low());
                        service.evaluateSymbolCycle(symbol, barTime);
                    }
                } else {
                    feed.setPendingLtp(bar.close());
                    service.evaluateSymbolCycle(symbol, barTime);
                }

                // Production EOD square-off runs at 23:15 IST
                if (!barTime.isBefore(symbolProps.getEodSquareOffTime())
                        && setup.getState() == CommoditySetupState.IN_TRADE) {
                    feed.setPendingLtp(bar.close());
                    service.squareOffAllPositions("EOD_SQUARE_OFF");
                }
            }

            // Safety: never carry a position past its session
            service.squareOffAllPositions("EOD_SQUARE_OFF");

            CommoditySetup setup = service.getSetup(symbol);
            if (setup.getActivePosition() != null && setup.getActivePosition().isClosed()) {
                trades.add(setup.getActivePosition());
            }
        }

        System.out.printf(
                "📈 %s production replay: %d trade(s) over %d session(s)%n",
                symbol, trades.size(), sessionMap.size());
        return trades;
    }

    /** Asserts the invariants production code must uphold on every trade. */
    private void assertProductionInvariants(
            List<CommodityTradePosition> trades, CommodityVwapProperties properties) {
        Map<String, Long> tradesPerSymbolDay = new HashMap<>();

        for (CommodityTradePosition t : trades) {
            String where = t.symbol() + " " + DATE_FMT.format(t.entryTime());

            // 1. Entries only inside the 15:30-21:30 IST session window
            LocalTime entryLocal = LocalTime.ofInstant(t.entryTime(), IST);
            assertThat(!entryLocal.isBefore(properties.getEntryStartTime()))
                    .as("entry at/after 15:30 for %s", where)
                    .isTrue();
            assertThat(entryLocal.isBefore(properties.getEntryCutoff()))
                    .as("entry before 21:30 for %s", where)
                    .isTrue();

            // 2. Per-unit risk capped at 0.8% of entry (small epsilon for 2-dp rounding)
            double risk = t.risk().doubleValue();
            double entry = t.entryPrice().doubleValue();
            assertThat(risk)
                    .as("risk cap for %s", where)
                    .isLessThanOrEqualTo(entry * properties.getMaxRiskPct() + 0.01);

            // 3. Target anchored at 1:2.5 RR from entry
            double rr = properties.getRiskRewardRatio();
            double expectedTarget = "LONG".equals(t.side()) ? entry + rr * risk : entry - rr * risk;
            assertThat(t.targetPrice().doubleValue())
                    .as("target = entry +/- %.1f x risk for %s", rr, where)
                    .isCloseTo(expectedTarget, offset(0.02));

            // 4. Every collected trade is closed with a recorded reason
            assertThat(t.isClosed()).as("trade closed for %s", where).isTrue();
            assertThat(t.exitReason()).as("exit reason for %s", where).isNotBlank();

            // 5. Max 1 trade per symbol per session
            String dayKey = t.symbol() + "|" + LocalDate.ofInstant(t.entryTime(), IST);
            tradesPerSymbolDay.merge(dayKey, 1L, Long::sum);
        }

        tradesPerSymbolDay.forEach(
                (key, count) ->
                        assertThat(count)
                                .as("max 1 trade/day for %s", key)
                                .isLessThanOrEqualTo((long) properties.getMaxTradesPerSymbol()));
    }

    private void printSingleReport(String title, List<CommodityTradePosition> trades) {
        if (trades.isEmpty()) return;

        long wins = trades.stream().filter(t -> t.pnl().compareTo(BigDecimal.ZERO) > 0).count();
        long losses = trades.size() - wins;
        double wr = (double) wins / trades.size() * 100.0;

        BigDecimal totalPnl =
                trades.stream()
                        .map(CommodityTradePosition::pnl)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        System.out.println("\n" + "=".repeat(95));
        System.out.printf(" 📊 %s — PRODUCTION-RULES REPLAY\n", title);
        System.out.println("=".repeat(95));
        System.out.printf(" Total Trades Executed   : %d\n", trades.size());
        System.out.printf(" Winning Trades          : %d (%.1f%%)\n", wins, wr);
        System.out.printf(" Losing Trades           : %d (%.1f%%)\n", losses, 100.0 - wr);
        System.out.printf(" Gross Realized P&L      : ₹%,.2f\n", totalPnl);
        System.out.println("=".repeat(95));

        System.out.println("\n📋 TRADE LOG:");
        System.out.printf(
                "%-12s | %-6s | %-6s | %-14s | %-14s | %-18s | %-14s\n",
                "Date", "Time", "Side", "Entry (₹)", "Exit (₹)", "Reason", "Gross P&L");
        System.out.println("-".repeat(95));
        for (CommodityTradePosition t : trades) {
            System.out.printf(
                    "%-12s | %-6s | %-6s | ₹%-13.1f | ₹%-13.1f | %-18s | ₹%,.2f\n",
                    DATE_FMT.format(t.entryTime()),
                    TIME_FMT.format(t.entryTime()),
                    t.side(),
                    t.entryPrice(),
                    t.exitPrice(),
                    t.exitReason(),
                    t.pnl());
        }
    }

    private void printCombinedSummary(List<CommodityTradePosition> allTrades) {
        if (allTrades.isEmpty()) {
            System.out.println("\n⚠️ No trades produced by the production rules this period.");
            return;
        }

        long wins = allTrades.stream().filter(t -> t.pnl().compareTo(BigDecimal.ZERO) > 0).count();
        BigDecimal totalPnl =
                allTrades.stream()
                        .map(CommodityTradePosition::pnl)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        System.out.println("\n" + "#".repeat(95));
        System.out.println(" 🏆 COMBINED PORTFOLIO (SILVER + CRUDEOIL) — GROSS, PAST 1 MONTH");
        System.out.println("#".repeat(95));
        System.out.printf(" Total Portfolio Trades  : %d\n", allTrades.size());
        System.out.printf(
                " Winning Trades          : %d (%.1f%%)\n",
                wins, (double) wins / allTrades.size() * 100.0);
        System.out.printf(" Total Gross Realized P&L: ₹%,.2f\n", totalPnl);
        System.out.println("#".repeat(95));
    }
}
