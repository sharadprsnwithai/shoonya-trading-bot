package com.tradingbot.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TripleSuperTrendBacktestServiceTest {

    private TechnicalAnalysisService taService;
    private ShoonyaMarketDataService marketDataService;
    private TripleSuperTrendBacktestService backtestService;

    @BeforeEach
    void setUp() {
        taService = new TechnicalAnalysisService();
        marketDataService = mock(ShoonyaMarketDataService.class);
        backtestService = new TripleSuperTrendBacktestService(taService, marketDataService);
    }

    private List<Candle> generateCycleCandles(String symbol, int count) {
        List<Candle> candles = new ArrayList<>();
        Instant start = Instant.parse("2026-08-01T04:00:00Z");
        double base = 1000.0;
        for (int i = 0; i < count; i++) {
            Instant t = start.plusSeconds(i * 3600);
            // Sine wave price fluctuation
            double delta = Math.sin(i * 0.1) * 150.0;
            double p = base + delta;
            candles.add(
                    new Candle(
                            symbol,
                            "60",
                            t,
                            BigDecimal.valueOf(p),
                            BigDecimal.valueOf(p + 10.0),
                            BigDecimal.valueOf(p - 10.0),
                            BigDecimal.valueOf(p + 2.0),
                            10000));
        }
        return candles;
    }

    @Test
    void testBacktestSimulationExecutesTrades() {
        List<Candle> candles = generateCycleCandles("VEDL", 80);
        BacktestResult result = backtestService.evaluateCandles("VEDL", candles, 1, false);

        assertThat(result).isNotNull();
        assertThat(result.symbol()).isEqualTo("VEDL");
        assertThat(result.totalTrades()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void testEmptyOrInsufficientCandlesReturnEmptyResult() {
        BacktestResult result = backtestService.evaluateCandles("NIFTY50", List.of(), 1, true);
        assertThat(result.totalTrades()).isEqualTo(0);
        assertThat(result.trades()).isEmpty();
    }
}
