package com.tradingbot.strategy.monthlyrange.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MonthlyDirectionalScoreTest {

    @Test
    @DisplayName("ConvictionLevel: resolves STRONG >= 65%, MODERATE 55-64%, NEUTRAL 45-54%")
    void testConvictionLevelThresholds() {
        assertThat(ConvictionLevel.fromProbability(72.0, 65.0)).isEqualTo(ConvictionLevel.STRONG);
        assertThat(ConvictionLevel.fromProbability(65.0, 65.0)).isEqualTo(ConvictionLevel.STRONG);
        assertThat(ConvictionLevel.fromProbability(60.0, 65.0)).isEqualTo(ConvictionLevel.MODERATE);
        assertThat(ConvictionLevel.fromProbability(51.0, 65.0)).isEqualTo(ConvictionLevel.NEUTRAL);
        assertThat(ConvictionLevel.fromProbability(30.0, 65.0))
                .isEqualTo(ConvictionLevel.STRONG); // 70% bearish
    }

    @Test
    @DisplayName("MarketBias: resolves BULLISH for > 54%, BEARISH for < 46%, else NEUTRAL")
    void testMarketBias() {
        assertThat(MarketBias.fromBullishProbability(66.0)).isEqualTo(MarketBias.BULLISH);
        assertThat(MarketBias.fromBullishProbability(34.0)).isEqualTo(MarketBias.BEARISH);
        assertThat(MarketBias.fromBullishProbability(50.0)).isEqualTo(MarketBias.NEUTRAL);
    }

    @Test
    @DisplayName("BrokenWingButterflyRecommendation: builds 1x2x1 Call or Put ratio spread")
    void testBrokenWingButterflyRecommendation() {
        BrokenWingButterflyRecommendation bwb =
                new BrokenWingButterflyRecommendation(
                        MarketBias.BULLISH,
                        "1x2x1 Broken-Wing Call Butterfly",
                        new BigDecimal("25000"),
                        new BigDecimal("25200"), // 4 OTM (step 50)
                        1,
                        new BigDecimal("25400"), // 8 OTM
                        2,
                        new BigDecimal("25500"), // 10 OTM
                        1,
                        "CE",
                        true,
                        "Strong bullish conviction (71.5%)");

        assertThat(bwb.actionable()).isTrue();
        assertThat(bwb.optionType()).isEqualTo("CE");
        assertThat(bwb.wing1Strike()).isEqualTo(new BigDecimal("25200"));
        assertThat(bwb.sellBodyStrike()).isEqualTo(new BigDecimal("25400"));
        assertThat(bwb.wing2Strike()).isEqualTo(new BigDecimal("25500"));
    }

    @Test
    @DisplayName(
            "MonthlyUniverseRanking: partitions and ranks Top 3 Directional and Top 3 Sideways")
    void testUniverseRanking() {
        MonthlyDirectionalScore strongBull =
                new MonthlyDirectionalScore(
                        "RELIANCE",
                        new BigDecimal("2800"),
                        75.0,
                        25.0,
                        MarketBias.BULLISH,
                        ConvictionLevel.STRONG,
                        Map.of("trend", 30.0),
                        null);

        MonthlyDirectionalScore strongBear =
                new MonthlyDirectionalScore(
                        "INFY",
                        new BigDecimal("1500"),
                        28.0,
                        72.0,
                        MarketBias.BEARISH,
                        ConvictionLevel.STRONG,
                        Map.of("trend", 10.0),
                        null);

        MonthlyDirectionalScore neutral1 =
                new MonthlyDirectionalScore(
                        "TCS",
                        new BigDecimal("3500"),
                        51.0,
                        49.0,
                        MarketBias.NEUTRAL,
                        ConvictionLevel.NEUTRAL,
                        Map.of("trend", 20.0),
                        null);

        MonthlyDirectionalScore neutral2 =
                new MonthlyDirectionalScore(
                        "HDFCBANK",
                        new BigDecimal("1650"),
                        49.5,
                        50.5,
                        MarketBias.NEUTRAL,
                        ConvictionLevel.NEUTRAL,
                        Map.of("trend", 20.0),
                        null);

        List<MonthlyDirectionalScore> all = List.of(strongBull, neutral1, strongBear, neutral2);
        MonthlyUniverseRanking ranking = MonthlyUniverseRanking.fromScores(all, 65.0);

        assertThat(ranking.topDirectionalPicks()).hasSize(2);
        assertThat(ranking.topDirectionalPicks().get(0).symbol())
                .isEqualTo("RELIANCE"); // 75% (|75-50|=25)
        assertThat(ranking.topDirectionalPicks().get(1).symbol())
                .isEqualTo("INFY"); // 72% (|28-50|=22)

        assertThat(ranking.topSidewaysPicks()).hasSize(2);
        assertThat(ranking.topSidewaysPicks().get(0).symbol())
                .isEqualTo("HDFCBANK"); // |49.5-50|=0.5
        assertThat(ranking.topSidewaysPicks().get(1).symbol()).isEqualTo("TCS"); // |51-50|=1.0
    }
}
