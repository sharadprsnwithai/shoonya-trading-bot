package com.tradingbot.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.model.NseOiSpurtItem;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HTTP client for NSE's live OI spurts feed ({@code /api/live-analysis-oi-spurts-underlyings}),
 * consumed by the LVR strategy's 09:25 IST phase-2 candidate selection.
 *
 * <p>Resilience contract (NSE sits behind Akamai bot management): browser headers + a shared {@link
 * CookieManager}, up to {@code max-attempts} tries with linear backoff, a homepage cookie warm-up
 * before retrying after HTTP 403, a stale-{@code currTradingDate} guard against cached payloads,
 * and an empty-list (never-throwing) result on any failure so the strategy degrades to sector-only
 * selection.
 */
@Component
public class NseOiSpurtClient {

    private static final Logger log = LoggerFactory.getLogger(NseOiSpurtClient.class);

    static final String DEFAULT_URL =
            "https://www.nseindia.com/api/live-analysis-oi-spurts-underlyings";
    private static final String HOMEPAGE_URL = "https://www.nseindia.com";
    private static final String REFERER = "https://www.nseindia.com/market-data/oi-spurts";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/124.0.0.0 Safari/537.36";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** Broad-market index underlyings that must never be selected as stock candidates. */
    private static final Set<String> EXCLUDED_INDICES =
            Set.of(
                    "NIFTY",
                    "NIFTY 50",
                    "BANKNIFTY",
                    "BANK NIFTY",
                    "FINNIFTY",
                    "MIDCPNIFTY",
                    "NIFTYNXT50",
                    "NIFTYFPI",
                    "NIFTY NEXT 50",
                    "NIFTY MIDCAP 50",
                    "NIFTY FINANCIAL SERVICES",
                    "NIFTY FIN SERVICE");

