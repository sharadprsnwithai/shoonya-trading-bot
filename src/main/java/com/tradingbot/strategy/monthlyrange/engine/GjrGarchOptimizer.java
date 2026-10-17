package com.tradingbot.strategy.monthlyrange.engine;

import com.tradingbot.strategy.monthlyrange.model.GjrGarchModelParams;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pure Java Maximum Likelihood Estimation optimizer for GJR-GARCH(1,1) volatility models.
 *
 * <p>Extends the symmetric {@link Garch11Optimizer} with a fourth parameter, {@code gamma}, which
 * is added to the shock coefficient only when the previous shock was negative. This is the leverage
 * term, and it is the entire reason to prefer this model on an equity index: a symmetric model
 * charges the same volatility for a 1% up day and a 1% down day, which NIFTY does not do.
 *
 * <p>Same Nelder-Mead simplex strategy and percentage-space scaling as the symmetric fitter, raised
 * to four dimensions. Stationarity is enforced against {@code alpha + beta + gamma/2 < 1}, and the
 * downside coefficient {@code alpha + gamma} is barred from going negative so a down day can never
 * <i>reduce</i> forecast volatility.
 */
@Component
public class GjrGarchOptimizer {

    private static final Logger log = LoggerFactory.getLogger(GjrGarchOptimizer.class);
    private static final double MIN_OMEGA_PCT = 1e-4;
    private static final double MAX_PERSISTENCE = 0.998;
    private static final double PENALTY_BASE = 1e8;
    private static final int DIM = 4;

    private final int maxIterations;
    private final double tolerance;

    public GjrGarchOptimizer() {
        this(600, 1e-6);
    }

    public GjrGarchOptimizer(int maxIterations, double tolerance) {
        this.maxIterations = maxIterations;
        this.tolerance = tolerance;
    }

    /**
     * Fits a GJR-GARCH(1,1) model to an array of daily log returns in decimal space.
     *
     * @param returns daily log returns, expected to contain at least five observations
     * @return fitted parameters, or a fallback GARCH(1,1) degenerate fit (gamma = 0) when the data
     *     cannot support an asymmetric model
     */
    public GjrGarchModelParams fit(double[] returns) {
        if (returns == null || returns.length < 5) {
            return createFallbackParams(
                    returns != null ? calculateSampleVariance(returns) : 0.0001, "TOO_FEW_SAMPLES");
        }

        int t = returns.length;
        double[] pctReturns = new double[t];
        for (int i = 0; i < t; i++) {
            pctReturns[i] = returns[i] * 100.0;
        }

        double meanPct = calculateMean(pctReturns);
        double sampleVarPct = calculateSampleVariance(pctReturns, meanPct);
        if (sampleVarPct <= 1e-6) {
            return createFallbackParams(1e-4, "ZERO_OR_TINY_VARIANCE");
        }

        // Seed from the symmetric solution so the extra dimension starts somewhere sensible.
        double initAlpha = 0.06;
        double initBeta = 0.88;
        double initGamma = 0.02;
        double initOmegaPct = sampleVarPct * (1.0 - initAlpha - initBeta - initGamma / 2.0);
        if (initOmegaPct < MIN_OMEGA_PCT) {
            initOmegaPct = MIN_OMEGA_PCT;
        }

        double[] start = new double[] {initOmegaPct, initAlpha, initBeta, initGamma};
        NelderMeadResult result = optimizeNelderMead(pctReturns, meanPct, sampleVarPct, start);

        double omegaPct = result.point[0];
        double alpha = result.point[1];
        double beta = result.point[2];
        double gamma = result.point[3];
        double persistence = alpha + beta + gamma / 2.0;

        if (omegaPct < MIN_OMEGA_PCT
                || alpha < 0.0
                || beta < 0.0
                || (alpha + gamma) < 0.0
                || persistence >= 1.0) {
            log.debug(
                    "[GJR-GARCH] Optimizer out of bounds (omega={}, alpha={}, beta={}, gamma={});"
                            + " falling back to a symmetric degenerate fit",
                    omegaPct,
                    alpha,
                    beta,
                    gamma);
            return createFallbackParams(sampleVarPct / 10000.0, "OPTIMIZATION_OUT_OF_BOUNDS");
        }

        double omegaDec = omegaPct / 10000.0;
        double longRunVarianceDec = omegaDec / (1.0 - persistence);

        return new GjrGarchModelParams(
                omegaDec,
                alpha,
                beta,
                gamma,
                longRunVarianceDec,
                -result.val,
                result.converged,
                result.converged ? "CONVERGED" : "MAX_ITERATIONS_REACHED");
    }

