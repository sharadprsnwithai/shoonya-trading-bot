package com.tradingbot.strategy.monthlyrange.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.tradingbot.strategy.monthlyrange.model.GarchModelParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GarchVolForecasterTest {

    private final GarchVolForecaster forecaster = new GarchVolForecaster();

    @Test
    @DisplayName("Should project multi-step conditional variance and cumulative monthly volatility")
    void testForecastMonthlyVol() {
        // Daily variance ~ 0.0001 (daily vol 1% => annual ~16%)
        GarchModelParams params =
                new GarchModelParams(
                        0.000005, 0.08, 0.87, 0.0001, -500.0, true, "CONVERGED");

        double[] returns = new double[250];
        for (int i = 0; i < returns.length; i++) {
            returns[i] = (i % 2 == 0 ? 0.01 : -0.01);
        }

        int horizon = 22;
        GarchVolForecaster.VolForecastResult result =
                forecaster.forecastMonthlyVol(params, returns, horizon);

        assertThat(result).isNotNull();
        assertThat(result.dailyVariances()).hasSize(horizon);
        // Monthly cumulative volatility should be roughly sqrt(22 * daily_variance)
        assertThat(result.cumulativeMonthlyVolPct()).isGreaterThan(2.0).isLessThan(15.0);
        // Annualized vol should be around 10% - 30%
        assertThat(result.annualizedVolPct())
                .isCloseTo(
                        result.cumulativeMonthlyVolPct() * Math.sqrt(252.0 / horizon),
                        within(0.01));
    }

    @Test
    @DisplayName("Should mean revert towards long-run unconditional variance")
    void testMeanReversionInForecast() {
        // High initial shock compared to long run variance
        double omega = 0.00001;
        double alpha = 0.10;
        double beta = 0.80;
        double vl = omega / (1.0 - alpha - beta); // 0.0001

        GarchModelParams params =
                new GarchModelParams(omega, alpha, beta, vl, -500.0, true, "CONVERGED");

        // Returns ending with a large 4% shock
        double[] returns = new double[100];
        returns[99] = 0.04;

        GarchVolForecaster.VolForecastResult result =
                forecaster.forecastMonthlyVol(params, returns, 22);

        double[] vars = result.dailyVariances();
        assertThat(vars[0]).isGreaterThan(vars[21]); // Decreasing towards long run variance
        assertThat(vars[21]).isCloseTo(vl, within(0.0002));
    }
}
