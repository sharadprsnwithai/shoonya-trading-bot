package com.tradingbot.strategy.monthlyrange.service;

import com.tradingbot.marketdata.YahooFinanceService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.monthlyrange.config.MonthlyRangeProperties;
import com.tradingbot.strategy.monthlyrange.engine.GarchRangeEngine;
import com.tradingbot.strategy.monthlyrange.model.GarchRangeBand;
import com.tradingbot.strategy.monthlyrange.model.GarchRangeForecast;
import com.tradingbot.strategy.monthlyrange.model.GarchRangeReport;
import com.tradingbot.strategy.monthlyrange.model.WingZoneForecast;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.BotLocale;
import com.tradingbot.util.NseTradingCalendarUtil;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Orchestrates short-dated GARCH(1,1) range forecasting for the weekly expiry cycle.
 *
 * <p>Where {@link MonthlyRangeService} projects a fixed 22-session horizon on post-expiry days,
 * this routine answers the question a weekly structure actually poses: given the last completed
 * close, how far can spot travel before the <i>next</i> expiry, and how often has that band held
 * historically.
 *
 * <p>The horizon is derived, never hardcoded — it is the trading-session distance from the last
 * completed session to the contractual expiry resolved through {@link
 * NseTradingCalendarUtil#getWeeklyExpiry}. A Wednesday deployment therefore reads as five sessions,
 * while the same endpoint called on a Monday correctly reads as two.
 */
@Service
public class GarchRangeService {

    private static final Logger log = LoggerFactory.getLogger(GarchRangeService.class);
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("EEE dd MMM");

    private final MonthlyRangeProperties properties;
    private final GarchRangeEngine rangeEngine;
    private final YahooFinanceService yahooFinanceService;
    private final TelegramService telegramService;

    @Autowired
    public GarchRangeService(
            MonthlyRangeProperties properties,
            GarchRangeEngine rangeEngine,
            YahooFinanceService yahooFinanceService,
            @Autowired(required = false) TelegramService telegramService) {
        this.properties = properties;
        this.rangeEngine = rangeEngine;
        this.yahooFinanceService = yahooFinanceService;
        this.telegramService = telegramService;
    }

    /** Builds the multi-symbol weekly range report and dispatches it to Telegram when enabled. */
    public GarchRangeReport generateWeeklyReport() {
        LocalDate today = LocalDate.now(NseTradingCalendarUtil.IST_ZONE);
        LocalDate referenceSession = lastCompletedSession(today);
        LocalDate expirySession =
                NseTradingCalendarUtil.getWeeklyExpiry(
                        today.plusDays(1), properties.resolveExpiryDayOfWeek());
        int horizon = resolveHorizon(referenceSession, expirySession);
        List<String> symbols = properties.getWeeklySymbols();

        log.info(
                "[GARCH-RANGE] Weekly range forecast: reference={}, expiry={}, horizon={} sessions,"
                        + " symbols={}",
                referenceSession,
                expirySession,
                horizon,
                symbols);

        List<GarchRangeForecast> forecasts = new ArrayList<>();
        int success = 0;
        int failure = 0;

        for (String symbol : symbols) {
            try {
                forecasts.add(
                        generateRangeForSymbol(symbol, horizon, referenceSession, expirySession));
                success++;
            } catch (Exception e) {
                log.error("[GARCH-RANGE] Failed to forecast {}: {}", symbol, e.getMessage(), e);
                failure++;
            }
        }

        String summary =
                String.format(
                        BotLocale.INSTANCE,
                        "Weekly range forecast completed for %s (%d succeeded, %d failed).",
                        expirySession,
                        success,
                        failure);

        GarchRangeReport report =
                new GarchRangeReport(
                        Instant.now(),
                        referenceSession,
                        expirySession,
                        horizon,
                        forecasts,
                        summary);

        if (properties.isTelegramAlertsEnabled() && telegramService != null) {
            try {
                telegramService.sendTextMessage(formatTelegramMessage(report));
            } catch (Exception e) {
                log.error(
                        "[GARCH-RANGE] Failed to dispatch Telegram report: {}", e.getMessage(), e);
            }
        }

        return report;
    }

    /**
     * Produces a range forecast for a single symbol using the current weekly horizon.
     *
     * @param symbol canonical symbol to forecast
     * @return the forecast for that symbol
     */
    public GarchRangeForecast generateRangeForSymbol(String symbol) {
        LocalDate today = LocalDate.now(NseTradingCalendarUtil.IST_ZONE);
        LocalDate referenceSession = lastCompletedSession(today);
        LocalDate expirySession =
                NseTradingCalendarUtil.getWeeklyExpiry(
                        today.plusDays(1), properties.resolveExpiryDayOfWeek());
        return generateRangeForSymbol(
                symbol,
                resolveHorizon(referenceSession, expirySession),
                referenceSession,
                expirySession);
    }

    /** Produces a range forecast for a single symbol over an explicit session window. */
    public GarchRangeForecast generateRangeForSymbol(
            String symbol,
            int horizonSessions,
            LocalDate referenceSession,
            LocalDate expirySession) {
        List<Candle> dailyCandles = yahooFinanceService.fetchDailyCandles(symbol, 2);
        return rangeEngine.forecastRange(
                symbol, dailyCandles, horizonSessions, referenceSession, expirySession);
    }

    /**
     * Trading sessions spanning the hold, measured from the last completed session through the
     * expiry inclusive. A Wednesday deployment into a Tuesday expiry therefore reads as five.
     *
     * @param referenceSession last completed session
     * @param expirySession contractual expiry
     * @return session count, floored at one
     */
    static int resolveHorizon(LocalDate referenceSession, LocalDate expirySession) {
        return Math.max(
                NseTradingCalendarUtil.tradingSessionsRemaining(referenceSession, expirySession),
                1);
    }

    /**
     * Last trading session strictly before {@code today}. A Tuesday deployment would otherwise
     * strike its spot from the same day's live candle, which is not yet a close.
     *
     * @param today the evaluation date
     * @return the most recent completed session
     */
    static LocalDate lastCompletedSession(LocalDate today) {
        LocalDate candidate = today.minusDays(1);
        while (!NseTradingCalendarUtil.isTradingDay(candidate)) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    /** Formats the weekly range report into a Markdown message for Telegram. */
    public String formatTelegramMessage(GarchRangeReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("📈 *WEEKLY RANGE FORECAST (GARCH 1,1, CALIBRATED)*\n");
        sb.append("📅 *Reference close:* `")
                .append(report.referenceSession().format(DAY_FMT))
                .append("` → *Expiry:* `")
                .append(report.expirySession().format(DAY_FMT))
                .append("` (")
                .append(report.horizonSessions())
                .append(" sessions)\n\n");

        for (GarchRangeForecast f : report.forecasts()) {
            if (f.spot().compareTo(BigDecimal.ZERO) <= 0) {
                sb.append("⚠️ *").append(f.symbol()).append("*: Insufficient history\n\n");
                continue;
            }

            sb.append("🔹 *")
                    .append(f.symbol())
                    .append("* (Spot: ₹")
                    .append(f.spot())
                    .append(")\n");
            sb.append(" • *Horizon σ:* `")
                    .append(f.horizonSigmaPct())
                    .append("%` (Ann ")
                    .append(f.annualizedVolPct())
                    .append("%, τ ")
                    .append(f.persistence())
                    .append(")\n");
            sb.append(" • *Calibration:* ")
                    .append(f.calibrationWindows())
                    .append(" out-of-sample windows\n");

            for (GarchRangeBand b : f.bands()) {
                sb.append(" • *")
                        .append(b.label())
                        .append(":* ₹")
                        .append(b.lower())
                        .append(" - ₹")
                        .append(b.upper())
                        .append("  (nominal ")
                        .append(b.nominalTerminalConfidencePct())
                        .append("% → measured ");
                if (b.measuredWindows() > 0) {
                    sb.append(b.measuredCoveragePct()).append("%");
                } else {
                    sb.append("n/a");
                }
                sb.append(")\n");
            }

            WingZoneForecast w = f.wingZone();
            if (w.measuredWindows() > 0) {
                sb.append(" • 🎯 *Wing zone* (")
                        .append(w.label())
                        .append("): ₹")
                        .append(w.lowerBreakeven())
                        .append(" - ₹")
                        .append(w.upperBreakeven())
                        .append("\n");
                sb.append(" • *Stays inside:* `")
                        .append(w.measuredStayPct())
                        .append("%` measured (")
                        .append(w.gaussianTerminalStayPct())
                        .append("% Gaussian terminal)\n");
            }
            sb.append("\n");
        }

        sb.append("💡 *Read this correctly:*\n")
                .append("1. \"Measured\" counts windows whose full high/low never left the band —")
                .append(" the number that matters for a hold-to-expiry structure.\n")
                .append("2. \"Nominal/Gaussian\" covers the closing return only and overstates the")
                .append(" true probability of staying inside.\n")
                .append("3. The wing zone is bounded by 2×short − wing, not by the short strike;")
                .append(" touching either breakeven during the hold is what costs money.\n")
                .append("4. GARCH sees volatility clustering, not expiry pinning or the")
                .append(" post-expiry IV reset that a Wednesday entry sits on.\n");
        return sb.toString();
    }
}
