package com.tradingbot.strategy.breakoutvwap.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.breakoutvwap.model.BvsBias;
import com.tradingbot.strategy.breakoutvwap.model.BvsSetup;
import com.tradingbot.strategy.breakoutvwap.model.BvsSetupState;
import com.tradingbot.util.NseTradingCalendarUtil;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Confluence, latch, session-range and VWAP-trigger decisions — the BVS bias engine. */
class BvsConfluenceTest {

    private static final LocalTime RANGE_START = LocalTime.of(9, 30);
    private static final LocalTime RANGE_END = LocalTime.of(10, 0);

    private static Instant ist(int hour, int minute) {
        return ZonedDateTime.of(2026, 10, 22, hour, minute, 0, 0, NseTradingCalendarUtil.IST_ZONE)
                .toInstant();
    }

    private static Candle candle5m(int hour, int minute, double high, double low) {
        return Candle.of5m(
                "X",
                ist(hour, minute),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(high),
                BigDecimal.valueOf(low),
                BigDecimal.valueOf(high),
                1000L);
    }

    /** Six in-window candles (09:30-09:55) with high 25000 / low 24800, plus a 10:00 outlier. */
    private static List<Candle> sessionCandles() {
        List<Candle> candles = new ArrayList<>();
        candles.add(candle5m(9, 30, 24950, 24850));
        candles.add(candle5m(9, 35, 24980, 24880));
        candles.add(candle5m(9, 40, 25000, 24900));
        candles.add(candle5m(9, 45, 24970, 24870));
        candles.add(candle5m(9, 50, 24960, 24860));
        candles.add(candle5m(9, 55, 24990, 24800));
        // post-window candle — must never widen the range
        candles.add(candle5m(10, 0, 99999, 10000));
        return candles;
    }

    @Test
    @DisplayName("session range uses only candles starting 09:30-09:55")
    void sessionRangeUsesOnlyCandlesStarting0930To0955() {
        BvsSetup setup = new BvsSetup();
        setup.setSymbol("RELIANCE");

        BreakoutVwapService.applySessionRange(sessionCandles(), setup, RANGE_START, RANGE_END);

        assertThat(setup.getSessionRangeHigh()).isEqualTo(25000.0);
        assertThat(setup.getSessionRangeLow()).isEqualTo(24800.0);
        assertThat(setup.getState()).isEqualTo(BvsSetupState.AWAITING_CONFLUENCE);
    }

    @Test
    @DisplayName("range stays unset until the full 09:30-10:00 window has printed")
    void sessionRangeRequiresSixCompletedCandles() {
        BvsSetup setup = new BvsSetup();
        setup.setSymbol("RELIANCE");
        List<Candle> partial =
                List.of(
                        candle5m(9, 30, 24950, 24850),
                        candle5m(9, 35, 24980, 24880),
                        candle5m(9, 40, 25000, 24900),
                        candle5m(9, 45, 24970, 24870));

        BreakoutVwapService.applySessionRange(partial, setup, RANGE_START, RANGE_END);

        assertThat(setup.getState()).isEqualTo(BvsSetupState.AWAITING_RANGE);
        assertThat(setup.getSessionRangeHigh()).isNull();
        assertThat(setup.getSessionRangeLow()).isNull();
    }

    @Test
    @DisplayName("long confluence needs both the range high and PDH broken")
    void confluenceLongRequiresBothHighsBroken() {
        BvsSetup setup = new BvsSetup();
        setup.setSymbol("RELIANCE");
        setup.setSessionRangeHigh(25000.0);
        setup.setSessionRangeLow(24800.0);
        setup.setPdh(25100.0);
        setup.setPdl(24700.0);

        assertThat(BreakoutVwapService.evaluateConfluence(25050.0, setup)).isEqualTo(BvsBias.NONE);
        assertThat(BreakoutVwapService.evaluateConfluence(25101.0, setup)).isEqualTo(BvsBias.LONG);
    }

    @Test
    @DisplayName("short confluence needs both the range low and PDL broken")
    void confluenceShortRequiresBothLowsBroken() {
        BvsSetup setup = new BvsSetup();
        setup.setSymbol("RELIANCE");
        setup.setSessionRangeHigh(25000.0);
        setup.setSessionRangeLow(24800.0);
        setup.setPdh(25100.0);
        setup.setPdl(24700.0);

        assertThat(BreakoutVwapService.evaluateConfluence(24750.0, setup)).isEqualTo(BvsBias.NONE);
        assertThat(BreakoutVwapService.evaluateConfluence(24699.0, setup)).isEqualTo(BvsBias.SHORT);
    }

