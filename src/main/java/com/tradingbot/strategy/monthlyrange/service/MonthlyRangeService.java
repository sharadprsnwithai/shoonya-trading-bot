package com.tradingbot.strategy.monthlyrange.service;

import com.tradingbot.marketdata.YahooFinanceService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.monthlyrange.config.MonthlyRangeProperties;
import com.tradingbot.strategy.monthlyrange.engine.MonthlyRangeCalculator;
import com.tradingbot.strategy.monthlyrange.model.MonthlyRangeForecast;
import com.tradingbot.strategy.monthlyrange.model.MonthlyRangeReport;
import com.tradingbot.telegram.TelegramService;
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
 * advisory reporting for NSE equities and benchmark indices.
 */
@Service
public class MonthlyRangeService {

    private static final Logger log = LoggerFactory.getLogger(MonthlyRangeService.class);
    private static final DateTimeFormatter CYCLE_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final MonthlyRangeProperties properties;
    private final MonthlyRangeCalculator calculator;
    private final YahooFinanceService yahooFinanceService;
    private final TelegramService telegramService;

    @Autowired
    public MonthlyRangeService(
            MonthlyRangeProperties properties,
            MonthlyRangeCalculator calculator,
            YahooFinanceService yahooFinanceService,
            @Autowired(required = false) TelegramService telegramService) {
        this.properties = properties;
        this.calculator = calculator;
        this.yahooFinanceService = yahooFinanceService;
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
                "[MONTHLY-RANGE] Starting monthly range forecast for cycle {} with {} symbols: {}",
                cycle,
                symbols.size(),
                symbols);

        List<MonthlyRangeForecast> forecasts = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (String symbol : symbols) {
            try {
                MonthlyRangeForecast forecast = generateForecastForSymbol(symbol, horizon);
                forecasts.add(forecast);
                successCount++;
            } catch (Exception e) {
                log.error(
                        "[MONTHLY-RANGE] Error calculating monthly range for {}: {}",
                        symbol,
                        e.getMessage(),
                        e);
                failureCount++;
                forecasts.add(
                        calculator.calculate(symbol, List.of(), horizon));
            }
        }

        String summary =
                String.format(
                        "Monthly range forecast completed for cycle %s (%d succeeded, %d failed).",
                        cycle, successCount, failureCount);

        MonthlyRangeReport report =
                new MonthlyRangeReport(Instant.now(), cycle, forecasts, summary);

        // Dispatch Telegram Notification if enabled
        if (properties.isTelegramAlertsEnabled() && telegramService != null) {
            try {
                String telegramMsg = formatTelegramMessage(report);
                telegramService.sendTextMessage(telegramMsg);
            } catch (Exception e) {
                log.error("[MONTHLY-RANGE] Failed to dispatch Telegram report: {}", e.getMessage(), e);
            }
        }

        return report;
    }

    /**
     * Calculates the monthly range forecast for a single symbol using default configured horizon.
     */
    public MonthlyRangeForecast generateForecastForSymbol(String symbol) {
        return generateForecastForSymbol(symbol, properties.getForecastHorizonDays());
    }

    /**
     * Calculates the monthly range forecast for a single symbol with specified horizon.
     */
    public MonthlyRangeForecast generateForecastForSymbol(String symbol, int horizonDays) {
        int yearsBack = (properties.getHistoryLookbackDays() > 252) ? 2 : 1;
        List<Candle> dailyCandles = yahooFinanceService.fetchDailyCandles(symbol, yearsBack);
        return calculator.calculate(symbol, dailyCandles, horizonDays);
    }

    /**
     * Formats the Monthly Range Report into a crisp Markdown message for Telegram.
     */
    public String formatTelegramMessage(MonthlyRangeReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 *MONTHLY OPTION RANGE FORECAST (GARCH-1,1)*\n");
        sb.append("📅 *Cycle:* `").append(report.cycle()).append("` | *Horizon:* ")
                .append(properties.getForecastHorizonDays()).append(" Trading Days\n");
        sb.append("🎯 *Confidence Band:* 95.4% (2-SD Safe Selling Zone)\n\n");

        for (MonthlyRangeForecast f : report.forecasts()) {
            if (f.spotPrice().compareTo(BigDecimal.ZERO) <= 0) {
                sb.append("⚠️ *").append(f.symbol()).append("*: Data Unavailable\n\n");
                continue;
            }

            double peBuffer =
                    calculateBufferPct(f.spotPrice().doubleValue(), f.safePeStrike().doubleValue());
            double ceBuffer =
                    calculateBufferPct(f.safeCeStrike().doubleValue(), f.spotPrice().doubleValue());

            sb.append("🔹 *").append(f.symbol()).append("* (Spot: ₹").append(f.spotPrice()).append(")\n");
            sb.append(" • *GARCH Vol (Monthly):* `").append(f.monthlyVolPct()).append("%` (Ann: ").append(f.annualizedVolPct()).append("%)\n");
            sb.append(" • *1-SD Range (68%):* ₹").append(f.lower1Sd()).append(" - ₹").append(f.upper1Sd()).append("\n");
            sb.append(" • *2-SD Range (95%):* ₹").append(f.lower2Sd()).append(" - ₹").append(f.upper2Sd()).append("\n");
            sb.append(" • 🛡️ *Safe PE Strike:* `₹").append(f.safePeStrike()).append("` [ -").append(String.format(java.util.Locale.US, "%.1f", peBuffer)).append("% ]\n");
            sb.append(" • 🛡️ *Safe CE Strike:* `₹").append(f.safeCeStrike()).append("` [ +").append(String.format(java.util.Locale.US, "%.1f", ceBuffer)).append("% ]\n");
            sb.append(" • *ATR-22:* ₹").append(f.atr22()).append(" | *HV-30:* ").append(f.hv30AnnualizedPct()).append("%\n\n");
        }

        sb.append("💡 *Guideline:* Sell OTM Strangles outside 2-SD strikes post Tuesday expiry.");
        return sb.toString();
    }

    private double calculateBufferPct(double high, double low) {
        if (low <= 0) return 0.0;
        return ((high - low) / low) * 100.0;
    }
}
