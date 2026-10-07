package com.tradingbot.strategy.monthlyrange.engine;

import com.tradingbot.strategy.monthlyrange.model.GarchModelParams;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pure Java Maximum Likelihood Estimation (MLE) optimizer for GARCH(1,1) volatility models.
 * Uses Nelder-Mead Simplex optimization with boundary penalty barriers to guarantee stationarity.
 */
@Component
public class Garch11Optimizer {

    private static final Logger log = LoggerFactory.getLogger(Garch11Optimizer.class);
    private static final double MIN_OMEGA = 1e-8;
    private static final double MAX_PERSISTENCE = 0.998;
    private static final double PENALTY_BASE = 1e8;

    private final int maxIterations;
    private final double tolerance;

    public Garch11Optimizer() {
        this(500, 1e-6);
    }

    public Garch11Optimizer(int maxIterations, double tolerance) {
        this.maxIterations = maxIterations;
        this.tolerance = tolerance;
    }

    /**
     * Fits a GARCH(1,1) model to an array of daily log returns:
     * sigma_t^2 = omega + alpha * (r_{t-1} - mu)^2 + beta * sigma_{t-1}^2
     */
    public GarchModelParams fit(double[] returns) {
        if (returns == null || returns.length < 5) {
            return createFallbackParams(returns != null ? calculateSampleVariance(returns) : 0.0001, "TOO_FEW_SAMPLES");
        }

        double mean = calculateMean(returns);
        double sampleVar = calculateSampleVariance(returns, mean);
        if (sampleVar <= 1e-8) {
            return createFallbackParams(1e-4, "ZERO_OR_TINY_VARIANCE");
        }

        // Initial parameter estimates: alpha = 0.08, beta = 0.88 => persistence = 0.96
        double initAlpha = 0.08;
        double initBeta = 0.88;
        double initOmega = sampleVar * (1.0 - initAlpha - initBeta);
        if (initOmega < MIN_OMEGA) {
            initOmega = MIN_OMEGA;
        }

        double[] initialPoint = new double[] {initOmega, initAlpha, initBeta};
        NelderMeadResult result = optimizeNelderMead(returns, mean, sampleVar, initialPoint);

        double omega = result.point[0];
        double alpha = result.point[1];
        double beta = result.point[2];
        double persistence = alpha + beta;

        // Post-optimization safety clamp
        if (omega < MIN_OMEGA || alpha < 0.0 || beta < 0.0 || persistence >= 1.0) {
            return createFallbackParams(sampleVar, "OPTIMIZATION_OUT_OF_BOUNDS");
        }

        double longRunVariance = omega / (1.0 - persistence);
        double logLikelihood = -result.val;

        return new GarchModelParams(
                omega,
                alpha,
                beta,
                longRunVariance,
                logLikelihood,
                result.converged,
                result.converged ? "CONVERGED" : "MAX_ITERATIONS_REACHED");
    }

