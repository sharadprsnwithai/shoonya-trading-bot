package com.tradingbot.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.model.Candle;
import com.tradingbot.util.StockFnoRegistry;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Service for fetching historical candle data from Shoonya (Finvasia NorenAPI) TPSeries. */
@Service
public class ShoonyaMarketDataService {

    private static final Logger log = LoggerFactory.getLogger(ShoonyaMarketDataService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter FORMATTER_SLASH =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final DateTimeFormatter FORMATTER_DASH =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");

    private final ShoonyaConfig config;
    private final ShoonyaAuthenticator authenticator;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<String, String> tokenCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    public ShoonyaMarketDataService(ShoonyaConfig config, ShoonyaAuthenticator authenticator) {
        this(
                config,
                authenticator,
                new ObjectMapper(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public static String buildFormBody(String jDataStr, String sessionToken) {
        StringBuilder sb = new StringBuilder();
        String safeJData = jDataStr != null ? jDataStr.replace("&", "%26") : "";
        sb.append("jData=").append(safeJData);
        if (sessionToken != null) {
            sb.append("&jKey=").append(sessionToken);
        }
        return sb.toString();
    }

    public ShoonyaMarketDataService(
            ShoonyaConfig config,
            ShoonyaAuthenticator authenticator,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.config = config;
        this.authenticator = authenticator;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /**
     * Fetches historical candles for the specified number of days back from now.
     *
     * @param exchange Exchange code (e.g. "NSE", "NFO")
     * @param token Instrument token (e.g. "2885" for RELIANCE)
     * @param symbol Canonical display symbol (e.g. "NSE:RELIANCE")
     * @param timeframe Timeframe in minutes (e.g. "1", "5", "15")
     * @param daysBack Number of calendar days back to fetch
     * @return Chronological list of Candle instances
     */
    /**
     * Fetches hourly (60-minute) candles for any symbol (NIFTY 50 or any of the 29 supported F&O
     * equities). Automatically resolves the exchange and instrument token via StockFnoRegistry /
     * Shoonya SearchScrip.
     *
     * @param symbol Canonical symbol (e.g. "NIFTY50", "ABB", "TATASTEEL")
     * @param daysBack Number of calendar days back to fetch
     * @return Chronological list of 1-hour Candle instances
     */
    public List<Candle> fetchHourlyCandles(String symbol, int daysBack) {
        String token = resolveToken(symbol);
        String exchange = resolveExchange(symbol);
        int boundedDays = Math.max(1, Math.min(daysBack, 95));
        log.info(
                "[HOURLY-DATA] Fetching {} days of 1-hour candles for {} (token: {}, exch: {})",
                boundedDays,
                symbol,
                token,
                exchange);
        return fetchHistoricalCandles(exchange, token, symbol, "60", boundedDays);
    }

    /**
     * Fetches daily (EOD) candles for a symbol.
     *
     * @param symbol Canonical symbol (e.g. "NIFTY50", "ABB")
     * @param daysBack Number of calendar days back to fetch (e.g. 365 for 1 year)
     * @return Chronological list of Daily Candle instances
     */
    public List<Candle> fetchDailyCandles(String symbol, int daysBack) {
        String token = resolveToken(symbol);
        String exchange = resolveExchange(symbol);
        int boundedDays = Math.max(1, Math.min(daysBack, 1200));
        log.info(
                "[DAILY-DATA] Fetching {} days of daily candles for {} (token: {}, exch: {})",
                boundedDays,
                symbol,
                token,
                exchange);
        return fetchHistoricalCandles(exchange, token, symbol, "D", boundedDays);
    }

    /**
     * Fetches 5-minute candles for a symbol.
     *
     * @param symbol Canonical symbol (e.g. "RELIANCE", "TATASTEEL")
     * @param daysBack Number of calendar days back to fetch (e.g. 2 for today and prior day)
     * @return Chronological list of 5-minute Candle instances
     */
    public List<Candle> fetch5MinCandles(String symbol, int daysBack) {
        String token = resolveToken(symbol);
        String exchange = resolveExchange(symbol);
        int boundedDays = Math.max(5, Math.min(daysBack, 15));
        return fetchHistoricalCandles(exchange, token, symbol, "5", boundedDays);
    }

    /**
     * Warms the in-memory token cache at startup with all pre-registered F&O instruments and
     * indices.
     */
    @jakarta.annotation.PostConstruct
    public void warmTokenCache() {
        for (Map.Entry<String, StockFnoRegistry.InstrumentInfo> entry :
                StockFnoRegistry.getAllInstruments().entrySet()) {
            String tok = entry.getValue().token();
            if (isValidNumericToken(tok)) {
                tokenCache.put(entry.getKey(), tok);
            }
        }
        for (Map.Entry<String, com.tradingbot.util.Nifty500Registry.StockMetadata> entry :
                com.tradingbot.util.Nifty500Registry.getAllMetadata().entrySet()) {
            String tok = entry.getValue().token();
            if (isValidNumericToken(tok)) {
                tokenCache.putIfAbsent(entry.getKey(), tok);
            }
        }
        log.info(
                "[MARKET-DATA] Token cache pre-warmed with {} active instruments (F&O + Nifty 500) at startup.",
                tokenCache.size());
    }

    /** Pre-warms the Shoonya API session during daily reset or startup. */
    public void prewarmSession() {
        if (config.isEnabled() && authenticator != null) {
            try {
                authenticator.getOrAuthenticateToken();
                log.info("[MARKET-DATA] Session pre-warmed successfully.");
            } catch (Exception e) {
                log.warn("[MARKET-DATA] Failed to pre-warm session: {}", e.getMessage());
            }
        }
    }

    private boolean isValidNumericToken(String token) {
        return token != null && !token.isBlank() && token.matches("\\d+");
    }

    /**
     * Resolves the instrument token for a symbol using cache, StockFnoRegistry, Nifty200Registry,
     * or Shoonya SearchScrip.
     */
    public String resolveToken(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "26000";
        }
        String clean = symbol.toUpperCase().trim();
        if (clean.startsWith("NSE:")) clean = clean.substring(4);
        if ("NIFTY".equalsIgnoreCase(clean)
                || "NIFTY 50".equalsIgnoreCase(clean)
                || "NIFTY_50".equalsIgnoreCase(clean)) {
            clean = "NIFTY50";
        }

        if (tokenCache.containsKey(clean)) {
            String cached = tokenCache.get(clean);
            if (isValidNumericToken(cached)) {
                return cached;
            }
        }

        String registeredToken = StockFnoRegistry.getToken(clean);
        if (isValidNumericToken(registeredToken)) {
            tokenCache.put(clean, registeredToken);
            return registeredToken;
        }

        var n200Meta = com.tradingbot.util.Nifty200Registry.getMetadata(clean);
        if (n200Meta != null && isValidNumericToken(n200Meta.token())) {
            tokenCache.put(clean, n200Meta.token());
            return n200Meta.token();
        }

        var n500Meta = com.tradingbot.util.Nifty500Registry.getMetadata(clean);
        if (n500Meta != null && isValidNumericToken(n500Meta.token())) {
            tokenCache.put(clean, n500Meta.token());
            return n500Meta.token();
        }

        // Fallback: Query SearchScrip API from Shoonya
        try {
            JsonNode searchRes = searchScrip("NSE", clean);
            if (searchRes != null && searchRes.isArray() && !searchRes.isEmpty()) {
                for (JsonNode item : searchRes) {
                    String tsym = item.path("tsym").asText("");
                    if (tsym.equalsIgnoreCase(clean + "-EQ") || tsym.equalsIgnoreCase(clean)) {
                        String tok = item.path("token").asText("");
                        if (isValidNumericToken(tok)) {
                            tokenCache.put(clean, tok);
                            return tok;
                        }
                    }
                }
                String tok = searchRes.get(0).path("token").asText("");
                if (isValidNumericToken(tok)) {
                    tokenCache.put(clean, tok);
                    return tok;
                }
            }
        } catch (Exception e) {
            log.warn(
                    "[MARKET-DATA] Failed to resolve token via SearchScrip for {}: {}",
                    clean,
                    e.getMessage());
        }

        if ("NIFTY50".equalsIgnoreCase(clean)
                || "NIFTY".equalsIgnoreCase(clean)
                || "NIFTY 50".equalsIgnoreCase(clean)) {
            return "26000";
        }
        log.warn("[MARKET-DATA] Unable to resolve token for symbol: {}", clean);
        return null;
    }

    /** Resolves the exchange for a given symbol (e.g. "NSE", "BSE", "MCX", "NFO"). */
    public String resolveExchange(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "NSE";
        }
        String clean = symbol.toUpperCase().trim();
        if (clean.startsWith("NSE:")) return "NSE";
        if (clean.startsWith("BSE:")) return "BSE";
        if (clean.startsWith("MCX:")) return "MCX";
        if (clean.startsWith("NFO:")) return "NFO";

        if (com.tradingbot.util.CommodityRegistry.isCommodity(clean)) {
            var meta = com.tradingbot.util.CommodityRegistry.getMetadata(clean);
            return (meta != null && meta.exchange() != null) ? meta.exchange() : "MCX";
        }

        var fnoInfo = StockFnoRegistry.get(clean);
        if (fnoInfo != null && fnoInfo.exchange() != null && !fnoInfo.exchange().isBlank()) {
            return fnoInfo.exchange();
        }

        var n500 = com.tradingbot.util.Nifty500Registry.getMetadata(clean);
        if (n500 != null && n500.exchange() != null && !n500.exchange().isBlank()) {
            return n500.exchange();
        }

        return "NSE";
    }

    /** Fetches real-time quote for a token from Shoonya GetQuotes API. */
    public JsonNode fetchQuote(String exchange, String token) {
        if (!config.isEnabled() || token == null || token.isBlank()) {
            return null;
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String sessionToken = authenticator.getOrAuthenticateToken();
                Map<String, Object> payload =
                        Map.of(
                                "uid",
                                config.getUserId(),
                                "actid",
                                config.getUserId(),
                                "exch",
                                exchange != null ? exchange : "NSE",
                                "token",
                                token);
                String body = buildFormBody(objectMapper.writeValueAsString(payload), sessionToken);

                HttpRequest req =
                        HttpRequest.newBuilder()
                                .uri(URI.create(config.getBaseUrl() + "/NorenWClientAPI/GetQuotes"))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .header("X-Forwarded-For", config.resolvePublicIp())
                                .timeout(Duration.ofSeconds(10))
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                body, StandardCharsets.UTF_8))
                                .build();

                HttpResponse<String> resp =
                        httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                String respBody = resp.body();

                if (isSessionExpired(resp.statusCode(), respBody)) {
                    log.warn(
                            "Shoonya session expired (HTTP {}) during GetQuotes fetch (attempt {}). Invalidating session and retrying...",
                            resp.statusCode(),
                            attempt);
                    authenticator.invalidateSession();
                    continue;
                }

                if (resp.statusCode() == 400
                        && respBody != null
                        && respBody.contains("exceeds Limit 10")) {
                    log.warn(
                            "[QUOTE] Rate limit reached fetching token {}. Backing off 250ms (attempt {})...",
                            token,
                            attempt);
                    Thread.sleep(250);
                    continue;
                }

                if (resp.statusCode() != 200) {
                    log.error(
                            "[QUOTE] HTTP error {} fetching quote for token {}: {}",
                            resp.statusCode(),
                            token,
                            respBody);
                    return null;
                }
                return objectMapper.readTree(respBody);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn(
                        "[QUOTE] Error fetching quote for token {} (attempt {}): {}",
                        token,
                        attempt,
                        e.getMessage());
            }
        }
        return null;
    }

