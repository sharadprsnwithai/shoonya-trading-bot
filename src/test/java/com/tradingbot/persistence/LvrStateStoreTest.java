package com.tradingbot.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.LowestVolumePaperPosition;
import com.tradingbot.model.strategy.LvrExitMode;
import com.tradingbot.model.strategy.LvrInstrumentType;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LvrStateStoreTest {

    @TempDir Path tmpDir;

    @Test
    @DisplayName("H6 Fix: a snapshot carrying unknown fields still loads instead of dropping state")
    void testLoadToleratesUnknownProperties() throws Exception {
        LvrStateStore.DailyState state = new LvrStateStore.DailyState();
        state.date = java.time.LocalDate.of(2026, 10, 8);
        state.todayLossCount = 1;
        state.tradeCounter = 4;
        state.setupAttempts.put("RELIANCE", 2);
        state.openPositions.add(LowestVolumePaperPosition.snapshotOf(openPosition()));

        Path file = tmpDir.resolve("lvr-state.json");
        LvrStateStore.save(file, state);

        // A newer/older build may have written extra fields — the file must still load.
        String json = Files.readString(file);
        ObjectMapper plain = new ObjectMapper().registerModule(new JavaTimeModule());
        var tree = plain.readTree(json);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree)
                .put("fieldFromAnotherBuild", "ignored");
        Files.writeString(file, plain.writeValueAsString(tree));

        LvrStateStore.DailyState loaded = LvrStateStore.load(file);

        assertThat(loaded).isNotNull();
        assertThat(loaded.openPositions).hasSize(1);
        assertThat(loaded.todayLossCount).isEqualTo(1);
        assertThat(loaded.tradeCounter).isEqualTo(4);
        assertThat(loaded.setupAttempts).containsEntry("RELIANCE", 2);
    }

    @Test
    @DisplayName("H6 Fix: a snapshot from a build that predates the risk ledgers still loads")
    void testLoadToleratesMissingNewFields() throws Exception {
        LvrStateStore.DailyState state = new LvrStateStore.DailyState();
        state.date = java.time.LocalDate.of(2026, 10, 8);
        state.exhaustedSymbols.add("TCS");

        Path file = tmpDir.resolve("lvr-state.json");
        LvrStateStore.save(file, state);
        String json = Files.readString(file);
        // Strip the fields added by H6 to emulate an older file.
        ObjectMapper plain = new ObjectMapper().registerModule(new JavaTimeModule());
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) plain.readTree(json);
        tree.remove("todayLossCount");
        tree.remove("tradeCounter");
        tree.remove("setupAttempts");
        Files.writeString(file, plain.writeValueAsString(tree));

        LvrStateStore.DailyState loaded = LvrStateStore.load(file);

        assertThat(loaded).isNotNull();
        assertThat(loaded.todayLossCount).isNull();
        assertThat(loaded.tradeCounter).isNull();
        assertThat(loaded.exhaustedSymbols).containsExactly("TCS");
    }

    @Test
    @DisplayName(
            "Archived P&L carry is exact BigDecimal — a long reset chain can no longer accumulate drift")
    void testArchivedRealizedPnlRoundTripsExactly() throws Exception {
        LvrStateStore.DailyState state = new LvrStateStore.DailyState();
        state.date = java.time.LocalDate.of(2026, 10, 8);
        // Not representable in binary floating point — must survive the round trip unchanged.
        state.archivedRealizedPnl = new BigDecimal("-1234.56");

        Path file = tmpDir.resolve("lvr-state.json");
        LvrStateStore.save(file, state);

        assertThat(LvrStateStore.load(file).archivedRealizedPnl)
                .isEqualByComparingTo(new BigDecimal("-1234.56"));
    }

    @Test
    @DisplayName("A snapshot written before the carry became BigDecimal (plain double) still loads")
    void testArchivedRealizedPnlLoadsLegacyDouble() throws Exception {
        LvrStateStore.DailyState state = new LvrStateStore.DailyState();
        state.date = java.time.LocalDate.of(2026, 10, 8);
        state.archivedRealizedPnl = new BigDecimal("-2500.75");

        Path file = tmpDir.resolve("lvr-state.json");
        LvrStateStore.save(file, state);

        // Rewrite the carry as the plain double an older build wrote.
        ObjectMapper plain = new ObjectMapper().registerModule(new JavaTimeModule());
        var tree =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        plain.readTree(Files.readString(file));
        tree.put("archivedRealizedPnl", -2500.75);
        Files.writeString(file, plain.writeValueAsString(tree));

        assertThat(LvrStateStore.load(file).archivedRealizedPnl)
                .isEqualByComparingTo(new BigDecimal("-2500.75"));
    }

    @Test
    @DisplayName(
            "H6 Fix: concurrent saves never leave a half-written state file or stray temp file")
    void testConcurrentSavesStayAtomic() throws Exception {
        Path file = tmpDir.resolve("lvr-state.json");
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < writers; i++) {
            final int n = i;
            pool.submit(
                    () -> {
                        try {
                            start.await();
                            LvrStateStore.DailyState state = new LvrStateStore.DailyState();
                            state.date = java.time.LocalDate.of(2026, 10, 8);
                            state.tradeCounter = n;
                            state.openPositions.add(
                                    LowestVolumePaperPosition.snapshotOf(openPosition()));
                            LvrStateStore.save(file, state);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // Whatever won the race, the file must parse and no temp files may be left behind.
        assertThat(LvrStateStore.load(file)).isNotNull();
        try (var files = Files.list(tmpDir)) {
            assertThat(files.filter(p -> p.toString().endsWith(".tmp")).count()).isZero();
        }
    }

    private static LowestVolumePaperPosition openPosition() {
        return new LowestVolumePaperPosition(
                "LVR-1",
                "RELIANCE",
                LvrInstrumentType.FUTURES,
                LvrExitMode.PARTIAL_1_2_TRAIL_COST_EOD_1500,
                "RELIANCE FUT",
                250,
                2,
                LowestVolumeDirection.LONG,
                BigDecimal.valueOf(3000.00),
                BigDecimal.valueOf(2980.00),
                BigDecimal.valueOf(3040.00),
                500,
                BigDecimal.valueOf(5000.00),
                Instant.now());
    }
}
