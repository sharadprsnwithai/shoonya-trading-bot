package com.tradingbot.strategy.silverorb.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Settle-math tests for {@link SilverOrbTrade}: price vs. margin returns and R-multiples. */
class SilverOrbTradeTest {

    @Test
    @DisplayName("LONG stop-out: -0.70% of price is -7% on margin at 10% margin, i.e. -1R")
    void longStopOutIsMinusOneR() {
        SilverOrbTrade t = new SilverOrbTrade();
        t.side = "LONG";
        t.entryTime = LocalDateTime.of(2026, 10, 14, 18, 30);
        t.entryPrice = 97;

        t.settle(LocalDateTime.of(2026, 10, 14, 18, 35), 96.321, "SL", 7.0, 10.0, -0.679 * 30);

        assertThat(t.isOpen()).isFalse();
        assertThat(t.exitReason).isEqualTo("SL");
        assertThat(t.points).isCloseTo(-0.679, within(1e-9));
        assertThat(t.priceRetPct).isCloseTo(-0.70, within(1e-9));
        assertThat(t.marginRetPct).isCloseTo(-7.0, within(1e-9));
        assertThat(t.rMultiple).isCloseTo(-1.0, within(1e-9));
        assertThat(t.pnlRs).isCloseTo(-20.37, within(1e-9));
    }

    @Test
    @DisplayName("SHORT target: price falling 2.5% is +25% on margin = +25/7 R")
    void shortTargetIsTwentyFiveSeventhR() {
        SilverOrbTrade t = new SilverOrbTrade();
        t.side = "SHORT";
        t.entryTime = LocalDateTime.of(2026, 10, 14, 18, 30);
        t.entryPrice = 225000;

        t.settle(
                LocalDateTime.of(2026, 10, 14, 19, 0),
                225000 - 5625,
                "TARGET",
                7.0,
                10.0,
                5625 * 30);

        assertThat(t.points).isCloseTo(5625.0, within(1e-9));
        assertThat(t.priceRetPct).isCloseTo(2.5, within(1e-9));
        assertThat(t.marginRetPct).isCloseTo(25.0, within(1e-9));
        assertThat(t.rMultiple).isCloseTo(25.0 / 7.0, within(1e-9));
        assertThat(t.pnlRs).isCloseTo(168750.0, within(1e-9));
    }

    @Test
    @DisplayName("SHORT stop-out above entry loses exactly 1R")
    void shortStopOutIsMinusOneR() {
        SilverOrbTrade t = new SilverOrbTrade();
        t.side = "SHORT";
        t.entryTime = LocalDateTime.of(2026, 10, 14, 18, 30);
        t.entryPrice = 93;

        // stop 0.70% above the short entry
        t.settle(LocalDateTime.of(2026, 10, 14, 18, 35), 93 + 0.651, "SL", 7.0, 10.0, -0.651 * 30);

        assertThat(t.points).isCloseTo(-0.651, within(1e-9));
        assertThat(t.rMultiple).isCloseTo(-1.0, within(1e-9));
    }

    @Test
    @DisplayName("A trade is open until an exit reason is booked")
    void isOpenUntilExitBooked() {
        SilverOrbTrade t = new SilverOrbTrade();
        t.side = "LONG";
        t.entryPrice = 97;

        assertThat(t.isOpen()).isTrue();

        t.settle(LocalDateTime.of(2026, 10, 14, 23, 0), 97.5, "SQUAREOFF", 7.0, 10.0, 15.0);

        assertThat(t.isOpen()).isFalse();
    }
}
