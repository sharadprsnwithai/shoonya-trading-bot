package com.tradingbot.strategy.commodity.feed;

import com.tradingbot.model.Candle;
import com.tradingbot.model.OptionChainResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Replay implementation of {@link CommodityQuoteFeed} serving historical 15m candles truncated at a
 * mutable "now", so the real {@code CommodityVwapStrategyService} can be driven bar-by-bar in
 * backtests.
 *
 * <p>Because Shoonya exposes no historical option chains, {@link #optionChain(String)} synthesizes
 * a chain whose PCR encodes a <b>price/VWAP proxy bias</b> (set by the harness via {@link
 * #setProxyPcr(Double)}). This keeps {@code evaluateDailyBias()} on the production code path while
 * making clear that the backtest validates every rule EXCEPT the live PCR signal itself, which is
 * validated by forward paper trading.
 */
public class ReplayCommodityQuoteFeed implements CommodityQuoteFeed {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final List<Candle> history;
    private volatile Instant now = Instant.EPOCH;
    private volatile BigDecimal pendingLtp;
    private volatile Double proxyPcr;
    private volatile LocalDate contractExpiry;

    public ReplayCommodityQuoteFeed(List<Candle> history) {
        this.history = history == null ? List.of() : List.copyOf(history);
    }

    /** Advances replay time; candles after this instant are invisible to the strategy. */
    public void advanceTo(Instant instant) {
        this.now = instant;
    }

    /** The price the strategy will observe on the next {@link #liveLtp(String)} call. */
    public void setPendingLtp(BigDecimal ltp) {
        this.pendingLtp = ltp;
    }

    /** Proxy PCR (1.30 bullish / 0.70 bearish) derived from the price/VWAP bias rule. */
    public void setProxyPcr(Double pcr) {
        this.proxyPcr = pcr;
    }

    /** Near-month contract expiry surfaced to the strategy's DTE safety gate (null = unknown). */
    public void setContractExpiry(LocalDate expiry) {
        this.contractExpiry = expiry;
    }

    @Override
    public LocalDate contractExpiry(String symbol) {
        return contractExpiry;
    }

    @Override
    public BigDecimal liveLtp(String symbol) {
        return pendingLtp;
    }

    @Override
    public List<Candle> candles15m(String symbol, int daysBack) {
        int boundedDays = Math.max(5, Math.min(daysBack, 15));
        LocalDate floor = LocalDate.ofInstant(now, IST).minusDays(boundedDays);
        List<Candle> out = new ArrayList<>();
        for (Candle c : history) {
            if (c.timestamp().isAfter(now)) break;
            if (!LocalDate.ofInstant(c.timestamp(), IST).isBefore(floor)) out.add(c);
        }
        return out;
    }

    @Override
    public OptionChainResponse optionChain(String symbol) {
        Double pcr = proxyPcr;
        if (pcr == null) return null;
        double rounded = Math.round(pcr * 100.0) / 100.0;
        long callOi = 10_000L;
        long putOi = Math.max(1L, Math.round(rounded * callOi));
        return new OptionChainResponse(
                symbol,
                BigDecimal.valueOf(5000),
                BigDecimal.valueOf(5000),
                symbol + "-FUT",
                5,
                callOi,
                putOi,
                rounded,
                List.of());
    }
}
