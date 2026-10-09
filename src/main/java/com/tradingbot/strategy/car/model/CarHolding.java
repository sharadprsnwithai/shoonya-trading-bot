package com.tradingbot.strategy.car.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

public record CarHolding(
        String symbol,
        long totalQuantity,
        BigDecimal averageBuyPrice,
        BigDecimal targetPrice,
        int accumulatedUnits,
        Instant firstEntryTime,
        Instant lastEntryTime) {

    public CarHolding withAdditionalFill(
            int additionalQty, BigDecimal fillPrice, BigDecimal profitTargetPct) {
        long newTotalQty = this.totalQuantity + additionalQty;
        BigDecimal totalSpent =
                (this.averageBuyPrice.multiply(BigDecimal.valueOf(this.totalQuantity)))
                        .add(fillPrice.multiply(BigDecimal.valueOf(additionalQty)));
        BigDecimal newAvgPrice =
                totalSpent.divide(BigDecimal.valueOf(newTotalQty), 2, RoundingMode.HALF_UP);
        BigDecimal targetMultiplier =
                BigDecimal.ONE.add(
                        profitTargetPct.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        BigDecimal newTarget =
                newAvgPrice.multiply(targetMultiplier).setScale(2, RoundingMode.HALF_UP);

        return new CarHolding(
                this.symbol,
                newTotalQty,
                newAvgPrice,
                newTarget,
                this.accumulatedUnits + 1,
                this.firstEntryTime,
                Instant.now());
    }

    public static CarHolding initial(
            String symbol, int qty, BigDecimal fillPrice, BigDecimal profitTargetPct) {
        BigDecimal targetMultiplier =
                BigDecimal.ONE.add(
                        profitTargetPct.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
        BigDecimal target = fillPrice.multiply(targetMultiplier).setScale(2, RoundingMode.HALF_UP);
        return new CarHolding(
                symbol,
                qty,
                fillPrice.setScale(2, RoundingMode.HALF_UP),
                target,
                1,
                Instant.now(),
                Instant.now());
    }
}
