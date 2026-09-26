package com.tradingbot.model.execution;

import java.math.BigDecimal;

/**
 * Unified model representing an active open or intraday position in Cash (Equity) or Derivatives
 * (F&O).
 */
public record BrokerPosition(
        String broker,
        String segment,
        String symbol,
        String tradingSymbol,
        String exchange,
        String productType,
        long quantity,
        BigDecimal averagePrice,
        BigDecimal lastPrice,
        BigDecimal pnl,
        BigDecimal mtm) {

    public static BrokerPosition of(
            String broker,
            String exchange,
            String symbol,
            String tradingSymbol,
            String productType,
            long quantity,
            BigDecimal averagePrice,
            BigDecimal lastPrice,
            BigDecimal pnl,
            BigDecimal mtm) {
        String seg = resolveSegment(exchange, tradingSymbol != null ? tradingSymbol : symbol);
        return new BrokerPosition(
                broker,
                seg,
                symbol,
                tradingSymbol,
                exchange,
                productType,
                quantity,
                averagePrice,
                lastPrice,
                pnl,
                mtm);
    }

    public static String resolveSegment(String exchange, String symbolOrContract) {
        if (exchange != null) {
            String ex = exchange.toUpperCase().trim();
            if (ex.equals("NFO") || ex.equals("BFO") || ex.equals("MCX") || ex.equals("CDS")) {
                return "DERIVATIVES";
            }
        }
        if (symbolOrContract != null) {
            String s = symbolOrContract.toUpperCase().trim();
            if (s.endsWith("FUT")
                    || s.contains(" FUT")
                    || s.contains(" ATM ")
                    || s.matches(".*\\d+(\\.\\d+)?(CE|PE)$")) {
                return "DERIVATIVES";
            }
        }
        return "CASH";
    }
}
