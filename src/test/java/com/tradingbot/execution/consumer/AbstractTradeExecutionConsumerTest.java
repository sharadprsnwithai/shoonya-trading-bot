package com.tradingbot.execution.consumer;

import static org.junit.jupiter.api.Assertions.*;

import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

class AbstractTradeExecutionConsumerTest {

    static class TestConsumer extends AbstractTradeExecutionConsumer {
        final List<TradeSignal> liveSignals = new ArrayList<>();
        final List<Integer> liveQuantities = new ArrayList<>();
        final List<TradeSignal> paperSignals = new ArrayList<>();
        final List<Integer> paperQuantities = new ArrayList<>();

        TestConsumer(
                String id,
                String broker,
                ExecutionMode mode,
                double multiplier,
                boolean enabled,
                long maxAgeSeconds) {
            super(id, broker, mode, multiplier, enabled, maxAgeSeconds);
        }

        @Override
        protected void handleLiveExecution(TradeSignal signal, int quantity) {
            liveSignals.add(signal);
            liveQuantities.add(quantity);
        }

        @Override
        protected void handlePaperExecution(TradeSignal signal, int quantity) {
            paperSignals.add(signal);
            paperQuantities.add(quantity);
        }
    }

    @Test
    void testQuantityMultiplierAppliedCorrectly() throws InterruptedException {
        TestConsumer consumer =
                new TestConsumer("test-c1", "TEST_BROKER", ExecutionMode.LIVE, 2.5, true, 30);
        Sinks.Many<TradeSignal> sink = Sinks.many().multicast().directBestEffort();
        consumer.start(sink.asFlux());

        TradeSignal signal =
                TradeSignal.of(
                        "LVR",
                        "NIFTY",
                        "NIFTY26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(22000),
                        null,
                        null,
                        100,
                        "Entry",
                        null);

        sink.tryEmitNext(signal);
        Thread.sleep(150);

        assertEquals(1, consumer.liveSignals.size());
        assertEquals(250, consumer.liveQuantities.get(0)); // 100 * 2.5 = 250
        consumer.stop();
    }

    @Test
    void testStaleSignalIsRejected() throws InterruptedException {
        TestConsumer consumer =
                new TestConsumer(
                        "test-c2",
                        "TEST_BROKER",
                        ExecutionMode.LIVE,
                        1.0,
                        true,
                        5); // 5 sec max age
        Sinks.Many<TradeSignal> sink = Sinks.many().multicast().directBestEffort();
        consumer.start(sink.asFlux());

        // Signal emitted 10 seconds ago
        TradeSignal staleSignal =
                new TradeSignal(
                        "SIG-OLD",
                        "LVR",
                        "INFY",
                        "INFY26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(1500),
                        null,
                        null,
                        400,
                        "Old signal",
                        Instant.now().minus(Duration.ofSeconds(10)),
                        null);

        sink.tryEmitNext(staleSignal);
        Thread.sleep(150);

        assertEquals(0, consumer.liveSignals.size());
        consumer.stop();
    }

    @Test
    void testPaperModeRoutesToHandlePaperExecution() throws InterruptedException {
        TestConsumer consumer =
                new TestConsumer("test-c3", "TEST_BROKER", ExecutionMode.PAPER, 1.0, true, 30);
        Sinks.Many<TradeSignal> sink = Sinks.many().multicast().directBestEffort();
        consumer.start(sink.asFlux());

        TradeSignal signal =
                TradeSignal.of(
                        "LVR",
                        "TCS",
                        "TCS26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(3800),
                        null,
                        null,
                        150,
                        "Paper entry",
                        null);

        sink.tryEmitNext(signal);
        Thread.sleep(150);

        assertEquals(0, consumer.liveSignals.size());
        assertEquals(1, consumer.paperSignals.size());
        assertEquals(150, consumer.paperQuantities.get(0));
        consumer.stop();
    }
}
