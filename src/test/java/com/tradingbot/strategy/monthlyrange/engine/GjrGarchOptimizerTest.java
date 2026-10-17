package com.tradingbot.strategy.monthlyrange.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradingbot.strategy.monthlyrange.model.GjrGarchModelParams;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GjrGarchOptimizerTest {

    private final GjrGarchOptimizer optimizer = new GjrGarchOptimizer();

    /**
     * Series engineered to carry a leverage effect: large down moves are always followed by large
     * moves, while large up moves are followed by quiet ones. A symmetric model cannot express
     * this; a GJR fit should put weight on gamma.
     */
    private double[] leveragedReturns(int count) {
        List<Double> out = new ArrayList<>();
        long seed = 11L;
        for (int i = 0; i < count; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double u = ((seed >>> 11) % 100000) / 100000.0;
            double shock = (u - 0.5) * 2.0;
            // A down shock is followed by turbulence; an up shock by calm.
            double nextScale = shock < 0.0 ? 0.020 : 0.002;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double u2 = ((seed >>> 11) % 100000) / 100000.0;
            out.add(u2 * nextScale * 2.0 - nextScale);
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    private double[] plainReturns(int count) {
        double[] arr = new double[count];
        long seed = 3L;
        for (int i = 0; i < count; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            arr[i] = (((seed >>> 11) % 100000) / 100000.0 - 0.5) * 0.02;
        }
        return arr;
    }

    @Test
    @DisplayName("falls back to a symmetric degenerate fit when there are too few observations")
    void testFallbackOnTooFewSamples() {
        GjrGarchModelParams params = optimizer.fit(new double[] {0.01, -0.01, 0.005});

        assertThat(params.statusMessage()).startsWith("FALLBACK");
        assertThat(params.gamma()).isEqualTo(0.0);
        assertThat(params.converged()).isTrue();
    }

    @Test
    @DisplayName("a null series is rejected the same way as an empty one")
    void testFallbackOnNull() {
        assertThat(optimizer.fit(null).statusMessage()).startsWith("FALLBACK");
    }

    @Test
    @DisplayName("fitted parameters respect every stationarity and sign constraint")
    void testFittedParamsAreAdmissible() {
        GjrGarchModelParams params = optimizer.fit(leveragedReturns(600));

        assertThat(params.omega()).isPositive();
        assertThat(params.alpha()).isGreaterThanOrEqualTo(0.0);
        assertThat(params.beta()).isGreaterThanOrEqualTo(0.0);
        // A down day may never reduce forecast volatility.
        assertThat(params.alpha() + params.gamma()).isGreaterThanOrEqualTo(0.0);
        assertThat(params.persistence()).isLessThan(1.0);
        assertThat(params.longRunVariance()).isPositive();
    }

    @Test
    @DisplayName("detects the injected leverage effect — down-day clustering lifts gamma")
    void testDetectsLeverage() {
        GjrGarchModelParams leveraged = optimizer.fit(leveragedReturns(800));

        assertThat(leveraged.gamma())
                .as("a series whose volatility follows down moves must produce gamma > 0")
                .isGreaterThan(0.0);
        assertThat(leveraged.leverageRatio())
                .as("down-day response must exceed up-day response on a leveraged series")
                .isGreaterThan(1.0);
    }

    @Test
    @DisplayName("the fitted model forecasts more variance after an equal-sized down shock")
    void testFittedModelBehavesAsymmetrically() {
        GjrGarchModelParams leveraged = optimizer.fit(leveragedReturns(800));

        // Identical history except the final shock: a down day versus an up day of the same
        // magnitude. Only a model that actually learned asymmetry can separate the two.
        double[] endsDown = {0.001, -0.001, 0.002, -0.015};
        double[] endsUp = {0.001, -0.001, 0.002, 0.015};

        double downShock =
                GarchRangeEngine.gjrHorizonVariance(leveraged, endsDown, endsDown.length, 3);
        double upShock = GarchRangeEngine.gjrHorizonVariance(leveraged, endsUp, endsUp.length, 3);

        assertThat(downShock)
                .as(
                        "a leveraged model must forecast a wider band after a down day than after an"
                                + " up day of identical size")
                .isGreaterThan(upShock);
    }

    @Test
    @DisplayName("an unclustered series still yields an admissible, finite fit")
    void testNoClusteringYieldsAdmissibleFit() {
        GjrGarchModelParams plain = optimizer.fit(plainReturns(800));

        // On an unclustered series both alpha and gamma collapse toward zero, so their *ratio* is
        // unidentified and asserting anything about the split would be asserting noise. What is a
        // real property of the fitter is that it still returns a finite, admissible model rather
        // than wandering into a degenerate region.
        assertThat(plain.converged()).isTrue();
        assertThat(plain.gamma()).isFinite();
        assertThat(plain.alpha()).isFinite();
        assertThat(plain.persistence()).isLessThan(1.0);
        assertThat(plain.longRunVariance()).isPositive().isFinite();
    }

    @Test
    @DisplayName("a constant series cannot support an asymmetric model")
    void testZeroVarianceFallsBack() {
        double[] flat = new double[200];

        GjrGarchModelParams params = optimizer.fit(flat);

        assertThat(params.statusMessage()).startsWith("FALLBACK");
        assertThat(params.gamma()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("downsideResponse and leverageRatio agree with their component parameters")
    void testDerivedQuantities() {
        GjrGarchModelParams params =
                new GjrGarchModelParams(1e-6, 0.08, 0.88, 0.04, 1e-4, 0.0, true, "x");

        assertThat(params.downsideResponse())
                .isCloseTo(0.12, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(params.leverageRatio())
                .isCloseTo(1.5, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(params.persistence())
                .isCloseTo(0.98, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    @DisplayName("leverageRatio is NaN rather than infinite when alpha is zero")
    void testLeverageRatioWithZeroAlpha() {
        GjrGarchModelParams params =
                new GjrGarchModelParams(1e-6, 0.0, 0.9, 0.0, 1e-5, 0.0, true, "x");

        assertThat(params.leverageRatio()).isNaN();
    }
}
