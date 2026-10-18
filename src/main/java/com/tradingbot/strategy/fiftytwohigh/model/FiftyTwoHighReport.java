package com.tradingbot.strategy.fiftytwohigh.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Aggregated 52-week-high breakout scan report across the scanned universe.
 *
 * @param generatedAt Instant the scan completed
 * @param evaluatedSession The trading session whose candle was evaluated (usually the prior day)
 * @param niftyHa Heikin-Ashi posture of the NIFTY 50 index on the last completed session (nullable)
 * @param universeLabel Human label for the scanned universe (e.g. "NSE F&amp;O equities")
 * @param universeSize Total symbols considered
 * @param evaluatedCount Symbols with enough history to evaluate (breakouts + non-breakouts)
 * @param insufficientHistoryCount Symbols skipped for having too few prior sessions
 * @param dataUnavailableCount Symbols with no usable daily data (fetch/parse failures)
 * @param breakouts Qualifying breakouts, strongest first (by breakout percentage)
 * @param tradeReadySetups Close-confirmed breakouts that cleared all five filters, carrying the
 *     recommended spread strikes (empty when the trade-setup filter is disabled or nothing
 *     qualified)
 * @param summary One-line human summary
 */
public record FiftyTwoHighReport(
        Instant generatedAt,
        LocalDate evaluatedSession,
        NiftyHaStatus niftyHa,
        String universeLabel,
        int universeSize,
        int evaluatedCount,
        int insufficientHistoryCount,
        int dataUnavailableCount,
        List<FiftyTwoHighBreakout> breakouts,
        List<BreakoutTradeSetup> tradeReadySetups,
        String summary) {}
