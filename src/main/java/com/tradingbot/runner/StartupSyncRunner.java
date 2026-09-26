package com.tradingbot.runner;

import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.execution.gateway.ShoonyaBrokerGateway;
import com.tradingbot.execution.gateway.ZerodhaBrokerGateway;
import com.tradingbot.kite.auth.KiteAuthService;
import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.execution.BrokerPosition;
import com.tradingbot.telegram.TelegramService;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Startup runner that automatically authenticates, fetches active positions in Cash and Derivatives
 * for all configured brokers, and synchronizes benchmark OHLC data.
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
    private final HistoricalOhlcCacheService ohlcCacheService;
    private final ShoonyaBrokerGateway shoonyaGateway;
    private final ZerodhaBrokerGateway zerodhaGateway;
    private final KiteAuthService kiteAuthService;
    private final TelegramService telegramService;

    @Autowired
    public StartupSyncRunner(
            ShoonyaConfig config,
            ShoonyaAuthenticator authenticator,
            ShoonyaMarketDataService marketDataService,
            HistoricalOhlcCacheService ohlcCacheService,
            @Autowired(required = false) ShoonyaBrokerGateway shoonyaGateway,
            @Autowired(required = false) ZerodhaBrokerGateway zerodhaGateway,
            @Autowired(required = false) KiteAuthService kiteAuthService,
            @Autowired(required = false) TelegramService telegramService) {
        this.config = config;
        this.authenticator = authenticator;
        this.marketDataService = marketDataService;
        this.ohlcCacheService = ohlcCacheService;
        this.shoonyaGateway = shoonyaGateway;
        this.zerodhaGateway = zerodhaGateway;
        this.kiteAuthService = kiteAuthService;
        this.telegramService = telegramService;
    }

    @Override
    public void run(String... args) {
        log.info("==================================================================");
        log.info("               TRADING BOT - STARTUP INITIALIZATION               ");
        log.info("==================================================================");
        log.info(
                "Shoonya Configuration: User={}, Mode={}",
                config.getUserId(),
                config.getExecutionMode());

        try {
            // 1. Authenticate with Shoonya & Fetch Active Positions
            log.info("[1/4] Authenticating with Shoonya (Finvasia NorenAPI)...");
            String sessionToken = authenticator.getOrAuthenticateToken();
            log.info(
                    "[1/4] Shoonya Authentication Successful! Session Token: {}...",
                    sessionToken.length() > 8 ? sessionToken.substring(0, 8) + "***" : "***");

            if (shoonyaGateway != null) {
                log.info("[1/4] Fetching current Shoonya positions in Cash & Derivatives...");
                List<BrokerPosition> shoonyaPositions = shoonyaGateway.getPositions();
                fetchAndReportPositions("SHOONYA", shoonyaPositions);
            }
        } catch (Exception e) {
            log.warn("Shoonya Startup Sync Notice: {}", e.getMessage());
        }

        try {
            // 2. Check Zerodha Kite Auth & Fetch Active Positions
            if (kiteAuthService != null && zerodhaGateway != null) {
                log.info("[2/4] Checking Zerodha Kite Connect authentication status...");
                KiteAuthService.KiteStatus kiteStatus = kiteAuthService.status();
                if ("ACTIVE".equalsIgnoreCase(kiteStatus.status())) {
                    log.info(
                            "[2/4] Zerodha Kite session active for user {}. Fetching positions...",
                            kiteStatus.userId());
                    List<BrokerPosition> zerodhaPositions = zerodhaGateway.getPositions();
                    fetchAndReportPositions("ZERODHA", zerodhaPositions);
                } else {
                    log.info(
                            "[2/4] Zerodha Kite status: {}. Standing by for web/auto-login.",
                            kiteStatus.detail());
                }
            }
        } catch (Exception e) {
            log.warn("Zerodha Kite Startup Notice: {}", e.getMessage());
        }

        try {
            // 3. Fetch OHLC benchmark data
            log.info("[3/4] Fetching OHLC benchmark data of the last 5 days on startup...");
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
                List<Candle> candles =
                        marketDataService.fetchHistoricalCandles(
                                target.exchange(),
                                target.token(),
                                target.symbol(),
                                target.timeframe(),
                                daysBack);

                displayCandleSummary(target.symbol(), target.timeframe(), candles);
                Thread.sleep(350);
            }
        } catch (Exception e) {
            log.warn("Historical Benchmark Candle Sync Notice: {}", e.getMessage());
        }

        try {
            // 4. Verify Historical OHLC Local SQLite / Memory Cache
            log.info("[4/4] Checking Yahoo Finance Historical OHLC Database cache status...");
            if (ohlcCacheService != null
                    && (ohlcCacheService.getCachedSymbolCount() == 0
                            || !ohlcCacheService.isCacheValidForToday())) {
                log.info(
                        "[4/4] OHLC Database is empty or stale (cached: {}, valid: {}). Initiating background sync...",
                        ohlcCacheService.getCachedSymbolCount(),
                        ohlcCacheService.isCacheValidForToday());
                java.util.concurrent.CompletableFuture.runAsync(
                        () -> {
                            try {
                                int syncedCount = ohlcCacheService.syncAll(false);
                                log.info(
                                        "[STARTUP-SYNC] Background OHLC sync finished. Total cached symbols: {}",
                                        syncedCount);
                            } catch (Exception ex) {
                                log.error(
                                        "[STARTUP-SYNC] Background OHLC sync error: {}",
                                        ex.getMessage(),
                                        ex);
                            }
                        });
            }
        } catch (Exception e) {
            log.warn("Yahoo Historical OHLC Cache Startup Notice: {}", e.getMessage());
        }

        log.info("==================================================================");
        log.info("     TRADING BOT READY - REST API LIVE ON PORT 8080               ");
        log.info("==================================================================");
    }

    private void fetchAndReportPositions(String brokerName, List<BrokerPosition> positions) {
        if (positions == null || positions.isEmpty()) {
            log.info("[{}] No active open positions in Cash or Derivatives.", brokerName);
            return;
        }

        List<BrokerPosition> cash =
                positions.stream().filter(p -> "CASH".equalsIgnoreCase(p.segment())).toList();
        List<BrokerPosition> derives =
                positions.stream()
                        .filter(p -> "DERIVATIVES".equalsIgnoreCase(p.segment()))
                        .toList();

        log.info("------------------------------------------------------------------");
        log.info(
                " [{}] ACTIVE POSITIONS ON LOGIN (Total: {}, Cash: {}, F&O: {})",
                brokerName,
                positions.size(),
                cash.size(),
                derives.size());
        log.info("------------------------------------------------------------------");
        for (BrokerPosition pos : positions) {
            log.info(
                    "  • [{}] {} (Exch: {}, Prd: {}) | Qty: {} | Avg: ₹{} | LTP: ₹{} | P&L: ₹{}",
                    pos.segment(),
                    pos.tradingSymbol(),
                    pos.exchange(),
                    pos.productType(),
                    pos.quantity(),
                    pos.averagePrice(),
                    pos.lastPrice(),
                    pos.pnl());
        }
        log.info("------------------------------------------------------------------");

        if (telegramService != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("📊 *%s Active Positions on Login*\n\n", brokerName));
            if (!cash.isEmpty()) {
                sb.append("🔹 *Cash (Equity) Positions:*\n");
                for (BrokerPosition p : cash) {
                    sb.append(
                            String.format(
                                    " • `%s` (Qty: %d | Avg: `₹%.2f` | P&L: `₹%.2f`)\n",
                                    p.tradingSymbol(),
                                    p.quantity(),
                                    p.averagePrice() != null ? p.averagePrice().doubleValue() : 0.0,
                                    p.pnl() != null ? p.pnl().doubleValue() : 0.0));
                }
                sb.append("\n");
            }
            if (!derives.isEmpty()) {
                sb.append("🔸 *Derivatives (F&O) Positions:*\n");
                for (BrokerPosition p : derives) {
                    sb.append(
                            String.format(
                                    " • `%s` (Qty: %d | Avg: `₹%.2f` | P&L: `₹%.2f`)\n",
                                    p.tradingSymbol(),
                                    p.quantity(),
                                    p.averagePrice() != null ? p.averagePrice().doubleValue() : 0.0,
                                    p.pnl() != null ? p.pnl().doubleValue() : 0.0));
                }
            }
            telegramService.sendTextMessage(sb.toString());
        }
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
