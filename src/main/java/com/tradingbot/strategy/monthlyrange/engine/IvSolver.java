package com.tradingbot.strategy.monthlyrange.engine;

/**
 * Inverts an observed option premium into an implied volatility.
 *
 * <p>Newton-Raphson on premium error divided by vega is the workhorse: it converges in three or
 * four steps from a sensible seed. It is not safe on its own, because vega collapses toward zero
 * for deep ITM and deep OTM strikes — the derivative vanishes exactly where the answer is least
 * identified, and the step size blows up. Every inversion therefore also carries a bisection
 * fallback over a fixed, wide bracket, which cannot diverge and settles the strikes Newton
 * abandons.
 *
 * <p>Rejection is deliberate and explicit. A premium that sits at or below intrinsic value implies
 * zero or negative time value, so no volatility can reproduce it; a premium whose time value is a
 * rounding sliver implies a volatility dominated by tick noise. Both are returned as {@code
 * Optional.empty()} rather than a number, because a fabricated IV feeding a band is worse than no
 * IV at all.
 *
 * <p>Package-private: the smile fitter is its only intended caller.
 */
final class IvSolver {

    /** Newton-Raphson iteration cap; convergence is normally three or four steps. */
    private static final int MAX_NEWTON_STEPS = 64;

    /** Bisection iteration cap; halving a [1e-6, 5] bracket to 1e-9 needs ~40. */
    private static final int MAX_BISECTION_STEPS = 64;

    /** Convergence tolerance on premium, as a fraction of spot. */
    private static final double PREMIUM_TOLERANCE = 1e-8;

    /** Absolute floor on solved volatility; below this the quote is tick noise. */
    private static final double MIN_SIGMA = 1e-4;

    /** Ceiling on solved volatility; above this the quote is stale or broken. */
    private static final double MAX_SIGMA = 5.0;

    /**
     * Minimum time value, as a fraction of spot, for a strike to be considered informative.
     *
     * <p>Below roughly half a basis point of spot the premium is within a tick or two of intrinsic,
     * and the implied volatility derived from it is almost entirely tick rounding. NIFTY at 22,600
     * has a 0.05 tick, so this rejects premiums under about 1.13 points.
     */
    private static final double MIN_TIME_VALUE_FRACTION = 5e-5;

    private IvSolver() {}

    /**
     * Result of one inversion: a volatility, or the reason none exists.
     *
     * @param iv implied volatility as an annual decimal when present
     * @param steps iterations taken, reported so callers can see how hard the strike fought back
     * @param reason human-readable rejection or fallback reason; empty on a clean Newton solve
     */
    record Solved(double iv, int steps, String reason) {}

    /**
     * Inverts a premium into an implied volatility.
     *
     * @param premium observed market premium for the contract
     * @param spot underlying price
     * @param strike strike price
     * @param years time to expiry in years
     * @param rate continuously compounded risk-free rate, annual decimal
     * @param isCall whether the premium belongs to a call
     * @return the solved volatility, or a rejection carrying the reason it could not be solved
     */
    Solved solve(
            double premium, double spot, double strike, double years, double rate, boolean isCall) {
        if (Double.isNaN(premium) || Double.isInfinite(premium) || premium <= 0.0) {
            return rejected("premium not positive: " + premium);
        }
        if (Double.isNaN(years) || years <= 0.0) {
            return rejected("no time remaining to expiry");
        }
        if (Double.isNaN(spot) || spot <= 0.0 || Double.isNaN(strike) || strike <= 0.0) {
            return rejected("non-positive spot or strike");
        }

        double intrinsic = isCall ? Math.max(spot - strike, 0.0) : Math.max(strike - spot, 0.0);
        double timeValue = premium - intrinsic;
        if (timeValue <= 0.0) {
            return rejected(
                    String.format(
                            "premium at or below intrinsic (%.2f <= %.2f)", premium, intrinsic));
        }
        if (timeValue < MIN_TIME_VALUE_FRACTION * spot) {
            return rejected(
                    String.format(
                            "time value %.4f below the %.4f floor — tick noise, not information",
                            timeValue, MIN_TIME_VALUE_FRACTION * spot));
        }

        Solved newton = newton(premium, spot, strike, years, rate, isCall);
        if (newton != null) {
            return newton;
        }
        Solved bisection = bisection(premium, spot, strike, years, rate, isCall);
        return bisection != null ? bisection : rejected("no volatility reproduces this premium");
    }

    /** Newton-Raphson from a vega-free seed; returns null when it cannot be trusted to finish. */
    private Solved newton(
            double premium, double spot, double strike, double years, double rate, boolean isCall) {
        double sigma = seed(premium, spot, strike, years, isCall);
        for (int step = 1; step <= MAX_NEWTON_STEPS; step++) {
            double model;
            double vega;
            try {
                model = BlackScholes.price(spot, strike, years, rate, sigma, isCall);
                vega = BlackScholes.vega(spot, strike, years, rate, sigma);
            } catch (IllegalArgumentException e) {
                return null;
            }

            double error = model - premium;
            if (Math.abs(error) <= PREMIUM_TOLERANCE * spot) {
                return new Solved(sigma, step, null);
            }
            // Vanishing vega means the next step is unbounded; hand over to bisection.
            if (!Double.isFinite(vega) || vega < 1e-12) {
                return null;
            }

            double next = sigma - error / vega;
            if (!Double.isFinite(next) || next < MIN_SIGMA || next > MAX_SIGMA) {
                return null;
            }
            sigma = next;
        }
        return null;
    }

    /** Bisection over [MIN_SIGMA, MAX_SIGMA]; monotone in sigma, so it cannot cycle. */
    private Solved bisection(
            double premium, double spot, double strike, double years, double rate, boolean isCall) {
        double low = MIN_SIGMA;
        double high = MAX_SIGMA;
        double lowValue;
        double highValue;
        try {
            lowValue = BlackScholes.price(spot, strike, years, rate, low, isCall) - premium;
            highValue = BlackScholes.price(spot, strike, years, rate, high, isCall) - premium;
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (lowValue > 0.0 || highValue < 0.0) {
            // The bracket does not straddle the quote: no admissible volatility explains it.
            return null;
        }

        for (int step = 1; step <= MAX_BISECTION_STEPS; step++) {
            double mid = 0.5 * (low + high);
            double midValue;
            try {
                midValue = BlackScholes.price(spot, strike, years, rate, mid, isCall) - premium;
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (Math.abs(midValue) <= PREMIUM_TOLERANCE * spot) {
                return new Solved(mid, step, "bisection fallback");
            }
            if (midValue < 0.0) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return null;
    }

    /**
     * Brenner-Subrahmanyam seed for ATM, widened for strikes away from the money.
     *
     * <p>{@code sqrt(2*pi/T) * premium / spot} is the small-T limit of the ATM implied volatility
     * and lands within a few percent for anything near the money. Far strikes get a floor so the
     * seed never enters a region where vega is already negligible.
     */
    private double seed(double premium, double spot, double strike, double years, boolean isCall) {
        double approx = Math.sqrt(2.0 * Math.PI / years) * (premium / spot);
        if (!Double.isFinite(approx) || approx <= 0.0) {
            return 0.20;
        }
        double logMoneyness = Math.log(spot / strike);
        double widened = approx * Math.exp(0.5 * logMoneyness * logMoneyness);
        return Math.min(Math.max(widened, 0.01), MAX_SIGMA * 0.9);
    }

    private static Solved rejected(String reason) {
        return new Solved(Double.NaN, 0, reason);
    }
}
