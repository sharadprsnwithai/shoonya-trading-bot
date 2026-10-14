package com.tradingbot.marketdata;

import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.model.OptionChainResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.LongAdder;

/**
 * Test-scoped {@link ShoonyaOptionChainService} that serves the closing option-chain snapshot
 * captured by the 20:00 IST post-close job instead of hitting the Shoonya API, falling back to a
 * live fetch when no snapshot exists for the replayed session.
 *
 * <p>Only the {@code LvrTodayGateReplayAuditTest} harness wires this in — it lives in test sources
 * on purpose so production can never read a stale chain. The live strategy keeps constructing the
 * plain {@code ShoonyaOptionChainService} and fetching in real time for the armed symbol only.
 *
 * <p>The single interception point is the six-argument {@link #getOptionChain}, which every chain
 * read funnels through (LVR's PCR/S&amp;R resolve, {@code getIndexOptionChain}, {@code
 * getNifty50OptionChain}). The snapshot ignores the caller's pinned strike and futures contract:
 * the stored chain is a complete closing chain anchored at the capture-time ATM, and a snapshot hit
 * is returned before any network or contract-resolution work happens, so a replay can run during
 * the overnight broker maintenance window.
 *
 * <p>Thread-safe: the audit shares one instance across all replay workers, so the hit/miss counters
 * are {@link LongAdder}s and the session date is volatile.
 */
public class SnapshotOptionChainService extends ShoonyaOptionChainService {

    private final OptionChainSnapshotService snapshotService;

    /** The session being replayed; snapshots are looked up under exactly this date. */
    private volatile LocalDate sessionDate;

    private final LongAdder snapshotHits = new LongAdder();
    private final LongAdder liveFetches = new LongAdder();
    private final LongAdder syntheticLive = new LongAdder();

    /**
     * @ * @param config Shoonya config (also used by the live fallback path)
     *
     * @param authenticator Shoonya authenticator for the live fallback path
     * @param marketDataService market data service for the live fallback path
     * @param snapshotService store holding the post-close snapshots; {@code null} disables snapshot
     *     reads entirely and makes every call a live fetch
     */
    public SnapshotOptionChainService(
            ShoonyaConfig config,
            ShoonyaAuthenticator authenticator,
            ShoonyaMarketDataService marketDataService,
            OptionChainSnapshotService snapshotService) {
        super(config, authenticator, marketDataService);
        this.snapshotService = snapshotService;
    }

    /**
     * Pins the trading session whose snapshots should be served.
     *
     * <p>The audit calls this once it has resolved the session from the 5-minute candles; while it
     * is {@code null} every read goes to the live API.
     *
     * @param sessionDate the resolved session date, or {@code null} to force live fetches
     */
    public void setSessionDate(LocalDate sessionDate) {
        this.sessionDate = sessionDate;
    }

    /**
     * @return the currently pinned session date, or {@code null} when serving live data only
     */
    public LocalDate getSessionDate() {
        return sessionDate;
    }

    /**
     * Serves the pinned session's snapshot when one exists, otherwise falls through to the real API
     * exactly as the production service would (including its fail-closed synthetic chain).
     *
     * @param underlying the underlying symbol, e.g. {@code RELIANCE}
     * @param futSymbol the near-month futures tsym (ignored on a snapshot hit)
     * @param futToken the futures token (ignored on a snapshot hit)
     * @param explicitStrike the caller's pinned ATM (ignored on a snapshot hit — the snapshot is
     *     anchored at the capture-time ATM)
     * @param count the requested strike-window half-width (ignored on a snapshot hit)
     * @param fetchQuotes whether to fetch per-strike quotes (ignored on a snapshot hit)
     * @return the snapshot when available and readable, otherwise the live fetch result
     */
    @Override
    public OptionChainResponse getOptionChain(
            String underlying,
            String futSymbol,
            String futToken,
            BigDecimal explicitStrike,
            int count,
            boolean fetchQuotes) {
        OptionChainResponse snapshot = resolveSnapshot(underlying);
        if (snapshot != null) {
            snapshotHits.increment();
            return snapshot;
        }
        liveFetches.increment();
        OptionChainResponse live =
                super.getOptionChain(
                        underlying, futSymbol, futToken, explicitStrike, count, fetchQuotes);
        if (live != null && live.isSynthetic()) {
            syntheticLive.increment();
        }
        return live;
    }

    /**
     * Looks up a snapshot for the pinned session only — an exact (session date, symbol) match with
     * no "latest" fallback, so a back-dated replay can never receive the wrong day's data.
     *
     * @param underlying the underlying to look up
     * @return the stored chain, or {@code null} for a miss, a null session date or a null store
     */
    private OptionChainResponse resolveSnapshot(String underlying) {
        if (snapshotService == null || sessionDate == null || underlying == null) {
            return null;
        }
        return snapshotService.getSnapshot(underlying, sessionDate);
    }

    /**
     * @return how many chain reads were served from the post-close snapshot store
     */
    public long snapshotHits() {
        return snapshotHits.sum();
    }

    /**
     * @return how many chain reads fell back to the live Shoonya API
     */
    public long liveFetches() {
        return liveFetches.sum();
    }

    /**
     * @return how many live fallbacks came back {@code synthetic} — fail-closed fetch failures with
     *     no usable PCR, which the replay defers (RETRY) instead of discarding
     */
    public long syntheticLiveReads() {
        return syntheticLive.sum();
    }

    /**
     * @return total chain reads across every replay pass and worker
     */
    public long totalReads() {
        return snapshotHits() + liveFetches();
    }

    /** Resets the coverage counters — used between audit passes when per-pass stats are needed. */
    public void resetCounters() {
        snapshotHits.reset();
        liveFetches.reset();
        syntheticLive.reset();
    }
}
