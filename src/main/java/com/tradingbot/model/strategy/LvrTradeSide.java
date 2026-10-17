package com.tradingbot.model.strategy;

/**
 * Side of one executed order inside an LVR trade, used by the round-trip cost model to decide which
 * statutory charges apply.
 *
 * <p>The two Indian taxes are sided, and getting the side wrong moves the answer a lot: STT is a
 * <b>sell-side</b> tax, while stamp duty is a <b>buy-side</b> tax. A futures SHORT therefore pays
 * STT on its entry leg and stamp duty on its exit leg — which is exactly what modelling each leg
 * explicitly gives us for free.
 */
public enum LvrTradeSide {

    /** Bought the contract: a futures long entry, an option premium purchase, or a short's exit. */
    BUY,

    /** Sold the contract: a futures long exit, an option premium sale, or a short's entry. */
    SELL;

    /**
     * @return the opposite side of the book — the side every exit leg takes relative to its entry
     */
    public LvrTradeSide opposite() {
        return this == BUY ? SELL : BUY;
    }
}
