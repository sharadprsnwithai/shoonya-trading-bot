package com.tradingbot.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.marketdata.YahooFinanceService;
import com.tradingbot.marketdata.repository.SqliteHistoricalOhlcRepository;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.kiss.config.KissStrategyConfig;
import com.tradingbot.strategy.kiss.indicator.KissIndicatorService;
import com.tradingbot.strategy.kiss.model.KissSnapshot;
import com.tradingbot.strategy.kiss.service.KissSwingService;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.CommodityRegistry;
import com.tradingbot.util.Nifty200Registry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class KissStrategyLiveScanRunnerTest {

    @Test
    void runLiveKissStrategyScan() throws Exception {
        System.out.println(
                "=========================================================================================");
        System.out.println(
                "            KISS (Keep It Swing Systematic) MULTI-TIMEFRAME LIVE SCANNER                 ");
        System.out.println(
                "        Higher TF: Weekly HA Filter | Trigger TF: 1-Hour HA 55-EMA Bands & MACD         ");
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
        KissStrategyConfig config = new KissStrategyConfig();
        config.setScanDelayMs(10); // fast scan for runner test
        KissIndicatorService indicatorService = new KissIndicatorService(taService, config);
        TelegramService telegramService = Mockito.mock(TelegramService.class);

        KissSwingService swingService =
                new KissSwingService(
                        config,
                        indicatorService,
                        ohlcCacheService,
                        yahooService,
                        telegramService,
                        mapper);
        swingService.init();

        List<String> scanUniverse = new ArrayList<>();
        // 1. Add Commodities
        scanUniverse.addAll(CommodityRegistry.getAllSymbols());
        // 2. Add Top Nifty 200 Stocks
        scanUniverse.addAll(Nifty200Registry.getNifty200Symbols());

        System.out.printf(
                "Scanning %d instruments across Nifty 200 and Commodities...\n",
                scanUniverse.size());

        List<KissSnapshot> bullishSetups = new ArrayList<>();
        List<KissSnapshot> bearishSetups = new ArrayList<>();
        List<KissSnapshot> activeTracking = new ArrayList<>();

        int processed = 0;
        for (String symbol : scanUniverse) {
            try {
                List<Candle> hourly = swingService.loadHourlyCandles(symbol);
                List<Candle> daily = swingService.loadDailyCandles(symbol);

                if (hourly.isEmpty() || daily.isEmpty()) {
                    continue;
                }

                KissSnapshot snapshot = indicatorService.computeSnapshot(symbol, hourly, daily);
                if (snapshot != null) {
                    activeTracking.add(snapshot);
                    if (snapshot.isBullishSetup()) {
                        bullishSetups.add(snapshot);
                    } else if (snapshot.isBearishSetup()) {
                        bearishSetups.add(snapshot);
                    }
                }
                processed++;
            } catch (Exception e) {
                // Ignore transient network errors
            }
        }

        System.out.printf("\nProcessed %d instruments with valid data.\n", processed);
        System.out.printf("Found %d 🟢 LONG (Buy Futures) Setups.\n", bullishSetups.size());
        System.out.printf("Found %d 🔴 SHORT (Sell Futures) Setups.\n\n", bearishSetups.size());

        System.out.println(
                "----------------------------------------------------------------------------------------------------------------------------------");
        System.out.printf(
                "%-12s %-6s %-10s %-10s %-10s %-10s %-10s %-10s %-10s %-10s\n",
                "Symbol",
                "Type",
                "LTP",
                "SL",
                "Target",
                "55 EMA H",
                "55 EMA L",
                "MACD",
                "Signal",
                "Weekly HA");
        System.out.println(
                "----------------------------------------------------------------------------------------------------------------------------------");

        for (KissSnapshot s : bullishSetups) {
            System.out.printf(
                    "%-12s %-6s %-10.2f %-10.2f %-10.2f %-10.2f %-10.2f %-10.2f %-10.2f %-10s\n",
                    s.symbol(),
                    "LONG",
                    s.currentPrice(),
                    s.suggestedSl(),
                    s.suggestedTarget(),
                    s.ema55High(),
                    s.ema55Low(),
                    s.macdLine(),
                    s.macdSignal(),
                    s.weeklyHaBullish() ? "🟢 GREEN" : "🔴 RED");
        }

        for (KissSnapshot s : bearishSetups) {
            System.out.printf(
                    "%-12s %-6s %-10.2f %-10.2f %-10.2f %-10.2f %-10.2f %-10.2f %-10.2f %-10s\n",
                    s.symbol(),
                    "SHORT",
                    s.currentPrice(),
                    s.suggestedSl(),
                    s.suggestedTarget(),
                    s.ema55High(),
                    s.ema55Low(),
                    s.macdLine(),
                    s.macdSignal(),
                    s.weeklyHaBullish() ? "🟢 GREEN" : "🔴 RED");
        }
        System.out.println(
                "=========================================================================================\n");
    }
}
