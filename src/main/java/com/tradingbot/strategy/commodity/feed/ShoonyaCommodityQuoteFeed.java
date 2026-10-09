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

    @Override
    public OptionChainResponse optionChain(String symbol) {
        if (optionChainService == null || symbol == null) return null;
        String clean = symbol.trim().toUpperCase();
        try {
            ShoonyaMarketDataService.FuturesContract fut =
                    marketDataService != null
                            ? marketDataService.resolveFuturesContract(clean)
                            : null;
            String futTsym = fut != null ? fut.tsym() : clean;
            String futToken = fut != null ? fut.token() : "";
            return optionChainService.getOptionChain(clean, futTsym, futToken, null, 5, true);
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
