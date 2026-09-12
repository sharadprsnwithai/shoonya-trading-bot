package com.tradingbot.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DailyWmaBacktestServiceTest {

    private ShoonyaMarketDataService marketDataService;
    private TechnicalAnalysisService taService;
    private DailyWmaBacktestService backtestService;

    @BeforeEach
    void setUp() {
        marketDataService = mock(ShoonyaMarketDataService.class);
        taService = new TechnicalAnalysisService(); // Real TA service for accurate WMA
        backtestService = new DailyWmaBacktestService(marketDataService, taService);
    }

    @Test
    void testBacktestSimulationOverTrendingData() {
        List<Candle> candles = new ArrayList<>();
        ZoneId ist = ZoneId.of("Asia/Kolkata");

        // 60 trading days: 30 days uptrend, 30 days downtrend
        double price = 24000.0;
        LocalDate startDate = LocalDate.of(2026, 6, 1);

        for (int i = 0; i < 60; i++) {
            if (i < 30) {
                price += 30.0;
            } else {
                price -= 40.0;
            }
            LocalDate d = startDate.plusDays(i);
            Instant t = d.atTime(9, 30).atZone(ist).toInstant();
            candles.add(
                    new Candle(
                            "10576",
                            "D",
                            t,
                            BigDecimal.valueOf(price - 10),
                            BigDecimal.valueOf(price + 20),
                            BigDecimal.valueOf(price - 20),
                            BigDecimal.valueOf(price),
                            100000L));
        }

        when(marketDataService.fetchDailyCandles(anyString(), anyInt())).thenReturn(candles);

        BacktestResult result = backtestService.runBacktest(60, 19, 0.22, 105.0, 15, 0.02, 1.0, 1);

        assertThat(result).isNotNull();
        assertThat(result.strategyId()).isEqualTo("19WMA_POSITIONAL_OPTION_SELLING");
        assertThat(result.totalTrades()).isGreaterThan(0);
        assertThat(result.trades()).isNotEmpty();
    }
}
