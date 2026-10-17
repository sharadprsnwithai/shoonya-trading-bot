package com.tradingbot.strategy.monthlyrange.model;

import java.math.BigDecimal;

/**
 * One strike's contribution to an implied volatility smile.
 *
 * <p>Both sides of a strike are priced independently even though put-call parity says they must
 * imply the same volatility. Retaining both, rather than averaging them up front, keeps the
 * disagreement visible: a strike whose call and put imply materially different volatilities is a
 * stale or one-sided quote, and hiding that inside an average would make a broken chain look
 * smooth.
 *
 * @param strike strike price
 * @param optionType {@code CE} or {@code PE}
 * @param premium observed last traded price used for the inversion
 * @param impliedVolPct solved implied volatility in percent, annualised; {@code Double.NaN} when
 *     the strike was rejected
 * @param usable whether this point passed every admissibility rule and was fitted
 * @param delta Black-Scholes delta at the solved volatility, {@code Double.NaN} when unusable
 * @param vega Black-Scholes vega at the solved volatility, {@code Double.NaN} when unusable; the
 *     natural fitting weight
 * @param note rejection or fallback reason when {@link #usable} is false, otherwise {@code null}
 */
public record SmilePoint(
        BigDecimal strike,
        String optionType,
        double premium,
        double impliedVolPct,
        boolean usable,
        double delta,
        double vega,
        String note) {}
