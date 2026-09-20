package com.tradingbot.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service to fetch historical Daily OHLC candle data directly from Yahoo Finance Chart API v8.
 * Provides resilient fallback and fast pre-market historical synchronization.
 */
@Service
public class YahooFinanceService {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceService.class);
    private static final String YAHOO_CHART_URL_PREFIX =
            "https://query1.finance.yahoo.com/v8/finance/chart/";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public YahooFinanceService(ObjectMapper objectMapper) {
        this(
                objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build());
    }

    public YahooFinanceService(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /**
     * Converts a local symbol (e.g. "RELIANCE", "M&M", "NIFTY 50") into a Yahoo Finance ticker
     * (e.g. "RELIANCE.NS", "M%26M.NS", "%5ENSEI").
     */
    public String toYahooTicker(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "%5ENSEI";
        }
        String clean = symbol.trim();
        if (clean.startsWith("NSE:")) {
            clean = clean.substring(4).trim();
        }

        if ("NIFTY 50".equalsIgnoreCase(clean)
                || "NIFTY50".equalsIgnoreCase(clean)
                || "NIFTY".equalsIgnoreCase(clean)
                || "^NSEI".equalsIgnoreCase(clean)
                || "%5ENSEI".equalsIgnoreCase(clean)) {
            return "%5ENSEI";
        }

        if ("NIFTY BANK".equalsIgnoreCase(clean)
                || "BANKNIFTY".equalsIgnoreCase(clean)
                || "BANK NIFTY".equalsIgnoreCase(clean)
                || "^NSEBANK".equalsIgnoreCase(clean)
                || "%5ENSEBANK".equalsIgnoreCase(clean)) {
            return "%5ENSEBANK";
        }

        if ("FINNIFTY".equalsIgnoreCase(clean)
                || "NIFTY FIN SERVICE".equalsIgnoreCase(clean)
                || "NIFTY FINANCIAL SERVICES".equalsIgnoreCase(clean)
                || "^CNXFIN".equalsIgnoreCase(clean)
                || "%5ECNXFIN".equalsIgnoreCase(clean)) {
            return "%5ECNXFIN";
        }

        if ("MIDCPNIFTY".equalsIgnoreCase(clean)
                || "NIFTY MID SELECT".equalsIgnoreCase(clean)
                || "^NSEMDCP".equalsIgnoreCase(clean)
                || "%5ENSEMDCP".equalsIgnoreCase(clean)) {
            return "%5ENSEMDCP";
        }

        if ("SENSEX".equalsIgnoreCase(clean)
                || "BSESN".equalsIgnoreCase(clean)
                || "^BSESN".equalsIgnoreCase(clean)
                || "%5EBSESN".equalsIgnoreCase(clean)) {
            return "%5EBSESN";
        }

        if (clean.startsWith("^")) {
            return "%5E" + clean.substring(1);
        }

        String encodedSymbol = URLEncoder.encode(clean, StandardCharsets.UTF_8).replace("+", "%20");
        return encodedSymbol + ".NS";
    }

    /**
     * Fetches daily OHLC candles for the specified number of years back from Yahoo Finance.
     *
     * @param symbol Symbol name (e.g. "RELIANCE", "NIFTY 50")
     * @param yearsBack Range in years (e.g. 2 for range=2y)
     * @return Chronological list of daily Candle objects, or empty list on failure
     */
    public List<Candle> fetchDailyCandles(String symbol, int yearsBack) {
        String ticker = toYahooTicker(symbol);
        int boundedYears = Math.max(1, Math.min(yearsBack, 5));
        String url = YAHOO_CHART_URL_PREFIX + ticker + "?range=" + boundedYears + "y&interval=1d";
        return fetchCandlesInternal(symbol, "D", ticker, url);
    }

    /**
     * Fetches 5-minute intraday OHLC candles for the specified number of days back from Yahoo
     * Finance.
     *
     * @param symbol Symbol name (e.g. "RELIANCE", "NIFTY 50", "TATASTEEL")
     * @param daysBack Range in days (e.g. 5 or 7 days)
     * @return Chronological list of 5-minute Candle objects, or empty list on failure
     */
    public List<Candle> fetch5MinCandles(String symbol, int daysBack) {
        String ticker = toYahooTicker(symbol);
        int boundedDays = Math.max(1, Math.min(daysBack, 60));
        String url = YAHOO_CHART_URL_PREFIX + ticker + "?range=" + boundedDays + "d&interval=5m";
        return fetchCandlesInternal(symbol, "5", ticker, url);
    }

    private List<Candle> fetchCandlesInternal(
            String symbol, String timeframe, String ticker, String url) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpRequest req =
                        HttpRequest.newBuilder()
                                .uri(URI.create(url))
                                .header("User-Agent", USER_AGENT)
                                .header("Accept", "application/json")
                                .timeout(Duration.ofSeconds(10))
                                .GET()
                                .build();

                HttpResponse<String> resp =
                        httpClient.send(req, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 200) {
                    List<Candle> candles = parseChartResponse(symbol, timeframe, resp.body());
                    if (!candles.isEmpty()) {
                        return candles;
                    }
                } else if (resp.statusCode() == 429) {
                    log.warn(
                            "[YAHOO-FINANCE] Rate limited (HTTP 429) fetching {}. Backing off (attempt {})...",
                            ticker,
                            attempt);
                    Thread.sleep(500);
                } else {
                    log.warn(
                            "[YAHOO-FINANCE] HTTP {} fetching {} from Yahoo Finance: {}",
                            resp.statusCode(),
                            ticker,
                            resp.body());
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn(
                        "[YAHOO-FINANCE] Error fetching {} (attempt {}): {}",
                        ticker,
                        attempt,
                        e.getMessage());
            }
        }
        return Collections.emptyList();
    }

    /** Parses Yahoo Finance v8 chart JSON response into a sorted list of daily Candle objects. */
    public List<Candle> parseChartResponse(String symbol, String json) {
        return parseChartResponse(symbol, "D", json);
    }

    /**
     * Parses Yahoo Finance v8 chart JSON response into a sorted list of Candle objects for a
     * specific timeframe.
     */
    public List<Candle> parseChartResponse(String symbol, String timeframe, String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode chart = root.path("chart");
            JsonNode error = chart.path("error");
            if (!error.isNull() && !error.isMissingNode() && !error.isEmpty()) {
                log.warn(
                        "[YAHOO-FINANCE] Yahoo chart API error for {}: {}",
                        symbol,
                        error.toString());
                return Collections.emptyList();
            }

            JsonNode resultArr = chart.path("result");
            if (!resultArr.isArray() || resultArr.isEmpty()) {
                return Collections.emptyList();
            }

            JsonNode res = resultArr.get(0);
            JsonNode timestamps = res.path("timestamp");
            JsonNode indicators = res.path("indicators");
            JsonNode quoteArr = indicators.path("quote");

            if (!timestamps.isArray() || !quoteArr.isArray() || quoteArr.isEmpty()) {
                return Collections.emptyList();
            }

            JsonNode quote = quoteArr.get(0);
            JsonNode opens = quote.path("open");
            JsonNode highs = quote.path("high");
            JsonNode lows = quote.path("low");
            JsonNode closes = quote.path("close");
            JsonNode volumes = quote.path("volume");

            int size = timestamps.size();
            List<Candle> candles = new ArrayList<>(size);

            for (int i = 0; i < size; i++) {
                JsonNode tsNode = timestamps.get(i);
                JsonNode oNode = opens.get(i);
                JsonNode hNode = highs.get(i);
                JsonNode lNode = lows.get(i);
                JsonNode cNode = closes.get(i);
                JsonNode vNode = volumes.get(i);

                if (tsNode == null
                        || tsNode.isNull()
                        || oNode == null
                        || oNode.isNull()
                        || hNode == null
                        || hNode.isNull()
                        || lNode == null
                        || lNode.isNull()
                        || cNode == null
                        || cNode.isNull()) {
                    continue; // Skip holidays / empty slots
                }

                long epochSec = tsNode.asLong();
                Instant instant = Instant.ofEpochSecond(epochSec);
                BigDecimal open = BigDecimal.valueOf(oNode.asDouble());
                BigDecimal high = BigDecimal.valueOf(hNode.asDouble());
                BigDecimal low = BigDecimal.valueOf(lNode.asDouble());
                BigDecimal close = BigDecimal.valueOf(cNode.asDouble());
                long volume = (vNode != null && !vNode.isNull()) ? vNode.asLong() : 0L;

                candles.add(new Candle(symbol, timeframe, instant, open, high, low, close, volume));
            }

            candles.sort(Comparator.comparing(Candle::timestamp));
            return candles;

        } catch (Exception e) {
            log.error(
                    "[YAHOO-FINANCE] Failed to parse chart response for {}: {}",
                    symbol,
                    e.getMessage(),
                    e);
            return Collections.emptyList();
        }
    }
}
