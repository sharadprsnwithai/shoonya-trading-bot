package com.tradingbot.strategy.monthlyrange.model;

import java.math.BigDecimal;

/**
 * A k-standard-deviation volatility band over a multi-session horizon.
 *
 * <p>Carries both the probability the band is <i>nominally</i> assumed to represent under a
 * log-normal assumption, and the coverage actually <i>measured</i> on historical out-of-sample
 * windows. The two are deliberately reported side by side: the Gaussian figure describes the
 * terminal return only, whereas a structure held to expiry is exposed to the entire path, so the
 * measured coverage is expected to sit lower.
 *
 * @param label human readable band label (e.g. "1.0 sigma")
 * @param kSigma band width in standard deviations of the horizon sigma
 * @param lower lower band price
 * @param upper upper band price
 * @param nominalTerminalConfidencePct Gaussian probability that the terminal return stays inside
 *     the band, i.e. {@code 2 * Phi(k) - 1}
 * @param measuredCoveragePct share of out-of-sample windows whose full high/low excursion stayed
 *     inside the band; {@code -1} when calibration could not be run
 * @param measuredWindows number of out-of-sample windows used; {@code 0} when unavailable
 */
public record GarchRangeBand(
        String label,
        double kSigma,
        BigDecimal lower,
        BigDecimal upper,
        double nominalTerminalConfidencePct,
        double measuredCoveragePct,
        int measuredWindows) {}
