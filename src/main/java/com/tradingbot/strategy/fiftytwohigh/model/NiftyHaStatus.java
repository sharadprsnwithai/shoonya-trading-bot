package com.tradingbot.strategy.fiftytwohigh.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Heikin-Ashi posture of the NIFTY 50 index on the last completed session, carried alongside the
 * 52-week-high breakout report so the broader trend context is visible in one glance.
 *
 * @param sessionDate Trading session the Heikin-Ashi bar reflects (usually the prior day)
 * @param color GREEN (HA close above HA open), RED (below), or FLAT (equal)
 * @param haOpen Heikin-Ashi open of the last bar
 * @param haHigh Heikin-Ashi high of the last bar
 * @param haLow Heikin-Ashi low of the last bar
 * @param haClose Heikin-Ashi close of the last bar
 * @param streak Consecutive same-color Heikin-Ashi bars ending at the last bar (0 when flat)
 * @param sourceSessions Number of daily candles used to build the Heikin-Ashi series
 */
public record NiftyHaStatus(
        LocalDate sessionDate,
        Color color,
        BigDecimal haOpen,
        BigDecimal haHigh,
        BigDecimal haLow,
        BigDecimal haClose,
        int streak,
        int sourceSessions) {

    /** Direction of a Heikin-Ashi bar. */
    public enum Color {
        /** HA close above HA open (bullish). */
        GREEN,
        /** HA close below HA open (bearish). */
        RED,
        /** HA close equal to HA open (indecisive). */
        FLAT
    }
}
