package com.tradingbot.strategy.silverorb.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.silverorb.config.SilverOrbProperties;
import com.tradingbot.strategy.silverorb.model.SilverOrbDayState;
import com.tradingbot.strategy.silverorb.model.SilverOrbEvent;
import com.tradingbot.strategy.silverorb.model.SilverOrbEventType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SilverOrbEngine} — the validated silver ORB strategy rules: ORB window,
 * calendar-day VWAP, bias flips, aligned VWAP-cross entries, SL/TGT/square-off exits with
 * SL-before-TGT on the same bar, one trade per day and the 21:00 cutoff.
 */
class SilverOrbEngineTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);

    private SilverOrbProperties props;
    private SilverOrbEngine engine;
    private SilverOrbDayState state;

    @BeforeEach
    void setUp() {
        props = new SilverOrbProperties(); // defaults: 7%/25% of margin at 10% margin
        engine = new SilverOrbEngine(props);
        state = new SilverOrbDayState();
        state.date = DAY;
    }

    // ------------------------------------------------------------------ fixtures

    /** Builds a 5m candle opening at hh:mm IST on the fixed test day. */
    private static Candle bar(int hh, int mm, double o, double h, double l, double c, long vol) {
        Instant ts = DAY.atTime(hh, mm).atZone(IST).toInstant();
        return new Candle(
                "MCX:SILVER04DEC26",
                "5",
                ts,
                BigDecimal.valueOf(o),
                BigDecimal.valueOf(h),
                BigDecimal.valueOf(l),
                BigDecimal.valueOf(c),
                vol);
    }

    /** Six valid ORB bars (18:00..18:25): high 96, low 94, typical 95, range 2.13%. */
    private static List<Candle> orbBars() {
        List<Candle> bars = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            bars.add(bar(18, i * 5, 95, 96, 94, 95, 100));
        }
        return bars;
    }

    /** Steady session bars closing exactly 97 (never crossing a VWAP above 97). */
    private static List<Candle> steadyBars(int fromMinute, int toMinute) {
        List<Candle> bars = new ArrayList<>();
        for (int m = fromMinute; m <= toMinute; m += 5) {
            bars.add(bar(m / 60, m % 60, 97, 97.1, 96.9, 97, 100));
        }
        return bars;
    }

    private static final int MIN_18_35 = 18 * 60 + 35;
    private static final int MIN_20_50 = 20 * 60 + 50;
    private static final int MIN_20_55 = 20 * 60 + 55;

    // ------------------------------------------------------------------ VWAP math

    @Test
    @DisplayName("Day VWAP is cumulative typical price weighted by volume across all day bars")
    void vwapSeriesIsCumulativeVolumeWeightedTypicalPrice() {
        // typ1 = (10+8+9)/3 = 9, vol 100 -> vwap 9
        // typ2 = (13+11+12)/3 = 12, vol 300 -> (900+3600)/400 = 11.25
        List<Candle> bars = List.of(bar(9, 0, 9, 10, 8, 9, 100), bar(9, 5, 12, 13, 11, 12, 300));
        double[] vwap = SilverOrbEngine.vwapSeries(bars);

        assertThat(vwap).hasSize(2);
        assertThat(vwap[0]).isCloseTo(9.0, within(1e-9));
        assertThat(vwap[1]).isCloseTo(11.25, within(1e-9));
    }

    @Test
    @DisplayName("Zero-volume bars are weighted 1 so dead bars cannot freeze the day anchor")
    void vwapSeriesWeightsZeroVolumeBarsAsOne() {
        // vwap2 = (9*100 + 20*1) / (100+1) = 920/101
        List<Candle> bars = List.of(bar(9, 0, 9, 10, 8, 9, 100), bar(9, 5, 20, 20, 20, 20, 0));
        double[] vwap = SilverOrbEngine.vwapSeries(bars);

        assertThat(vwap[1]).isCloseTo(920.0 / 101.0, within(1e-9));
    }

    @Test
    @DisplayName("VWAP anchor includes morning (pre-ORB) bars — calendar-day, not session, VWAP")
    void vwapSeriesIncludesMorningBars() {
        double[] vwap = SilverOrbEngine.vwapSeries(List.of(bar(9, 0, 98, 98, 98, 98, 2000)));

        assertThat(vwap[0]).isCloseTo(98.0, within(1e-9));
    }

    // ------------------------------------------------------------------ ORB validity

    @Test
    @DisplayName("Six ORB bars with healthy range finalize ORB high/low and keep the day tradeable")
    void validOrbFinalizesWithHighLow() {
        List<Candle> dayBars = orbBars();
        dayBars.add(bar(18, 30, 95.5, 97, 95, 97, 100));

        List<SilverOrbEvent> events = engine.processBars(dayBars, state);

        assertThat(state.skipReason).isNull();
        assertThat(state.orbHigh).isCloseTo(96.0, within(1e-9));
        assertThat(state.orbLow).isCloseTo(94.0, within(1e-9));
        assertThat(state.orbBarCount).isEqualTo(6);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(SilverOrbEventType.ENTRY);
    }

    @Test
    @DisplayName("Fewer than 4 ORB bars skips the day — no events even on a perfect later cross")
    void insufficientOrbBarsSkipsDay() {
        List<Candle> dayBars = new ArrayList<>(orbBars().subList(0, 3)); // 18:00..18:10 only
        dayBars.add(bar(18, 30, 95.5, 97, 95, 97, 100));

        List<SilverOrbEvent> events = engine.processBars(dayBars, state);

        assertThat(state.skipReason).isEqualTo("INSUFFICIENT_ORB_BARS");
        assertThat(state.dayComplete).isTrue();
        assertThat(events).isEmpty();
        assertThat(state.trade).isNull();
    }

    @Test
    @DisplayName("Dead ORB range (<0.05%) skips the day via FLAT_ORB")
    void flatOrbSkipsDay() {
        List<Candle> dayBars = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            dayBars.add(bar(18, i * 5, 94.995, 95.0, 94.99, 94.995, 100));
        }
        dayBars.add(bar(18, 30, 96, 97, 95.5, 96.5, 100));

        List<SilverOrbEvent> events = engine.processBars(dayBars, state);

        assertThat(state.skipReason).isEqualTo("FLAT_ORB");
        assertThat(state.dayComplete).isTrue();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("Empty or null bar list yields no events and leaves state untouched")
    void emptyOrNullBarsProduceNoEvents() {
        assertThat(engine.processBars(List.of(), state)).isEmpty();
        assertThat(engine.processBars(null, state)).isEmpty();
        assertThat(state.lastProcessedBarOpen).isNull();
    }

    // ------------------------------------------------------------------ bias + entry

    @Test
    @DisplayName(
            "First session close beyond ORB high with a VWAP cross enters LONG at the bar close")
    void longEntryOnBiasAndAlignedCross() {
        List<Candle> dayBars = orbBars();
        dayBars.add(bar(18, 30, 95.5, 97, 95, 97, 100)); // close 97 > orbHigh 96, up-cross

        List<SilverOrbEvent> events = engine.processBars(dayBars, state);

        assertThat(events).hasSize(1);
        SilverOrbEvent event = events.get(0);
        assertThat(event.type()).isEqualTo(SilverOrbEventType.ENTRY);
        assertThat(event.trade().side).isEqualTo("LONG");
        assertThat(event.trade().entryPrice).isCloseTo(97.0, within(1e-9));
        assertThat(event.trade().stopPrice).isCloseTo(96.321, within(1e-9)); // -0.70% of price
        assertThat(event.trade().targetPrice).isCloseTo(99.425, within(1e-9)); // +2.50% of price
        assertThat(event.trade().entryTime).isEqualTo(DAY.atTime(18, 30));
        assertThat(event.trade().biasTime).isEqualTo(DAY.atTime(18, 30));
        assertThat(state.bias).isEqualTo("LONG");
    }

    @Test
    @DisplayName("Close beyond ORB low with a down-cross enters SHORT (first session bar)")
    void shortEntryOnFirstSessionBar() {
        List<Candle> dayBars = orbBars();
        dayBars.add(bar(18, 30, 93.5, 93.5, 92.5, 93, 100)); // close 93 < orbLow 94, down-cross

        List<SilverOrbEvent> events = engine.processBars(dayBars, state);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).trade().side).isEqualTo("SHORT");
        assertThat(events.get(0).trade().entryPrice).isCloseTo(93.0, within(1e-9));
        assertThat(events.get(0).trade().stopPrice).isCloseTo(93.651, within(1e-9)); // +0.70%
        assertThat(events.get(0).trade().targetPrice).isCloseTo(90.675, within(1e-9)); // -2.50%
        assertThat(state.bias).isEqualTo("SHORT");
    }

    @Test
    @DisplayName(
            "Bias sets LONG without an entry, flips SHORT on the opposite break, enters SHORT there")
    void biasFlipsOnOppositeBoundaryBreak() {
        // Rising ORB closes put the last ORB close above VWAP, so the LONG bias bar cannot cross.
        List<Candle> dayBars = new ArrayList<>();
        int[] closes = {94, 95, 96, 96, 96, 96};
        for (int i = 0; i < 6; i++) {
            dayBars.add(bar(18, i * 5, 95, 96, 94, closes[i], 100));
        }
        dayBars.add(bar(18, 30, 96.5, 97.2, 96.4, 97, 100)); // LONG bias, no up-cross

        List<SilverOrbEvent> afterBias = engine.processBars(new ArrayList<>(dayBars), state);
        assertThat(afterBias).isEmpty();
        assertThat(state.bias).isEqualTo("LONG");
        assertThat(state.trade).isNull();

        dayBars.add(bar(18, 35, 96, 96.5, 92.5, 93, 100)); // flip to SHORT + down-cross

        List<SilverOrbEvent> afterFlip = engine.processBars(dayBars, state);
        assertThat(afterFlip).hasSize(1);
        assertThat(afterFlip.get(0).trade().side).isEqualTo("SHORT");
        assertThat(afterFlip.get(0).trade().entryPrice).isCloseTo(93.0, within(1e-9));
        assertThat(state.bias).isEqualTo("SHORT");
        assertThat(state.trade.biasTime).isEqualTo(DAY.atTime(18, 35));
    }

    @Test
    @DisplayName("A cross against the active bias does not open a trade")
    void crossAgainstBiasOpensNothing() {
        // Rising ORB closes put the last ORB close above VWAP, so the 18:30 bar can set LONG bias
        // without an up-cross. The 18:35 bar then closes BELOW the day VWAP (a down-cross) while
        // staying inside the ORB range — cross opposes the LONG bias, so nothing opens.
        List<Candle> dayBars = new ArrayList<>();
        int[] closes = {94, 95, 96, 96, 96, 96};
        for (int i = 0; i < 6; i++) {
            dayBars.add(bar(18, i * 5, 95, 96, 94, closes[i], 100));
        }
        dayBars.add(bar(18, 30, 96.5, 97.2, 96.4, 97, 100)); // LONG bias, no up-cross
        dayBars.add(bar(18, 35, 97, 97, 94.8, 95, 100)); // down-cross inside ORB range

        List<SilverOrbEvent> events = engine.processBars(dayBars, state);

        assertThat(events).isEmpty();
        assertThat(state.bias).isEqualTo("LONG");
        assertThat(state.trade).isNull();
    }

    @Test
    @DisplayName(
            "Morning-heavy volume pins day VWAP above evening prices — no entry (session VWAP would have entered)")
    void calendarDayAnchorUsesMorningBars() {
        // Session-anchored VWAP (18:00+) would be ~95.2 and the 18:30 close of 97 would cross it;
        // with the calendar-day anchor the morning block at 200 keeps VWAP ~193, so no cross fires.
        List<Candle> dayBars = new ArrayList<>();
        dayBars.add(bar(9, 0, 200, 201, 199, 200, 10000));
        dayBars.addAll(orbBars());
        dayBars.add(bar(18, 30, 95.5, 97, 95, 97, 100));

        List<SilverOrbEvent> events = engine.processBars(dayBars, state);

        assertThat(events).isEmpty();
        assertThat(state.bias).isEqualTo("LONG"); // bias still tracked, only entry suppressed
        assertThat(state.trade).isNull();
    }

    @Test
    @DisplayName("Entry allowed on the last qualifying bar before the cutoff (opens 20:55)")
    void entryAllowedJustBeforeCutoff() {
        List<Candle> dayBars = new ArrayList<>();
        dayBars.add(bar(9, 0, 98, 98, 98, 98, 2000)); // VWAP ~97.3, above session opens
        dayBars.addAll(orbBars());
        dayBars.add(bar(18, 30, 97, 97.2, 96.8, 97, 100)); // LONG bias, no cross yet
        dayBars.addAll(steadyBars(MIN_18_35, MIN_20_50)); // closes 97, VWAP stays above 97
        dayBars.add(bar(20, 55, 97.5, 97.6, 97.4, 97.5, 100)); // dips VWAP below close -> cross

        List<SilverOrbEvent> events = engine.processBars(dayBars, state);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).trade().entryPrice).isCloseTo(97.5, within(1e-9));
        assertThat(events.get(0).trade().entryTime).isEqualTo(DAY.atTime(20, 55));
    }

    @Test
    @DisplayName("The same cross at 21:00 is blocked — entries until 21:00 only")
    void entryBlockedAtCutoff() {
        List<Candle> dayBars = new ArrayList<>();
        dayBars.add(bar(9, 0, 98, 98, 98, 98, 2000));
        dayBars.addAll(orbBars());
        dayBars.add(bar(18, 30, 97, 97.2, 96.8, 97, 100));
        dayBars.addAll(steadyBars(MIN_18_35, MIN_20_55)); // no cross through 20:55
        dayBars.add(bar(21, 0, 97.5, 97.6, 97.4, 97.5, 100)); // perfect cross, but cutoff

        List<SilverOrbEvent> events = engine.processBars(dayBars, state);

        assertThat(events).isEmpty();
        assertThat(state.entryClosed).isTrue();
        assertThat(state.dayComplete).isTrue();
        assertThat(state.trade).isNull();
    }

    // ------------------------------------------------------------------ exits

    @Test
    @DisplayName("Stop-out fills at the exact stop price with -1R and -7% margin return")
    void stopLossFillsAtStopPrice() {
        openLongAt1830();

        List<SilverOrbEvent> events =
                engine.processBars(
                        List.of(
                                bar(18, 30, 95.5, 97, 95, 97, 100),
                                bar(18, 35, 97, 97.2, 96, 96.5, 100)),
                        state);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(SilverOrbEventType.EXIT);
        var t = events.get(0).trade();
        assertThat(t.exitReason).isEqualTo("SL");
        assertThat(t.exitPrice).isCloseTo(96.321, within(1e-9));
        assertThat(t.points).isCloseTo(-0.679, within(1e-9));
        assertThat(t.priceRetPct).isCloseTo(-0.70, within(1e-9));
        assertThat(t.marginRetPct).isCloseTo(-7.0, within(1e-9));
        assertThat(t.rMultiple).isCloseTo(-1.0, within(1e-9));
        assertThat(t.pnlRs).isCloseTo(-0.679 * 30, within(1e-9));
        assertThat(state.dayComplete).isTrue();
    }

    @Test
    @DisplayName("When one bar touches both levels the stop wins (conservative SL-before-TGT)")
    void stopWinsWhenBothLevelsTouchedOnSameBar() {
        openLongAt1830();

        List<SilverOrbEvent> events =
                engine.processBars(
                        List.of(
                                bar(18, 30, 95.5, 97, 95, 97, 100),
                                bar(18, 35, 97, 100, 96, 99, 100)), // high >= TGT, low <= SL
                        state);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).trade().exitReason).isEqualTo("SL");
        assertThat(events.get(0).trade().exitPrice).isCloseTo(96.321, within(1e-9));
        assertThat(events.get(0).trade().rMultiple).isCloseTo(-1.0, within(1e-9));
    }

    @Test
    @DisplayName("Target fill yields +25% margin return = +25/7 R and +72.75 INR at 30kg")
    void targetFillsAtTargetPrice() {
        openLongAt1830();

        List<SilverOrbEvent> events =
                engine.processBars(
                        List.of(
                                bar(18, 30, 95.5, 97, 95, 97, 100),
                                bar(18, 35, 97, 99.5, 96.5, 99, 100)), // high >= TGT, low > SL
                        state);

        assertThat(events).hasSize(1);
        var t = events.get(0).trade();
        assertThat(t.exitReason).isEqualTo("TARGET");
        assertThat(t.exitPrice).isCloseTo(99.425, within(1e-9));
        assertThat(t.marginRetPct).isCloseTo(25.0, within(1e-9));
        assertThat(t.rMultiple).isCloseTo(25.0 / 7.0, within(1e-9));
        assertThat(t.pnlRs).isCloseTo(2.425 * 30, within(1e-9));
    }

    @Test
    @DisplayName("Square-off exits at the close of the first bar opening at/after 23:00 IST")
    void squareOffAtTwentyThreeHundred() {
        List<Candle> dayBars = new ArrayList<>(orbBars());
        dayBars.add(bar(18, 30, 95.5, 97, 95, 97, 100)); // LONG entry at 97
        dayBars.addAll(steadyBars(MIN_18_35, 22 * 60 + 55)); // low 96.9 > SL 96.321, high < TGT
        dayBars.add(bar(23, 0, 97.5, 97.8, 97, 97.6, 100));

        List<SilverOrbEvent> events = engine.processBars(dayBars, state);

        assertThat(events).hasSize(2); // entry at 18:30 + square-off exit at 23:00
        assertThat(events.get(0).type()).isEqualTo(SilverOrbEventType.ENTRY);
        assertThat(events.get(1).type()).isEqualTo(SilverOrbEventType.EXIT);
        var t = events.get(1).trade();
        assertThat(t.exitReason).isEqualTo("SQUAREOFF");
        assertThat(t.exitPrice).isCloseTo(97.6, within(1e-9));
        assertThat(t.exitTime).isEqualTo(DAY.atTime(23, 0));
        assertThat(state.dayComplete).isTrue();
    }

    @Test
    @DisplayName("One trade per day: after an exit no further entries happen")
    void oneTradePerDayNoReentry() {
        List<Candle> dayBars = new ArrayList<>(orbBars());
        dayBars.add(bar(18, 30, 95.5, 97, 95, 97, 100)); // entry
        dayBars.add(bar(18, 35, 97, 97.2, 96, 96.5, 100)); // SL exit
        dayBars.add(bar(18, 40, 96.5, 97.5, 94, 94.5, 100)); // looks like a fresh SHORT setup

        List<SilverOrbEvent> events = engine.processBars(dayBars, state);

        assertThat(events).hasSize(2); // entry + exit only
        assertThat(events.get(1).type()).isEqualTo(SilverOrbEventType.EXIT);
        assertThat(state.trade.exitReason).isEqualTo("SL");
        assertThat(state.dayComplete).isTrue();
    }

    // ------------------------------------------------------------------ restart safety

    @Test
    @DisplayName("Replaying the same bars emits no duplicate events (idempotent cycles)")
    void replayIsIdempotent() {
        List<Candle> dayBars = orbBars();
        dayBars.add(bar(18, 30, 95.5, 97, 95, 97, 100));
        dayBars.add(bar(18, 35, 97, 97.2, 96, 96.5, 100));

        List<SilverOrbEvent> first = engine.processBars(dayBars, state);
        List<SilverOrbEvent> second = engine.processBars(dayBars, state);

        assertThat(first).hasSize(2); // entry + SL exit
        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("Partial cycle then full fetch processes only the unseen bars")
    void partialCycleProcessesOnlyNewBars() {
        List<Candle> dayBars = orbBars();
        dayBars.add(bar(18, 30, 95.5, 97, 95, 97, 100));

        List<SilverOrbEvent> first = engine.processBars(dayBars, state);
        assertThat(first).hasSize(1); // entry only
        assertThat(state.lastProcessedBarOpen).isEqualTo(DAY.atTime(18, 30));

        dayBars.add(bar(18, 35, 97, 97.2, 96, 96.5, 100));
        List<SilverOrbEvent> second = engine.processBars(dayBars, state);
        assertThat(second).hasSize(1); // SL exit only, no duplicate entry
        assertThat(second.get(0).type()).isEqualTo(SilverOrbEventType.EXIT);
    }

    // ------------------------------------------------------------------ force settle

    @Test
    @DisplayName("forceSettle books an open trade flat-of-last-close as FORCE_SQUAREOFF")
    void forceSettleClosesOpenTrade() {
        openLongAt1830();

        var settled = engine.forceSettle(state, 97.4, DAY.atTime(23, 20));

        assertThat(settled).isNotNull();
        assertThat(settled.exitReason).isEqualTo("FORCE_SQUAREOFF");
        assertThat(settled.exitPrice).isCloseTo(97.4, within(1e-9));
        assertThat(settled.rMultiple).isCloseTo(0.4 / 0.679, within(1e-9));
        assertThat(state.dayComplete).isTrue();
    }

    @Test
    @DisplayName("forceSettle is a no-op without an open trade")
    void forceSettleNoopWithoutOpenTrade() {
        assertThat(engine.forceSettle(state, 97.4, DAY.atTime(23, 20))).isNull();
    }

    // ------------------------------------------------------------------ helpers

    /** Runs the standard entry fixture (ORB + 18:30 LONG entry at 97) and leaves the trade open. */
    private void openLongAt1830() {
        List<Candle> bars = orbBars();
        bars.add(bar(18, 30, 95.5, 97, 95, 97, 100));
        List<SilverOrbEvent> events = engine.processBars(bars, state);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(SilverOrbEventType.ENTRY);
    }
}
