package com.tradingbot.model.order;

/** Order transaction direction (BUY / SELL). */
public enum TransactionType {
    BUY("B"),
    SELL("S");

    private final String code;

    TransactionType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
