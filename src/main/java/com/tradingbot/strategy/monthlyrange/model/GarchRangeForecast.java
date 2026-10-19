package com.tradingbot.strategy.monthlyrange.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-horizon volatility range forecast for a single symbol over a short-dated hold window.
 *
 * <p>Unlike {@link MonthlyRangeForecast}, which projects a fixed 22-session horizon and fuses the
 * option chain, this is a pure volatility projection scoped to an explicit session window (e.g.
 * Wednesday open through the following Tuesday expiry) and calibrated against realised history.
 *
 * <p>A symmetric GARCH(1,1) and a GJR-GARCH carrying a leverage term are both fitted, calibrated
 * and reported. The GARCH bands remain the primary series for continuity; {@link
 * #modelComparison()} carries the head-to-head verdict for callers that want to switch.
 *
 * @param symbol canonical symbol
 * @param spot reference spot, normally the close of the last completed session
 * @param horizonSessions trading sessions the forecast covers
 * @param horizonSigmaPct cumulative conditional volatility over the horizon, in percent (GARCH)
 * @param annualizedVolPct horizon sigma annualised via sqrt(252 / horizon)
 * @param terminalDailyVolPct one-session conditional volatility at the forecast origin
 * @param persistence fitted alpha + beta; values near 1 mean shocks decay slowly
 * @param referenceSession last completed session used as the spot reference
 * @param expirySession contractual expiry the horizon runs through
 * @param bands GARCH k-sigma bands with nominal and measured coverage
 * @param gjrHorizonSigmaPct the same horizon sigma produced by the GJR-GARCH model
 * @param gjrBands GJR k-sigma bands, calibrated independently of the GARCH bands
 * @param wingZone spot-containment view for the configured wing structure, on GARCH volatility
 * @param modelComparison head-to-head GARCH versus GJR verdict, or null when comparison is off
 * @param calibrationWindows out-of-sample windows used for the measurement
 * @param trainSplitIndex return index at which the out-of-sample segment begins
 * @param garchStatus fitter status message, including any fallback reason
 * @param generatedAt when the forecast was produced
 */
public record GarchRangeForecast(
        String symbol,
        BigDecimal spot,
        int horizonSessions,
        BigDecimal horizonSigmaPct,
        BigDecimal annualizedVolPct,
        BigDecimal terminalDailyVolPct,
        double persistence,
        LocalDate referenceSession,
        LocalDate expirySession,
        List<GarchRangeBand> bands,
        BigDecimal gjrHorizonSigmaPct,
        List<GarchRangeBand> gjrBands,
        WingZoneForecast wingZone,
        ModelComparison modelComparison,
        int calibrationWindows,
        int trainSplitIndex,
        String garchStatus,
        Instant generatedAt) {

    public GarchRangeForecast {
        bands = bands == null ? null : new ArrayList<>(bands);
        gjrBands = gjrBands == null ? null : new ArrayList<>(gjrBands);
    }

    @Override
    public List<GarchRangeBand> bands() {
        return bands == null ? null : new ArrayList<>(bands);
    }

    @Override
    public List<GarchRangeBand> gjrBands() {
        return gjrBands == null ? null : new ArrayList<>(gjrBands);
    }
}
