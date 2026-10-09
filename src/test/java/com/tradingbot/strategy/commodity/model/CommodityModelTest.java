package com.tradingbot.strategy.commodity.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommodityModelTest {

    @Test
    @DisplayName("Should classify Bias correctly based on PCR thresholds")
    void testBiasClassification() {
        assertThat(CommodityBias.fromPcr(1.20, 1.15, 0.85)).isEqualTo(CommodityBias.BULLISH);
        assertThat(CommodityBias.fromPcr(1.15, 1.15, 0.85)).isEqualTo(CommodityBias.BULLISH);
        assertThat(CommodityBias.fromPcr(0.80, 1.15, 0.85)).isEqualTo(CommodityBias.BEARISH);
        assertThat(CommodityBias.fromPcr(0.85, 1.15, 0.85)).isEqualTo(CommodityBias.BEARISH);
        assertThat(CommodityBias.fromPcr(1.00, 1.15, 0.85)).isEqualTo(CommodityBias.NEUTRAL);
        assertThat(CommodityBias.fromPcr(null, 1.15, 0.85)).isEqualTo(CommodityBias.NEUTRAL);
        assertThat(CommodityBias.fromPcr(0.0, 1.15, 0.85)).isEqualTo(CommodityBias.NEUTRAL);
    }

    @Test
    @DisplayName("Should calculate 1:2 RR Target and Risk for LONG position")
    void testLongRiskRewardCalculation() {
        BigDecimal entryPrice = new BigDecimal("5500.00");
        BigDecimal stopLoss = new BigDecimal("5460.00"); // 40 pts risk
        BigDecimal riskRewardRatio = new BigDecimal("2.0");

        CommodityTradePosition pos =
                CommodityTradePosition.createLong(
                        "CRUDEOILM", entryPrice, stopLoss, riskRewardRatio, 10, Instant.now());

        assertThat(pos.symbol()).isEqualTo("CRUDEOILM");
        assertThat(pos.side()).isEqualTo("LONG");
        assertThat(pos.entryPrice()).isEqualByComparingTo(new BigDecimal("5500.00"));
        assertThat(pos.stopLoss()).isEqualByComparingTo(new BigDecimal("5460.00"));
        assertThat(pos.risk()).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(pos.targetPrice())
                .isEqualByComparingTo(new BigDecimal("5580.00")); // 5500 + (2 * 40)
        assertThat(pos.isClosed()).isFalse();
    }

    @Test
    @DisplayName("Should calculate 1:2 RR Target and Risk for SHORT position")
    void testShortRiskRewardCalculation() {
        BigDecimal entryPrice = new BigDecimal("75000.00");
        BigDecimal stopLoss = new BigDecimal("75300.00"); // 300 pts risk
        BigDecimal riskRewardRatio = new BigDecimal("2.0");

        CommodityTradePosition pos =
                CommodityTradePosition.createShort(
                        "GOLDM", entryPrice, stopLoss, riskRewardRatio, 1, Instant.now());

        assertThat(pos.symbol()).isEqualTo("GOLDM");
        assertThat(pos.side()).isEqualTo("SHORT");
        assertThat(pos.entryPrice()).isEqualByComparingTo(new BigDecimal("75000.00"));
        assertThat(pos.stopLoss()).isEqualByComparingTo(new BigDecimal("75300.00"));
        assertThat(pos.risk()).isEqualByComparingTo(new BigDecimal("300.00"));
        assertThat(pos.targetPrice())
                .isEqualByComparingTo(new BigDecimal("74400.00")); // 75000 - (2 * 300)
        assertThat(pos.isClosed()).isFalse();
    }

    @Test
    @DisplayName("Should execute 50% partial booking at Target and move SL to Cost for LONG")
    void testPartialBookingAndRunnerExitLong() {
        BigDecimal entryPrice = new BigDecimal("100.00");
        BigDecimal stopLoss = new BigDecimal("90.00"); // 10 pts risk
        BigDecimal riskRewardRatio = new BigDecimal("2.5"); // Target = 125

        CommodityTradePosition pos =
                CommodityTradePosition.createLong(
                        "SILVERM", entryPrice, stopLoss, riskRewardRatio, 2, Instant.now());

        assertThat(pos.targetPrice()).isEqualByComparingTo(new BigDecimal("125.00"));
        assertThat(pos.isPartialBooked()).isFalse();

        // 1. Book 50% partial at Target 1 (125.00)
        CommodityTradePosition partialPos =
                pos.executePartialBook(new BigDecimal("125.00"), Instant.now(), 1.0);
        assertThat(partialPos.isPartialBooked()).isTrue();
        assertThat(partialPos.isClosed()).isFalse();
        assertThat(partialPos.remainingQuantity()).isEqualTo(1);
        assertThat(partialPos.currentStopLoss())
                .isEqualByComparingTo(new BigDecimal("100.00")); // Moved to Cost
        assertThat(partialPos.partialPnl())
                .isEqualByComparingTo(new BigDecimal("25.00")); // (125 - 100) * 1

        // 2. Dynamic 10 EMA Trailing update (EMA = 115 > entryPrice 100)
        partialPos.updateDynamicStopLoss(new BigDecimal("115.00"));
        assertThat(partialPos.currentStopLoss()).isEqualByComparingTo(new BigDecimal("115.00"));

        // 3. Runner Exit at Dynamic SL (115.00)
        CommodityTradePosition closedPos =
                partialPos.close(
                        new BigDecimal("115.00"), Instant.now(), "RUNNER_10EMA_TRAIL_EXIT", 1.0);
        assertThat(closedPos.isClosed()).isTrue();
        assertThat(closedPos.runnerPnl())
                .isEqualByComparingTo(new BigDecimal("15.00")); // (115 - 100) * 1
        assertThat(closedPos.pnl()).isEqualByComparingTo(new BigDecimal("40.00")); // 25 + 15
        assertThat(closedPos.exitReason()).isEqualTo("RUNNER_10EMA_TRAIL_EXIT");
    }

    @Test
    @DisplayName("Should execute 50% partial booking at Target and move SL to Cost for SHORT")
    void testPartialBookingAndRunnerExitShort() {
        BigDecimal entryPrice = new BigDecimal("100.00");
        BigDecimal stopLoss = new BigDecimal("110.00"); // 10 pts risk
        BigDecimal riskRewardRatio = new BigDecimal("2.5"); // Target = 75

        CommodityTradePosition pos =
                CommodityTradePosition.createShort(
                        "SILVERM", entryPrice, stopLoss, riskRewardRatio, 2, Instant.now());

        assertThat(pos.targetPrice()).isEqualByComparingTo(new BigDecimal("75.00"));
        assertThat(pos.isPartialBooked()).isFalse();

        // 1. Book 50% partial at Target 1 (75.00)
        CommodityTradePosition partialPos =
                pos.executePartialBook(new BigDecimal("75.00"), Instant.now(), 1.0);
        assertThat(partialPos.isPartialBooked()).isTrue();
        assertThat(partialPos.isClosed()).isFalse();
        assertThat(partialPos.remainingQuantity()).isEqualTo(1);
        assertThat(partialPos.currentStopLoss())
                .isEqualByComparingTo(new BigDecimal("100.00")); // Moved to Cost
        assertThat(partialPos.partialPnl())
                .isEqualByComparingTo(new BigDecimal("25.00")); // (100 - 75) * 1

        // 2. Dynamic 10 EMA Trailing update (EMA = 85 < entryPrice 100)
        partialPos.updateDynamicStopLoss(new BigDecimal("85.00"));
        assertThat(partialPos.currentStopLoss()).isEqualByComparingTo(new BigDecimal("85.00"));

        // 3. Runner Exit at Dynamic SL (85.00)
        CommodityTradePosition closedPos =
                partialPos.close(
                        new BigDecimal("85.00"), Instant.now(), "RUNNER_10EMA_TRAIL_EXIT", 1.0);
        assertThat(closedPos.isClosed()).isTrue();
        assertThat(closedPos.runnerPnl())
                .isEqualByComparingTo(new BigDecimal("15.00")); // (100 - 85) * 1
        assertThat(closedPos.pnl()).isEqualByComparingTo(new BigDecimal("40.00")); // 25 + 15
        assertThat(closedPos.exitReason()).isEqualTo("RUNNER_10EMA_TRAIL_EXIT");
    }

    void testSetupState() {
        CommoditySetup setup = new CommoditySetup("GOLD");
        assertThat(setup.getSymbol()).isEqualTo("GOLD");
        assertThat(setup.getBias()).isEqualTo(CommodityBias.NEUTRAL);
        assertThat(setup.getState()).isEqualTo(CommoditySetupState.IDLE);

        setup.updateBias(CommodityBias.BULLISH, 1.25);
        assertThat(setup.getBias()).isEqualTo(CommodityBias.BULLISH);
        assertThat(setup.getPcr()).isEqualTo(1.25);
        assertThat(setup.getState()).isEqualTo(CommoditySetupState.BIAS_IDENTIFIED);

        setup.armLong(new BigDecimal("75200.00"), new BigDecimal("75100.00"), Instant.now());
        assertThat(setup.getState()).isEqualTo(CommoditySetupState.ARMED_LONG);
        assertThat(setup.getTriggerHigh()).isEqualByComparingTo(new BigDecimal("75200.00"));
        assertThat(setup.getVwapAtSetup()).isEqualByComparingTo(new BigDecimal("75100.00"));
    }
}
