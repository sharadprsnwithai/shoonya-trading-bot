package com.tradingbot.runner;

import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Startup runner that automatically authenticates and fetches 5-day OHLC benchmark data on
 * application startup.
 */
@Component
public class StartupSyncRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupSyncRunner.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss").withZone(IST);

    private final ShoonyaConfig config;
    private final ShoonyaAuthenticator authenticator;
    private final ShoonyaMarketDataService marketDataService;

    public StartupSyncRunner(
            ShoonyaConfig config,
            ShoonyaAuthenticator authenticator,
            ShoonyaMarketDataService marketDataService) {
        this.config = config;
        this.authenticator = authenticator;
        this.marketDataService = marketDataService;
    }

    @Override
    public void run(String... args) {
        log.info("==================================================================");
        log.info("               SHOONYA TRADING BOT - STARTUP                      ");
        log.info("==================================================================");
        log.info(
                "Configuration loaded. User: {}, Base URL: {}",
                config.getUserId(),
                config.getBaseUrl());

        try {
            // 1. Authenticate / Login
            log.info("[1/2] Authenticating with Shoonya (Finvasia NorenAPI)...");
            String sessionToken = authenticator.getOrAuthenticateToken();
            log.info(
                    "[1/2] Shoonya Authentication Successful! Session Token: {}...",
                    sessionToken.length() > 8 ? sessionToken.substring(0, 8) + "***" : "***");

            // 2. Fetch OHLC data for last 5 days on startup
            log.info("[2/2] Fetching OHLC data of the last 5 days on startup...");

            // Default startup instruments: F&O Benchmark Basket + NIFTY 50
            record InstrumentTarget(
                    String exchange, String token, String symbol, String timeframe) {}
            List<InstrumentTarget> targets =
                    List.of(
                            new InstrumentTarget("NFO", "68407", "NIFTY50", "5"),
                            new InstrumentTarget("NSE", "2885", "NSE:RELIANCE", "5"),
                            new InstrumentTarget("NSE", "11536", "NSE:TCS", "5"),
                            new InstrumentTarget("NSE", "1594", "NSE:INFY", "5"));

            int daysBack = 5;
            for (InstrumentTarget target : targets) {
                log.info(
                        "Fetching {} days of {}m OHLC candles for {} (token: {})...",
                        daysBack,
                        target.timeframe(),
                        target.symbol(),
                        target.token());

                List<Candle> candles =
                        marketDataService.fetchHistoricalCandles(
                                target.exchange(),
                                target.token(),
                                target.symbol(),
                                target.timeframe(),
                                daysBack);

                displayCandleSummary(target.symbol(), target.timeframe(), candles);
                // Respect Shoonya API rate limit (350ms between requests)
                Thread.sleep(350);
            }

        } catch (Exception e) {
            log.warn("Shoonya Startup Sync Notice: {}", e.getMessage());
        }

        log.info("==================================================================");
        log.info("     SHOONYA TRADING BOT READY - REST API LIVE ON PORT 8080       ");
        log.info("==================================================================");
    }

    private static void displayCandleSummary(
            String symbol, String timeframe, List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            log.warn("  [!] No candles retrieved for {}", symbol);
            return;
        }

        Candle first = candles.get(0);
        Candle latest = candles.get(candles.size() - 1);

        log.info("  ---------------------------------------------------------------");
        log.info(
                "  Symbol: {} (Timeframe: {}m) | Total Candles: {}",
                symbol,
                timeframe,
                candles.size());
        log.info(
                "  Range: {} -> {}",
                TIME_FMT.format(first.timestamp()),
                TIME_FMT.format(latest.timestamp()));
        log.info(
                "  First  OHLCV: O={} H={} L={} C={} V={}",
                first.open(),
                first.high(),
                first.low(),
                first.close(),
                first.volume());
        log.info(
                "  Latest OHLCV: O={} H={} L={} C={} V={}",
                latest.open(),
                latest.high(),
                latest.low(),
                latest.close(),
                latest.volume());
        log.info("  ---------------------------------------------------------------");
    }
}
