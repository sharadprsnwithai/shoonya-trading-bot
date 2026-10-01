package com.tradingbot.strategy.bollingerha.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tradingbot.bus.ReactiveSignalEventBus;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import com.tradingbot.strategy.bollingerha.config.BollingerHaProperties;
import com.tradingbot.strategy.bollingerha.model.CompletedCandleEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BollingerHaIntradayEngineTest {

    private ReactiveSignalEventBus eventBus;
    private BollingerHaProperties properties;
    private BollingerHaIntradayEngine engine;

    @BeforeEach
    void setUp() {
        eventBus = mock(ReactiveSignalEventBus.class);
        properties = new BollingerHaProperties();
        engine = new BollingerHaIntradayEngine(properties, eventBus);
    }

    private void feedWarmupCandles(String token, String symbol, ZonedDateTime startTime) {
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
            engine.onCandleCompleted(new CompletedCandleEvent(token, symbol, "CE", c, t));
        }
    }

    @Test
    void testLowerBandBounceTriggersEntrySignalAndCalculatesTarget() {
        String token = "12345";
        String symbol = "NIFTY26OCT25950CE";
        engine.initStrike("CE", token, symbol, new BigDecimal("25950"));

        ZonedDateTime startTime =
                ZonedDateTime.of(2026, 10, 2, 9, 15, 0, 0, ZoneId.of("Asia/Kolkata"));
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

        ZonedDateTime startTime =
                ZonedDateTime.of(2026, 10, 2, 9, 15, 0, 0, ZoneId.of("Asia/Kolkata"));
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

        ZonedDateTime startTime =
                ZonedDateTime.of(2026, 10, 2, 9, 15, 0, 0, ZoneId.of("Asia/Kolkata"));
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

        // Now price moves to Target 1
        Instant t23 = startTime.plusMinutes(22).toInstant();
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

        ArgumentCaptor<TradeSignal> allCaptor = ArgumentCaptor.forClass(TradeSignal.class);
        verify(eventBus, atLeast(3)).publish(allCaptor.capture());

        List<TradeSignal> signals = allCaptor.getAllValues();
        TradeSignal partialExitSig =
                signals.stream()
                        .filter(s -> s.action() == SignalAction.PARTIAL_EXIT_LONG)
                        .findFirst()
                        .orElse(null);
        assertNotNull(partialExitSig);
        assertEquals(65, partialExitSig.baseQuantity()); // Half quantity (1 lot = 65)

        TradeSignal updateSlSig =
                signals.stream()
                        .filter(s -> s.action() == SignalAction.UPDATE_STOP_LOSS)
                        .findFirst()
                        .orElse(null);
        assertNotNull(updateSlSig);
        assertEquals(entrySig.price(), updateSlSig.stopLoss()); // Trailed to cost
    }
}