    private static final DateTimeFormatter TRADING_DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.US);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private volatile Clock clock = Clock.system(ZoneId.of("Asia/Kolkata"));

    @Value("${trading-bot.strategy.lowest-volume.nse-oi.url:" + DEFAULT_URL + "}")
    private volatile String url = DEFAULT_URL;

    @Value("${trading-bot.strategy.lowest-volume.nse-oi.max-attempts:3}")
    private volatile int maxAttempts = 3;

    /** Linear retry backoff base in milliseconds (attempt N sleeps {@code base * N}). */
    private volatile long retryBackoffMs = 500;

    /**
     * Spring constructor: builds a dedicated HttpClient with cookie handling so Akamai {@code
     * _abck}/{@code bm_sz} cookies set by one request are replayed on the next.
     *
     * @param objectMapper Jackson mapper used for payload parsing
     */
    @Autowired
    public NseOiSpurtClient(ObjectMapper objectMapper) {
        this(
                objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build());
    }

    /**
     * Injectable constructor for tests (mock HttpClient + fixed clock).
     *
     * @param objectMapper Jackson mapper used for payload parsing
     * @param httpClient HTTP transport to use for all requests
     */
    public NseOiSpurtClient(ObjectMapper objectMapper, HttpClient httpClient) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /**
     * Fetches the NSE OI spurts feed and returns the top {@code topN} stock underlyings ranked by
     * percentage change in OI (NSE's own ordering), excluding index underlyings.
     *
     * <p>Never throws; returns an empty list after {@code max-attempts} failed attempts (each
     * failure is logged at WARN) so callers can fall back to sector-only selection.
     *
     * @param topN maximum number of underlyings to return (values &lt;= 0 yield an empty list)
     * @return ranked stock underlyings, size {@code <= topN}, or empty on total failure
     */
    public List<NseOiSpurtItem> fetchTopUnderlyings(int topN) {
        if (topN <= 0) {
            return Collections.emptyList();
        }
        int attempts = Math.max(1, maxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            List<NseOiSpurtItem> items = fetchOnce(attempt, attempts);
            if (!items.isEmpty()) {
                return items.size() <= topN ? items : items.subList(0, topN);
            }
            if (attempt < attempts) {
                sleepQuietly(retryBackoffMs * attempt);
            }
        }
        log.warn(
                "[NSE-OI] Giving up after {} attempt(s) — LVR continues without OI spurts"
                        + " (sector-only selection).",
                attempts);
        return Collections.emptyList();
    }

    /**
     * Parses and validates a raw OI spurts payload: rejects stale {@code currTradingDate} values
     * (NSE may serve cached data), drops index underlyings, and ranks the remaining stocks by
     * {@code avgInOI} descending.
     *
     * @param json raw JSON response body
     * @return stock underlyings ranked by % OI change, or empty on stale/malformed payloads
     */
    public List<NseOiSpurtItem> parsePayload(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                log.warn("[NSE-OI] Payload has no data array — treating as failure.");
                return Collections.emptyList();
            }

            String currTradingDate = root.path("currTradingDate").asText("");
            String expected = java.time.LocalDate.now(clock).format(TRADING_DATE_FMT);
            if (!expected.equalsIgnoreCase(currTradingDate.trim())) {
                log.warn(
                        "[NSE-OI] Stale payload: currTradingDate={} but expected {} — NSE served"
                                + " cached data. Treating as failure.",
                        currTradingDate,
                        expected);
                return Collections.emptyList();
            }

            List<NseOiSpurtItem> items = new ArrayList<>();
            for (JsonNode node : data) {
                String symbol = node.path("symbol").asText("");
                if (symbol.isBlank()) {
                    continue;
                }
                String upper = symbol.trim().toUpperCase(Locale.US);
                if (isExcludedIndex(upper)) {
                    continue;
                }
                items.add(
                        new NseOiSpurtItem(
                                upper,
                                node.path("latestOI").asLong(0L),
                                node.path("prevOI").asLong(0L),
                                node.path("changeInOI").asLong(0L),
                                node.path("avgInOI").asDouble(0.0),
                                node.path("underlyingValue").asDouble(0.0)));
            }
            items.sort(Comparator.comparingDouble(NseOiSpurtItem::avgInOi).reversed());
            return items;
        } catch (IOException | RuntimeException e) {
            log.warn("[NSE-OI] Failed to parse OI spurts payload: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * @return true when the symbol is a broad-market index that must never be traded as stock
     */
    private boolean isExcludedIndex(String upperSymbol) {
        return EXCLUDED_INDICES.contains(upperSymbol) || upperSymbol.startsWith("NIFTY ");
    }

    /** Single HTTP attempt; empty list = attempt failed (never throws). */
    private List<NseOiSpurtItem> fetchOnce(int attempt, int attempts) {
        try {
            HttpRequest req =
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("User-Agent", USER_AGENT)
                            .header("Accept", "application/json, text/plain, */*")
                            .header("Accept-Language", "en-US,en;q=0.9")
                            .header("Referer", REFERER)
                            .timeout(REQUEST_TIMEOUT)
                            .GET()
                            .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status == 200 && resp.body() != null && !resp.body().isBlank()) {
                List<NseOiSpurtItem> items = parsePayload(resp.body());
                if (!items.isEmpty()) {
                    log.info(
                            "[NSE-OI] Fetched {} OI-spurt underlyings from NSE (HTTP 200,"
                                    + " attempt {}).",
                            items.size(),
                            attempt);
                }
                return items;
            }
            log.warn(
                    "[NSE-OI] HTTP {} fetching OI spurts (attempt {}/{}).",
                    status,
                    attempt,
                    attempts);
            if (status == 403 && attempt < attempts) {
                warmCookies();
            }
            return Collections.emptyList();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[NSE-OI] Interrupted while fetching OI spurts — aborting retries.");
            return Collections.emptyList();
        } catch (IOException | RuntimeException e) {
            log.warn(
                    "[NSE-OI] Error fetching OI spurts (attempt {}/{}): {}",
                    attempt,
                    attempts,
                    e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Best-effort Akamai cookie warm-up: hits the NSE homepage (an HTTP 403 there is tolerated —
     * bot-manager cookies may still be set) so cookies land in the shared CookieManager before the
     * next API retry.
     */
    private void warmCookies() {
        try {
            HttpRequest req =
                    HttpRequest.newBuilder()
                            .uri(URI.create(HOMEPAGE_URL))
                            .header("User-Agent", USER_AGENT)
                            .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                            .timeout(REQUEST_TIMEOUT)
                            .GET()
                            .build();
            httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            log.debug("[NSE-OI] Cookie warm-up request completed (status tolerated).");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.debug("[NSE-OI] Cookie warm-up interrupted.");
        } catch (IOException | RuntimeException e) {
            log.debug("[NSE-OI] Cookie warm-up failed: {}", e.getMessage());
        }
    }

    /** Sleeps the given backoff, preserving interrupt status; never throws. */
    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Test hook: pin the clock used for the stale-{@code currTradingDate} guard. */
    public void setClock(Clock clock) {
        this.clock = clock != null ? clock : Clock.system(ZoneId.of("Asia/Kolkata"));
    }

    /** Test hook / tuning: base retry backoff in milliseconds (0 disables sleeping). */
    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = Math.max(0, retryBackoffMs);
    }
}
