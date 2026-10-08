package com.tradingbot.strategy.monthlyrange.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradingbot.model.Candle;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.model.OptionContract;
import com.tradingbot.model.OptionStrike;
import com.tradingbot.strategy.monthlyrange.config.MonthlyRangeProperties;
import com.tradingbot.strategy.monthlyrange.model.MonthlyRangeForecast;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MonthlyRangeCalculatorTest {

    private MonthlyRangeCalculator calculator;
    private MonthlyRangeProperties properties;

    @BeforeEach
    void setUp() {
        properties = new MonthlyRangeProperties();
        calculator =
                new MonthlyRangeCalculator(
                        new Garch11Optimizer(), new GarchVolForecaster(), properties);
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

    private OptionChainResponse createMockOptionChain(
            String symbol, BigDecimal spot, BigDecimal atmStrike, BigDecimal maxCallOiStrike, BigDecimal maxPutOiStrike) {
        List<OptionStrike> strikes = new ArrayList<>();

        // Add ATM strike with LTPs
        OptionContract atmCall =
                new OptionContract(symbol + "CE", "1", "CE", atmStrike, new BigDecimal("85.00"), 50000L, 1000L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        OptionContract atmPut =
                new OptionContract(symbol + "PE", "2", "PE", atmStrike, new BigDecimal("75.00"), 40000L, 1000L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        strikes.add(new OptionStrike(atmStrike, true, atmCall, atmPut));

        // Add Max Call OI Strike
        OptionContract maxCall =
                new OptionContract(symbol + "CE", "3", "CE", maxCallOiStrike, new BigDecimal("12.00"), 250000L, 5000L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        strikes.add(new OptionStrike(maxCallOiStrike, false, maxCall, null));

        // Add Max Put OI Strike
        OptionContract maxPut =
                new OptionContract(symbol + "PE", "4", "PE", maxPutOiStrike, new BigDecimal("10.00"), 280000L, 6000L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        strikes.add(new OptionStrike(maxPutOiStrike, false, null, maxPut));

        return new OptionChainResponse(
                symbol, spot, atmStrike, symbol, strikes.size(), 300000L, 320000L, 1.06, strikes);
    }

    @Test
    @DisplayName("Should detect earnings month (October) and expand confidence multiplier to 2.35 sigma")
    void testEarningsMonthMultiplierExpansion() {
        List<Candle> candles = generateMockCandles("RELIANCE", 2800.0, 100, 0.01);
        LocalDate octoberDate = LocalDate.of(2026, 10, 28); // Q2 Earnings month

        MonthlyRangeForecast forecast = calculator.calculate("RELIANCE", candles, 22, octoberDate, null);

        assertThat(forecast.isEventMonth()).isTrue();
        assertThat(forecast.confidenceMultiplier()).isEqualTo(2.35);
        assertThat(forecast.eventReason()).contains("Earnings Cycle");

        // Normal month (March)
        LocalDate marchDate = LocalDate.of(2026, 3, 25);
        MonthlyRangeForecast marchForecast = calculator.calculate("RELIANCE", candles, 22, marchDate, null);
        assertThat(marchForecast.isEventMonth()).isFalse();
        assertThat(marchForecast.confidenceMultiplier()).isEqualTo(2.00);

        // 2.35-SD range should be strictly wider than 2.00-SD range
        assertThat(forecast.lower2Sd()).isLessThan(marchForecast.lower2Sd());
        assertThat(forecast.upper2Sd()).isGreaterThan(marchForecast.upper2Sd());
    }

    @Test
    @DisplayName("Should fuse Institutional OI walls to guarantee safer outer boundaries")
    void testOptionChainOiSupportAndResistanceFusion() {
        List<Candle> candles = generateMockCandles("RELIANCE", 2800.0, 100, 0.01);
        LocalDate date = LocalDate.of(2026, 10, 28);

        // Max Call OI at 3200 (further out than GARCH), Max Put OI at 2500 (further out than GARCH)
        OptionChainResponse chain =
                createMockOptionChain("RELIANCE", new BigDecimal("2800.00"), new BigDecimal("2800.00"), new BigDecimal("3200.00"), new BigDecimal("2500.00"));

        MonthlyRangeForecast forecast = calculator.calculate("RELIANCE", candles, 22, date, chain);

        assertThat(forecast.maxCallOiStrike()).isEqualByComparingTo("3200.00");
        assertThat(forecast.maxPutOiStrike()).isEqualByComparingTo("2500.00");
        assertThat(forecast.atmStraddleMove()).isEqualByComparingTo("160.00"); // 85 + 75

        // Final safe strikes must be at or beyond the OI walls
        assertThat(forecast.safePeStrike()).isLessThanOrEqualTo(new BigDecimal("2500.00"));
        assertThat(forecast.safeCeStrike()).isGreaterThanOrEqualTo(new BigDecimal("3200.00"));
    }

    @Test
    @DisplayName("Should compute range and safe strikes for RELIANCE (Strike Step 20)")
    void testCalculateReliance() {
        List<Candle> candles = generateMockCandles("RELIANCE", 2800.0, 100, 0.01);
        MonthlyRangeForecast forecast = calculator.calculate("RELIANCE", candles, 22);

        assertThat(forecast).isNotNull();
        assertThat(forecast.symbol()).isEqualTo("RELIANCE");
        assertThat(forecast.strikeStep()).isEqualByComparingTo("20");

        BigDecimal safePe = forecast.safePeStrike();
        assertThat(safePe.remainder(new BigDecimal("20"))).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(safePe).isLessThanOrEqualTo(forecast.lower2Sd());

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
