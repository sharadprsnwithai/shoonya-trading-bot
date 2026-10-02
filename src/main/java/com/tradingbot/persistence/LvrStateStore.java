package com.tradingbot.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tradingbot.model.strategy.LvrPositionSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * M10: JSON persistence for LVR daily state — open positions, trade history, exhausted symbols,
 * carried P&L and the breaker latch. Written atomically (temp file + move) so a crash mid-write
 * can never corrupt the last good snapshot.
 */
public final class LvrStateStore {

    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .enable(SerializationFeature.INDENT_OUTPUT);

    private LvrStateStore() {}

    /** The persisted slice of LVR state. Public fields for direct Jackson binding. */
    public static final class DailyState {
        public LocalDate date;
        public Instant savedAt;
        public List<LvrPositionSnapshot> openPositions = new ArrayList<>();
        public List<LvrPositionSnapshot> tradeHistory = new ArrayList<>();
        public List<String> exhaustedSymbols = new ArrayList<>();
        public double archivedRealizedPnl = 0.0;
        public LocalDate archiveDate;
        public boolean circuitBreakerTripped;
        public boolean standDownToday;
    }

    /** Atomically writes {@code state} to {@code path}. Creates parent directories as needed. */
    public static void save(Path path, DailyState state) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        MAPPER.writeValue(tmp.toFile(), state);
        try {
            Files.move(
                    tmp,
                    path,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Loads state from {@code path}; returns {@code null} when the file does not exist or cannot
     * be parsed (a corrupt snapshot must never block startup — it is logged by the caller).
     */
    public static DailyState load(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return MAPPER.readValue(path.toFile(), DailyState.class);
        } catch (IOException e) {
            return null;
        }
    }

    /** Writes a dated audit copy of the state (used on daily reset / EOD). */
    public static void saveDatedArchive(Path stateFilePath, DailyState state, LocalDate date)
            throws IOException {
        Path dir = stateFilePath.toAbsolutePath().getParent();
        if (dir == null) {
            dir = Path.of(".");
        }
        Path archive = dir.resolve("lvr-snapshot-" + date + ".json");
        save(archive, state);
    }
}
