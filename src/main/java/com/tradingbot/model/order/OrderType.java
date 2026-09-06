package com.tradingbot.model.order;

/** Shoonya order price types. */
public enum OrderType {
    LMT("LMT"),
    MKT("MKT"),
    SL_LMT("SL-LMT"),
    SL_MKT("SL-MKT");

    private final String code;

    OrderType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
