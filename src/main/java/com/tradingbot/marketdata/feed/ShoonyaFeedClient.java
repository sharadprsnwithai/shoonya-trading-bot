package com.tradingbot.marketdata.feed;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.config.ShoonyaFeedProperties;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Long-lived streaming market-data feed for the Shoonya (Finvasia NorenAPI) gateway.
 *
 * <p>One connection multiplexes every subscribed instrument, replacing the per-symbol {@code
 * GetQuotes} REST loop. The broker documents a market-data REST ceiling of roughly one request per
 * second per instrument and advises batching via this feed rather than issuing one call per
 * instrument in a loop; a 125-symbol morning universe therefore costs a single connection and zero
 * additional requests.
 *
 * <h2>Why this matters beyond latency</h2>
 *
 * <p>The REST loop walks a fixed symbol order and sleeps between calls, so the resulting snapshot
 * is ragged — early symbols are read tens of seconds before late ones — and it carries no per-quote
 * timestamp. Because throttling hits the tail of that order non-randomly, and because the
 * sector-ranking path has no minimum-coverage guard, a throttled morning scan could silently rank
 * sectors on a biased subset. This feed delivers every instrument over one connection with a
 * per-tick receive timestamp, so freshness is measurable and coverage is explicit.
 *
 * <h2>Failure behaviour</h2>
 *
 * <p>The feed is strictly an optimisation layer: it never blocks a caller and never fabricates
 * data. When the connection is down, a symbol is unsubscribed, or a tick is older than {@link
 * ShoonyaFeedProperties#getSnapshotMaxAgeMs()}, the lookup simply omits that symbol so the caller
 * falls back to REST for exactly the symbols that need it. An unexpected disconnect triggers an
 * exponential-backoff reconnect that re-subscribes everything previously subscribed.
 *
 * @author trading-bot
 * @since 1.0
 */
@Component
public class ShoonyaFeedClient {

    private static final Logger log = LoggerFactory.getLogger(ShoonyaFeedClient.class);

    private final ShoonyaFeedProperties properties;
    private final ShoonyaConfig shoonyaConfig;
    private final ShoonyaAuthenticator authenticator;
    private final HttpClient httpClient;

    /** Merged instrument state, keyed by {@code EXCHANGE|TOKEN}. */
    private final Map<String, FeedTick> ticks = new ConcurrentHashMap<>();

    /** Symbol to {@code EXCHANGE|TOKEN} key, resolved once and cached for the session. */
    private final Map<String, String> symbolToKey = new ConcurrentHashMap<>();

    /** Keys the gateway has been asked to stream. Re-sent verbatim after every reconnect. */
    private final Set<String> subscribedKeys = ConcurrentHashMap.newKeySet();

    /** Reassembles text frames, which the JDK may deliver split across chunks. */
    private final StringBuilder inboundBuffer = new StringBuilder();

    private volatile WebSocket webSocket;
    private volatile boolean running;
    private volatile boolean authenticated;
    private volatile int reconnectAttempts;

    /** Session token handed to the feed on connect, refreshed on every connection attempt. */
    private volatile String sessionToken = "";

    private ScheduledExecutorService scheduler;

    /**
     * Builds the feed with its own HTTP client, following the convention used by the other Shoonya
     * services. Spring selects this constructor because no {@link HttpClient} bean is published.
     *
     * @param properties feed configuration
     * @param shoonyaConfig broker credentials and endpoints
     * @param authenticator REST session provider; the feed reuses its token
     */
    @Autowired
    public ShoonyaFeedClient(
            ShoonyaFeedProperties properties,
            ShoonyaConfig shoonyaConfig,
            ShoonyaAuthenticator authenticator) {
        this(
                properties,
                shoonyaConfig,
                authenticator,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    /**
     * @param properties feed configuration
     * @param shoonyaConfig broker credentials and endpoints
     * @param authenticator REST session provider; the feed reuses its token
     * @param httpClient HTTP client used only to open the websocket
     */
    public ShoonyaFeedClient(
            ShoonyaFeedProperties properties,
            ShoonyaConfig shoonyaConfig,
            ShoonyaAuthenticator authenticator,
            HttpClient httpClient) {
        this.properties = properties;
        this.shoonyaConfig = shoonyaConfig;
        this.authenticator = authenticator;
        this.httpClient = httpClient;
    }

    /** Opens the feed when enabled. Disabled or unconfigured brokers leave the feed dormant. */
    @PostConstruct
    public void start() {
        if (!properties.isEnabled() || !shoonyaConfig.isEnabled()) {
            log.info(
                    "[FEED] Streaming market-data feed disabled; REST quote path remains authoritative.");
            return;
        }
        this.running = true;
        this.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread t = new Thread(runnable, "shoonya-feed-reconnect");
                            t.setDaemon(true);
                            return t;
                        });
        connect();
    }

    /** Closes the socket and stops the reconnect worker. */
    @PreDestroy
    public void stop() {
        this.running = false;
        this.authenticated = false;
        WebSocket ws = this.webSocket;
        this.webSocket = null;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
            } catch (Exception e) {
                log.debug("[FEED] Close during shutdown failed: {}", e.getMessage());
            }
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        log.info("[FEED] Streaming market-data feed stopped.");
    }

    /**
     * Reports whether the feed can currently serve ticks.
     *
     * <p>True once a successful connect acknowledgement has been received. The listener clears this
     * on close and on error, so it is false for the whole reconnect window; there is no separate
     * socket check because authentication cannot outlive the socket that established it.
     *
     * @return true when the session is authenticated
     */
    public boolean isUp() {
        return authenticated;
    }

    /**
     * Registers symbol-to-token mappings and subscribes any that the gateway is not yet streaming.
     *
     * <p>Resolution happens once per symbol and is cached, so repeated scans across the day cost
     * nothing. Keys already subscribed are skipped, which makes this safe to call on every cycle.
     *
     * <p>Exchange is taken from a {@code NSE:}/{@code BSE:}/{@code MCX:}/{@code NFO:}/{@code BFO:}
     * prefix on the symbol and defaults to {@code NSE}. Callers whose symbol vocabulary is richer —
     * derivatives such as {@code RELIANCE26OCTFUT}, for instance — must use {@link
     * #ensureSubscribed(Map, Function)} with their own resolver.
     *
     * @param symbolToToken trading symbols mapped to their exchange tokens
     * @return the keys that were newly subscribed
     */
    public Set<String> ensureSubscribed(Map<String, String> symbolToToken) {
        return ensureSubscribed(symbolToToken, ShoonyaFeedClient::resolveExchange);
    }

    /**
     * Registers symbol-to-token mappings using a caller-supplied exchange resolver.
     *
     * <p>Tokens are unique per exchange but not globally, so subscribing a derivative token on the
     * cash-equity exchange yields a dead subscription rather than a wrong price. Callers trading
     * derivatives should supply the same resolver their REST path uses, so both sources agree on
     * the exchange for a given symbol.
     *
     * @param symbolToToken trading symbols mapped to their exchange tokens
     * @param exchangeOf resolves the exchange code for a symbol; a null or blank result falls back
     *     to the prefix convention, then to {@code NSE}
     * @return the keys that were newly subscribed
     */
    public Set<String> ensureSubscribed(
            Map<String, String> symbolToToken,
            java.util.function.Function<String, String> exchangeOf) {
        Set<String> newlyAdded = new LinkedHashSet<>();
        if (symbolToToken == null || symbolToToken.isEmpty()) {
            return newlyAdded;
        }
        List<String> toSubscribe = new ArrayList<>();
        for (Map.Entry<String, String> entry : symbolToToken.entrySet()) {
            String symbol = entry.getKey();
            String token = entry.getValue();
            if (symbol == null || symbol.isBlank() || token == null || token.isBlank()) {
                continue;
            }
            String exchange = resolveExchangeFor(symbol, exchangeOf);
            String key = ShoonyaFeedProtocol.instrumentKey(exchange, token);
            if (key == null) {
                continue;
            }
            symbolToKey.putIfAbsent(symbol, key);
            if (subscribedKeys.add(key)) {
                toSubscribe.add(key);
                newlyAdded.add(key);
            }
        }
        if (!toSubscribe.isEmpty()) {
            sendFrames(
                    ShoonyaFeedProtocol.subscribeFrames(
                            toSubscribe, properties.getMaxTokensPerFrame()));
        }
        return newlyAdded;
    }

    /**
     * Resolves the exchange for a symbol, preferring the caller-supplied resolver.
     *
     * @param symbol the trading symbol
     * @param exchangeOf the caller-supplied resolver, may be null
     * @return the exchange code, never null
     */
    private static String resolveExchangeFor(
            String symbol, java.util.function.Function<String, String> exchangeOf) {
        if (exchangeOf != null) {
            try {
                String resolved = exchangeOf.apply(symbol);
                if (resolved != null && !resolved.isBlank()) {
                    return resolved.trim().toUpperCase();
                }
            } catch (Exception e) {
                log.debug(
                        "[FEED] Exchange resolver rejected {}; falling back to the prefix convention: {}",
                        symbol,
                        e.getMessage());
            }
        }
        return resolveExchange(symbol);
    }

    /**
     * Derives the exchange for a symbol from its suffix convention, defaulting to cash equity.
     *
     * @param symbol the trading symbol
     * @return the exchange code
     */
    private static String resolveExchange(String symbol) {
        String clean = symbol.toUpperCase().trim();
        for (String prefix : new String[] {"NSE:", "BSE:", "MCX:", "NFO:", "BFO:"}) {
            if (clean.startsWith(prefix)) {
                return prefix.substring(0, prefix.length() - 1);
            }
        }
        return "NSE";
    }

    /**
     * Returns live snapshots for the given symbols.
     *
     * <p>Symbols with no fresh tick are simply absent from the result; the caller is expected to
     * back-fill them over REST. Returning a partial map is deliberate — a stale price is worse than
     * a missing one.
     *
     * @param symbols the trading symbols to read
     * @param maxAge maximum acceptable tick age; non-positive disables the freshness check
     * @return snapshots keyed by symbol, never null
     */
    public Map<String, StockQuoteSnapshot> snapshots(Collection<String> symbols, Duration maxAge) {
        Map<String, StockQuoteSnapshot> out = new ConcurrentHashMap<>();
        if (symbols == null || symbols.isEmpty()) {
            return out;
        }
        for (String symbol : symbols) {
            FeedTick tick = tickFor(symbol);
            if (tick == null || !tick.isFresh(maxAge)) {
                continue;
            }
            StockQuoteSnapshot snapshot = tick.toSnapshot(symbol);
            if (snapshot != null) {
                out.put(symbol, snapshot);
            }
        }
        return out;
    }

    /**
     * Reports what fraction of the given symbols currently carry a fresh tick.
     *
     * @param symbols the trading symbols to measure
     * @param maxAge maximum acceptable tick age
     * @return percentage in {@code [0, 100]}; 0 when {@code symbols} is empty
     */
    public double coveragePct(Collection<String> symbols, Duration maxAge) {
        if (symbols == null || symbols.isEmpty()) {
            return 0.0;
        }
        Duration age = maxAge == null ? this.maxAge() : maxAge;
        long covered = 0;
        for (String symbol : symbols) {
            FeedTick tick = tickFor(symbol);
            if (tick != null && tick.isFresh(age)) {
                covered++;
            }
        }
        return (covered * 100.0) / symbols.size();
    }

    /**
     * Blocks until the requested symbols reach the configured coverage, or the budget expires.
     *
     * <p>The gateway acknowledges every subscription with a full snapshot, so this typically
     * returns in well under a second. It is the hand-off point between subscribing the universe and
     * reading it.
     *
     * @param symbols the trading symbols to wait for
     * @param minCoveragePct the coverage threshold that ends the wait successfully
     * @param timeout maximum time to block
     * @return true when coverage reached the threshold
     */
    public boolean awaitCoverage(
            Collection<String> symbols, double minCoveragePct, Duration timeout) {
        if (symbols == null || symbols.isEmpty()) {
            return true;
        }
        Instant deadline = Instant.now().plus(timeout == null ? Duration.ZERO : timeout);
        long pollMs = Math.max(1, properties.getWarmupPollIntervalMs());
        while (true) {
            if (coveragePct(symbols, maxAge()) >= minCoveragePct) {
                return true;
            }
            if (Instant.now().isAfter(deadline)) {
                return false;
            }
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * Blocks until the requested symbols reach the configured warm-up coverage, or the configured
     * budget expires.
     *
     * <p>Convenience wrapper over {@link #awaitCoverage(Collection, double, Duration)} that applies
     * the configured thresholds, so callers do not need to reach into the properties object.
     *
     * @param symbols the trading symbols to wait for
     * @return true when coverage reached the configured threshold
     */
    public boolean awaitUniverseCoverage(Collection<String> symbols) {
        return awaitCoverage(
                symbols,
                properties.getMinWarmupCoveragePct(),
                Duration.ofMillis(Math.max(1, properties.getWarmupTimeoutMs())));
    }

    /**
     * @return the configured maximum acceptable tick age
     */
    public Duration maxAge() {
        return Duration.ofMillis(Math.max(1, properties.getSnapshotMaxAgeMs()));
    }

    /**
     * @param symbol the trading symbol
     * @return the live tick for that symbol, or null when it is not subscribed
     */
    public FeedTick tickFor(String symbol) {
        if (symbol == null) {
            return null;
        }
        String key = symbolToKey.get(symbol);
        return key == null ? null : ticks.get(key);
    }

    /**
     * @return the number of instruments currently subscribed
     */
    public int subscribedCount() {
        return subscribedKeys.size();
    }

    /**
     * @return a copy of the subscription keys, for status reporting
     */
    public Set<String> subscribedKeys() {
        return Set.copyOf(subscribedKeys);
    }

    // ---------------------------------------------------------------------
    // Connection lifecycle
    // ---------------------------------------------------------------------

    /** Opens the websocket and authenticates the session. */
    private void connect() {
        if (!running) {
            return;
        }
        try {
            // Resolved once here and reused by the listener; asking the authenticator twice could
            // trigger two headless logins on a cold session.
            this.sessionToken = resolveSessionToken();
            URI uri = URI.create(properties.getUri());
            httpClient
                    .newWebSocketBuilder()
                    .connectTimeout(
                            Duration.ofSeconds(Math.max(1, properties.getConnectTimeoutSeconds())))
                    .buildAsync(uri, new FeedListener())
                    .thenAccept(ws -> log.info("[FEED] Connected to {}", uri))
                    .exceptionally(
                            error -> {
                                log.warn(
                                        "[FEED] Connection to {} failed: {}",
                                        uri,
                                        error.getMessage());
                                scheduleReconnect();
                                return null;
                            });
        } catch (Exception e) {
            log.warn("[FEED] Unable to start feed: {}", e.getMessage(), e);
            scheduleReconnect();
        }
    }

    /**
     * Obtains the REST session token, which the feed gateway reuses as its access token.
     *
     * @return the token, or an empty string when none can be obtained
     */
    private String resolveSessionToken() {
        try {
            String token = authenticator.getOrAuthenticateToken();
            return token == null ? "" : token;
        } catch (Exception e) {
            log.warn("[FEED] Could not obtain session token: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Schedules a reconnect with exponential backoff, capped at the configured maximum. The backoff
     * resets as soon as the session authenticates, so a healthy feed does not accumulate delay.
     */
    private void scheduleReconnect() {
        if (!running || scheduler == null || scheduler.isShutdown()) {
            return;
        }
        int attempt = reconnectAttempts++;
        long cap = Math.max(1, properties.getReconnectMaxBackoffSeconds());
        long initial = Math.max(1, properties.getReconnectInitialBackoffSeconds());
        long delay = Math.min(cap, initial << Math.min(attempt, 10));
        log.warn("[FEED] Reconnecting in {}s (attempt {}).", delay, attempt + 1);
        scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
    }

    /**
     * Sends frames to the gateway, logging and scheduling a reconnect when the socket is gone.
     *
     * @param frames the JSON frames to send
     */
    private void sendFrames(Collection<String> frames) {
        if (frames == null || frames.isEmpty()) {
            return;
        }
        WebSocket ws = this.webSocket;
        if (ws == null) {
            log.debug("[FEED] {} frame(s) not sent; socket is not open.", frames.size());
            return;
        }
        for (String frame : frames) {
            try {
                ws.sendText(frame, true)
                        .exceptionally(
                                error -> {
                                    log.warn("[FEED] Send failed: {}", error.getMessage());
                                    return null;
                                });
            } catch (Exception e) {
                log.warn("[FEED] Send failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Processes one complete inbound frame.
     *
     * <p>This is the single entry point for every inbound message, used by the websocket listener
     * in production. It is public so the feed can be driven from any transport — a recorded replay,
     * a test harness, or an alternative socket implementation — without duplicating the state
     * machine.
     *
     * @param text the raw frame
     */
    public void handleRawText(String text) {
        JsonNode node = ShoonyaFeedProtocol.parse(text);
        if (node == null) {
            log.debug("[FEED] Ignoring non-JSON frame.");
            return;
        }
        switch (ShoonyaFeedProtocol.classify(node)) {
            case AUTH_ACK -> {
                if (ShoonyaFeedProtocol.isAuthenticated(node)) {
                    this.authenticated = true;
                    this.reconnectAttempts = 0;
                    log.info("[FEED] Feed session authenticated.");
                    // Subscriptions do not survive a reconnect, so everything previously requested
                    // is re-sent verbatim on every successful authentication.
                    if (!subscribedKeys.isEmpty()) {
                        sendFrames(
                                ShoonyaFeedProtocol.subscribeFrames(
                                        List.copyOf(subscribedKeys),
                                        properties.getMaxTokensPerFrame()));
                    }
                } else {
                    this.authenticated = false;
                    log.error("[FEED] Feed authentication rejected: {}", text);
                    scheduleReconnect();
                }
            }
            case KEY_SNAPSHOT, KEY_DIFF -> {
                String key = ShoonyaFeedProtocol.instrumentKeyOf(node);
                if (key == null) {
                    return;
                }
                FeedTick tick = ticks.computeIfAbsent(key, k -> tickFromKey(k));
                if (ShoonyaFeedProtocol.classify(node)
                        == ShoonyaFeedProtocol.MessageKind.KEY_SNAPSHOT) {
                    tick.applyFull(node);
                } else {
                    tick.applyDiff(node);
                }
            }
            case KEY_ERROR ->
                    log.warn(
                            "[FEED] Subscription rejected: {}",
                            ShoonyaFeedProtocol.errorMessage(node));
            case ERROR -> log.warn("[FEED] Feed error: {}", ShoonyaFeedProtocol.errorMessage(node));
            case UNSUB_ACK -> log.debug("[FEED] Unsubscribe acknowledged.");
            case OTHER -> log.trace("[FEED] Ignored frame type {}", node.path("t").asText("?"));
        }
    }

    /**
     * Builds a tick holder for a subscription key.
     *
     * @param key the {@code EXCHANGE|TOKEN} key
     * @return the holder
     */
    private static FeedTick tickFromKey(String key) {
        int sep = key.indexOf('|');
        if (sep <= 0 || sep >= key.length() - 1) {
            return new FeedTick("NSE", key);
        }
        return new FeedTick(key.substring(0, sep), key.substring(sep + 1));
    }

    /**
     * Clears session state when the socket drops so that {@link #isUp()} stops reporting a feed
     * that can no longer deliver ticks.
     *
     * <p>Subscriptions are cleared here as well. They belong to the gateway session, not to this
     * object, and a reconnected socket carries none. Leaving the keys in place would make {@link
     * #ensureSubscribed(Map)} see them as already registered and skip the frames, so the feed would
     * reconnect, authenticate, and then stream nothing while reporting itself healthy - a silent
     * and permanent fallback to REST for the rest of the process.
     */
    void markDisconnected() {
        this.authenticated = false;
        this.subscribedKeys.clear();
    }

    // ---------------------------------------------------------------------
    // WebSocket listener
    // ---------------------------------------------------------------------

    /**
     * JDK websocket listener that reassembles split text frames, keeps demand flowing, and drives
     * reconnection on close or error.
     *
     * <p>The JDK client is demand-driven: unless the listener asks for more with {@code
     * request(1)}, no further messages are delivered. That demand is renewed after every completed
     * frame.
     */
    private final class FeedListener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            ShoonyaFeedClient.this.webSocket = webSocket;
            // A missing token is logged rather than thrown: the reconnect backoff will retry, and a
            // hard failure here would take the whole application down over an optional
            // optimisation.
            sendFrames(
                    List.of(
                            ShoonyaFeedProtocol.authFrame(
                                    shoonyaConfig.getUserId(),
                                    shoonyaConfig.getUserId(),
                                    properties.getSource(),
                                    ShoonyaFeedClient.this.sessionToken)));
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            StringBuilder buffer;
            synchronized (inboundBuffer) {
                inboundBuffer.append(data);
                if (!last) {
                    webSocket.request(1);
                    return null;
                }
                buffer = new StringBuilder(inboundBuffer);
                inboundBuffer.setLength(0);
            }
            handleRawText(buffer.toString());
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("[FEED] Socket closed ({} {}); reconnecting.", statusCode, reason);
            markDisconnected();
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("[FEED] Socket error: {}", error.getMessage());
            markDisconnected();
            scheduleReconnect();
        }
    }
}
