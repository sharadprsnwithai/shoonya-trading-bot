package com.tradingbot.model.order;

import java.math.BigDecimal;

/** Request payload for placing or modifying an order on Shoonya. */
public record OrderRequest(
        String symbol,
        String exchange,
        TransactionType transactionType,
        OrderType orderType,
        ProductType productType,
        int quantity,
        BigDecimal price,
        BigDecimal triggerPrice,
        String tag) {
    public static OrderRequest market(
            String symbol, String exchange, TransactionType type, int qty, String tag) {
        return new OrderRequest(
                symbol,
                exchange,
                type,
                OrderType.MKT,
                ProductType.MIS,
                qty,
                BigDecimal.ZERO,
                null,
                tag);
    }

    public static OrderRequest limit(
            String symbol,
            String exchange,
            TransactionType type,
            int qty,
            BigDecimal price,
            String tag) {
        return new OrderRequest(
                symbol, exchange, type, OrderType.LMT, ProductType.MIS, qty, price, null, tag);
    }

    public static OrderRequest stopLossLimit(
            String symbol,
            String exchange,
            TransactionType type,
            int qty,
            BigDecimal triggerPrice,
            BigDecimal limitPrice,
            String tag) {
        return new OrderRequest(
                symbol,
                exchange,
                type,
                OrderType.SL_LMT,
                ProductType.MIS,
                qty,
                limitPrice,
                triggerPrice,
                tag);
    }
}
