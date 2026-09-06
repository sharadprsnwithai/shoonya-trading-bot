package com.tradingbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tradingbot.backtest.BacktestResult;
import com.tradingbot.backtest.TripleSuperTrendBacktestService;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BasketHealthScoringServiceTest {

    private TripleSuperTrendBacktestService backtestService;
    private ShoonyaMarketDataService marketDataService;
    private TechnicalAnalysisService taService;
    private TelegramService telegramService;
    private BasketHealthScoringService scoringService;

    @BeforeEach
    void setUp() {
        backtestService = mock(TripleSuperTrendBacktestService.class);
        marketDataService = mock(ShoonyaMarketDataService.class);
        taService = mock(TechnicalAnalysisService.class);
        telegramService = mock(TelegramService.class);
        scoringService =
                new BasketHealthScoringService(
                        backtestService, marketDataService, taService, telegramService);
    }

    private List<Candle> createDummyCandles(int count) {
        List<Candle> candles = new ArrayList<>();
        Instant start = Instant.parse("2026-08-01T04:00:00Z");
        for (int i = 0; i < count; i++) {
            candles.add(
                    new Candle(
                            "TEST",
                            "60",
                            start.plusSeconds(i * 3600),
                            BigDecimal.valueOf(100),
                            BigDecimal.valueOf(105),
                            BigDecimal.valueOf(95),
                            BigDecimal.valueOf(102),
                            10000));
        }
        return candles;
    }

    @Test
    void testRankAllSymbolsRanksCorrectly() {
        List<Candle> candles = createDummyCandles(35);
        when(marketDataService.fetchHourlyCandles(anyString(), anyInt())).thenReturn(candles);
        when(backtestService.evaluateCandles(
                        anyString(),
                        anyList(),
                        anyInt(),
                        anyBoolean(),
                        anyBoolean(),
                        anyBoolean(),
                        anyDouble(),
                        anyDouble(),
                        anyDouble(),
                        anyInt(),
                        anyBoolean(),
                        anyBoolean(),
                        anyDouble(),
                        anyBoolean(),
                        anyDouble(),
                        anyDouble(),
                        anyBoolean(),
                        anyInt()))
                .thenReturn(
                        new BacktestResult(
                                "ID",
                                "TEST",
                                30,
                                4,
                                3,
                                1,
                                75.0,
                                10.0,
                                BigDecimal.valueOf(20000),
                                BigDecimal.valueOf(5000),
                                BigDecimal.valueOf(15000),
                                BigDecimal.valueOf(5000),
                                4.0,
                                List.of()));

        double[] dummyAdx = new double[35];
        java.util.Arrays.fill(dummyAdx, 28.0);
        double[] dummyAtr = new double[35];
        java.util.Arrays.fill(dummyAtr, 2.0);

        when(taService.calculateAdxSeries(any(), any(), any(), anyInt())).thenReturn(dummyAdx);
        when(taService.calculateAtrSeries(any(), any(), any(), anyInt())).thenReturn(dummyAtr);
        com.tradingbot.model.indicator.SuperTrendResult[] dummySt =
                new com.tradingbot.model.indicator.SuperTrendResult[35];
        java.util.Arrays.fill(
                dummySt,
                com.tradingbot.model.indicator.SuperTrendResult.of(90.0, 95.0, 85.0, true));
        when(taService.calculateSuperTrendSeries(any(), any(), any(), anyInt(), anyDouble()))
                .thenReturn(dummySt);
        double[] dummyEma = new double[35];
        java.util.Arrays.fill(dummyEma, 95.0);
        when(taService.calculateEmaSeries(any(), anyInt())).thenReturn(dummyEma);

        List<BasketHealthScoringService.StockHealthScore> ranked =
                scoringService.rankAllSymbols(30);

        assertThat(ranked).isNotEmpty();
        assertThat(ranked.get(0).rank()).isEqualTo(1);
        assertThat(ranked.get(0).isTop10()).isTrue();
        assertThat(ranked.get(0).compositeScore()).isGreaterThan(0.0);
        assertThat(ranked.get(0).direction()).isEqualTo("🟢 BULLISH (Buy CE)");

        boolean sent = scoringService.sendRebalanceTelegramReport(ranked);
        assertThat(sent).isTrue();
    }
}
