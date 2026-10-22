package com.tradingbot.strategy.breakoutvwap.service;

import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.breakoutvwap.config.BvsProperties;
import com.tradingbot.strategy.breakoutvwap.model.BvsBias;
import com.tradingbot.strategy.breakoutvwap.model.BvsSetup;
import com.tradingbot.strategy.breakoutvwap.model.BvsSetupState;
import com.tradingbot.util.NseTradingCalendarUtil;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Breakout VWAP (BVS) strategy engine: dual-level confluence bias (09:30-10:00 session range +
 * previous-day H/L), VWAP-crossing 5m trigger candle, futures entry on the trigger high/low break.
 * Decision methods are pure static functions over {@link BvsSetup} so the engine stays testable and
 * backtest-friendly.
 */
@Service
@Slf4j
public class BreakoutVwapService {

    /**
     * Strategy identifier used for TradeSignal routing and config (strategy-modes.BREAKOUT_VWAP).
     */
    public static final String STRATEGY_ID = "BREAKOUT_VWAP";

    private static final int SESSION_RANGE_SLOT_MINUTES = 5;

    private final BvsProperties properties;
    private final TechnicalAnalysisService technicalAnalysisService;

    public BreakoutVwapService(
            BvsProperties properties, TechnicalAnalysisService technicalAnalysisService) {
        this.properties = properties;
        this.technicalAnalysisService = technicalAnalysisService;
    }

    /**
     * Derives the 09:30-10:00 session range from today's 5m candles: high/low of the candles whose
     * start time falls in {@code [rangeStart, rangeEnd)}, taking effect only once every slot in the
     * window has printed. Once complete the range is fixed — later calls are no-ops, so no
     * post-10:00 candle can ever widen it.
     *
     * @param candles5m today's 5-minute candles (any order, may be incomplete)
     * @param setup the setup to populate
     * @param rangeStart inclusive window start, IST
     * @param rangeEnd exclusive window end, IST
     */
    public static void applySessionRange(
            List<Candle> candles5m, BvsSetup setup, LocalTime rangeStart, LocalTime rangeEnd) {
        if (setup == null || setup.getState() != BvsSetupState.AWAITING_RANGE) {
            return;
        }
        if (candles5m == null || candles5m.isEmpty()) {
            return;
        }
        double highValue = -Double.MAX_VALUE;
        double lowValue = Double.MAX_VALUE;
        int count = 0;
        for (Candle candle : candles5m) {
            if (candle == null
                    || candle.timestamp() == null
                    || candle.high() == null
                    || candle.low() == null) {
                continue;
            }
            LocalTime start =
                    LocalTime.ofInstant(candle.timestamp(), NseTradingCalendarUtil.IST_ZONE);
            if (start.isBefore(rangeStart) || !start.isBefore(rangeEnd)) {
                continue;
            }
            count++;
            highValue = Math.max(highValue, candle.high().doubleValue());
            lowValue = Math.min(lowValue, candle.low().doubleValue());
        }
        int expectedSlots =
                (int) Duration.between(rangeStart, rangeEnd).toMinutes()
                        / SESSION_RANGE_SLOT_MINUTES;
        if (count < expectedSlots) {
            return;
        }
        setup.setSessionRangeHigh(highValue);
        setup.setSessionRangeLow(lowValue);
        setup.setState(BvsSetupState.AWAITING_CONFLUENCE);
        log.debug(
                "BVS range complete for {}: high={} low={}",
                setup.getSymbol(),
                highValue,
                lowValue);
    }

    /**
     * Dual-level confluence (spec rule 9): LONG above both highs, SHORT below both lows, NONE
     * otherwise or when any level is missing.
     *
     * @param close candle close price
     * @param setup setup carrying the four levels
     * @return the evaluated bias for this close
     */
    public static BvsBias evaluateConfluence(double close, BvsSetup setup) {
        if (setup == null) {
            return BvsBias.NONE;
        }
        Double rangeHigh = setup.getSessionRangeHigh();
        Double rangeLow = setup.getSessionRangeLow();
        Double pdh = setup.getPdh();
        Double pdl = setup.getPdl();
        if (rangeHigh == null || rangeLow == null || pdh == null || pdl == null) {
            return BvsBias.NONE;
        }
        if (close > Math.max(rangeHigh, pdh)) {
            return BvsBias.LONG;
        }
        if (close < Math.min(rangeLow, pdl)) {
            return BvsBias.SHORT;
        }
        return BvsBias.NONE;
    }

    /**
     * Latch rule (spec rule 10): a latched bias holds until the opposite confluence flips it; NONE
     * never clears it.
     *
     * @param current currently latched bias
     * @param evaluated bias evaluated from the latest close
     * @return the bias after applying the latch
     */
    public static BvsBias advanceBias(BvsBias current, BvsBias evaluated) {
        if (current == null) {
            return evaluated == null ? BvsBias.NONE : evaluated;
        }
        if (evaluated == null || evaluated == BvsBias.NONE || evaluated == current) {
            return current;
        }
        return evaluated;
    }

    /**
     * Trigger test (spec rule 11): a completed 5m candle closing beyond session VWAP in the bias
     * direction.
     *
     * @param bias latched bias
     * @param close candle close
     * @param vwap session VWAP at that candle
     * @return true when the candle qualifies as a trigger candle
     */
    public static boolean isVwapTrigger(BvsBias bias, double close, double vwap) {
        if (bias == BvsBias.LONG) {
            return close > vwap;
        }
        if (bias == BvsBias.SHORT) {
            return close < vwap;
        }
        return false;
    }

    /**
     * Arms the trigger window (spec rules 13/20): eligible states are BIAS_ARMED and TRIGGER_ARMED
     * (an expired window re-arms from the next trigger candle), the candle must be a VWAP cross in
     * the bias direction, and its close must not be past the entry cutoff.
     *
     * @param setup setup to arm
     * @param bias latched bias
     * @param close trigger candle close
     * @param vwap session VWAP at the trigger candle
     * @param candleHigh trigger candle high (entry level for LONG)
     * @param candleLow trigger candle low (entry level for SHORT)
     * @param candleCloseTime close time of the trigger candle — window starts here
     * @param entryCutoff no arming after this IST time
     */
    public static void armTriggerIfEligible(
            BvsSetup setup,
            BvsBias bias,
            double close,
            double vwap,
            double candleHigh,
            double candleLow,
            Instant candleCloseTime,
            LocalTime entryCutoff) {
        if (setup == null || bias == null || bias == BvsBias.NONE || candleCloseTime == null) {
            return;
        }
        BvsSetupState state = setup.getState();
        if (state != BvsSetupState.BIAS_ARMED && state != BvsSetupState.TRIGGER_ARMED) {
            return;
        }
        if (!isVwapTrigger(bias, close, vwap)) {
            return;
        }
        LocalTime closeTime = LocalTime.ofInstant(candleCloseTime, NseTradingCalendarUtil.IST_ZONE);
        if (closeTime.isAfter(entryCutoff)) {
            return;
        }
        setup.setTriggerHigh(candleHigh);
        setup.setTriggerLow(candleLow);
        setup.setTriggerArmedAt(candleCloseTime);
        setup.setEntryConsumed(false);
        setup.setState(BvsSetupState.TRIGGER_ARMED);
    }
}
