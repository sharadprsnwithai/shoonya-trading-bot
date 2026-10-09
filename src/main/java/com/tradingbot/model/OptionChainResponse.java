package com.tradingbot.model;

import java.math.BigDecimal;
import java.util.List;

/** REST response payload for Option Chain data. */
public record OptionChainResponse(
        String underlying,
        BigDecimal underlyingPrice,
        BigDecimal atmStrike,
        String tradingSymbol,
        int strikeCount,
        long totalCallOi,
        long totalPutOi,
        double pcr,
        List<OptionStrike> strikes) {

    /** Returns the strike price with maximum Call Open Interest (Resistance level). */
    public BigDecimal maxCallOiStrike() {
        if (strikes == null || strikes.isEmpty()) return null;
        BigDecimal maxStrike = null;
        long maxOi = -1;
        for (OptionStrike s : strikes) {
            if (s.call() != null && s.call().openInterest() > maxOi) {
                maxOi = s.call().openInterest();
                maxStrike = s.strikePrice();
            }
        }
        return maxStrike;
    }

    /** Returns the strike price with maximum Put Open Interest (Support level). */
    public BigDecimal maxPutOiStrike() {
        if (strikes == null || strikes.isEmpty()) return null;
        BigDecimal maxStrike = null;
        long maxOi = -1;
        for (OptionStrike s : strikes) {
            if (s.put() != null && s.put().openInterest() > maxOi) {
                maxOi = s.put().openInterest();
                maxStrike = s.strikePrice();
            }
        }
        return maxStrike;
    }
}
