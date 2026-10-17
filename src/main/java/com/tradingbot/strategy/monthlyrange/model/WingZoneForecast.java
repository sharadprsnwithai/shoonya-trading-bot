package com.tradingbot.strategy.monthlyrange.model;

import java.math.BigDecimal;

/**
 * Spot-containment assessment for a short-gamma wing structure held to expiry.
 *
 * <p>The payoff of a wing structure rises one-for-one once spot clears the long wing, then falls
 * two-for-one once spot clears the short wing. Its upper zero crossing therefore sits at {@code 2 *
 * shortOffset - wingOffset}, <b>not</b> at the short strike, which is where profit peaks rather
 * than where it dies. The lower bound is the same value: once spot falls below the long wing every
 * leg expires worthless and the credit is simply kept, so the structure stays profitable all the
 * way down. For the reference 150/200/350 structure the zone is therefore 250 points wide on each
 * side, plus the net credit collected.
 *
 * <p>Touching that bound <i>during</i> the hold is what costs money, which is why containment is
 * measured against the window's full high/low excursion rather than its closing return.
 */
public record WingZoneForecast(
        String label,
        BigDecimal spot,
        BigDecimal lowerBreakeven,
        BigDecimal upperBreakeven,
        double zoneHalfWidthPct,
        double gaussianTerminalStayPct,
        double measuredStayPct,
        int measuredWindows,
        int horizonSessions,
        double wingOffsetPts,
        double shortOffsetPts,
        double creditPts,
        String note) {}
