package com.tradingbot.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BollingerHaStateStoreTest {

    @Test
    void testSaveAndLoadRoundTrip(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("nested/deeper/state.json");
        BollingerHaStateStore.State state = new BollingerHaStateStore.State();
        state.tradeDate = LocalDate.of(2026, 10, 4);
        state.savedAt = Instant.parse("2026-10-04T06:00:00Z");
        state.tradeCount = 2;
        state.realizedPnl = new BigDecimal("123.45");
        state.locked = true;
        state.positions.add(position());
        state.openPosition = position();

        BollingerHaStateStore.save(file, state);

        assertTrue(Files.isRegularFile(file), "parent directories should be created on save");
        assertFalse(
                Files.exists(file.resolveSibling("state.json.tmp")),
                "the temp file must be moved away");

        BollingerHaStateStore.State loaded = BollingerHaStateStore.load(file);
        assertNotNull(loaded);
        assertEquals(LocalDate.of(2026, 10, 4), loaded.tradeDate);
        assertNotNull(loaded.savedAt);
        assertEquals(2, loaded.tradeCount);
        assertEquals(0, loaded.realizedPnl.compareTo(new BigDecimal("123.45")));
        assertTrue(loaded.locked);
        assertEquals(1, loaded.positions.size());
        assertEquals("NIFTY26OCT25950CE", loaded.positions.get(0).symbol);
        assertNotNull(loaded.openPosition);
        assertEquals(130, loaded.openPosition.remainingQuantity);
        assertEquals(0, loaded.openPosition.entryPrice.compareTo(new BigDecimal("141.00")));
        assertFalse(loaded.openPosition.closed);
        assertNotNull(loaded.openPosition.entryTime);
    }

    @Test
    void testLoadReturnsNullForMissingFile(@TempDir Path dir) {
        assertNull(BollingerHaStateStore.load(dir.resolve("absent.json")));
        assertNull(BollingerHaStateStore.load(null));
    }

    @Test
    void testLoadReturnsNullForCorruptSnapshot(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("corrupt.json");
        Files.writeString(file, "{ this is not json");

        assertNull(BollingerHaStateStore.load(file), "a corrupt snapshot must never block startup");
    }

    @Test
    void testSaveOverwritesPreviousSnapshot(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("state.json");
        BollingerHaStateStore.State first = new BollingerHaStateStore.State();
        first.tradeDate = LocalDate.of(2026, 10, 4);
        first.tradeCount = 1;
        BollingerHaStateStore.save(file, first);

        BollingerHaStateStore.State second = new BollingerHaStateStore.State();
        second.tradeDate = LocalDate.of(2026, 10, 5);
        second.tradeCount = 4;
        BollingerHaStateStore.save(file, second);

        BollingerHaStateStore.State loaded = BollingerHaStateStore.load(file);
        assertNotNull(loaded);
        assertEquals(LocalDate.of(2026, 10, 5), loaded.tradeDate);
        assertEquals(4, loaded.tradeCount);
        assertTrue(List.of("state.json").contains(file.getFileName().toString()));
    }

    private static BollingerHaStateStore.PositionSnapshot position() {
        BollingerHaStateStore.PositionSnapshot p = new BollingerHaStateStore.PositionSnapshot();
        p.positionId = "BHA-1";
        p.symbol = "NIFTY26OCT25950CE";
        p.token = "12345";
        p.optionType = "CE";
        p.entryPrice = new BigDecimal("141.00");
        p.stopLoss = new BigDecimal("124.00");
        p.targetPrice = new BigDecimal("175.00");
        p.totalQuantity = 130;
        p.remainingQuantity = 130;
        p.entryTime = Instant.parse("2026-10-04T04:00:00Z");
        p.closed = false;
        return p;
    }
}
