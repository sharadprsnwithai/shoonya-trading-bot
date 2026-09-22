package com.tradingbot.model.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LowestVolumePaperPositionTest {

    @Test
    @DisplayName("Futures LONG Position - 100% Full Exit at 1:4 Target computes +4R exact P&L")
    void testFuturesLongFullExitTarget() {
        BigDecimal entryPrice = BigDecimal.valueOf(1875.00);
        BigDecimal slPrice = BigDecimal.valueOf(1872.00);
        BigDecimal targetPrice = BigDecimal.valueOf(1887.00); // 1:4 (12 pts gain on 3 pts risk)
        int lotSize = 350;
        int lots = 1;
        int totalQty = lotSize * lots;
        BigDecimal plannedRisk =
                BigDecimal.valueOf(3.00).multiply(BigDecimal.valueOf(totalQty)); // 1050

        LowestVolumePaperPosition pos =
                new LowestVolumePaperPosition(
                        "LVR-1",
                        "SUNPHARMA",
                        LvrInstrumentType.FUTURES,
                        LvrExitMode.FULL_TARGET_1_4,
                        "SUNPHARMA FUT",
                        lotSize,
                        lots,
                        LowestVolumeDirection.LONG,
                        entryPrice,
                        slPrice,
                        targetPrice,
                        totalQty,
                        plannedRisk,
                        Instant.now());

        assertThat(pos.getInstrumentType()).isEqualTo(LvrInstrumentType.FUTURES);
        assertThat(pos.getExitMode()).isEqualTo(LvrExitMode.FULL_TARGET_1_4);
        assertThat(pos.getTotalQuantity()).isEqualTo(350);

        // Execute 100% full exit at target
        pos.closeFullFutures(targetPrice, "TARGET_1_4_FULL_EXIT", Instant.now());

        assertThat(pos.isClosed()).isTrue();
        assertThat(pos.getRemainingQuantity()).isZero();
        assertThat(pos.getTotalRealizedPnl())
                .isEqualByComparingTo(BigDecimal.valueOf(4200.00)); // +12 * 350
        assertThat(pos.getExitReason()).isEqualTo("TARGET_1_4_FULL_EXIT");
    }

    @Test
    @DisplayName("Futures SHORT Position - Stop Loss Hit computes -1R exact P&L")
    void testFuturesShortStopLossHit() {
        BigDecimal entryPrice = BigDecimal.valueOf(4900.00);
        BigDecimal slPrice = BigDecimal.valueOf(4910.00);
        BigDecimal targetPrice = BigDecimal.valueOf(4860.00); // 1:4 (40 pts gain on 10 pts risk)
        int lotSize = 125;
        int lots = 2;
        int totalQty = lotSize * lots; // 250
        BigDecimal plannedRisk =
                BigDecimal.valueOf(10.00).multiply(BigDecimal.valueOf(totalQty)); // 2500

        LowestVolumePaperPosition pos =
                new LowestVolumePaperPosition(
                        "LVR-2",
                        "TORNTPHARM",
                        LvrInstrumentType.FUTURES,
                        LvrExitMode.FULL_TARGET_1_4,
                        "TORNTPHARM FUT",
                        lotSize,
                        lots,
                        LowestVolumeDirection.SHORT,
                        entryPrice,
                        slPrice,
                        targetPrice,
                        totalQty,
                        plannedRisk,
                        Instant.now());

        // Execute Stop Loss hit exit
        pos.closeFullFutures(slPrice, "SPOT_SL_HIT", Instant.now());

        assertThat(pos.isClosed()).isTrue();
        assertThat(pos.getRemainingQuantity()).isZero();
        assertThat(pos.getTotalRealizedPnl())
                .isEqualByComparingTo(BigDecimal.valueOf(-2500.00)); // -10 * 250
        assertThat(pos.getExitReason()).isEqualTo("SPOT_SL_HIT");
    }
}
