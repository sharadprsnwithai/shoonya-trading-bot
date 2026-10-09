package com.tradingbot.strategy.car.gtt;

import java.math.BigDecimal;

/**
 * Executed details of a GTT trigger that has already fired.
 *
 * @param quantity quantity actually executed (may be less than the trigger quantity)
 * @param price average executed price
 */
public record GttFill(int quantity, BigDecimal price) {

    public GttFill {
        if (quantity <= 0) {
            throw new IllegalArgumentException("GTT fill quantity must be positive");
        }
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("GTT fill price must be positive");
        }
    }
}
