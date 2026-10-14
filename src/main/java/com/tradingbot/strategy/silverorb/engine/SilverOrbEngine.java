package com.tradingbot.strategy.silverorb.engine;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.silverorb.config.SilverOrbProperties;
import com.tradingbot.strategy.silverorb.model.SilverOrbDayState;
import com.tradingbot.strategy.silverorb.model.SilverOrbEvent;
import com.tradingbot.strategy.silverorb.model.SilverOrbEventType;
import com.tradingbot.strategy.silverorb.model.SilverOrbTrade;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure decision engine for the Silver ORB evening-session strategy (validated variant: ORB bias +
 * calendar-day VWAP cross, futures, 7%/25% of margin). Faithful port of
 * scripts/silver_orb_vwap_futures_backtest.py:
 *
 * <ul>
 *   <li>ORB = high/low of the bars intersecting [orbStart, orbEnd) — the 18:00-18:30 window at 5m.
 *   <li>Bias: first session-bar close beyond ORB high/low sets LONG/SHORT; flips on the opposite
 *       boundary.
 *   <li>Entry: 5m close crossing the <b>calendar-day</b> VWAP (anchored 00:00 IST, typical price
 *       (H+L+C)/3 weighted by volume, zero-volume bars weighted 1) in the bias direction; the bias
 *       bar itself may be the crossing bar; no entries at/after the cutoff; one trade per day.
 *   <li>Exits (SL checked first on the same bar, conservative): SL/TARGET at their exact prices,
 *       square-off at the close of the first bar opening at/after squareOff (23:00 IST).
 * </ul>
 *
 * Stateless per call: all mutable progress lives in {@link SilverOrbDayState}, which the caller
 * persists; bars already covered by lastProcessedBarOpen are skipped, so replays are idempotent.
 */
public final class SilverOrbEngine {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final SilverOrbProperties props;

    /** Builds an engine bound to the given strategy configuration. */
    public SilverOrbEngine(SilverOrbProperties props) {
        this.props = props;
    }

    /**
     * Processes every not-yet-processed closed bar of the day against the strategy rules.
     *
     * @param dayBars all of today's closed 5m bars, ascending (00:00 IST anchor included)
     * @param state mutable day state (progress, ORB, bias, trade)
     * @return ENTRY/EXIT events emitted in chronological order (possibly empty)
     */
    public List<SilverOrbEvent> processBars(List<Candle> dayBars, SilverOrbDayState state) {
        List<SilverOrbEvent> events = new ArrayList<>();
        if (dayBars == null || dayBars.isEmpty() || state == null || state.dayComplete) {
            return events;
        }
        double[] vwap = vwapSeries(dayBars);
        boolean orbReady = state.orbBarCount > 0 || state.skipReason != null;

        for (int i = 0; i < dayBars.size(); i++) {
            Candle bar = dayBars.get(i);
            LocalDateTime barOpen = LocalDateTime.ofInstant(bar.timestamp(), IST);
            LocalTime t = barOpen.toLocalTime();
            if (t.isBefore(props.getOrbStart())) {
                continue; // day-session bars only feed the calendar-day VWAP
            }
            if (state.lastProcessedBarOpen != null
                    && !barOpen.isAfter(state.lastProcessedBarOpen)) {
                continue;
            }
            if (!orbReady && !t.isBefore(props.getOrbEnd())) {
                finalizeOrb(dayBars, state);
                orbReady = true;
                if (state.skipReason != null) {
                    state.dayComplete = true;
                    state.lastProcessedBarOpen = barOpen;
                    break;
                }
            }
            if (!orbReady) {
                state.lastProcessedBarOpen = barOpen; // ORB-window bar consumed
                continue;
            }
            if (state.trade == null) {
                if (!t.isBefore(props.getEntryCutoff())) {
                    state.entryClosed = true;
                    state.dayComplete = true;
                    state.lastProcessedBarOpen = barOpen;
                    break;
                }
                updateBiasAndMaybeEnter(bar, barOpen, i, dayBars, vwap, state, events);
            } else {
                manageExit(bar, barOpen, state, events);
                if (state.dayComplete) {
                    state.lastProcessedBarOpen = barOpen;
                    break;
                }
            }
            state.lastProcessedBarOpen = barOpen;
        }
        return events;
    }

    /**
     * Data-outage safety net: settles an open trade at the last known close once the square-off
     * grace period has passed (e.g. the 23:00 bar never arrived from the broker).
     *
     * @param state mutable day state holding the open trade
     * @param lastClose last observed close of the day
     * @param settleTime IST wall-clock time of the forced settle
     * @return the settled trade, or null when nothing was open
     */
    public SilverOrbTrade forceSettle(
            SilverOrbDayState state, double lastClose, LocalDateTime settleTime) {
        if (state == null || state.trade == null || !state.trade.isOpen()) {
            return null;
        }
        settle(state.trade, settleTime, lastClose, "FORCE_SQUAREOFF");
        state.dayComplete = true;
        return state.trade;
    }

    /**
     * Calendar-day VWAP series anchored at 00:00 IST: cumulative (typical price x volume) over
     * cumulative volume, typical = (H+L+C)/3. Zero-volume bars are weighted 1 (matches the
     * backtest; keeps dead far-month bars from freezing the anchor).
     *
     * @param bars ascending candles of one IST day
     * @return per-bar VWAP aligned with the input order
     */
    static double[] vwapSeries(List<Candle> bars) {
        double[] out = new double[bars.size()];
        double cumPv = 0.0;
        double cumV = 0.0;
        for (int i = 0; i < bars.size(); i++) {
            Candle b = bars.get(i);
            double typ =
                    (b.high().doubleValue() + b.low().doubleValue() + b.close().doubleValue())
                            / 3.0;
            double weight = b.volume() > 0 ? b.volume() : 1.0;
            cumPv += typ * weight;
            cumV += weight;
            out[i] = cumPv / cumV;
        }
        return out;
    }

