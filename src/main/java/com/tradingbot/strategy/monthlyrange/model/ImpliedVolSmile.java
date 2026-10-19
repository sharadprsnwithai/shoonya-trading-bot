package com.tradingbot.strategy.monthlyrange.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Implied volatility smile recovered from observed option premiums.
 *
 * <p>This is the market's own forward-looking range estimate, extracted by inverting Black-Scholes
 * on the premiums the chain already carries. It is the complement the volatility models cannot
 * supply: a fitted GARCH or GJR model returns one sigma for every strike, whereas this preserves
 * the strike-by-strike structure — including NIFTY's persistent put skew, which is exactly where a
 * winged structure's legs are priced.
 *
 * <p>It is <b>not</b> automatically trustworthy. Implied volatility is only as good as the premiums
 * it was inverted from, and far-wing strikes carry thin, stale, tick-rounded quotes. Every point
 * therefore records whether it survived admissibility, and {@link #status()} reports how much of
 * the chain was usable. A smile built from two strikes is reported as degraded rather than passed
 * off as a reading.
 *
 * @param symbol canonical symbol the chain belongs to
 * @param spot underlying price the smile is struck against
 * @param asOf when the premiums were observed
 * @param expirySession contractual expiry the smile is priced to
 * @param yearsToExpiry time from observation to expiry, annualised
 * @param atmIvPct implied volatility at the money, annualised percent; {@code Double.NaN} when
 *     insufficient strikes survived
 * @param atmHorizonSigmaPct {@link #atmIvPct} converted to cumulative volatility over the forecast
 *     horizon, so it is directly comparable with a GARCH or GJR horizon sigma
 * @param putSkewPts 25-delta put IV minus 25-delta call IV, in volatility points. Positive is the
 *     normal equity skew — downside strikes priced richer than upside. {@code Double.NaN} when
 *     either wing is missing
 * @param usablePoints strikes that passed every admissibility rule
 * @param totalPoints strikes attempted
 * @param points every attempted point, usable and rejected, with the reason for each rejection
 * @param status {@code "OK"} for a healthy smile, otherwise a human-readable quality warning
 * @param note the horizon, rate and day-count assumptions behind the inversion
 */
public record ImpliedVolSmile(
        String symbol,
        BigDecimal spot,
        Instant asOf,
        LocalDate expirySession,
        double yearsToExpiry,
        double atmIvPct,
        double atmHorizonSigmaPct,
        double putSkewPts,
        int usablePoints,
        int totalPoints,
        List<SmilePoint> points,
        String status,
        String note) {

    public ImpliedVolSmile {
        points = points == null ? null : new ArrayList<>(points);
    }

    @Override
    public List<SmilePoint> points() {
        return points == null ? null : new ArrayList<>(points);
    }

    /**
     * True when the smile is fit for the primary headline figures.
     *
     * @return {@code true} when enough strikes survived to quote an ATM volatility
     */
    public boolean isUsable() {
        return !Double.isNaN(atmIvPct) && usablePoints >= 3;
    }
}
