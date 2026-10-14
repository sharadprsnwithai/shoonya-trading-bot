package com.tradingbot.strategy.silverorb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Root persisted state of the Silver ORB paper run: the running trade log (closed trades), the
 * current day's mutable state, and the month-end latch. Aggregates are derived from the trade log
 * so the persisted file never drifts from its source of truth.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SilverOrbState {

    /** When this snapshot was written. */
    public Instant savedAt;

    /** All closed trades of the paper run, oldest first. */
    public List<SilverOrbTrade> trades = new ArrayList<>();

    /** Mutable state of today's session; null before the first cycle of a day. */
    public SilverOrbDayState currentDay;

    /** True once the month-end summary telegram has been sent. */
    public boolean finalSummarySent;

    /** Total number of closed trades. */
    public int tradeCount() {
        return trades.size();
    }

    /** Number of closed trades with positive rupee P&L. */
    public int winCount() {
        int wins = 0;
        for (SilverOrbTrade t : trades) {
            if (t.pnlRs > 0) {
                wins++;
            }
        }
        return wins;
    }

    /** Net rupee P&L of all closed trades. */
    public double totalPnlRs() {
        double total = 0;
        for (SilverOrbTrade t : trades) {
            total += t.pnlRs;
        }
        return total;
    }

    /** Net R-multiple sum of all closed trades. */
    public double totalR() {
        double total = 0;
        for (SilverOrbTrade t : trades) {
            total += t.rMultiple;
        }
        return total;
    }

    /** Win rate in percent (0 when no trades). */
    public double winRatePct() {
        return trades.isEmpty() ? 0.0 : 100.0 * winCount() / trades.size();
    }
}
