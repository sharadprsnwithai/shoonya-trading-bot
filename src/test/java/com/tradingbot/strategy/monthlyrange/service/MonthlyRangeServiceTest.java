package com.tradingbot.strategy.monthlyrange.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import com.tradingbot.marketdata.YahooFinanceService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.monthlyrange.config.MonthlyRangeProperties;
import com.tradingbot.strategy.monthlyrange.engine.Garch11Optimizer;
import com.tradingbot.strategy.monthlyrange.engine.GarchVolForecaster;
import com.tradingbot.strategy.monthlyrange.engine.MonthlyRangeCalculator;
import com.tradingbot.strategy.monthlyrange.model.MonthlyRangeForecast;
import com.tradingbot.strategy.monthlyrange.model.MonthlyRangeReport;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MonthlyRangeServiceTest {

    private YahooFinanceService yahooFinanceService;
    private TelegramService telegramService;
    private MonthlyRangeProperties properties;
    private MonthlyRangeCalculator calculator;
    private MonthlyRangeService service;

    @BeforeEach
    void setUp() {
        yahooFinanceService = mock(YahooFinanceService.class);
        telegramService = mock(TelegramService.class);
        properties = new MonthlyRangeProperties();
        properties.setSymbols(List.of("RELIANCE", "TCS"));
        properties.setTelegramAlertsEnabled(true);

        calculator = new MonthlyRangeCalculator(new Garch11Optimizer(), new GarchVolForecaster());
        service =
                new MonthlyRangeService(
                        properties, calculator, yahooFinanceService, telegramService);
    }

    private List<Candle> createSampleCandles(String symbol, double startPrice) {
        List<Candle> candles = new ArrayList<>();
        Instant now = Instant.now().minus(60, ChronoUnit.DAYS);
        double price = startPrice;
        for (int i = 0; i < 60; i++) {
            double change = (i % 2 == 0 ? 0.01 : -0.008);
            double close = price * (1.0 + change);
            candles.add(
                    new Candle(
                            "NSE:" + symbol,
                            "D",
                            now.plus(i, ChronoUnit.DAYS),
                            BigDecimal.valueOf(price),
                            BigDecimal.valueOf(close * 1.005),
                            BigDecimal.valueOf(close * 0.995),
                            BigDecimal.valueOf(close),
                            50000L));
            price = close;
        }
        return candles;
    }

    @Test
    @DisplayName("Should generate report for all configured symbols and send Telegram alert")
    void testGenerateMonthlyReport() {
        when(yahooFinanceService.fetchDailyCandles(eq("RELIANCE"), anyInt()))
                .thenReturn(createSampleCandles("RELIANCE", 2800.0));
        when(yahooFinanceService.fetchDailyCandles(eq("TCS"), anyInt()))
                .thenReturn(createSampleCandles("TCS", 3900.0));

        MonthlyRangeReport report = service.generateMonthlyReport();

        assertThat(report).isNotNull();
        assertThat(report.forecasts()).hasSize(2);
        assertThat(report.forecasts().get(0).symbol()).isEqualTo("RELIANCE");
        assertThat(report.forecasts().get(1).symbol()).isEqualTo("TCS");

        // Verify Telegram message was sent
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(telegramService, atLeastOnce()).sendTextMessage(captor.capture());
        String msg = captor.getValue();
        assertThat(msg).contains("RELIANCE").contains("TCS").contains("MONTHLY OPTION RANGE");
    }

    @Test
    @DisplayName("Should isolate errors per symbol when fetching candles fails")
    void testGenerateMonthlyReportWithPartialFailure() {
        when(yahooFinanceService.fetchDailyCandles(eq("RELIANCE"), anyInt()))
                .thenThrow(new RuntimeException("Network timeout"));
        when(yahooFinanceService.fetchDailyCandles(eq("TCS"), anyInt()))
                .thenReturn(createSampleCandles("TCS", 3900.0));

        MonthlyRangeReport report = service.generateMonthlyReport();

        assertThat(report).isNotNull();
        assertThat(report.forecasts()).hasSize(2);
        assertThat(report.forecasts().get(1).symbol()).isEqualTo("TCS");
        assertThat(report.forecasts().get(1).spotPrice()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Should generate single symbol forecast on demand")
    void testGenerateForecastForSymbol() {
        when(yahooFinanceService.fetchDailyCandles(eq("INFY"), anyInt()))
                .thenReturn(createSampleCandles("INFY", 1850.0));

        MonthlyRangeForecast forecast = service.generateForecastForSymbol("INFY");

        assertThat(forecast).isNotNull();
        assertThat(forecast.symbol()).isEqualTo("INFY");
        assertThat(forecast.spotPrice()).isGreaterThan(BigDecimal.ZERO);
        assertThat(forecast.safePeStrike()).isNotNull();
        assertThat(forecast.safeCeStrike()).isNotNull();
    }
}
