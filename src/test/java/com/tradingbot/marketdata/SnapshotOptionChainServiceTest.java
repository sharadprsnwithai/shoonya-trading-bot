package com.tradingbot.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.marketdata.repository.SqliteOptionChainSnapshotRepository;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.model.OptionContract;
import com.tradingbot.model.OptionStrike;
import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Snapshot-first behaviour of the audit-only {@link SnapshotOptionChainService}.
 *
 * <p>The live fallback is proven without network by disabling Shoonya on the wrapped config: the
 * super implementation then returns its synthetic mock chain, so "the call reached the live path"
 * is observable as {@code isSynthetic() == true} — while a snapshot hit returns the stored real
 * chain even though the config says Shoonya is off.
 */
class SnapshotOptionChainServiceTest {

    private static final LocalDate SESSION = LocalDate.of(2026, 10, 14);

    @TempDir File tempDir;

    private OptionChainSnapshotService snapshotService;
    private SnapshotOptionChainService service;

    @BeforeEach
    void setUp() throws Exception {
        SqliteOptionChainSnapshotRepository repository =
                new SqliteOptionChainSnapshotRepository(
                        new File(tempDir, "snapshots.db").getAbsolutePath());
        repository.init();
        snapshotService = new OptionChainSnapshotService(repository, new ObjectMapper());

        ShoonyaConfig config = new ShoonyaConfig();
        config.setEnabled(false); // live path => synthetic mock chain, never a network call
        service =
                new SnapshotOptionChainService(
                        config,
                        mock(ShoonyaAuthenticator.class),
                        mock(ShoonyaMarketDataService.class),
                        snapshotService);
    }

    @Test
    @DisplayName("A stored chain for the pinned session is served instead of the live path")
    void snapshotHitIsServed() throws Exception {
        snapshotService.save("RELIANCE", SESSION, realChain("RELIANCE", 1.42));
        service.setSessionDate(SESSION);

        OptionChainResponse chain = readChain("RELIANCE");

        assertFalse(
                chain.isSynthetic(), "the stored real chain must win over the disabled live path");
        assertEquals(1.42, chain.pcr(), 1e-9, "the stored PCR is what the gate would see");
        assertEquals(1, service.snapshotHits(), "one snapshot hit");
        assertEquals(0, service.liveFetches(), "no live fallback on a hit");
        assertEquals(1, service.totalReads());
    }

    @Test
    @DisplayName("With no pinned session every read goes to the live path")
    void withoutSessionDateFallsBackToLive() throws Exception {
        snapshotService.save("RELIANCE", SESSION, realChain("RELIANCE", 1.42));
        // setSessionDate deliberately not called

        OptionChainResponse chain = readChain("RELIANCE");

        assertTrue(chain.isSynthetic(), "no pinned session means the live path runs");
        assertEquals(0, service.snapshotHits());
        assertEquals(1, service.liveFetches());
    }

    @Test
    @DisplayName("A session with no snapshot falls back to live instead of borrowing another day")
    void missFallsBackToLive() throws Exception {
        snapshotService.save("RELIANCE", SESSION, realChain("RELIANCE", 1.42));
        service.setSessionDate(SESSION.plusDays(1)); // replaying a session that has no snapshot

        OptionChainResponse chain = readChain("RELIANCE");

        assertTrue(
                chain.isSynthetic(),
                "an exact-key miss must fall back to live, never serve the wrong day");
        assertEquals(0, service.snapshotHits());
        assertEquals(1, service.liveFetches());
    }

    @Test
    @DisplayName("An unpinned symbol falls back to live even when the session is pinned")
    void unknownSymbolFallsBackToLive() throws Exception {
        snapshotService.save("RELIANCE", SESSION, realChain("RELIANCE", 1.42));
        service.setSessionDate(SESSION);

        OptionChainResponse chain = readChain("TCS");

        assertTrue(chain.isSynthetic(), "symbols without a snapshot fetch live");
        assertEquals(0, service.snapshotHits());
        assertEquals(1, service.liveFetches());
    }

    @Test
    @DisplayName("A null snapshot store disables snapshot reads without breaking the live path")
    void nullStoreStaysLiveOnly() {
        ShoonyaConfig config = new ShoonyaConfig();
        config.setEnabled(false);
        SnapshotOptionChainService liveOnly =
                new SnapshotOptionChainService(
                        config,
                        mock(ShoonyaAuthenticator.class),
                        mock(ShoonyaMarketDataService.class),
                        null);
        liveOnly.setSessionDate(SESSION);

        OptionChainResponse chain = readChain(liveOnly, "RELIANCE");

        assertTrue(chain.isSynthetic(), "a null store degrades to the plain production service");
        assertEquals(1, liveOnly.liveFetches());
    }

    @Test
    @DisplayName("The pinned session date is reported back for diagnostics")
    void sessionDateRoundTrips() {
        assertNull(service.getSessionDate(), "unpinned by default");

        service.setSessionDate(SESSION);
        assertEquals(SESSION, service.getSessionDate());

        service.resetCounters();
        assertEquals(0, service.snapshotHits());
        assertEquals(0, service.liveFetches());
    }

    // ------------------------------------------------------------- fixtures

    /** One chain read through the service under test. */
    private OptionChainResponse readChain(String underlying) {
        return readChain(service, underlying);
    }

    /** One chain read through an arbitrary instance. */
    private static OptionChainResponse readChain(
            SnapshotOptionChainService service, String underlying) {
        return service.getOptionChain(underlying, underlying + "-FUT", "1", null, 4, true);
    }

    /** A funded, non-synthetic closing chain. */
    private static OptionChainResponse realChain(String underlying, double pcr) {
        List<OptionStrike> strikes =
                List.of(
                        new OptionStrike(
                                new BigDecimal("1450"),
                                true,
                                contract("CE", 5_000),
                                contract("PE", 7_000)),
                        new OptionStrike(
                                new BigDecimal("1500"),
                                false,
                                contract("CE", 1_000),
                                contract("PE", 2_000)));
        return new OptionChainResponse(
                underlying,
                new BigDecimal("1452"),
                new BigDecimal("1450"),
                underlying + "-FUT",
                2,
                6_000,
                9_000,
                pcr,
                strikes);
    }

    /** One CE/PE pair at a strike. */
    private static OptionContract contract(String optionType, long openInterest) {
        return new OptionContract(
                "X" + optionType,
                "1",
                optionType,
                new BigDecimal("1450"),
                new BigDecimal("10.5"),
                openInterest,
                100L,
                null,
                null,
                null);
    }
}
