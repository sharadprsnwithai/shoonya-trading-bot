package com.tradingbot.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.model.Candle;
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

    @Autowired
    public ShoonyaMarketDataService(ShoonyaConfig config, ShoonyaAuthenticator authenticator) {
        this(
                config,
                authenticator,
                new ObjectMapper(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
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
                String formBody = "jData=" + jDataStr + "&jKey=" + sessionToken;

                HttpRequest request =
                        HttpRequest.newBuilder()
                                .uri(URI.create(config.getBaseUrl() + "/NorenWClientAPI/TPSeries"))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .header("X-Forwarded-For", config.resolvePublicIp())
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                formBody, StandardCharsets.UTF_8))
                                .build();

                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body();

                if (body != null
                        && (body.contains("Session Expired")
                                || body.contains("Invalid Session Key")
                                || body.contains("NOT_LOGGED_IN"))) {
                    log.warn(
                            "Shoonya session expired during TPSeries fetch. Invalidating session and retrying (attempt {})...",
                            attempt);
                    authenticator.invalidateSession();
                    continue;
                }

                return parseShoonyaCandles(body, symbol, timeframe);

            } catch (Exception e) {
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
                        long volume = node.path("v").asLong(0);

                        Instant timestamp = parseTimestamp(node);
                        candles.add(
                                new Candle(
                                        symbol, timeframe, timestamp, open, high, low, close,
                                        volume));
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

    private Instant parseTimestamp(JsonNode node) {
        if (node.has("ssboe")) {
            long epochSeconds = node.path("ssboe").asLong(0);
            if (epochSeconds > 0) {
                return Instant.ofEpochSecond(epochSeconds);
            }
        }
        String timeStr = node.path("time").asText("");
        if (!timeStr.isEmpty()) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(timeStr, FORMATTER_SLASH);
                return ldt.atZone(IST).toInstant();
            } catch (Exception e1) {
                try {
                    LocalDateTime ldt = LocalDateTime.parse(timeStr, FORMATTER_DASH);
                    return ldt.atZone(IST).toInstant();
                } catch (Exception ignored) {
                }
            }
        }
        return Instant.now();
    }
}
