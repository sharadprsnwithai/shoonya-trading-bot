package com.tradingbot.strategy.commodity.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.OptionChainResponse;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Live {@link CommodityQuoteFeed} backed by the Shoonya broker API. */
@Component
public class ShoonyaCommodityQuoteFeed implements CommodityQuoteFeed {

    private static final Logger log = LoggerFactory.getLogger(ShoonyaCommodityQuoteFeed.class);

    private final ShoonyaOptionChainService optionChainService;
    private final ShoonyaMarketDataService marketDataService;

    @Autowired
    public ShoonyaCommodityQuoteFeed(
            ShoonyaOptionChainService optionChainService,
            ShoonyaMarketDataService marketDataService) {
        this.optionChainService = optionChainService;
        this.marketDataService = marketDataService;
    }

    /**
     * Fetches the option chain used for the daily PCR bias.
     *
     * <p>The window is ATM ± {@link ShoonyaOptionChainService#PCR_STRIKE_WINDOW} so commodities
     * read the same strike window as the LVR equity gate. The chain may come back {@code synthetic}
     * when the exchange has no listed contract for the symbol — callers must treat that as
     * unavailable rather than as a neutral PCR.
     *
     * @param symbol the MCX underlying (e.g. {@code SILVER})
     * @return the live chain, or {@code null} when no contract resolves
     */
    @Override
    public OptionChainResponse optionChain(String symbol) {
        if (optionChainService == null || symbol == null) return null;
        String clean = symbol.trim().toUpperCase();
        try {
            ShoonyaMarketDataService.FuturesContract fut =
                    marketDataService != null
                            ? marketDataService.resolveFuturesContract(clean)
                            : null;
            if (fut == null || fut.tsym() == null || fut.tsym().isBlank()) {
                log.warn(
                        "[COMMODITY-FEED] No F&O contract resolved for {} on MCX — option chain "
                                + "unavailable, PCR cannot be computed.",
                        clean);
                return null;
            }
            String futTsym = fut.tsym();
            String futToken = fut.token() != null ? fut.token() : "";
            return optionChainService.getOptionChain(
                    clean,
                    futTsym,
                    futToken,
                    null,
                    ShoonyaOptionChainService.PCR_STRIKE_WINDOW,
                    true);
        } catch (Exception e) {
            log.error(
                    "[COMMODITY-FEED] Error fetching option chain for {}: {}",
                    clean,
                    e.getMessage(),
                    e);
            return null;
        }
    }

    @Override
    public java.time.LocalDate contractExpiry(String symbol) {
        if (marketDataService == null || symbol == null) return null;
        String clean = symbol.trim().toUpperCase();
        try {
            ShoonyaMarketDataService.FuturesContract fut =
                    marketDataService.resolveFuturesContract(clean);
            if (fut == null || fut.tsym() == null || fut.tsym().isBlank()) return null;
            return marketDataService.parseContractExpiry(fut.tsym(), clean.length());
        } catch (Exception e) {
            log.debug(
                    "[COMMODITY-FEED] Error resolving contract expiry for {}: {}",
                    clean,
                    e.getMessage());
            return null;
        }
    }

    @Override
    public List<Candle> candles15m(String symbol, int daysBack) {
        if (marketDataService == null) return null;
        try {
            return marketDataService.fetch15MinCandles(symbol, daysBack);
        } catch (Exception e) {
            log.debug(
                    "[COMMODITY-FEED] Error fetching 15m candles for {}: {}",
                    symbol,
                    e.getMessage());
            return null;
        }
    }

    @Override
    public BigDecimal liveLtp(String symbol) {
        try {
            if (marketDataService == null) return null;
            String clean = symbol != null ? symbol.trim().toUpperCase() : "";
            String exchange = marketDataService.resolveExchange(clean);
            String token = marketDataService.resolveToken(clean);
            if (token == null || token.isBlank()) {
                String parent =
                        clean.endsWith("M") ? clean.substring(0, clean.length() - 1) : clean;
                token = marketDataService.resolveToken(parent);
            }
            if (token != null && !token.isBlank()) {
                JsonNode quote = marketDataService.fetchQuote(exchange, token);
                if (quote != null && quote.has("lp")) {
                    return new BigDecimal(quote.path("lp").asText());
                }
            }
        } catch (Exception e) {
            log.debug("[COMMODITY-FEED] Error fetching LTP for {}: {}", symbol, e.getMessage());
        }
        return null;
    }
}
