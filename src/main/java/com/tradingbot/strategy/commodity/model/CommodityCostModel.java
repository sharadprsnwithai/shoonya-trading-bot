package com.tradingbot.strategy.commodity.model;

import com.tradingbot.strategy.commodity.config.CommodityVwapProperties;
import com.tradingbot.util.CommodityRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Applies configurable round-trip transaction costs (brokerage + slippage) to commodity trades so
 * reported and backtested PnL can be measured net of costs. Uses the same notional basis as {@link
 * CommodityTradePosition} PnL (price delta x quantity x unit multiplier).
 */
public class CommodityCostModel {

    private final double brokerageInrPerLotPerSide;
    private final double slippageBpsPerSide;

    public CommodityCostModel(double brokerageInrPerLotPerSide, double slippageBpsPerSide) {
        this.brokerageInrPerLotPerSide = brokerageInrPerLotPerSide;
        this.slippageBpsPerSide = slippageBpsPerSide;
    }

    /** Builds a cost model from strategy properties (defaults applied when properties are null). */
    public static CommodityCostModel from(CommodityVwapProperties properties) {
        CommodityVwapProperties.Costs costs =
                properties != null && properties.getCosts() != null
                        ? properties.getCosts()
                        : new CommodityVwapProperties.Costs();
        return new CommodityCostModel(
                costs.getBrokerageInrPerLotPerSide(), costs.getSlippageBpsPerSide());
    }

    /**
     * Total round-trip cost in INR for a trade: brokerage x 2 sides x lots + slippage x 2 sides x
     * contract notional.
     */
    public BigDecimal roundTripCost(CommodityTradePosition position) {
        if (position == null || position.entryPrice() == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return roundTripCost(position.symbol(), position.entryPrice(), position.quantity());
    }

    /** Round-trip cost in INR for a prospective trade identified by symbol, entry and quantity. */
    public BigDecimal roundTripCost(String symbol, BigDecimal entryPrice, int quantity) {
        if (entryPrice == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        int lots = lotsTraded(symbol, quantity);
        double notional =
                entryPrice.doubleValue() * quantity * CommodityRegistry.getUnitMultiplier(symbol);
        double brokerage = brokerageInrPerLotPerSide * 2.0 * lots;
        double slippage = (slippageBpsPerSide / 10_000.0) * 2.0 * notional;
        return BigDecimal.valueOf(brokerage + slippage).setScale(2, RoundingMode.HALF_UP);
    }

    private int lotsTraded(String symbol, int quantity) {
        CommodityRegistry.CommodityMetadata meta = CommodityRegistry.getMetadata(symbol);
        int lotSize = meta != null ? meta.lotSize() : 0;
        if (lotSize <= 0 || quantity <= 0) return 1;
        return Math.max(1, Math.round((float) quantity / lotSize));
    }
}
