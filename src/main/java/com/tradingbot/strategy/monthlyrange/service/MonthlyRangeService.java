package com.tradingbot.strategy.monthlyrange.service;

import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.marketdata.YahooFinanceService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.strategy.monthlyrange.config.MonthlyRangeProperties;
import com.tradingbot.strategy.monthlyrange.engine.MonthlyRangeCalculator;
import com.tradingbot.strategy.monthlyrange.model.MarketBias;
import com.tradingbot.strategy.monthlyrange.model.MonthlyRangeForecast;
import com.tradingbot.strategy.monthlyrange.model.MonthlyRangeReport;
import com.tradingbot.telegram.TelegramService;
import com.tradingbot.util.BotLocale;
import com.tradingbot.util.NseTradingCalendarUtil;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * Orchestrates monthly range forecasting, multi-asset quantitative calculation, and Telegram
 * advisory reporting for NSE equities and benchmark indices with GARCH + IV + OI Fusion.
 */
@Service
public class MonthlyRangeService {

    private static final Logger log = LoggerFactory.getLogger(MonthlyRangeService.class);
    private static final DateTimeFormatter CYCLE_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final MonthlyRangeProperties properties;
    private final MonthlyRangeCalculator calculator;
    private final YahooFinanceService yahooFinanceService;
    private final ShoonyaOptionChainService optionChainService;
    private final TelegramService telegramService;

    @Autowired
    public MonthlyRangeService(
            MonthlyRangeProperties properties,
            MonthlyRangeCalculator calculator,
            YahooFinanceService yahooFinanceService,
            @Autowired(required = false) ShoonyaOptionChainService optionChainService,
            @Autowired(required = false) TelegramService telegramService) {
        this.properties = properties;
        this.calculator = calculator;
        this.yahooFinanceService = yahooFinanceService;
        this.optionChainService = optionChainService;
        this.telegramService = telegramService;
    }

    /**
     * Executes the monthly range forecasting workflow across all configured symbols, builds a
     * comprehensive report, and sends Telegram alerts.
     */
    public MonthlyRangeReport generateMonthlyReport() {
        LocalDate today = LocalDate.now(NseTradingCalendarUtil.IST_ZONE);
        String cycle = today.format(CYCLE_FMT);
        List<String> symbols = properties.getSymbols();
        int horizon = properties.getForecastHorizonDays();

        log.info(
                "[MONTHLY-RANGE] Starting fused monthly range forecast for cycle {} with {} symbols: {}",
                cycle,
                symbols.size(),
                symbols);

        List<MonthlyRangeForecast> forecasts = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (String symbol : symbols) {
            try {
                MonthlyRangeForecast forecast = generateForecastForSymbol(symbol, horizon, today);
                forecasts.add(forecast);
                successCount++;
            } catch (Exception e) {
                log.error(
                        "[MONTHLY-RANGE] Error calculating monthly range for {}: {}",
                        symbol,
                        e.getMessage(),
                        e);
                failureCount++;
                forecasts.add(calculator.calculate(symbol, List.of(), horizon, today, null));
            }
        }

        String summary =
                String.format(
                        BotLocale.INSTANCE,
                        "Monthly range forecast completed for cycle %s (%d succeeded, %d failed).",
                        cycle,
                        successCount,
                        failureCount);

        List<com.tradingbot.strategy.monthlyrange.model.MonthlyDirectionalScore> directionalScores =
                forecasts.stream()
                        .map(MonthlyRangeForecast::directionalScore)
                        .filter(java.util.Objects::nonNull)
                        .toList();

        com.tradingbot.strategy.monthlyrange.model.MonthlyUniverseRanking ranking =
                com.tradingbot.strategy.monthlyrange.model.MonthlyUniverseRanking.fromScores(
                        directionalScores, properties.getStrongConvictionThreshold());

        MonthlyRangeReport report =
                new MonthlyRangeReport(Instant.now(), cycle, forecasts, summary, ranking);

        // Dispatch Telegram Notification if enabled
        if (properties.isTelegramAlertsEnabled() && telegramService != null) {
            try {
                String telegramMsg = formatTelegramMessage(report);
                telegramService.sendTextMessage(telegramMsg);
            } catch (Exception e) {
                log.error(
                        "[MONTHLY-RANGE] Failed to dispatch Telegram report: {}",
                        e.getMessage(),
                        e);
            }
        }

        return report;
    }

    /**
     * Calculates the monthly range forecast for a single symbol using default configured horizon.
     */
    public MonthlyRangeForecast generateForecastForSymbol(String symbol) {
        return generateForecastForSymbol(
                symbol,
                properties.getForecastHorizonDays(),
                LocalDate.now(NseTradingCalendarUtil.IST_ZONE));
    }

    /** Calculates the monthly range forecast for a single symbol with specified horizon. */
    public MonthlyRangeForecast generateForecastForSymbol(String symbol, int horizonDays) {
        return generateForecastForSymbol(
                symbol, horizonDays, LocalDate.now(NseTradingCalendarUtil.IST_ZONE));
    }

    /**
     * Calculates the monthly range forecast for a single symbol with specified horizon and date.
     */
    public MonthlyRangeForecast generateForecastForSymbol(
            String symbol, int horizonDays, LocalDate asOfDate) {
        int yearsBack = properties.getHistoryLookbackDays() > 252 ? 2 : 1;
        List<Candle> dailyCandles = yahooFinanceService.fetchDailyCandles(symbol, yearsBack);

        OptionChainResponse optionChain = null;
        if (optionChainService != null) {
            try {
                optionChain =
                        optionChainService.getIndexOptionChain(
                                symbol, null, properties.getOptionChainStrikeCount(), true);
            } catch (Exception e) {
                log.warn(
                        "[MONTHLY-RANGE] Failed to fetch live option chain for {}: {}",
                        symbol,
                        e.getMessage(),
                        e);
            }
        }

        return calculator.calculate(symbol, dailyCandles, horizonDays, asOfDate, optionChain);
    }

