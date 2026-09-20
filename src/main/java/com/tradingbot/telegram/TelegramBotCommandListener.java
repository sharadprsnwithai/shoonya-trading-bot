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
    private final com.tradingbot.service.LowestVolumeReversalService lvrService;
    private final com.tradingbot.strategy.rsihighway.service.RsiHighwaySwingService swingService;
    private final com.tradingbot.strategy.kiss.service.KissSwingService kissService;
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
            @Autowired(required = false)
                    com.tradingbot.service.LowestVolumeReversalService lvrService,
            @Autowired(required = false)
                    com.tradingbot.strategy.rsihighway.service.RsiHighwaySwingService swingService,
            @Autowired(required = false)
                    com.tradingbot.strategy.kiss.service.KissSwingService kissService,
            TelegramService telegramService,
            @Autowired(required = false) ShoonyaConfig shoonyaConfig,
            ObjectMapper objectMapper) {
        this(
                positionalService,
                positionalConfig,
                lvrService,
                swingService,
                kissService,
                telegramService,
                shoonyaConfig,
                objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    public TelegramBotCommandListener(
            BollingerHaPositionalService positionalService,
            PositionalStrategyConfig positionalConfig,
            com.tradingbot.service.LowestVolumeReversalService lvrService,
            com.tradingbot.strategy.rsihighway.service.RsiHighwaySwingService swingService,
            com.tradingbot.strategy.kiss.service.KissSwingService kissService,
            TelegramService telegramService,
            ShoonyaConfig shoonyaConfig,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.positionalService = positionalService;
        this.positionalConfig = positionalConfig;
        this.lvrService = lvrService;
        this.swingService = swingService;
        this.kissService = kissService;
        this.telegramService = telegramService;
        this.shoonyaConfig = shoonyaConfig;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public TelegramBotCommandListener(
            BollingerHaPositionalService positionalService,
            PositionalStrategyConfig positionalConfig,
            TelegramService telegramService,
            ShoonyaConfig shoonyaConfig,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this(
                positionalService,
                positionalConfig,
                null,
                null,
                null,
                telegramService,
                shoonyaConfig,
                objectMapper,
                httpClient);
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
            case "/all_status":
            case "/all":
                StringBuilder sb = new StringBuilder("🤖 *All Strategy Status*\n\n");
                if (positionalService != null) {
                    sb.append("📊 *Bollinger Positional (NIFTY):*\n")
                            .append(positionalService.getSummaryStatus())
                            .append("\n\n");
                }
                if (lvrService != null) {
                    sb.append("⚡ *LVR Strategy (5m Options):*\n")
                            .append("• Winning Sector: ")
                            .append(
                                    lvrService.getSectorState().topSector() != null
                                                    && !lvrService
                                                            .getSectorState()
                                                            .topSector()
                                                            .isBlank()
                                            ? lvrService.getSectorState().topSector()
                                            : "None")
                            .append("\n• Active Setups: ")
                            .append(lvrService.getActiveSetups().size())
                            .append(" | Open Positions: ")
                            .append(lvrService.getOpenPositions().size())
                            .append("\n• Closed Trades: ")
                            .append(lvrService.getTradeHistory().size())
                            .append("\n\n");
                }
                if (swingService != null) {
                    sb.append("📈 *RSI Highway Swing:*\n")
                            .append("• Active Positions: ")
                            .append(swingService.getActivePositions().size())
                            .append("\n• Available Capital: ₹")
                            .append(
                                    String.format(
                                            "%.2f", swingService.getState().getAvailableCapital()))
                            .append("\n\n");
                }
                if (kissService != null) {
                    var kissState = kissService.getState();
                    sb.append("🎯 *KISS Multi-Timeframe Strategy (Nifty 200 + MCX):*\n")
                            .append(
                                    String.format(
                                            "• Equity: ₹%.2f | Active Positions: %d | Closed: %d\n",
                                            kissState.getTotalPortfolioEquity(),
                                            kissState.getPositions().size(),
                                            kissState.getClosedPositions().size()));
                }
                return sb.toString();

            case "/lvr":
            case "/lvr_status":
                if (lvrService == null) return "⚠️ LVR Service not active.";
                var sec = lvrService.getSectorState();
                return String.format(
                        "⚡ *Lowest Volume Reversal (LVR) Status*\n"
                                + "• Market Sentiment: *%s* (%d Adv / %d Dec)\n"
                                + "• Winning Sector: *%s* (%.2f%%)\n"
                                + "• Candidates (%d): `%s`\n"
                                + "• Open Positions: %d | Closed Trades: %d",
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
                        lvrService.getOpenPositions().size(),
                        lvrService.getTradeHistory().size());

            case "/lvr_scan":
                if (lvrService == null) return "⚠️ LVR Service not active.";
                lvrService.runMorningUniverseScan();
                return "🔍 LVR Morning Universe Scan completed!\n\n"
                        + processCommand("/lvr_status");

            case "/swing":
            case "/swing_status":
                if (swingService == null) return "⚠️ RSI Highway Swing Service not active.";
                var swingState = swingService.getState();
                return String.format(
                        "📈 *RSI Highway Swing Status*\n"
                                + "• Total Equity: ₹%.2f\n"
                                + "• Available Capital: ₹%.2f\n"
                                + "• Active Positions: %d\n"
                                + "• Closed Positions: %d",
                        swingState.getTotalPortfolioEquity(),
                        swingState.getAvailableCapital(),
                        swingState.getPositions().size(),
                        swingState.getClosedPositions().size());

            case "/kiss":
            case "/kiss_status":
                if (kissService == null) return "⚠️ KISS Strategy Service not active.";
                var kissState = kissService.getState();
                StringBuilder kissSb = new StringBuilder();
                kissSb.append("🎯 *KISS Multi-Timeframe Strategy Status*\n");
                kissSb.append(
                        String.format(
                                "• Total Equity: ₹%.2f\n", kissState.getTotalPortfolioEquity()));
                kissSb.append(
                        String.format(
                                "• Available Capital: ₹%.2f\n", kissState.getAvailableCapital()));
                kissSb.append(
                        String.format("• Active Positions: %d\n", kissState.getPositions().size()));
                kissSb.append(
                        String.format(
                                "• Closed Trades: %d\n", kissState.getClosedPositions().size()));
                if (!kissState.getPositions().isEmpty()) {
                    kissSb.append("\n*Active Positions:*\n");
                    kissState
                            .getPositions()
                            .values()
                            .forEach(
                                    p -> {
                                        kissSb.append(
                                                String.format(
                                                        "• `%s` (%s) | Qty: %d | Entry: ₹%.2f | LTP: ₹%.2f | PnL: ₹%.2f (%.2f%%)\n",
                                                        p.getSymbol(),
                                                        p.getSignalType(),
                                                        p.getQuantity(),
                                                        p.getEntryPrice(),
                                                        p.getCurrentLtp(),
                                                        p.getUnrealizedPnl(),
                                                        p.getUnrealizedPnlPct()));
                                    });
                }
                return kissSb.toString();

            case "/kiss_scan":
                if (kissService == null) return "⚠️ KISS Strategy Service not active.";
                var kissSignals = kissService.scanAndExecute();
                return String.format(
                        "🎯 KISS Full Scan Completed! Generated %d new actionable signals.",
                        kissSignals.size());

            case "/kiss_mcx":
                if (kissService == null) return "⚠️ KISS Strategy Service not active.";
                var mcxSignals = kissService.scanMcxUniverse();
                return String.format(
                        "🛢️ KISS MCX Commodity Scan Completed! Found %d signals.",
                        mcxSignals.size());

            case "/kiss_nse":
                if (kissService == null) return "⚠️ KISS Strategy Service not active.";
                var nseSignals = kissService.scanNseUniverse();
                return String.format(
                        "📊 KISS NSE Equity Scan Completed! Found %d signals.", nseSignals.size());

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
                        🤖 *Shoonya Trading Bot Commands:*

                        📊 *Overall & Multi-Strategy:*
                        • `/status` or `/all` - View status across all strategies

                        🎯 *KISS Multi-Timeframe Strategy (Nifty 200 + MCX Commodities):*
                        • `/kiss` or `/kiss_status` - View active paper positions & PnL
                        • `/kiss_scan` - Trigger manual full universe scan
                        • `/kiss_mcx` - Scan MCX commodities (Crude, Gold, Silver, Copper, NatGas)
                        • `/kiss_nse` - Scan Nifty 200 equity futures

                        ⚡ *LVR (Lowest Volume Reversal 5m Options):*
                        • `/lvr` - View LVR winning sector & candidate setups
                        • `/lvr_scan` - Trigger manual LVR 09:25 AM universe scan

                        📈 *RSI Highway Swing:*
                        • `/swing` - View RSI Highway portfolio & active positions

                        🛡️ *Positional Strategy (Bollinger Bands + HA):*
                        • `/pos_status` - View current positional trade & state
                        • `/scan` or `/pos_scan` - Run 3:00 PM IST strategy evaluation
                        • `/approve` - Confirm & execute staged positional trade
                        • `/reject` - Cancel & reject staged positional trade
                        • `/exit` - Immediately exit active positional trade
                        • `/mode <AUTO|MANUAL>` - Switch positional execution mode
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
                            .timeout(Duration.ofSeconds(10))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build();

            httpClient.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("[TELEGRAM LISTENER] Error answering callback query: {}", e.getMessage());
        }
    }
}
