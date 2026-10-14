package com.tradingbot.marketdata;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.marketdata.repository.SqliteOptionChainSnapshotRepository;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.model.OptionContract;
import com.tradingbot.model.OptionStrike;
import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Capture rules and JSON round-trip for the 20:00 IST option-chain snapshot service.
 *
 * <p>Everything runs against a temporary SQLite file and a mocked chain service — no network.
 */
class OptionChainSnapshotServiceTest {

    private static final LocalDate SESSION = LocalDate.of(2026, 10, 14);

    @TempDir File tempDir;

    private SqliteOptionChainSnapshotRepository repository;
    private ShoonyaOptionChainService chainService;
    private OptionChainSnapshotService service;

    @BeforeEach
    void setUp() {
        repository =
                new SqliteOptionChainSnapshotRepository(
                        new File(tempDir, "snapshots.db").getAbsolutePath());
        repository.init();
        chainService = mock(ShoonyaOptionChainService.class);
        service = new OptionChainSnapshotService(repository, chainService, new ObjectMapper());
    }

    // ------------------------------------------------------------- capture rules

    @Test
    @DisplayName("Only real, funded chains are stored — synthetic, null and broken are skipped")
    void captureKeepsOnlyUsableChains() {
        OptionChainResponse good = chain("RELIANCE", 1.42, false, 5_000, 7_000);
        OptionChainResponse synthetic = chain("TCS", 1.0, true, 5_000, 5_000);
        when(chainService.getIndexOptionChain(anyString(), isNull(), anyInt(), anyBoolean()))
                .thenAnswer(
                        inv ->
                                switch (inv.getArgument(0, String.class)) {
                                    case "RELIANCE" -> good;
                                    case "TCS" -> synthetic;
                                    case "INFY" -> null;
                                    default -> throw new RuntimeException("broker timeout");
                                });

        OptionChainSnapshotService.CaptureResult result =
                service.captureUniverse(List.of("RELIANCE", "TCS", "INFY", "WIPRO"), SESSION);

        assertEquals(1, result.captured(), "only the real chain is worth storing");
        assertEquals(3, result.skipped(), "synthetic, null and failed captures are skipped");
        assertEquals(SESSION, result.sessionDate());
        assertNotNull(service.getSnapshot("RELIANCE", SESSION), "the good chain is readable");
        assertNull(
                service.getSnapshot("TCS", SESSION),
                "a synthetic chain is a fetch failure, never a stored reading");
        assertNull(service.getSnapshot("INFY", SESSION), "a null chain stores nothing");
        assertNull(service.getSnapshot("WIPRO", SESSION), "a thrown fetch stores nothing");
    }

    @Test
    @DisplayName("A chain without real OI on one side is treated as a fetch failure")
    void captureSkipsChainsWithoutFundedSides() {
        when(chainService.getIndexOptionChain(anyString(), isNull(), anyInt(), anyBoolean()))
                .thenReturn(chain("INFY", 0.9, false, 0, 5_000));

        OptionChainSnapshotService.CaptureResult result =
                service.captureUniverse(List.of("INFY"), SESSION);

        assertEquals(0, result.captured(), "zero call OI means the window is not trustworthy");
        assertEquals(1, result.skipped());
        assertNull(service.getSnapshot("INFY", SESSION));
    }

    @Test
    @DisplayName("Capture prunes snapshots beyond the retention window")
    void capturePrunesExpiredSnapshots() {
        LocalDate expired = SESSION.minusDays(OptionChainSnapshotService.RETENTION_DAYS + 5);
        repository.save(expired, "OLD", java.time.Instant.now(), 1.0, false, "{}");
        when(chainService.getIndexOptionChain(anyString(), isNull(), anyInt(), anyBoolean()))
                .thenReturn(chain("RELIANCE", 1.2, false, 1_000, 1_000));

        OptionChainSnapshotService.CaptureResult result =
                service.captureUniverse(List.of("RELIANCE"), SESSION);

        assertEquals(1, result.pruned(), "the expired row is deleted");
        assertNull(service.getSnapshot("OLD", expired), "expired session is gone");
        assertNotNull(service.getSnapshot("RELIANCE", SESSION), "today's row survives");
    }

