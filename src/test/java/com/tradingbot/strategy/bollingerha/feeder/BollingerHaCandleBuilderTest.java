package com.tradingbot.strategy.bollingerha.feeder;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.model.Candle;
import com.tradingbot.strategy.bollingerha.model.CompletedCandleEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BollingerHaCandleBuilderTest {

    @Test
    void testMinuteTickAggregationAndCandleCompletion() {
        List<CompletedCandleEvent> completedEvents = new ArrayList<>();
        BollingerHaCandleBuilder builder =
                new BollingerHaCandleBuilder(
                        "12345", "NIFTY26OCT25950CE", "CE", completedEvents::add);

        // Minute 09:15:00 to 09:15:59 ticks
        Instant t1 = Instant.parse("2026-10-02T03:45:05Z"); // 09:15:05 IST
        builder.onTick(new BigDecimal("150.00"), 50L, t1);
        builder.onTick(new BigDecimal("155.00"), 50L, t1.plusSeconds(10));
        builder.onTick(new BigDecimal("148.00"), 50L, t1.plusSeconds(20));
        builder.onTick(new BigDecimal("152.00"), 50L, t1.plusSeconds(40));

        // Minute 09:16:01 tick triggers completion of 09:15 minute candle
        Instant t2 = Instant.parse("2026-10-02T03:46:01Z");
        builder.onTick(new BigDecimal("153.00"), 50L, t2);

        assertEquals(1, completedEvents.size());
        CompletedCandleEvent event = completedEvents.get(0);
        assertEquals("12345", event.token());
        assertEquals("NIFTY26OCT25950CE", event.symbol());
        assertEquals("CE", event.optionType());

        Candle c = event.candle();
        assertEquals(new BigDecimal("150.00"), c.open());
        assertEquals(new BigDecimal("155.00"), c.high());
        assertEquals(new BigDecimal("148.00"), c.low());
        assertEquals(new BigDecimal("152.00"), c.close());
        assertEquals(200L, c.volume());
    }
}
