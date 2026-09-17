package com.tradingbot.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.positional.config.PositionalStrategyConfig;
import com.tradingbot.positional.service.BollingerHaPositionalService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Bidirectional Telegram Bot Listener. Polls for incoming commands (/status, /scan, /approve,
 * /reject, /exit, /mode, /help) and handles interactive inline keyboard callbacks.
 */
@Component
@ConditionalOnProperty(
        name = "trading-bot.telegram.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class TelegramBotCommandListener {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotCommandListener.class);

    private final BollingerHaPositionalService positionalService;
    private final PositionalStrategyConfig positionalConfig;
    private final TelegramService telegramService;
    private final ShoonyaConfig shoonyaConfig;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService pollingExecutor;
    private long lastUpdateId = 0;

    @Autowired
    public TelegramBotCommandListener(
            BollingerHaPositionalService positionalService,
            PositionalStrategyConfig positionalConfig,
            TelegramService telegramService,
            @Autowired(required = false) ShoonyaConfig shoonyaConfig,
            ObjectMapper objectMapper) {
        this(
                positionalService,
                positionalConfig,
                telegramService,
                shoonyaConfig,
                objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    public TelegramBotCommandListener(
            BollingerHaPositionalService positionalService,
            PositionalStrategyConfig positionalConfig,
            TelegramService telegramService,
            ShoonyaConfig shoonyaConfig,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.positionalService = positionalService;
        this.positionalConfig = positionalConfig;
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

            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();

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
        // 1. Text Message Commands
        if (update.has("message")) {
            JsonNode message = update.path("message");
            String text = message.path("text").asText("");
            long chatId = message.path("chat").path("id").asLong();

            if (text.startsWith("/")) {
                log.info("[TELEGRAM LISTENER] Received command: '{}' from chat {}", text, chatId);
                String responseText = processCommand(text);
                if (responseText != null && !responseText.isBlank()) {
                    telegramService.sendAlert(responseText);
                }
            }
        }

        // 2. Interactive Inline Button Callbacks
        if (update.has("callback_query")) {
            JsonNode callback = update.path("callback_query");
            String callbackId = callback.path("id").asText();
            String data = callback.path("data").asText();
            log.info("[TELEGRAM LISTENER] Received callback query: '{}'", data);

            answerCallback(callbackId, "Processing...");

            if ("pos_approve".equalsIgnoreCase(data)) {
                boolean approved = positionalService.approveStagedTrade();
                if (approved) {
                    telegramService.sendAlert(
                            "✅ Staged positional trade was *APPROVED* and executed successfully!");
                } else {
                    telegramService.sendAlert("⚠️ No staged trade available for approval.");
                }
            } else if ("pos_reject".equalsIgnoreCase(data)) {
                boolean rejected = positionalService.rejectStagedTrade();
                if (rejected) {
                    telegramService.sendAlert(
                            "🛑 Staged positional trade was *REJECTED*. Status reset to FLAT.");
                } else {
                    telegramService.sendAlert("⚠️ No staged trade available to reject.");
                }
            }
        }
    }

    public String processCommand(String fullCommand) {
        if (fullCommand == null || fullCommand.isBlank()) return "";

        String[] parts = fullCommand.trim().split("\\s+");
        String cmd = parts[0].toLowerCase();

        // Strip bot username suffix if present (e.g. /status@MyBot -> /status)
        if (cmd.contains("@")) {
            cmd = cmd.substring(0, cmd.indexOf("@"));
        }

        switch (cmd) {
            case "/status":
            case "/pos_status":
                return positionalService.getSummaryStatus();

            case "/scan":
            case "/pos_scan":
                positionalService.scanAndEvaluate();
                return "🔍 Scan completed!\n\n" + positionalService.getSummaryStatus();

            case "/approve":
            case "/pos_approve":
                boolean approved = positionalService.approveStagedTrade();
                return approved
                        ? "✅ Staged trade approved and executed."
                        : "⚠️ No staged trade currently waiting for approval.";

            case "/reject":
            case "/pos_reject":
                boolean rejected = positionalService.rejectStagedTrade();
                return rejected
                        ? "🛑 Staged trade rejected. State reset to FLAT."
                        : "⚠️ No staged trade currently waiting to reject.";

            case "/exit":
            case "/pos_exit":
                positionalService.forceExitCurrentPosition("TELEGRAM_MANUAL_EXIT");
                return "⚡ Exit signal dispatched for active positional trade.";

            case "/mode":
            case "/pos_mode":
                if (parts.length > 1) {
                    String newMode = parts[1].toUpperCase();
                    if ("AUTO".equals(newMode)
                            || "MANUAL".equals(newMode)
                            || "MANUAL_CONFIRMATION".equals(newMode)) {
                        positionalConfig.setExecutionMode(newMode);
                        return "⚙️ Positional Strategy execution mode updated to: *"
                                + newMode
                                + "*";
                    } else {
                        return "⚠️ Invalid mode. Choose `AUTO` or `MANUAL_CONFIRMATION`.";
                    }
                }
                return "⚙️ Current Positional Mode: *"
                        + positionalConfig.getExecutionMode()
                        + "*\n\nUsage: `/mode <AUTO|MANUAL_CONFIRMATION>`";

            case "/help":
            case "/pos_help":
                return """
                        🤖 *Positional Strategy Bot Commands:*

                        • `/status` - View current positional strategy state & active trade
                        • `/scan` - Run manual 3:00 PM IST strategy evaluation scan
                        • `/approve` - Confirm and execute staged trade
                        • `/reject` - Cancel and reject staged trade
                        • `/exit` - Immediately exit active positional trade
                        • `/mode <AUTO|MANUAL>` - Switch execution mode
                        • `/help` - Show command list
                        """;

            default:
                return "❓ Unknown command `" + cmd + "`. Send `/help` for available options.";
        }
    }

    private void answerCallback(String callbackId, String text) {
        if (shoonyaConfig == null || shoonyaConfig.getTelegramBotToken().isBlank()) return;

        try {
            String token = shoonyaConfig.getTelegramBotToken().trim();
            String url =
                    String.format(
                            "https://api.telegram.org/bot%s/answerCallbackQuery?callback_query_id=%s&text=%s",
                            token, callbackId, URLEncoder.encode(text, StandardCharsets.UTF_8));

            HttpRequest req =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build();

            httpClient.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("[TELEGRAM LISTENER] Error answering callback query: {}", e.getMessage());
        }
    }
}
