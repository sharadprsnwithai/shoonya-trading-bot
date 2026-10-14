package com.tradingbot.strategy.silverorb.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tradingbot.strategy.silverorb.model.SilverOrbState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON persistence for the Silver ORB paper run — day state (ORB/bias/trade/progress) plus the
 * cumulative trade log, so a bot restart mid-session replays idempotently and the month's P&L
 * survives restarts. Written atomically (temp file + move) so a crash mid-write can never corrupt
 * the last good snapshot.
 */
public final class SilverOrbStateStore {

    private static final Logger log = LoggerFactory.getLogger(SilverOrbStateStore.class);

    private static final ObjectMapper MAPPER =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .enable(SerializationFeature.INDENT_OUTPUT);

    private SilverOrbStateStore() {}

    /** Atomically writes {@code state} to {@code path}. Creates parent directories as needed. */
    public static void save(Path path, SilverOrbState state) throws IOException {
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
     * parsed (a corrupt snapshot must never block the strategy — the failure is logged here).
     *
     * @param path state file location
     * @return the persisted state, or null when absent/corrupt
     */
    public static SilverOrbState load(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return MAPPER.readValue(path.toFile(), SilverOrbState.class);
        } catch (IOException e) {
            log.warn(
                    "[SILVER-ORB] Corrupt state file at {} — starting fresh: {}",
                    path,
                    e.getMessage(),
                    e);
            return null;
        }
    }
}