    /**
     * Finalizes ORB high/low/count from the day's bars intersecting [orbStart, orbEnd). Interval
     * aware (a bar counts when [open, open+barMinutes) overlaps the window), mirroring the
     * backtest's window logic for any bar alignment.
     */
    private void finalizeOrb(List<Candle> dayBars, SilverOrbDayState state) {
        double high = Double.NEGATIVE_INFINITY;
        double low = Double.POSITIVE_INFINITY;
        int count = 0;
        for (Candle b : dayBars) {
            LocalDateTime bo = LocalDateTime.ofInstant(b.timestamp(), IST);
            LocalDateTime windowStart = bo.toLocalDate().atTime(props.getOrbStart());
            long windowMinutes =
                    props.getOrbEnd().toSecondOfDay() / 60
                            - props.getOrbStart().toSecondOfDay() / 60;
            boolean overlaps =
                    bo.isBefore(windowStart.plusMinutes(windowMinutes))
                            && bo.plusMinutes(props.barMinutes()).isAfter(windowStart);
            if (!overlaps) {
                continue;
            }
            high = Math.max(high, b.high().doubleValue());
            low = Math.min(low, b.low().doubleValue());
            count++;
        }
        state.orbBarCount = count;
        if (count < props.getMinOrbBars()) {
            state.skipReason = "INSUFFICIENT_ORB_BARS";
            return;
        }
        state.orbHigh = high;
        state.orbLow = low;
        if ((high - low) / low * 100.0 < props.getMinOrbRangePct()) {
            state.skipReason = "FLAT_ORB";
        }
    }

    /** Updates the ORB bias from boundary closes and opens a trade on an aligned VWAP cross. */
    private void updateBiasAndMaybeEnter(
            Candle bar,
            LocalDateTime barOpen,
            int i,
            List<Candle> dayBars,
            double[] vwap,
            SilverOrbDayState state,
            List<SilverOrbEvent> events) {
        double close = bar.close().doubleValue();
        if (close > state.orbHigh) {
            if (!"LONG".equals(state.bias)) {
                state.bias = "LONG";
                state.biasTime = barOpen;
            }
        } else if (close < state.orbLow) {
            if (!"SHORT".equals(state.bias)) {
                state.bias = "SHORT";
                state.biasTime = barOpen;
            }
        }
        boolean upCross =
                i > 0 && dayBars.get(i - 1).close().doubleValue() <= vwap[i - 1] && close > vwap[i];
        boolean dnCross =
                i > 0 && dayBars.get(i - 1).close().doubleValue() >= vwap[i - 1] && close < vwap[i];
        if ("LONG".equals(state.bias) && upCross) {
            events.add(openTrade("LONG", bar, barOpen, state));
        } else if ("SHORT".equals(state.bias) && dnCross) {
            events.add(openTrade("SHORT", bar, barOpen, state));
        }
    }

    /** Checks SL (first, conservative), target and square-off against the bar's range. */
    private void manageExit(
            Candle bar,
            LocalDateTime barOpen,
            SilverOrbDayState state,
            List<SilverOrbEvent> events) {
        SilverOrbTrade trade = state.trade;
        double hi = bar.high().doubleValue();
        double lo = bar.low().doubleValue();
        double close = bar.close().doubleValue();
        boolean isLong = "LONG".equals(trade.side);
        boolean hitSl = isLong ? lo <= trade.stopPrice : hi >= trade.stopPrice;
        boolean hitTgt = isLong ? hi >= trade.targetPrice : lo <= trade.targetPrice;
        if (hitSl) {
            settle(trade, barOpen, trade.stopPrice, "SL");
        } else if (hitTgt) {
            settle(trade, barOpen, trade.targetPrice, "TARGET");
        } else if (!barOpen.toLocalTime().isBefore(props.getSquareOff())) {
            settle(trade, barOpen, close, "SQUAREOFF");
        } else {
            return;
        }
        state.dayComplete = true;
        events.add(new SilverOrbEvent(SilverOrbEventType.EXIT, trade));
    }

    /** Opens a trade at the bar close with stop/target distances from risk/target of margin. */
    private SilverOrbEvent openTrade(
            String side, Candle bar, LocalDateTime barOpen, SilverOrbDayState state) {
        double entry = bar.close().doubleValue();
        double slDist = (props.getRiskPct() / 100.0) * (props.getMarginPct() / 100.0) * entry;
        double tgtDist = (props.getTargetPct() / 100.0) * (props.getMarginPct() / 100.0) * entry;
        SilverOrbTrade trade = new SilverOrbTrade();
        trade.side = side;
        trade.biasTime = state.biasTime;
        trade.entryTime = barOpen;
        trade.entryPrice = entry;
        trade.stopPrice = "LONG".equals(side) ? entry - slDist : entry + slDist;
        trade.targetPrice = "LONG".equals(side) ? entry + tgtDist : entry - tgtDist;
        state.trade = trade;
        return new SilverOrbEvent(SilverOrbEventType.ENTRY, trade);
    }

    /** Books the exit on the trade (rupee P&L = points x kgPerLot x lots). */
    private void settle(
            SilverOrbTrade trade, LocalDateTime exitTime, double exitPrice, String reason) {
        double sign = "LONG".equals(trade.side) ? 1.0 : -1.0;
        double points = sign * (exitPrice - trade.entryPrice);
        double pnlRs = points * props.getKgPerLot() * props.getLots();
        trade.settle(exitTime, exitPrice, reason, props.getRiskPct(), props.getMarginPct(), pnlRs);
    }
}
