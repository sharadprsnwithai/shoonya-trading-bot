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
        List<OptionStrike> strikes) {}