    private GarchModelParams createFallbackParams(double sampleVar, String reason) {
        double safeVar = Math.max(sampleVar, 1e-6);
        double fallbackAlpha = 0.08;
        double fallbackBeta = 0.88;
        double fallbackOmega = safeVar * (1.0 - fallbackAlpha - fallbackBeta);
        return new GarchModelParams(
                fallbackOmega,
                fallbackAlpha,
                fallbackBeta,
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

    /**
     * Calculates Negative Log-Likelihood (NLL) for GARCH(1,1) with Gaussian errors.
     */
    double computeNll(double[] point, double[] returns, double mean, double sampleVar) {
        double omega = point[0];
        double alpha = point[1];
        double beta = point[2];

        // Soft penalty barriers
        if (omega < MIN_OMEGA) {
            return PENALTY_BASE + (MIN_OMEGA - omega) * 1e7;
        }
        if (alpha < 0.0) {
            return PENALTY_BASE + (-alpha) * 1e7;
        }
        if (beta < 0.0) {
            return PENALTY_BASE + (-beta) * 1e7;
        }
        if (alpha + beta >= MAX_PERSISTENCE) {
            return PENALTY_BASE + ((alpha + beta) - MAX_PERSISTENCE) * 1e7;
        }

        int t = returns.length;
        double currentSigma2 = sampleVar;
        double nll = 0.0;

        for (int i = 0; i < t; i++) {
            double eps = returns[i] - mean;
            if (i > 0) {
                double prevEps = returns[i - 1] - mean;
                currentSigma2 = omega + alpha * (prevEps * prevEps) + beta * currentSigma2;
            }

            if (currentSigma2 <= 1e-12 || Double.isNaN(currentSigma2) || Double.isInfinite(currentSigma2)) {
                return PENALTY_BASE;
            }

            // Normal Log-Likelihood term: 0.5 * (ln(2*pi) + ln(sigma^2) + eps^2 / sigma^2)
            nll += 0.5 * (Math.log(2.0 * Math.PI) + Math.log(currentSigma2) + (eps * eps) / currentSigma2);
        }

        return Double.isNaN(nll) ? PENALTY_BASE : nll;
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
        int dim = 3;
        double[][] simplex = new double[dim + 1][dim];
        double[] values = new double[dim + 1];

        // Vertex 0: start point
        simplex[0] = Arrays.copyOf(start, dim);
        values[0] = computeNll(simplex[0], returns, mean, sampleVar);

        // Perturb remaining vertices
        double[] steps = new double[] {start[0] * 0.2 + 1e-6, 0.03, 0.03};
        for (int i = 1; i <= dim; i++) {
            simplex[i] = Arrays.copyOf(start, dim);
            simplex[i][i - 1] += steps[i - 1];
            values[i] = computeNll(simplex[i], returns, mean, sampleVar);
        }

        double alphaNm = 1.0;
        double gammaNm = 2.0;
        double rhoNm = 0.5;
        double sigmaNm = 0.5;

        boolean converged = false;

        for (int iter = 0; iter < maxIterations; iter++) {
            // Sort simplex vertices by objective function value (ascending)
            sortSimplex(simplex, values);

            // Check convergence tolerance
            double range = Math.abs(values[dim] - values[0]);
            if (range < tolerance) {
                converged = true;
                break;
            }

            // Centroid of the best n vertices (excluding worst vertex dim)
            double[] centroid = new double[dim];
            for (int i = 0; i < dim; i++) {
                for (int d = 0; d < dim; d++) {
                    centroid[d] += simplex[i][d] / dim;
                }
            }

            // 1. Reflection
            double[] reflected = new double[dim];
            for (int d = 0; d < dim; d++) {
                reflected[d] = centroid[d] + alphaNm * (centroid[d] - simplex[dim][d]);
            }
            double reflectedVal = computeNll(reflected, returns, mean, sampleVar);

            if (values[0] <= reflectedVal && reflectedVal < values[dim - 1]) {
                simplex[dim] = reflected;
                values[dim] = reflectedVal;
                continue;
            }

            // 2. Expansion
            if (reflectedVal < values[0]) {
                double[] expanded = new double[dim];
                for (int d = 0; d < dim; d++) {
                    expanded[d] = centroid[d] + gammaNm * (reflected[d] - centroid[d]);
                }
                double expandedVal = computeNll(expanded, returns, mean, sampleVar);
                if (expandedVal < reflectedVal) {
                    simplex[dim] = expanded;
                    values[dim] = expandedVal;
                } else {
                    simplex[dim] = reflected;
                    values[dim] = reflectedVal;
                }
                continue;
            }

            // 3. Contraction
            if (reflectedVal < values[dim]) {
                // Outside contraction
                double[] contracted = new double[dim];
                for (int d = 0; d < dim; d++) {
                    contracted[d] = centroid[d] + rhoNm * (reflected[d] - centroid[d]);
                }
                double contractedVal = computeNll(contracted, returns, mean, sampleVar);
                if (contractedVal < reflectedVal) {
                    simplex[dim] = contracted;
                    values[dim] = contractedVal;
                    continue;
                }
            } else {
                // Inside contraction
                double[] contracted = new double[dim];
                for (int d = 0; d < dim; d++) {
                    contracted[d] = centroid[d] - rhoNm * (centroid[d] - simplex[dim][d]);
                }
                double contractedVal = computeNll(contracted, returns, mean, sampleVar);
                if (contractedVal < values[dim]) {
                    simplex[dim] = contracted;
                    values[dim] = contractedVal;
                    continue;
                }
            }

            // 4. Shrink towards best vertex
            for (int i = 1; i <= dim; i++) {
                for (int d = 0; d < dim; d++) {
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
