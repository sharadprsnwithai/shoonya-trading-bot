package com.tradingbot.strategy.monthlyrange.model;

/**
 * Parameters fitted for GARCH(1,1) conditional volatility process:
 * sigma_t^2 = omega + alpha * epsilon_{t-1}^2 + beta * sigma_{t-1}^2
 */
public record GarchModelParams(
        double omega,
        double alpha,
        double beta,
        double longRunVariance,
        double logLikelihood,
        boolean converged,
        String statusMessage) {

    public double persistence() {
        return alpha + beta;
    }
}
