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

    @Test
    @DisplayName("Position exposes booked quantity and handles partial exit math")
    void testBookedQuantityAccessors() {
        int lotSize = 100;
        int lots = 3;
        int totalQty = 300;
        BigDecimal entryPrice = BigDecimal.valueOf(50.0);

        LowestVolumePaperPosition pos =
                new LowestVolumePaperPosition(
                        "LVR-5",
                        "SBIN",
                        LvrExitMode.PARTIAL_1_2_TRAIL_10EMA_COST_EOD_1500,
                        "CE",
                        "SBIN ATM 800CE",
                        BigDecimal.valueOf(800),
                        lotSize,
                        lots,
                        LowestVolumeDirection.LONG,
                        BigDecimal.valueOf(15.0),
                        entryPrice,
                        BigDecimal.valueOf(48.0),
                        BigDecimal.valueOf(54.0),
                        totalQty,
                        BigDecimal.valueOf(300.0),
                        Instant.now());

        // 3 lots: (3+1)/2 = 2 lots booked (200 qty)
        assertThat(pos.getEffectiveBookedQuantityOnTarget()).isEqualTo(200);
        pos.executePartialBook(BigDecimal.valueOf(25.0), Instant.now());
        assertThat(pos.getBookedQuantity()).isEqualTo(200);
        assertThat(pos.getRemainingQuantity()).isEqualTo(100);
    }

    @Test
    @DisplayName("Should store PDH, PDL, setupPattern and OI change in LowestVolumeSetup")
    void testLowestVolumeSetupPdhPdlAndPattern() {
        LowestVolumeSetup setup = new LowestVolumeSetup("RELIANCE", LowestVolumeDirection.LONG);
        setup.setPdh(BigDecimal.valueOf(3000.00));
        setup.setPdl(BigDecimal.valueOf(2950.00));
        setup.setSetupPattern("VANDE_BHARAT_INSIDE_BAR");
        setup.setOiChangePct(12.5);

        assertThat(setup.getPdh()).isEqualByComparingTo(BigDecimal.valueOf(3000.00));
        assertThat(setup.getPdl()).isEqualByComparingTo(BigDecimal.valueOf(2950.00));
        assertThat(setup.getSetupPattern()).isEqualTo("VANDE_BHARAT_INSIDE_BAR");
        assertThat(setup.getOiChangePct()).isEqualTo(12.5);

        // Reset to scanning should preserve PDH/PDL but reset trigger & pattern
        setup.resetToScanning();
        assertThat(setup.getPdh()).isEqualByComparingTo(BigDecimal.valueOf(3000.00));
        assertThat(setup.getPdl()).isEqualByComparingTo(BigDecimal.valueOf(2950.00));
        assertThat(setup.getSetupPattern()).isNull();
    }
}