    @Test
    @DisplayName("An empty universe is a no-op, not an error")
    void captureEmptyUniverseIsNoop() {
        OptionChainSnapshotService.CaptureResult result = service.captureUniverse(List.of(), null);

        assertEquals(0, result.captured());
        assertEquals(0, result.skipped());
        assertNotNull(result.sessionDate(), "session falls back to today (IST)");
    }

    @Test
    @DisplayName("A read-only wiring refuses to capture instead of failing halfway")
    void readOnlyWiringRefusesToCapture() {
        OptionChainSnapshotService readOnly =
                new OptionChainSnapshotService(repository, new ObjectMapper());

        assertThrows(
                IllegalStateException.class,
                () -> readOnly.captureUniverse(List.of("RELIANCE"), SESSION),
                "capturing without a chain service must fail loudly");
    }

    // ------------------------------------------------------------- read path

    @Test
    @DisplayName("A stored chain survives the JSON round trip with its window intact")
    void snapshotRoundTripsThroughJson() throws Exception {
        OptionChainResponse original = chain("RELIANCE", 1.37, false, 12_000, 9_000);
        service.save("RELIANCE", SESSION, original);

        OptionChainResponse read = service.getSnapshot("RELIANCE", SESSION);

        assertNotNull(read, "the snapshot must deserialize");
        assertEquals("RELIANCE", read.underlying());
        assertEquals(1.37, read.pcr(), 1e-9, "PCR is stored unrounded");
        assertEquals(12_000, read.totalCallOi());
        assertEquals(9_000, read.totalPutOi());
        assertEquals(original.strikes().size(), read.strikes().size(), "the window is complete");
        assertEquals(
                original.maxCallOiStrike(),
                read.maxCallOiStrike(),
                "max-OI resistance survives the round trip");
        assertEquals(
                original.maxPutOiStrike(),
                read.maxPutOiStrike(),
                "max-OI support survives the round trip");
        assertFalse(read.isSynthetic());
        assertTrue(
                read.strikes().get(1).isAtm(),
                "the ATM marker on the stored window survives the round trip");
    }

    @Test
    @DisplayName("Lookups are exact: no session, no symbol or no row all mean null")
    void snapshotLookupIsExact() throws Exception {
        service.save("RELIANCE", SESSION, chain("RELIANCE", 1.0, false, 100, 100));

        assertNotNull(service.getSnapshot("RELIANCE", SESSION));
        assertNull(
                service.getSnapshot("RELIANCE", SESSION.plusDays(1)),
                "a different session must never fall back to another day's snapshot");
        assertNull(service.getSnapshot("TCS", SESSION), "unknown symbol is a miss");
        assertNull(service.getSnapshot(null, SESSION), "null symbol is a miss");
        assertNull(service.getSnapshot("RELIANCE", null), "null session is a miss");
    }

    // ------------------------------------------------------------- fixtures

    /**
     * A funded, non-synthetic chain with an ATM window large enough to carry max-OI strikes.
     *
     * @param underlying the underlying symbol
     * @param pcr the put-call ratio of the window
     * @param synthetic whether to build the fabricated fallback instead
     * @param callOi open interest applied to the ATM call
     * @param putOi open interest applied to the ATM put
     * @return the candidate chain
     */
    private static OptionChainResponse chain(
            String underlying, double pcr, boolean synthetic, long callOi, long putOi) {
        List<OptionStrike> strikes = new ArrayList<>();
        strikes.add(
                new OptionStrike(
                        new BigDecimal("1400"),
                        false,
                        contract("CE", callOi / 2),
                        contract("PE", putOi / 2)));
        strikes.add(
                new OptionStrike(
                        new BigDecimal("1450"),
                        true,
                        contract("CE", callOi),
                        contract("PE", putOi)));
        strikes.add(
                new OptionStrike(
                        new BigDecimal("1500"),
                        false,
                        contract("CE", callOi / 4),
                        contract("PE", putOi / 4)));
        return new OptionChainResponse(
                underlying,
                new BigDecimal("1452"),
                new BigDecimal("1450"),
                underlying + "-FUT",
                3,
                callOi,
                putOi,
                pcr,
                strikes,
                synthetic);
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
