package com.tradingbot.strategy.monthlyrange.model;

/**
 * Head-to-head assessment of a symmetric GARCH(1,1) against a GJR-GARCH on the same return series.
 *
 * <p>Three independent lenses are reported rather than a single opaque score, because they answer
 * different questions and can legitimately disagree:
 *
 * <ul>
 *   <li><b>Out-of-sample log-likelihood</b> — the primary metric. Both models are fitted on the
 *       training segment only, then scored on the log-likelihood of returns neither model saw. The
 *       extra leverage parameter has to earn its keep here or it is overfitting.
 *   <li><b>AIC / BIC</b> — in-sample, penalised for parameter count, so GARCH's three parameters
 *       face a lower bar than GJR's four. Reported as a cross-check on the primary metric.
 *   <li><b>Coverage calibration error</b> — how far each model's measured band coverage sits from
 *       the probability it nominally claims, averaged across the configured k-sigma bands. This is
 *       the decision-relevant lens: it says which model produces bands you can actually size
 *       against.
 * </ul>
 *
 * @param preferredModel {@code GJR_GARCH}, {@code GARCH_1_1} or {@code MIXED} when the lenses
 *     disagree
 * @param garchOutOfSampleLogLik predictive log-likelihood of the held-out segment under GARCH
 * @param gjrOutOfSampleLogLik predictive log-likelihood of the held-out segment under GJR
 * @param garchAic Akaike information criterion for GARCH
 * @param gjrAic Akaike information criterion for GJR
 * @param garchBic Bayesian information criterion for GARCH
 * @param gjrBic Bayesian information criterion for GJR
 * @param gamma fitted leverage coefficient; zero means GJR reduced to a symmetric model
 * @param leverageRatio (alpha + gamma) / alpha — above one means down days dominate
 * @param garchCoverageErrorPct mean absolute gap between nominal and measured coverage, in points
 * @param gjrCoverageErrorPct same gap for the GJR bands
 * @param garchWingStayPct measured share of windows whose excursion stayed inside the wing zone,
 *     using GARCH volatility
 * @param gjrWingStayPct the same containment rate using GJR volatility
 * @param outOfSampleReturns number of held-out returns scored
 * @param gjrConverged whether the GJR optimizer reported convergence
 * @param rationale plain-language explanation of how the verdict was reached
 */
public record ModelComparison(
        String preferredModel,
        double garchOutOfSampleLogLik,
        double gjrOutOfSampleLogLik,
        double garchAic,
        double gjrAic,
        double garchBic,
        double gjrBic,
        double gamma,
        double leverageRatio,
        double garchCoverageErrorPct,
        double gjrCoverageErrorPct,
        double garchWingStayPct,
        double gjrWingStayPct,
        int outOfSampleReturns,
        boolean gjrConverged,
        String rationale) {}
