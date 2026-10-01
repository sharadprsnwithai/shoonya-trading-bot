package com.tradingbot.strategy.bollingerha;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.bus.ReactiveSignalEventBus;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import com.tradingbot.strategy.bollingerha.config.BollingerHaProperties;
import com.tradingbot.strategy.bollingerha.model.CompletedCandleEvent;
import com.tradingbot.strategy.bollingerha.service.BollingerHaIntradayEngine;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class BollingerHaIntegrationTest {

    @Autowired private ReactiveSignalEventBus signalBus;

    @Autowired private BollingerHaProperties properties;

    @Test
    void testEndToEndSignalPipeline() {
        List<TradeSignal> signalsReceived = new ArrayList<>();
        signalBus.getSignalStream().subscribe(signalsReceived::add);

        BollingerHaIntradayEngine engine = new BollingerHaIntradayEngine(properties, signalBus);
        String token = "99001";
        String symbol = "NIFTY26OCT25950CE";
        engine.initStrike("CE", token, symbol, new BigDecimal("25950"));

        ZonedDateTime t0 = ZonedDateTime.of(2026, 10, 2, 9, 15, 0, 0, ZoneId.of("Asia/Kolkata"));

        // 20 warmup ascending candles
        for (int i = 0; i < 20; i++) {
            Instant t = t0.plusMinutes(i).toInstant();
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

        // Lower band dip
        Instant t21 = t0.plusMinutes(20).toInstant();
        engine.onCandleCompleted(
                new CompletedCandleEvent(
                        token,
                        symbol,
                        "CE",
                        new Candle(
                                symbol,
                                "1",
                                t21,
                                new BigDecimal("140.00"),
                                new BigDecimal("141.00"),
                                new BigDecimal("85.00"),
                                new BigDecimal("86.00"),
                                1500L),
                        t21));

        // Reversal green candle
        Instant t22 = t0.plusMinutes(21).toInstant();
        engine.onCandleCompleted(
                new CompletedCandleEvent(
                        token,
                        symbol,
                        "CE",
                        new Candle(
                                symbol,
                                "1",
                                t22,
                                new BigDecimal("130.00"),
                                new BigDecimal("140.00"),
                                new BigDecimal("125.00"),
                                new BigDecimal("139.00"),
                                2000L),
                        t22));

        assertFalse(signalsReceived.isEmpty());
        TradeSignal entrySig =
                signalsReceived.stream()
                        .filter(
                                s ->
                                        s.action() == SignalAction.ENTRY_LONG
                                                && symbol.equals(s.tradingSymbol()))
                        .findFirst()
                        .orElse(null);

        assertNotNull(entrySig);
        assertEquals(SignalAction.ENTRY_LONG, entrySig.action());
        assertEquals("NIFTY26OCT25950CE", entrySig.tradingSymbol());
        assertEquals(130, entrySig.baseQuantity());
    }
}
