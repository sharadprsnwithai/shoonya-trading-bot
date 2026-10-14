package com.tradingbot.strategy.silverorb.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.tradingbot.strategy.silverorb.model.SilverOrbDayState;
import com.tradingbot.strategy.silverorb.model.SilverOrbState;
import com.tradingbot.strategy.silverorb.model.SilverOrbTrade;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Round-trip and failure-mode tests for {@link SilverOrbStateStore}. */
class SilverOrbStateStoreTest {

    @TempDir Path tempDir;

    @Test
    @DisplayName("State survives a save/load round-trip with all fields intact")
    void roundTripPreservesState() throws IOException {
        Path file = tempDir.resolve("silver-orb-state.json");
        SilverOrbState state = new SilverOrbState();
        state.savedAt = Instant.parse("2026-10-14T13:06:00Z");
        state.finalSummarySent = true;

        SilverOrbDayState day = new SilverOrbDayState();
        day.date = LocalDate.of(2026, 10, 14);
        day.orbHigh = 226500.5;
        day.orbLow = 224100.25;
        day.orbBarCount = 6;
        day.bias = "LONG";
        day.biasTime = LocalDateTime.of(2026, 10, 14, 18, 35);
        day.lastProcessedBarOpen = LocalDateTime.of(2026, 10, 14, 18, 35);
        day.entryClosed = true;
        day.dayComplete = true;
        day.eodSummarySent = true;

        SilverOrbTrade open = new SilverOrbTrade();
        open.side = "LONG";
        open.entryTime = LocalDateTime.of(2026, 10, 14, 18, 35);
        open.entryPrice = 226500.5;
        open.stopPrice = 224914.0;
        open.targetPrice = 232163.06;
        open.biasTime = LocalDateTime.of(2026, 10, 14, 18, 35);
        day.trade = open;

        // SHORT that runs +2.5% of price: 225000 -> 219375 = +5625 pts = +25% on margin = +25/7 R.
        SilverOrbTrade closed = new SilverOrbTrade();
        closed.side = "SHORT";
        closed.entryTime = LocalDateTime.of(2026, 10, 13, 19, 10);
        closed.entryPrice = 225000.0;
        closed.settle(
                LocalDateTime.of(2026, 10, 13, 20, 0), 219375.0, "TARGET", 7.0, 10.0, 5625.0 * 30);
        state.trades.add(closed);

        state.currentDay = day;
        SilverOrbStateStore.save(file, state);
        SilverOrbState loaded = SilverOrbStateStore.load(file);

        assertThat(loaded).isNotNull();
        assertThat(loaded.savedAt).isEqualTo(state.savedAt);
        assertThat(loaded.finalSummarySent).isTrue();
        assertThat(loaded.currentDay.date).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(loaded.currentDay.orbHigh).isEqualTo(226500.5);
        assertThat(loaded.currentDay.orbLow).isEqualTo(224100.25);
        assertThat(loaded.currentDay.orbBarCount).isEqualTo(6);
        assertThat(loaded.currentDay.bias).isEqualTo("LONG");
        assertThat(loaded.currentDay.biasTime).isEqualTo(LocalDateTime.of(2026, 10, 14, 18, 35));
        assertThat(loaded.currentDay.lastProcessedBarOpen)
                .isEqualTo(LocalDateTime.of(2026, 10, 14, 18, 35));
        assertThat(loaded.currentDay.entryClosed).isTrue();
        assertThat(loaded.currentDay.dayComplete).isTrue();
        assertThat(loaded.currentDay.eodSummarySent).isTrue();

        // open trade on the current day
        assertThat(loaded.currentDay.trade.isOpen()).isTrue();
        assertThat(loaded.currentDay.trade.entryPrice).isEqualTo(226500.5);
        assertThat(loaded.currentDay.trade.targetPrice).isEqualTo(232163.06);
        assertThat(loaded.currentDay.trade.biasTime)
                .isEqualTo(LocalDateTime.of(2026, 10, 14, 18, 35));

        // closed trade in the ledger with derived metrics
        assertThat(loaded.trades).hasSize(1);
        SilverOrbTrade t = loaded.trades.get(0);
        assertThat(t.exitReason).isEqualTo("TARGET");
        assertThat(t.points).isEqualTo(5625.0);
        assertThat(t.priceRetPct).isCloseTo(2.5, within(1e-9));
        assertThat(t.marginRetPct).isCloseTo(25.0, within(1e-9));
        assertThat(t.rMultiple).isCloseTo(25.0 / 7.0, within(1e-9));
        assertThat(t.pnlRs).isEqualTo(5625.0 * 30);
        assertThat(loaded.totalPnlRs()).isEqualTo(5625.0 * 30);
        assertThat(loaded.winCount()).isEqualTo(1);
        assertThat(loaded.winRatePct()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("Loading a missing file returns null (fresh start)")
    void loadMissingFileReturnsNull() {
        assertThat(SilverOrbStateStore.load(tempDir.resolve("nope.json"))).isNull();
    }

    @Test
    @DisplayName("A corrupt state file returns null instead of crashing the bot")
    void loadCorruptFileReturnsNull() throws IOException {
        Path file = tempDir.resolve("corrupt.json");
        Files.writeString(file, "{ not valid json !!");

        assertThat(SilverOrbStateStore.load(file)).isNull();
    }

    @Test
    @DisplayName("Save creates missing parent directories")
    void saveCreatesParentDirectories() throws IOException {
        Path file = tempDir.resolve("nested/deeper/state.json");
        SilverOrbStateStore.save(file, new SilverOrbState());

        assertThat(file).exists();
        assertThat(SilverOrbStateStore.load(file)).isNotNull();
    }

    @Test
    @DisplayName("Overwriting an existing file replaces it atomically")
    void saveOverwritesExistingFile() throws IOException {
        Path file = tempDir.resolve("state.json");
        SilverOrbState first = new SilverOrbState();
        SilverOrbStateStore.save(file, first);

        SilverOrbState second = new SilverOrbState();
        second.finalSummarySent = true;
        SilverOrbStateStore.save(file, second);

        assertThat(SilverOrbStateStore.load(file).finalSummarySent).isTrue();
        // no leftover temp file
        assertThat(tempDir.resolve("state.json.tmp")).doesNotExist();
    }
}
