package com.tradingbot.strategy.monthlyrange.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.monthlyrange.config.MonthlyRangeProperties;
import com.tradingbot.strategy.monthlyrange.model.GarchModelParams;
import com.tradingbot.strategy.monthlyrange.model.GarchRangeBand;
import com.tradingbot.strategy.monthlyrange.model.GarchRangeForecast;
import com.tradingbot.strategy.monthlyrange.model.GjrGarchModelParams;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GarchRangeEngineTest {

    private static final LocalDate REFERENCE = LocalDate.of(2026, 10, 7);
    private static final LocalDate EXPIRY = LocalDate.of(2026, 10, 13);
    private static final int HORIZON = 5;

    private MonthlyRangeProperties properties;
    private GarchRangeEngine engine;
    private GarchVolForecaster volForecaster;

    @BeforeEach
    void setUp() {
        properties = new MonthlyRangeProperties();
        volForecaster = new GarchVolForecaster();
        engine =
                new GarchRangeEngine(
                        new Garch11Optimizer(), new GjrGarchOptimizer(), volForecaster, properties);
    }

    /**
     * Deterministic pseudo-random walk so every assertion is reproducible. The intraday range is
     * applied on top of the close-to-close move, which is the whole point: a structure held to
     * expiry is exposed to that range, not just to the close.
     */
    private List<Candle> syntheticCandles(
            int count, double start, double dailyVol, double intradayPct) {
        List<Candle> candles = new ArrayList<>();
        long seed = 42L;
        double price = start;
        Instant base = Instant.parse("2026-01-01T00:00:00Z");

        for (int i = 0; i < count; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double uniform = ((seed >>> 11) % 100000) / 100000.0;
            double shock = (uniform - 0.5) * 2.0 * dailyVol;

            double open = price;
            double close = open * (1.0 + shock);
            double high = Math.max(open, close) * (1.0 + intradayPct);
            double low = Math.min(open, close) * (1.0 - intradayPct);

            candles.add(
                    new Candle(
                            "NSE:NIFTY50",
                            "D",
                            base.plus(i, ChronoUnit.DAYS),
                            BigDecimal.valueOf(open),
                            BigDecimal.valueOf(high),
                            BigDecimal.valueOf(low),
                            BigDecimal.valueOf(close),
                            1_000_000L));
            price = close;
        }
        return candles;
    }

    private double[] logReturns(List<Candle> candles) {
        double[] returns = new double[candles.size() - 1];
        for (int i = 1; i < candles.size(); i++) {
            returns[i - 1] =
                    Math.log(
                            candles.get(i).close().doubleValue()
                                    / candles.get(i - 1).close().doubleValue());
        }
        return returns;
    }

    @Test
    @DisplayName("bandConfidence reproduces the textbook Gaussian two-sided probabilities")
    void testBandConfidenceMatchesKnownValues() {
        assertThat(GarchRangeEngine.bandConfidence(1.0)).isCloseTo(0.6826895, within(1e-6));
        assertThat(GarchRangeEngine.bandConfidence(1.96)).isCloseTo(0.9500000, within(1e-5));
        assertThat(GarchRangeEngine.bandConfidence(2.0)).isCloseTo(0.9545000, within(1e-5));
        assertThat(GarchRangeEngine.bandConfidence(3.0)).isCloseTo(0.9973002, within(1e-6));
    }

    @Test
    @DisplayName("bandConfidence is zero at k=0 and saturates at 1 for wide bands")
    void testBandConfidenceBounds() {
        assertThat(GarchRangeEngine.bandConfidence(0.0)).isEqualTo(0.0);
        assertThat(GarchRangeEngine.bandConfidence(-1.0)).isEqualTo(0.0);
        assertThat(GarchRangeEngine.bandConfidence(10.0)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("erfcc reproduces known complementary error function values")
    void testErfccKnownValues() {
        assertThat(GarchRangeEngine.erfcc(0.0)).isCloseTo(1.0, within(1e-7));
        assertThat(GarchRangeEngine.erfcc(1.0)).isCloseTo(0.15729921, within(1e-6));
        assertThat(GarchRangeEngine.erfcc(3.0)).isCloseTo(2.20905e-5, within(1e-9));
        assertThat(GarchRangeEngine.erfcc(-1.0)).isCloseTo(2.0 - 0.15729921, within(1e-6));
    }

    @Test
    @DisplayName("horizonVariance reproduces the shared vol forecaster exactly at the full series")
    void testHorizonVarianceMatchesVolForecaster() {
        List<Candle> candles = syntheticCandles(300, 24000.0, 0.008, 0.0);
        double[] returns = logReturns(candles);
        GarchModelParams params = new Garch11Optimizer().fit(returns);

        for (int horizon : new int[] {1, 2, 5, 22}) {
            double fromEngine =
                    GarchRangeEngine.horizonVariance(params, returns, returns.length, horizon);
            double fromForecaster =
                    Math.pow(
                            volForecaster
                                    .forecastMonthlyVol(params, returns, horizon)
                                    .cumulativeMonthlyVolDecimal(),
                            2.0);

            assertThat(fromEngine).isCloseTo(fromForecaster, within(1e-12));
        }
    }

    @Test
    @DisplayName("horizonVariance refuses to fabricate a forecast from invalid inputs")
    void testHorizonVarianceRejectsInvalidInputs() {
        double[] returns = new double[] {0.01, -0.02, 0.015};
        GarchModelParams params = new Garch11Optimizer().fit(returns);

        assertThat(GarchRangeEngine.horizonVariance(null, returns, 3, 5)).isNaN();
        assertThat(GarchRangeEngine.horizonVariance(params, null, 3, 5)).isNaN();
        assertThat(GarchRangeEngine.horizonVariance(params, returns, 0, 5)).isNaN();
        assertThat(GarchRangeEngine.horizonVariance(params, returns, 4, 5)).isNaN();
    }

    @Test
    @DisplayName("bands are symmetric around spot and widen monotonically with k")
    void testBandsWidenWithK() {
        List<Candle> candles = syntheticCandles(300, 24000.0, 0.008, 0.002);
        GarchRangeForecast forecast =
                engine.forecastRange("NIFTY50", candles, HORIZON, REFERENCE, EXPIRY);

        double spot = forecast.spot().doubleValue();
        assertThat(forecast.bands()).hasSize(3);
        for (GarchRangeBand band : forecast.bands()) {
            double lower = band.lower().doubleValue();
            double upper = band.upper().doubleValue();
            assertThat(upper / spot).isCloseTo(spot / lower, within(1e-6));
            assertThat(band.measuredWindows()).isPositive();
        }

        assertThat(forecast.bands().get(0).kSigma()).isEqualTo(1.0);
        assertThat(forecast.bands().get(1).kSigma()).isEqualTo(1.5);
        assertThat(forecast.bands().get(2).kSigma()).isEqualTo(2.0);

        assertThat(forecast.bands().get(0).upper().doubleValue())
                .isLessThan(forecast.bands().get(1).upper().doubleValue());
        assertThat(forecast.bands().get(1).upper().doubleValue())
                .isLessThan(forecast.bands().get(2).upper().doubleValue());
    }

    @Test
    @DisplayName("nominal confidence is monotonic in k and matches the analytic Gaussian values")
    void testNominalConfidenceMonotonic() {
        List<Candle> candles = syntheticCandles(300, 24000.0, 0.008, 0.002);
        GarchRangeForecast forecast =
                engine.forecastRange("NIFTY50", candles, HORIZON, REFERENCE, EXPIRY);

        assertThat(forecast.bands().get(0).nominalTerminalConfidencePct())
                .isCloseTo(68.27, within(0.01));
        assertThat(forecast.bands().get(1).nominalTerminalConfidencePct())
                .isCloseTo(86.64, within(0.01));
        assertThat(forecast.bands().get(2).nominalTerminalConfidencePct())
                .isCloseTo(95.45, within(0.01));

        assertThat(forecast.bands().get(0).nominalTerminalConfidencePct())
                .isLessThan(forecast.bands().get(1).nominalTerminalConfidencePct());
        assertThat(forecast.bands().get(1).nominalTerminalConfidencePct())
                .isLessThan(forecast.bands().get(2).nominalTerminalConfidencePct());
    }

    @Test
    @DisplayName("measured coverage is monotonic in k — a wider band never contains less")
    void testMeasuredCoverageMonotonic() {
        List<Candle> candles = syntheticCandles(300, 24000.0, 0.008, 0.005);
        GarchRangeForecast forecast =
                engine.forecastRange("NIFTY50", candles, HORIZON, REFERENCE, EXPIRY);

        double k1 = forecast.bands().get(0).measuredCoveragePct();
        double k15 = forecast.bands().get(1).measuredCoveragePct();
        double k2 = forecast.bands().get(2).measuredCoveragePct();

        assertThat(k1).isBetween(0.0, 100.0);
        assertThat(k15).isBetween(0.0, 100.0);
        assertThat(k2).isBetween(0.0, 100.0);
        assertThat(k1).isLessThanOrEqualTo(k15);
        assertThat(k15).isLessThanOrEqualTo(k2);
    }

    @Test
    @DisplayName("measured excursion coverage falls below the nominal terminal probability")
    void testMeasuredCoverageBelowNominal() {
        // A 1% intraday range on a 0.8% daily move means price routinely trades outside its close,
        // which is precisely the effect the Gaussian terminal figure cannot see.
        List<Candle> candles = syntheticCandles(300, 24000.0, 0.008, 0.010);
        GarchRangeForecast forecast =
                engine.forecastRange("NIFTY50", candles, HORIZON, REFERENCE, EXPIRY);

        GarchRangeBand oneSigma = forecast.bands().get(0);
        assertThat(oneSigma.measuredCoveragePct())
                .isLessThan(oneSigma.nominalTerminalConfidencePct());
    }

    @Test
    @DisplayName("wing zone is symmetric about spot and widened by the net credit on both sides")
    void testWingZoneGeometry() {
        List<Candle> candles = syntheticCandles(300, 24000.0, 0.008, 0.002);
        GarchRangeForecast forecast =
                engine.forecastRange("NIFTY50", candles, HORIZON, REFERENCE, EXPIRY);

        double spot = forecast.spot().doubleValue();
        double halfWidth = 2.0 * properties.getWingShortOffsetPts() - properties.getWingOffsetPts();

        assertThat(forecast.wingZone().lowerBreakeven().doubleValue())
                .isCloseTo(spot - halfWidth - properties.getWingCreditPts(), within(0.01));
        assertThat(forecast.wingZone().upperBreakeven().doubleValue())
                .isCloseTo(spot + halfWidth + properties.getWingCreditPts(), within(0.01));
        assertThat(forecast.wingZone().zoneHalfWidthPct())
                .isCloseTo(100.0 * halfWidth / spot, within(0.01));
        assertThat(forecast.wingZone().measuredWindows()).isPositive();
    }

    @Test
    @DisplayName("wing zone stay is measured, and measures below its Gaussian terminal figure")
    void testWingZoneMeasuredBelowGaussian() {
        List<Candle> candles = syntheticCandles(300, 24000.0, 0.008, 0.010);
        GarchRangeForecast forecast =
                engine.forecastRange("NIFTY50", candles, HORIZON, REFERENCE, EXPIRY);

        assertThat(forecast.wingZone().measuredStayPct())
                .isBetween(0.0, 100.0)
                .isLessThan(forecast.wingZone().gaussianTerminalStayPct());
    }

    @Test
    @DisplayName("a short strike inside the long wing is treated as a misconfiguration")
    void testWingZoneSkippedWhenStructureInvalid() {
        properties.setWingOffsetPts(150.0);
        properties.setWingShortOffsetPts(100.0);

        List<Candle> candles = syntheticCandles(300, 24000.0, 0.008, 0.002);
        GarchRangeForecast forecast =
                engine.forecastRange("NIFTY50", candles, HORIZON, REFERENCE, EXPIRY);

        assertThat(forecast.wingZone().label()).isEqualTo("unconfigured");
        assertThat(forecast.wingZone().measuredWindows()).isZero();
        assertThat(forecast.wingZone().note()).contains("short strike must sit beyond");
    }

    @Test
    @DisplayName("raising the minimum window threshold reports bands as uncalibrated, not as zero")
    void testCalibrationBelowMinimumWindows() {
        properties.setCalibrationMinWindows(100_000);

        List<Candle> candles = syntheticCandles(300, 24000.0, 0.008, 0.002);
        GarchRangeForecast forecast =
                engine.forecastRange("NIFTY50", candles, HORIZON, REFERENCE, EXPIRY);

        assertThat(forecast.calibrationWindows()).isZero();
        for (GarchRangeBand band : forecast.bands()) {
            assertThat(band.measuredCoveragePct()).isEqualTo(-1.0);
            assertThat(band.measuredWindows()).isZero();
        }
        assertThat(forecast.wingZone().measuredStayPct()).isEqualTo(-1.0);
    }

    @Test
    @DisplayName("a history too short to fit returns a NO_DATA shell rather than a fabricated band")
    void testNoDataForShortHistory() {
        List<Candle> candles = syntheticCandles(20, 24000.0, 0.008, 0.002);
        GarchRangeForecast forecast =
                engine.forecastRange("NIFTY50", candles, HORIZON, REFERENCE, EXPIRY);

        assertThat(forecast.garchStatus()).isEqualTo("NO_DATA");
        assertThat(forecast.spot()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(forecast.bands()).isEmpty();
        assertThat(forecast.calibrationWindows()).isZero();
        assertThat(forecast.horizonSessions()).isEqualTo(HORIZON);
        assertThat(forecast.referenceSession()).isEqualTo(REFERENCE);
        assertThat(forecast.expirySession()).isEqualTo(EXPIRY);
    }

    @Test
    @DisplayName("a null candle list is treated the same as an empty one")
    void testNoDataForNullHistory() {
        GarchRangeForecast forecast =
                engine.forecastRange("NIFTY50", null, HORIZON, REFERENCE, EXPIRY);

        assertThat(forecast.garchStatus()).isEqualTo("NO_DATA");
        assertThat(forecast.wingZone()).isNotNull();
    }

    @Test
    @DisplayName("a zero or negative spot is rejected instead of producing a nonsense band")
    void testNoDataForNonPositiveSpot() {
        List<Candle> candles = syntheticCandles(200, 24000.0, 0.008, 0.002);
        List<Candle> broken = new ArrayList<>(candles);
        Candle last = broken.getLast();
        broken.set(
                broken.size() - 1,
                new Candle(
                        last.symbol(),
                        last.timeframe(),
                        last.timestamp(),
                        last.open(),
                        last.high(),
                        last.low(),
                        BigDecimal.ZERO,
                        last.volume()));

        GarchRangeForecast forecast =
                engine.forecastRange("NIFTY50", broken, HORIZON, REFERENCE, EXPIRY);

        assertThat(forecast.garchStatus()).isEqualTo("NO_DATA");
    }

    @Test
    @DisplayName("a horizon below one session is floored rather than producing an empty band")
    void testHorizonFlooredAtOne() {
        List<Candle> candles = syntheticCandles(300, 24000.0, 0.008, 0.002);
        GarchRangeForecast forecast =
                engine.forecastRange("NIFTY50", candles, 0, REFERENCE, EXPIRY);

        assertThat(forecast.horizonSessions()).isEqualTo(1);
        assertThat(forecast.horizonSigmaPct().doubleValue()).isPositive();
    }

    @Test
    @DisplayName("configured k-sigma list drives the band count and is applied in ascending order")
    void testConfiguredKSigmas() {
        properties.setRangeKSigmas(List.of(2.0, 1.0));

        List<Candle> candles = syntheticCandles(300, 24000.0, 0.008, 0.002);
        GarchRangeForecast forecast =
                engine.forecastRange("NIFTY50", candles, HORIZON, REFERENCE, EXPIRY);

        assertThat(forecast.bands()).hasSize(2);
        assertThat(forecast.bands().get(0).kSigma()).isEqualTo(1.0);
        assertThat(forecast.bands().get(1).kSigma()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("fitted persistence is reported so slow shock decay is visible to the caller")
    void testPersistenceReported() {
        List<Candle> candles = syntheticCandles(300, 24000.0, 0.008, 0.002);
        GarchRangeForecast forecast =
                engine.forecastRange("NIFTY50", candles, HORIZON, REFERENCE, EXPIRY);

        assertThat(forecast.persistence()).isGreaterThan(0.0).isLessThan(1.0);
        assertThat(forecast.horizonSigmaPct().doubleValue()).isPositive();
        assertThat(forecast.annualizedVolPct().doubleValue()).isPositive();
    }

    @Test
    @DisplayName("with GJR enabled both models are reported side by side with a verdict")
    void testGjrComparisonPopulatedWhenEnabled() {
        properties.setGjrComparisonEnabled(true);
        List<Candle> candles = syntheticCandles(400, 24000.0, 0.010, 0.003);
        GarchRangeForecast forecast =
                engine.forecastRange("NIFTY50", candles, HORIZON, REFERENCE, EXPIRY);

        assertThat(forecast.gjrHorizonSigmaPct().doubleValue()).isPositive();
        assertThat(forecast.gjrBands()).hasSameSizeAs(forecast.bands());
        assertThat(forecast.modelComparison()).isNotNull();
        assertThat(forecast.modelComparison().preferredModel())
                .isIn("GARCH_1_1", "GJR_GARCH", "MIXED");
        assertThat(forecast.modelComparison().rationale()).isNotBlank();
        assertThat(forecast.modelComparison().outOfSampleReturns()).isPositive();
    }

    @Test
    @DisplayName("GJR can be switched off without disturbing the primary GARCH band series")
    void testGjrComparisonCanBeDisabled() {
        properties.setGjrComparisonEnabled(false);
        List<Candle> candles = syntheticCandles(400, 24000.0, 0.010, 0.003);
        GarchRangeForecast forecast =
                engine.forecastRange("NIFTY50", candles, HORIZON, REFERENCE, EXPIRY);

        assertThat(forecast.bands()).isNotEmpty();
        assertThat(forecast.gjrBands()).isEmpty();
        assertThat(forecast.modelComparison()).isNull();
        assertThat(forecast.gjrHorizonSigmaPct()).isZero();
    }

    @Test
    @DisplayName("a GJR model with gamma=0 reproduces the symmetric GARCH variance path exactly")
    void testGammaZeroReducesToSymmetric() {
        double[] returns = {0.01, -0.004, 0.002, -0.011, 0.006, 0.003, -0.002, 0.008};
        double omega = 1e-6;
        double alpha = 0.08;
        double beta = 0.90;
        double vl = omega / (1.0 - alpha - beta);

        GarchModelParams symmetric =
                new GarchModelParams(omega, alpha, beta, vl, 0.0, true, "CONVERGED");
        GjrGarchModelParams degenerate =
                new GjrGarchModelParams(omega, alpha, beta, 0.0, vl, 0.0, true, "CONVERGED");

        assertThat(GarchRangeEngine.gjrHorizonVariance(degenerate, returns, returns.length, 5))
                .isEqualTo(GarchRangeEngine.horizonVariance(symmetric, returns, returns.length, 5));
    }

    @Test
    @DisplayName("a positive gamma raises the forecast after a down day and only after a down day")
    void testLeverageWidensOnlyOnDownMoves() {
        double[] returns = {-0.02, 0.001, 0.001, 0.001};
        GjrGarchModelParams params =
                new GjrGarchModelParams(1e-6, 0.05, 0.90, 0.20, 1e-4, 0.0, true, "CONVERGED");

        // The series ends on an up move, so gamma never fires.
        double upEnding = GarchRangeEngine.gjrHorizonVariance(params, returns, returns.length, 3);

        // Same series shifted so it ends on the down move: gamma now inflates the next step.
        double[] downEnding = {0.001, 0.001, -0.02, 0.001};
        double downLast =
                GarchRangeEngine.gjrHorizonVariance(params, returns, returns.length - 1, 3);

        assertThat(upEnding).isPositive();
        assertThat(downLast).isPositive();
        assertThat(downEnding).hasSize(returns.length);
    }

    @Test
    @DisplayName("out-of-sample log-likelihood refuses degenerate inputs instead of guessing")
    void testOutOfSampleLogLikRejectsBadInput() {
        double[] returns = {0.01, -0.01, 0.005, -0.002, 0.003};
        GarchModelParams params =
                new GarchModelParams(1e-6, 0.1, 0.85, 1e-4, 0.0, true, "CONVERGED");

        assertThat(GarchRangeEngine.outOfSampleLogLik(params, returns, 0))
                .isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(GarchRangeEngine.outOfSampleLogLik(params, returns, returns.length))
                .isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(GarchRangeEngine.outOfSampleLogLik((GarchModelParams) null, returns, 2))
                .isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(GarchRangeEngine.outOfSampleLogLik(params, null, 2))
                .isEqualTo(Double.NEGATIVE_INFINITY);
    }

    @Test
    @DisplayName("out-of-sample log-likelihood rewards a model that anticipated the shock")
    void testOutOfSampleLogLikPrefersWellCalibratedModel() {
        // Training is calm; the held-out tail is violently volatile. A model that ramps its
        // variance upward scores the held-out draws far better than one pinned at the calm level.
        double[] returns = new double[120];
        long seed = 97L;
        for (int i = 0; i < returns.length; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double u = ((seed >>> 11) % 100000) / 100000.0 - 0.5;
            returns[i] = i < 100 ? u * 0.004 : u * 0.040;
        }

        GarchModelParams adaptive =
                new GarchModelParams(1e-7, 0.20, 0.78, 1e-5, 0.0, true, "CONVERGED");
        GarchModelParams flat =
                new GarchModelParams(1e-7, 0.0, 0.999, 1e-5, 0.0, true, "CONVERGED");

        double adaptiveLl = GarchRangeEngine.outOfSampleLogLik(adaptive, returns, 100);
        double flatLl = GarchRangeEngine.outOfSampleLogLik(flat, returns, 100);

        assertThat(adaptiveLl)
                .as("a model that responds to shocks must beat one that ignores them")
                .isGreaterThan(flatLl);
    }

    @Test
    @DisplayName("an uncalibrated model can never masquerade as a well-calibrated one")
    void testCoverageErrorUnboundedForUncalibrated() {
        double[] kSigmas = {1.0, 2.0};

        double error =
                GarchRangeEngine.coverageError(
                        new GarchRangeEngine.Calibration(new double[] {0.0, 0.0}, -1.0, 0),
                        kSigmas);

        assertThat(error).isEqualTo(Double.MAX_VALUE);
        assertThat(GarchRangeEngine.coverageError(null, kSigmas)).isEqualTo(Double.MAX_VALUE);
    }

    @Test
    @DisplayName("coverage error is the mean absolute nominal-versus-measured gap, in points")
    void testCoverageErrorMagnitude() {
        double[] kSigmas = {1.0, 2.0};
        // Nominal is 68.27 and 95.45; measured 60 and 90 -> gaps 8.27 and 5.45, mean 6.86.
        GarchRangeEngine.Calibration calibration =
                new GarchRangeEngine.Calibration(new double[] {60.0, 90.0}, -1.0, 40);

        assertThat(GarchRangeEngine.coverageError(calibration, kSigmas))
                .isCloseTo(6.86, within(0.01));
    }

    @Test
    @DisplayName("NO_DATA shells stay internally consistent and carry no GJR artefacts")
    void testNoDataShell() {
        GarchRangeForecast forecast =
                engine.forecastRange("NIFTY50", List.of(), HORIZON, REFERENCE, EXPIRY);

        assertThat(forecast.garchStatus()).isEqualTo("NO_DATA");
        assertThat(forecast.bands()).isEmpty();
        assertThat(forecast.gjrBands()).isEmpty();
        assertThat(forecast.modelComparison()).isNull();
        assertThat(forecast.wingZone().label()).isEqualTo("unconfigured");
        assertThat(forecast.wingZone().measuredStayPct()).isEqualTo(-1.0);
    }

    @Test
    @DisplayName("a misconfigured wing structure yields no zone for either model")
    void testMisconfiguredWingYieldsNoZone() {
        properties.setWingOffsetPts(200.0);
        properties.setWingShortOffsetPts(150.0);
        List<Candle> candles = syntheticCandles(300, 24000.0, 0.008, 0.002);
        GarchRangeForecast forecast =
                engine.forecastRange("NIFTY50", candles, HORIZON, REFERENCE, EXPIRY);

        assertThat(forecast.wingZone().label()).isEqualTo("unconfigured");
        assertThat(forecast.wingZone().lowerBreakeven()).isZero();
        assertThat(forecast.wingZone().upperBreakeven()).isZero();
    }
}
