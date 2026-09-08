package com.tradingbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.indicator.SuperTrendResult;
import com.tradingbot.model.strategy.MtfTrendStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MultiTimeframeTrendServiceTest {

    private TechnicalAnalysisService taService;
    private MultiTimeframeTrendService trendService;

    @BeforeEach
    void setUp() {
        taService = mock(TechnicalAnalysisService.class);
        trendService = new MultiTimeframeTrendService(taService);
    }

    private List<Candle> createCandles(int count, double basePrice, double step) {
        List<Candle> list = new ArrayList<>();
        Instant start = Instant.parse("2026-09-01T04:00:00Z");
        for (int i = 0; i < count; i++) {
            double p = basePrice + (i * step);
            list.add(
                    new Candle(
                            "TEST",
                            "60",
                            start.plusSeconds(i * 3600),
                            BigDecimal.valueOf(p - 1),
                            BigDecimal.valueOf(p + 2),
                            BigDecimal.valueOf(p - 2),
                            BigDecimal.valueOf(p),
                            1000));
        }
        return list;
    }

    @Test
    void testEvaluateTrendFullConfluence() {
        List<Candle> weekly = createCandles(20, 200.0, 5.0);
        List<Candle> daily = createCandles(30, 250.0, 2.0);
        List<Candle> hourly = createCandles(30, 290.0, 1.0);

        // Mock Weekly Indicators
        double[] wEma = new double[20];
        Arrays.fill(wEma, 220.0);
        SuperTrendResult[] wSt = new SuperTrendResult[20];
        Arrays.fill(wSt, SuperTrendResult.of(210.0, 220.0, 200.0, true));

        // Mock Daily Indicators
        double[] dEma = new double[30];
        Arrays.fill(dEma, 260.0);
        SuperTrendResult[] dSt = new SuperTrendResult[30];
        Arrays.fill(dSt, SuperTrendResult.of(250.0, 260.0, 240.0, true));
        double[] dRsi = new double[30];
        Arrays.fill(dRsi, 58.0);

        // Mock Hourly Indicators
        double[] hEma = new double[30];
        Arrays.fill(hEma, 300.0);
        SuperTrendResult[] hSt = new SuperTrendResult[30];
        Arrays.fill(hSt, SuperTrendResult.of(295.0, 305.0, 290.0, true));
        double[] hAdx = new double[30];
        Arrays.fill(hAdx, 26.0);

        when(taService.calculateEmaSeries(any(), anyInt()))
                .thenReturn(wEma)
                .thenReturn(dEma)
                .thenReturn(hEma);
        when(taService.calculateSuperTrendSeries(any(), any(), any(), anyInt(), anyDouble()))
                .thenReturn(wSt)
                .thenReturn(dSt)
                .thenReturn(hSt);
        when(taService.calculateRsiSeries(any(), anyInt())).thenReturn(dRsi);
        when(taService.calculateAdxSeries(any(), any(), any(), anyInt())).thenReturn(hAdx);

        MtfTrendStatus status = trendService.evaluateTrend("TEST", weekly, daily, hourly);

        assertThat(status).isNotNull();
        assertThat(status.weeklyUptrend()).isTrue();
        assertThat(status.dailyUptrend()).isTrue();
        assertThat(status.hourlyUptrend()).isTrue();
        assertThat(status.isFullConfluence()).isTrue();
        assertThat(status.atmCallStrike()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void testEvaluateTrendFailsWhenWeeklyIsBearish() {
        List<Candle> weekly = createCandles(20, 200.0, -2.0);
        List<Candle> daily = createCandles(30, 250.0, 2.0);
        List<Candle> hourly = createCandles(30, 290.0, 1.0);

        // Weekly Bearish
        double[] wEma = new double[20];
        Arrays.fill(wEma, 240.0);
        SuperTrendResult[] wSt = new SuperTrendResult[20];
        Arrays.fill(wSt, SuperTrendResult.of(250.0, 260.0, 240.0, false));

        // Daily Bullish
        double[] dEma = new double[30];
        Arrays.fill(dEma, 260.0);
        SuperTrendResult[] dSt = new SuperTrendResult[30];
        Arrays.fill(dSt, SuperTrendResult.of(250.0, 260.0, 240.0, true));
        double[] dRsi = new double[30];
        Arrays.fill(dRsi, 55.0);

        // Hourly Bullish
        double[] hEma = new double[30];
        Arrays.fill(hEma, 280.0);
        SuperTrendResult[] hSt = new SuperTrendResult[30];
        Arrays.fill(hSt, SuperTrendResult.of(285.0, 295.0, 280.0, true));
        double[] hAdx = new double[30];
        Arrays.fill(hAdx, 24.0);

        when(taService.calculateEmaSeries(any(), anyInt()))
                .thenReturn(wEma)
                .thenReturn(dEma)
                .thenReturn(hEma);
        when(taService.calculateSuperTrendSeries(any(), any(), any(), anyInt(), anyDouble()))
                .thenReturn(wSt)
                .thenReturn(dSt)
                .thenReturn(hSt);
        when(taService.calculateRsiSeries(any(), anyInt())).thenReturn(dRsi);
        when(taService.calculateAdxSeries(any(), any(), any(), anyInt())).thenReturn(hAdx);

        MtfTrendStatus status = trendService.evaluateTrend("TEST", weekly, daily, hourly);

        assertThat(status.weeklyUptrend()).isFalse();
        assertThat(status.isFullConfluence()).isFalse();
    }

    @Test
    void testEvaluateTrendLoserBearishConfluence() {
        List<Candle> weekly = createCandles(20, 300.0, -5.0);
        List<Candle> daily = createCandles(30, 250.0, -2.0);
        List<Candle> hourly = createCandles(30, 200.0, -1.0);

        // Weekly Bearish (Price below ST)
        double[] wEma = new double[20];
        Arrays.fill(wEma, 250.0);
        SuperTrendResult[] wSt = new SuperTrendResult[20];
        Arrays.fill(wSt, SuperTrendResult.of(260.0, 270.0, 255.0, false));

        // Daily Bearish (Price below ST)
        double[] dEma = new double[30];
        Arrays.fill(dEma, 220.0);
        SuperTrendResult[] dSt = new SuperTrendResult[30];
        Arrays.fill(dSt, SuperTrendResult.of(230.0, 240.0, 225.0, false));
        double[] dRsi = new double[30];
        Arrays.fill(dRsi, 40.0);

        // Hourly: Crossed below SuperTrend (Previous bar was Bullish, Current bar is Bearish)
        double[] hEma = new double[30];
        Arrays.fill(hEma, 180.0);
        SuperTrendResult[] hSt = new SuperTrendResult[30];
        for (int i = 0; i < 29; i++) {
            hSt[i] = SuperTrendResult.of(170.0, 175.0, 168.0, true);
        }
        hSt[29] = SuperTrendResult.of(175.0, 180.0, 172.0, false); // Crossed below SuperTrend!

        double[] hAdx = new double[30];
        Arrays.fill(hAdx, 26.0);

        when(taService.calculateEmaSeries(any(), anyInt()))
                .thenReturn(wEma)
                .thenReturn(dEma)
                .thenReturn(hEma);
        when(taService.calculateSuperTrendSeries(any(), any(), any(), anyInt(), anyDouble()))
                .thenReturn(wSt)
                .thenReturn(dSt)
                .thenReturn(hSt);
        when(taService.calculateRsiSeries(any(), anyInt())).thenReturn(dRsi);
        when(taService.calculateAdxSeries(any(), any(), any(), anyInt())).thenReturn(hAdx);

        MtfTrendStatus status = trendService.evaluateTrend("TEST", weekly, daily, hourly);

        assertThat(status).isNotNull();
        assertThat(status.weeklyUptrend()).isFalse();
        assertThat(status.dailyUptrend()).isFalse();
        assertThat(status.isFullConfluence()).isFalse(); // Uptrend confluence is false
        assertThat(status.isBearishConfluence()).isTrue(); // Loser / Bearish confluence is true
        assertThat(status.isFreshHourlyBearishTrigger()).isTrue(); // Crossed below ST in hourly
        assertThat(status.atmPutStrike()).isGreaterThan(BigDecimal.ZERO);
    }
}
