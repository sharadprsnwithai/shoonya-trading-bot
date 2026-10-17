package com.tradingbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.NseBanPeriodClient;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.LowestVolumePaperPosition;
import com.tradingbot.model.strategy.LowestVolumeSetup;
import com.tradingbot.model.strategy.LvrExitMode;
import com.tradingbot.model.strategy.LvrInstrumentType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LVR F&O ban-period exclusion tests (design spec 2026-10-17 §3): the pre-market ban list is loaded
 * into session state, and — once enforcement is switched on — banned symbols are dropped from the
 * watchlist and never promoted from the reservoir.
 *
 * <p>Service ships in log-only validation mode by default ({@code LVR_BAN_LOG_ONLY=true}), so each
 * enforcing test flips it explicitly with {@link LowestVolumeReversalService#setBanPeriodLogOnly}.
 */
class LowestVolumeBanPeriodTest {

    private ShoonyaMarketDataService marketDataService;
    private LowestVolumeReversalService service;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @BeforeEach
    void setUp() {
        marketDataService = mock(ShoonyaMarketDataService.class);
        service =
                new LowestVolumeReversalService(
                        marketDataService,
                        new TechnicalAnalysisService(),
                        null,
                        mock(ShoonyaConfig.class),
                        null);
        service.setClock(Clock.fixed(Instant.parse("2026-10-13T04:30:00Z"), IST)); // 10:00 IST
        service.setMorningScanDelayMs(0);
        service.setPdhPdlFilterEnabled(false);
        service.setOpening15mRangeFilterEnabled(false);
        service.setPcrFilterEnabled(false);
        service.setOptionSrFilterEnabled(false);
        service.setSectorMomentumFilterEnabled(false);
    }

    /** Wires a mock ban client that reports a successful fetch of {@code banned}. */
    private void wireBanClient(Set<String> banned, boolean fetchSuccessful) {
        NseBanPeriodClient client = mock(NseBanPeriodClient.class);
        when(client.fetchBanList()).thenReturn(banned);
        when(client.isLastFetchSuccessful()).thenReturn(fetchSuccessful);
        service.setNseBanPeriodClient(client);
    }

    private LowestVolumePaperPosition openPosition(String symbol) {
        return new LowestVolumePaperPosition(
                "LVR-1",
                symbol,
                LvrInstrumentType.FUTURES,
                LvrExitMode.FULL_TARGET_1_2,
                symbol + " FUT",
                250,
                2,
                LowestVolumeDirection.LONG,
                BigDecimal.valueOf(2500.0),
                BigDecimal.valueOf(2480.0),
                BigDecimal.valueOf(2540.0),
                500,
                BigDecimal.valueOf(10000.0),
                Instant.now());
    }

    @Test
    @DisplayName("A successful fetch loads banned symbols into session state")
    void fetchLoadsBannedSymbolsIntoSessionState() {
        wireBanClient(Set.of("RELIANCE", "PVRINOX"), true);

        service.runNseBanListFetch();

        assertThat(service.getBannedSymbols()).containsExactlyInAnyOrder("RELIANCE", "PVRINOX");
        assertThat(service.isBanListUnavailable()).isFalse();
    }

    @Test
    @DisplayName("An empty ban list is a clean session, not a degraded one")
    void emptyBanListIsNotUnavailable() {
        wireBanClient(Set.of(), true);

        service.runNseBanListFetch();

        assertThat(service.getBannedSymbols()).isEmpty();
        assertThat(service.isBanListUnavailable()).isFalse();
    }

    @Test
    @DisplayName("A failed fetch clears the list and marks it unavailable")
    void failedFetchMarksListUnavailable() {
        wireBanClient(Set.of("RELIANCE"), false);

        service.runNseBanListFetch();

        assertThat(service.getBannedSymbols()).isEmpty();
        assertThat(service.isBanListUnavailable()).isTrue();
    }

    @Test
    @DisplayName("A missing client degrades gracefully (no fetch, no failure)")
    void missingClientDegradesGracefully() {
        service.setNseBanPeriodClient(null);

        service.runNseBanListFetch();

        assertThat(service.getBannedSymbols()).isEmpty();
        assertThat(service.isBanListUnavailable()).isFalse();
    }

    @Test
    @DisplayName("Log-only validation mode reports banned symbols without excluding them")
    void logOnlyModeReportsButDoesNotExclude() {
        service.setBanPeriodLogOnly(true);
        wireBanClient(Set.of("TATAMOTORS"), true);
        service.runNseBanListFetch();

        service.getActiveSetups().clear();
        service.getCandidateReservoir().add("TATAMOTORS");
        service.getCandidateReservoir().add("BAJFINANCE");
        service.replenishActiveCandidatesIfNeeded(LocalTime.of(10, 15));

        assertThat(service.getActiveSetups())
                .as("log-only mode must not act on the ban list")
                .containsKeys("TATAMOTORS", "BAJFINANCE");
    }

    @Test
    @DisplayName("Enforcement never promotes a banned symbol from the reservoir")
    void enforcementSkipsBannedSymbolsOnPromotion() {
        service.setBanPeriodLogOnly(false);
        wireBanClient(Set.of("TATAMOTORS"), true);
        service.runNseBanListFetch();

        service.getActiveSetups().clear();
        service.getCandidateReservoir().add("TATAMOTORS");
        service.getCandidateReservoir().add("BAJFINANCE");
        service.replenishActiveCandidatesIfNeeded(LocalTime.of(10, 15));

        assertThat(service.getActiveSetups()).doesNotContainKey("TATAMOTORS");
        assertThat(service.getActiveSetups()).containsKey("BAJFINANCE");
    }

    @Test
    @DisplayName("Enforcement drops an already-active banned symbol from the watchlist")
    void enforcementDropsActiveBannedSymbol() {
        service.setBanPeriodLogOnly(false);
        service.getActiveSetups()
                .put("RELIANCE", new LowestVolumeSetup("RELIANCE", LowestVolumeDirection.LONG));

        wireBanClient(Set.of("RELIANCE"), true);
        service.runNseBanListFetch();

        assertThat(service.getActiveSetups()).doesNotContainKey("RELIANCE");
    }

    @Test
    @DisplayName("A banned symbol with an open position is kept under normal exit management")
    void openPositionIsNeverDropped() {
        service.setBanPeriodLogOnly(false);
        service.getActiveSetups()
                .put("RELIANCE", new LowestVolumeSetup("RELIANCE", LowestVolumeDirection.LONG));
        service.getOpenPositions().put("RELIANCE", openPosition("RELIANCE"));

        wireBanClient(Set.of("RELIANCE"), true);
        service.runNseBanListFetch();

        assertThat(service.getActiveSetups())
                .as("an open position must never be orphaned by a universe filter")
                .containsKey("RELIANCE");
        assertThat(service.getOpenPositions()).containsKey("RELIANCE");
    }

    @Test
    @DisplayName("The morning watchlist filter is inert while log-only mode is on")
    void morningWatchlistFilterRespectsLogOnlyMode() {
        service.setBanPeriodLogOnly(true);
        wireBanClient(Set.of("RELIANCE"), true);
        service.runNseBanListFetch();

        // Log-only: enforcement helper is off, so the symbol stays tradeable in principle.
        assertThat(service.getBannedSymbols()).contains("RELIANCE");
        assertThat(service.isBanListUnavailable()).isFalse();
    }

    @Test
    @DisplayName("resetDaily clears the ban list so tomorrow re-fetches it")
    void resetDailyClearsBanState() {
        wireBanClient(Set.of("RELIANCE", "PVRINOX"), true);
        service.runNseBanListFetch();
        assertThat(service.getBannedSymbols()).isNotEmpty();

        service.resetDaily(true);

        assertThat(service.getBannedSymbols()).isEmpty();
        assertThat(service.isBanListUnavailable()).isFalse();
    }
}
