package com.tradingbot.indicator;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradingbot.model.Candle;
import com.tradingbot.model.indicator.IndicatorResponse;
import com.tradingbot.model.indicator.SuperTrendResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TechnicalAnalysisServiceTest {

    private final TechnicalAnalysisService taService = new TechnicalAnalysisService();

    @Test
    void testRsiCalculation() {
        // 20 rising close prices
        double[] close = new double[20];
        for (int i = 0; i < 20; i++) {
            close[i] = 100.0 + (i * 2.0);
        }

        double[] rsiSeries = taService.calculateRsiSeries(close, 14);
        assertThat(rsiSeries).hasSize(20);

        double latestRsi = taService.calculateLatestRsi(close, 14);
        assertThat(latestRsi).isGreaterThan(70.0); // Strong uptrend -> Overbought
    }

    @Test
    void testSuperTrendCalculation() {
        int len = 25;
        double[] high = new double[len];
        double[] low = new double[len];
        double[] close = new double[len];

        for (int i = 0; i < len; i++) {
            high[i] = 105.0 + (i * 5.0);
            low[i] = 95.0 + (i * 5.0);
            close[i] = 104.0 + (i * 5.0); // Close near the high -> Strong uptrend
        }

        SuperTrendResult[] st = taService.calculateSuperTrendSeries(high, low, close, 7, 3.0);
        assertThat(st).hasSize(len);

        SuperTrendResult latest = st[len - 1];
        assertThat(latest.isBullish()).isTrue();
        assertThat(latest.trend()).isEqualTo("BULLISH");
        assertThat(latest.value()).isGreaterThan(0);
    }

    @Test
    void testVwapCalculation() {
        List<Candle> candles = new ArrayList<>();
        Instant start = Instant.parse("2026-09-04T03:45:00Z"); // 09:15 IST

        for (int i = 0; i < 5; i++) {
            Instant t = start.plusSeconds(i * 300);
            candles.add(
                    new Candle(
                            "NIFTY50",
                            "5",
                            t,
                            BigDecimal.valueOf(24000.0 + i * 10),
                            BigDecimal.valueOf(24020.0 + i * 10),
                            BigDecimal.valueOf(23990.0 + i * 10),
                            BigDecimal.valueOf(24010.0 + i * 10),
                            1000L));
        }

        double[] vwap = taService.calculateVwapSeries(candles);
        assertThat(vwap).hasSize(5);
        assertThat(vwap[0]).isGreaterThan(23990.0);
        assertThat(vwap[4]).isGreaterThan(vwap[0]); // Upward drift
    }

    @Test
    void testAnalyzeGeneratesSnapshots() {
        List<Candle> candles = new ArrayList<>();
        Instant start = Instant.parse("2026-09-04T03:45:00Z");

        for (int i = 0; i < 20; i++) {
            Instant t = start.plusSeconds(i * 300);
            candles.add(
                    new Candle(
                            "NIFTY50",
                            "5",
                            t,
                            BigDecimal.valueOf(24000.0 + i * 5),
                            BigDecimal.valueOf(24015.0 + i * 5),
                            BigDecimal.valueOf(23990.0 + i * 5),
                            BigDecimal.valueOf(24005.0 + i * 5),
                            5000L));
        }

        IndicatorResponse response = taService.analyze("NIFTY50", "5m", candles, 7, 3.0, 14);
        assertThat(response).isNotNull();
        assertThat(response.count()).isEqualTo(20);
        assertThat(response.latest()).isNotNull();
        assertThat(response.latest().vwap()).isNotNull();
    }

    @Test
    void testAdxCalculation() {
        int len = 40;
        double[] high = new double[len];
        double[] low = new double[len];
        double[] close = new double[len];

        for (int i = 0; i < len; i++) {
            high[i] = 100.0 + (i * 3.0) + 1.0;
            low[i] = 100.0 + (i * 3.0) - 1.0;
            close[i] = 100.0 + (i * 3.0);
        }

        double[] adxSeries = taService.calculateAdxSeries(high, low, close, 14);
        assertThat(adxSeries).hasSize(len);

        double latestAdx = taService.calculateLatestAdx(high, low, close, 14);
        assertThat(Double.isNaN(latestAdx)).isFalse();
        assertThat(latestAdx).isGreaterThan(20.0); // Strong trending series
    }

    @Test
    void testWmaCalculation() {
        double[] prices = new double[25];
        for (int i = 0; i < 25; i++) {
            prices[i] = 100.0 + i;
        }

        double[] wma19 = taService.calculateWmaSeries(prices, 19);
        assertThat(wma19).hasSize(25);
        for (int i = 0; i < 18; i++) {
            assertThat(wma19[i]).isNaN();
        }
        // At index 18 (19th element: 100..118):
        // Expected = 100 + 12 = 112.0
        assertThat(wma19[18]).isEqualTo(112.0);
        assertThat(wma19[24]).isEqualTo(118.0);
    }
}
