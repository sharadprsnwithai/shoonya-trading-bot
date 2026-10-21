package com.tradingbot.strategy.monthlyrange.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tradingbot.marketdata.YahooFinanceService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.monthlyrange.config.MonthlyRangeProperties;
import com.tradingbot.strategy.monthlyrange.engine.Garch11Optimizer;
import com.tradingbot.strategy.monthlyrange.engine.GarchRangeEngine;
import com.tradingbot.strategy.monthlyrange.engine.GarchVolForecaster;
import com.tradingbot.strategy.monthlyrange.engine.GjrGarchOptimizer;
import com.tradingbot.strategy.monthlyrange.model.GarchRangeBand;
import com.tradingbot.strategy.monthlyrange.model.GarchRangeForecast;
import com.tradingbot.strategy.monthlyrange.model.GarchRangeReport;
import com.tradingbot.strategy.monthlyrange.model.ModelComparison;
import com.tradingbot.strategy.monthlyrange.model.WingZoneForecast;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GarchRangeServiceTest {

    private MonthlyRangeProperties properties;
    private YahooFinanceService yahooFinanceService;
    private TelegramService telegramService;
    private GarchRangeService service;

    @BeforeEach
    void setUp() {
        properties = new MonthlyRangeProperties();
        yahooFinanceService = mock(YahooFinanceService.class);
        telegramService = mock(TelegramService.class);
        service =
                new GarchRangeService(
                        properties,
                        new GarchRangeEngine(
                                new Garch11Optimizer(),
                                new GjrGarchOptimizer(),
                                new GarchVolForecaster(),
                                properties),
                        yahooFinanceService,
                        telegramService);
    }

    private GarchRangeForecast calibratedForecast(String symbol, double spot) {
        return new GarchRangeForecast(
                symbol,
                round2(spot),
                5,
                new BigDecimal("1.79"),
                new BigDecimal("12.66"),
                new BigDecimal("0.79"),
                0.95,
                LocalDate.of(2026, 10, 6),
                LocalDate.of(2026, 10, 13),
                List.of(
                        new GarchRangeBand(
                                "1.0 sigma",
                                1.0,
                                round2(spot * 0.982),
                                round2(spot * 1.018),
                                68.27,
                                41.20,
                                42),
                        new GarchRangeBand(
                                "2.0 sigma",
                                2.0,
                                round2(spot * 0.964),
                                round2(spot * 1.036),
                                95.45,
                                83.10,
                                42)),
                new BigDecimal("1.85"),
                List.of(
                        new GarchRangeBand(
                                "1.0 sigma",
                                1.0,
                                round2(spot * 0.9815),
                                round2(spot * 1.0185),
                                68.27,
                                44.05,
                                42)),
                new WingZoneForecast(
                        "150/200 wing structure",
                        round2(spot),
                        round2(spot - 270.0),
                        round2(spot + 270.0),
                        1.13,
                        62.4,
                        48.8,
                        42,
                        5,
                        150.0,
                        200.0,
                        20.0,
                        "measured vs gaussian"),
                new ModelComparison(
                        "GJR_GARCH",
                        -610.5,
                        -601.2,
                        1230.0,
                        1216.4,
                        1244.0,
                        1234.1,
                        0.031,
                        1.52,
                        12.4,
                        9.8,
                        48.8,
                        51.2,
                        205,
                        true,
                        "GJR wins on both lenses."),
                42,
                179,
                "CONVERGED",
                Instant.now());
    }

    private GarchRangeForecast noDataForecast(String symbol) {
        return new GarchRangeForecast(
                symbol,
                BigDecimal.ZERO,
                5,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0.0,
                LocalDate.of(2026, 10, 6),
                LocalDate.of(2026, 10, 13),
                List.of(),
                BigDecimal.ZERO,
                List.of(),
                new WingZoneForecast(
                        "unconfigured",
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        0.0,
                        0.0,
                        -1.0,
                        0,
                        5,
                        150.0,
                        200.0,
                        20.0,
                        "none"),
                null,
                0,
                0,
                "NO_DATA",
                Instant.now());
    }

    private GarchRangeReport report(List<GarchRangeForecast> forecasts) {
        return new GarchRangeReport(
                Instant.now(),
                LocalDate.of(2026, 10, 6),
                LocalDate.of(2026, 10, 13),
                5,
                forecasts,
                "done");
    }

    private static BigDecimal round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    @Test
    @DisplayName("Wednesday into a Tuesday expiry reads as five trading sessions")
    void testHorizonForWednesdayDeployment() {
        LocalDate reference = LocalDate.of(2026, 10, 6); // Tuesday
        LocalDate expiry = LocalDate.of(2026, 10, 13); // following Tuesday

        assertThat(GarchRangeService.resolveHorizon(reference, expiry)).isEqualTo(5);
    }

    @Test
    @DisplayName("Monday into the same expiry reads as two sessions, not five")
    void testHorizonForMondayDeployment() {
        LocalDate reference = LocalDate.of(2026, 10, 9); // Friday
        LocalDate expiry = LocalDate.of(2026, 10, 13); // Tuesday

        assertThat(GarchRangeService.resolveHorizon(reference, expiry)).isEqualTo(2);
    }

    @Test
    @DisplayName("a holiday-rolled expiry shortens the horizon by the rolled session")
    void testHorizonHonoursHolidayRolledExpiry() {
        // Dussehra on Tue 20 Oct 2026 rolls the expiry back to Mon 19 Oct.
        LocalDate reference = LocalDate.of(2026, 10, 13); // Tuesday
        LocalDate expiry = LocalDate.of(2026, 10, 19); // rolled from Tuesday 20th

        assertThat(GarchRangeService.resolveHorizon(reference, expiry)).isEqualTo(4);
    }

    @Test
    @DisplayName("a horizon is never floored below one session")
    void testHorizonFlooredAtOne() {
        LocalDate sameDay = LocalDate.of(2026, 10, 13);

        assertThat(GarchRangeService.resolveHorizon(sameDay, sameDay)).isEqualTo(1);
    }

    @Test
    @DisplayName("the reference session is the last close, not today's live candle")
    void testLastCompletedSessionSkipsBackwards() {
        assertThat(GarchRangeService.lastCompletedSession(LocalDate.of(2026, 10, 7)))
                .isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(GarchRangeService.lastCompletedSession(LocalDate.of(2026, 10, 12)))
                .isEqualTo(LocalDate.of(2026, 10, 9));
    }

    @Test
    @DisplayName("a Monday reference skips the weekend rather than landing on Sunday")
    void testLastCompletedSessionOverWeekend() {
        LocalDate monday = LocalDate.of(2026, 10, 12);

        assertThat(GarchRangeService.lastCompletedSession(monday))
                .isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(GarchRangeService.lastCompletedSession(monday).getDayOfWeek())
                .isEqualTo(java.time.DayOfWeek.FRIDAY);
    }

    @Test
    @DisplayName("telegram message carries 1 sigma range, 2 sigma cushion, direction, and strategy")
    void testTelegramMessageCarries1SigmaRangeAndDirection() {
        String msg =
                service.formatTelegramMessage(
                        report(List.of(calibratedForecast("NIFTY50", 24000.0))));

        assertThat(msg).contains("WEEKLY GARCH RANGE & STRATEGY");
        assertThat(msg).contains("NIFTY50");
        assertThat(msg).contains("5 sessions");
        assertThat(msg).contains("Weekly Range (1σ)");
        assertThat(msg).contains("Max Cushion (2σ)");
        assertThat(msg).contains("Direction");
    }

    @Test
    @DisplayName("a symbol without enough history is reported as such rather than silently dropped")
    void testTelegramMessageReportsNoData() {
        String msg = service.formatTelegramMessage(report(List.of(noDataForecast("RELIANCE"))));

        assertThat(msg).contains("Insufficient history");
    }

    @Test
    @DisplayName("weekly report fetches candles for every configured symbol and alerts")
    void testGenerateWeeklyReport() {
        properties.setWeeklySymbols(List.of("NIFTY50", "RELIANCE"));
        when(yahooFinanceService.fetchDailyCandles(anyString(), anyInt()))
                .thenReturn(syntheticCandles(300));

        GarchRangeReport report = service.generateWeeklyReport();

        assertThat(report.forecasts()).hasSize(2);
        assertThat(report.horizonSessions()).isPositive();
        assertThat(report.summary()).contains("2 succeeded, 0 failed");
        verify(telegramService, times(1)).sendTextMessage(anyString());
    }

    @Test
    @DisplayName("a symbol that throws is counted as a failure without killing the batch")
    void testGenerateWeeklyReportIsolatesFailures() {
        properties.setWeeklySymbols(List.of("NIFTY50", "RELIANCE"));
        when(yahooFinanceService.fetchDailyCandles(anyString(), anyInt()))
                .thenReturn(syntheticCandles(300))
                .thenThrow(new IllegalStateException("feed down"));

        GarchRangeReport report = service.generateWeeklyReport();

        assertThat(report.forecasts()).hasSize(1);
        assertThat(report.summary()).contains("1 succeeded, 1 failed");
    }

    @Test
    @DisplayName("telegram dispatch is skipped when alerts are disabled")
    void testTelegramSkippedWhenDisabled() {
        properties.setTelegramAlertsEnabled(false);
        properties.setWeeklySymbols(List.of("NIFTY50"));
        when(yahooFinanceService.fetchDailyCandles(anyString(), anyInt()))
                .thenReturn(syntheticCandles(300));

        service.generateWeeklyReport();

        verify(telegramService, never()).sendTextMessage(anyString());
    }

    @Test
    @DisplayName("telegram is optional, so a missing bot does not break the routine")
    void testTelegramOptional() {
        GarchRangeService noBot =
                new GarchRangeService(
                        properties,
                        new GarchRangeEngine(
                                new Garch11Optimizer(),
                                new GjrGarchOptimizer(),
                                new GarchVolForecaster(),
                                properties),
                        yahooFinanceService,
                        null);
        properties.setWeeklySymbols(List.of("NIFTY50"));
        when(yahooFinanceService.fetchDailyCandles(anyString(), anyInt()))
                .thenReturn(syntheticCandles(300));

        assertThat(noBot.generateWeeklyReport().forecasts()).hasSize(1);
    }

    private static List<Candle> syntheticCandles(int count) {
        List<Candle> candles = new ArrayList<>();
        long seed = 7L;
        double price = 24000.0;
        Instant base = Instant.parse("2026-01-01T00:00:00Z");

        for (int i = 0; i < count; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double uniform = ((seed >>> 11) % 100000) / 100000.0;
            double shock = (uniform - 0.5) * 2.0 * 0.008;
            double open = price;
            double close = open * (1.0 + shock);
            candles.add(
                    Candle.ofDaily(
                            "NSE:NIFTY50",
                            base.plus(i, ChronoUnit.DAYS),
                            BigDecimal.valueOf(open),
                            BigDecimal.valueOf(Math.max(open, close) * 1.002),
                            BigDecimal.valueOf(Math.min(open, close) * 0.998),
                            BigDecimal.valueOf(close),
                            1_000_000L));
            price = close;
        }
        return candles;
    }
}