    /**
     * Negative Log-Likelihood for GJR-GARCH in percentage space.
     *
     * @param point simplex point holding omega, alpha, beta, gamma in percentage variance space
     * @param returns percentage-space returns
     * @param mean constant mean of the return series
     * @param sampleVar unconditional sample variance used to seed the filter
     * @return the negative log-likelihood, or a penalty when the point is inadmissible
     */
    double computeNll(double[] point, double[] returns, double mean, double sampleVar) {
        double omega = point[0];
        double alpha = point[1];
        double beta = point[2];
        double gamma = point[3];

        if (omega < MIN_OMEGA_PCT) {
            return PENALTY_BASE + (MIN_OMEGA_PCT - omega) * 1e7;
        }
        if (alpha < 0.0) {
            return PENALTY_BASE + (-alpha) * 1e7;
        }
        if (beta < 0.0) {
            return PENALTY_BASE + (-beta) * 1e7;
        }
        // A down day may never reduce forecast volatility.
        if ((alpha + gamma) < 0.0) {
            return PENALTY_BASE + (-(alpha + gamma)) * 1e7;
        }
        if (alpha + beta + gamma / 2.0 >= MAX_PERSISTENCE) {
            return PENALTY_BASE + ((alpha + beta + gamma / 2.0) - MAX_PERSISTENCE) * 1e7;
        }

        int t = returns.length;
        double currentSigma2 = sampleVar;
        double nll = 0.0;

        for (int i = 0; i < t; i++) {
            double eps = returns[i] - mean;
            if (i > 0) {
                double prevEps = returns[i - 1] - mean;
                // Leverage: the shock coefficient doubles up only when the prior shock was
                // negative.
                double shockCoef = prevEps < 0.0 ? (alpha + gamma) : alpha;
                currentSigma2 = omega + shockCoef * (prevEps * prevEps) + beta * currentSigma2;
            }

            if (currentSigma2 <= 1e-8
                    || Double.isNaN(currentSigma2)
                    || Double.isInfinite(currentSigma2)) {
                return PENALTY_BASE;
            }

            nll +=
                    0.5
                            * (Math.log(2.0 * Math.PI)
                                    + Math.log(currentSigma2)
                                    + (eps * eps) / currentSigma2);
        }

        return Double.isNaN(nll) ? PENALTY_BASE : nll;
    }

    private GjrGarchModelParams createFallbackParams(double sampleVarDec, String reason) {
        double safeVar = Math.max(sampleVarDec, 1e-6);
        double fallbackAlpha = 0.08;
        double fallbackBeta = 0.88;
        // gamma = 0 makes the fallback exactly a symmetric GARCH(1,1).
        double fallbackOmega = safeVar * (1.0 - fallbackAlpha - fallbackBeta);
        return new GjrGarchModelParams(
                fallbackOmega,
                fallbackAlpha,
                fallbackBeta,
                0.0,
                safeVar,
                0.0,
                true,
                "FALLBACK: " + reason);
    }

    private double calculateMean(double[] data) {
        double sum = 0.0;
        for (double v : data) {
            sum += v;
        }
        return sum / data.length;
    }

    private double calculateSampleVariance(double[] data) {
        return calculateSampleVariance(data, calculateMean(data));
    }

    private double calculateSampleVariance(double[] data, double mean) {
        double sumSq = 0.0;
        for (double v : data) {
            double diff = v - mean;
            sumSq += diff * diff;
        }
        return Math.max(sumSq / data.length, 1e-8);
    }

    private static class NelderMeadResult {
        double[] point;
        double val;
        boolean converged;

        NelderMeadResult(double[] point, double val, boolean converged) {
            this.point = point;
            this.val = val;
            this.converged = converged;
        }
    }

