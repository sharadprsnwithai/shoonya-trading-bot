package com.tradingbot.model.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RsiCrossoverPositionTest {

    @Test
    void testPositionLifecycleAndPnlCalculation() {
        Instant now = Instant.now();
        RsiCrossoverPosition pos =
                new RsiCrossoverPosition(
                        "TRD_001",
                        "NIFTY24OCT22500CE",
                        "CE",
                        BigDecimal.valueOf(22500),
                        BigDecimal.valueOf(150.0),
                        65,
                        now);

        assertThat(pos.isClosed()).isFalse();
        assertThat(pos.getTradeId()).isEqualTo("TRD_001");
        assertThat(pos.getSymbol()).isEqualTo("NIFTY24OCT22500CE");
        assertThat(pos.getOptionType()).isEqualTo("CE");
        assertThat(pos.getStrike()).isEqualByComparingTo(BigDecimal.valueOf(22500));
        assertThat(pos.getEntryPrice()).isEqualByComparingTo(BigDecimal.valueOf(150.0));
        assertThat(pos.getQuantity()).isEqualTo(65);
        assertThat(pos.getEntryTime()).isEqualTo(now);

        // Test PnL calculation while open
        assertThat(pos.calculatePnl(BigDecimal.valueOf(180.0)))
                .isEqualByComparingTo(BigDecimal.valueOf(1950.0)); // (180 - 150) * 65

        // Close position
        Instant exitTime = now.plusSeconds(300);
        pos.close(BigDecimal.valueOf(180.0), "RSI_REVERSAL", exitTime);

        assertThat(pos.isClosed()).isTrue();
        assertThat(pos.getExitPrice()).isEqualByComparingTo(BigDecimal.valueOf(180.0));
        assertThat(pos.getExitReason()).isEqualTo("RSI_REVERSAL");
        assertThat(pos.getExitTime()).isEqualTo(exitTime);
        assertThat(pos.getPnl()).isEqualByComparingTo(BigDecimal.valueOf(1950.0));
    }
}