    /**
     * Formats the Monthly Range Report into a crisp 2-sigma & direction Markdown message for
     * Telegram.
     */
    public String formatTelegramMessage(MonthlyRangeReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 *MONTHLY OPTION RANGE (2σ)*\n");
        sb.append("📅 *Cycle:* `")
                .append(report.cycle())
                .append("` | *Horizon:* ")
                .append(properties.getForecastHorizonDays())
                .append(" Trading Days\n\n");

        for (MonthlyRangeForecast f : report.forecasts()) {
            if (f.spotPrice().compareTo(BigDecimal.ZERO) <= 0) {
                sb.append("⚠️ *").append(f.symbol()).append("*: Data Unavailable\n\n");
                continue;
            }

            var ds = f.directionalScore();
            String dirIcon = "⚖️";
            String biasStr = "NEUTRAL";
            String confStr = "";
            if (ds != null) {
                dirIcon =
                        ds.bias() == MarketBias.BULLISH
                                ? "🐂"
                                : ds.bias() == MarketBias.BEARISH ? "🐻" : "⚖️";
                biasStr = ds.bias().name();
                double conf =
                        ds.bias() == MarketBias.BULLISH
                                ? ds.bullishProbabilityPct()
                                : (ds.bias() == MarketBias.BEARISH
                                        ? ds.bearishProbabilityPct()
                                        : Math.max(
                                                ds.bullishProbabilityPct(),
                                                ds.bearishProbabilityPct()));
                confStr = String.format(BotLocale.INSTANCE, " (`%.0f%%`)", conf);
            }

            sb.append("🔹 *")
                    .append(f.symbol())
                    .append("* (Spot: ₹")
                    .append(f.spotPrice())
                    .append(")\n");
            if (f.lower1Sd() != null && f.upper1Sd() != null) {
                BigDecimal halfDiff =
                        f.spotPrice()
                                .subtract(f.lower1Sd())
                                .abs()
                                .setScale(0, RoundingMode.HALF_UP);
                sb.append(" • *Monthly Range (1σ):* `₹")
                        .append(f.lower1Sd())
                        .append(" – ₹")
                        .append(f.upper1Sd())
                        .append("` (±")
                        .append(halfDiff)
                        .append(" pts)\n");
            }
            sb.append(" • *Max Boundary (2σ):* `₹")
                    .append(f.lower2Sd())
                    .append(" – ₹")
                    .append(f.upper2Sd())
                    .append("`\n");
            sb.append(" • *Direction:* ")
                    .append(dirIcon)
                    .append(" *")
                    .append(biasStr)
                    .append("*")
                    .append(confStr)
                    .append("\n");

            if (f.safePeStrike() != null && f.safeCeStrike() != null) {
                sb.append(" • *Safe Strikes:* PE `₹")
                        .append(f.safePeStrike())
                        .append("` | CE `₹")
                        .append(f.safeCeStrike())
                        .append("`\n");
            }

            BigDecimal step =
                    f.strikeStep() != null && f.strikeStep().compareTo(BigDecimal.ZERO) > 0
                            ? f.strikeStep()
                            : BigDecimal.valueOf(50);
            BigDecimal wingOffset = step.multiply(BigDecimal.valueOf(2));

            if (ds != null && ds.bias() == MarketBias.BULLISH && f.safePeStrike() != null) {
                BigDecimal longPe = f.safePeStrike().subtract(wingOffset);
                sb.append(" • 💡 *Recommended Trade:* *Bull Put Spread*\n")
                        .append("    ➡️ `Sell PE ₹")
                        .append(f.safePeStrike())
                        .append(" | Buy PE ₹")
                        .append(longPe)
                        .append("`\n")
                        .append("    🎯 _Exit:_ Book profit at 60%–70% decay.\n");
            } else if (ds != null && ds.bias() == MarketBias.BEARISH && f.safeCeStrike() != null) {
                BigDecimal longCe = f.safeCeStrike().add(wingOffset);
                sb.append(" • 💡 *Recommended Trade:* *Bear Call Spread*\n")
                        .append("    ➡️ `Sell CE ₹")
                        .append(f.safeCeStrike())
                        .append(" | Buy CE ₹")
                        .append(longCe)
                        .append("`\n")
                        .append("    🎯 _Exit:_ Book profit at 60%–70% decay.\n");
            } else if (f.safePeStrike() != null && f.safeCeStrike() != null) {
                BigDecimal longPe = f.safePeStrike().subtract(wingOffset);
                BigDecimal longCe = f.safeCeStrike().add(wingOffset);
                sb.append(" • 💡 *Recommended Trade:* *Iron Condor*\n")
                        .append("    ➡️ `Sell PE ₹")
                        .append(f.safePeStrike())
                        .append("/Buy PE ₹")
                        .append(longPe)
                        .append(" & Sell CE ₹")
                        .append(f.safeCeStrike())
                        .append("/Buy CE ₹")
                        .append(longCe)
                        .append("`\n")
                        .append("    🎯 _Exit:_ Book profit at 50%–60% decay.\n");
            }
            sb.append("\n");
        }

        return sb.toString().trim();
    }
}
