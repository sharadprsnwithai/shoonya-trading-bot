package com.tradingbot.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.indicator.TechnicalAnalysisService;
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

class ShoonyaLast5DaysLvrReplayRunnerTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(IST);
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy").withZone(IST);

    @Test
    @DisplayName(
            "Replay real 5-minute historical market data for last 5 trading days through Kushal Varshney LVR Framework")
    void testReplayLast5WorkingDaysLvr() {
        System.out.println(
                "==========================================================================================");
        System.out.println(
                "  5-DAY REAL MARKET DATA REPLAY - KUSHAL VARSHNEY LVR STRATEGY FRAMEWORK");
        System.out.println(
                "==========================================================================================");

        YahooFinanceService yfService = new YahooFinanceService(new ObjectMapper());

        // 1. Gather Sector Universe (e.g. top liquid F&O sector stocks + Nifty 50 constituents)
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
                "[FETCH] Downloading real 5-minute historical candles for %d liquid stocks...\n",
                universeSymbols.size());

        Map<String, List<Candle>> stockCandlesMap = new HashMap<>();
        Set<LocalDate> allDates = new TreeSet<>();

        for (String sym : universeSymbols) {
            try {
                List<Candle> candles = yfService.fetch5MinCandles(sym, 8);
                if (candles != null && !candles.isEmpty()) {
                    stockCandlesMap.put(sym, candles);
                    for (Candle c : candles) {
                        LocalDate d = LocalDate.ofInstant(c.timestamp(), IST);
                        allDates.add(d);
                    }
                }
                Thread.sleep(15);
            } catch (Exception ignored) {
            }
        }

        List<LocalDate> sortedDates = new ArrayList<>(allDates);
        // Take the last 5 trading days
        int numDays = Math.min(5, sortedDates.size());
        List<LocalDate> last5Days =
                sortedDates.subList(sortedDates.size() - numDays, sortedDates.size());

        System.out.printf(
                "[SETUP] Loaded historical data across %d days. Evaluating last %d trading days: %s\n\n",
                sortedDates.size(), last5Days.size(), last5Days);

        int totalDaysTraded = 0;
        int totalTradesTriggered = 0;
        int totalWins = 0;
        int totalLosses = 0;
        double netRMultiple = 0.0;

        LowestVolumeReversalScanner scanner = new LowestVolumeReversalScanner();
        TechnicalAnalysisService taService = new TechnicalAnalysisService();
        LowestVolumeReversalService lvrService =
                new LowestVolumeReversalService(null, taService, null, null, null);
        lvrService.setInstrumentType(LvrInstrumentType.FUTURES);
        lvrService.setExitMode(LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500);
        lvrService.setTelegramAlerts(false);

        // 2. Iterate Day by Day
        for (int dayIdx = 0; dayIdx < last5Days.size(); dayIdx++) {
            LocalDate tradeDate = last5Days.get(dayIdx);
            totalDaysTraded++;

            System.out.println(
                    "==========================================================================================");
            System.out.printf(
                    "  DAY %d / %d: %s (%s)\n",
                    dayIdx + 1,
                    last5Days.size(),
                    tradeDate.format(DateTimeFormatter.ofPattern("EEEE, dd-MMMM-yyyy")),
                    tradeDate);
            System.out.println(
                    "==========================================================================================");

            // Filter candles for this specific day
            Map<String, List<Candle>> dayStockCandles = new HashMap<>();
            for (Map.Entry<String, List<Candle>> entry : stockCandlesMap.entrySet()) {
                List<Candle> filtered =
                        entry.getValue().stream()
                                .filter(
                                        c ->
                                                LocalDate.ofInstant(c.timestamp(), IST)
                                                        .equals(tradeDate))
                                .sorted((c1, c2) -> c1.timestamp().compareTo(c2.timestamp()))
                                .toList();
                if (!filtered.isEmpty()) {
                    dayStockCandles.put(entry.getKey(), filtered);
                }
            }

            // Step 1: 09:25 IST Sentiment Analysis
            List<StockQuoteSnapshot> niftyQuotes0925 = new ArrayList<>();
            List<String> nifty50Symbols =
                    Nifty200Registry.getNifty200Symbols().stream().limit(50).toList();

            for (String sym : nifty50Symbols) {
                List<Candle> candles = dayStockCandles.get(sym);
                if (candles != null && candles.size() >= 2) {
                    double open = candles.get(0).open().doubleValue();
                    double ltp = candles.get(1).close().doubleValue();
                    double pct = (open > 0) ? ((ltp - open) / open) * 100.0 : 0.0;
                    niftyQuotes0925.add(new StockQuoteSnapshot(sym, ltp, open, open, pct));
                }
            }

            LowestVolumeDirection sentiment = scanner.evaluateMarketSentiment(niftyQuotes0925);
            long adv = niftyQuotes0925.stream().filter(q -> q.pctChange() > 0).count();
            long dec = niftyQuotes0925.stream().filter(q -> q.pctChange() < 0).count();

            System.out.printf(
                    "  [09:25 IST] Market Sentiment: %s (Advances: %d, Declines: %d)\n",
                    sentiment, adv, dec);

            // Step 2: 09:25 IST Sector Ranking
            Map<String, List<StockQuoteSnapshot>> sectorQuotes0925 = new HashMap<>();
            for (Map.Entry<String, List<String>> entry :
                    NiftySectorRegistry.getSectorConstituents().entrySet()) {
                String sectorName = entry.getKey();
                List<StockQuoteSnapshot> sQuotes = new ArrayList<>();
                for (String sym : entry.getValue()) {
                    List<Candle> candles = dayStockCandles.get(sym);
                    if (candles != null && candles.size() >= 2) {
                        double open = candles.get(0).open().doubleValue();
                        double ltp = candles.get(1).close().doubleValue();
                        double pct = (open > 0) ? ((ltp - open) / open) * 100.0 : 0.0;
                        sQuotes.add(new StockQuoteSnapshot(sym, ltp, open, open, pct));
                    }
                }
                sectorQuotes0925.put(sectorName, sQuotes);
            }

            List<LowestVolumeReversalScanner.SectorRankResult> rankedSectors =
                    scanner.rankSectors(sectorQuotes0925, sentiment);

            if (!rankedSectors.isEmpty()) {
                LowestVolumeReversalScanner.SectorRankResult topSector = rankedSectors.get(0);
                System.out.printf(
                        "  [09:25 IST] Top Sector: %s (%+.2f%%)\n",
                        topSector.sectorName(), topSector.pctChange());

                List<StockQuoteSnapshot> topSectorStockQuotes =
                        sectorQuotes0925.getOrDefault(topSector.sectorName(), List.of());
                List<String> topCandidates =
                        scanner.filterCandidateStocks(topSectorStockQuotes, sentiment);
                System.out.printf("  [09:25 IST] Candidate Stocks: %s\n", topCandidates);
            }

            // Step 3: Run 5m Candle Engine for the Day
            List<String> candidateList =
                    (rankedSectors.isEmpty())
                            ? List.of()
                            : scanner.filterCandidateStocks(
                                    sectorQuotes0925.getOrDefault(
                                            rankedSectors.get(0).sectorName(), List.of()),
                                    sentiment);

            Map<String, List<Candle>> sectorCandidateCandles = new HashMap<>();
            for (String sym : candidateList) {
                if (dayStockCandles.containsKey(sym)) {
                    sectorCandidateCandles.put(sym, dayStockCandles.get(sym));
                }
            }
            System.out.println("  --- [A] Top Sector Candidates Execution (Production Mode) ---");
            DayResult sectorResult =
                    runDayExecution(lvrService, sectorCandidateCandles, sentiment, true);

            System.out.println("\n  --- [B] Universe-Wide (All 140+ F&O Stocks) LVR Replay ---");
            DayResult dayResult = runDayExecution(lvrService, dayStockCandles, sentiment, false);

            totalTradesTriggered += sectorResult.trades;
            totalWins += sectorResult.wins;
            totalLosses += sectorResult.losses;
            netRMultiple += sectorResult.dayR;

            System.out.printf(
                    "\n  >>> DAY %d SUMMARY: %d Trades | %d Wins | %d Losses | Day Net: %+.2f R (Top Sector: %d Trades, %+.2f R)\n\n",
                    dayIdx + 1,
                    dayResult.trades,
                    dayResult.wins,
                    dayResult.losses,
                    dayResult.dayR,
                    sectorResult.trades,
                    sectorResult.dayR);
        }

        // 3. Final Multi-Day Summary
        System.out.println(
                "==========================================================================================");
        System.out.println("  5-DAY PRACTICE SUMMARY & PERFORMANCE METRICS");
        System.out.println(
                "==========================================================================================");
        System.out.printf("  Total Days Simulated    : %d\n", totalDaysTraded);
        System.out.printf("  Total Trades Triggered  : %d\n", totalTradesTriggered);
        System.out.printf("  Total Wins (>= 1:2)     : %d\n", totalWins);
        System.out.printf("  Total Losses (SL Hit)   : %d\n", totalLosses);
        double winRate =
                (totalTradesTriggered > 0)
                        ? ((double) totalWins / totalTradesTriggered) * 100.0
                        : 0.0;
        System.out.printf("  Win Rate                : %.1f%%\n", winRate);
        System.out.printf("  Cumulative Net Return   : %+.2f R\n", netRMultiple);
        System.out.println(
                "==========================================================================================");
    }

    private record DayResult(int trades, int wins, int losses, double dayR) {}

    private DayResult runDayExecution(
            LowestVolumeReversalService service,
            Map<String, List<Candle>> dayStockCandles,
            LowestVolumeDirection direction,
            boolean verbose) {
        int trades = 0;
        int wins = 0;
        int losses = 0;
        double totalR = 0.0;

        for (Map.Entry<String, List<Candle>> entry : dayStockCandles.entrySet()) {
            String sym = entry.getKey();
            List<Candle> candles = entry.getValue();
            if (candles == null || candles.size() < 4) continue;

            List<LowestVolumePaperPosition> executedTrades =
                    service.replaySession(sym, direction, candles);
            for (LowestVolumePaperPosition pos : executedTrades) {
                trades++;
                double unitRisk =
                        pos.getStockEntryPrice()
                                .subtract(pos.getInitialStockSl())
                                .abs()
                                .doubleValue();
                double pnl = pos.getTotalRealizedPnl().doubleValue();
                double rValue =
                        (unitRisk > 0 && pos.getTotalQuantity() > 0)
                                ? pnl / (unitRisk * pos.getTotalQuantity())
                                : (pnl >= 0 ? 2.0 : -1.0);

                totalR += rValue;
                if (rValue > 0) {
                    wins++;
                } else {
                    losses++;
                }

                if (verbose) {
                    String icon = rValue > 0 ? "🎯" : "🛑";
                    System.out.printf(
                            "    %s [%s] %s (%s) | Entry=%.2f, SL=%.2f -> Exit=%s (%s) |"
                                    + " Realized: %+.2fR (₹%.2f)\n",
                            icon,
                            sym,
                            pos.getTradeId(),
                            pos.getDirection(),
                            pos.getStockEntryPrice().doubleValue(),
                            pos.getInitialStockSl().doubleValue(),
                            pos.getExitReason(),
                            pos.isPartialBooked() ? "50% @ 1:2 + Runner" : "Full",
                            rValue,
                            pnl);
                }
            }
        }

        return new DayResult(trades, wins, losses, totalR);
    }
}
