package com.tradingbot.strategy.monthlyrange.model;

/**
 * Parameters fitted for a GJR-GARCH (Glosten-Jagannathan-Runkle) conditional volatility process:
 *
 * <pre>
 *   sigma_t^2 = omega + (alpha + gamma * I_{t-1}) * epsilon_{t-1}^2 + beta * sigma_{t-1}^2
 * </pre>
 *
 * <p>where {@code I_{t-1}} is one when the previous shock was negative. The {@code gamma} leverage
 * term is what distinguishes this from a symmetric GARCH(1,1): it lets negative shocks raise
 * volatility more than positive shocks of equal magnitude, which is the behaviour equity indices
 * typically exhibit and which a symmetric model is structurally unable to express.
 *
 * <p>Under the usual zero-mean Gaussian assumption the unconditional probability of a negative
 * shock is one half, so the unconditional persistence is {@code alpha + beta + gamma / 2} and the
 * long-run variance is {@code omega / (1 - alpha - beta - gamma / 2)}.
 *
 * @param omega intercept, strictly positive
 * @param alpha symmetric response to a positive shock
 * @param beta GARCH decay term
 * @param gamma additional response triggered only by a negative shock; zero reduces this exactly to
 *     a symmetric GARCH(1,1)
 * @param longRunVariance unconditional variance the process mean-reverts to
 * @param logLikelihood maximised in-sample log-likelihood
 * @param converged whether the optimizer reported convergence
 * @param statusMessage optimizer status, including any fallback reason
 */
public record GjrGarchModelParams(
        double omega,
        double alpha,
        double beta,
        double gamma,
        double longRunVariance,
        double logLikelihood,
        boolean converged,
        String statusMessage) {

    /**
     * Unconditional persistence assuming negative shocks occur with probability one half.
     *
     * @return alpha + beta + gamma/2
     */
    public double persistence() {
        return alpha + beta + gamma / 2.0;
    }

    /**
     * Variance response to a negative shock, i.e. how much a down day actually moves the forecast.
     *
     * @return alpha + gamma
     */
    public double downsideResponse() {
        return alpha + gamma;
    }

    /**
     * Ratio of the downside response to the upside response. Above one means down days are more
     * destabilising than up days — the leverage effect this model exists to capture.
     *
     * @return (alpha + gamma) / alpha, or {@code NaN} when alpha is zero
     */
    public double leverageRatio() {
        return alpha > 0.0 ? (alpha + gamma) / alpha : Double.NaN;
    }
}
