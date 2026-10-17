package com.tradingbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.strategy.LvrInstrumentType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LVR expiry/DTE rules tests (design spec 2026-10-17 §4.2–§4.3): in OPTIONS mode a symbol whose
 * current monthly series is inside the DTE floor is never promoted from the standby reservoir, so
 * the ≤3 active candidate slots stay productive.
 *
 * <p>October 2026's monthly expiry is Tuesday the 27th (NSE last-Tuesday convention), so Monday the
 * 26th has 1 trading session left — inside the default floor of 2 — while Monday the 12th has 10.
 */
class LowestVolumeExpiryDteTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Monday 26-Oct-2026, 10:30 IST — one trading session before the Oct 27 expiry. */
    private static final Instant NEAR_EXPIRY = Instant.parse("2026-10-26T05:00:00Z");

    /** Monday 12-Oct-2026, 10:30 IST — well inside the October series (10 sessions left). */
    private static final Instant MID_SERIES = Instant.parse("2026-10-12T05:00:00Z");

    private LowestVolumeReversalService service;

    @BeforeEach
    void setUp() {
        service =
                new LowestVolumeReversalService(
                        mock(ShoonyaMarketDataService.class),
                        new TechnicalAnalysisService(),
                        null,
                        mock(ShoonyaConfig.class),
                        null);
        service.setMorningScanDelayMs(0);
        service.setPdhPdlFilterEnabled(false);
        service.setOpening15mRangeFilterEnabled(false);
        service.setPcrFilterEnabled(false);
        service.setOptionSrFilterEnabled(false);
        service.setSectorMomentumFilterEnabled(false);
        // Non-empty reservoir → replenish() has something to promote in every scenario below.
        service.getCandidateReservoir().clear();
        service.getCandidateReservoir().add("TATAMOTORS");
        service.getCandidateReservoir().add("BAJFINANCE");
    }

    /** Seeds the clock and runs one replenishment tick, returning the promoted watchlist. */
    private java.util.Set<String> promoteAt(Instant instant) {
        service.setClock(Clock.fixed(instant, IST));
        service.replenishActiveCandidatesIfNeeded(LocalTime.of(10, 15));
        return service.getActiveSetups().keySet();
    }

    @Test
    @DisplayName("OPTIONS inside the DTE floor are never promoted from the reservoir")
    void optionsNearExpiryAreNotPromoted() {
        service.setInstrumentType(LvrInstrumentType.OPTIONS);
        service.setExpiryDteEnabled(true);
        service.setOptionMinDteSessions(2);

        assertThat(promoteAt(NEAR_EXPIRY))
                .as("1 session left with a floor of 2 — both candidates are un-enterable")
                .isEmpty();
    }

    @Test
    @DisplayName("OPTIONS on the monthly expiry session are never promoted")
    void optionsOnExpiryDayAreNotPromoted() {
        service.setInstrumentType(LvrInstrumentType.OPTIONS);
        service.setExpiryDteEnabled(true);
        service.setOptionMinDteSessions(2);

        // Tuesday 27-Oct-2026 IS the monthly expiry session.
        assertThat(promoteAt(Instant.parse("2026-10-27T04:30:00Z"))).isEmpty();
    }

    @Test
    @DisplayName("OPTIONS with a healthy DTE promote normally")
    void optionsWithHealthyDteArePromoted() {
        service.setInstrumentType(LvrInstrumentType.OPTIONS);
        service.setExpiryDteEnabled(true);
        service.setOptionMinDteSessions(2);

        assertThat(promoteAt(MID_SERIES))
                .as("10 sessions left — well clear of the floor")
                .containsExactlyInAnyOrder("TATAMOTORS", "BAJFINANCE");
    }

    @Test
    @DisplayName("FUTURES are never excluded by the options floor, even on expiry day")
    void futuresAreNeverExcludedByTheOptionsFloor() {
        service.setInstrumentType(LvrInstrumentType.FUTURES);
        service.setExpiryDteEnabled(true);
        service.setOptionMinDteSessions(2);
        service.setFuturesMinDteSessions(0);

        assertThat(promoteAt(Instant.parse("2026-10-27T04:30:00Z")))
                .as("the live FUTURES strategy must not be silently disabled")
                .containsExactlyInAnyOrder("TATAMOTORS", "BAJFINANCE");
    }

    @Test
    @DisplayName("A futures floor of 0 stays inert even when raised-config mode is off")
    void futuresFloorOfZeroIsInert() {
        service.setInstrumentType(LvrInstrumentType.FUTURES);
        service.setExpiryDteEnabled(true);
        service.setFuturesMinDteSessions(0);

        assertThat(promoteAt(NEAR_EXPIRY)).containsExactlyInAnyOrder("TATAMOTORS", "BAJFINANCE");
    }

    @Test
    @DisplayName("option-min-dte-sessions = 0 restores the legacy behaviour")
    void zeroOptionFloorRestoresLegacyBehaviour() {
        service.setInstrumentType(LvrInstrumentType.OPTIONS);
        service.setExpiryDteEnabled(true);
        service.setOptionMinDteSessions(0);

        assertThat(promoteAt(NEAR_EXPIRY)).containsExactlyInAnyOrder("TATAMOTORS", "BAJFINANCE");
    }

    @Test
    @DisplayName("The gate kill switch leaves options promotion untouched")
    void gateDisabledLeavesPromotionUntouched() {
        service.setInstrumentType(LvrInstrumentType.OPTIONS);
        service.setExpiryDteEnabled(false);
        service.setOptionMinDteSessions(2);

        assertThat(promoteAt(NEAR_EXPIRY)).containsExactlyInAnyOrder("TATAMOTORS", "BAJFINANCE");
    }
}
