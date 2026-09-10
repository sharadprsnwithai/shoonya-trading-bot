package com.tradingbot.model.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DailyWmaPositionTest {

    @Test
    void testBullPutSpreadLifecycleAndPnlCalculation() {
        LocalDate entryDate = LocalDate.of(2026, 9, 10);
        LocalDate expiryDate = LocalDate.of(2026, 9, 24);
        Instant entryTime = Instant.now();

        // Bull Put Spread: Sell 24100 PE @ 95.00, Buy 23600 PE @ 12.00 (2% OTM Hedge)
        DailyWmaPosition pos =
                new DailyWmaPosition(
                        "WMA_20260910_001",
                        "NIFTY",
                        "SELL",
                        "PE",
                        "BULLISH",
                        entryDate,
                        entryTime,
                        BigDecimal.valueOf(24850.50),
                        BigDecimal.valueOf(24620.30),
                        expiryDate,
                        "NIFTY24SEP24100PE",
                        BigDecimal.valueOf(24100),
                        BigDecimal.valueOf(95.00),
                        0.22,
                        65,
                        "NIFTY24SEP23600PE",
                        BigDecimal.valueOf(23600),
                        BigDecimal.valueOf(12.00),
                        65,
                        BigDecimal.valueOf(83.00), // Net Credit = 95 - 12
                        BigDecimal.valueOf(190.00), // 2.0x SL
                        0);

        assertThat(pos.isClosed()).isFalse();
        assertThat(pos.getTradeId()).isEqualTo("WMA_20260910_001");
        assertThat(pos.getShortSymbol()).isEqualTo("NIFTY24SEP24100PE");
        assertThat(pos.getHedgeSymbol()).isEqualTo("NIFTY24SEP23600PE");
        assertThat(pos.getNetCredit()).isEqualByComparingTo(BigDecimal.valueOf(83.00));
        assertThat(pos.getStopLossPrice()).isEqualByComparingTo(BigDecimal.valueOf(190.00));

        // PnL in open trade:
        // Short decays to 20.00 -> Profit = (95 - 20) * 65 = +4875
        // Hedge decays to 2.00  -> Loss   = (2 - 12) * 65  = -650
        // Net Spread PnL = 4875 - 650 = +4225
        BigDecimal pnl = pos.calculateSpreadPnl(BigDecimal.valueOf(20.00), BigDecimal.valueOf(2.00));
        assertThat(pnl).isEqualByComparingTo(BigDecimal.valueOf(4225.00));

        // Close position
        LocalDate exitDate = LocalDate.of(2026, 9, 24);
        Instant exitTime = entryTime.plusSeconds(3600 * 24 * 14);
        pos.close(
                BigDecimal.valueOf(20.00),
                BigDecimal.valueOf(2.00),
                "EXPIRY_SQUARE_OFF",
                exitTime,
                exitDate,
                BigDecimal.valueOf(25200.00));

        assertThat(pos.isClosed()).isTrue();
        assertThat(pos.getShortExitPrice()).isEqualByComparingTo(BigDecimal.valueOf(20.00));
        assertThat(pos.getHedgeExitPrice()).isEqualByComparingTo(BigDecimal.valueOf(2.00));
        assertThat(pos.getRealizedPnl()).isEqualByComparingTo(BigDecimal.valueOf(4225.00));
        assertThat(pos.getExitReason()).isEqualTo("EXPIRY_SQUARE_OFF");
        assertThat(pos.getExitDate()).isEqualTo(exitDate);
    }
}
