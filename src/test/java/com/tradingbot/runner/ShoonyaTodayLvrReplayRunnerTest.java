package com.tradingbot.runner;

import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.LowestVolumeSetup;
import com.tradingbot.model.strategy.LowestVolumeSetupState;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
import com.tradingbot.service.LowestVolumeReversalScanner;
import com.tradingbot.service.LowestVolumeReversalService;
import com.tradingbot.util.Nifty200Registry;
import com.tradingbot.util.NiftySectorRegistry;
import com.tradingbot.util.StockFnoRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
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

    @Test
    @DisplayName("Replay today's Shoonya 5m data through Kushal Varshney LVR Framework")
    void testReplayToday5mCandles() {
        System.out.println(
                "==========================================================================");
        System.out.println("  SHOONYA 5-MIN LIVE CANDLE REPLAY - KUSHAL VARSHNEY LVR FRAMEWORK");
        System.out.println(
                "==========================================================================");

        // 1. Fetch 5m candles for Nifty 50 and Sector Stocks
        Map<String, List<Candle>> stockCandlesToday = new HashMap<>();

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
                        + " sector F&O stocks from Shoonya...");

        LocalDate today = LocalDate.now(IST);

        for (String sym : allUniverseSymbols) {
            try {
                List<Candle> candles = marketDataService.fetch5MinCandles(sym, 1);
                if (candles != null && !candles.isEmpty()) {
                    List<Candle> todayList =
                            candles.stream()
                                    .filter(
                                            c ->
                                                    LocalDate.ofInstant(c.timestamp(), IST)
                                                                    .equals(today)
                                                            || today.getDayOfWeek().getValue() > 5)
                                    .toList();

                    if (!todayList.isEmpty()) {
                        stockCandlesToday.put(sym, todayList);
                    }
                }
                Thread.sleep(20);
            } catch (Exception e) {
                // ignore
            }
        }

        System.out.println(
                "[REPLAY] Successfully loaded today's 5m candles for "
                        + stockCandlesToday.size()
                        + " stocks.");

        // 2. Step 1: 09:25 IST Sentiment & Sector Ranking Simulation
        System.out.println(
                "\n--------------------------------------------------------------------------");
        System.out.println(" STEP 1: 09:25 AM IST MARKET SENTIMENT & SECTOR RANKING");
        System.out.println(
                "--------------------------------------------------------------------------");

        List<StockQuoteSnapshot> niftyQuotes0925 = new ArrayList<>();
        List<String> nifty50Symbols =
                Nifty200Registry.getNifty200Symbols().stream().limit(50).toList();

        for (String sym : nifty50Symbols) {
            List<Candle> candles = stockCandlesToday.get(sym);
            if (candles != null && candles.size() >= 2) {
                Candle c1 = candles.get(0);
                Candle c2 = candles.get(1);
                double open = c1.open().doubleValue();
                double ltp = c2.close().doubleValue();
                double pct = (open > 0) ? ((ltp - open) / open) * 100.0 : 0.0;
                niftyQuotes0925.add(new StockQuoteSnapshot(sym, ltp, open, open, pct));
            }
        }

        LowestVolumeReversalScanner scanner = new LowestVolumeReversalScanner();
        LowestVolumeDirection sentiment = scanner.evaluateMarketSentiment(niftyQuotes0925);
        long adv = niftyQuotes0925.stream().filter(q -> q.pctChange() > 0).count();
        long dec = niftyQuotes0925.stream().filter(q -> q.pctChange() < 0).count();

        System.out.printf(
                "09:25 IST NIFTY 50 Breadth: %d Advances | %d Declines -> Sentiment: %s\n\n",
                adv, dec, sentiment);

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
                    double pct = (open > 0) ? ((ltp - open) / open) * 100.0 : 0.0;
                    sQuotes.add(new StockQuoteSnapshot(sym, ltp, open, open, pct));
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
            List<StockQuoteSnapshot> topSectorStockQuotes =
                    sectorQuotes0925.getOrDefault(winningSector.sectorName(), List.of());
            winningCandidates = scanner.filterCandidateStocks(topSectorStockQuotes, sentiment);
            System.out.println("Candidate Stocks Filtered: " + winningCandidates);
        }

        // Detailed Breakdown for Winning Sector Candidates
        System.out.println(
                "\n--------------------------------------------------------------------------");
        System.out.println(" STEP 2: DETAILED 5-MIN CANDLE BREAKDOWN FOR WINNING CANDIDATES");
        System.out.println(
                "--------------------------------------------------------------------------");
        for (String sym : winningCandidates) {
            printCandleDetails(sym, stockCandlesToday.get(sym), sentiment);
        }

        // 3. Step 3: Scan all 107 F&O stocks across BOTH LONG & SHORT directions
        System.out.println(
                "\n--------------------------------------------------------------------------");
        System.out.println(
                " STEP 3: UNIVERSE-WIDE SIGNAL AUDIT (LONG & SHORT SETUPS ACROSS ALL 107 STOCKS)");
        System.out.println(
                "--------------------------------------------------------------------------");

        System.out.println(
                "\n--- [A] LONG SETUPS (Pullback on Red Candle with Volume < Baseline) ---");
        int longTriggers = auditUniverse(stockCandlesToday, LowestVolumeDirection.LONG);

        System.out.println(
                "\n--- [B] SHORT SETUPS (Pullback on Green Candle with Volume < Baseline) ---");
        int shortTriggers = auditUniverse(stockCandlesToday, LowestVolumeDirection.SHORT);

        System.out.println(
                "\n==========================================================================");
        System.out.printf(
                "  SUMMARY: %d LONG Trades Triggered | %d SHORT Trades Triggered\n",
                longTriggers, shortTriggers);
        System.out.println(
                "==========================================================================");
    }

    private void printCandleDetails(
            String symbol, List<Candle> candles, LowestVolumeDirection dir) {
        if (candles == null || candles.isEmpty()) {
            System.out.printf("\n[CANDLE-DETAILS] %s: No candles found.\n", symbol);
            return;
        }

        System.out.printf(
                "\n================ %s (Direction: %s | Total Candles: %d) ================\n",
                symbol, dir, candles.size());
        if (candles.size() < 3) return;

        long baseline =
                Math.min(
                        candles.get(0).volume(),
                        Math.min(candles.get(1).volume(), candles.get(2).volume()));
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

    private int auditUniverse(
            Map<String, List<Candle>> stockCandlesToday, LowestVolumeDirection direction) {
        int triggersFound = 0;
        int armedSetupsFound = 0;

        for (Map.Entry<String, List<Candle>> entry : stockCandlesToday.entrySet()) {
            String sym = entry.getKey();
            List<Candle> candles = entry.getValue();
            if (candles == null || candles.size() < 4) continue;

            long baselineLowest =
                    Math.min(
                            candles.get(0).volume(),
                            Math.min(candles.get(1).volume(), candles.get(2).volume()));
            long rollingLowest = baselineLowest;

            LowestVolumeSetup activeSetup = null;
            boolean inTrade = false;
            BigDecimal entryPrice = null;
            BigDecimal slPrice = null;
            BigDecimal targetPrice = null;
            boolean partialBooked = false;

            for (int i = 3; i < candles.size(); i++) {
                Candle c = candles.get(i);
                LocalTime candleTime = LocalTime.ofInstant(c.timestamp(), IST);
                boolean isOpposite =
                        (direction == LowestVolumeDirection.SHORT) ? c.isGreen() : c.isRed();

                // Active Trade Exits
                if (inTrade) {
                    boolean slHit = false;
                    boolean targetHit = false;

                    if (direction == LowestVolumeDirection.SHORT) {
                        if (c.high().compareTo(slPrice) >= 0) slHit = true;
                        if (c.low().compareTo(targetPrice) <= 0) targetHit = true;
                    } else {
                        if (c.low().compareTo(slPrice) <= 0) slHit = true;
                        if (c.high().compareTo(targetPrice) >= 0) targetHit = true;
                    }

                    if (slHit) {
                        System.out.printf(
                                "        [%s IST] 🛑 STOP LOSS HIT for %s at %.2f (SL was %.2f)\n",
                                TIME_FMT.format(c.timestamp()),
                                sym,
                                slPrice.doubleValue(),
                                slPrice.doubleValue());
                        inTrade = false;
                        activeSetup = null;
                    } else if (targetHit && !partialBooked) {
                        partialBooked = true;
                        slPrice = entryPrice; // Move SL to Cost
                        System.out.printf(
                                "        [%s IST] 🎯 1:4 TARGET REACHED (50%% Booked) for %s at %.2f | SL moved to Cost (%.2f)\n",
                                TIME_FMT.format(c.timestamp()),
                                sym,
                                targetPrice.doubleValue(),
                                slPrice.doubleValue());
                    }

                    if (candleTime.isAfter(LocalTime.of(15, 10)) && inTrade) {
                        System.out.printf(
                                "        [%s IST] 🏁 15:15 HARD EOD EXIT for %s at Close %.2f (Entry was %.2f)\n",
                                TIME_FMT.format(c.timestamp()),
                                sym,
                                c.close().doubleValue(),
                                entryPrice.doubleValue());
                        inTrade = false;
                        break;
                    }
                    continue;
                }

                // Check Trigger Breach
                if (activeSetup != null
                        && activeSetup.getState() == LowestVolumeSetupState.TRIGGER_ARMED) {
                    boolean breached = false;
                    if (direction == LowestVolumeDirection.SHORT
                            && c.low().compareTo(activeSetup.getTriggerPrice()) <= 0) {
                        breached = true;
                    } else if (direction == LowestVolumeDirection.LONG
                            && c.high().compareTo(activeSetup.getTriggerPrice()) >= 0) {
                        breached = true;
                    }

                    if (breached) {
                        inTrade = true;
                        entryPrice = activeSetup.getTriggerPrice();
                        slPrice = activeSetup.getStopLossPrice();
                        targetPrice = activeSetup.getTarget1Price();
                        partialBooked = false;
                        triggersFound++;

                        String optType = (direction == LowestVolumeDirection.SHORT) ? "PE" : "CE";
                        BigDecimal strikeStep = StockFnoRegistry.getStrikeStep(sym, entryPrice);
                        BigDecimal atmStrike =
                                entryPrice
                                        .divide(strikeStep, 0, RoundingMode.HALF_UP)
                                        .multiply(strikeStep);

                        System.out.printf(
                                "\n    🔥 [%s IST] TRADE TRIGGERED: %s %s | BUY %s ATM %.0f%s (Spot Entry: %.2f, SL: %.2f, 1:4 Target: %.2f)\n",
                                TIME_FMT.format(c.timestamp()),
                                sym,
                                direction,
                                sym,
                                atmStrike.doubleValue(),
                                optType,
                                entryPrice.doubleValue(),
                                slPrice.doubleValue(),
                                targetPrice.doubleValue());
                        activeSetup = null;
                        continue;
                    }
                }

                // Arming / Trailing Check
                if (candleTime.isBefore(LocalTime.of(13, 0))) {
                    if (isOpposite && c.volume() < rollingLowest) {
                        rollingLowest = c.volume();
                        activeSetup = new LowestVolumeSetup(sym, direction);

                        BigDecimal trg, sl, tgt;
                        if (direction == LowestVolumeDirection.SHORT) {
                            trg = c.low().subtract(BigDecimal.valueOf(0.05));
                            sl = c.high().add(BigDecimal.valueOf(0.05));
                            BigDecimal risk = sl.subtract(trg);
                            tgt = trg.subtract(risk.multiply(BigDecimal.valueOf(4)));
                        } else {
                            trg = c.high().add(BigDecimal.valueOf(0.05));
                            sl = c.low().subtract(BigDecimal.valueOf(0.05));
                            BigDecimal risk = trg.subtract(sl);
                            tgt = trg.add(risk.multiply(BigDecimal.valueOf(4)));
                        }

                        activeSetup.setTriggerCandle(c, trg, sl, tgt);
                        activeSetup.setDayLowestVolume(rollingLowest);
                        armedSetupsFound++;

                        System.out.printf(
                                "      * [%s IST] Setup Armed on %s: %s candle Vol=%,d (< lowest %,d) | Trigger=%.2f, SL=%.2f, 1:4 Target=%.2f\n",
                                TIME_FMT.format(c.timestamp()),
                                sym,
                                c.isGreen() ? "GREEN" : "RED",
                                c.volume(),
                                rollingLowest,
                                trg.doubleValue(),
                                sl.doubleValue(),
                                tgt.doubleValue());
                    } else if (c.volume() < rollingLowest) {
                        rollingLowest = c.volume();
                    }
                }
            }
        }

        System.out.printf(
                "  -> Total Armed Setups: %d | Total Executed Triggers: %d\n",
                armedSetupsFound, triggersFound);
        return triggersFound;
    }
}
