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
            String symbol,
            BigDecimal spot,
            BigDecimal strike1,
            BigDecimal strike2,
            BigDecimal maxCallOiStrike,
            BigDecimal maxPutOiStrike) {
        List<OptionStrike> strikes = new ArrayList<>();

        // Add 2 strikes near spot where neither has exact isAtm=true
        OptionContract call1 =
                new OptionContract(
                        symbol + "CE",
                        "1",
                        "CE",
                        strike1,
                        new BigDecimal("90.00"),
                        50000L,
                        1000L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO);
        OptionContract put1 =
                new OptionContract(
                        symbol + "PE",
                        "2",
                        "PE",
                        strike1,
                        new BigDecimal("65.00"),
                        40000L,
                        1000L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO);
        strikes.add(new OptionStrike(strike1, false, call1, put1));

        OptionContract call2 =
                new OptionContract(
                        symbol + "CE",
                        "3",
                        "CE",
                        strike2,
                        new BigDecimal("80.00"),
                        55000L,
                        1200L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO);
        OptionContract put2 =
                new OptionContract(
                        symbol + "PE",
                        "4",
                        "PE",
                        strike2,
                        new BigDecimal("75.00"),
                        45000L,
                        1200L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO);
        strikes.add(new OptionStrike(strike2, false, call2, put2));

        // Add Max Call OI Strike
        OptionContract maxCall =
                new OptionContract(
                        symbol + "CE",
                        "5",
                        "CE",
                        maxCallOiStrike,
                        new BigDecimal("12.00"),
                        250000L,
                        5000L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO);
        strikes.add(new OptionStrike(maxCallOiStrike, false, maxCall, null));

        // Add Max Put OI Strike
        OptionContract maxPut =
                new OptionContract(
                        symbol + "PE",
                        "6",
                        "PE",
                        maxPutOiStrike,
                        new BigDecimal("10.00"),
                        280000L,
                        6000L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO);
        strikes.add(new OptionStrike(maxPutOiStrike, false, null, maxPut));

        return new OptionChainResponse(
                symbol, spot, strike1, symbol, strikes.size(), 300000L, 320000L, 1.06, strikes);
    }

    @Test
    @DisplayName(
            "Should detect earnings month and apply asymmetric multipliers (PE: 2.45 sigma, CE: 2.25 sigma)")
    void testEarningsMonthAsymmetricMultipliers() {
        List<Candle> candles = generateMockCandles("RELIANCE", 2800.0, 100, 0.01);
        LocalDate octoberDate = LocalDate.of(2026, 10, 28);

        MonthlyRangeForecast forecast =
                calculator.calculate("RELIANCE", candles, 22, octoberDate, null);

        assertThat(forecast.isEventMonth()).isTrue();
        assertThat(forecast.confidenceMultiplier()).isEqualTo(2.45); // PE skew multiplier reported
        assertThat(forecast.eventReason()).contains("Earnings Cycle");

        // Normal month (March)
        LocalDate marchDate = LocalDate.of(2026, 3, 25);
        MonthlyRangeForecast marchForecast =
                calculator.calculate("RELIANCE", candles, 22, marchDate, null);
        assertThat(marchForecast.isEventMonth()).isFalse();
        assertThat(marchForecast.confidenceMultiplier()).isEqualTo(2.10);

        assertThat(forecast.lower2Sd()).isLessThan(marchForecast.lower2Sd());
        assertThat(forecast.upper2Sd()).isGreaterThan(marchForecast.upper2Sd());
    }

    @Test
    @DisplayName(
            "Should reliably resolve nearest strike ATM Straddle even with fractional spot and no exact isAtm flag")
    void testNearestStrikeAtmStraddleResolution() {
        List<Candle> candles = generateMockCandles("RELIANCE", 2814.60, 100, 0.01);
        LocalDate date = LocalDate.of(2026, 10, 28);

        // Spot is 2814.60 -> Nearest strike is 2820.00 (call: 80, put: 75 -> sum = 155)
        OptionChainResponse chain =
                createMockOptionChain(
                        "RELIANCE",
                        new BigDecimal("2814.60"),
                        new BigDecimal("2800.00"),
                        new BigDecimal("2820.00"),
                        new BigDecimal("3200.00"),
                        new BigDecimal("2500.00"));

        MonthlyRangeForecast forecast = calculator.calculate("RELIANCE", candles, 22, date, chain);

        assertThat(forecast.atmStraddleMove()).isEqualByComparingTo("155.00");
        assertThat(forecast.maxCallOiStrike()).isEqualByComparingTo("3200.00");
        assertThat(forecast.maxPutOiStrike()).isEqualByComparingTo("2500.00");
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
