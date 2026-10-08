package com.tradingbot.strategy.monthlyrange.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.tradingbot.strategy.monthlyrange.model.GarchModelParams;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Garch11OptimizerTest {

    private final Garch11Optimizer optimizer = new Garch11Optimizer();

    @Test
    @DisplayName("Should fit valid GARCH(1,1) parameters on simulated return series with well-conditioned scaling")
    void testFitGarchOnSimulatedReturns() {
        Random random = new Random(42);
        int n = 500;
        double[] returns = new double[n];
        double trueOmega = 0.00001;
        double trueAlpha = 0.10;
        double trueBeta = 0.85;

        double currentSigma2 = trueOmega / (1.0 - trueAlpha - trueBeta);
        for (int t = 0; t < n; t++) {
            double z = random.nextGaussian();
            double shock = Math.sqrt(currentSigma2) * z;
            returns[t] = shock;
            currentSigma2 = trueOmega + trueAlpha * (shock * shock) + trueBeta * currentSigma2;
        }

        GarchModelParams params = optimizer.fit(returns);

        assertThat(params).isNotNull();
        assertThat(params.converged()).isTrue();
        assertThat(params.omega()).isGreaterThan(0.0).isLessThan(0.001); // Well-scaled decimal omega
        assertThat(params.alpha()).isGreaterThanOrEqualTo(0.0);
        assertThat(params.beta()).isGreaterThanOrEqualTo(0.0);
        assertThat(params.persistence()).isLessThan(1.0);
        assertThat(params.longRunVariance()).isGreaterThan(0.0);

        // Long run variance should be close to sample variance of returns
        double sumSq = 0.0;
        for (double r : returns) sumSq += r * r;
        double sampleVar = sumSq / n;
        assertThat(params.longRunVariance()).isCloseTo(sampleVar, within(sampleVar * 0.5));
    }

    @Test
    @DisplayName("Should handle flat or zero returns gracefully with positive floor parameters")
    void testFitGarchOnFlatReturns() {
        double[] flatReturns = new double[100]; // all 0.0

        GarchModelParams params = optimizer.fit(flatReturns);

        assertThat(params).isNotNull();
        assertThat(params.omega()).isGreaterThan(0.0);
        assertThat(params.longRunVariance()).isGreaterThan(0.0);
        assertThat(params.persistence()).isLessThan(1.0);
    }

    @Test
    @DisplayName("Should handle very short return series with fallback")
    void testFitGarchOnShortSeries() {
        double[] shortReturns = new double[] {0.01, -0.02, 0.015};

        GarchModelParams params = optimizer.fit(shortReturns);

        assertThat(params).isNotNull();
        assertThat(params.omega()).isGreaterThan(0.0);
        assertThat(params.persistence()).isLessThan(1.0);
    }
}
