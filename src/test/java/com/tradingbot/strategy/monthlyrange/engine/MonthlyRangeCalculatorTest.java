package com.tradingbot.strategy.monthlyrange.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.monthlyrange.model.MonthlyRangeForecast;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MonthlyRangeCalculatorTest {

    private MonthlyRangeCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new MonthlyRangeCalculator(new Garch11Optimizer(), new GarchVolForecaster());
    }

    private List<Candle> generateMockCandles(
            String symbol, double startPrice, int count, double dailyPctVol) {
        List<Candle> candles = new ArrayList<>();
        Instant now = Instant.now().minus(count, ChronoUnit.DAYS);
        double price = startPrice;

        for (int i = 0; i < count; i++) {
            double change = (i % 2 == 0 ? dailyPctVol : -dailyPctVol);
            double open = price;
            double close = open * (1.0 + change);
            double high = Math.max(open, close) * 1.005;
            double low = Math.min(open, close) * 0.995;
            price = close;

            candles.add(
                    new Candle(
                            "NSE:" + symbol,
                            "D",
                            now.plus(i, ChronoUnit.DAYS),
                            BigDecimal.valueOf(open),
                            BigDecimal.valueOf(high),
                            BigDecimal.valueOf(low),
                            BigDecimal.valueOf(close),
                            100000L));
        }
        return candles;
    }

    @Test
    @DisplayName("Should compute range and safe strikes for RELIANCE (Strike Step 20)")
    void testCalculateReliance() {
        List<Candle> candles = generateMockCandles("RELIANCE", 2800.0, 100, 0.01);
        MonthlyRangeForecast forecast = calculator.calculate("RELIANCE", candles, 22);

        assertThat(forecast).isNotNull();
        assertThat(forecast.symbol()).isEqualTo("RELIANCE");
        assertThat(forecast.strikeStep()).isEqualByComparingTo("20");

        // Safe PE strike should be a multiple of 20 and strictly <= lower2Sd
        BigDecimal safePe = forecast.safePeStrike();
        assertThat(safePe.remainder(new BigDecimal("20"))).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(safePe).isLessThanOrEqualTo(forecast.lower2Sd());

        // Safe CE strike should be a multiple of 20 and strictly >= upper2Sd
        BigDecimal safeCe = forecast.safeCeStrike();
        assertThat(safeCe.remainder(new BigDecimal("20"))).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(safeCe).isGreaterThanOrEqualTo(forecast.upper2Sd());

        assertThat(forecast.monthlyVolPct()).isGreaterThan(0.0);
        assertThat(forecast.hv30AnnualizedPct()).isGreaterThan(0.0);
        assertThat(forecast.atr22()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Should compute range and safe strikes for SBIN (Strike Step 5)")
    void testCalculateSbin() {
        List<Candle> candles = generateMockCandles("SBIN", 800.0, 100, 0.012);
        MonthlyRangeForecast forecast = calculator.calculate("SBIN", candles, 22);

        assertThat(forecast).isNotNull();
        assertThat(forecast.symbol()).isEqualTo("SBIN");
        assertThat(forecast.strikeStep()).isEqualByComparingTo("5");

        BigDecimal safePe = forecast.safePeStrike();
        assertThat(safePe.remainder(new BigDecimal("5"))).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(safePe).isLessThanOrEqualTo(forecast.lower2Sd());

        BigDecimal safeCe = forecast.safeCeStrike();
        assertThat(safeCe.remainder(new BigDecimal("5"))).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(safeCe).isGreaterThanOrEqualTo(forecast.upper2Sd());
    }

    @Test
    @DisplayName("Should compute range and safe strikes for NIFTY50 (Strike Step 50)")
    void testCalculateNifty50() {
        List<Candle> candles = generateMockCandles("NIFTY50", 25000.0, 100, 0.008);
        MonthlyRangeForecast forecast = calculator.calculate("NIFTY50", candles, 22);

        assertThat(forecast).isNotNull();
        assertThat(forecast.symbol()).isEqualTo("NIFTY50");
        assertThat(forecast.strikeStep()).isEqualByComparingTo("50");

        BigDecimal safePe = forecast.safePeStrike();
        assertThat(safePe.remainder(new BigDecimal("50"))).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(safePe).isLessThanOrEqualTo(forecast.lower2Sd());

        BigDecimal safeCe = forecast.safeCeStrike();
        assertThat(safeCe.remainder(new BigDecimal("50"))).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(safeCe).isGreaterThanOrEqualTo(forecast.upper2Sd());
    }

    @Test
    @DisplayName("Should fallback gracefully when candles are null or empty")
    void testEmptyCandlesFallback() {
        MonthlyRangeForecast forecast = calculator.calculate("TCS", List.of(), 22);

        assertThat(forecast).isNotNull();
        assertThat(forecast.symbol()).isEqualTo("TCS");
        assertThat(forecast.spotPrice()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
