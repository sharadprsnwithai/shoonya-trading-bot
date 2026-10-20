package com.tradingbot.scheduler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

import com.tradingbot.marketdata.HistoricalOhlcCacheService;
import com.tradingbot.marketdata.OptionChainSnapshotService;
import com.tradingbot.marketdata.OptionChainSnapshotService.CaptureResult;
import com.tradingbot.model.Candle;
import com.tradingbot.util.NiftySectorRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Post-close job orchestration: both steps always get their chance, and the snapshot session date
 * is probed from the freshly synced OHLC data rather than the wall clock.
 */
class PostCloseDataSchedulerTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private HistoricalOhlcCacheService cacheService;
    private OptionChainSnapshotService snapshotService;
    private PostCloseDataScheduler scheduler;

    @BeforeEach
    void setUp() {
        cacheService = mock(HistoricalOhlcCacheService.class);
        snapshotService = mock(OptionChainSnapshotService.class);
        scheduler = new PostCloseDataScheduler(cacheService, snapshotService);
    }

    @Test
    @DisplayName("The job force-syncs high/low first, then captures chains for the audit universe")
    void runsBothSteps() {
        LocalDate today = LocalDate.now(IST);
        when(cacheService.syncAll(true)).thenReturn(500);
        when(cacheService.getDailyCandles("NIFTY 50")).thenReturn(List.of(dailyCandle(today)));
        when(snapshotService.captureUniverse(anyList(), any(LocalDate.class)))
                .thenReturn(new CaptureResult(today, 100, 7, 0));

        scheduler.runPostCloseDataJob();

        verify(cacheService).syncAll(true);
        verify(snapshotService).captureUniverse(NiftySectorRegistry.getFlattenedUniverse(), today);
    }

    @Test
    @DisplayName("The snapshot session is the latest completed candle, not the wall-clock date")
    void snapshotSessionComesFromTheSyncedCandle() {
        LocalDate lastTraded = LocalDate.of(2026, 10, 9); // a Friday — weekend run probes back
        when(cacheService.syncAll(true)).thenReturn(500);
        when(cacheService.getDailyCandles("NIFTY 50")).thenReturn(List.of(dailyCandle(lastTraded)));
        when(snapshotService.captureUniverse(anyList(), any(LocalDate.class)))
                .thenReturn(new CaptureResult(lastTraded, 100, 0, 0));

        scheduler.runPostCloseDataJob();

        verify(snapshotService).captureUniverse(anyList(), eq(lastTraded));
    }

    @Test
    @DisplayName("An empty cache probe falls back to today (IST) as the session key")
    void probeFallbackIsToday() {
        LocalDate today = LocalDate.now(IST);
        when(cacheService.syncAll(true)).thenReturn(500);
        when(cacheService.getDailyCandles("NIFTY 50")).thenReturn(Collections.emptyList());
        when(snapshotService.captureUniverse(anyList(), any(LocalDate.class)))
                .thenReturn(new CaptureResult(today, 100, 0, 0));

        scheduler.runPostCloseDataJob();

        verify(snapshotService).captureUniverse(anyList(), eq(today));
    }

    @Test
    @DisplayName("A failed OHLC sync does not stop the chain snapshots")
    void ohlcFailureStillCaptures() {
        LocalDate today = LocalDate.now(IST);
        when(cacheService.syncAll(true)).thenThrow(new IllegalStateException("Yahoo down"));
        when(snapshotService.captureUniverse(anyList(), any(LocalDate.class)))
                .thenReturn(new CaptureResult(today, 100, 0, 0));

        assertDoesNotThrow(() -> scheduler.runPostCloseDataJob());

        verify(snapshotService).captureUniverse(anyList(), eq(today));
    }

    @Test
    @DisplayName("A failed capture does not propagate out of the scheduled method")
    void captureFailureDoesNotThrow() {
        when(cacheService.syncAll(true)).thenReturn(500);
        when(cacheService.getDailyCandles("NIFTY 50"))
                .thenReturn(List.of(dailyCandle(LocalDate.now(IST))));
        when(snapshotService.captureUniverse(anyList(), any(LocalDate.class)))
                .thenThrow(new IllegalStateException("broker timeout"));

        assertDoesNotThrow(() -> scheduler.runPostCloseDataJob());

        verify(cacheService).syncAll(true);
    }

    @Test
    @DisplayName("A probe error falls back to today rather than aborting the capture")
    void probeErrorFallsBackToToday() {
        LocalDate today = LocalDate.now(IST);
        when(cacheService.syncAll(true)).thenReturn(500);
        when(cacheService.getDailyCandles("NIFTY 50"))
                .thenThrow(new IllegalStateException("cache cold"));
        when(snapshotService.captureUniverse(anyList(), any(LocalDate.class)))
                .thenReturn(new CaptureResult(today, 100, 0, 0));

        assertDoesNotThrow(() -> scheduler.runPostCloseDataJob());

        verify(snapshotService).captureUniverse(anyList(), eq(today));
    }

    // ------------------------------------------------------------- fixtures

    /** One completed daily candle whose IST date is {@code sessionDate}. */
    private static Candle dailyCandle(LocalDate sessionDate) {
        return new Candle(
                "NIFTY 50",
                "D",
                sessionDate.atTime(10, 30).atZone(IST).toInstant(),
                BigDecimal.valueOf(25_000),
                BigDecimal.valueOf(25_200),
                BigDecimal.valueOf(24_900),
                BigDecimal.valueOf(25_150),
                100_000L);
    }
}
