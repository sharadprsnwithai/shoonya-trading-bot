package com.tradingbot.strategy.silverorb.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Aggregate-derivation tests for {@link SilverOrbState}. */
class SilverOrbStateTest {

    private static SilverOrbTrade trade(double pnlRs, double r) {
        SilverOrbTrade t = new SilverOrbTrade();
        t.side = "LONG";
        t.pnlRs = pnlRs;
        t.rMultiple = r;
        t.exitReason = "TARGET";
        return t;
    }

    @Test
    @DisplayName("Ledger aggregates (count, wins, win rate, net P&L, net R) derive from trades")
    void aggregatesDeriveFromLedger() {
        SilverOrbState state = new SilverOrbState();
        state.trades.add(trade(100, 2.0));
        state.trades.add(trade(-50, -1.0));
        state.trades.add(trade(25, 0.5));

        assertThat(state.tradeCount()).isEqualTo(3);
        assertThat(state.winCount()).isEqualTo(2);
        assertThat(state.winRatePct()).isCloseTo(200.0 / 3.0, within(1e-9));
        assertThat(state.totalPnlRs()).isCloseTo(75.0, within(1e-9));
        assertThat(state.totalR()).isCloseTo(1.5, within(1e-9));
    }

    @Test
    @DisplayName("Empty ledger reports zero win rate instead of dividing by zero")
    void emptyLedgerWinRateIsZero() {
        SilverOrbState state = new SilverOrbState();

        assertThat(state.tradeCount()).isZero();
        assertThat(state.winCount()).isZero();
        assertThat(state.winRatePct()).isZero();
        assertThat(state.totalPnlRs()).isZero();
        assertThat(state.totalR()).isZero();
    }
}
