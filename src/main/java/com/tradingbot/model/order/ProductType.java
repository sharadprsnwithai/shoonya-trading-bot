package com.tradingbot.model.order;

/** Product types for Shoonya orders. */
public enum ProductType {
    MIS("M"), // Intraday Margin
    NRML("M"), // Futures & Options Normal
    CNC("C"); // Cash & Carry Equity

    private final String code;

    ProductType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
