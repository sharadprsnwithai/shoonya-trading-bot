package com.tradingbot.strategy.breakoutvwap.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradingbot.strategy.breakoutvwap.model.BvsBias;
import com.tradingbot.strategy.breakoutvwap.model.BvsBucket;
import com.tradingbot.strategy.breakoutvwap.model.BvsSetupState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Round-trip, missing-file and corrupt-file contract for the BVS disk state. */
class BvsStateStoreTest {

    @TempDir Path tempDir;

    private static BvsDailyState sampleState() {
        BvsDailyState state = new BvsDailyState();
        state.date = LocalDate.of(2026, 10, 22);
        state.savedAt = Instant.parse("2026-10-22T05:30:00Z");
        state.stoodDownToday = false;

        BvsSetupSnapshot setup = new BvsSetupSnapshot();
        setup.symbol = "RELIANCE";
        setup.state = BvsSetupState.BIAS_ARMED;
        setup.bias = BvsBias.LONG;
        setup.sessionRangeHigh = 25000.0;
        setup.sessionRangeLow = 24800.0;
        setup.pdh = 25100.0;
        setup.pdl = 24700.0;
        setup.triggerHigh = 25050.0;
        setup.triggerLow = 24990.0;
        setup.triggerArmedAt = Instant.parse("2026-10-22T05:35:00Z");
        setup.entryConsumed = false;
        state.setups.put("RELIANCE", setup);

        BvsPositionSnapshot open = new BvsPositionSnapshot();
        open.symbol = "RELIANCE";
        open.brokerTradingSymbol = "RELIANCE23OCTFUT";
        open.token = "4321";
        open.direction = BvsBias.LONG;
        open.entryPrice = 25000.0;
        open.stopPrice = 25000.0;
        open.rValue = 40.0;
        open.targetPrice = 25100.0;
        open.totalQuantity = 25;
        open.remainingQuantity = 12;
        open.partialBooked = true;
        open.entryTime = Instant.parse("2026-10-22T05:40:00Z");
        open.tradeId = "BVS-ABC12345";
        state.openPositions.add(open);

        BvsPositionSnapshot closed = new BvsPositionSnapshot();
        closed.symbol = "TCS";
        closed.brokerTradingSymbol = "TCS23OCTFUT";
        closed.token = "8765";
        closed.direction = BvsBias.SHORT;
        closed.entryPrice = 4000.0;
        closed.stopPrice = 4040.0;
        closed.rValue = 40.0;
        closed.targetPrice = 3900.0;
        closed.totalQuantity = 25;
        closed.remainingQuantity = 25;
        closed.partialBooked = false;
        closed.entryTime = Instant.parse("2026-10-22T06:00:00Z");
        closed.tradeId = "BVS-DEF67890";
        state.tradeHistory.add(closed);

        state.universeByBucket.put(BvsBucket.GAINERS, List.of("RELIANCE", "TCS"));
        state.universeByBucket.put(BvsBucket.OI_SPURT, List.of("HDFCBANK"));
        return state;
    }

    @Test
    @DisplayName("save then load returns every field of the state")
    void saveLoadRoundTrip() throws IOException {
        Path file = tempDir.resolve("bvs-state.json");

        BvsStateStore.save(file, sampleState());
        BvsDailyState loaded = BvsStateStore.load(file);

        assertThat(loaded).isNotNull();
        assertThat(loaded.date).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(loaded.savedAt).isEqualTo(Instant.parse("2026-10-22T05:30:00Z"));
        assertThat(loaded.stoodDownToday).isFalse();

        BvsSetupSnapshot setup = loaded.setups.get("RELIANCE");
        assertThat(setup).isNotNull();
        assertThat(setup.state).isEqualTo(BvsSetupState.BIAS_ARMED);
        assertThat(setup.bias).isEqualTo(BvsBias.LONG);
        assertThat(setup.sessionRangeHigh).isEqualTo(25000.0);
        assertThat(setup.sessionRangeLow).isEqualTo(24800.0);
        assertThat(setup.pdh).isEqualTo(25100.0);
        assertThat(setup.pdl).isEqualTo(24700.0);
        assertThat(setup.triggerHigh).isEqualTo(25050.0);
        assertThat(setup.triggerLow).isEqualTo(24990.0);
        assertThat(setup.triggerArmedAt).isEqualTo(Instant.parse("2026-10-22T05:35:00Z"));
        assertThat(setup.entryConsumed).isFalse();

        assertThat(loaded.openPositions).hasSize(1);
        BvsPositionSnapshot open = loaded.openPositions.get(0);
        assertThat(open.symbol).isEqualTo("RELIANCE");
        assertThat(open.brokerTradingSymbol).isEqualTo("RELIANCE23OCTFUT");
        assertThat(open.token).isEqualTo("4321");
        assertThat(open.direction).isEqualTo(BvsBias.LONG);
        assertThat(open.entryPrice).isEqualTo(25000.0);
        assertThat(open.stopPrice).isEqualTo(25000.0);
        assertThat(open.rValue).isEqualTo(40.0);
        assertThat(open.targetPrice).isEqualTo(25100.0);
        assertThat(open.totalQuantity).isEqualTo(25);
        assertThat(open.remainingQuantity).isEqualTo(12);
        assertThat(open.partialBooked).isTrue();
        assertThat(open.entryTime).isEqualTo(Instant.parse("2026-10-22T05:40:00Z"));
        assertThat(open.tradeId).isEqualTo("BVS-ABC12345");

        assertThat(loaded.tradeHistory).hasSize(1);
        assertThat(loaded.tradeHistory.get(0).symbol).isEqualTo("TCS");
        assertThat(loaded.tradeHistory.get(0).direction).isEqualTo(BvsBias.SHORT);
        assertThat(loaded.tradeHistory.get(0).tradeId).isEqualTo("BVS-DEF67890");

        assertThat(loaded.universeByBucket.get(BvsBucket.GAINERS))
                .containsExactly("RELIANCE", "TCS");
        assertThat(loaded.universeByBucket.get(BvsBucket.OI_SPURT)).containsExactly("HDFCBANK");
    }

    @Test
    @DisplayName("a missing file loads as null")
    void loadReturnsNullOnMissingFile() {
        assertThat(BvsStateStore.load(tempDir.resolve("nope.json"))).isNull();
    }

    @Test
    @DisplayName("a corrupt file loads as null without throwing")
    void loadReturnsNullOnCorruptFile() throws IOException {
        Path file = tempDir.resolve("corrupt.json");
        Files.writeString(file, "not json");

        assertThat(BvsStateStore.load(file)).isNull();
    }

    @Test
    @DisplayName("saving twice to the same path keeps the second state")
    void saveIsAtomicOverwrite() throws IOException {
        Path file = tempDir.resolve("bvs-state.json");
        BvsDailyState first = sampleState();
        first.date = LocalDate.of(2026, 10, 21);
        BvsDailyState second = sampleState();

        BvsStateStore.save(file, first);
        BvsStateStore.save(file, second);
        BvsDailyState loaded = BvsStateStore.load(file);

        assertThat(loaded.date).isEqualTo(LocalDate.of(2026, 10, 22));
    }
}
