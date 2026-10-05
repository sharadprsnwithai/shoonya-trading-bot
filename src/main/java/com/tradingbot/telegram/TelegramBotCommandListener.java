package com.tradingbot.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.service.LowestVolumeReversalService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Bidirectional Telegram Bot Command Listener for the Lowest Volume Reversal (LVR) Strategy. Polls
 * for incoming commands (/status, /lvr, /scan, /exit, /reset, /help).
 */
@Component
@ConditionalOnProperty(
        name = "trading-bot.telegram.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class TelegramBotCommandListener {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotCommandListener.class);

    private final LowestVolumeReversalService lvrService;
    private final com.tradingbot.strategy.car.CarWeeklyGttService carWeeklyService;
    private final com.tradingbot.strategy.driftvwap.DriftVwapOptionSellingService driftVwapService;
    private final TelegramService telegramService;
    private final ShoonyaConfig shoonyaConfig;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService pollingExecutor;
    private long lastUpdateId = 0;

    @Autowired
    public TelegramBotCommandListener(
            @Autowired(required = false) LowestVolumeReversalService lvrService,
            @Autowired(required = false)
                    com.tradingbot.strategy.car.CarWeeklyGttService carWeeklyService,
            @Autowired(required = false)
                    com.tradingbot.strategy.driftvwap.DriftVwapOptionSellingService
                            driftVwapService,
            TelegramService telegramService,
            @Autowired(required = false) ShoonyaConfig shoonyaConfig,
            ObjectMapper objectMapper) {
        this(
                lvrService,
                carWeeklyService,
                driftVwapService,
                telegramService,
                shoonyaConfig,
                objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    public TelegramBotCommandListener(
            LowestVolumeReversalService lvrService,
            TelegramService telegramService,
            ShoonyaConfig shoonyaConfig,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this(lvrService, null, null, telegramService, shoonyaConfig, objectMapper, httpClient);
    }

    public TelegramBotCommandListener(
            LowestVolumeReversalService lvrService,
            com.tradingbot.strategy.car.CarWeeklyGttService carWeeklyService,
            TelegramService telegramService,
            ShoonyaConfig shoonyaConfig,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this(
                lvrService,
                carWeeklyService,
                null,
                telegramService,
                shoonyaConfig,
                objectMapper,
                httpClient);
    }

    public TelegramBotCommandListener(
            LowestVolumeReversalService lvrService,
            com.tradingbot.strategy.car.CarWeeklyGttService carWeeklyService,
            com.tradingbot.strategy.driftvwap.DriftVwapOptionSellingService driftVwapService,
            TelegramService telegramService,
            ShoonyaConfig shoonyaConfig,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.lvrService = lvrService;
        this.carWeeklyService = carWeeklyService;
        this.driftVwapService = driftVwapService;
        this.telegramService = telegramService;
        this.shoonyaConfig = shoonyaConfig;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @PostConstruct
    public void startPolling() {
        if (shoonyaConfig == null
                || !shoonyaConfig.isTelegramEnabled()
                || shoonyaConfig.getTelegramBotToken().isBlank()) {
            log.info(
                    "[TELEGRAM LISTENER] Telegram is not configured or disabled. Polling listener not started.");
            return;
        }

        running.set(true);
        pollingExecutor = Executors.newSingleThreadExecutor();
        pollingExecutor.submit(this::pollLoop);
        log.info("[TELEGRAM LISTENER] Telegram Bot Polling listener started successfully.");
    }

    @PreDestroy
    public void stopPolling() {
        running.set(false);
        if (pollingExecutor != null) {
            pollingExecutor.shutdownNow();
        }
        log.info("[TELEGRAM LISTENER] Telegram Bot Polling listener stopped.");
    }

    private void pollLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                pollUpdates();
                Thread.sleep(1500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[TELEGRAM LISTENER] Error in poll loop: {}", e.getMessage());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    public void pollUpdates() {
        if (shoonyaConfig == null || shoonyaConfig.getTelegramBotToken().isBlank()) return;

        try {
            String token = shoonyaConfig.getTelegramBotToken().trim();
            String url =
                    String.format(
                            "https://api.telegram.org/bot%s/getUpdates?offset=%d&timeout=5",
                            token, lastUpdateId + 1);

            HttpRequest req =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
                if (root.path("ok").asBoolean(false)) {
                    JsonNode result = root.path("result");
                    if (result.isArray()) {
                        for (JsonNode update : result) {
                            long updateId = update.path("update_id").asLong();
                            if (updateId > lastUpdateId) {
                                lastUpdateId = updateId;
                            }
                            handleSingleUpdate(update);
                        }
                    }
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.debug("[TELEGRAM LISTENER] Polling thread interrupted");
        } catch (Exception e) {
            log.debug("[TELEGRAM LISTENER] pollUpdates exception: {}", e.getMessage());
        }
    }

    private void handleSingleUpdate(JsonNode update) {
        if (update.has("message")) {
            JsonNode message = update.path("message");
            String text = message.path("text").asText("");
            long chatId = message.path("chat").path("id").asLong();
            String targetChatId = chatId != 0 ? String.valueOf(chatId) : null;

            if (text.startsWith("/")) {
                log.info("[TELEGRAM LISTENER] Received command: '{}' from chat {}", text, chatId);
                String responseText = processCommand(text);
                if (responseText != null && !responseText.isBlank()) {
                    if (targetChatId != null) {
                        telegramService.sendTextMessage(targetChatId, responseText);
                    } else {
                        telegramService.sendAlert(responseText);
                    }
                }
            }
        }
    }

    public String processCommand(String fullCommand) {
        if (fullCommand == null || fullCommand.isBlank()) return "";

        String[] parts = fullCommand.trim().split("\\s+");
        String cmd = parts[0].toLowerCase();

        if (cmd.contains("@")) {
            cmd = cmd.substring(0, cmd.indexOf("@"));
        }

        switch (cmd) {
            case "/status":
            case "/lvr":
            case "/lvr_status":
                if (lvrService == null) return "⚠️ LVR Service not active.";
                var sec = lvrService.getSectorState();
                return String.format(
                        "⚡ *Lowest Volume Reversal (LVR) Strategy Status*\n\n"
                                + "• Market Sentiment: *%s* (%d Adv / %d Dec)\n"
                                + "• Winning Sector: *%s* (%.2f%%)\n"
                                + "• Candidates (%d): `%s`\n"
                                + "• Active Setups: %d\n"
                                + "• Open Positions: %d\n"
                                + "• Closed Trades: %d\n"
                                + "• Exit Mode: *1:2 RR + Cost Floor + 10 EMA Trail*",
                        sec.sentiment(),
                        sec.advances(),
                        sec.declines(),
                        sec.topSector() != null && !sec.topSector().isBlank()
                                ? sec.topSector()
                                : "None",
                        sec.sectorPctChange(),
                        sec.candidateSymbols() != null ? sec.candidateSymbols().size() : 0,
                        sec.candidateSymbols() != null
                                ? String.join(", ", sec.candidateSymbols())
                                : "None",
                        lvrService.getActiveSetups().size(),
                        lvrService.getOpenPositions().size(),
                        lvrService.getTradeHistory().size());

            case "/scan":
            case "/lvr_scan":
                if (lvrService == null) return "⚠️ LVR Service not active.";
                lvrService.runCycle();
                return "🔍 LVR 5-Minute Strategy Cycle executed!\n\n" + processCommand("/status");

            case "/morning_scan":
                if (lvrService == null) return "⚠️ LVR Service not active.";
                lvrService.runMorningUniverseScan();
                return "🌅 LVR Morning Universe Scan executed!\n\n" + processCommand("/status");

            case "/car":
            case "/car_status":
                if (carWeeklyService == null) return "⚠️ CAR Weekly GTT Service not active.";
                var pState = carWeeklyService.getPortfolioState();
                int investedUnits =
                        pState.getHoldings().values().stream()
                                .mapToInt(
                                        com.tradingbot.strategy.car.model.CarHolding
                                                ::accumulatedUnits)
                                .sum();
                return String.format(
                        "📈 *CAR Weekly GTT Strategy Status*\n\n"
                                + "• Total Capital: `₹%.2f`\n"
                                + "• Unit Size (1/40th): `₹%.2f`\n"
                                + "• Invested Units: `%d / %d`\n"
                                + "• Available Units: `%d`\n"
                                + "• Active Demat Holdings: `%d`\n"
                                + "• Active GTT Orders: `%d`",
                        pState.getTotalCapital().doubleValue(),
                        pState.getUnitSize().doubleValue(),
                        investedUnits,
                        pState.getNumParts(),
                        pState.getAvailableUnits(),
                        pState.getHoldings().size(),
                        pState.getGttOrders().size());

            case "/car_run":
            case "/car_weekly":
                if (carWeeklyService == null) return "⚠️ CAR Weekly GTT Service not active.";
                carWeeklyService.runSundayWeeklyRoutine();
                return "🚀 CAR Weekly GTT Routine executed! GTT orders placed on Kite.\n\n"
                        + processCommand("/car");

            case "/drift":
            case "/drift_status":
            case "/drift_vwap":
                if (driftVwapService == null) return "⚠️ Drift VWAP Service not active.";
                var dTrend = driftVwapService.getLatestTrendState();
                var dPos = driftVwapService.getOpenPosition();
                return String.format(
                        "⚡ *Drift VWAP Option Selling Status*\n\n"
                                + "• Trend Direction: *%s*\n"
                                + "• 15m Close: `₹%.2f` | 15m VWAP: `₹%.2f`\n"
                                + "• 1-Hour Momentum: `%+.2f%%`\n"
                                + "• Open Position: *%s*\n"
                                + "• Today's Trades: `%d / 4` | Losses: `%d / 2`",
                        dTrend.direction(),
                        dTrend.close15m().doubleValue(),
                        dTrend.vwap15m().doubleValue(),
                        dTrend.momentum1hrPct(),
                        dPos != null && !dPos.isClosed()
                                ? String.format(
                                        Locale.US,
                                        "SOLD %s ATM %d @ ₹%.2f (Target: ₹%.2f, SL: ₹%.2f)",
                                        dPos.getOptionType(),
                                        dPos.getStrikePrice().intValue(),
                                        dPos.getEntryPremium().doubleValue(),
                                        dPos.getTargetPremium().doubleValue(),
                                        dPos.getSlPremium().doubleValue())
                                : "None (Standing By)",
                        driftVwapService.getTodayTradesCount().get(),
                        driftVwapService.getTodayLossCount().get());

            case "/drift_run":
                if (driftVwapService == null) return "⚠️ Drift VWAP Service not active.";
                driftVwapService.runCycle();
                return "🔍 Drift VWAP 5-Minute Cycle executed!\n\n" + processCommand("/drift");

            case "/exit":
            case "/squareoff":
                if (lvrService == null) return "⚠️ LVR Service not active.";
                lvrService.executeHardExit(LocalTime.now());
                return "🛑 Manual hard exit executed. All open LVR positions squared off.";

            case "/reset":
                if (lvrService == null) return "⚠️ LVR Service not active.";
                {
                    boolean force =
                            java.util.Arrays.stream(parts)
                                    .skip(1)
                                    .anyMatch(a -> a.equalsIgnoreCase("force"));
                    boolean duringTradingHours = lvrService.isWithinTradingHours();
                    // H6: an intraday /reset flattens live positions — refuse unless forced.
                    if (duringTradingHours && !force) {
                        log.warn(
                                "[AUDIT] Telegram /reset REFUSED: caller=telegram,"
                                        + " duringTradingHours=true, force=false");
                        return "⛔ Intraday reset refused — market is open and positions may be"
                                + " live. Send `/reset force` to flatten positions and reset"
                                + " (realized P&L is preserved).";
                    }
                    lvrService.resetDaily(force);
                    log.warn(
                            "[AUDIT] Telegram /reset EXECUTED: caller=telegram, force={},"
                                    + " duringTradingHours={}",
                            force,
                            duringTradingHours);
                }
                return "🔄 LVR daily session state reset successfully.";

            case "/help":
            default:
                return "🤖 *Lowest Volume Reversal Bot Commands:*\n\n"
                        + "• `/status` - Live LVR strategy status & positions\n"
                        + "• `/scan` - Execute immediate 5m strategy cycle\n"
                        + "• `/morning_scan` - Force 09:25 AM morning scan\n"
                        + "• `/exit` - Square off all open positions immediately\n"
                        + "• `/reset [force]` - Reset daily session state (`force` required intraday)\n"
                        + "• `/car` - Live CAR Weekly GTT Portfolio Status\n"
                        + "• `/car_run` - Force execute CAR Weekly GTT Sunday routine & place orders\n"
                        + "• `/help` - Show this command menu";
        }
    }
}
