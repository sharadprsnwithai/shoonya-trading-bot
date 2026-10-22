package com.tradingbot.execution.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

/**
 * Lane-isolation guard: a consumer with a non-empty strategyModes map ignores signals from
 * strategies it does not map, so a strategy can only ever execute on its own dedicated lane. An
 * empty map keeps the historical fallback to the consumer's default mode (manual/ad-hoc signals).
 */
class TradeExecutionConsumerRoutingTest {

    static class RecordingConsumer extends AbstractTradeExecutionConsumer {
        final List<TradeSignal> liveSignals = new java.util.ArrayList<>();
        final List<TradeSignal> paperSignals = new java.util.ArrayList<>();

        RecordingConsumer(ExecutionMode defaultMode, Map<String, ExecutionMode> strategyModes) {
            super("test-routing", "TEST_BROKER", defaultMode, strategyModes, 1.0, true, 30);
        }

        @Override
        protected void handleLiveExecution(TradeSignal signal, int quantity) {
            liveSignals.add(signal);
        }

        @Override
        protected void handlePaperExecution(TradeSignal signal, int quantity) {
            paperSignals.add(signal);
        }
    }

    private static TradeSignal entry(String strategyId) {
        return TradeSignal.of(
                strategyId,
                "RELIANCE",
                "RELIANCE23OCTFUT",
                SignalAction.ENTRY_LONG,
                BigDecimal.valueOf(25000),
                null,
                null,
                25,
                "test entry",
                Map.of("instrumentType", "FUTURE"));
    }

    private static void deliver(RecordingConsumer consumer, TradeSignal... signals)
            throws InterruptedException {
        Sinks.Many<TradeSignal> sink = Sinks.many().multicast().directBestEffort();
        consumer.start(sink.asFlux());
        for (TradeSignal signal : signals) {
            sink.tryEmitNext(signal);
        }
        Thread.sleep(150);
        consumer.stop();
    }

    @Test
    @DisplayName("signals from unmapped strategies are ignored by mapped consumers")
    void unmappedStrategySignalsAreIgnored() throws InterruptedException {
        RecordingConsumer consumer =
                new RecordingConsumer(
                        ExecutionMode.LIVE,
                        Map.of(
                                "LOWEST_VOLUME_REVERSAL", ExecutionMode.PAPER,
                                "CAR_WEEKLY_GTT", ExecutionMode.PAPER));

        deliver(consumer, entry("BREAKOUT_VWAP"), entry("OTHER"));

        assertEquals(0, consumer.liveSignals.size());
        assertEquals(0, consumer.paperSignals.size());
    }

    @Test
    @DisplayName("mapped strategies still route in their mapped mode")
    void mappedStrategySignalsStillRoute() throws InterruptedException {
        RecordingConsumer consumer =
                new RecordingConsumer(
                        ExecutionMode.LIVE,
                        Map.of(
                                "LOWEST_VOLUME_REVERSAL", ExecutionMode.PAPER,
                                "CAR_WEEKLY_GTT", ExecutionMode.PAPER));

        deliver(consumer, entry("LOWEST_VOLUME_REVERSAL"), entry("CAR_WEEKLY_GTT"));

        assertEquals(0, consumer.liveSignals.size());
        assertEquals(2, consumer.paperSignals.size());
    }

    @Test
    @DisplayName("an empty strategy-modes map keeps the fallback to the consumer's default mode")
    void emptyStrategyModesKeepsFallback() throws InterruptedException {
        RecordingConsumer consumer = new RecordingConsumer(ExecutionMode.PAPER, Map.of());

        deliver(consumer, entry("BREAKOUT_VWAP"), entry("SOMETHING_ELSE"));

        assertEquals(0, consumer.liveSignals.size());
        assertEquals(2, consumer.paperSignals.size());
    }
}
