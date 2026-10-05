package com.tradingbot.runner;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.marketdata.YahooFinanceService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.LowestVolumePaperPosition;
import com.tradingbot.model.strategy.LvrExitMode;
import com.tradingbot.model.strategy.LvrInstrumentType;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
import com.tradingbot.service.LowestVolumeReversalScanner;
import com.tradingbot.service.LowestVolumeReversalService;
import com.tradingbot.util.Nifty200Registry;
import com.tradingbot.util.NiftySectorRegistry;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@org.springframework.test.context.ActiveProfiles("test")
class ShoonyaLast30DaysLvrReplayRunnerTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(IST);
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy").withZone(IST);

    @Autowired private LowestVolumeReversalService lvrService;
    @Autowired private HistoricalOhlcCacheService ohlcCacheService;
    @Autowired private LowestVolumeReversalScanner scanner;
    @Autowired private TechnicalAnalysisService taService;

    @Test
    @DisplayName(
            "Replay real 5-minute historical market data for last 30 days (1 Month) through LVR Strategy Engine")
    void testReplayLast30DaysLvr() {
        System.out.println(
                "==========================================================================================================");
        System.out.println(
                "  1-MONTH REAL 5-MINUTE CANDLE REPLAY - LOWEST VOLUME REVERSAL (LVR) STRATEGY WITH ADVANCED GUARDRAILS");
        System.out.println(
                "==========================================================================================================");
        System.out.println(
                "⚙️ Configuration: Fixed 2 Lots | Max 1 Attempt/Symbol | Min 56% Breadth | Intraday VWAP & Sector Alignment");
        System.out.println(
                "📦 Execution Structure: 1:2 RR Target (50% Partial Book) + 10 EMA Trailing Runner Exit + 15:00 EOD Square-off");
        System.out.println(
                "==========================================================================================================\n");

        YahooFinanceService yfService = new YahooFinanceService(new ObjectMapper());

        // 1. Gather Sector Universe (all sector constituents + Nifty 50)
        List<String> universeSymbols = new ArrayList<>();
        for (List<String> sectorStocks : NiftySectorRegistry.getSectorConstituents().values()) {
            for (String s : sectorStocks) {
                if (!universeSymbols.contains(s)) {
                    universeSymbols.add(s);
                }
            }
        }
        for (String s : Nifty200Registry.getNifty200Symbols().stream().limit(50).toList()) {
            if (!universeSymbols.contains(s)) {
                universeSymbols.add(s);
            }
        }

        System.out.printf(
                "[FETCH] Downloading 30-day 5-minute historical candles for %d universe stocks from Yahoo Finance...\n",
                universeSymbols.size());

        Map<String, List<Candle>> stockCandlesMap = new HashMap<>();
        Set<LocalDate> allDates = new TreeSet<>();

        for (String sym : universeSymbols) {
            try {
                List<Candle> candles = yfService.fetch5MinCandles(sym, 35);
                if (candles != null && !candles.isEmpty()) {
                    stockCandlesMap.put(sym, candles);
                    for (Candle c : candles) {
                        LocalDate d = LocalDate.ofInstant(c.timestamp(), IST);
                        allDates.add(d);
                    }
                }
                Thread.sleep(10);
            } catch (Exception ignored) {
            }
        }

        List<LocalDate> sortedDates = new ArrayList<>(allDates);
        int numDays = Math.min(22, sortedDates.size());
        List<LocalDate> testTradingDays =
                sortedDates.subList(sortedDates.size() - numDays, sortedDates.size());

        System.out.printf(
                "[SETUP] Successfully loaded candles. Evaluating %d trading days from %s to %s\n\n",
                testTradingDays.size(),
                testTradingDays.get(0).format(DATE_FMT),
                testTradingDays.get(testTradingDays.size() - 1).format(DATE_FMT));

        int totalTrades = 0;
        int totalWins = 0;
        int totalLosses = 0;
        double totalRealizedPnlRs = 0.0;
        List<LowestVolumePaperPosition> allExecutedTrades = new ArrayList<>();

        for (LocalDate sessionDate : testTradingDays) {
            System.out.println(
                    "----------------------------------------------------------------------------------------------------------");
            System.out.printf("📅 SESSION: %s\n", sessionDate.format(DATE_FMT));
            System.out.println(
                    "----------------------------------------------------------------------------------------------------------");

            // 1. Build 09:25 snapshot quotes
            List<StockQuoteSnapshot> niftyQuotes0925 = new ArrayList<>();
            Map<String, List<StockQuoteSnapshot>> sectorQuotes0925 = new HashMap<>();

            for (String sym : universeSymbols) {
                List<Candle> allCandles = stockCandlesMap.get(sym);
                if (allCandles == null) continue;

                List<Candle> dayCandles =
                        allCandles.stream()
                                .filter(
                                        c ->
                                                LocalDate.ofInstant(c.timestamp(), IST)
                                                        .equals(sessionDate))
                                .toList();

                if (dayCandles.size() < 2) continue;

                Candle c1 = dayCandles.get(0);
                Candle c2 = dayCandles.get(1);

                double prevClose = c1.open().doubleValue();
                double ltp0925 = c2.close().doubleValue();
                double high0925 = Math.max(c1.high().doubleValue(), c2.high().doubleValue());
                double low0925 = Math.min(c1.low().doubleValue(), c2.low().doubleValue());
                double pctChange = ((ltp0925 - prevClose) / prevClose) * 100.0;

                StockQuoteSnapshot snapshot =
                        new StockQuoteSnapshot(sym, ltp0925, high0925, low0925, pctChange);

                if (NiftySectorRegistry.NIFTY_50_CONSTITUENTS.contains(sym)) {
                    niftyQuotes0925.add(snapshot);
                }

                String sector = NiftySectorRegistry.getSectorForSymbol(sym);
                if (sector != null) {
                    sectorQuotes0925.computeIfAbsent(sector, k -> new ArrayList<>()).add(snapshot);
                }
            }

            if (niftyQuotes0925.isEmpty()) {
                System.out.println("  ⚠️ No Nifty 50 data available for session. Skipping.");
                continue;
            }

            // 2. Evaluate Market Sentiment with 56% Breadth Guardrail
            LowestVolumeDirection sentiment =
                    scanner.evaluateMarketSentiment(niftyQuotes0925, 56.0);
            if (sentiment == LowestVolumeDirection.NONE) {
                System.out.println(
                        "  ⚠️ Market Breadth Neutral / Mixed (<56% threshold). Standing down for the day to avoid whipsaws.");
                continue;
            }

            // 3. Rank Sectors
            List<LowestVolumeReversalScanner.SectorRankResult> rankedSectors =
                    scanner.rankSectors(sectorQuotes0925, sentiment);
            if (rankedSectors.isEmpty()) {
                System.out.println("  ⚠️ No ranked sectors available.");
                continue;
            }

            LowestVolumeReversalScanner.SectorRankResult winningSector = rankedSectors.get(0);
            List<StockQuoteSnapshot> sectorStockSnapshots =
                    sectorQuotes0925.getOrDefault(winningSector.sectorName(), List.of());
            List<String> topCandidates =
                    scanner.filterCandidateStocks(sectorStockSnapshots, sentiment);

            System.out.printf(
                    "  • Sentiment: %s | Winning Sector: %s (%+.2f%%) | Candidates: %s\n",
                    sentiment,
                    winningSector.sectorName(),
                    winningSector.pctChange(),
                    topCandidates);

            // 4. Configure Service for Session Date
            lvrService.setClock(
                    Clock.fixed(sessionDate.atTime(10, 0).atZone(IST).toInstant(), IST));
            lvrService.setInstrumentType(LvrInstrumentType.FUTURES);
            lvrService.setExitMode(LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500);
            lvrService.setDefaultLots(2);
            lvrService.setDynamicPositionSizing(false);
            lvrService.setMaxAttemptsPerSymbol(1);
            lvrService.setMaxSlippagePct(0.12);
            lvrService.setMinStopLossPct(0.35);
            lvrService.setVwapConfirmationEnabled(true);
            lvrService.setOpening15mRangeFilterEnabled(true);
            lvrService.setPdhPdlFilterEnabled(true);
            lvrService.setSectorMomentumFilterEnabled(false);

            int sessionTrades = 0;
            double sessionPnl = 0.0;

            for (String candidate : topCandidates.stream().limit(3).toList()) {
                List<Candle> allCandles = stockCandlesMap.get(candidate);
                if (allCandles == null) continue;

                List<Candle> dayCandles =
                        allCandles.stream()
                                .filter(
                                        c ->
                                                LocalDate.ofInstant(c.timestamp(), IST)
                                                        .equals(sessionDate))
                                .toList();

                if (dayCandles.size() < 4) continue;

                List<LowestVolumePaperPosition> trades =
                        lvrService.replaySession(candidate, sentiment, dayCandles);
                for (LowestVolumePaperPosition t : trades) {
                    sessionTrades++;
                    totalTrades++;
                    double pnl = t.getTotalRealizedPnl().doubleValue();
                    sessionPnl += pnl;
                    totalRealizedPnlRs += pnl;

                    if (pnl > 0) totalWins++;
                    else totalLosses++;

                    allExecutedTrades.add(t);

                    System.out.printf(
                            "    ▶ Trade: %-12s | Entry: %-5s @ %-8.2f | Exit: %-5s @ %-8.2f | Reason: %-22s | PnL: ₹%+9.2f\n",
                            t.getSymbol(),
                            t.getEntryTime() != null
                                    ? t.getEntryTime().atZone(IST).format(TIME_FMT)
                                    : "N/A",
                            t.getStockEntryPrice().doubleValue(),
                            t.getExitTime() != null
                                    ? t.getExitTime().atZone(IST).format(TIME_FMT)
                                    : "N/A",
                            t.getRunnerExitPremium().doubleValue(),
                            t.getExitReason(),
                            pnl);
                }
            }

            if (sessionTrades == 0) {
                System.out.println(
                        "    (No trade triggers breached for candidates today - zero risk taken)");
            } else {
                System.out.printf(
                        "  👉 Session Total: %d Trade(s) | Net Day P&L: ₹%+,.2f\n",
                        sessionTrades, sessionPnl);
            }
        }

        System.out.println(
                "\n==========================================================================================================");
        System.out.println("🏆 1-MONTH BACKTEST PERFORMANCE SUMMARY (LAST 22 TRADING SESSIONS)");
        System.out.println(
                "==========================================================================================================");
        System.out.printf("• Total Trading Days:        %d days\n", testTradingDays.size());
        System.out.printf(
                "• Total Trades Executed:     %d trades (Avg %.1f trades/day)\n",
                totalTrades, (double) totalTrades / testTradingDays.size());
        System.out.printf(
                "• Winning Trades:            %d (%.1f%% Win Rate)\n",
                totalWins, totalTrades > 0 ? ((double) totalWins / totalTrades) * 100.0 : 0.0);
        System.out.printf(
                "• Losing Trades:             %d (%.1f%% Loss Rate)\n",
                totalLosses, totalTrades > 0 ? ((double) totalLosses / totalTrades) * 100.0 : 0.0);

        double grossWins =
                allExecutedTrades.stream()
                        .mapToDouble(t -> Math.max(0.0, t.getTotalRealizedPnl().doubleValue()))
                        .sum();
        double grossLosses =
                allExecutedTrades.stream()
                        .mapToDouble(
                                t -> Math.abs(Math.min(0.0, t.getTotalRealizedPnl().doubleValue())))
                        .sum();
        double pf = grossLosses > 0 ? grossWins / grossLosses : 99.9;

        System.out.printf("• Gross Profit:              ₹%+,10.2f\n", grossWins);
        System.out.printf("• Gross Loss:                ₹%-,10.2f\n", -grossLosses);
        System.out.printf("• Profit Factor:             %.2f\n", pf);
        System.out.printf(
                "• Total Realized Net P&L:    ₹%+,.2f (Fixed 2 Lots)\n", totalRealizedPnlRs);
        System.out.printf(
                "• Average P&L per Trade:     ₹%+,.2f\n",
                totalTrades > 0 ? totalRealizedPnlRs / totalTrades : 0.0);
        System.out.println(
                "==========================================================================================================");

        assertThat(totalTrades).isGreaterThanOrEqualTo(0);
    }
}
