package com.tradingbot.strategy.monthlyrange.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.monthlyrange.model.ConvictionLevel;
import com.tradingbot.strategy.monthlyrange.model.GjrGarchModelParams;
import com.tradingbot.strategy.monthlyrange.model.MarketBias;
import com.tradingbot.strategy.monthlyrange.model.MonthlyDirectionalScore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MonthlyDirectionalProbabilityEngineTest {

    private MonthlyDirectionalProbabilityEngine engine;

    @BeforeEach
    void setUp() {
        engine = new MonthlyDirectionalProbabilityEngine();
    }

    private List<Candle> createCandles(
            String symbol, double startPrice, double trendPerDay, int count) {
        List<Candle> candles = new ArrayList<>(count);
        LocalDate start = LocalDate.of(2025, 1, 1);
        double price = startPrice;
        for (int i = 0; i < count; i++) {
            price += trendPerDay;
            double open = price - trendPerDay * 0.5;
            double high = price + 2.0;
            double low = price - 2.0;
            candles.add(
                    Candle.ofDaily(
                            "NSE:" + symbol,
                            start.plusDays(i).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant(),
                            BigDecimal.valueOf(open),
                            BigDecimal.valueOf(high),
                            BigDecimal.valueOf(low),
                            BigDecimal.valueOf(price),
                            100_000L));
        }
        return candles;
    }

    @Test
    @DisplayName("Evaluate strong bullish market: generates CE 1x2x1 Broken-Wing Butterfly")
    void testStrongBullishScoreAndButterfly() {
        // 250 daily candles with steady upward trend (+1.5 per day from 20000 to ~20375)
        List<Candle> candles = createCandles("NIFTY50", 20000.0, 1.5, 250);
        BigDecimal spot = candles.get(candles.size() - 1).close();

        GjrGarchModelParams gjr =
                new GjrGarchModelParams(1e-6, 0.05, 0.90, 0.02, 1e-4, 0.0, true, "CONVERGED");

        MonthlyDirectionalScore score =
                engine.evaluateDirectionalScore("NIFTY50", spot, candles, null, gjr, 65.0);

        assertThat(score.bias()).isEqualTo(MarketBias.BULLISH);
        assertThat(score.conviction()).isEqualTo(ConvictionLevel.STRONG);
        assertThat(score.bullishProbabilityPct()).isGreaterThanOrEqualTo(65.0);

        var bwb = score.butterflyRecommendation();
        assertThat(bwb).isNotNull();
        assertThat(bwb.actionable()).isTrue();
        assertThat(bwb.optionType()).isEqualTo("CE");
        // NIFTY step is 50. ATM rounded
        BigDecimal step = BigDecimal.valueOf(50.0);
        assertThat(bwb.wing1Strike())
                .isEqualTo(bwb.atmStrike().add(step.multiply(BigDecimal.valueOf(4))));
        assertThat(bwb.sellBodyStrike())
                .isEqualTo(bwb.atmStrike().add(step.multiply(BigDecimal.valueOf(8))));
        assertThat(bwb.wing2Strike())
                .isEqualTo(bwb.atmStrike().add(step.multiply(BigDecimal.valueOf(10))));
        assertThat(bwb.wing1Lots()).isEqualTo(1);
        assertThat(bwb.sellBodyLots()).isEqualTo(2);
        assertThat(bwb.wing2Lots()).isEqualTo(1);
    }

    @Test
    @DisplayName("Evaluate strong bearish market: generates PE 1x2x1 Broken-Wing Butterfly")
    void testStrongBearishScoreAndButterfly() {
        // 250 daily candles with steady downward trend (-2.0 per day from 24000 to ~23500)
        List<Candle> candles = createCandles("RELIANCE", 24000.0, -2.0, 250);
        BigDecimal spot = candles.get(candles.size() - 1).close();

        GjrGarchModelParams gjr =
                new GjrGarchModelParams(1e-6, 0.04, 0.88, 0.12, 1e-4, 0.0, true, "CONVERGED");

        MonthlyDirectionalScore score =
                engine.evaluateDirectionalScore("RELIANCE", spot, candles, null, gjr, 65.0);

        assertThat(score.bias()).isEqualTo(MarketBias.BEARISH);
        assertThat(score.conviction()).isEqualTo(ConvictionLevel.STRONG);
        assertThat(score.bearishProbabilityPct()).isGreaterThanOrEqualTo(65.0);

        var bwb = score.butterflyRecommendation();
        assertThat(bwb).isNotNull();
        assertThat(bwb.actionable()).isTrue();
        assertThat(bwb.optionType()).isEqualTo("PE");
        // RELIANCE step is 20
        BigDecimal step = BigDecimal.valueOf(20.0);
        assertThat(bwb.wing1Strike())
                .isEqualTo(bwb.atmStrike().subtract(step.multiply(BigDecimal.valueOf(4))));
        assertThat(bwb.sellBodyStrike())
                .isEqualTo(bwb.atmStrike().subtract(step.multiply(BigDecimal.valueOf(8))));
        assertThat(bwb.wing2Strike())
                .isEqualTo(bwb.atmStrike().subtract(step.multiply(BigDecimal.valueOf(10))));
    }

    @Test
    @DisplayName("Evaluate neutral / sideways market: butterfly marked not actionable")
    void testNeutralMarketScore() {
        // 250 candles flat around 25000 (oscillation between 24980 and 25020)
        List<Candle> candles = new ArrayList<>(250);
        LocalDate start = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < 250; i++) {
            double price = 25000.0 + (i % 2 == 0 ? 10.0 : -10.0);
            candles.add(
                    Candle.ofDaily(
                            "NSE:NIFTY50",
                            start.plusDays(i).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant(),
                            BigDecimal.valueOf(price),
                            BigDecimal.valueOf(price + 5),
                            BigDecimal.valueOf(price - 5),
                            BigDecimal.valueOf(price),
                            100_000L));
        }
        BigDecimal spot = new BigDecimal("25000.0");

        MonthlyDirectionalScore score =
                engine.evaluateDirectionalScore("NIFTY50", spot, candles, null, null, 65.0);
        assertThat(score.conviction()).isEqualTo(ConvictionLevel.NEUTRAL);
        assertThat(score.butterflyRecommendation().actionable()).isFalse();
    }
}
