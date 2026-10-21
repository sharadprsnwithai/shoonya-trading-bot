package com.tradingbot.strategy.monthlyrange.engine;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.monthlyrange.config.MonthlyRangeProperties;
import com.tradingbot.strategy.monthlyrange.model.GarchModelParams;
import com.tradingbot.strategy.monthlyrange.model.GarchRangeBand;
import com.tradingbot.strategy.monthlyrange.model.GarchRangeForecast;
import com.tradingbot.strategy.monthlyrange.model.GjrGarchModelParams;
import com.tradingbot.strategy.monthlyrange.model.MarketBias;
import com.tradingbot.strategy.monthlyrange.model.ModelComparison;
import com.tradingbot.strategy.monthlyrange.model.WingZoneForecast;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Multi-horizon volatility range forecaster for short-dated structures.
 *
 * <p>Fits a symmetric GARCH(1,1) and a GJR-GARCH carrying a leverage term, calibrates both against
 * realised history, and reports a head-to-head verdict. The GARCH band series remains primary for
 * continuity; callers that want the leverage-aware view read {@link GarchRangeForecast#gjrBands()}.
 *
 * <p>The distinction this engine exists to make explicit is that a band's Gaussian probability
 * describes the <i>terminal</i> return, while a structure held to expiry is exposed to the whole
 * <i>path</i>. Measured coverage therefore sits below the nominal figure, and the gap between them
 * is the cost of being marked against an excursion rather than a close.
 *
 * <p>Calibration is genuinely out-of-sample on the variance path: each origin replays the filter
 * using only the returns observed up to that point. Parameters themselves come from the full fitted
 * series, so the measurement is still mildly optimistic — a deliberate trade against refitting once
 * per origin, which would cost a full MLE optimisation per window for little additional signal.
 */
@Component
public class GarchRangeEngine {

    private static final Logger log = LoggerFactory.getLogger(GarchRangeEngine.class);
    private static final int MIN_CANDLES = 60;
    private static final double MIN_SIGMA = 1e-10;
    private static final double UNMEASURED = -1.0;

    private final Garch11Optimizer garchOptimizer;
    private final GjrGarchOptimizer gjrOptimizer;
    private final GarchVolForecaster volForecaster;
    private final MonthlyRangeProperties properties;
    private final MonthlyDirectionalProbabilityEngine directionalEngine;

    /** Result of a walk-forward calibration pass for a single volatility model. */
    record Calibration(double[] measuredCoverage, double measuredZoneStayPct, int windows) {}

    /**
     * Model-agnostic cumulative variance projection, so a single calibration loop can score both
     * volatility models without duplicating the excursion logic.
     */
    @FunctionalInterface
    interface VarianceForecast {
        double cumulative(double[] returns, int uptoExclusive, int horizon);
    }

    public GarchRangeEngine(
            Garch11Optimizer garchOptimizer,
            GjrGarchOptimizer gjrOptimizer,
            GarchVolForecaster volForecaster) {
        this(garchOptimizer, gjrOptimizer, volForecaster, new MonthlyRangeProperties(), null);
    }

    public GarchRangeEngine(
            Garch11Optimizer garchOptimizer,
            GjrGarchOptimizer gjrOptimizer,
            GarchVolForecaster volForecaster,
            MonthlyRangeProperties properties) {
        this(garchOptimizer, gjrOptimizer, volForecaster, properties, null);
    }

    @Autowired
    public GarchRangeEngine(
            Garch11Optimizer garchOptimizer,
            GjrGarchOptimizer gjrOptimizer,
            GarchVolForecaster volForecaster,
            MonthlyRangeProperties properties,
            @Autowired(required = false) MonthlyDirectionalProbabilityEngine directionalEngine) {
        this.garchOptimizer = garchOptimizer;
        this.gjrOptimizer = gjrOptimizer;
        this.volForecaster = volForecaster;
        this.properties = properties != null ? properties : new MonthlyRangeProperties();
        this.directionalEngine = directionalEngine;
    }

    /**
     * Produces a horizon-scoped volatility forecast for a symbol, calibrating each band against the
     * realised high/low excursion of comparable out-of-sample windows.
     *
     * @param symbol canonical symbol being forecast
     * @param dailyCandles daily candles oldest first; the last close is the spot reference
     * @param horizonSessions trading sessions the forecast covers, always at least 1
     * @param referenceSession last completed session the spot was struck from
     * @param expirySession contractual expiry the horizon runs through
     * @return the forecast, or a NO_DATA shell when the candle series is too short to fit
     */
    public GarchRangeForecast forecastRange(
            String symbol,
            List<Candle> dailyCandles,
            int horizonSessions,
            LocalDate referenceSession,
            LocalDate expirySession) {
        int horizon = Math.max(horizonSessions, 1);
        Instant now = Instant.now();

        if (dailyCandles == null || dailyCandles.size() < MIN_CANDLES) {
            log.warn(
                    "[GARCH-RANGE] {} has {} daily candles, below the {} required; returning NO_DATA",
                    symbol,
                    dailyCandles == null ? 0 : dailyCandles.size(),
                    MIN_CANDLES);
            return noData(symbol, horizon, referenceSession, expirySession, now);
        }

        double spot = dailyCandles.getLast().close().doubleValue();
        if (spot <= 0) {
            return noData(symbol, horizon, referenceSession, expirySession, now);
        }

        double[] returns = logReturns(dailyCandles);
        if (returns.length < MIN_CANDLES) {
            return noData(symbol, horizon, referenceSession, expirySession, now);
        }

        GarchModelParams params = garchOptimizer.fit(returns);
        GarchVolForecaster.VolForecastResult vol =
                volForecaster.forecastMonthlyVol(params, returns, horizon);
        double horizonSigma = vol.cumulativeMonthlyVolDecimal();

        double[] kSigmas = resolveKSigmas();
        VarianceForecast garchVar = (r, upto, h) -> horizonVariance(params, r, upto, h);
        Calibration garchCal = calibrate(dailyCandles, returns, horizon, kSigmas, garchVar);
        List<GarchRangeBand> bands = buildBands(spot, horizonSigma, kSigmas, garchCal);
        WingZoneForecast wingZone = buildWingZone(spot, horizon, horizonSigma, garchCal);

        BigDecimal gjrSigmaPct = BigDecimal.ZERO;
        List<GarchRangeBand> gjrBands = List.of();
        ModelComparison comparison = null;

        if (properties.isGjrComparisonEnabled()) {
            GjrGarchModelParams gjrParams = gjrOptimizer.fit(returns);
            double gjrSigma = gjrHorizonVariance(gjrParams, returns, returns.length, horizon);
            gjrSigma =
                    Double.isNaN(gjrSigma) || gjrSigma <= 0.0 ? horizonSigma : Math.sqrt(gjrSigma);

            VarianceForecast gjrVar = (r, upto, h) -> gjrHorizonVariance(gjrParams, r, upto, h);
            Calibration gjrCal = calibrate(dailyCandles, returns, horizon, kSigmas, gjrVar);
            gjrBands = buildBands(spot, gjrSigma, kSigmas, gjrCal);
            gjrSigmaPct = round2(gjrSigma * 100.0);

            comparison =
                    compareModels(
                            returns,
                            params,
                            gjrParams,
                            kSigmas,
                            garchCal,
                            gjrCal,
                            gjrSigmaPct.doubleValue() > 0.0 && gjrParams.converged());
        }

        MarketBias directionalBias = MarketBias.NEUTRAL;
        Double directionalConfidence = null;
        if (directionalEngine != null) {
            try {
                var ds =
                        directionalEngine.evaluateDirectionalScore(
                                symbol, BigDecimal.valueOf(spot), dailyCandles, null, null, 65.0);
                if (ds != null) {
                    directionalBias = ds.bias();
                    directionalConfidence =
                            ds.bias() == MarketBias.BULLISH
                                    ? ds.bullishProbabilityPct()
                                    : (ds.bias() == MarketBias.BEARISH
                                            ? ds.bearishProbabilityPct()
                                            : Math.max(
                                                    ds.bullishProbabilityPct(),
                                                    ds.bearishProbabilityPct()));
                }
            } catch (Exception e) {
                log.warn(
                        "[GARCH-RANGE] Directional evaluation failed for {}: {}",
                        symbol,
                        e.getMessage());
            }
        }

        return new GarchRangeForecast(
                symbol,
                round2(spot),
                horizon,
                round2(horizonSigma * 100.0),
                round2(vol.annualizedVolPct()),
                round2(vol.terminalDailyVolPct()),
                round2d(params.persistence()),
                referenceSession,
                expirySession,
                List.copyOf(bands),
                gjrSigmaPct,
                List.copyOf(gjrBands),
                wingZone,
                comparison,
                garchCal.windows(),
                (int) Math.floor(returns.length * properties.getCalibrationTrainPct()),
                params.statusMessage(),
                now,
                directionalBias,
                directionalConfidence);
    }

    private List<GarchRangeBand> buildBands(
            double spot, double horizonSigma, double[] kSigmas, Calibration calibration) {
        List<GarchRangeBand> bands = new ArrayList<>(kSigmas.length);
        for (int i = 0; i < kSigmas.length; i++) {
            double k = kSigmas[i];
            bands.add(
                    new GarchRangeBand(
                            String.format("%.1f sigma", k),
                            k,
                            round2(spot * Math.exp(-k * horizonSigma)),
                            round2(spot * Math.exp(k * horizonSigma)),
                            round2d(100.0 * bandConfidence(k)),
                            calibration.windows() > 0
                                    ? round2d(calibration.measuredCoverage()[i])
                                    : UNMEASURED,
                            calibration.windows()));
        }
        return bands;
    }

    /**
     * Scores both volatility models and returns a verdict.
     *
     * <p>The primary lens is out-of-sample predictive log-likelihood: parameters are refitted on
     * the training segment only, then both models are scored on returns neither of them saw. The
     * extra leverage parameter has to earn its place there or it is overfitting. Coverage
     * calibration error is the secondary lens and the one that matters for sizing bands.
     */
    ModelComparison compareModels(
            double[] returns,
            GarchModelParams params,
            GjrGarchModelParams gjrParams,
            double[] kSigmas,
            Calibration garchCal,
            Calibration gjrCal,
            boolean gjrConverged) {
        int n = returns.length;
        int split = (int) Math.floor(n * properties.getCalibrationTrainPct());
        int oos = Math.max(n - split, 0);

        GarchModelParams garchTrain = garchOptimizer.fit(java.util.Arrays.copyOf(returns, split));
        GjrGarchModelParams gjrTrain = gjrOptimizer.fit(java.util.Arrays.copyOf(returns, split));
        double garchOos = outOfSampleLogLik(garchTrain, returns, split);
        double gjrOos = outOfSampleLogLik(gjrTrain, returns, split);

        double garchAic = 2.0 * 3.0 - 2.0 * params.logLikelihood();
        double gjrAic = 2.0 * 4.0 - 2.0 * gjrParams.logLikelihood();
        double garchBic = 3.0 * Math.log(n) - 2.0 * params.logLikelihood();
        double gjrBic = 4.0 * Math.log(n) - 2.0 * gjrParams.logLikelihood();

        double garchErr = coverageError(garchCal, kSigmas);
        double gjrErr = coverageError(gjrCal, kSigmas);

        boolean gjrBetterOos = gjrOos > garchOos;
        boolean gjrBetterCov = gjrErr < garchErr;
        String preferred =
                gjrBetterOos && gjrBetterCov
                        ? "GJR_GARCH"
                        : (!gjrBetterOos && !gjrBetterCov ? "GARCH_1_1" : "MIXED");

        // When alpha collapses onto its zero lower bound every shock response is carried by gamma,
        // so the leverage ratio is unbounded rather than large. Reporting it as a plain multiple
        // would invite a reading the fit cannot support.
        boolean alphaCollapsed = gjrParams.alpha() < 1e-6;
        String leverageNote =
                alphaCollapsed
                        ? String.format(
                                "alpha collapsed to %.2e, so gamma carries the whole shock"
                                        + " response and the downside/upside ratio is unbounded rather"
                                        + " than large; treat gamma=%.4f as the sole asymmetry estimate.",
                                gjrParams.alpha(), gjrParams.gamma())
                        : String.format(
                                "gamma=%.4f, downside/upside response ratio=%.2f.",
                                gjrParams.gamma(), gjrParams.leverageRatio());

        String rationale =
                String.format(
                        "Out-of-sample log-likelihood GARCH %.2f vs GJR %.2f (higher is better)."
                                + " Coverage error GARCH %.2f pts vs GJR %.2f pts (lower is better)."
                                + " Leverage %s Verdict %s.",
                        garchOos, gjrOos, garchErr, gjrErr, leverageNote, preferred);

        return new ModelComparison(
                preferred,
                round2d(garchOos),
                round2d(gjrOos),
                round2d(garchAic),
                round2d(gjrAic),
                round2d(garchBic),
                round2d(gjrBic),
                round2d(gjrParams.gamma()),
                round2d(gjrParams.leverageRatio()),
                round2d(garchErr),
                round2d(gjrErr),
                garchCal.windows() > 0 ? round2d(garchCal.measuredZoneStayPct()) : UNMEASURED,
                gjrCal.windows() > 0 ? round2d(gjrCal.measuredZoneStayPct()) : UNMEASURED,
                oos,
                gjrConverged,
                rationale);
    }

    /**
     * Mean absolute gap between the probability a band nominally claims and the coverage actually
     * measured for it, in percentage points. Returns {@link Double#MAX} when uncalibrated so an
     * unmeasured model can never look well-calibrated by default.
     */
    static double coverageError(Calibration calibration, double[] kSigmas) {
        if (calibration == null || calibration.windows() <= 0) {
            return Double.MAX_VALUE;
        }
        double sum = 0.0;
        for (int i = 0; i < kSigmas.length; i++) {
            sum += Math.abs(100.0 * bandConfidence(kSigmas[i]) - calibration.measuredCoverage()[i]);
        }
        return sum / kSigmas.length;
    }

    /**
     * Predictive log-likelihood of the held-out tail under a fitted model.
     *
     * <p>The filter is seeded and warmed entirely on the training segment, so the first held-out
     * return is scored with a forecast built only from data the model never saw. Subsequent
     * held-out returns update the filter state — standard practice, since a live system would have
     * observed them too — but never the parameters.
     */
    static double outOfSampleLogLik(GarchModelParams params, double[] returns, int split) {
        if (params == null || returns == null || split <= 0 || split >= returns.length) {
            return Double.NEGATIVE_INFINITY;
        }
        double mean = 0.0;
        for (int i = 0; i < split; i++) {
            mean += returns[i];
        }
        mean /= split;

        double sumSq = 0.0;
        for (int i = 0; i < split; i++) {
            double d = returns[i] - mean;
            sumSq += d * d;
        }
        double sigma2 = Math.max(sumSq / split, 1e-8);

        double ll = 0.0;
        for (int i = 0; i < returns.length; i++) {
            double eps = returns[i] - mean;
            if (i > 0) {
                double prevEps = returns[i - 1] - mean;
                sigma2 =
                        params.omega()
                                + params.alpha() * (prevEps * prevEps)
                                + params.beta() * sigma2;
            }
            if (i >= split) {
                if (sigma2 <= MIN_SIGMA || Double.isNaN(sigma2)) {
                    return Double.NEGATIVE_INFINITY;
                }
                ll += -0.5 * (Math.log(2.0 * Math.PI) + Math.log(sigma2) + (eps * eps) / sigma2);
            }
        }
        return ll;
    }

    /**
     * Out-of-sample predictive log-likelihood for a fitted GJR-GARCH model.
     *
     * @param params parameters fitted on the training segment only
     * @param returns full return series
     * @param split index at which the held-out segment begins
     * @return summed held-out log-likelihood, or negative infinity when inadmissible
     */
    static double outOfSampleLogLik(GjrGarchModelParams params, double[] returns, int split) {
        if (params == null || returns == null || split <= 0 || split >= returns.length) {
            return Double.NEGATIVE_INFINITY;
        }
        double mean = 0.0;
        for (int i = 0; i < split; i++) {
            mean += returns[i];
        }
        mean /= split;

        double sumSq = 0.0;
        for (int i = 0; i < split; i++) {
            double d = returns[i] - mean;
            sumSq += d * d;
        }
        double sigma2 = Math.max(sumSq / split, 1e-8);

        double ll = 0.0;
        for (int i = 0; i < returns.length; i++) {
            double eps = returns[i] - mean;
            if (i > 0) {
                double prevEps = returns[i - 1] - mean;
                double shockCoef =
                        prevEps < 0.0 ? (params.alpha() + params.gamma()) : params.alpha();
                sigma2 = params.omega() + shockCoef * (prevEps * prevEps) + params.beta() * sigma2;
            }
            if (i >= split) {
                if (sigma2 <= MIN_SIGMA || Double.isNaN(sigma2)) {
                    return Double.NEGATIVE_INFINITY;
                }
                ll += -0.5 * (Math.log(2.0 * Math.PI) + Math.log(sigma2) + (eps * eps) / sigma2);
            }
        }
        return ll;
    }

    /**
     * Gaussian probability that a standard normal draw falls within {@code +/-k} standard
     * deviations, i.e. {@code 2 * Phi(k) - 1}. Computed as {@code 1 - erfcc(k / sqrt(2))} using the
     * Numerical Recipes complementary error function, whose fractional error stays below 1.2e-7.
     *
     * @param k band width in standard deviations, expected non-negative
     * @return probability in the range [0, 1]
     */
    static double bandConfidence(double k) {
        if (k <= 0) {
            return 0.0;
        }
        double c = 1.0 - erfcc(k / Math.sqrt(2.0));
        return Math.min(Math.max(c, 0.0), 1.0);
    }

    /**
     * Complementary error function via the Chebyshev-fitted rational approximation from Numerical
     * Recipes. Chosen over an Abramowitz &amp; Stegun 7.1.26 series because the latter is only
     * accurate to ~1.5e-7 in absolute terms, which is too coarse once scaled into a coverage
     * percentage.
     *
     * @param x evaluation point
     * @return erfc(x)
     */
    static double erfcc(double x) {
        double z = Math.abs(x);
        double t = 2.0 / (2.0 + z);
        double ty = 4.0 * t - 2.0;
        double[] cof = {
            -1.3026537197817094, 6.4196979235649026e-1, 1.9476473204185836e-2,
                    -9.561514786808631e-3,
            -9.46595344482036e-4, 3.66839497852761e-4, 4.2523324806907e-5, -2.0278578112534e-5,
            -1.624290004647e-6, 1.303655835580e-6, 1.5626441722e-8, -8.5238095915e-8,
            6.529054439e-9, 5.059343495e-9, -9.91364156e-10, -2.27365122e-10,
            9.6467911e-11, 2.394038e-12, -6.886027e-12, 8.94487e-13,
            3.13092e-13, -1.12708e-13, 3.81e-16, 7.106e-15
        };
        double d = 0.0;
        double dd = 0.0;
        for (int j = cof.length - 1; j > 0; j--) {
            double tmp = d;
            d = ty * d - dd + cof[j];
            dd = tmp;
        }
        double ans = t * Math.exp(-z * z + 0.5 * (cof[0] + ty * d) - dd);
        return x >= 0.0 ? ans : 2.0 - ans;
    }

    /**
     * Builds the spot-containment view for the configured wing structure.
     *
     * <p>The profit zone is bounded by {@code 2 * shortOffset - wingOffset} on both sides — the
     * point where the two-for-one decline from the short wing cancels the credit collected. The
     * downside is <b>not</b> clipped at the long wing: falling below it simply leaves every leg
     * worthless and the credit is retained, so the call side is profitable all the way down. A
     * short strike sitting inside the long wing is treated as a misconfiguration and yields no
     * zone.
     */
    private WingZoneForecast buildWingZone(
            double spot, int horizon, double horizonSigma, Calibration calibration) {
        double wingOffset = properties.getWingOffsetPts();
        double shortOffset = properties.getWingShortOffsetPts();
        double credit = properties.getWingCreditPts();

        double zoneHalfWidth = 2.0 * shortOffset - wingOffset;
        if (shortOffset <= wingOffset || zoneHalfWidth <= 0.0 || spot <= 0.0) {
            log.warn(
                    "[GARCH-RANGE] Wing structure misconfigured (wing={}, short={}); skipping zone",
                    wingOffset,
                    shortOffset);
            return emptyWingZone(spot, horizon, wingOffset, shortOffset, credit);
        }

        double halfWidthRatio = zoneHalfWidth / spot;
        double gaussian =
                bandConfidence(Math.log1p((zoneHalfWidth + credit) / spot) / horizonSigma);

        return new WingZoneForecast(
                String.format("%.0f/%.0f wing structure", wingOffset, shortOffset),
                round2(spot),
                round2(spot - zoneHalfWidth - credit),
                round2(spot + zoneHalfWidth + credit),
                round2d(100.0 * halfWidthRatio),
                round2d(100.0 * gaussian),
                calibration.windows() > 0 ? round2d(calibration.measuredZoneStayPct()) : UNMEASURED,
                calibration.windows(),
                horizon,
                wingOffset,
                shortOffset,
                credit,
                "Measured stay counts windows whose full high/low excursion never touched either"
                        + " breakeven; the Gaussian figure covers the terminal return only and"
                        + " overstates the true hold-to-expiry probability.");
    }

    /**
     * Walk-forward calibration over the out-of-sample tail of the series.
     *
     * <p>Each origin replays the volatility filter using only the returns observed up to that
     * point, forms the k-sigma band from that origin's spot, then checks whether the realised
     * high/low excursion of the following {@code horizon} sessions stayed inside it.
     *
     * @return measured coverage per k plus the wing-zone stay rate; an empty result when the
     *     out-of-sample segment is too short to yield the configured minimum windows
     */
    Calibration calibrate(
            List<Candle> candles,
            double[] returns,
            int horizon,
            double[] kSigmas,
            VarianceForecast variance) {
        double[] measured = new double[kSigmas.length];
        int n = returns.length;
        int split = (int) Math.floor(n * properties.getCalibrationTrainPct());
        // Origin t leaves candles[1..t+1] observed; the window runs candles[t+2..t+1+horizon].
        int lastOrigin = n - horizon - 1;

        if (lastOrigin < split) {
            log.warn(
                    "[GARCH-RANGE] Calibration segment too short (split={}, lastOrigin={});"
                            + " bands are uncalibrated",
                    split,
                    lastOrigin);
            return new Calibration(measured, UNMEASURED, 0);
        }

        double wingOffset = properties.getWingOffsetPts();
        double shortOffset = properties.getWingShortOffsetPts();
        double credit = properties.getWingCreditPts();
        double zoneHalfWidth = 2.0 * shortOffset - wingOffset;
        boolean zoneConfigured = shortOffset > wingOffset && zoneHalfWidth > 0.0;

        int[] held = new int[kSigmas.length];
        int zoneHeld = 0;
        int windows = 0;

        for (int t = split; t <= lastOrigin; t++) {
            double sigma2H = variance.cumulative(returns, t, horizon);
            if (Double.isNaN(sigma2H) || sigma2H <= 0.0) {
                continue;
            }
            double sigmaH = Math.sqrt(sigma2H);
            double originSpot = candles.get(t + 1).close().doubleValue();
            if (sigmaH <= 0.0 || originSpot <= 0.0) {
                continue;
            }

            double windowHigh = Double.NEGATIVE_INFINITY;
            double windowLow = Double.POSITIVE_INFINITY;
            for (int j = t + 2; j <= t + 1 + horizon; j++) {
                windowHigh = Math.max(windowHigh, candles.get(j).high().doubleValue());
                windowLow = Math.min(windowLow, candles.get(j).low().doubleValue());
            }
            if (Double.isInfinite(windowHigh) || Double.isInfinite(windowLow)) {
                continue;
            }

            windows++;
            for (int i = 0; i < kSigmas.length; i++) {
                double lower = originSpot * Math.exp(-kSigmas[i] * sigmaH);
                double upper = originSpot * Math.exp(kSigmas[i] * sigmaH);
                if (windowLow >= lower && windowHigh <= upper) {
                    held[i]++;
                }
            }

            if (zoneConfigured) {
                double zoneLower = originSpot - zoneHalfWidth - credit;
                double zoneUpper = originSpot + zoneHalfWidth + credit;
                if (windowLow >= zoneLower && windowHigh <= zoneUpper) {
                    zoneHeld++;
                }
            }
        }

        if (windows < properties.getCalibrationMinWindows()) {
            log.warn(
                    "[GARCH-RANGE] Only {} calibration windows available, below the {} required;"
                            + " bands are uncalibrated",
                    windows,
                    properties.getCalibrationMinWindows());
            return new Calibration(new double[kSigmas.length], UNMEASURED, 0);
        }

        for (int i = 0; i < kSigmas.length; i++) {
            measured[i] = 100.0 * held[i] / windows;
        }
        return new Calibration(measured, 100.0 * zoneHeld / windows, windows);
    }

    /**
     * Cumulative h-step-ahead conditional variance for a symmetric GARCH(1,1), measured from the
     * returns observed up to {@code uptoExclusive}. Mirrors {@link
     * GarchVolForecaster#forecastMonthlyVol} exactly — same mean, sample variance, filter recursion
     * and mean-reversion walk — but truncates the series so the forecast is available at a
     * historical origin.
     *
     * @param params fitted GARCH(1,1) parameters
     * @param returns full daily log return series
     * @param uptoExclusive number of leading returns treated as observed
     * @param horizon sessions to project forward
     * @return summed conditional variance across the horizon
     */
    static double horizonVariance(
            GarchModelParams params, double[] returns, int uptoExclusive, int horizon) {
        if (params == null
                || returns == null
                || uptoExclusive <= 0
                || uptoExclusive > returns.length) {
            return Double.NaN;
        }

        double mean = 0.0;
        for (int i = 0; i < uptoExclusive; i++) {
            mean += returns[i];
        }
        mean /= uptoExclusive;

        double sampleVar = sampleVariance(returns, uptoExclusive, mean);

        double omega = params.omega();
        double alpha = params.alpha();
        double beta = params.beta();
        double persistence = Math.min(params.persistence(), 0.999);
        double vl = params.longRunVariance();

        double sigma2 = sampleVar;
        for (int i = 1; i < uptoExclusive; i++) {
            double eps = returns[i - 1] - mean;
            sigma2 = omega + alpha * (eps * eps) + beta * sigma2;
        }

        double lastEps = returns[uptoExclusive - 1] - mean;
        double sigmaNext = Math.max(omega + alpha * (lastEps * lastEps) + beta * sigma2, MIN_SIGMA);
        return cumulativeForward(sigmaNext, vl, persistence, horizon);
    }

    /**
     * Cumulative h-step-ahead conditional variance for a GJR-GARCH with leverage.
     *
     * <p>The one-step forecast applies the shock coefficient conditionally: {@code alpha + gamma}
     * after a negative shock, {@code alpha} otherwise. Beyond one step the indicator's expectation
     * is its unconditional probability, taken as one half under a zero-mean Gaussian, so
     * persistence collapses to {@code alpha + beta + gamma/2} and the forecast decays toward {@code
     * omega / (1 - alpha - beta - gamma/2)}.
     *
     * @param params fitted GJR-GARCH parameters
     * @param returns full daily log return series
     * @param uptoExclusive number of leading returns treated as observed
     * @param horizon sessions to project forward
     * @return summed conditional variance across the horizon
     */
    static double gjrHorizonVariance(
            GjrGarchModelParams params, double[] returns, int uptoExclusive, int horizon) {
        if (params == null
                || returns == null
                || uptoExclusive <= 0
                || uptoExclusive > returns.length) {
            return Double.NaN;
        }

        double mean = 0.0;
        for (int i = 0; i < uptoExclusive; i++) {
            mean += returns[i];
        }
        mean /= uptoExclusive;

        double sampleVar = sampleVariance(returns, uptoExclusive, mean);

        double omega = params.omega();
        double alpha = params.alpha();
        double beta = params.beta();
        double gamma = params.gamma();
        double persistence = Math.min(params.persistence(), 0.999);
        double vl = params.longRunVariance();

        double sigma2 = sampleVar;
        for (int i = 1; i < uptoExclusive; i++) {
            double eps = returns[i - 1] - mean;
            double shockCoef = eps < 0.0 ? (alpha + gamma) : alpha;
            sigma2 = omega + shockCoef * (eps * eps) + beta * sigma2;
        }

        double lastEps = returns[uptoExclusive - 1] - mean;
        double shockCoef = lastEps < 0.0 ? (alpha + gamma) : alpha;
        double sigmaNext =
                Math.max(omega + shockCoef * (lastEps * lastEps) + beta * sigma2, MIN_SIGMA);
        return cumulativeForward(sigmaNext, vl, persistence, horizon);
    }

    private static double cumulativeForward(
            double sigmaNext, double longRunVar, double persistence, int horizon) {
        double total = sigmaNext;
        for (int k = 1; k < horizon; k++) {
            total +=
                    Math.max(
                            longRunVar + Math.pow(persistence, k) * (sigmaNext - longRunVar),
                            MIN_SIGMA);
        }
        return total;
    }

    private static double sampleVariance(double[] returns, int uptoExclusive, double mean) {
        double sumSq = 0.0;
        for (int i = 0; i < uptoExclusive; i++) {
            double diff = returns[i] - mean;
            sumSq += diff * diff;
        }
        return Math.max(sumSq / uptoExclusive, 1e-8);
    }

    private double[] logReturns(List<Candle> candles) {
        int n = candles.size();
        double[] returns = new double[n - 1];
        for (int i = 1; i < n; i++) {
            double prev = candles.get(i - 1).close().doubleValue();
            double cur = candles.get(i).close().doubleValue();
            returns[i - 1] = prev > 0 && cur > 0 ? Math.log(cur / prev) : 0.0;
        }
        return returns;
    }

    private double[] resolveKSigmas() {
        List<Double> configured = properties.getRangeKSigmas();
        if (configured == null || configured.isEmpty()) {
            return new double[] {1.0, 1.5, 2.0};
        }
        return configured.stream()
                .mapToDouble(Double::doubleValue)
                .filter(k -> k > 0)
                .sorted()
                .toArray();
    }

    private static WingZoneForecast emptyWingZone(
            double spot, int horizon, double wingOffset, double shortOffset, double credit) {
        return new WingZoneForecast(
                "unconfigured",
                round2(spot),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0.0,
                0.0,
                UNMEASURED,
                0,
                horizon,
                wingOffset,
                shortOffset,
                credit,
                "Wing offsets are not a valid structure (short strike must sit beyond the long"
                        + " wing); no containment probability was computed.");
    }

    private static GarchRangeForecast noData(
            String symbol,
            int horizon,
            LocalDate referenceSession,
            LocalDate expirySession,
            Instant now) {
        return new GarchRangeForecast(
                symbol,
                BigDecimal.ZERO,
                horizon,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                0.0,
                referenceSession,
                expirySession,
                List.of(),
                BigDecimal.ZERO,
                List.of(),
                emptyWingZone(0.0, horizon, 0.0, 0.0, 0.0),
                null,
                0,
                0,
                "NO_DATA",
                now);
    }

    private static BigDecimal round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static double round2d(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
