package com.tradingbot.telegram;

import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.model.execution.ActiveSpreadPosition;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.LowestVolumePaperPosition;
import com.tradingbot.model.strategy.LowestVolumeSetup;
import com.tradingbot.model.strategy.OhlvDirection;
import com.tradingbot.model.strategy.OhlvPaperPosition;
import com.tradingbot.model.strategy.OhlvSetup;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
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
import java.util.List;
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
     * LowestVolumeReversalService).
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
     * LowestVolumeReversalService).
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

    /**
     * Sends an exit alert for an Option Buying position where: Est. PnL Points = Exit Premium -
     * Entry Premium.
     */
    public void sendOptionBuyingExitAlert(
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
            log.debug(
                    "[TELEGRAM] Option Buying Exit alert skipped (Telegram disabled or credentials empty)");
            return;
        }

        // For Option Buying: PnL Points = Exit Premium - Entry Premium
        BigDecimal premiumDiff =
                (entryPremium != null && exitPremium != null)
                        ? exitPremium.subtract(entryPremium).setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
        String pnlEmoji = premiumDiff.signum() >= 0 ? "🟢" : "🔴";
        String pnlSign = premiumDiff.signum() >= 0 ? "+" : "";

        String message =
                String.format(
                        "🏁 *[OPTION BUYING EXIT ALERT]* 🏁\n"
                                + "📈 *Strategy:* %s\n"
                                + "🎯 *Underlying:* %s\n"
                                + "👉 *Action:* %s\n\n"
                                + "📊 *Exit Details:*\n"
                                + "   • *Strike:* %.0f %s\n"
                                + "   • *Underlying Exit Price:* ₹%.2f\n"
                                + "   • *Option Entry Premium:* ₹%.2f\n"
                                + "   • *Option Exit Premium:* ₹%.2f\n"
                                + "   • %s *Realized P&L:* *%s₹%.2f* / share\n\n"
                                + "ℹ️ *Exit Reason:* %s\n"
                                + "⏰ *Exit Time:* %s IST",
                        strategyName != null ? strategyName : "Option Buying Strategy",
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
                        exitReason != null ? exitReason : "Fast SuperTrend Reversal",
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

    /** Sends an alert when a Lowest Volume Reversal setup passes all filters and entry is ARMED. */
    public void sendLvrSetupArmedAlert(LowestVolumeSetup setup, int minLots, BigDecimal rpt) {
        if (!config.isTelegramEnabled()
                || config.getTelegramBotToken().isBlank()
                || config.getTelegramChatId().isBlank()) {
            return;
        }

        boolean longDir = setup.getDirection() == LowestVolumeDirection.LONG;
        String dirEmoji = longDir ? "🟢 [LONG SETUP ARMED]" : "🔴 [SHORT SETUP ARMED]";
        String optionType = longDir ? "CE" : "PE";
        String triggerLabel = longDir ? "Breakout Above High" : "Breakdown Below Low";
        String slLabel = longDir ? "Low - 1 tick" : "High + 1 tick";

        String message =
                String.format(
                        "🎯 *%s* 🎯\n"
                                + "📈 *Strategy:* Lowest Volume Reversal (5m)\n"
                                + "🏷️ *Symbol:* `%s` — Buy ATM %s (Monthly)\n"
                                + "⚡ *Trigger Condition:* %s\n\n"
                                + "📊 *Stock Setup Levels:*\n"
                                + "   • *Trigger Price:* ₹%.2f\n"
                                + "   • *Stop-Loss (SL):* ₹%.2f (%s)\n"
                                + "   • *Target 1 (1:2 RR):* ₹%.2f\n"
                                + "   • *Trigger Candle Vol:* %,d (Lowest from start of day)\n"
                                + "   • *5m ATR(14):* ₹%.2f\n\n"
                                + "💼 *Option Execution:*\n"
                                + "   • *Instrument:* ATM %s, strike fixed at entry\n"
                                + "   • *Min Lots:* %d (final lots sized from live premium at fill)\n"
                                + "   • *Risk at Stake:* ₹%.2f\n"
                                + "⏳ *Timeout:* Order expires if not filled within 6 bars (30 min)\n"
                                + "⏰ *Time:* %s IST",
                        dirEmoji,
                        setup.getSymbol(),
                        optionType,
                        triggerLabel,
                        setup.getTriggerPrice(),
                        setup.getStopLossPrice(),
                        slLabel,
                        setup.getTarget1Price(),
                        setup.getTriggerCandleVolume(),
                        setup.getAtr14(),
                        optionType,
                        minLots,
                        rpt != null ? rpt : BigDecimal.valueOf(1000),
                        TIME_FMT.format(Instant.now()));

        sendAsync(message);
    }

    /** Sends an alert when a Paper Trade Entry is filled for Lowest Volume Reversal. */    public void sendLvrTradeEntryAlert(LowestVolumePaperPosition pos, LowestVolumeSetup setup) {
        if (!config.isTelegramEnabled()
                || config.getTelegramBotToken().isBlank()
                || config.getTelegramChatId().isBlank()) {
            return;
        }

        boolean isLong = pos.getDirection() == LowestVolumeDirection.LONG;
        String dirEmoji =
                isLong
                        ? "🚀 🟢 *[TRADE SIGNAL: BUY CALL]* 🟢 🚀"
                        : "🔻 🔴 *[TRADE SIGNAL: BUY PUT]* 🔴 🔻";
        String actionLabel = isLong ? "BUY CALL (CE)" : "BUY PUT (PE)";

        String message =
                String.format(
                        "%s\n\n"
                                + "📈 *Strategy:* Lowest Volume Reversal (5m)\n"
                                + "⚡ *ACTION:* *%s*\n"
                                + "🏷️ *Trade ID:* `%s`\n"
                                + "📌 *Symbol:* `%s`\n"
                                + "🎯 *Option Contract:* ATM `%s` (Strike: ₹%s)\n"
                                + "💰 *Entry Premium:* ₹%.2f\n\n"
                                + "📊 *Trade Levels:*\n"
                                + "   • *Lots:* %d × %d = %d units\n"
                                + "   • *Stock SL:* ₹%.2f\n"
                                + "   • *Stock Target 1 (1:2 RR):* ₹%.2f\n"
                                + "   • *Risk Allocated:* ₹%.2f\n\n"
                                + "🛡️ *Trade Management:*\n"
                                + "   • At Target 1: Book 50%% profit & Move SL to Breakeven\n"
                                + "   • Runner 50%%: Trailed via 5m SuperTrend(10, 3)\n"
                                + "⏰ *Entry Time:* %s IST",
                        dirEmoji,
                        actionLabel,
                        pos.getTradeId(),
                        pos.getSymbol(),
                        pos.getOptionType(),
                        pos.getAtmStrike(),
                        pos.getEntryPremium(),
                        pos.getLots(),
                        pos.getLotSize(),
                        pos.getTotalQuantity(),
                        pos.getCurrentStockSl(),
                        pos.getTarget1StockPrice(),
                        pos.getPlannedRisk(),
                        TIME_FMT.format(pos.getEntryTime()));

        sendAsync(message);
    }

    /** Sends an alert when Target 1 (1:2 RR) is hit: 50% booked and SL moved to Breakeven. */
    public void sendLvrPartialBookAlert(LowestVolumePaperPosition pos, BigDecimal partialPnl) {
        if (!config.isTelegramEnabled()
                || config.getTelegramBotToken().isBlank()
                || config.getTelegramChatId().isBlank()) {
            return;
        }

        int bookedQty = (pos.getTotalQuantity() + 1) / 2;

        String message =
                String.format(
                        "💰 *[TARGET 1 HIT: 50%% PROFIT BOOKED]* 💰\n"
                                + "📈 *Strategy:* Lowest Volume Reversal (1:2 RR Achieved)\n"
                                + "🏷️ *Trade ID:* `%s`\n"
                                + "📌 *Symbol:* `%s` %s ₹%s\n\n"
                                + "📊 *Booking Execution:*\n"
                                + "   • *Entry Premium:* ₹%.2f\n"
                                + "   • *Exit Premium:* ₹%.2f\n"
                                + "   • *Units Booked:* %d (50%%)\n"
                                + "   • 🟢 *Partial Realized P&L:* *₹%.2f*\n\n"
                                + "🛡️ *Risk Free Mode Activated:*\n"
                                + "   • *Remaining Runner:* %d units\n"
                                + "   • *New SL:* Entry Premium (Breakeven)\n"
                                + "   • *Trailing Engine:* 5m SuperTrend(10, 3)\n"
                                + "⏰ *Time:* %s IST",
                        pos.getTradeId(),
                        pos.getSymbol(),
                        pos.getOptionType(),
                        pos.getAtmStrike(),
                        pos.getEntryPremium(),
                        pos.getPartialExitPremium(),
                        bookedQty,
                        partialPnl,
                        pos.getRemainingQuantity(),
                        TIME_FMT.format(
                                pos.getPartialExitTime() != null
                                        ? pos.getPartialExitTime()
                                        : Instant.now()));

        sendAsync(message);
    }

    /**
     * Sends an alert when a position is fully exited (SL hit, SuperTrend flip, or 15:00 Hard Exit).
     */
    public void sendLvrTradeExitAlert(LowestVolumePaperPosition pos, String reason) {
        if (!config.isTelegramEnabled()
                || config.getTelegramBotToken().isBlank()
                || config.getTelegramChatId().isBlank()) {
            return;
        }

        String pnlEmoji =
                (pos.getTotalRealizedPnl() != null && pos.getTotalRealizedPnl().signum() >= 0)
                        ? "🟢"
                        : "🔴";
        String pnlSign =
                (pos.getTotalRealizedPnl() != null && pos.getTotalRealizedPnl().signum() >= 0)
                        ? "+"
                        : "";

        String message =
                String.format(
                        "🏁 *[PAPER TRADE CLOSED]* 🏁\n"
                                + "📈 *Strategy:* Lowest Volume Reversal & Continuation\n"
                                + "🏷️ *Trade ID:* `%s`\n"
                                + "📌 *Symbol:* `%s` %s ₹%s\n\n"
                                + "📊 *Trade Summary:*\n"
                                + "   • *Entry Premium:* ₹%.2f\n"
                                + "   • *Exit Premium:* ₹%.2f\n"
                                + "   • *Units:* %d\n"
                                + "   • *Partial Booking P&L:* ₹%.2f\n"
                                + "   • *Runner P&L:* ₹%.2f\n"
                                + "   • %s *Total Realized P&L:* *%s₹%.2f*\n\n"
                                + "ℹ️ *Exit Reason:* %s\n"
                                + "⏰ *Exit Time:* %s IST",
                        pos.getTradeId(),
                        pos.getSymbol(),
                        pos.getOptionType(),
                        pos.getAtmStrike(),
                        pos.getEntryPremium(),
                        pos.getRunnerExitPremium() != null
                                ? pos.getRunnerExitPremium()
                                : pos.getEntryPremium(),
                        pos.getTotalQuantity(),
                        pos.getPartialPnl(),
                        pos.getRunnerPnl(),
                        pnlEmoji,
                        pnlSign,
                        pos.getTotalRealizedPnl(),
                        reason,
                        TIME_FMT.format(
                                pos.getExitTime() != null ? pos.getExitTime() : Instant.now()));

        sendAsync(message);
    }

    /**
     * Sends a detailed alert with all identified F&O stocks (Top Gainers & Losers) including live
     * spot prices, % changes, day open, and market alignment.
     */
    public void sendLvrIdentifiedStocksAlert(
            List<StockQuoteSnapshot> topGainers,
            List<StockQuoteSnapshot> topLosers,
            boolean niftyBullish,
            int activeSetupsCount,
            int openTradesCount) {
        if (!config.isTelegramEnabled()
                || config.getTelegramBotToken().isBlank()
                || config.getTelegramChatId().isBlank()) {
            return;
        }

        String niftyEmoji =
                niftyBullish
                        ? "🟢 BULLISH (Long Trades Active)"
                        : "🔴 BEARISH (Short Trades Active)";
        String activeSide = niftyBullish ? "✅ Longs Eligible" : "✅ Shorts Eligible";

        StringBuilder sb = new StringBuilder();
        sb.append("📊 *[LOWEST VOLUME REVERSAL: 09:25 AM FIXED WATCHLIST]* 📊\n\n");
        sb.append("🕒 *Scan Time:* ")
                .append(TIME_FMT.format(Instant.now()))
                .append(" IST (Daily Fixed List)\n");
        sb.append("🧭 *NIFTY 50 Direction:* ").append(niftyEmoji).append("\n");
        sb.append("📈 *Execution Status:* ").append(activeSide).append("\n");
        sb.append(
                "📌 *Notice:* This list is fixed for the day. Strategy will monitor this basket exclusively.\n\n");

        sb.append("🟢 *TOP GAINERS (LONG CANDIDATES):*\n");
        if (topGainers == null || topGainers.isEmpty()) {
            sb.append("   • None meeting >= +1.0% threshold\n");
        } else {
            for (int i = 0; i < topGainers.size(); i++) {
                StockQuoteSnapshot s = topGainers.get(i);
                sb.append(
                        String.format(
                                "%d. *%s* ➔ Spot: *₹%.2f* (+%.2f%%) | Open: ₹%.2f\n",
                                (i + 1), s.symbol(), s.ltp(), s.pctChange(), s.open()));
            }
        }

        sb.append("\n🔴 *TOP LOSERS (SHORT CANDIDATES):*\n");
        if (topLosers == null || topLosers.isEmpty()) {
            sb.append("   • None meeting <= -1.0% threshold\n");
        } else {
            for (int i = 0; i < topLosers.size(); i++) {
                StockQuoteSnapshot s = topLosers.get(i);
                sb.append(
                        String.format(
                                "%d. *%s* ➔ Spot: *₹%.2f* (%.2f%%) | Open: ₹%.2f\n",
                                (i + 1), s.symbol(), s.ltp(), s.pctChange(), s.open()));
            }
        }

        sb.append(
                String.format(
                        "\n🎯 *Tracked Setups Forming:* `%d` | 💼 *Open Paper Positions:* `%d` / 2\n",
                        activeSetupsCount, openTradesCount));
        sb.append("🤖 *Automation:* 5-min candle polling active on this list (09:26 - 15:00 IST)");

        sendAsync(sb.toString());
    }

    /**
     * Sends an intraday scan summary report with Top Gainers/Losers and Nifty Breadth alignment.
     */
    public void sendLvrScanSummaryAlert(
            List<String> topGainers,
            List<String> topLosers,
            boolean niftyBullish,
            int activeSetupsCount,
            int openTradesCount) {
        if (!config.isTelegramEnabled()
                || config.getTelegramBotToken().isBlank()
                || config.getTelegramChatId().isBlank()) {
            return;
        }

        String niftyEmoji =
                niftyBullish ? "🟢 BULLISH (Longs Eligible)" : "🔴 BEARISH (Shorts Eligible)";

        StringBuilder sb = new StringBuilder();
        sb.append("📊 *[LOWEST VOLUME REVERSAL: SCANNER UPDATE]* 📊\n\n");
        sb.append("🕒 *Time:* ").append(TIME_FMT.format(Instant.now())).append(" IST\n");
        sb.append("🧭 *NIFTY 50 Alignment:* ").append(niftyEmoji).append("\n\n");

        sb.append("🟢 *Top Gainers (Long Watchlist):*\n");
        if (topGainers == null || topGainers.isEmpty()) {
            sb.append("   • None meeting >= +1.0% threshold\n");
        } else {
            sb.append("   `").append(String.join(", ", topGainers)).append("`\n");
        }

        sb.append("\n🔴 *Top Losers (Short Watchlist):*\n");
        if (topLosers == null || topLosers.isEmpty()) {
            sb.append("   • None meeting <= -1.0% threshold\n");
        } else {
            sb.append("   `").append(String.join(", ", topLosers)).append("`\n");
        }

        sb.append(
                String.format(
                        "\n🎯 *Active Setups Tracking:* `%d` | 💼 *Open Paper Trades:* `%d` / 2\n",
                        activeSetupsCount, openTradesCount));
        sb.append("🤖 *Automation:* 5-min candle polling active (09:26 - 15:00 IST)");

        sendAsync(sb.toString());
    }

    /**
     * Sends an alert when an RSI Crossover option trade is executed (Single Leg or Hedged Spread).
     */
    public void sendRsiCrossoverEntryAlert(
            com.tradingbot.model.strategy.RsiCrossoverPosition position,
            double rsi5,
            double rsi15,
            double prevRsi5,
            double prevRsi15) {
        if (!config.isTelegramEnabled()
                || config.getTelegramBotToken().isBlank()
                || config.getTelegramChatId().isBlank()) {
            return;
        }

        String action = position.getAction() != null ? position.getAction().toUpperCase() : "BUY";
        boolean isBullish = ("BUY".equals(action) && "CE".equalsIgnoreCase(position.getOptionType()))
                || ("SELL".equals(action) && "PE".equalsIgnoreCase(position.getOptionType()));

        String header;
        String direction;
        StringBuilder details = new StringBuilder();

        if (position.isHedgeEnabled()) {
            header = isBullish ? "BULL PUT SPREAD (2% OTM HEDGE)" : "BEAR CALL SPREAD (2% OTM HEDGE)";
            direction = isBullish ? "🟢 BULLISH (5m RSI > 15m RSI)" : "🔴 BEARISH (5m RSI < 15m RSI)";
            
            BigDecimal netCredit = position.getNetCredit();
            BigDecimal strikeDiff = position.getHedgeStrike() != null && position.getStrike() != null
                    ? position.getStrike().subtract(position.getHedgeStrike()).abs()
                    : BigDecimal.ZERO;
            BigDecimal maxRiskPerShare = strikeDiff.subtract(netCredit).max(BigDecimal.ZERO);

            String mainStrikeLabel = position.getStrike() != null
                    ? String.format("NIFTY %.0f %s", position.getStrike().doubleValue(), position.getOptionType())
                    : position.getOptionType();
            String hedgeStrikeLabel = position.getHedgeStrike() != null
                    ? String.format("NIFTY %.0f %s", position.getHedgeStrike().doubleValue(), position.getOptionType())
                    : position.getOptionType();

            details.append(String.format("   • *Sell Leg (ATM):* SELL *%s* (`%s`) @ ₹%.2f\n",
                    mainStrikeLabel, position.getSymbol(), position.getEntryPrice() != null ? position.getEntryPrice().doubleValue() : 0.0));
            details.append(String.format("   • *Hedge Leg (2%% OTM):* BUY *%s* (`%s`) @ ₹%.2f\n",
                    hedgeStrikeLabel, position.getHedgeSymbol(), position.getHedgeEntryPrice() != null ? position.getHedgeEntryPrice().doubleValue() : 0.0));
            details.append(String.format("   • *Net Credit:* ₹%.2f / share\n", netCredit != null ? netCredit.doubleValue() : 0.0));
            details.append(String.format("   • *Defined Max Risk:* ₹%.2f / share\n", maxRiskPerShare.doubleValue()));
        } else {
            header = "SELL".equals(action) ? "OPTION SELL" : "OPTION BUY";
            direction = isBullish ? "🟢 BULLISH (5m RSI > 15m RSI)" : "🔴 BEARISH (5m RSI < 15m RSI)";
            String strikeLabel = position.getStrike() != null
                    ? String.format("NIFTY %.0f %s", position.getStrike().doubleValue(), position.getOptionType())
                    : position.getOptionType();
            details.append(String.format("   • *Strike / Leg:* %s *%s* (`%s`)\n",
                    action, strikeLabel, position.getSymbol()));
            details.append(String.format("   • *Entry Premium:* ₹%.2f\n", position.getEntryPrice() != null ? position.getEntryPrice().doubleValue() : 0.0));
        }

        String message =
                String.format(
                        "🚀 *[NIFTY RSI CROSSOVER: %s]* 🚀\n\n"
                                + "🧭 *Direction:* %s\n"
                                + "⚡ *Action:* `%s %s`\n"
                                + "📦 *Quantity:* %d units\n\n"
                                + "📊 *Execution Details:*\n%s\n"
                                + "📈 *Current RSI:* 5m: `%.1f` | 15m: `%.1f`\n"
                                + "📉 *Previous RSI:* 5m: `%.1f` | 15m: `%.1f`\n"
                                + "🕒 *Time:* %s IST",
                        header,
                        direction,
                        action,
                        position.getOptionType(),
                        position.getQuantity(),
                        details.toString(),
                        rsi5,
                        rsi15,
                        prevRsi5,
                        prevRsi15,
                        TIME_FMT.format(position.getEntryTime() != null ? position.getEntryTime() : Instant.now()));

        sendAsync(message);
    }

    /**
     * Sends an alert when an RSI Crossover option trade is exited (Reversal, SL, TP, or EOD).
     */
    public void sendRsiCrossoverExitAlert(
            com.tradingbot.model.strategy.RsiCrossoverPosition position,
            String reason) {
        if (!config.isTelegramEnabled()
                || config.getTelegramBotToken().isBlank()
                || config.getTelegramChatId().isBlank()) {
            return;
        }

        BigDecimal totalPnl = position.getTotalRealizedPnl();
        String pnlEmoji = totalPnl.signum() >= 0 ? "🟢" : "🔴";
        String pnlSign = totalPnl.signum() >= 0 ? "+" : "";

        StringBuilder details = new StringBuilder();
        if (position.isHedgeEnabled()) {
            String mainStrikeLabel = position.getStrike() != null
                    ? String.format("NIFTY %.0f %s", position.getStrike().doubleValue(), position.getOptionType())
                    : position.getOptionType();
            String hedgeStrikeLabel = position.getHedgeStrike() != null
                    ? String.format("NIFTY %.0f %s", position.getHedgeStrike().doubleValue(), position.getOptionType())
                    : position.getOptionType();

            details.append(String.format("   • *Main Sell Leg (%s):* `%s` | Entry: ₹%.2f ➔ Exit: ₹%.2f | P&L: ₹%.2f\n",
                    mainStrikeLabel,
                    position.getSymbol(),
                    position.getEntryPrice() != null ? position.getEntryPrice().doubleValue() : 0.0,
                    position.getExitPrice() != null ? position.getExitPrice().doubleValue() : 0.0,
                    position.getPnl() != null ? position.getPnl().doubleValue() : 0.0));
            details.append(String.format("   • *Hedge Buy Leg (%s):* `%s` | Entry: ₹%.2f ➔ Exit: ₹%.2f | P&L: ₹%.2f\n",
                    hedgeStrikeLabel,
                    position.getHedgeSymbol(),
                    position.getHedgeEntryPrice() != null ? position.getHedgeEntryPrice().doubleValue() : 0.0,
                    position.getHedgeExitPrice() != null ? position.getHedgeExitPrice().doubleValue() : 0.0,
                    position.getHedgePnl() != null ? position.getHedgePnl().doubleValue() : 0.0));
        } else {
            String strikeLabel = position.getStrike() != null
                    ? String.format("NIFTY %.0f %s", position.getStrike().doubleValue(), position.getOptionType())
                    : position.getOptionType();
            details.append(String.format("   • *Strike (%s):* `%s`\n", strikeLabel, position.getSymbol()));
            details.append(String.format("   • *Entry:* ₹%.2f | *Exit:* ₹%.2f\n",
                    position.getEntryPrice() != null ? position.getEntryPrice().doubleValue() : 0.0,
                    position.getExitPrice() != null ? position.getExitPrice().doubleValue() : 0.0));
        }

        String message =
                String.format(
                        "🏁 *[NIFTY RSI CROSSOVER: TRADE EXITED]* 🏁\n\n"
                                + "ℹ️ *Reason:* %s\n"
                                + "📊 *Trade Breakdown:*\n%s\n"
                                + "%s *Total Realized P&L:* *%s₹%.2f*\n"
                                + "🕒 *Exit Time:* %s IST",
                        reason,
                        details.toString(),
                        pnlEmoji,
                        pnlSign,
                        totalPnl.doubleValue(),
                        TIME_FMT.format(position.getExitTime() != null ? position.getExitTime() : Instant.now()));

        sendAsync(message);
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

    /**
     * Sends the 09:31 AM IST watchlist broadcast for the NIFTY 100 OHL-VWAP strategy. Lists each
     * qualifying setup with its direction (open=high → PE, open=low → CE) and marked levels.
     */
    public void sendOhlvWatchlistAlert(List<OhlvSetup> watchlist) {
        if (!config.isTelegramEnabled()
                || config.getTelegramBotToken().isBlank()
                || config.getTelegramChatId().isBlank()) {
            return;
        }

        if (watchlist == null || watchlist.isEmpty()) {
            sendAsync("📡 *OHL-VWAP: 09:31 AM WATCHLIST*\n\nNo qualifying setups today.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📡 *OHL-VWAP: 09:31 AM WATCHLIST*\n\n");
        for (OhlvSetup s : watchlist) {
            String dir =
                    s.getDirection() == OhlvDirection.BULLISH
                            ? "🟢 open=low → CE"
                            : "🔴 open=high → PE";
            sb.append(String.format("• `%s` %s (H ₹%s / L ₹%s)\n",
                    s.getSymbol(), dir, s.getMarkedHigh(), s.getMarkedLow()));
        }
        sendAsync(sb.toString());
    }

    /** Sends the paper entry alert with ATM strike, option type, lot size and entry premium. */
    public void sendOhlvEntryAlert(OhlvPaperPosition pos) {
        if (!config.isTelegramEnabled()
                || config.getTelegramBotToken().isBlank()
                || config.getTelegramChatId().isBlank()) {
            return;
        }

        boolean bullish = pos.getDirection() == OhlvDirection.BULLISH;
        String dirEmoji = bullish ? "🚀 🟢 *[OHL-VWAP: BUY CALL]* 🟢 🚀" : "🔻 🔴 *[OHL-VWAP: BUY PUT]* 🔴 🔻";
        String message =
                String.format(
                        "%s\n\n"
                                + "📈 *Strategy:* NIFTY 100 OHL-VWAP (Paper)\n"
                                + "⚡ *ACTION:* BUY ATM %s\n"
                                + "🏷️ *Trade ID:* `%s`\n"
                                + "📌 *Symbol:* `%s`\n"
                                + "🎯 *Strike:* ₹%s (%s)\n"
                                + "🔢 *Lot Size:* %d\n"
                                + "💰 *Entry Premium:* ₹%.2f\n\n"
                                + "⏰ *Entry Time:* %s IST",
                        dirEmoji,
                        pos.getOptionType(),
                        pos.getTradeId(),
                        pos.getSymbol(),
                        pos.getAtmStrike(),
                        bullish ? "BULLISH" : "BEARISH",
                        pos.getLotSize(),
                        pos.getEntryPremium(),
                        TIME_FMT.format(Instant.now()));

        sendAsync(message);
    }

    /** Sends the exit alert (VWAP opposite close or mandatory EOD square-off) with realized P&L. */
    public void sendOhlvExitAlert(OhlvPaperPosition pos, String reason) {
        if (!config.isTelegramEnabled()
                || config.getTelegramBotToken().isBlank()
                || config.getTelegramChatId().isBlank()) {
            return;
        }

        BigDecimal pnl = pos.getRealizedPnl();
        String pnlEmoji = pnl.signum() >= 0 ? "🟢" : "🔴";
        String message =
                String.format(
                        "*[OHL-VWAP: EXIT]* %s\n\n"
                                + "📌 *Symbol:* `%s` %s\n"
                                + "🏷️ *Trade ID:* `%s`\n"
                                + "📊 *P&L (Paper):* %s ₹%.2f\n"
                                + "📤 *Reason:* %s\n"
                                + "⏰ *Exit Time:* %s IST",
                        pnlEmoji,
                        pos.getSymbol(),
                        pos.getOptionType(),
                        pos.getTradeId(),
                        pnlEmoji,
                        pnl,
                        reason,
                        TIME_FMT.format(Instant.now()));

        sendAsync(message);
    }
}
