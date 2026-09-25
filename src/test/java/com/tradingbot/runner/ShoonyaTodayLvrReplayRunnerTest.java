package com.tradingbot.runner;

import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.LowestVolumePaperPosition;
import com.tradingbot.model.strategy.LowestVolumeSetup;
import com.tradingbot.model.strategy.LvrExitMode;
import com.tradingbot.model.strategy.LvrInstrumentType;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
import com.tradingbot.service.LowestVolumeReversalScanner;
import com.tradingbot.service.LowestVolumeReversalService;
import com.tradingbot.util.NiftySectorRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Replay Runner for Lowest Volume Reversal (LVR) strategy. Executes live 5-minute Shoonya candle
 * feeds directly through the actual LowestVolumeReversalService and LowestVolumeReversalScanner
 * production beans.
 */
@SpringBootTest
class ShoonyaTodayLvrReplayRunnerTest {

    private static final Logger log =
            LoggerFactory.getLogger(ShoonyaTodayLvrReplayRunnerTest.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm").withZone(IST);

    @Autowired private ShoonyaMarketDataService marketDataService;
    @Autowired private ShoonyaAuthenticator authenticator;
    @Autowired private LowestVolumeReversalService lvrService;
    @Autowired private LowestVolumeReversalScanner scanner;

    @Test
    @DisplayName("Dedicated Backtest of Morning Candidate Stocks through Actual LVR Service")
    void testBacktestTodayStocksIdentified() {
        System.out.println(
                "\n==========================================================================");
        System.out.println("  LVR BACKTEST: MORNING CANDIDATE STOCKS VIA PRODUCTION SERVICE LOGIC");
        System.out.println(
                "==========================================================================");

        List<String> candidateStocks =
                List.of("SUNPHARMA", "LAURUSLABS", "TORNTPHARM", "AUROPHARMA");
        Map<String, List<Candle>> candlesMap = fetchSessionCandlesForStocks(candidateStocks);

        // Run Production Stock Futures Mode (100% Full Exit at 1:2 Target)
        System.out.println(
                "\n--- [A] PRODUCTION MODE: STOCK FUTURES (100% Full Exit at 1:2 Target) ---");
        lvrService.setInstrumentType(LvrInstrumentType.FUTURES);
        lvrService.setExitMode(LvrExitMode.FULL_TARGET_1_2);
        lvrService.setTelegramAlerts(false);

        runServiceReplay(candidateStocks, candlesMap, LowestVolumeDirection.LONG);

        // Run Options Mode (50% Partial at 1:2 Target + 10 EMA Trailing Runner)
        System.out.println(
                "\n--- [B] COMPARISON MODE: ATM OPTIONS (50% Partial + 10 EMA Trail) ---");
        lvrService.setInstrumentType(LvrInstrumentType.OPTIONS);
        lvrService.setExitMode(LvrExitMode.PARTIAL_RUNNER_10EMA);

        runServiceReplay(candidateStocks, candlesMap, LowestVolumeDirection.LONG);

        // Reset to production defaults
        lvrService.setInstrumentType(LvrInstrumentType.FUTURES);
        lvrService.setExitMode(LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500);
    }

    @Test
    @DisplayName("Replay Full Universe of 107 Sector Stocks through Actual LVR Service")
    void testReplayToday5mCandles() {
        System.out.println(
                "\n==========================================================================");
        System.out.println("  SHOONYA 5-MIN CANDLE REPLAY - SECTOR SCANNER & UNIVERSE AUDIT");
        System.out.println(
                "==========================================================================");

        List<String> allUniverseSymbols = new ArrayList<>();
        for (List<String> sectorStocks : NiftySectorRegistry.getSectorConstituents().values()) {
            for (String s : sectorStocks) {
                if (!allUniverseSymbols.contains(s)) {
                    allUniverseSymbols.add(s);
                }
            }
        }

        System.out.println(
                "[REPLAY] Fetching 5m candles for "
                        + allUniverseSymbols.size()
                        + " sector F&O stocks...");
        Map<String, List<Candle>> stockCandlesToday =
                fetchSessionCandlesForStocks(allUniverseSymbols);

        // Step 1: 09:25 IST Morning Sentiment & Sector Scan using Actual
        // LowestVolumeReversalScanner
        System.out.println(
                "\n--------------------------------------------------------------------------");
        System.out.println(" STEP 1: 09:25 AM MORNING SENTIMENT & SECTOR SCAN VIA SCANNER BEAN");
        System.out.println(
                "--------------------------------------------------------------------------");

        List<StockQuoteSnapshot> niftyQuotes0925 = new ArrayList<>();
        for (String sym : NiftySectorRegistry.NIFTY_50_CONSTITUENTS) {
            List<Candle> candles = stockCandlesToday.get(sym);
            if (candles != null && candles.size() >= 2) {
                double open = candles.get(0).open().doubleValue();
                double ltp = candles.get(1).close().doubleValue();
                if (open > 0) {
                    double pct = (ltp - open) / open * 100.0;
                    niftyQuotes0925.add(new StockQuoteSnapshot(sym, ltp, open, open, pct));
                }
            }
        }

        LowestVolumeDirection sentiment = scanner.evaluateMarketSentiment(niftyQuotes0925);
        System.out.printf(
                "Market Sentiment at 09:25 IST: %s (NIFTY 50 Adv/Dec: %d / %d)\n",
                sentiment,
                niftyQuotes0925.stream().filter(q -> q.pctChange() > 0).count(),
                niftyQuotes0925.stream().filter(q -> q.pctChange() < 0).count());

        Map<String, List<StockQuoteSnapshot>> sectorQuotes0925 = new HashMap<>();
        for (Map.Entry<String, List<String>> entry :
                NiftySectorRegistry.getSectorConstituents().entrySet()) {
            String sectorName = entry.getKey();
            List<StockQuoteSnapshot> sQuotes = new ArrayList<>();
            for (String sym : entry.getValue()) {
                List<Candle> candles = stockCandlesToday.get(sym);
                if (candles != null && candles.size() >= 2) {
                    double open = candles.get(0).open().doubleValue();
                    double ltp = candles.get(1).close().doubleValue();
                    if (open > 0) {
                        double pct = (ltp - open) / open * 100.0;
                        sQuotes.add(new StockQuoteSnapshot(sym, ltp, open, open, pct));
                    }
                }
            }
            sectorQuotes0925.put(sectorName, sQuotes);
        }

        List<LowestVolumeReversalScanner.SectorRankResult> rankedSectors =
                scanner.rankSectors(sectorQuotes0925, sentiment);
        System.out.println("11 Sector Rankings at 09:25 IST:");
        for (int i = 0; i < rankedSectors.size(); i++) {
            LowestVolumeReversalScanner.SectorRankResult sr = rankedSectors.get(i);
            System.out.printf("  #%d %-20s : %+.2f%%\n", i + 1, sr.sectorName(), sr.pctChange());
        }

        LowestVolumeReversalScanner.SectorRankResult winningSector =
                rankedSectors.isEmpty() ? null : rankedSectors.get(0);
        List<String> winningCandidates = new ArrayList<>();
        if (winningSector != null) {
            System.out.printf(
                    "\n🏆 WINNING SECTOR: %s (%+.2f%%)\n",
                    winningSector.sectorName(), winningSector.pctChange());
            List<StockQuoteSnapshot> topSectorQuotes =
                    sectorQuotes0925.getOrDefault(winningSector.sectorName(), List.of());
            winningCandidates = scanner.filterCandidateStocks(topSectorQuotes, sentiment);
            System.out.println("Candidate Stocks Filtered: " + winningCandidates);
        }

        // Step 2: Detailed 5-min Candle Setup Evaluation for Winning Sector Candidates
        System.out.println(
                "\n--------------------------------------------------------------------------");
        System.out.println(" STEP 2: 5-MIN CANDLE BREAKDOWN FOR WINNING SECTOR CANDIDATES");
        System.out.println(
                "--------------------------------------------------------------------------");
        for (String sym : winningCandidates) {
            printCandleSequenceDetails(sym, stockCandlesToday.get(sym), sentiment);
        }

        // Step 3: Run Full Sector Universe Backtest via LowestVolumeReversalService.replaySession
        System.out.println(
                "\n--------------------------------------------------------------------------");
        System.out.println(
                " STEP 3: UNIVERSE-WIDE TRADE REPLAY THROUGH LOWESTVOLUMEREVERSALSERVICE");
        System.out.println(
                "--------------------------------------------------------------------------");

        lvrService.setInstrumentType(LvrInstrumentType.FUTURES);
        lvrService.setExitMode(LvrExitMode.FULL_TARGET_1_4);
        lvrService.setTelegramAlerts(false);

        System.out.println("\n>>> [LONG REPLAY ACROSS ALL 107 STOCKS] <<<");
        runServiceReplay(allUniverseSymbols, stockCandlesToday, LowestVolumeDirection.LONG);

        System.out.println("\n>>> [SHORT REPLAY ACROSS ALL 107 STOCKS] <<<");
        runServiceReplay(allUniverseSymbols, stockCandlesToday, LowestVolumeDirection.SHORT);
    }

    private void runServiceReplay(
            List<String> symbols,
            Map<String, List<Candle>> candlesMap,
            LowestVolumeDirection direction) {
        double totalRupeesPnl = 0.0;
        double totalR = 0.0;
        int totalTrades = 0;
        int wins = 0;
        int losses = 0;

        for (String sym : symbols) {
            List<Candle> sessionCandles = candlesMap.get(sym);
            if (sessionCandles == null || sessionCandles.size() < 4) {
                continue;
            }

            List<LowestVolumePaperPosition> trades =
                    lvrService.replaySession(sym, direction, sessionCandles);
            for (LowestVolumePaperPosition pos : trades) {
                totalTrades++;
                double pnl = pos.getTotalRealizedPnl().doubleValue();
                totalRupeesPnl += pnl;

                double unitRisk =
                        pos.getStockEntryPrice()
                                .subtract(pos.getInitialStockSl())
                                .abs()
                                .doubleValue();
                boolean isOptions = pos.getInstrumentType() == LvrInstrumentType.OPTIONS;
                BigDecimal entryPrc = isOptions ? pos.getEntryPremium() : pos.getStockEntryPrice();
                BigDecimal exitPrc =
                        (pos.getRunnerExitPrice() != null) ? pos.getRunnerExitPrice() : entryPrc;
                double ptsCaptured =
                        isOptions
                                ? (exitPrc.subtract(entryPrc).doubleValue())
                                : ((pos.getDirection() == LowestVolumeDirection.LONG)
                                        ? (exitPrc.subtract(entryPrc).doubleValue())
                                        : (entryPrc.subtract(exitPrc).doubleValue()));
                double rValue = (unitRisk > 0 && !isOptions) ? (ptsCaptured / unitRisk) : 0.0;

                if (pos.getExitReason().contains("TARGET_1_4")) {
                    wins++;
                    rValue = (pos.getExitMode() == LvrExitMode.FULL_TARGET_1_4) ? 4.0 : 2.0;
                } else if (pos.getExitReason().contains("SL")) {
                    losses++;
                    rValue = pos.isPartialBooked() ? 0.0 : -1.0;
                } else if (isOptions && pos.isPartialBooked()) {
                    wins++;
                    rValue = 2.0 + (ptsCaptured / (unitRisk > 0 ? unitRisk * 0.5 : 1.0));
                }
                totalR += rValue;

                System.out.printf(
                        "  %-10s | %-7s | %-10s | Entry=%8.2f | SL=%8.2f | Target=%8.2f | Exit=%8.2f | Reason=%-20s | Pts=%+6.2f | %+.1fR | P&L: ₹%+.2f\n",
                        pos.getSymbol(),
                        pos.getDirection(),
                        pos.getInstrumentType(),
                        entryPrc.doubleValue(),
                        pos.getInitialStockSl().doubleValue(),
                        pos.getTarget1StockPrice().doubleValue(),
                        exitPrc.doubleValue(),
                        pos.getExitReason(),
                        ptsCaptured,
                        rValue,
                        pnl);
            }
        }

        System.out.println(
                "  ----------------------------------------------------------------------------------------------------------------------------");
        System.out.printf(
                "  TOTALS: Trades: %d | Wins: %d | Losses: %d | Win Rate: %.1f%% | Net R: %+.2f R | Net Realized P&L: ₹%+.2f\n",
                totalTrades,
                wins,
                losses,
                totalTrades > 0 ? (wins * 100.0 / totalTrades) : 0.0,
                totalR,
                totalRupeesPnl);
        System.out.println(
                "  ----------------------------------------------------------------------------------------------------------------------------");
    }

    private void printCandleSequenceDetails(
            String symbol, List<Candle> candles, LowestVolumeDirection dir) {
        if (candles == null || candles.isEmpty()) {
            System.out.printf("\n[CANDLE-DETAILS] %s: No candles found.\n", symbol);
            return;
        }

        System.out.printf(
                "\n================ %s (Direction: %s | Total Candles: %d) ================\n",
                symbol, dir, candles.size());
        if (candles.size() < 3) return;

        LowestVolumeSetup initialSetup =
                lvrService.evaluateCandleSequence(symbol, dir, candles.subList(0, 3));
        long baseline = initialSetup.getDayLowestVolume();
        System.out.printf(
                "  * Baseline lowest volume (C1..C3): %,d (C1=%,d, C2=%,d, C3=%,d)\n",
                baseline,
                candles.get(0).volume(),
                candles.get(1).volume(),
                candles.get(2).volume());

        long rolling = baseline;
        for (int i = 0; i < Math.min(candles.size(), 20); i++) {
            Candle c = candles.get(i);
            String time = TIME_FMT.format(c.timestamp());
            String color = c.isGreen() ? "GREEN" : (c.isRed() ? "RED  " : "DOJI ");
            boolean isOpposite = (dir == LowestVolumeDirection.LONG) ? c.isRed() : c.isGreen();
            boolean lowVol = (i >= 3) && (c.volume() < rolling);

            String tag =
                    (i < 3)
                            ? "[BASELINE IGNORED]"
                            : (isOpposite && lowVol
                                    ? ">>> ARMED TRIGGER <<<"
                                    : (lowVol ? "[NEW LOW VOL]" : ""));
            System.out.printf(
                    "  C%-2d [%s IST] %s | O=%7.2f H=%7.2f L=%7.2f C=%7.2f | Vol=%9d | %s\n",
                    i + 1,
                    time,
                    color,
                    c.open().doubleValue(),
                    c.high().doubleValue(),
                    c.low().doubleValue(),
                    c.close().doubleValue(),
                    c.volume(),
                    tag);

            if (i >= 3 && c.volume() < rolling) {
                rolling = c.volume();
            }
        }
    }

    private Map<String, List<Candle>> fetchSessionCandlesForStocks(List<String> symbols) {
        Map<String, List<Candle>> map = new HashMap<>();
        for (String sym : symbols) {
            try {
                List<Candle> rawCandles = marketDataService.fetch5MinCandles(sym, 8);
                if (rawCandles != null && !rawCandles.isEmpty()) {
                    LocalDate targetDate =
                            LocalDate.ofInstant(
                                    rawCandles.get(rawCandles.size() - 1).timestamp(), IST);
                    List<Candle> sessionCandles =
                            rawCandles.stream()
                                    .filter(
                                            c ->
                                                    LocalDate.ofInstant(c.timestamp(), IST)
                                                            .equals(targetDate))
                                    .toList();
                    if (!sessionCandles.isEmpty()) {
                        map.put(sym, sessionCandles);
                    }
                }
            } catch (Exception e) {
                log.debug("Error fetching candles for {}: {}", sym, e.getMessage());
            }
        }
        return map;
    }
}
