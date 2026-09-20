package com.tradingbot.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.marketdata.YahooFinanceService;
import com.tradingbot.marketdata.repository.SqliteHistoricalOhlcRepository;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.rsihighway.config.RsiHighwayConfig;
import com.tradingbot.strategy.rsihighway.indicator.MultiTimeframeRsiService;
import com.tradingbot.strategy.rsihighway.indicator.PriceActionPatternDetector;
import com.tradingbot.strategy.rsihighway.model.MarketBreadthSnapshot;
import com.tradingbot.strategy.rsihighway.model.MultiTimeframeRsiSnapshot;
import com.tradingbot.strategy.rsihighway.service.RsiHighwayMarketBreadthService;
import com.tradingbot.util.Nifty500Registry;
import com.tradingbot.util.StockFnoRegistry;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class RsiHighwayLiveScanRunnerTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy").withZone(IST);

    @Test
    void runRsiHighwayScanForToday() throws Exception {
        System.out.println(
                "=========================================================================================");
        System.out.println(
                "                RSI HIGHWAY MULTI-TIMEFRAME SWING STRATEGY LIVE SCAN                    ");
        System.out.println(
                "=========================================================================================");

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        YahooFinanceService yahooService = new YahooFinanceService(mapper);
        SqliteHistoricalOhlcRepository sqliteRepo =
                new SqliteHistoricalOhlcRepository("data/trading_bot.db");
        sqliteRepo.init();

        HistoricalOhlcCacheService ohlcCacheService =
                new HistoricalOhlcCacheService(
                        yahooService, mapper, sqliteRepo, "data/historical_ohlc.json", true);
        ohlcCacheService.init();

        TechnicalAnalysisService taService = new TechnicalAnalysisService();
        PriceActionPatternDetector patternDetector = new PriceActionPatternDetector();
        RsiHighwayConfig config = new RsiHighwayConfig();
        MultiTimeframeRsiService mtfRsiService =
                new MultiTimeframeRsiService(taService, patternDetector, config);
        RsiHighwayMarketBreadthService breadthService = new RsiHighwayMarketBreadthService();

        // Universe of stocks: All Nifty 500 + F&O liquid universe
        Set<String> allSymbols = new LinkedHashSet<>();
        allSymbols.add("NIFTY 50");
        allSymbols.addAll(StockFnoRegistry.getAllInstruments().keySet());
        allSymbols.addAll(Nifty500Registry.getAllMetadata().keySet());

        System.out.println("Universe total symbols: " + allSymbols.size());
        System.out.println("Loading daily historical data...");

        Map<String, List<Candle>> candlesMap = new HashMap<>();
        int count = 0;
        for (String sym : allSymbols) {
            List<Candle> daily = ohlcCacheService.getDailyCandles(sym);
            if (daily != null && !daily.isEmpty()) {
                candlesMap.put(sym, daily);
                count++;
            }
        }
        System.out.println(
                "Successfully loaded historical daily candles for " + count + " symbols.");

        Set<String> focusSymbols =
                Set.of("RADICO", "ZYDUSLIFE", "ANANDRATHI", "NAVINFLUOR", "BHEL", "SHYAMMETL");
        for (String fSym : focusSymbols) {
            List<Candle> cList = candlesMap.get(fSym);
            if (cList != null && cList.size() >= 5) {
                System.out.println("\n--- RECENT CANDLES FOR " + fSym + " ---");
                for (int i = cList.size() - 5; i < cList.size(); i++) {
                    Candle c = cList.get(i);
                    System.out.printf(
                            "Date: %s | O: %.2f | H: %.2f | L: %.2f | C: %.2f | V: %d\n",
                            DATE_FMT.format(c.timestamp()),
                            c.open(),
                            c.high(),
                            c.low(),
                            c.close(),
                            c.volume());
                }
            }
        }

        // 1. Evaluate Market Breadth
        List<Candle> indexCandles = candlesMap.get("NIFTY 50");
        MarketBreadthSnapshot breadth =
                breadthService.evaluateBreadth(
                        candlesMap,
                        indexCandles,
                        config.getMin52wLeaders(),
                        config.getMaxIndexDrawdownPct());

        System.out.println(
                "\n-----------------------------------------------------------------------------------------");
        System.out.println(
                "                             MARKET BREADTH HEALTH CHECK                                 ");
        System.out.println(
                "-----------------------------------------------------------------------------------------");
        System.out.printf(
                "  Highway Status        : %s\n",
                breadth.isHighwayOpen() ? "🟢 OPEN (Entries Permitted)" : "🔴 CLOSED");
        System.out.printf(
                "  52-Week High Leaders  : %d stocks (Threshold: >= %d)\n",
                breadth.leadersNear52WeekHighCount(), config.getMin52wLeaders());
        System.out.printf(
                "  NIFTY 50 Drawdown     : %.2f%% (Max Allowed: %.2f%%)\n",
                breadth.indexDrawdownPct() * 100.0, config.getMaxIndexDrawdownPct() * 100.0);
        System.out.printf("  Diagnosis Reason      : %s\n", breadth.reason());
        if (!breadth.leadersList().isEmpty()) {
            System.out.printf(
                    "  Top 52W Leaders       : %s\n",
                    String.join(
                            ", ",
                            breadth.leadersList()
                                    .subList(0, Math.min(15, breadth.leadersList().size()))));
        }
        System.out.println(
                "-----------------------------------------------------------------------------------------");

        // 2. Scan for Highway Candidates & Entry Triggers
        List<MultiTimeframeRsiSnapshot> allSnapshots = new ArrayList<>();
        List<MultiTimeframeRsiSnapshot> highwayStocks = new ArrayList<>();
        List<MultiTimeframeRsiSnapshot> qualifiedTriggers = new ArrayList<>();

        for (Map.Entry<String, List<Candle>> entry : candlesMap.entrySet()) {
            String sym = entry.getKey();
            if ("NIFTY 50".equalsIgnoreCase(sym)) continue;

            try {
                MultiTimeframeRsiSnapshot snap =
                        mtfRsiService.computeSnapshot(sym, entry.getValue());
                if (snap != null) {
                    allSnapshots.add(snap);
                    if (snap.isHighwayCandidate()) {
                        highwayStocks.add(snap);
                        if (snap.isDailySetupValid()) {
                            qualifiedTriggers.add(snap);
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore calculation errors on illiquid/short-history symbols
            }
        }

        // Sort qualified triggers by proximity to Daily RSI 50
        qualifiedTriggers.sort(
                Comparator.comparingDouble(
                                (MultiTimeframeRsiSnapshot s) -> Math.abs(s.dailyRsi() - 50.0))
                        .thenComparing(
                                (a, b) ->
                                        Double.compare(
                                                b.monthlyRsi() + b.weeklyRsi(),
                                                a.monthlyRsi() + a.weeklyRsi())));

        // Sort highway stocks by strength
        highwayStocks.sort(
                Comparator.comparingDouble(
                                (MultiTimeframeRsiSnapshot s) -> s.monthlyRsi() + s.weeklyRsi())
                        .reversed());

        System.out.println(
                "\n=========================================================================================");
        System.out.println(
                "                 QUALIFIED STOCKS READY FOR ENTRY (PULLBACK IN 48-55 ZONE)               ");
        System.out.println(
                "=========================================================================================");
        System.out.printf(
                "%-14s | %-9s | %-10s | %-10s | %-10s | %-10s | %-15s | %-10s\n",
                "SYMBOL",
                "LTP (₹)",
                "MONTH RSI",
                "WEEK RSI",
                "DAY RSI",
                "DAY ATR",
                "PATTERN",
                "REC STOP LOSS");
        System.out.println(
                "-----------------------------------------------------------------------------------------");

        if (qualifiedTriggers.isEmpty()) {
            System.out.println(
                    "  No stocks currently triggered the exact 48-55 Daily pullback bounce today.");
        } else {
            for (MultiTimeframeRsiSnapshot q : qualifiedTriggers) {
                double sl = Math.max(q.signalCandleLow() - 0.05, q.currentPrice() * 0.92);
                String pat = q.pattern().map(Enum::name).orElse("BOUNCE");
                System.out.printf(
                        "%-14s | %9.2f | %10.2f | %10.2f | %10.2f | %10.2f | %-15s | ₹%9.2f (%.1f%%)\n",
                        q.symbol(),
                        q.currentPrice(),
                        q.monthlyRsi(),
                        q.weeklyRsi(),
                        q.dailyRsi(),
                        q.dailyAtr(),
                        pat,
                        sl,
                        ((sl - q.currentPrice()) / q.currentPrice()) * 100.0);
            }
        }
        System.out.println(
                "-----------------------------------------------------------------------------------------");

        System.out.println(
                "\n=========================================================================================");
        System.out.println(
                "       SUPER-TRENDING HIGHWAY STOCKS (MONTHLY >= 60 & WEEKLY >= 60) - WATCHLIST         ");
        System.out.println(
                "=========================================================================================");
        System.out.printf(
                "%-14s | %-9s | %-10s | %-10s | %-10s | %-10s | %-20s\n",
                "SYMBOL", "LTP (₹)", "MONTH RSI", "WEEK RSI", "DAY RSI", "DAY ATR", "STATUS");
        System.out.println(
                "-----------------------------------------------------------------------------------------");

        int displayLimit = Math.min(25, highwayStocks.size());
        for (int i = 0; i < displayLimit; i++) {
            MultiTimeframeRsiSnapshot s = highwayStocks.get(i);
            String status =
                    s.isDailySetupValid()
                            ? "🟢 READY (BUY)"
                            : (s.dailyRsi() > 55 ? "🟡 EXTENDED (Wait for Dip)" : "⚪ PULLBACK");
            System.out.printf(
                    "%-14s | %9.2f | %10.2f | %10.2f | %10.2f | %10.2f | %-20s\n",
                    s.symbol(),
                    s.currentPrice(),
                    s.monthlyRsi(),
                    s.weeklyRsi(),
                    s.dailyRsi(),
                    s.dailyAtr(),
                    status);
        }
        System.out.println(
                "-----------------------------------------------------------------------------------------");
        System.out.printf(
                "Summary: %d Total Universe Scanned | %d Highway Leaders (M>=60 & W>=60) | %d Qualified Buy Signals\n",
                allSnapshots.size(), highwayStocks.size(), qualifiedTriggers.size());
        System.out.println(
                "=========================================================================================");
    }
}
