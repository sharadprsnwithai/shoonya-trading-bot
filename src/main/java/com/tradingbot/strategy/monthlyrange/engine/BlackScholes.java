package com.tradingbot.strategy.monthlyrange.engine;

/**
 * Black-Scholes pricing primitives, used to invert option premiums into implied volatility.
 *
 * <p>This is deliberately narrow: price, vega and the standard normal distribution. It exists to
 * support {@link IvSolver}, not to be a general pricing library, and it carries no opinion about
 * rates, dividends or day-count conventions beyond what the caller passes in.
 *
 * <p>All volatility arguments are annualised decimals (0.15 = 15%) and all times are annualised
 * year fractions (0.0137 = five trading sessions).
 */
public final class BlackScholes {

    /** Two-argument normal density at the origin; the normal CDF's scale factor. */
    private static final double INV_SQRT_2PI = 1.0 / Math.sqrt(2.0 * Math.PI);

    private BlackScholes() {}

    /**
     * Prices a European call or put.
     *
     * @param spot underlying price, must be positive
     * @param strike strike price, must be positive
     * @param years time to expiry in years, must be positive
     * @param rate continuously compounded risk-free rate, as an annual decimal
     * @param sigma annualised volatility as a decimal, must be positive
     * @param isCall {@code true} for a call, {@code false} for a put
     * @return theoretical premium in the same units as spot and strike
     * @throws IllegalArgumentException when any argument is non-positive
     */
    public static double price(
            double spot, double strike, double years, double rate, double sigma, boolean isCall) {
        requirePositive(spot, "spot");
        requirePositive(strike, "strike");
        requirePositive(years, "years");
        requirePositive(sigma, "sigma");

        double sqrtT = Math.sqrt(years);
        double d1 =
                (Math.log(spot / strike) + (rate + 0.5 * sigma * sigma) * years) / (sigma * sqrtT);
        double d2 = d1 - sigma * sqrtT;
        double discountedStrike = strike * Math.exp(-rate * years);

        return isCall
                ? spot * normalCdf(d1) - discountedStrike * normalCdf(d2)
                : discountedStrike * normalCdf(-d2) - spot * normalCdf(-d1);
    }

    /**
     * Vega of a European call or put — identical for both, by put-call parity.
     *
     * <p>This is the slope of premium with respect to volatility, and the denominator of the
     * Newton-Raphson step in {@link IvSolver}. It collapses toward zero for deep ITM and deep OTM
     * strikes, which is precisely where inverting a premium into a volatility stops being
     * numerically meaningful — the solver rejects those strikes on that basis.
     *
     * @param spot underlying price, must be positive
     * @param strike strike price, must be positive
     * @param years time to expiry in years, must be positive
     * @param rate continuously compounded risk-free rate, as an annual decimal
     * @param sigma annualised volatility as a decimal, must be positive
     * @return vega per 1.00 of volatility, so divide by 100 for the per-point figure
     * @throws IllegalArgumentException when any argument is non-positive
     */
    public static double vega(double spot, double strike, double years, double rate, double sigma) {
        requirePositive(spot, "spot");
        requirePositive(strike, "strike");
        requirePositive(years, "years");
        requirePositive(sigma, "sigma");

        double sqrtT = Math.sqrt(years);
        double d1 =
                (Math.log(spot / strike) + (rate + 0.5 * sigma * sigma) * years) / (sigma * sqrtT);
        return spot * normalPdf(d1) * sqrtT;
    }

    /**
     * Delta of a European call or put.
     *
     * <p>Used to locate the 25-delta wing on each side of the smile, which is where skew is
     * conventionally quoted. Computed at the solved implied volatility, so a strike's reported
     * delta and its reported IV are always mutually consistent.
     *
     * @param spot underlying price, must be positive
     * @param strike strike price, must be positive
     * @param years time to expiry in years, must be positive
     * @param rate continuously compounded risk-free rate, as an annual decimal
     * @param sigma annualised volatility as a decimal, must be positive
     * @param isCall {@code true} for a call, {@code false} for a put
     * @return delta in [-1, 1]: approximately [0, 1] for a call, [-1, 0] for a put
     * @throws IllegalArgumentException when any argument is non-positive
     */
    public static double delta(
            double spot, double strike, double years, double rate, double sigma, boolean isCall) {
        requirePositive(spot, "spot");
        requirePositive(strike, "strike");
        requirePositive(years, "years");
        requirePositive(sigma, "sigma");

        double sqrtT = Math.sqrt(years);
        double d1 =
                (Math.log(spot / strike) + (rate + 0.5 * sigma * sigma) * years) / (sigma * sqrtT);
        return isCall ? normalCdf(d1) : normalCdf(d1) - 1.0;
    }

    /**
     * Standard normal cumulative distribution function.
     *
     * <p>Computed as {@code 0.5 * erfc(-x / sqrt(2))} using the Numerical Recipes complementary
     * error function, whose fractional error stays below 1.2e-7 across the real line. That accuracy
     * matters here: an IV inversion compares premiums at the 1e-4 level, and a coarser CDF would
     * put a visible bias into the fitted smile.
     *
     * @param x evaluation point
     * @return P(Z &lt;= x) for a standard normal Z
     */
    public static double normalCdf(double x) {
        return 0.5 * GarchRangeEngine.erfcc(-x / Math.sqrt(2.0));
    }

    /**
     * Standard normal probability density function.
     *
     * @param x evaluation point
     * @return the density at {@code x}
     */
    public static double normalPdf(double x) {
        return INV_SQRT_2PI * Math.exp(-0.5 * x * x);
    }

    private static void requirePositive(double value, String name) {
        if (!(value > 0.0) || Double.isNaN(value)) {
            throw new IllegalArgumentException(name + " must be positive but was " + value);
        }
    }
}
