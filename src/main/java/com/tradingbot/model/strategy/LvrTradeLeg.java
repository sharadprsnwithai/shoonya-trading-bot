package com.tradingbot.model.strategy;

import java.math.BigDecimal;

/**
 * One executed order (a single fill) of an LVR trade, feeding {@code LvrCostModel}.
 *
 * <p>A trade is split into legs rather than costed as a whole because charges are levied per leg
 * and per side: brokerage is charged per executed order, STT only on sells, and stamp duty only on
 * buys.
 *
 * @param side buy or sell
 * @param price executed price per unit — the futures price, or the option premium
 * @param quantity number of units (shares/units, never lots)
 */
public record LvrTradeLeg(LvrTradeSide side, BigDecimal price, int quantity) {

    /**
     * @return this leg's turnover (price × quantity), or zero when the leg cannot be costed
     */
    public BigDecimal turnover() {
        if (price == null || quantity <= 0) {
            return BigDecimal.ZERO;
        }
        return price.multiply(BigDecimal.valueOf(quantity));
    }

    /**
     * @return true when this leg carries enough information to be costed
     */
    public boolean isCostable() {
        return side != null && price != null && price.signum() > 0 && quantity > 0;
    }
}
