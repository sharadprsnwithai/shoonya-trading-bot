package com.tradingbot.util;

/**
 * Utility for Black-Scholes option pricing and Greek (Delta) calculations.
 */
public final class BlackScholesUtil {

    private BlackScholesUtil() {}

    /**
     * Calculates Option Delta for Call or Put.
     */
    public static double calculateDelta(
            double spot,
            double strike,
            double timeToExpiryYears,
            double iv,
            double riskFreeRate,
            boolean isCall) {
        if (timeToExpiryYears <= 1e-6 || iv <= 1e-6) {
            if (isCall) {
                return spot >= strike ? 1.0 : 0.0;
            } else {
                return spot <= strike ? -1.0 : 0.0;
            }
        }

        double d1 =
                (Math.log(spot / strike) + (riskFreeRate + 0.5 * iv * iv) * timeToExpiryYears)
                        / (iv * Math.sqrt(timeToExpiryYears));

        if (isCall) {
            return cumulativeNormalDistribution(d1);
        } else {
            return cumulativeNormalDistribution(d1) - 1.0;
        }
    }

    /**
     * Calculates Black-Scholes theoretical option price.
     */
    public static double calculateOptionPrice(
            double spot,
            double strike,
            double timeToExpiryYears,
            double iv,
            double riskFreeRate,
            boolean isCall) {
        if (timeToExpiryYears <= 1e-6) {
            return isCall ? Math.max(0.0, spot - strike) : Math.max(0.0, strike - spot);
        }

        double sqrtT = Math.sqrt(timeToExpiryYears);
        double d1 =
                (Math.log(spot / strike) + (riskFreeRate + 0.5 * iv * iv) * timeToExpiryYears)
                        / (iv * sqrtT);
        double d2 = d1 - iv * sqrtT;

        if (isCall) {
            return spot * cumulativeNormalDistribution(d1)
                    - strike * Math.exp(-riskFreeRate * timeToExpiryYears) * cumulativeNormalDistribution(d2);
        } else {
            return strike * Math.exp(-riskFreeRate * timeToExpiryYears) * cumulativeNormalDistribution(-d2)
                    - spot * cumulativeNormalDistribution(-d1);
        }
    }

    /**
     * Cumulative standard normal distribution function (using error function erf).
     */
    public static double cumulativeNormalDistribution(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    /**
     * High-precision numerical approximation of the Error function erf(z).
     * Maximum error < 1.5 * 10^-7 (Abramowitz and Stegun 7.1.26).
     */
    public static double erf(double z) {
        double t = 1.0 / (1.0 + 0.3275911 * Math.abs(z));
        double poly =
                t * (0.254829592
                        + t * (-0.284496736
                                + t * (1.421413741
                                        + t * (-1.453152027
                                                + t * 1.061405429))));
        double result = 1.0 - poly * Math.exp(-z * z);
        return z >= 0 ? result : -result;
    }
}
