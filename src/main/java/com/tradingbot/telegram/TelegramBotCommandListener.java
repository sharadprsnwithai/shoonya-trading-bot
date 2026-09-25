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
            TelegramService telegramService,
            @Autowired(required = false) ShoonyaConfig shoonyaConfig,
            ObjectMapper objectMapper) {
        this(
                lvrService,
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
        this.lvrService = lvrService;
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

            case "/exit":
            case "/squareoff":
                if (lvrService == null) return "⚠️ LVR Service not active.";
                lvrService.executeHardExit(LocalTime.now());
                return "🛑 Manual hard exit executed. All open LVR positions squared off.";

            case "/reset":
                if (lvrService == null) return "⚠️ LVR Service not active.";
                lvrService.resetDaily();
                return "🔄 LVR daily session state reset successfully.";

            case "/help":
            default:
                return "🤖 *Lowest Volume Reversal Bot Commands:*\n\n"
                        + "• `/status` - Live LVR strategy status & positions\n"
                        + "• `/scan` - Execute immediate 5m strategy cycle\n"
                        + "• `/morning_scan` - Force 09:25 AM morning scan\n"
                        + "• `/exit` - Square off all open positions immediately\n"
                        + "• `/reset` - Reset daily session state\n"
                        + "• `/help` - Show this command menu";
        }
    }
}
