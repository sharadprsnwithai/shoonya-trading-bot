package com.tradingbot.marketdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.marketdata.repository.SqliteOptionChainSnapshotRepository;
import com.tradingbot.marketdata.repository.SqliteOptionChainSnapshotRepository.SnapshotRow;
import com.tradingbot.model.OptionChainResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Captures and serves closing option-chain snapshots for the F&O audit universe.
 *
 * <p>The 20:00 IST post-close job runs {@link #captureUniverse} for every symbol in {@link
 * com.tradingbot.util.NiftySectorRegistry#getFlattenedUniverse()}; the LVR gate replay audit then
 * reads those snapshots back via {@link #getSnapshot} instead of hitting the Shoonya API, so the
 * audit can run any time after the close — including the overnight broker maintenance window.
 *
 * <p>Only usable chains are stored: a synthetic (mock) chain or one without real call/put OI is a
 * fetch failure, and storing it would launder a failure into an apparently valid reading. Symbols
 * whose capture fails simply have no row, and the consumer falls back to a live fetch.
 *
 * <p>Live trading never reads this store — {@code resolveLiveOptionChainLevels} still fetches the
 * chain in real time for the armed symbol only, so no stale data can reach a live entry gate.
 */
@Service
public class OptionChainSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(OptionChainSnapshotService.class);

    /** Snapshots older than this many sessions are pruned on every capture run. */
    public static final int RETENTION_DAYS = 30;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final SqliteOptionChainSnapshotRepository repository;
    private final ShoonyaOptionChainService optionChainService;
    private final ObjectMapper objectMapper;

    @Autowired
    public OptionChainSnapshotService(
            SqliteOptionChainSnapshotRepository repository,
            ShoonyaOptionChainService optionChainService,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.optionChainService = optionChainService;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    /** Read-only wiring (audit/tests): serves {@link #getSnapshot} but cannot capture. */
    public OptionChainSnapshotService(
            SqliteOptionChainSnapshotRepository repository, ObjectMapper objectMapper) {
        this(repository, null, objectMapper);
    }

    /** Outcome of one capture run, for scheduler logging and tests. */
    public record CaptureResult(LocalDate sessionDate, int captured, int skipped, int pruned) {}

    /**
     * Fetches the closing option chain for every symbol and stores the usable ones under the given
     * session date.
     *
     * <p>Each symbol is independent: a null chain, a synthetic chain, a chain without real OI or a
     * thrown exception only skips that symbol and the run continues. Retention pruning runs at the
     * end of every successful call.
     *
     * @param symbols the underlyings to capture, e.g. the flattened sector universe
     * @param sessionDate the trading session the chains reflect; {@code null} means today (IST)
     * @return capture statistics; {@code captured} is the number of rows written
     */
    public CaptureResult captureUniverse(List<String> symbols, LocalDate sessionDate) {
        if (optionChainService == null) {
            throw new IllegalStateException(
                    "No option chain service wired — cannot capture snapshots");
        }
        if (symbols == null || symbols.isEmpty()) {
            return new CaptureResult(
                    sessionDate != null ? sessionDate : LocalDate.now(IST), 0, 0, 0);
        }

        LocalDate session = sessionDate != null ? sessionDate : LocalDate.now(IST);
        int captured = 0;
        int skipped = 0;

        for (int i = 0; i < symbols.size(); i++) {
            String symbol = symbols.get(i);
            try {
                OptionChainResponse chain =
                        optionChainService.getIndexOptionChain(
                                symbol, null, ShoonyaOptionChainService.PCR_STRIKE_WINDOW, true);
                if (isUsable(chain)) {
                    save(symbol, session, chain);
                    captured++;
                } else {
                    skipped++;
                    log.debug(
                            "[CHAIN-SNAPSHOT] Skipping {} for {}: {}",
                            symbol,
                            session,
                            chain == null ? "no chain returned" : "synthetic/empty chain");
                }
            } catch (Exception e) {
                skipped++;
                log.warn("[CHAIN-SNAPSHOT] Capture failed for {}: {}", symbol, e.getMessage());
            }

            if ((i + 1) % 25 == 0) {
                log.info(
                        "[CHAIN-SNAPSHOT] Progress: {}/{} symbols (captured={}, skipped={})",
                        i + 1,
                        symbols.size(),
                        captured,
                        skipped);
            }
        }

        int pruned = repository.deleteSessionDatesBefore(session.minusDays(RETENTION_DAYS));
        log.info(
                "[CHAIN-SNAPSHOT] Session {}: captured={}, skipped={}, pruned={} ({} stored for"
                        + " session)",
                session,
                captured,
                skipped,
                pruned,
                repository.countForSession(session));
        return new CaptureResult(session, captured, skipped, pruned);
    }

    /**
     * Stores one chain under the session date it reflects.
     *
     * @param symbol the underlying; trimmed and upper-cased by the repository
     * @param sessionDate the trading session the chain reflects
     * @param chain the chain to serialise and persist
     * @throws JsonProcessingException when the chain cannot be serialised
     */
    public void save(String symbol, LocalDate sessionDate, OptionChainResponse chain)
            throws JsonProcessingException {
        String json = objectMapper.writeValueAsString(chain);
        repository.save(
                sessionDate,
                symbol,
                java.time.Instant.now(),
                chain.pcr(),
                chain.isSynthetic(),
                json);
    }

    /**
     * Reads a stored snapshot for the given session date.
     *
     * <p>The lookup is an exact (session date, symbol) match — there is no "latest snapshot"
     * fallback, so a back-dated replay can never silently receive data from the wrong day. A miss,
     * an unreadable payload or a {@code null} argument all return {@code null} and the caller
     * decides whether to fall back to a live fetch.
     *
     * @param symbol the underlying to look up
     * @param sessionDate the trading session to probe
     * @return the stored chain, or {@code null} when no usable snapshot exists
     */
    public OptionChainResponse getSnapshot(String symbol, LocalDate sessionDate) {
        if (symbol == null || sessionDate == null) {
            return null;
        }
        SnapshotRow row = repository.find(sessionDate, symbol).orElse(null);
        if (row == null) {
            return null;
        }
        try {
            return objectMapper.readValue(row.chainJson(), OptionChainResponse.class);
        } catch (Exception e) {
            log.warn(
                    "[CHAIN-SNAPSHOT] Stored snapshot for {} on {} is unreadable: {}",
                    symbol,
                    sessionDate,
                    e.getMessage());
            return null;
        }
    }

    /**
     * @return the backing repository, for coverage reporting and diagnostics
     */
    public SqliteOptionChainSnapshotRepository getRepository() {
        return repository;
    }

    /**
     * Mirrors the fail-closed criteria the live LVR path applies to a freshly fetched chain: a
     * synthetic chain or one without real call/put OI is a fetch failure, never a reading.
     *
     * @param chain the candidate chain
     * @return {@code true} when the chain is real, non-empty and safe to persist
     */
    private static boolean isUsable(OptionChainResponse chain) {
        return chain != null
                && !chain.isSynthetic()
                && chain.totalCallOi() > 0
                && chain.totalPutOi() > 0
                && chain.strikes() != null
                && !chain.strikes().isEmpty();
    }
}