    /** Searches for scrips on Shoonya using the SearchScrip API. */
    public JsonNode searchScrip(String exchange, String searchText) {
        if (!config.isEnabled()) {
            return null;
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String sessionToken = authenticator.getOrAuthenticateToken();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("uid", config.getUserId());
                payload.put("exch", exchange != null ? exchange : "NSE");
                payload.put("stext", searchText);

                String jDataStr = objectMapper.writeValueAsString(payload);
                String formBody = buildFormBody(jDataStr, sessionToken);

                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(
                                        URI.create(
                                                config.getBaseUrl()
                                                        + "/NorenWClientAPI/SearchScrip"))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .header("X-Forwarded-For", config.resolvePublicIp())
                                .timeout(Duration.ofSeconds(10))
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                formBody, StandardCharsets.UTF_8))
                                .build();

                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body();

                if (isSessionExpired(response.statusCode(), body)) {
                    log.warn(
                            "[SEARCH-SCRIP] Shoonya session expired (HTTP {}) searching for {}. Invalidating session and retrying (attempt {})...",
                            response.statusCode(),
                            searchText,
                            attempt);
                    authenticator.invalidateSession();
                    continue;
                }

                if (response.statusCode() == 400
                        && body != null
                        && body.contains("exceeds Limit 10")) {
                    log.warn(
                            "[SEARCH-SCRIP] Rate limit reached searching for {}. Backing off 250ms (attempt {})...",
                            searchText,
                            attempt);
                    Thread.sleep(250);
                    continue;
                }

