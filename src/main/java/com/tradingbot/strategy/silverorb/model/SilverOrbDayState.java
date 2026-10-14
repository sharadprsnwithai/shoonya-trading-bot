package com.tradingbot.strategy.silverorb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Mutable per-day strategy state for the Silver ORB evening session: ORB levels, bias, the single
 * daily trade, and processing progress. Progress is tracked bar-by-bar (lastProcessedBarOpen) so a
 * crash or restart replays idempotently and never double-fills. Public fields for direct Jackson
 * binding (see {@code SilverOrbStateStore}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SilverOrbDayState {

    /** IST trading date this state belongs to. */
    public LocalDate date;

    /** ORB high of the 18:00-18:30 IST window; 0 until finalized. */
    public double orbHigh;

    /** ORB low of the 18:00-18:30 IST window; 0 until finalized. */
    public double orbLow;

    /** Number of bars inside the ORB window. */
    public int orbBarCount;

    /** Skip marker: null / INSUFFICIENT_ORB_BARS / FLAT_ORB (no entries that day). */
    public String skipReason;

    /** Current ORB bias: null / LONG / SHORT. */
    public String bias;

    /** IST wall-clock time the current bias was set. */
    public LocalDateTime biasTime;

    /** Open time of the last 5m bar fully processed; null before the first processed bar. */
    public LocalDateTime lastProcessedBarOpen;

    /** True once the 21:00 IST entry cutoff was reached without a trade. */
    public boolean entryClosed;

    /** True once the day may no longer produce events (trade settled or entries closed). */
    public boolean dayComplete;

    /** True once the end-of-day P&L digest telegram was sent. */
    public boolean eodSummarySent;

    /** The day's single trade (open or settled); one trade per day by strategy rule. */
    public SilverOrbTrade trade;
}
