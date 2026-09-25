package com.tradingbot.model.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LowestVolumePaperPositionTest {

    @Test
    @DisplayName("Futures LONG Position - 100% Full Exit at 1:2 Target computes +2R exact P&L")
    void testFuturesLongFullExitTarget() {
        BigDecimal entryPrice = BigDecimal.valueOf(1875.00);
        BigDecimal slPrice = BigDecimal.valueOf(1872.00);
        BigDecimal targetPrice = BigDecimal.valueOf(1881.00); // 1:2 (6 pts gain on 3 pts risk)
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
                        LvrExitMode.FULL_TARGET_1_2,
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
        assertThat(pos.getExitMode()).isEqualTo(LvrExitMode.FULL_TARGET_1_2);
        assertThat(pos.getTotalQuantity()).isEqualTo(350);

        // Execute 100% full exit at target
        pos.closeFullFutures(targetPrice, "TARGET_1_2_FULL_EXIT", Instant.now());

        assertThat(pos.isClosed()).isTrue();
        assertThat(pos.getRemainingQuantity()).isZero();
        assertThat(pos.getTotalRealizedPnl())
                .isEqualByComparingTo(BigDecimal.valueOf(2100.00)); // +6 * 350
        assertThat(pos.getExitReason()).isEqualTo("TARGET_1_2_FULL_EXIT");
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

    @Test
    @DisplayName("Ceiling partial booking on odd lots (3 lots = 2 lots booked, 1 lot runner)")
    void testCeilPartialBookingOddLots() {
        int lotSize = 250;
        int lots = 3;
        int totalQty = lotSize * lots; // 750
        BigDecimal entryPrice = BigDecimal.valueOf(100.00);
        BigDecimal slPrice = BigDecimal.valueOf(98.00); // Risk = 2.00
        BigDecimal targetPrice = BigDecimal.valueOf(104.00); // Target 1:2

        LowestVolumePaperPosition pos =
                new LowestVolumePaperPosition(
                        "LVR-3",
                        "RELIANCE",
                        LvrInstrumentType.FUTURES,
                        LvrExitMode.PARTIAL_1_2_TRAIL_COST_EOD_1500,
                        "RELIANCE FUT",
                        lotSize,
                        lots,
                        LowestVolumeDirection.LONG,
                        entryPrice,
                        slPrice,
                        targetPrice,
                        totalQty,
                        BigDecimal.valueOf(1500.00),
                        Instant.now());

        // Book partial at Target (104.00)
        pos.executePartialBook(targetPrice, Instant.now());

        // Ceiling of 3 lots is 2 lots (500 shares). Remaining is 1 lot (250 shares).
        assertThat(pos.isPartialBooked()).isTrue();
        assertThat(pos.isClosed()).isFalse();
        assertThat(pos.getRemainingQuantity()).isEqualTo(250);
        // Partial P&L: (104 - 100) * 500 = +2000.00
        assertThat(pos.getPartialPnl()).isEqualByComparingTo("2000.00");
        assertThat(pos.getTotalRealizedPnl()).isEqualByComparingTo("2000.00");
        // Cost SL moved to entry price 100.00
        assertThat(pos.getCurrentStockSl()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Ceiling partial booking on 1 lot books full 1 lot and closes position")
    void testCeilPartialBookingSingleLot() {
        int lotSize = 250;
        int lots = 1;
        int totalQty = lotSize * lots; // 250
        BigDecimal entryPrice = BigDecimal.valueOf(100.00);
        BigDecimal slPrice = BigDecimal.valueOf(98.00);
        BigDecimal targetPrice = BigDecimal.valueOf(104.00);

        LowestVolumePaperPosition pos =
                new LowestVolumePaperPosition(
                        "LVR-4",
                        "RELIANCE",
                        LvrInstrumentType.FUTURES,
                        LvrExitMode.PARTIAL_1_2_TRAIL_COST_EOD_1500,
                        "RELIANCE FUT",
                        lotSize,
                        lots,
                        LowestVolumeDirection.LONG,
                        entryPrice,
                        slPrice,
                        targetPrice,
                        totalQty,
                        BigDecimal.valueOf(500.00),
                        Instant.now());

        pos.executePartialBook(targetPrice, Instant.now());

        // Ceiling of 1 lot is 1 lot (250 shares booked). Remaining is 0 shares.
        assertThat(pos.isPartialBooked()).isTrue();
        assertThat(pos.isClosed()).isTrue();
        assertThat(pos.getRemainingQuantity()).isZero();
        assertThat(pos.getPartialPnl()).isEqualByComparingTo("1000.00"); // (104 - 100) * 250
        assertThat(pos.getTotalRealizedPnl()).isEqualByComparingTo("1000.00");
        assertThat(pos.getExitReason()).isEqualTo("TARGET_1_2_FULL_EXIT");
    }
}
