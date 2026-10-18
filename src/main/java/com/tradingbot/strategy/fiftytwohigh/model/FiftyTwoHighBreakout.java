package com.tradingbot.strategy.fiftytwohigh.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A single NSE F&amp;O equity whose latest completed daily candle crossed above its prior 52-week
 * high.
 *
 * @param symbol NSE trading symbol (e.g. "BEL")
 * @param sector Sector label resolved from the Nifty sector registry (may be null)
 * @param sessionDate Trading session of the candle that crossed
 * @param latestClose Close of the crossing candle
 * @param latestHigh High of the crossing candle (the level that broke out)
 * @param latestLow Low of the crossing candle
 * @param latestOpen Open of the crossing candle
 * @param latestVolume Traded volume of the crossing candle
 * @param prior52wHigh Highest high of the preceding 52-week window (the level that was crossed)
 * @param prior52wHighDate Session on which the prior 52-week high was set
 * @param breakoutPct Percentage the crossing high exceeded the prior 52-week high by
 * @param closeVsPriorPct Percentage the close finished relative to the prior 52-week high
 * @param closeConfirmed Whether the close also finished above the prior 52-week high
 * @param sessionsSincePriorHigh Trading sessions between the prior-high candle and the cross
 * @param priorSessionsEvaluated Count of prior sessions that backed the 52-week-high window
 * @param lotSize Standard F&amp;O lot size for the symbol
 */
public record FiftyTwoHighBreakout(
        String symbol,
        String sector,
        LocalDate sessionDate,
        BigDecimal latestClose,
        BigDecimal latestHigh,
        BigDecimal latestLow,
        BigDecimal latestOpen,
        long latestVolume,
        BigDecimal prior52wHigh,
        LocalDate prior52wHighDate,
        BigDecimal breakoutPct,
        BigDecimal closeVsPriorPct,
        boolean closeConfirmed,
        int sessionsSincePriorHigh,
        int priorSessionsEvaluated,
        int lotSize) {}