    private NelderMeadResult optimizeNelderMead(
            double[] returns, double mean, double sampleVar, double[] start) {
        double[][] simplex = new double[DIM + 1][DIM];
        double[] values = new double[DIM + 1];

        simplex[0] = Arrays.copyOf(start, DIM);
        values[0] = computeNll(simplex[0], returns, mean, sampleVar);

        double[] steps = new double[] {start[0] * 0.2 + 0.01, 0.03, 0.03, 0.02};
        for (int i = 1; i <= DIM; i++) {
            simplex[i] = Arrays.copyOf(start, DIM);
            simplex[i][i - 1] += steps[i - 1];
            values[i] = computeNll(simplex[i], returns, mean, sampleVar);
        }

        final double alphaNm = 1.0;
        final double gammaNm = 2.0;
        final double rhoNm = 0.5;
        final double sigmaNm = 0.5;
        boolean converged = false;

        for (int iter = 0; iter < maxIterations; iter++) {
            sortSimplex(simplex, values);

            if (Math.abs(values[DIM] - values[0]) < tolerance) {
                converged = true;
                break;
            }

            double[] centroid = new double[DIM];
            for (int i = 0; i < DIM; i++) {
                for (int d = 0; d < DIM; d++) {
                    centroid[d] += simplex[i][d] / DIM;
                }
            }

            double[] reflected = new double[DIM];
            for (int d = 0; d < DIM; d++) {
                reflected[d] = centroid[d] + alphaNm * (centroid[d] - simplex[DIM][d]);
            }
            double reflectedVal = computeNll(reflected, returns, mean, sampleVar);

            if (values[0] <= reflectedVal && reflectedVal < values[DIM - 1]) {
                simplex[DIM] = reflected;
                values[DIM] = reflectedVal;
                continue;
            }

            if (reflectedVal < values[0]) {
                double[] expanded = new double[DIM];
                for (int d = 0; d < DIM; d++) {
                    expanded[d] = centroid[d] + gammaNm * (reflected[d] - centroid[d]);
                }
                double expandedVal = computeNll(expanded, returns, mean, sampleVar);
                if (expandedVal < reflectedVal) {
                    simplex[DIM] = expanded;
                    values[DIM] = expandedVal;
                } else {
                    simplex[DIM] = reflected;
                    values[DIM] = reflectedVal;
                }
                continue;
            }

            if (reflectedVal < values[DIM]) {
                double[] contracted = new double[DIM];
                for (int d = 0; d < DIM; d++) {
                    contracted[d] = centroid[d] + rhoNm * (reflected[d] - centroid[d]);
                }
                double contractedVal = computeNll(contracted, returns, mean, sampleVar);
                if (contractedVal < reflectedVal) {
                    simplex[DIM] = contracted;
                    values[DIM] = contractedVal;
                    continue;
                }
            } else {
                double[] contracted = new double[DIM];
                for (int d = 0; d < DIM; d++) {
                    contracted[d] = centroid[d] - rhoNm * (simplex[DIM][d] - centroid[d]);
                }
                double contractedVal = computeNll(contracted, returns, mean, sampleVar);
                if (contractedVal < values[DIM]) {
                    simplex[DIM] = contracted;
                    values[DIM] = contractedVal;
                    continue;
                }
            }

            for (int i = 1; i <= DIM; i++) {
                for (int d = 0; d < DIM; d++) {
                    simplex[i][d] = simplex[0][d] + sigmaNm * (simplex[i][d] - simplex[0][d]);
                }
                values[i] = computeNll(simplex[i], returns, mean, sampleVar);
            }
        }

        sortSimplex(simplex, values);
        return new NelderMeadResult(simplex[0], values[0], converged);
    }

    private void sortSimplex(double[][] simplex, double[] values) {
        for (int i = 0; i < values.length - 1; i++) {
            for (int j = i + 1; j < values.length; j++) {
                if (values[j] < values[i]) {
                    double tmpV = values[i];
                    values[i] = values[j];
                    values[j] = tmpV;

                    double[] tmpP = simplex[i];
                    simplex[i] = simplex[j];
                    simplex[j] = tmpP;
                }
            }
        }
    }
}
