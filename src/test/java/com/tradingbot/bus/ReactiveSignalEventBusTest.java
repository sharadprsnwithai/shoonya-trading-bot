package com.tradingbot.bus;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ReactiveSignalEventBusTest {

    private ReactiveSignalEventBus bus;

    @BeforeEach
    void setUp() {
        bus = new ReactiveSignalEventBus();
    }

    @Test
    void testMulticastSignalBroadcastToMultipleSubscribers() {
        Flux<TradeSignal> stream = bus.getSignalStream();
        AtomicInteger sub1Count = new AtomicInteger(0);
        AtomicInteger sub2Count = new AtomicInteger(0);

        stream.subscribe(sig -> sub1Count.incrementAndGet());
        stream.subscribe(sig -> sub2Count.incrementAndGet());

        TradeSignal signal =
                TradeSignal.of(
                        "LVR",
                        "SBIN",
                        "SBIN26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(800),
                        BigDecimal.valueOf(790),
                        BigDecimal.valueOf(820),
                        750,
                        "Breakout",
                        null);

        boolean published = bus.publish(signal);
        assertTrue(published);
        assertEquals(1, sub1Count.get());
        assertEquals(1, sub2Count.get());
    }

    @Test
    void testIgnoreNonActionableHoldSignal() {
        TradeSignal hold = TradeSignal.hold("LVR", "INFY", "No trigger");
        boolean published = bus.publish(hold);
        assertFalse(published);
    }

    @Test
    void testIgnoreNullSignal() {
        boolean published = bus.publish(null);
        assertFalse(published);
    }

    @Test
    void testLateSubscriberDoesNotReceivePastSignals() {
        TradeSignal signal1 =
                TradeSignal.of(
                        "LVR",
                        "TCS",
                        "TCS26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(3500),
                        null,
                        null,
                        175,
                        "Early signal",
                        null);
        bus.publish(signal1);

        AtomicInteger lateSubCount = new AtomicInteger(0);
        bus.getSignalStream().subscribe(sig -> lateSubCount.incrementAndGet());

        TradeSignal signal2 =
                TradeSignal.of(
                        "LVR",
                        "WIPRO",
                        "WIPRO26MARFUT",
                        SignalAction.ENTRY_SHORT,
                        BigDecimal.valueOf(500),
                        null,
                        null,
                        1500,
                        "Late signal",
                        null);
        bus.publish(signal2);

        assertEquals(1, lateSubCount.get());
    }
}
