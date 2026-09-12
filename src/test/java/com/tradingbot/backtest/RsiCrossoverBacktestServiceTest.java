package com.tradingbot.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

class RsiCrossoverBacktestServiceTest {

    private ShoonyaMarketDataService marketDataService;
    private TechnicalAnalysisService taService;
    private RsiCrossoverBacktestService backtestService;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @BeforeEach
    void setUp() {
        marketDataService = mock(ShoonyaMarketDataService.class);
        taService = new TechnicalAnalysisService(); // Use real TA calculation
        backtestService = new RsiCrossoverBacktestService(marketDataService, taService);
    }

    private List<Candle> generateMockDayCandles(LocalDate date, double basePrice, double trend) {
        List<Candle> list = new ArrayList<>();
        Instant start = date.atTime(9, 15).atZone(IST).toInstant();
        for (int i = 0; i < 75; i++) { // 75 5m candles in a trading day (09:15 to 15:30)
            double price = basePrice + (i * trend);
            list.add(
                    new Candle(
                            "NIFTY 50",
                            "5",
                            start.plusSeconds(i * 300L),
                            BigDecimal.valueOf(price),
                            BigDecimal.valueOf(price + 5),
                            BigDecimal.valueOf(price - 5),
                            BigDecimal.valueOf(price),
                            1000));
        }
        return list;
    }

    @Test
    void testEmptyCandlesReturnsZeroSummary() {
        BacktestResult result = backtestService.evaluateCandles("NIFTY 50", List.of(), 1, 14);
        assertThat(result.totalTrades()).isEqualTo(0);
        assertThat(result.winRatePercent()).isEqualTo(0.0);
    }

    @Test
    void testMultiDayEvaluation() {
        List<Candle> allCandles = new ArrayList<>();
        LocalDate day1 = LocalDate.of(2026, 9, 7);
        LocalDate day2 = LocalDate.of(2026, 9, 8);
        LocalDate day3 = LocalDate.of(2026, 9, 9);
        LocalDate day4 = LocalDate.of(2026, 9, 10);

        allCandles.addAll(generateMockDayCandles(day1, 24000.0, 1.0));
        allCandles.addAll(generateMockDayCandles(day2, 24100.0, -1.0));
        allCandles.addAll(generateMockDayCandles(day3, 24050.0, 2.0));
        allCandles.addAll(generateMockDayCandles(day4, 24200.0, -1.5));

        BacktestResult result = backtestService.evaluateCandles("NIFTY 50", allCandles, 1, 14, false, 0.0, 20.0, 50.0);

        assertThat(result.strategyId()).isEqualTo(RsiCrossoverBacktestService.STRATEGY_ID);
        assertThat(result.symbol()).isEqualTo("NIFTY 50");
        assertThat(result.daysTested()).isEqualTo(4);
    }

    @Test
    void testOptionSellingVsOptionBuyingModes() {
        List<Candle> allCandles = new ArrayList<>();
        LocalDate day1 = LocalDate.of(2026, 9, 7);
        LocalDate day2 = LocalDate.of(2026, 9, 8);
        LocalDate day3 = LocalDate.of(2026, 9, 9);
        LocalDate day4 = LocalDate.of(2026, 9, 10);

        allCandles.addAll(generateMockDayCandles(day1, 24000.0, 1.5));
        allCandles.addAll(generateMockDayCandles(day2, 24100.0, -1.5));
        allCandles.addAll(generateMockDayCandles(day3, 24050.0, 2.0));
        allCandles.addAll(generateMockDayCandles(day4, 24200.0, -2.0));

        BacktestResult sellResult = backtestService.evaluateCandles("NIFTY 50", allCandles, "OPTION_SELLING", 1, 14, 2, true, 15.0, 20.0, 50.0);
        BacktestResult buyResult = backtestService.evaluateCandles("NIFTY 50", allCandles, "OPTION_BUYING", 1, 14, 2, true, 15.0, 20.0, 50.0);

        assertThat(sellResult.strategyId()).isEqualTo(RsiCrossoverBacktestService.STRATEGY_ID);
        assertThat(buyResult.strategyId()).isEqualTo(RsiCrossoverBacktestService.STRATEGY_ID);
        assertThat(sellResult.daysTested()).isEqualTo(4);
        assertThat(buyResult.daysTested()).isEqualTo(4);
    }

    @Test
    void testHedgedSpreadBacktestEvaluation() {
        List<Candle> allCandles = new ArrayList<>();
        LocalDate day1 = LocalDate.of(2026, 9, 7);
        LocalDate day2 = LocalDate.of(2026, 9, 8);
        LocalDate day3 = LocalDate.of(2026, 9, 9);
        LocalDate day4 = LocalDate.of(2026, 9, 10);

        allCandles.addAll(generateMockDayCandles(day1, 24000.0, 1.5));
        allCandles.addAll(generateMockDayCandles(day2, 24100.0, -1.5));
        allCandles.addAll(generateMockDayCandles(day3, 24050.0, 2.0));
        allCandles.addAll(generateMockDayCandles(day4, 24200.0, -2.0));

        BacktestResult hedgedResult = backtestService.evaluateCandles(
                "NIFTY 50", allCandles, "OPTION_SELLING", 1, 14, 1, true, 2.0, true, 20.0, 2.0, 50.0);

        assertThat(hedgedResult.strategyId()).isEqualTo(RsiCrossoverBacktestService.STRATEGY_ID);
        assertThat(hedgedResult.daysTested()).isEqualTo(4);
    }
}
