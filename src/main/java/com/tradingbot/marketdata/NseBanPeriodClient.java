package com.tradingbot.marketdata;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HTTP client for NSE's daily "Security in ban period for the next trade date" (F&O segment) list,
 * consumed by the LVR strategy to exclude securities that may not take net-new derivative exposure.
 *
 * <p><b>Why it matters.</b> Per NCL circular NCL/CMPT/70085 (08-Sep-2025) implementing SEBI
 * SEBI/HO/MRD/TPD-1/P/CIR/2025/79, a security whose market-wide Futures-equivalent open interest
 * (FutEq OI) exceeds 95% of the market-wide position limit (MWPL) is placed in ban from the next
 * trading day; during ban every trade must <i>reduce</i> the entity's end-of-day FutEq OI. LVR's
 * long option and long futures entries always <i>increase</i> FutEq OI, so there is no compliant
 * way for the strategy to trade a banned security. Penalties (1% of violation value, min ₹5,000,
 * max ₹1,00,000 per entity per stock per day) repeat daily until the breach is cleared.
 *
 * <p><b>Resilience contract</b> (NSE sits behind Akamai bot management): browser headers + a shared
 * {@link CookieManager}, up to {@code max-attempts} tries with linear backoff, a homepage cookie
 * warm-up before retrying after HTTP 403, and a never-throwing public API. The list is published
 * EOD and applies to the <b>next</b> trading day, so a pre-market fetch on trading day {@code T}
 * requests the file named for {@code T}.
 *
 * <p><b>Validity guard.</b> A zero-symbol payload is a legitimate state (no security in ban), so
 * "empty" alone cannot signal failure. {@link #parseBanList(String)} therefore only reports a valid
 * result when the payload carries a recognisable header mentioning "symbol"; an error page, captcha
 * interstitial or truncated download is reported as invalid and the fetch reports failure rather
 * than silently asserting "nothing is banned".
 */
@Component
public class NseBanPeriodClient {

    private static final Logger log = LoggerFactory.getLogger(NseBanPeriodClient.class);

    /** Default NSE archive URL; the current rolling ban file is fo_secban.csv. */
    static final String DEFAULT_URL = "https://nsearchives.nseindia.com/content/fo/fo_secban.csv";

    private static final String HOMEPAGE_URL = "https://www.nseindia.com";
    private static final String REFERER = "https://www.nseindia.com/report-detail/eq_secban";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/124.0.0.0 Safari/537.36";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** NSE ban-list archive files are named ddMMyyyy. */
    private static final DateTimeFormatter URL_DATE_FMT = DateTimeFormatter.ofPattern("ddMMyyyy");

    /** A plausible NSE F&O underlying: starts with a letter, no spaces or stray punctuation. */
    private static final Pattern SYMBOL_PATTERN = Pattern.compile("^[A-Z][A-Z0-9&.\\-]{1,20}$");

    /** Header/label tokens that appear in the payload but are never tradable underlyings. */
    private static final Set<String> NON_SYMBOL_TOKENS =
            Set.of(
                    "SYMBOL",
                    "SYMBOLS",
                    "SR",
                    "S.NO",
                    "SNO",
                    "NO",
                    "DETAILS",
                    "SCRIP",
                    "UNDERLYING");

    private final HttpClient httpClient;
    private volatile Clock clock = Clock.system(ZoneId.of("Asia/Kolkata"));

    @Value("${trading-bot.strategy.lowest-volume.nse-ban.url:" + DEFAULT_URL + "}")
    private volatile String url = DEFAULT_URL;

    @Value("${trading-bot.strategy.lowest-volume.nse-ban.max-attempts:3}")
    private volatile int maxAttempts = 3;

    /** Linear retry backoff base in milliseconds (attempt N sleeps {@code base * N}). */
    private volatile long retryBackoffMs = 500;

    /** Outcome of the most recent successful/failed fetch, surfaced for fail-open/fail-closed. */
    private volatile boolean lastFetchSuccessful;

    /** Trade date the current ban list applies to (today's session), or null when unavailable. */
    private volatile LocalDate lastFetchDate;

    /**
     * Spring constructor: builds a dedicated HttpClient with cookie handling so Akamai {@code
     * _abck}/{@code bm_sz} cookies set by one request are replayed on the next.
     */
    @Autowired
    public NseBanPeriodClient() {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build());
    }

    /**
     * Injectable constructor for tests (mock HttpClient + fixed clock).
     *
     * @param httpClient HTTP transport to use for all requests
     */
    public NseBanPeriodClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Fetches the ban list applicable to today's session.
     *
     * <p>Never throws. On failure returns an empty set with {@link #isLastFetchSuccessful()} false,
     * so callers can distinguish "nothing is banned" from "we could not find out".
     *
     * @return banned underlyings (uppercase), or empty when nothing is banned or on failure
     */
    public Set<String> fetchBanList() {
        LocalDate tradeDate = LocalDate.now(clock);
        int attempts = Math.max(1, maxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            Set<String> symbols = fetchOnce(tradeDate, attempt, attempts);
            if (lastFetchSuccessful) {
                log.info(
                        "[NSE-BAN] Fetched ban list for {} ({} symbol(s), attempt {}).",
                        tradeDate,
                        symbols.size(),
                        attempt);
                return symbols;
            }
            if (attempt < attempts) {
                sleepQuietly(retryBackoffMs * attempt);
            }
        }
        log.warn(
                "[NSE-BAN] Giving up after {} attempt(s) — LVR cannot confirm which securities"
                        + " are in F&O ban for {}.",
                attempts,
                tradeDate);
        return Collections.emptySet();
    }

    /**
     * Parses a raw ban-list payload (CSV or the equivalent HTML table).
     *
     * <p>Only a payload carrying a recognisable "symbol" header is treated as valid; anything else
     * (error page, captcha interstitial, truncated body) is reported invalid so callers can fail
     * open/closed deliberately rather than concluding "nothing is banned".
     *
     * @param body raw response body
     * @return parsed result; {@code valid=false} means the payload was not a ban list at all
     */
    public BanListResult parseBanList(String body) {
        if (body == null || body.isBlank()) {
            log.warn("[NSE-BAN] Empty ban-list payload — treating as failure.");
            return BanListResult.invalid();
        }
        String trimmed = body.trim();
        boolean html = trimmed.indexOf('<') >= 0;
        Set<String> symbols = new LinkedHashSet<>();
        boolean headerSeen = false;

        for (String row : splitRows(trimmed, html)) {
            java.util.List<String> cells = extractCells(row, html);
            if (cells.isEmpty()) {
                continue;
            }
            String cell0 = cells.get(0).trim().toUpperCase(Locale.ROOT);
            String symbolCell = (cells.size() >= 2 ? cells.get(1) : cells.get(0)).trim();
            String symbolUpper = symbolCell.toUpperCase(Locale.ROOT);
            if (symbolUpper.contains("SYMBOL")
                    || cell0.contains("SECURITIES IN BAN")
                    || cell0.contains("TRADE DATE")
                    || (cell0.contains("BAN") && cell0.contains("DATE"))) {
                headerSeen = true;
                continue;
            }
            // The underlying is the second column, so the details column can never be read as a
            // symbol. A single-column payload is treated as one symbol per row.
            String token = symbolUpper;
            if (NON_SYMBOL_TOKENS.contains(token) || !SYMBOL_PATTERN.matcher(token).matches()) {
                continue;
            }
            symbols.add(token);
        }

        if (!headerSeen) {
            log.warn(
                    "[NSE-BAN] Payload has no ban-list header — not a ban list (error page or"
                            + " format change). Treating as failure.");
            return BanListResult.invalid();
        }
        return new BanListResult(Collections.unmodifiableSet(symbols), true);
    }

    /**
     * @return true when the most recent fetch returned a valid, parseable ban list
     */
    public boolean isLastFetchSuccessful() {
        return lastFetchSuccessful;
    }

    /**
     * @return the trade date the current ban list applies to, or null when unavailable
     */
    public LocalDate getLastFetchDate() {
        return lastFetchDate;
    }

    /** Builds the request URL for the given trade date, filling the {@code {date}} placeholder. */
    String buildUrl(LocalDate tradeDate) {
        String template = url != null && !url.isBlank() ? url : DEFAULT_URL;
        if (template.contains("{date}")) {
            return template.replace("{date}", URL_DATE_FMT.format(tradeDate));
        }
        return template;
    }

    /** Single HTTP attempt; records success/failure on {@link #lastFetchSuccessful}. */
    private Set<String> fetchOnce(LocalDate tradeDate, int attempt, int attempts) {
        lastFetchSuccessful = false;
        try {
            HttpRequest req =
                    HttpRequest.newBuilder()
                            .uri(URI.create(buildUrl(tradeDate)))
                            .header("User-Agent", USER_AGENT)
                            .header("Accept", "text/csv,text/html,application/xhtml+xml,*/*;q=0.8")
                            .header("Accept-Language", "en-US,en;q=0.9")
                            .header("Referer", REFERER)
                            .timeout(REQUEST_TIMEOUT)
                            .GET()
                            .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status == 200 && resp.body() != null && !resp.body().isBlank()) {
                BanListResult result = parseBanList(resp.body());
                if (result.valid()) {
                    lastFetchSuccessful = true;
                    lastFetchDate = tradeDate;
                    return result.symbols();
                }
            } else {
                log.warn(
                        "[NSE-BAN] HTTP {} fetching ban list for {} (attempt {}/{}).",
                        status,
                        tradeDate,
                        attempt,
                        attempts);
            }
            if (status == 403 && attempt < attempts) {
                warmCookies();
            }
            return Collections.emptySet();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[NSE-BAN] Interrupted while fetching ban list — aborting retries.");
            return Collections.emptySet();
        } catch (IOException | RuntimeException e) {
            log.warn(
                    "[NSE-BAN] Error fetching ban list for {} (attempt {}/{}): {}",
                    tradeDate,
                    attempt,
                    attempts,
                    e.getMessage());
            return Collections.emptySet();
        }
    }

    /** Splits a payload into row strings: CSV lines, or {@code <tr>} blocks when HTML. */
    private java.util.List<String> splitRows(String body, boolean html) {
        if (!html) {
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (String line : body.split("\\r?\\n")) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
            return lines;
        }
        java.util.List<String> rows = new java.util.ArrayList<>();
        Matcher tr = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr>").matcher(body);
        while (tr.find()) {
            if (!tr.group(1).isBlank()) {
                rows.add(tr.group(1));
            }
        }
        return rows.isEmpty() ? Collections.singletonList(body) : rows;
    }

    /** Extracts cell values from a row: CSV fields, or {@code <td>} contents when HTML. */
    private java.util.List<String> extractCells(String row, boolean html) {
        java.util.List<String> cells = new java.util.ArrayList<>();
        if (html) {
            Matcher td = Pattern.compile("(?is)<td\\b[^>]*>(.*?)</td>").matcher(row);
            while (td.find()) {
                cells.add(stripTags(td.group(1)));
            }
            return cells;
        }
        Collections.addAll(cells, row.split(","));
        return cells;
    }

    /** Removes residual markup and decodes the few entities that can appear in a symbol cell. */
    private String stripTags(String value) {
        return value.replaceAll("(?is)<[^>]*>", "")
                .replace("&amp;", "&")
                .replace("&nbsp;", " ")
                .trim();
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
            log.debug("[NSE-BAN] Cookie warm-up request completed (status tolerated).");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.debug("[NSE-BAN] Cookie warm-up interrupted.");
        } catch (IOException | RuntimeException e) {
            log.debug("[NSE-BAN] Cookie warm-up failed: {}", e.getMessage());
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

    /** Test hook: pin the clock used to resolve today's ban-list file. */
    public void setClock(Clock clock) {
        this.clock = clock != null ? clock : Clock.system(ZoneId.of("Asia/Kolkata"));
    }

    /** Test hook / tuning: set the URL template. */
    public void setUrl(String url) {
        this.url = url;
    }

    /** Test hook / tuning: base retry backoff in milliseconds (0 disables sleeping). */
    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = Math.max(0, retryBackoffMs);
    }

    /**
     * Parsed ban-list outcome.
     *
     * @param symbols banned underlyings in uppercase
     * @param valid false when the payload was not a recognisable ban list
     */
    public record BanListResult(Set<String> symbols, boolean valid) {

        public BanListResult {
            symbols = symbols == null ? null : new LinkedHashSet<>(symbols);
        }

        @Override
        public Set<String> symbols() {
            return symbols == null ? null : new LinkedHashSet<>(symbols);
        }

        static BanListResult invalid() {
            return new BanListResult(Collections.emptySet(), false);
        }
    }
}
