package com.tradingbot.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON persistence for Bollinger HA intraday state — trade count, realized PnL, circuit lock and
 * the open position. Written atomically (temp file + move) so a crash mid-write can never corrupt
 * the last good snapshot; a corrupt or missing snapshot loads as {@code null} and never blocks
 * startup.
 */
public final class BollingerHaStateStore {

    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .enable(SerializationFeature.INDENT_OUTPUT);

    private BollingerHaStateStore() {}

    /**
     * Persisted position fields. Public for direct Jackson binding (mirrors LvrPositionSnapshot).
     */
    public static final class PositionSnapshot {
        public String positionId;
        public String symbol;
        public String token;
        public String optionType;
        public BigDecimal entryPrice;
        public BigDecimal stopLoss;
        public BigDecimal targetPrice;
        public BigDecimal exitPrice;
        public int totalQuantity;
        public int remainingQuantity;
        public Instant entryTime;
        public Instant exitTime;
        public boolean targetHit;
        public boolean costSlActive;
        public boolean closed;
        public String exitReason;
    }

    /** The persisted slice of Bollinger HA state. */
    public static final class State {
        public LocalDate tradeDate;
        public Instant savedAt;
        public int tradeCount;
        public BigDecimal realizedPnl;
        public boolean locked;
        public PositionSnapshot openPosition;
        public List<PositionSnapshot> positions = new ArrayList<>();
    }

    /** Atomically writes {@code state} to {@code path}. Creates parent directories as needed. */
    public static void save(Path path, State state) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        MAPPER.writeValue(tmp.toFile(), state);
        try {
            Files.move(
                    tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Loads state from {@code path}; returns {@code null} when the file does not exist or cannot be
     * parsed (a corrupt snapshot must never block startup — it is logged by the caller).
     */
    public static State load(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return MAPPER.readValue(path.toFile(), State.class);
        } catch (IOException e) {
            return null;
        }
    }
}
