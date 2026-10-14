package com.tradingbot.strategy.silverorb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.silverorb.config.SilverOrbProperties;
import com.tradingbot.strategy.silverorb.model.SilverOrbDayState;
import com.tradingbot.strategy.silverorb.model.SilverOrbState;
import com.tradingbot.strategy.silverorb.model.SilverOrbTrade;
import com.tradingbot.strategy.silverorb.persistence.SilverOrbStateStore;
import com.tradingbot.telegram.TelegramService;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link SilverOrbService}: closed-bar filtering, day rollover, engine dispatch,
 * telegram entry/exit/digest flow, unsettled-trade carry-over, run-until gating and persistence.
 */
class SilverOrbServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);

    @TempDir Path tempDir;

    private ShoonyaMarketDataService marketData;
    private TelegramService telegram;
    private SilverOrbProperties props;
    private SilverOrbService service;
    private Path stateFile;

    @BeforeEach
    void setUp() {
        marketData = mock(ShoonyaMarketDataService.class);
        telegram = mock(TelegramService.class);
        props = new SilverOrbProperties();
        stateFile = tempDir.resolve("silver-orb-state.json");
        props.setStateFile(stateFile.toString());
        service = new SilverOrbService(marketData, telegram, props);
        service.setClock(fixedClock(DAY.atTime(18, 36)));
    }

    // ------------------------------------------------------------------ fixtures

    private static Clock fixedClock(LocalDateTime now) {
        return Clock.fixed(now.atZone(IST).toInstant(), IST);
    }

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

    /** Six valid ORB bars + a LONG entry bar closing 97 at 18:30. */
    private static List<Candle> entryBars() {
        List<Candle> bars = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            bars.add(bar(18, i * 5, 95, 96, 94, 95, 100));
        }
        bars.add(bar(18, 30, 95.5, 97, 95, 97, 100));
        return bars;
    }

    private void serve(List<Candle> bars) {
        when(marketData.fetchHistoricalCandles(
                        eq("MCX"),
                        eq("495214"),
                        eq("SILVER04DEC26"),
                        eq("5"),
                        anyLong(),
                        anyLong()))
                .thenReturn(bars);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("Entry cycle sends a PAPER ENTRY telegram and persists the open trade")
    void entryCycleAlertsAndPersists() {
        serve(entryBars());

        service.runCycle();

        verify(telegram, times(1)).sendAlert(contains("PAPER ENTRY"));
        verify(telegram, times(1)).sendAlert(contains("`LONG`"));
        SilverOrbState state = SilverOrbStateStore.load(stateFile);
        assertThat(state).isNotNull();
        assertThat(state.currentDay).isNotNull();
        assertThat(state.currentDay.date).isEqualTo(DAY);
        assertThat(state.currentDay.trade).isNotNull();
        assertThat(state.currentDay.trade.isOpen()).isTrue();
        assertThat(state.currentDay.trade.entryPrice).isCloseTo(97.0, within(1e-9));
        assertThat(state.trades).isEmpty(); // open trades are not in the ledger yet
    }

    @Test
    @DisplayName("Re-running the same cycle does not duplicate the entry or the alert")
    void duplicateCycleIsIdempotent() {
        serve(entryBars());

        service.runCycle();
        service.runCycle();

        verify(telegram, times(1)).sendAlert(contains("PAPER ENTRY"));
    }

    @Test
    @DisplayName("SL exit adds the trade to the ledger and sends a PAPER EXIT telegram")
    void exitCycleAlertsAndLedgers() {
        List<Candle> bars = entryBars();
        bars.add(bar(18, 35, 97, 97.2, 96, 96.5, 100)); // low 96 <= stop 96.321
        serve(bars);

        // First cycle books the entry (18:35 bar is not yet closed at 18:36 -> open).
        service.setClock(fixedClock(DAY.atTime(18, 36)));
        service.runCycle();

        // Second cycle at 18:41 sees the closed 18:35 bar and books the stop-out.
        service.setClock(fixedClock(DAY.atTime(18, 41)));
        service.runCycle();

        verify(telegram, times(1)).sendAlert(contains("PAPER ENTRY"));
        verify(telegram, times(1)).sendAlert(contains("PAPER EXIT"));
        verify(telegram, times(1)).sendAlert(contains("SL"));
        SilverOrbState state = SilverOrbStateStore.load(stateFile);
        assertThat(state.trades).hasSize(1);
        assertThat(state.trades.get(0).exitReason).isEqualTo("SL");
        assertThat(state.trades.get(0).rMultiple).isCloseTo(-1.0, within(1e-9));
        assertThat(state.totalPnlRs()).isCloseTo(-0.679 * 30, within(1e-9));
    }

    @Test
    @DisplayName("Forming (not yet closed) bars are ignored")
    void formingBarsAreIgnored() {
        List<Candle> bars = entryBars();
        bars.add(bar(18, 35, 97, 97.2, 96, 96.5, 100)); // closes at 18:40 > now 18:36
        serve(bars);

        service.runCycle();

        SilverOrbState state = SilverOrbStateStore.load(stateFile);
        assertThat(state.currentDay.trade.isOpen()).isTrue(); // entry only, no exit yet
        verify(telegram, times(1)).sendAlert(contains("PAPER ENTRY"));
        verify(telegram, never()).sendAlert(contains("PAPER EXIT"));
    }

    @Test
    @DisplayName("No bars at all skips the cycle without touching state or telegram")
    void emptyFeedSkipsCycle() {
        serve(List.of());

        service.runCycle();

        assertThat(stateFile).doesNotExist();
        verifyNoInteractions(telegram);
    }

    @Test
    @DisplayName("Day rollover resets the day state but keeps the cumulative ledger")
    void dayRolloverKeepsLedger() throws IOException {
        // Yesterday's completed state with one winning trade.
        SilverOrbState yesterday = new SilverOrbState();
        SilverOrbDayState prevDay = new SilverOrbDayState();
        prevDay.date = DAY.minusDays(1);
        SilverOrbTrade prevTrade = new SilverOrbTrade();
        prevTrade.side = "LONG";
        prevTrade.entryTime = DAY.minusDays(1).atTime(18, 30);
        prevTrade.entryPrice = 97;
        prevTrade.settle(
                DAY.minusDays(1).atTime(19, 0),
                99,
                "TARGET",
                props.getRiskPct(),
                props.getMarginPct(),
                60.0);
        prevDay.trade = prevTrade;
        prevDay.dayComplete = true;
        yesterday.currentDay = prevDay;
        yesterday.trades.add(prevTrade);
        SilverOrbStateStore.save(stateFile, yesterday);

        serve(entryBars());
        service.runCycle();

        SilverOrbState state = SilverOrbStateStore.load(stateFile);
        assertThat(state.currentDay.date).isEqualTo(DAY);
        assertThat(state.currentDay.trade.entryTime).isEqualTo(DAY.atTime(18, 30)); // fresh day
        assertThat(state.trades).hasSize(1); // yesterday's win preserved
        assertThat(state.totalPnlRs()).isEqualTo(60.0);
    }

    @Test
    @DisplayName("An open trade crossing midnight is settled flat as UNSETTLED_EOD and alerted")
    void unsettledTradeIsCarriedOverFlat() throws IOException {
        SilverOrbState stale = new SilverOrbState();
        SilverOrbDayState prevDay = new SilverOrbDayState();
        prevDay.date = DAY.minusDays(1);
        SilverOrbTrade open = new SilverOrbTrade();
        open.side = "LONG";
        open.entryTime = DAY.minusDays(1).atTime(18, 30);
        open.entryPrice = 97;
        open.stopPrice = 96.321;
        open.targetPrice = 99.425;
        prevDay.trade = open; // still open at rollover
        stale.currentDay = prevDay;
        SilverOrbStateStore.save(stateFile, stale);

        serve(entryBars());
        service.runCycle();

        verify(telegram, times(1)).sendAlert(contains("UNSETTLED_EOD"));
        SilverOrbState state = SilverOrbStateStore.load(stateFile);
        assertThat(state.trades).hasSize(1);
        assertThat(state.trades.get(0).exitReason).isEqualTo("UNSETTLED_EOD");
        assertThat(state.trades.get(0).rMultiple).isEqualTo(0.0);
        assertThat(state.currentDay.date).isEqualTo(DAY);
        assertThat(state.currentDay.trade.entryTime).isEqualTo(DAY.atTime(18, 30)); // new day
    }

    @Test
    @DisplayName("After runUntil only a single final summary is ever sent (no fetches)")
    void runUntilSendsFinalSummaryOnce() throws IOException {
        service.setClock(fixedClock(LocalDate.of(2026, 11, 15).atTime(10, 0))); // > runUntil
        SilverOrbState seed = new SilverOrbState();
        SilverOrbTrade t = new SilverOrbTrade();
        t.side = "LONG";
        t.entryTime = DAY.atTime(18, 30);
        t.entryPrice = 97;
        t.settle(DAY.atTime(19, 0), 99, "TARGET", props.getRiskPct(), props.getMarginPct(), 60.0);
        seed.trades.add(t);
        SilverOrbStateStore.save(stateFile, seed);

        service.runCycle();
        service.runCycle();

        verify(telegram, times(1)).sendAlert(contains("PAPER RUN COMPLETE"));
        verify(marketData, never())
                .fetchHistoricalCandles(
                        anyString(), anyString(), anyString(), anyString(), anyLong(), anyLong());
        SilverOrbState state = SilverOrbStateStore.load(stateFile);
        assertThat(state.finalSummarySent).isTrue();
    }

    @Test
    @DisplayName("Disabled strategy never fetches or alerts")
    void disabledStrategyDoesNothing() {
        props.setEnabled(false);

        service.runCycle();

        verifyNoInteractions(marketData);
        verifyNoInteractions(telegram);
    }

    @Test
    @DisplayName("Telegram alerts disabled by config suppresses messages but still persists")
    void telegramToggleSuppressesMessages() {
        props.setTelegramAlertsEnabled(false);
        serve(entryBars());

        service.runCycle();

        verifyNoInteractions(telegram);
        SilverOrbState state = SilverOrbStateStore.load(stateFile);
        assertThat(state.currentDay.trade.isOpen()).isTrue();
    }

    @Test
    @DisplayName("End-of-day digest fires once after the square-off window")
    void eodDigestSentOnceAfterSquareOff() throws IOException {
        // Settled trade on file, clock inside the digest window (>= 23:05).
        SilverOrbState seed = new SilverOrbState();
        SilverOrbDayState day = new SilverOrbDayState();
        day.date = DAY;
        day.dayComplete = true;
        SilverOrbTrade t = new SilverOrbTrade();
        t.side = "LONG";
        t.entryTime = DAY.atTime(18, 30);
        t.entryPrice = 97;
        t.settle(
                DAY.atTime(23, 0),
                97.5,
                "SQUAREOFF",
                props.getRiskPct(),
                props.getMarginPct(),
                15.0);
        day.trade = t;
        seed.currentDay = day;
        seed.trades.add(t);
        SilverOrbStateStore.save(stateFile, seed);

        service.setClock(fixedClock(DAY.atTime(23, 6)));
        serve(entryBars());

        service.runCycle();
        service.runCycle();

        verify(telegram, times(1)).sendAlert(contains("DAY P&L"));
        SilverOrbState state = SilverOrbStateStore.load(stateFile);
        assertThat(state.currentDay.eodSummarySent).isTrue();
    }

    @Test
    @DisplayName("Broker feed is requested for the IST calendar day window")
    void fetchUsesIstDayWindow() {
        serve(List.of());

        service.runCycle();

        long expectedStart = DAY.atStartOfDay(IST).toEpochSecond();
        long expectedEnd = DAY.atTime(18, 36).atZone(IST).toEpochSecond();
        verify(marketData, times(1))
                .fetchHistoricalCandles(
                        eq("MCX"),
                        eq("495214"),
                        eq("SILVER04DEC26"),
                        eq("5"),
                        eq(expectedStart),
                        eq(expectedEnd));
    }
}
