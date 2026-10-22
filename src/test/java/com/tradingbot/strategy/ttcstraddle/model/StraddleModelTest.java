package com.tradingbot.strategy.ttcstraddle.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StraddleModelTest {

    @Test
    @DisplayName("StraddleLeg computes 50% stop loss accurately")
    void testStraddleLegStopLossCalculation() {
        BigDecimal entryPrice = BigDecimal.valueOf(100.00);
        BigDecimal sl = StraddleLeg.computeStopLoss(entryPrice, 50.0);

        assertThat(sl).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("StraddleDailyState tracks open legs and daily P&L")
    void testStraddleDailyStateTracking() {
        LocalDate today = LocalDate.of(2026, 10, 22);
        StraddleDailyState state = new StraddleDailyState(today, "NIFTY");

        assertThat(state.getTradeDate()).isEqualTo(today);
        assertThat(state.getInstrument()).isEqualTo("NIFTY");
        assertThat(state.isKillSwitchTripped()).isFalse();
        assertThat(state.getPhase()).isEqualTo(StraddleSessionPhase.IDLE);

        // Record a morning leg
        StraddleLeg shortCe =
                new StraddleLeg(
                        "NIFTY26OCT23000CE",
                        "CE",
                        BigDecimal.valueOf(23000),
                        BigDecimal.valueOf(120.0),
                        BigDecimal.valueOf(180.0),
                        25,
                        true);
        state.setMorningShortCe(shortCe);

        assertThat(state.getMorningShortCe()).isNotNull();
        assertThat(state.getMorningShortCe().isActive()).isTrue();
    }
}
