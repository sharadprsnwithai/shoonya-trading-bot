package com.tradingbot.telegram;

import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.model.execution.ActiveSpreadPosition;
import com.tradingbot.model.execution.ExecutionMode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service to dispatch instant Telegram notifications for both PAPER and LIVE option trading alerts.
 */
@Service
public class TelegramService {

    private static final Logger log = LoggerFactory.getLogger(TelegramService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss").withZone(IST);

    private final ShoonyaConfig config;
    private final HttpClient httpClient;
    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(2);

    @Autowired
    public TelegramService(ShoonyaConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Sends an entry alert when a Short Spread trade is executed (in PAPER or LIVE mode). */
    public void sendTradeAlert(ActiveSpreadPosition pos, String strategyName) {
        if (!config.isTelegramEnabled()
                || config.getTelegramBotToken().isBlank()
                || config.getTelegramChatId().isBlank()) {
            log.debug("[TELEGRAM] Alert skipped (Telegram disabled or credentials empty)");
            return;
        }

        String modeBadge =
                (pos.mode() == ExecutionMode.LIVE)
                        ? "🔴 *[LIVE TRADE ALERT]* 🔴"
                        : "🧪 *[PAPER TRADE ALERT]* 🧪";
        String hedgeDetails =
                (pos.hedgeSymbol() != null && !pos.hedgeSymbol().isBlank())
                        ? String.format(
                                "🛡️ *BUY Leg (Hedge):* `%s`\n   • *Premium:* ₹%.2f\n   • *Quantity:* %d",
                                pos.hedgeSymbol(), pos.hedgeEntryPremium(), pos.quantity())
                        : "🛡️ *BUY Leg (Hedge):* None (Naked Sell)";

        String message =
                String.format(
                        "%s\n"
                                + "📈 *Strategy:* %s\n"
                                + "🎯 *Underlying:* %s\n\n"
                                + "👉 *Action:* Directional Short Option Spread\n"
                                + "🔻 *SELL Leg:* `%s`\n"
                                + "   • *Strike:* %.0f %s\n"
                                + "   • *Sell Premium (LTP):* ₹%.2f\n"
                                + "   • *Quantity:* %d\n\n"
                                + "%s\n\n"
                                + "🛑 *Stop-Loss (Hard SL-L):*\n"
                                + "   • *Trigger Price:* ₹%.2f (+40%%)\n"
                                + "   • *Limit Price:* ₹%.2f\n\n"
                                + "⏰ *Time:* %s IST",
                        modeBadge,
                        strategyName != null ? strategyName : pos.strategyId(),
                        pos.underlying(),
                        pos.shortSymbol(),
                        pos.strikePrice(),
                        pos.optionType(),
                        pos.shortEntryPremium(),
                        pos.quantity(),
                        hedgeDetails,
                        pos.slTriggerPrice(),
                        pos.slLimitPrice(),
                        TIME_FMT.format(pos.entryTime()));

        sendAsync(message);
    }

    /** Sends an exit / square-off alert when a trade is closed. */
    public void sendTradeExitAlert(ActiveSpreadPosition pos, String strategyName, String reason) {
        if (!config.isTelegramEnabled()
                || config.getTelegramBotToken().isBlank()
                || config.getTelegramChatId().isBlank()) {
            return;
        }

        String modeBadge =
                (pos.mode() == ExecutionMode.LIVE)
                        ? "🏁 *[LIVE TRADE CLOSED]* 🏁"
                        : "🏁 *[PAPER TRADE CLOSED]* 🏁";
        String pnlEmoji =
                (pos.realizedPnl() != null && pos.realizedPnl().signum() >= 0) ? "🟢" : "🔴";
        String pnlSign = (pos.realizedPnl() != null && pos.realizedPnl().signum() >= 0) ? "+" : "";

        String message =
                String.format(
                        "%s\n"
                                + "📈 *Strategy:* %s\n"
                                + "👉 *Trade ID:* `%s`\n\n"
                                + "🔻 *Short Leg:* `%s`\n"
                                + "   • *Entry:* ₹%.2f ➔ *Exit:* ₹%.2f\n"
                                + "🛡️ *Hedge Leg:* `%s`\n"
                                + "   • *Entry:* ₹%.2f ➔ *Exit:* ₹%.2f\n\n"
                                + "%s *Realized P&L:* *%s₹%.2f*\n"
                                + "ℹ️ *Exit Reason:* %s\n"
                                + "⏰ *Exit Time:* %s IST",
                        modeBadge,
                        strategyName != null ? strategyName : pos.strategyId(),
                        pos.tradeId(),
                        pos.shortSymbol(),
                        pos.shortEntryPremium(),
                        pos.shortExitPremium() != null ? pos.shortExitPremium() : 0.0,
                        pos.hedgeSymbol() != null ? pos.hedgeSymbol() : "NONE",
                        pos.hedgeEntryPremium() != null ? pos.hedgeEntryPremium() : 0.0,
                        pos.hedgeExitPremium() != null ? pos.hedgeExitPremium() : 0.0,
                        pnlEmoji,
                        pnlSign,
                        pos.realizedPnl() != null ? pos.realizedPnl() : 0.0,
                        reason != null ? reason : "Target / SL Exit",
                        pos.exitTime() != null
                                ? TIME_FMT.format(pos.exitTime())
                                : TIME_FMT.format(java.time.Instant.now()));

        sendAsync(message);
    }

    /**
     * Sends an entry signal alert generated by a strategy (e.g.,
     * PivotSuperTrendOptionSellingStrategy).
     */
    public void sendStrategySignalAlert(
            String strategyName,
            String underlying,
            String signalAction,
            BigDecimal underlyingPrice,
            BigDecimal strikePrice,
            String optionType,
            BigDecimal optionPremium,
            Map<String, Object> extraInfo,
            Instant timestamp) {
        if (!config.isTelegramEnabled()
                || config.getTelegramBotToken().isBlank()
                || config.getTelegramChatId().isBlank()) {
            log.debug("[TELEGRAM] Signal alert skipped (Telegram disabled or credentials empty)");
            return;
        }

        StringBuilder extraSb = new StringBuilder();
        if (extraInfo != null && !extraInfo.isEmpty()) {
            extraInfo.forEach(
                    (k, v) -> {
                        if (v != null) {
                            extraSb.append(String.format("   • *%s:* %s\n", formatKey(k), v));
                        }
                    });
        }

        String extraSection = extraSb.length() > 0 ? extraSb.toString().trim() + "\n" : "";

        String message =
                String.format(
                        "⚡ *[STRATEGY SIGNAL ALERT]* ⚡\n"
                                + "📈 *Strategy:* %s\n"
                                + "🎯 *Underlying:* %s\n"
                                + "👉 *Action:* %s\n\n"
                                + "📊 *Trade Details:*\n"
                                + "   • *Underlying Entry Price:* ₹%.2f\n"
                                + "   • *Strike:* %.0f %s\n"
                                + "   • *Option Entry Premium (LTP):* ₹%.2f\n"
                                + "%s\n"
                                + "⏰ *Signal Time:* %s IST",
                        strategyName != null ? strategyName : "Intraday Strategy",
                        underlying != null ? underlying : "NIFTY 50",
                        signalAction,
                        underlyingPrice != null ? underlyingPrice : BigDecimal.ZERO,
                        strikePrice != null ? strikePrice : BigDecimal.ZERO,
                        optionType != null ? optionType : "",
                        optionPremium != null ? optionPremium : BigDecimal.ZERO,
                        extraSection,
                        TIME_FMT.format(timestamp != null ? timestamp : Instant.now()));

        sendAsync(message);
    }

    /**
     * Sends an exit / square-off alert generated by a strategy (e.g.,
     * PivotSuperTrendOptionSellingStrategy).
     */
    public void sendStrategyExitAlert(
            String strategyName,
            String underlying,
            String exitAction,
            BigDecimal underlyingExitPrice,
            BigDecimal strikePrice,
            String optionType,
            BigDecimal entryPremium,
            BigDecimal exitPremium,
            String exitReason,
            Instant timestamp) {
        if (!config.isTelegramEnabled()
                || config.getTelegramBotToken().isBlank()
                || config.getTelegramChatId().isBlank()) {
            log.debug("[TELEGRAM] Exit alert skipped (Telegram disabled or credentials empty)");
            return;
        }

        // For Option Selling: PnL Points = Entry Premium - Exit Premium
        BigDecimal premiumDiff =
                (entryPremium != null && exitPremium != null)
                        ? entryPremium.subtract(exitPremium).setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
        String pnlEmoji = premiumDiff.signum() >= 0 ? "🟢" : "🔴";
        String pnlSign = premiumDiff.signum() >= 0 ? "+" : "";

        String message =
                String.format(
                        "🏁 *[STRATEGY EXIT ALERT]* 🏁\n"
                                + "📈 *Strategy:* %s\n"
                                + "🎯 *Underlying:* %s\n"
                                + "👉 *Action:* %s\n\n"
                                + "📊 *Exit Details:*\n"
                                + "   • *Strike:* %.0f %s\n"
                                + "   • *Underlying Exit Price:* ₹%.2f\n"
                                + "   • *Option Entry Premium:* ₹%.2f\n"
                                + "   • *Option Exit Premium:* ₹%.2f\n"
                                + "   • %s *Est. Option P&L:* *%s₹%.2f* / share\n\n"
                                + "ℹ️ *Exit Reason:* %s\n"
                                + "⏰ *Exit Time:* %s IST",
                        strategyName != null ? strategyName : "Intraday Strategy",
                        underlying != null ? underlying : "NIFTY 50",
                        exitAction,
                        strikePrice != null ? strikePrice : BigDecimal.ZERO,
                        optionType != null ? optionType : "",
                        underlyingExitPrice != null ? underlyingExitPrice : BigDecimal.ZERO,
                        entryPremium != null ? entryPremium : BigDecimal.ZERO,
                        exitPremium != null ? exitPremium : BigDecimal.ZERO,
                        pnlEmoji,
                        pnlSign,
                        premiumDiff,
                        exitReason != null ? exitReason : "Strategy Exit Signal",
                        TIME_FMT.format(timestamp != null ? timestamp : Instant.now()));

        sendAsync(message);
    }

    private String formatKey(String key) {
        if ("r1".equalsIgnoreCase(key)) return "Pivot R1 Level";
        if ("s1".equalsIgnoreCase(key)) return "Pivot S1 Level";
        if ("supertrend".equalsIgnoreCase(key)) return "SuperTrend (7, 3)";
        if ("tradeNum".equalsIgnoreCase(key)) return "Daily Trade #";
        if ("spreadType".equalsIgnoreCase(key)) return "Spread Type";
        if ("sellLeg".equalsIgnoreCase(key)) return "Sell Leg (ATM)";
        if ("buyHedgeLeg".equalsIgnoreCase(key)) return "Buy Hedge Leg";
        if ("netCredit".equalsIgnoreCase(key)) return "Net Credit";
        if ("maxRisk".equalsIgnoreCase(key)) return "Defined Max Risk";
        if ("lots".equalsIgnoreCase(key)) return "Position Lots";
        if ("quantity".equalsIgnoreCase(key)) return "Total Quantity";
        if ("oiDiff".equalsIgnoreCase(key)) return "OTM OI Difference";
        if ("exitReason".equalsIgnoreCase(key)) return "Exit Reason";
        return key;
    }

    /** Dispatches an instant test notification to verify Telegram bot connectivity. */
    public boolean sendTestAlert(String strategyName) {
        if (!config.isTelegramEnabled()
                || config.getTelegramBotToken().isBlank()
                || config.getTelegramChatId().isBlank()) {
            log.warn("[TELEGRAM] Test alert skipped: Telegram disabled or token/chatId missing");
            return false;
        }

        String message =
                String.format(
                        "🔔 *[TELEGRAM NOTIFICATION TEST]* 🔔\n"
                                + "📈 *Strategy:* %s\n"
                                + "🤖 *Bot Status:* Online & Connected\n\n"
                                + "📡 *Active Alerts Configured:*\n"
                                + "   • ⚡ *Entry Alert:* Directional Confluence & Credit Spread Leg Details\n"
                                + "   • 🛑 *Hard Stop Loss Alert:* Immediate notification on 30%% premium expansion\n"
                                + "   • 🎯 *Take Profit Alert:* Immediate notification on 50%% premium decay\n"
                                + "   • 🔄 *SuperTrend Flip Alert:* Immediate exit alert on trend reversal\n"
                                + "   • ⏰ *15:14 Square-Off Alert:* Auto square-off before market close\n\n"
                                + "⏰ *Test Time:* %s IST",
                        strategyName != null ? strategyName : "Intraday Option Selling",
                        TIME_FMT.format(Instant.now()));

        sendAsync(message);
        return true;
    }

    /** Sends a raw message asynchronously to avoid blocking execution threads. */
    public void sendAsync(String text) {
        CompletableFuture.runAsync(
                () -> {
                    try {
                        String token = config.getTelegramBotToken().trim();
                        String chatId = config.getTelegramChatId().trim();

                        String url = "https://api.telegram.org/bot" + token + "/sendMessage";
                        String formBody =
                                "chat_id="
                                        + URLEncoder.encode(chatId, StandardCharsets.UTF_8)
                                        + "&text="
                                        + URLEncoder.encode(text, StandardCharsets.UTF_8)
                                        + "&parse_mode=Markdown";

                        HttpRequest request =
                                HttpRequest.newBuilder()
                                        .uri(URI.create(url))
                                        .header("Content-Type", "application/x-www-form-urlencoded")
                                        .POST(
                                                HttpRequest.BodyPublishers.ofString(
                                                        formBody, StandardCharsets.UTF_8))
                                        .build();

                        HttpResponse<String> response =
                                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() == 200) {
                            log.info("[TELEGRAM] Alert sent successfully to chat {}", chatId);
                        } else {
                            log.warn(
                                    "[TELEGRAM] Failed to send alert (HTTP {}): {}",
                                    response.statusCode(),
                                    response.body());
                        }
                    } catch (Exception e) {
                        log.error("[TELEGRAM] Alert dispatch error: {}", e.getMessage());
                    }
                },
                asyncExecutor);
    }
}
