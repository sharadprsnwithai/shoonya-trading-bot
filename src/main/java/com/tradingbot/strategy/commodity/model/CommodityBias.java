package com.tradingbot.strategy.commodity.model;

/** Directional market bias for MCX commodities derived from Option Chain Put-Call Ratio (PCR). */
public enum CommodityBias {
    BULLISH,
    BEARISH,
    NEUTRAL;

    /**
     * Determines the directional bias given the current PCR and configured threshold limits.
     *
     * @param pcr the Put-Call Ratio calculated from Open Interest
     * @param bullishMin the minimum PCR threshold to classify as BULLISH (e.g. 1.15)
     * @param bearishMax the maximum PCR threshold to classify as BEARISH (e.g. 0.85)
     * @return {@link CommodityBias} classification
     */
    public static CommodityBias fromPcr(Double pcr, double bullishMin, double bearishMax) {
        if (pcr == null || pcr <= 0.0) {
            return NEUTRAL;
        }
        if (pcr >= bullishMin) {
            return BULLISH;
        }
        if (pcr <= bearishMax) {
            return BEARISH;
        }
        return NEUTRAL;
    }
}
