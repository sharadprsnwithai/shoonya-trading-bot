package com.tradingbot.strategy.silverorb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;

/**
 * A single PAPER silver futures trade produced by the Silver ORB strategy. Public fields for direct
 * Jackson binding (see {@code SilverOrbStateStore}); prices are plain doubles because the whole
 * risk model (7% / 25% of margin) is percentage-based and mirrors the validated Python backtest
 * (scripts/silver_orb_vwap_futures_backtest.py) one-to-one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SilverOrbTrade {

    /** Trade side: "LONG" or "SHORT". */
    public String side;

    /** IST wall-clock time when the ORB bias that led to this trade was set. */
    public LocalDateTime biasTime;

    /** IST wall-clock entry time (close of the crossing 5m bar). */
    public LocalDateTime entryTime;

    /** Entry price (close of the VWAP-cross bar). */
    public double entryPrice;

    /** Hard stop price derived from riskPct of margin. */
    public double stopPrice;

    /** Target price derived from targetPct of margin. */
    public double targetPrice;

    /** IST wall-clock exit time; null while the position is open. */
    public LocalDateTime exitTime;

    /** Exit price; 0 while open. */
    public double exitPrice;

    /** Exit reason: SL / TARGET / SQUAREOFF / FORCE_SQUAREOFF; null while open. */
    public String exitReason;

    /** Signed price points captured (positive = profit). */
    public double points;

    /** Signed move in percent of entry price. */
    public double priceRetPct;

    /** Signed return in percent of margin deployed (priceRetPct / marginPct). */
    public double marginRetPct;

    /** Signed R-multiple (marginRetPct / riskPct); −1R on a full stop-out. */
    public double rMultiple;

    /** Signed rupee P&L for the configured lot size (points x kgPerLot x lots). */
    public double pnlRs;

    /** True while no exit has been booked. */
    public boolean isOpen() {
        return exitReason == null || exitReason.isBlank();
    }

    /**
     * Books the exit and derives every performance metric from the entry parameters.
     *
     * @param exitTime IST wall-clock exit time
     * @param exitPrice fill price at the exit level
     * @param reason SL / TARGET / SQUAREOFF / FORCE_SQUAREOFF
     * @param riskPct risk in percent of margin (e.g. 7.0)
     * @param marginPct assumed margin in percent of notional (e.g. 10.0)
     * @param pnlRs rupee P&L computed by the caller (points x kgPerLot x lots)
     */
    public void settle(
            LocalDateTime exitTime,
            double exitPrice,
            String reason,
            double riskPct,
            double marginPct,
            double pnlRs) {
        this.exitTime = exitTime;
        this.exitPrice = exitPrice;
        this.exitReason = reason;
        double sign = "LONG".equals(side) ? 1.0 : -1.0;
        this.points = sign * (exitPrice - entryPrice);
        this.priceRetPct = points / entryPrice * 100.0;
        // Leverage = 100/marginPct (10% margin => 10x), so a -0.70% price move is -7.0% on margin.
        this.marginRetPct = priceRetPct * 100.0 / marginPct;
        this.rMultiple = marginRetPct / riskPct;
        this.pnlRs = pnlRs;
    }
}
