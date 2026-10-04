package com.tradingbot.strategy.bollingerha.feeder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.bollingerha.config.BollingerHaProperties;
import com.tradingbot.strategy.bollingerha.model.CompletedCandleEvent;
import com.tradingbot.strategy.bollingerha.model.SelectedStrikes;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ShoonyaHybridDataFeederTest {

    private ShoonyaHybridDataFeeder feeder;

    private ShoonyaHybridDataFeeder newFeeder(List<CompletedCandleEvent> events) {
        ShoonyaConfig config = mock(ShoonyaConfig.class);
        when(config.isEnabled()).thenReturn(false);

        ShoonyaAuthenticator authenticator = mock(ShoonyaAuthenticator.class);
        ShoonyaMarketDataService marketDataService = mock(ShoonyaMarketDataService.class);

        feeder =
                new ShoonyaHybridDataFeeder(
                        config,
                        authenticator,
                        marketDataService,
                        new BollingerHaProperties(),
                        new ObjectMapper(),
                        HttpClient.newHttpClient());

        SelectedStrikes strikes =
                new SelectedStrikes(
                        new BigDecimal("24000.00"),
                        new BigDecimal("24000"),
                        "111",
                        "NIFTY26OCT24000CE",
                        "222",
                        "NIFTY26OCT24000PE");
        feeder.initialize(strikes, events::add, ltp -> {});
        return feeder;
    }

    @AfterEach
    void tearDown() {
        if (feeder != null) {
            feeder.stop();
        }
    }

    @Test
    void testCumulativeSessionVolumeIsConvertedToPerTickDelta() {
        ShoonyaHybridDataFeeder f = newFeeder(new ArrayList<>());

        // First observation only establishes the baseline — a session that started hours ago must
        // not land as one giant volume spike on the current candle.
        assertEquals(0L, f.toDeltaVolume("111", 1000));
        assertEquals(50L, f.toDeltaVolume("111", 1050));
        assertEquals(0L, f.toDeltaVolume("111", 1050));
        // Counter reset (new session) reads as 0, never as a negative bar
        assertEquals(0L, f.toDeltaVolume("111", 30));
        assertEquals(20L, f.toDeltaVolume("111", 50));
    }

    @Test
    void testCandleIsDeduplicatedOnBucketAcrossWsAndRestPaths() {
        List<CompletedCandleEvent> events = new ArrayList<>();
        ShoonyaHybridDataFeeder f = newFeeder(events);

        Instant bucket = Instant.parse("2026-10-02T03:45:00Z");
        Candle candle = newCandle("NIFTY26OCT24000CE", bucket);

        f.handleCandleCompleted(
                new CompletedCandleEvent(
                        "111", "NIFTY26OCT24000CE", "CE", candle, bucket.plusSeconds(60)));
        // Same bucket stamped with a different (wall-clock) completion time — REST replay
        f.handleCandleCompleted(
                new CompletedCandleEvent("111", "NIFTY26OCT24000CE", "CE", candle, Instant.now()));
        assertEquals(1, events.size());

        // An older bucket replayed later must never overwrite a newer candle
        f.handleCandleCompleted(
                new CompletedCandleEvent(
                        "111",
                        "NIFTY26OCT24000CE",
                        "CE",
                        newCandle("NIFTY26OCT24000CE", bucket.minusSeconds(60)),
                        bucket));
        assertEquals(1, events.size());

        // The next bucket is accepted
        f.handleCandleCompleted(
                new CompletedCandleEvent(
                        "111",
                        "NIFTY26OCT24000CE",
                        "CE",
                        newCandle("NIFTY26OCT24000CE", bucket.plusSeconds(60)),
                        bucket.plusSeconds(120)));
        assertEquals(2, events.size());
    }

    private static Candle newCandle(String symbol, Instant bucket) {
        return new Candle(
                symbol,
                "1",
                bucket,
                new BigDecimal("150.00"),
                new BigDecimal("155.00"),
                new BigDecimal("145.00"),
                new BigDecimal("152.00"),
                100L);
    }
}
