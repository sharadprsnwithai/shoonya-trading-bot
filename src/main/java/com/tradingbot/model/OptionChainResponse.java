package com.tradingbot.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * REST response payload for Option Chain data.
 *
 * <p>{@link #synthetic()} distinguishes live exchange data from the fabricated fallback produced by
 * {@code ShoonyaOptionChainService#mockOptionChain}. The mock mirrors call OI against put OI, so
 * its Put-Call Ratio is always exactly {@code 1.00} regardless of market conditions. Consumers must
 * treat a synthetic chain as unavailable rather than as a neutral-but-valid reading.
 */
public record OptionChainResponse(
        String underlying,
        BigDecimal underlyingPrice,
        BigDecimal atmStrike,
        String tradingSymbol,
        int strikeCount,
        long totalCallOi,
        long totalPutOi,
        double pcr,
        List<OptionStrike> strikes,
        boolean synthetic) {

    /**
     * Convenience constructor for live (non-synthetic) chain data.
     *
     * @param underlying the underlying symbol
     * @param underlyingPrice the spot/futures price the chain is anchored to
     * @param atmStrike the at-the-money strike
     * @param tradingSymbol the futures/option trading symbol requested
     * @param strikeCount number of strikes in the chain
     * @param totalCallOi aggregate call open interest
     * @param totalPutOi aggregate put open interest
     * @param pcr put-call ratio derived from open interest
     * @param strikes the strike list
     */
    public OptionChainResponse(
            String underlying,
            BigDecimal underlyingPrice,
            BigDecimal atmStrike,
            String tradingSymbol,
            int strikeCount,
            long totalCallOi,
            long totalPutOi,
            double pcr,
            List<OptionStrike> strikes) {
        this(
                underlying,
                underlyingPrice,
                atmStrike,
                tradingSymbol,
                strikeCount,
                totalCallOi,
                totalPutOi,
                pcr,
                strikes,
                false);
    }

    /**
     * Whether this payload was synthesized by the fallback rather than fetched from the exchange.
     *
     * @return {@code true} when the chain is fabricated and must not drive a trading decision
     */
    public boolean isSynthetic() {
        return synthetic;
    }

    /**
     * Returns the strike price with maximum Call Open Interest (Resistance level).
     *
     * <p>Returns {@code null} when no strike carries any call open interest. The previous guard
     * started {@code maxOi} at {@code -1}, so a window whose quotes had all failed — every OI zero
     * — still produced a "resistance" strike, which is the lowest one in the list. That fabricated
     * level then fed the OPTION_SR gate.
     *
     * @return the max-call-OI strike, or {@code null} if the window carries no call OI at all
     */
    public BigDecimal maxCallOiStrike() {
        if (strikes == null || strikes.isEmpty()) return null;
        BigDecimal maxStrike = null;
        long maxOi = 0;
        for (OptionStrike s : strikes) {
            if (s.call() != null && s.call().openInterest() > maxOi) {
                maxOi = s.call().openInterest();
                maxStrike = s.strikePrice();
            }
        }
        return maxStrike;
    }

    /**
     * Returns the strike price with maximum Put Open Interest (Support level).
     *
     * <p>Returns {@code null} when no strike carries any put open interest — see {@link
     * #maxCallOiStrike()} for why the zero case must not yield a strike.
     *
     * @return the max-put-OI strike, or {@code null} if the window carries no put OI at all
     */
    public BigDecimal maxPutOiStrike() {
        if (strikes == null || strikes.isEmpty()) return null;
        BigDecimal maxStrike = null;
        long maxOi = 0;
        for (OptionStrike s : strikes) {
            if (s.put() != null && s.put().openInterest() > maxOi) {
                maxOi = s.put().openInterest();
                maxStrike = s.strikePrice();
            }
        }
        return maxStrike;
    }
}