                if (response.statusCode() != 200) {
                    log.error(
                            "[SEARCH-SCRIP] HTTP error {} searching for {}: {}",
                            response.statusCode(),
                            searchText,
                            body);
                    return null;
                }
                JsonNode root = objectMapper.readTree(body);
                if ("Ok".equalsIgnoreCase(root.path("stat").asText())) {
                    return root.path("values");
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn(
                        "[SEARCH-SCRIP] Error searching for scrip {} (attempt {}): {}",
                        searchText,
                        attempt,
                        e.getMessage());
            }
        }
        return null;
    }

    /**
     * Fetches historical candles for the specified number of days back from now.
     *
     * @param exchange Exchange code (e.g. "NSE", "NFO")
     * @param token Instrument token (e.g. "2885" for RELIANCE)
     * @param symbol Canonical display symbol (e.g. "NSE:RELIANCE")
     * @param timeframe Timeframe in minutes (e.g. "1", "5", "15")
     * @param daysBack Number of calendar days back to fetch
     * @return Chronological list of Candle instances
     */
    public List<Candle> fetchHistoricalCandles(
            String exchange, String token, String symbol, String timeframe, int daysBack) {
        long endEpoch = Instant.now().getEpochSecond();
        long startEpoch = endEpoch - ((long) daysBack * 24 * 3600);
        return fetchHistoricalCandles(exchange, token, symbol, timeframe, startEpoch, endEpoch);
    }

    /** Fetches historical candles for a specific epoch time range. */
    public List<Candle> fetchHistoricalCandles(
            String exchange,
            String token,
            String symbol,
            String timeframe,
            long startEpoch,
            long endEpoch) {
        if (!config.isEnabled()) {
            log.info("Shoonya is disabled. Returning empty candle list for {}", symbol);
            return Collections.emptyList();
        }
        if (token == null || token.isBlank()) {
            log.warn("Cannot fetch candles for {} because token is null/blank", symbol);
            return Collections.emptyList();
        }

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String sessionToken = authenticator.getOrAuthenticateToken();

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("uid", config.getUserId());
                payload.put("exch", exchange != null ? exchange : "NSE");
                payload.put("token", token);
                payload.put("st", String.valueOf(startEpoch));
                payload.put("et", String.valueOf(endEpoch));
                payload.put("intrv", timeframe);

                String jDataStr = objectMapper.writeValueAsString(payload);
                String formBody = buildFormBody(jDataStr, sessionToken);

                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(URI.create(config.getBaseUrl() + "/NorenWClientAPI/TPSeries"))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .header("X-Forwarded-For", config.resolvePublicIp())
                                .timeout(Duration.ofSeconds(10))
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                formBody, StandardCharsets.UTF_8))
                                .build();

                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body();

                if (isSessionExpired(response.statusCode(), body)) {
                    log.warn(
                            "Shoonya session expired (HTTP {}) during TPSeries fetch for {}. Invalidating session and retrying (attempt {})...",
                            response.statusCode(),
                            symbol,
                            attempt);
                    authenticator.invalidateSession();
                    continue;
                }

                if (response.statusCode() == 400
                        && body != null
                        && body.contains("exceeds Limit 10")) {
                    log.warn(
                            "Rate limit reached fetching TPSeries for {}. Backing off 250ms (attempt {})...",
                            symbol,
                            attempt);
                    Thread.sleep(250);
                    continue;
                }

                if (response.statusCode() != 200) {
                    log.error(
                            "HTTP error {} fetching TPSeries for {}: {}",
                            response.statusCode(),
                            symbol,
                            body);
                    return Collections.emptyList();
                }

                return parseShoonyaCandles(body, symbol, timeframe);

            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn(
                        "Error fetching Shoonya TPSeries for {} (attempt {}): {}",
                        symbol,
                        attempt,
                        e.getMessage());
                if (attempt == 2) {
                    return Collections.emptyList();
                }
            }
        }
        return Collections.emptyList();
    }

    /** Parses the Shoonya TPSeries JSON response into Candle instances. */
    public List<Candle> parseShoonyaCandles(String responseBody, String symbol, String timeframe) {
        List<Candle> candles = new ArrayList<>();
        if (responseBody == null || responseBody.isBlank()) {
            return candles;
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    if ("Ok".equalsIgnoreCase(node.path("stat").asText("Ok")) || node.has("into")) {
                        BigDecimal open = new BigDecimal(node.path("into").asText("0"));
                        BigDecimal high = new BigDecimal(node.path("inth").asText("0"));
                        BigDecimal low = new BigDecimal(node.path("intl").asText("0"));
                        BigDecimal close = new BigDecimal(node.path("intc").asText("0"));
                        long volume = 0;
                        if (node.has("intv")) {
                            volume = Math.abs(node.path("intv").asLong(0));
                        }
                        if (volume == 0 && node.has("v")) {
                            volume = node.path("v").asLong(0);
                        }

                        Instant timestamp = parseTimestamp(node);
                        if (open.compareTo(BigDecimal.ZERO) > 0
                                && high.compareTo(BigDecimal.ZERO) > 0
                                && low.compareTo(BigDecimal.ZERO) > 0
                                && close.compareTo(BigDecimal.ZERO) > 0) {
                            candles.add(
                                    new Candle(
                                            symbol, timeframe, timestamp, open, high, low, close,
                                            volume));
                        }
                    }
                }
            } else if (root.isObject() && "Not_Ok".equalsIgnoreCase(root.path("stat").asText())) {
                log.warn(
                        "Shoonya TPSeries returned error for {}: {}",
                        symbol,
                        root.path("emsg").asText("Unknown error"));
            }
        } catch (Exception e) {
            log.error("Failed to parse Shoonya TPSeries response: {}", responseBody, e);
        }

        candles.sort(Comparator.comparing(Candle::timestamp));
        return candles;
    }

    private boolean isSessionExpired(int statusCode, String body) {
        if (statusCode == 401 || statusCode == 403) {
            return true;
        }
        if (body == null || body.isBlank()) {
            return false;
        }
        return body.contains("Session Expired")
                || body.contains("Invalid Session Key")
                || body.contains("NOT_LOGGED_IN")
                || body.contains("Invalid Token")
                || body.contains("INVALID_SESSION");
    }

    private Instant parseTimestamp(JsonNode node) {
        if (node.has("ssboe")) {
            long epochSeconds = node.path("ssboe").asLong(0);
            if (epochSeconds > 0) {
                return Instant.ofEpochSecond(epochSeconds);
            }
        }
        String timeStr = node.path("time").asText("");
        if (!timeStr.isEmpty()) {
            String clean = timeStr.trim();
            // Handle date-only "dd-MM-yyyy", "dd/MM/yyyy", "yyyy-MM-dd"
            if (clean.length() == 10) {
                try {
                    if (clean.charAt(2) == '-' || clean.charAt(2) == '/') {
                        DateTimeFormatter fmt =
                                clean.charAt(2) == '-'
                                        ? DateTimeFormatter.ofPattern("dd-MM-yyyy")
                                        : DateTimeFormatter.ofPattern("dd/MM/yyyy");
                        return java.time.LocalDate.parse(clean, fmt).atStartOfDay(IST).toInstant();
                    } else if (clean.charAt(4) == '-') {
                        return java.time.LocalDate.parse(clean, DateTimeFormatter.ISO_LOCAL_DATE)
                                .atStartOfDay(IST)
                                .toInstant();
                    }
                } catch (Exception ignored) {
                }
            }
            try {
                LocalDateTime ldt = LocalDateTime.parse(clean, FORMATTER_SLASH);
                return ldt.atZone(IST).toInstant();
            } catch (Exception e1) {
                try {
                    LocalDateTime ldt = LocalDateTime.parse(clean, FORMATTER_DASH);
                    return ldt.atZone(IST).toInstant();
                } catch (Exception e2) {
                    try {
                        LocalDateTime ldt =
                                LocalDateTime.parse(
                                        clean, DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm"));
                        return ldt.atZone(IST).toInstant();
                    } catch (Exception e3) {
                        try {
                            LocalDateTime ldt =
                                    LocalDateTime.parse(
                                            clean, DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
                            return ldt.atZone(IST).toInstant();
                        } catch (Exception e4) {
                            try {
                                LocalDateTime ldt =
                                        LocalDateTime.parse(
                                                clean, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                                return ldt.atZone(IST).toInstant();
                            } catch (Exception e5) {
                                log.debug(
                                        "Failed parsing Shoonya candle time string '{}', defaulting to now",
                                        timeStr);
                            }
                        }
                    }
                }
            }
        }
        return Instant.now();
    }
}