    @Test
    @DisplayName("bias latches until opposite confluence")
    void biasLatchesUntilOppositeConfluence() {
        assertThat(BreakoutVwapService.advanceBias(BvsBias.NONE, BvsBias.LONG))
                .isEqualTo(BvsBias.LONG);
        assertThat(BreakoutVwapService.advanceBias(BvsBias.LONG, BvsBias.NONE))
                .isEqualTo(BvsBias.LONG);
        assertThat(BreakoutVwapService.advanceBias(BvsBias.LONG, BvsBias.LONG))
                .isEqualTo(BvsBias.LONG);
        assertThat(BreakoutVwapService.advanceBias(BvsBias.LONG, BvsBias.SHORT))
                .isEqualTo(BvsBias.SHORT);
        assertThat(BreakoutVwapService.advanceBias(BvsBias.NONE, BvsBias.NONE))
                .isEqualTo(BvsBias.NONE);
    }

    @Test
    @DisplayName("VWAP trigger checks bias direction only")
    void vwapTriggerChecksDirectionOnly() {
        assertThat(BreakoutVwapService.isVwapTrigger(BvsBias.LONG, 25010.0, 25000.0)).isTrue();
        assertThat(BreakoutVwapService.isVwapTrigger(BvsBias.LONG, 24990.0, 25000.0)).isFalse();
        assertThat(BreakoutVwapService.isVwapTrigger(BvsBias.SHORT, 24990.0, 25000.0)).isTrue();
        assertThat(BreakoutVwapService.isVwapTrigger(BvsBias.SHORT, 25010.0, 25000.0)).isFalse();
        assertThat(BreakoutVwapService.isVwapTrigger(BvsBias.NONE, 25010.0, 25000.0)).isFalse();
    }

    @Test
    @DisplayName("arming sets the entry window from the trigger candle close, none after cutoff")
    void armTriggerSetsWindowFromCandleClose() {
        BvsSetup setup = new BvsSetup();
        setup.setSymbol("RELIANCE");
        setup.setState(BvsSetupState.BIAS_ARMED);
        setup.setBias(BvsBias.LONG);

        BreakoutVwapService.armTriggerIfEligible(
                setup,
                BvsBias.LONG,
                25010.0,
                25000.0,
                25050.0,
                24990.0,
                ist(11, 5),
                LocalTime.of(14, 30));

        assertThat(setup.getState()).isEqualTo(BvsSetupState.TRIGGER_ARMED);
        assertThat(setup.getTriggerHigh()).isEqualTo(25050.0);
        assertThat(setup.getTriggerLow()).isEqualTo(24990.0);
        assertThat(setup.getTriggerArmedAt()).isEqualTo(ist(11, 5));
        assertThat(setup.isEntryConsumed()).isFalse();

        // 14:31 IST is past the entry cutoff — no arm
        BvsSetup late = new BvsSetup();
        late.setSymbol("RELIANCE");
        late.setState(BvsSetupState.BIAS_ARMED);
        late.setBias(BvsBias.LONG);

        BreakoutVwapService.armTriggerIfEligible(
                late,
                BvsBias.LONG,
                25010.0,
                25000.0,
                25050.0,
                24990.0,
                ist(14, 31),
                LocalTime.of(14, 30));

        assertThat(late.getState()).isEqualTo(BvsSetupState.BIAS_ARMED);
        assertThat(late.getTriggerHigh()).isNull();
    }

    @Test
    @DisplayName("missing PDH/PDL means no confluence")
    void pdhPdlMissingMeansNoConfluence() {
        BvsSetup setup = new BvsSetup();
        setup.setSymbol("RELIANCE");
        setup.setSessionRangeHigh(25000.0);
        setup.setSessionRangeLow(24800.0);

        assertThat(BreakoutVwapService.evaluateConfluence(99999.0, setup)).isEqualTo(BvsBias.NONE);
        assertThat(BreakoutVwapService.evaluateConfluence(1.0, setup)).isEqualTo(BvsBias.NONE);
    }
}
