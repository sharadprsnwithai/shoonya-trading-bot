package com.tradingbot.model;

import java.math.BigDecimal;
import java.time.Instant;

/** REST response payload for Put-Call Ratio (PCR) analysis. */
public record PcrResponse(
        String underlying,
        BigDecimal underlyingPrice,
        BigDecimal atmStrike,
        int strikeCount,
        long totalCallOi,
        long totalPutOi,
        double pcrOi,
        long totalCallVolume,
        long totalPutVolume,
        double pcrVolume,
        String sentiment,
        Instant timestamp) {
    public static PcrResponse calculate(
            String underlying,
            BigDecimal underlyingPrice,
            BigDecimal atmStrike,
            int strikeCount,
            long totalCallOi,
            long totalPutOi,
            long totalCallVolume,
            long totalPutVolume) {
        double pcrOi = totalCallOi > 0 ? (double) totalPutOi / totalCallOi : 0.0;
        double pcrVol = totalCallVolume > 0 ? (double) totalPutVolume / totalCallVolume : 0.0;
        double roundedPcrOi = Math.round(pcrOi * 100.0) / 100.0;
        double roundedPcrVol = Math.round(pcrVol * 100.0) / 100.0;

        String sentiment;
        if (pcrOi >= 1.2) {
            sentiment = "BULLISH";
        } else if (pcrOi <= 0.8) {
            sentiment = "BEARISH";
        } else {
            sentiment = "NEUTRAL";
        }

        return new PcrResponse(
                underlying,
                underlyingPrice,
                atmStrike,
                strikeCount,
                totalCallOi,
                totalPutOi,
                roundedPcrOi,
                totalCallVolume,
                totalPutVolume,
                roundedPcrVol,
                sentiment,
                Instant.now());
    }
}
