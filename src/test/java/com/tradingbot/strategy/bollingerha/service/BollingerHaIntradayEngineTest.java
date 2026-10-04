package com.tradingbot.strategy.bollingerha.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tradingbot.bus.ReactiveSignalEventBus;
import com.tradingbot.model.Candle;
import com.tradingbot.persistence.BollingerHaStateStore;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import com.tradingbot.strategy.bollingerha.config.BollingerHaProperties;
import com.tradingbot.strategy.bollingerha.model.BollingerHaPosition;
import com.tradingbot.strategy.bollingerha.model.CompletedCandleEvent;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class BollingerHaIntradayEngineTest {

    private ReactiveSignalEventBus eventBus;
    private BollingerHaProperties properties;
    private BollingerHaIntradayEngine engine;

    @BeforeEach
    void setUp(@TempDir java.nio.file.Path tempDir) {
        eventBus = mock(ReactiveSignalEventBus.class);
        // D6: the engine publishes before mutating, so the bus has to accept signals for any
        // state change to happen at all.
        when(eventBus.publish(any(TradeSignal.class))).thenReturn(true);
        properties = new BollingerHaProperties();
        // Keep persistence out of the repo's data/ directory — a leftover state file from an
        // earlier run would be restored and silently suppress every entry in this class.
        properties.setStateFilePath(tempDir.resolve("engine-state.json").toString());
        engine = new BollingerHaIntradayEngine(properties, eventBus);
    }

    /** 09:15 IST on the current trading day (keeps the engine inside its entry window). */
    private ZonedDateTime entryWindowStart() {
        return java.time.LocalDate.now(ZoneId.of("Asia/Kolkata"))
                .atTime(9, 15)
                .atZone(ZoneId.of("Asia/Kolkata"));
    }

    private void feedWarmupCandles(String token, String symbol, ZonedDateTime startTime) {
        feedWarmupCandles(token, symbol, "CE", startTime);
    }

    private void feedWarmupCandles(
            String token, String symbol, String optionType, ZonedDateTime startTime) {
        // Feed 20 ascending candles (100 -> 150) so BB lower band stays far below price (~97 vs
        // ~146)
        for (int i = 0; i < 20; i++) {
            Instant t = startTime.plusMinutes(i).toInstant();
            BigDecimal base = BigDecimal.valueOf(100.0 + (i * 2.5));
            Candle c =
                    new Candle(
                            symbol,
                            "1",
                            t,
                            base,
                            base.add(new BigDecimal("2.00")),
                            base.subtract(new BigDecimal("1.00")),
                            base.add(new BigDecimal("1.50")),
                            1000L);
            engine.onCandleCompleted(new CompletedCandleEvent(token, symbol, optionType, c, t));
        }
    }

    /** Warmup (20) + lower-band touch + green reversal for a CE — leaves an open position. */
    private TradeSignal openPosition(String token, String symbol, ZonedDateTime startTime) {
        engine.initStrike("CE", token, symbol, new BigDecimal("25950"));
        engine.initStrike("PE", token + "-PE", symbol.replace("CE", "PE"), new BigDecimal("25950"));
        feedWarmupCandles(token, symbol, "CE", startTime);
        feedTouchAndReversal(token, symbol, startTime, "CE");

        return action(publishedSignals(), SignalAction.ENTRY_LONG);
    }

    /** Full entry setup for an arbitrary contract — used by the trend-filter tests. */
    private void runEntrySetup(
            String token, String symbol, String optionType, ZonedDateTime start) {
        engine.initStrike(optionType, token, symbol, new BigDecimal("25950"));
        feedWarmupCandles(token, symbol, optionType, start);
        feedTouchAndReversal(token, symbol, start, optionType);
    }

    private void feedTouchAndReversal(
            String token, String symbol, ZonedDateTime startTime, String optionType) {
        Instant t21 = startTime.plusMinutes(20).toInstant();
        Candle touchCandle =
                new Candle(
                        symbol,
                        "1",
                        t21,
                        new BigDecimal("140.00"),
                        new BigDecimal("141.00"),
                        new BigDecimal("85.00"),
                        new BigDecimal("86.00"),
                        2000L);
        engine.onCandleCompleted(
                new CompletedCandleEvent(token, symbol, optionType, touchCandle, t21));

        Instant t22 = startTime.plusMinutes(21).toInstant();
        Candle revCandle =
                new Candle(
                        symbol,
                        "1",
                        t22,
                        new BigDecimal("130.00"),
                        new BigDecimal("140.00"),
                        new BigDecimal("125.00"),
                        new BigDecimal("139.00"),
                        2500L);
        engine.onCandleCompleted(
                new CompletedCandleEvent(token, symbol, optionType, revCandle, t22));
    }

    private List<TradeSignal> publishedSignals() {
        ArgumentCaptor<TradeSignal> captor = ArgumentCaptor.forClass(TradeSignal.class);
        verify(eventBus, atLeastOnce()).publish(captor.capture());
        return captor.getAllValues();
    }

    private static TradeSignal action(List<TradeSignal> signals, SignalAction action) {
        return signals.stream().filter(s -> s.action() == action).findFirst().orElse(null);
    }

    @Test
    void testLowerBandBounceTriggersEntrySignalAndCalculatesTarget() {
        String token = "12345";
        String symbol = "NIFTY26OCT25950CE";
        engine.initStrike("CE", token, symbol, new BigDecimal("25950"));

        ZonedDateTime startTime = entryWindowStart();
        feedWarmupCandles(token, symbol, startTime);

        // Candle 21: Plunge touching lower band (< 97)
        Instant t21 = startTime.plusMinutes(20).toInstant();
        Candle touchCandle =
                new Candle(
                        symbol,
                        "1",
                        t21,
                        new BigDecimal("140.00"),
                        new BigDecimal("141.00"),
                        new BigDecimal("85.00"),
                        new BigDecimal("86.00"),
                        2000L);
        engine.onCandleCompleted(new CompletedCandleEvent(token, symbol, "CE", touchCandle, t21));

        // Candle 22: Green HA reversal candle
        Instant t22 = startTime.plusMinutes(21).toInstant();
        Candle revCandle =
                new Candle(
                        symbol,
                        "1",
                        t22,
                        new BigDecimal("130.00"),
                        new BigDecimal("140.00"),
                        new BigDecimal("125.00"),
                        new BigDecimal("139.00"),
                        2500L);
        engine.onCandleCompleted(new CompletedCandleEvent(token, symbol, "CE", revCandle, t22));

        ArgumentCaptor<TradeSignal> captor = ArgumentCaptor.forClass(TradeSignal.class);
        verify(eventBus, times(1)).publish(captor.capture());

        TradeSignal sig = captor.getValue();
        assertEquals(SignalAction.ENTRY_LONG, sig.action());
        assertEquals("NIFTY26OCT25950CE", sig.tradingSymbol());
        assertTrue(sig.price().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(sig.stopLoss().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(sig.targetPrice().compareTo(sig.price()) > 0);
        assertEquals(130, sig.baseQuantity()); // 2 lots = 130
    }

    @Test
    void testMaxSlFilterRejectsOversizedCandle() {
        String token = "12345";
        String symbol = "NIFTY26OCT25950CE";
        engine.initStrike("CE", token, symbol, new BigDecimal("25950"));

        ZonedDateTime startTime = entryWindowStart();
        feedWarmupCandles(token, symbol, startTime);

        // Touch candle (< 97)
        Instant t21 = startTime.plusMinutes(20).toInstant();
        Candle touchCandle =
                new Candle(
                        symbol,
                        "1",
                        t21,
                        new BigDecimal("140.00"),
                        new BigDecimal("141.00"),
                        new BigDecimal("85.00"),
                        new BigDecimal("86.00"),
                        2000L);
        engine.onCandleCompleted(new CompletedCandleEvent(token, symbol, "CE", touchCandle, t21));

        // Reversal candle with huge range (High = 175, Low = 120 -> Risk ~ 57 pts > 20 pts)
        Instant t22 = startTime.plusMinutes(21).toInstant();
        Candle bigCandle =
                new Candle(
                        symbol,
                        "1",
                        t22,
                        new BigDecimal("130.00"),
                        new BigDecimal("175.00"),
                        new BigDecimal("120.00"),
                        new BigDecimal("174.00"),
                        2500L);
        engine.onCandleCompleted(new CompletedCandleEvent(token, symbol, "CE", bigCandle, t22));

        verify(eventBus, never()).publish(any());
    }

    @Test
    void testPartialExitAt1to2TargetAndCostSlTrailing() {
        String token = "12345";
        String symbol = "NIFTY26OCT25950CE";
        engine.initStrike("CE", token, symbol, new BigDecimal("25950"));

        ZonedDateTime startTime = entryWindowStart();
        feedWarmupCandles(token, symbol, startTime);

        // Touch
        Instant t21 = startTime.plusMinutes(20).toInstant();
        Candle touchCandle =
                new Candle(
                        symbol,
                        "1",
                        t21,
                        new BigDecimal("140.00"),
                        new BigDecimal("141.00"),
                        new BigDecimal("85.00"),
                        new BigDecimal("86.00"),
                        2000L);
        engine.onCandleCompleted(new CompletedCandleEvent(token, symbol, "CE", touchCandle, t21));

        // Reversal
        Instant t22 = startTime.plusMinutes(21).toInstant();
        Candle revCandle =
                new Candle(
                        symbol,
                        "1",
                        t22,
                        new BigDecimal("130.00"),
                        new BigDecimal("140.00"),
                        new BigDecimal("125.00"),
                        new BigDecimal("139.00"),
                        2500L);
        engine.onCandleCompleted(new CompletedCandleEvent(token, symbol, "CE", revCandle, t22));

        ArgumentCaptor<TradeSignal> captor = ArgumentCaptor.forClass(TradeSignal.class);
        verify(eventBus, times(1)).publish(captor.capture());
        TradeSignal entrySig = captor.getValue();
        BigDecimal targetPrice = entrySig.targetPrice();

        // Now price moves to Target 1 (stays above the trailed cost stop)
        Instant t23 = startTime.plusMinutes(22).toInstant();
        Candle targetCandle =
                new Candle(
                        symbol,
                        "1",
                        t23,
                        entrySig.price(),
                        targetPrice.add(new BigDecimal("2.00")),
                        entrySig.price().add(BigDecimal.ONE),
                        targetPrice.add(BigDecimal.ONE),
                        3000L);
        engine.onCandleCompleted(new CompletedCandleEvent(token, symbol, "CE", targetCandle, t23));

        ArgumentCaptor<TradeSignal> allCaptor = ArgumentCaptor.forClass(TradeSignal.class);
        // D3: exactly one publish per state change — the entry, then the partial exit. The runner
        // stop trails to cost inside that same signal, so no separate UPDATE_STOP_LOSS is emitted.
        verify(eventBus, times(2)).publish(allCaptor.capture());

        List<TradeSignal> signals = allCaptor.getAllValues();
        TradeSignal partialExitSig = action(signals, SignalAction.PARTIAL_EXIT_LONG);
        assertNotNull(partialExitSig);
        assertEquals(65, partialExitSig.baseQuantity()); // Half quantity (1 lot = 65)
        assertEquals(entrySig.price(), partialExitSig.stopLoss()); // Runner trailed to cost
        assertNull(action(signals, SignalAction.UPDATE_STOP_LOSS));

        BollingerHaPosition runner = engine.getActivePosition();
        assertNotNull(runner);
        assertEquals(65, runner.getRemainingQuantity());
        assertTrue(runner.isTargetHit());
        assertEquals(entrySig.price(), runner.getStopLoss());

        BigDecimal expectedPnl =
                targetPrice.subtract(entrySig.price()).multiply(BigDecimal.valueOf(65));
        assertEquals(0, engine.getDailyState().getRealizedPnl().compareTo(expectedPnl));
    }

    @Test
    void testSingleLotPositionTrailsStopInsteadOfSplitting() {
        properties.setDefaultLots(1); // 65 qty — cannot be split into two halves
        engine = new BollingerHaIntradayEngine(properties, eventBus);

        String token = "12345";
        String symbol = "NIFTY26OCT25950CE";
        engine.initStrike("CE", token, symbol, new BigDecimal("25950"));

        ZonedDateTime startTime = entryWindowStart();
        feedWarmupCandles(token, symbol, startTime);
        feedTouchAndReversal(token, symbol, startTime, "CE");

        TradeSignal entrySig = action(publishedSignals(), SignalAction.ENTRY_LONG);
        assertNotNull(entrySig);
        assertEquals(65, entrySig.baseQuantity());

        BigDecimal targetPrice = entrySig.targetPrice();
        Instant t23 = startTime.plusMinutes(22).toInstant();
        Candle targetCandle =
                new Candle(
                        symbol,
                        "1",
                        t23,
                        entrySig.price(),
                        targetPrice.add(new BigDecimal("2.00")),
                        entrySig.price().add(BigDecimal.ONE),
                        targetPrice.add(BigDecimal.ONE),
                        3000L);
        engine.onCandleCompleted(new CompletedCandleEvent(token, symbol, "CE", targetCandle, t23));

        List<TradeSignal> signals = publishedSignals();
        assertNull(action(signals, SignalAction.PARTIAL_EXIT_LONG));

        TradeSignal updateSlSig = action(signals, SignalAction.UPDATE_STOP_LOSS);
        assertNotNull(updateSlSig);
        assertEquals(entrySig.price(), updateSlSig.stopLoss()); // Trailed to cost
        assertEquals(65, updateSlSig.baseQuantity()); // Whole position still open
        assertNotNull(engine.getActivePosition());
        assertTrue(engine.getActivePosition().isTargetHit());
        assertEquals(0, engine.getDailyState().getRealizedPnl().compareTo(BigDecimal.ZERO));
    }

    @Test
    void testCeEntryRejectedWhenSpotIsBelowEma() {
        String token = "12345";
        String symbol = "NIFTY26OCT25950CE";

        // 20 flat samples settle the EMA at 24000, then a drop puts spot below it.
        for (int i = 0; i < 20; i++) {
            engine.updateSpotPrice(new BigDecimal("24000"));
        }
        engine.updateSpotPrice(new BigDecimal("23000"));

        runEntrySetup(token, symbol, "CE", entryWindowStart());

        // Filtered before the bus — nothing is ever published.
        verify(eventBus, never()).publish(any(TradeSignal.class));
        assertNull(engine.getActivePosition());
    }

    @Test
    void testPeEntryRejectedWhenSpotIsAboveEma() {
        String token = "12345";
        String symbol = "NIFTY26OCT25950PE";

        for (int i = 0; i < 20; i++) {
            engine.updateSpotPrice(new BigDecimal("24000"));
        }
        engine.updateSpotPrice(new BigDecimal("25000"));

        runEntrySetup(token, symbol, "PE", entryWindowStart());

        // Filtered before the bus — nothing is ever published.
        verify(eventBus, never()).publish(any(TradeSignal.class));
        assertNull(engine.getActivePosition());
    }

    @Test
    void testAutoSquareOffFlattensOpenPositionAtCutoff() {
        TradeSignal entrySig = openPosition("12345", "NIFTY26OCT25950CE", entryWindowStart());
        assertNotNull(entrySig);
        assertNotNull(engine.getActivePosition());

        properties.setAutoSquareOffTime("15:00");
        ZonedDateTime afterCutoff = entryWindowStart().withHour(15).withMinute(1);
        boolean handled = engine.enforceAutoSquareOff(afterCutoff.toInstant());

        assertTrue(handled);
        List<TradeSignal> signals = publishedSignals();
        TradeSignal sqOff = action(signals, SignalAction.SQUARE_OFF);
        assertNotNull(sqOff);
        assertNotNull(sqOff.price()); // Priced off the last close, not a placeholder
        assertEquals(65 * 2, sqOff.baseQuantity());
        assertNull(engine.getActivePosition());
        // (last close 139 - entry 141) * 130 — the loss is booked, not dropped.
        assertTrue(engine.getDailyState().getRealizedPnl().compareTo(BigDecimal.ZERO) < 0);
    }

    @Test
    void testRolloverForcesExitOfOpenPosition() {
        TradeSignal entrySig = openPosition("12345", "NIFTY26OCT25950CE", entryWindowStart());
        assertNotNull(entrySig);
        assertNotNull(engine.getActivePosition());

        ZonedDateTime nextDay = entryWindowStart().plusDays(1);
        Instant t = nextDay.toInstant();
        Candle staleCandle =
                new Candle(
                        "NIFTY26OCT25950CE",
                        "1",
                        t,
                        new BigDecimal("130.00"),
                        new BigDecimal("131.00"),
                        new BigDecimal("129.00"),
                        new BigDecimal("130.00"),
                        4000L);
        engine.onCandleCompleted(
                new CompletedCandleEvent("12345", "NIFTY26OCT25950CE", "CE", staleCandle, t));

        List<TradeSignal> signals = publishedSignals();
        assertNotNull(action(signals, SignalAction.SQUARE_OFF));
        assertNull(engine.getActivePosition());
        assertEquals(0, engine.getDailyState().getTradeCount());
        assertEquals(0, engine.getDailyState().getRealizedPnl().compareTo(BigDecimal.ZERO));
        assertFalse(engine.getSetupStates().get("CE").isTouchedLowerBand());
    }

    @Test
    void testPublishFailureLeavesPositionUntouched() {
        ZonedDateTime start = entryWindowStart();
        String token = "12345";
        String symbol = "NIFTY26OCT25950CE";
        engine.initStrike("CE", token, symbol, new BigDecimal("25950"));
        feedWarmupCandles(token, symbol, start);
        feedTouchAndReversal(token, symbol, start, "CE");

        TradeSignal entrySig = action(publishedSignals(), SignalAction.ENTRY_LONG);
        assertNotNull(entrySig); // publish returned true, so the entry committed
        BollingerHaPosition position = engine.getActivePosition();
        assertNotNull(position);

        // Bus goes down: the partial exit must NOT be applied.
        when(eventBus.publish(any(TradeSignal.class))).thenReturn(false);

        BigDecimal targetPrice = entrySig.targetPrice();
        Instant t23 = start.plusMinutes(22).toInstant();
        Candle targetCandle =
                new Candle(
                        symbol,
                        "1",
                        t23,
                        entrySig.price(),
                        targetPrice.add(new BigDecimal("2.00")),
                        entrySig.price().subtract(new BigDecimal("1.00")),
                        targetPrice.add(BigDecimal.ONE),
                        3000L);
        engine.onCandleCompleted(new CompletedCandleEvent(token, symbol, "CE", targetCandle, t23));

        BollingerHaPosition after = engine.getActivePosition();
        assertNotNull(after);
        assertFalse(after.isTargetHit());
        assertEquals(position.getTotalQuantity(), after.getRemainingQuantity());
        assertEquals(0, engine.getDailyState().getRealizedPnl().compareTo(BigDecimal.ZERO));
    }

    @Test
    void testTickDrivenTargetAndStop() {
        ZonedDateTime start = entryWindowStart();
        TradeSignal entrySig = openPosition("12345", "NIFTY26OCT25950CE", start);
        assertNotNull(entrySig);

        // Gap over the target on a live tick.
        engine.onTick(
                "12345",
                entrySig.targetPrice().add(BigDecimal.ONE),
                start.plusMinutes(25).toInstant());
        assertNotNull(action(publishedSignals(), SignalAction.PARTIAL_EXIT_LONG));
        assertTrue(engine.getActivePosition().isTargetHit());

        // Tick below the trailed (cost) stop closes the runner.
        engine.onTick(
                "12345",
                entrySig.price().subtract(BigDecimal.ONE),
                start.plusMinutes(30).toInstant());
        assertNotNull(action(publishedSignals(), SignalAction.EXIT_LONG));
        assertNull(engine.getActivePosition());
        assertTrue(engine.getDailyState().getRealizedPnl().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void testSameDayStateIsRestoredAfterRestart() {
        TradeSignal entrySig = openPosition("12345", "NIFTY26OCT25950CE", entryWindowStart());
        assertNotNull(entrySig);

        BollingerHaIntradayEngine restarted = new BollingerHaIntradayEngine(properties, eventBus);

        BollingerHaPosition restored = restarted.getActivePosition();
        assertNotNull(restored);
        assertEquals("NIFTY26OCT25950CE", restored.getSymbol());
        assertEquals(130, restored.getRemainingQuantity());
        assertFalse(restored.isTargetHit());
        assertEquals(1, restarted.getDailyState().getTradeCount());
        assertEquals(0, restarted.getDailyState().getRealizedPnl().compareTo(BigDecimal.ZERO));
        // The restored trade still consumes a slot for the rest of the day.
        assertTrue(restarted.getDailyState().getTradeCount() > 0);
    }

    @Test
    void testStaleSnapshotForcesExitOfOrphanedPositionOnFirstEvent(@TempDir Path tempDir)
            throws Exception {
        Path file = tempDir.resolve("stale.json");
        BollingerHaStateStore.State stale = new BollingerHaStateStore.State();
        stale.tradeDate = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1);
        stale.tradeCount = 1;
        stale.realizedPnl = new BigDecimal("10.50");
        stale.locked = false;
        stale.openPosition = stalePosition();
        stale.positions = new ArrayList<>(List.of(stalePosition()));
        BollingerHaStateStore.save(file, stale);

        BollingerHaProperties staleProps = new BollingerHaProperties();
        staleProps.setStateFilePath(file.toString());
        BollingerHaIntradayEngine restarted = new BollingerHaIntradayEngine(staleProps, eventBus);

        // Yesterday's position must not come back as a live one.
        assertNull(restarted.getActivePosition());
        verify(eventBus, never()).publish(any(TradeSignal.class));

        // The first live event of the new day forces it out.
        ZonedDateTime start = entryWindowStart();
        Instant t = start.plusMinutes(5).toInstant();
        Candle c =
                new Candle(
                        "NIFTY26OCT25950CE",
                        "1",
                        t,
                        new BigDecimal("130.00"),
                        new BigDecimal("131.00"),
                        new BigDecimal("129.00"),
                        new BigDecimal("130.00"),
                        500L);
        restarted.onCandleCompleted(
                new CompletedCandleEvent("12345", "NIFTY26OCT25950CE", "CE", c, t));

        TradeSignal sqOff = action(publishedSignals(), SignalAction.SQUARE_OFF);
        assertNotNull(sqOff);
        assertEquals("NIFTY26OCT25950CE", sqOff.tradingSymbol());
        assertEquals(130, sqOff.baseQuantity());
        assertNull(restarted.getActivePosition());
    }

    private static BollingerHaStateStore.PositionSnapshot stalePosition() {
        BollingerHaStateStore.PositionSnapshot p = new BollingerHaStateStore.PositionSnapshot();
        p.positionId = "BHA-STALE";
        p.symbol = "NIFTY26OCT25950CE";
        p.token = "12345";
        p.optionType = "CE";
        p.entryPrice = new BigDecimal("141.00");
        p.stopLoss = new BigDecimal("124.00");
        p.targetPrice = new BigDecimal("175.00");
        p.totalQuantity = 130;
        p.remainingQuantity = 130;
        p.entryTime = Instant.parse("2026-10-03T04:00:00Z");
        p.closed = false;
        return p;
    }

    @Test
    void testAlignedPartialQuantityFloorsToLotBoundary() {
        assertEquals(65, BollingerHaIntradayEngine.alignedPartialQuantity(130, 65));
        assertEquals(65, BollingerHaIntradayEngine.alignedPartialQuantity(195, 65));
        assertEquals(130, BollingerHaIntradayEngine.alignedPartialQuantity(260, 65));
        assertEquals(0, BollingerHaIntradayEngine.alignedPartialQuantity(65, 65));
        assertEquals(0, BollingerHaIntradayEngine.alignedPartialQuantity(30, 65));
        assertEquals(0, BollingerHaIntradayEngine.alignedPartialQuantity(130, 1));
    }
}
