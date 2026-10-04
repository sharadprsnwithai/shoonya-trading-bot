package com.tradingbot.strategy.bollingerha.feeder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.bollingerha.config.BollingerHaProperties;
import com.tradingbot.strategy.bollingerha.model.CompletedCandleEvent;
import com.tradingbot.strategy.bollingerha.model.SelectedStrikes;
import com.tradingbot.util.StockFnoRegistry;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Hybrid real-time data feeder connecting to Shoonya WebSocket tick stream with automatic fallback
 * to Shoonya REST TPSeries polling on disconnection.
 *
 * <p>The touchline subscription carries the option contracts (NFO) and the underlying index spot
 * (NSE); spot ticks are routed to a separate listener so the strategy's trend filter keeps running
 * even while the option feed is being rebuilt.
 */
@Service
public class ShoonyaHybridDataFeeder implements WebSocket.Listener {

    private static final Logger log = LoggerFactory.getLogger(ShoonyaHybridDataFeeder.class);
    private static final String SHOONYA_WSS_URL = "wss://api.shoonya.com/NorenWSTP/";
    private static final String FALLBACK_SPOT_TOKEN = "26000";
    private static final long REST_LOOKBACK_MINUTES = 15;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_BASE_DELAY_SECONDS = 15;

    private final ShoonyaConfig config;
    private final ShoonyaAuthenticator authenticator;
    private final ShoonyaMarketDataService marketDataService;
    private final BollingerHaProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(feederThreadFactory());

    private final Map<String, BollingerHaCandleBuilder> candleBuilders = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastCompletedBucket = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCumulativeVolume = new ConcurrentHashMap<>();
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    private volatile WebSocket webSocket;
    private volatile Consumer<CompletedCandleEvent> candleListener;
    private volatile Consumer<BigDecimal> spotListener;
    private volatile SelectedStrikes currentStrikes;
    private volatile String spotToken;
    private volatile String spotExchange = "NSE";
    private ScheduledFuture<?> fallbackPollFuture;
    private ScheduledFuture<?> reconnectFuture;

    private static ThreadFactory feederThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "bollinger-ha-feeder");
            thread.setDaemon(true);
            return thread;
        };
    }

    @Autowired
    public ShoonyaHybridDataFeeder(
            ShoonyaConfig config,
            ShoonyaAuthenticator authenticator,
            ShoonyaMarketDataService marketDataService,
            BollingerHaProperties properties) {
        this(
                config,
                authenticator,
                marketDataService,
                properties,
                new ObjectMapper(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public ShoonyaHybridDataFeeder(
            ShoonyaConfig config,
            ShoonyaAuthenticator authenticator,
            ShoonyaMarketDataService marketDataService,
            BollingerHaProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.config = config;
        this.authenticator = authenticator;
        this.marketDataService = marketDataService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /**
     * Initializes the feeder for the selected ATM CE and PE contracts.
     *
     * @param strikes Selected ATM strikes
     * @param candleListener Callback invoked when a candle completes
     * @param spotListener Callback invoked on every underlying-index spot tick
     */
    public synchronized void initialize(
            SelectedStrikes strikes,
            Consumer<CompletedCandleEvent> candleListener,
            Consumer<BigDecimal> spotListener) {
        this.currentStrikes = strikes;
        this.candleListener = candleListener;
        this.spotListener = spotListener;
        this.isRunning.set(true);
        this.reconnectAttempts.set(0);

        candleBuilders.clear();
        lastCompletedBucket.clear();
        lastCumulativeVolume.clear();

        int timeframeMinutes = properties != null ? properties.getTimeframeMinutes() : 1;
        if (strikes != null) {
            candleBuilders.put(
                    strikes.ceToken(),
                    new BollingerHaCandleBuilder(
                            strikes.ceToken(),
                            strikes.ceSymbol(),
                            "CE",
                            timeframeMinutes,
                            this::handleCandleCompleted));
            candleBuilders.put(
                    strikes.peToken(),
                    new BollingerHaCandleBuilder(
                            strikes.peToken(),
                            strikes.peSymbol(),
                            "PE",
                            timeframeMinutes,
                            this::handleCandleCompleted));
        }

        String underlying = properties != null ? properties.getUnderlying() : "NIFTY";
        String resolvedSpotToken = StockFnoRegistry.getToken(underlying);
        this.spotToken =
                resolvedSpotToken != null && !resolvedSpotToken.isBlank()
                        ? resolvedSpotToken
                        : FALLBACK_SPOT_TOKEN;
        String resolvedExchange = StockFnoRegistry.getExchange(underlying);
        this.spotExchange =
                resolvedExchange != null && !resolvedExchange.isBlank() ? resolvedExchange : "NSE";

        connectWebSocket();
        startFallbackPolling();
    }

    /** Package-private for testing: bucket-keyed dedup shared by the WS and REST paths. */
    void handleCandleCompleted(CompletedCandleEvent event) {
        String token = event.token();
        Instant bucket = event.candle().timestamp();
        Instant last = lastCompletedBucket.get(token);
        // D5: dedup on the candle bucket (not on wall-clock completion time) so the WS stream and
        // the REST backfill — which disagree on the clock they stamp — cannot emit the same candle
        // twice, and a stale backfill candle can never overwrite a newer one.
        if (last != null && !bucket.isAfter(last)) {
            log.debug(
                    "[BOLLINGER-HA-FEEDER] Dropping duplicate/stale candle for {} (bucket {} <="
                            + " last {})",
                    event.symbol(),
                    bucket,
                    last);
            return;
        }
        lastCompletedBucket.put(token, bucket);
        if (candleListener != null) {
            candleListener.accept(event);
        }
    }

    private synchronized void connectWebSocket() {
        if (!isRunning.get()) {
            return;
        }
        if (!config.isEnabled()) {
            log.info(
                    "[BOLLINGER-HA-FEEDER] Shoonya disabled. Operating in simulated/fallback mode.");
            return;
        }

        try {
            String token = authenticator.getOrAuthenticateToken();
            if (token == null || token.isBlank()) {
                log.warn("[BOLLINGER-HA-FEEDER] No active session token. Using REST fallback.");
                scheduleReconnect();
                return;
            }

            httpClient
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(SHOONYA_WSS_URL), this)
                    .thenAccept(
                            ws -> {
                                this.webSocket = ws;
                                this.isConnected.set(true);
                                this.reconnectAttempts.set(0);
                                log.info("[BOLLINGER-HA-FEEDER] WebSocket connected successfully.");
                                sendAuthAndSubscribe(ws);
                            })
                    .exceptionally(
                            ex -> {
                                log.warn(
                                        "[BOLLINGER-HA-FEEDER] WebSocket connection failed: {}."
                                                + " Relying on REST polling fallback.",
                                        ex.getMessage());
                                this.isConnected.set(false);
                                scheduleReconnect();
                                return null;
                            });
        } catch (Exception e) {
            log.error(
                    "[BOLLINGER-HA-FEEDER] Error establishing WebSocket connection: {}",
                    e.getMessage());
            this.isConnected.set(false);
            scheduleReconnect();
        }
    }

    /**
     * D10: reconnects with a linear backoff after a drop. The REST fallback keeps candles flowing
     * in the meantime, so a failed reconnect never stalls the strategy.
     */
    private synchronized void scheduleReconnect() {
        if (!isRunning.get() || isConnected.get()) {
            return;
        }
        if (reconnectFuture != null && !reconnectFuture.isDone()) {
            return;
        }
        int attempt = reconnectAttempts.incrementAndGet();
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            log.error(
                    "[BOLLINGER-HA-FEEDER] Giving up on WebSocket reconnect after {} attempts."
                            + " Continuing on the REST fallback only.",
                    MAX_RECONNECT_ATTEMPTS);
            return;
        }
        long delay = Math.min(RECONNECT_BASE_DELAY_SECONDS * attempt, 60);
        log.info(
                "[BOLLINGER-HA-FEEDER] Scheduling WebSocket reconnect in {}s (attempt {}/{}).",
                delay,
                attempt,
                MAX_RECONNECT_ATTEMPTS);
        reconnectFuture =
                scheduler.schedule(
                        () -> {
                            if (isRunning.get() && !isConnected.get()) {
                                connectWebSocket();
                            }
                        },
                        delay,
                        TimeUnit.SECONDS);
    }

    private void sendAuthAndSubscribe(WebSocket ws) {
        try {
            String sessionToken = authenticator.getOrAuthenticateToken();
            String userId = config.getUserId();

            // Connect auth message
            String authMsg =
                    String.format(
                            "{\"t\":\"c\",\"uid\":\"%s\",\"actid\":\"%s\",\"susertoken\":\"%s\"}",
                            userId, userId, sessionToken);
            ws.sendText(authMsg, true);

            // Subscribe touchline for CE, PE and the underlying spot used by the trend filter
            if (currentStrikes != null) {
                StringBuilder keys = new StringBuilder();
                keys.append("NFO|").append(currentStrikes.ceToken());
                keys.append("#NFO|").append(currentStrikes.peToken());
                if (spotToken != null && !spotToken.isBlank()) {
                    keys.append('#').append(spotExchange).append('|').append(spotToken);
                }
                String subMsg = String.format("{\"t\":\"t\",\"k\":\"%s\"}", keys);
                ws.sendText(subMsg, true);
                log.info(
                        "[BOLLINGER-HA-FEEDER] Subscribed to touchline: {} (spot {}|{})",
                        keys,
                        spotExchange,
                        spotToken);
            }
        } catch (Exception e) {
            log.error(
                    "[BOLLINGER-HA-FEEDER] Error sending WS subscription message: {}",
                    e.getMessage());
        }
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        try {
            JsonNode root = objectMapper.readTree(data.toString());
            String type = root.path("t").asText();

            // 'tk' = Touchline Feed, 'tf' = Touchline Feed / Depth
            if ("tk".equals(type) || "tf".equals(type)) {
                String token = root.path("tk").asText();
                String ltpStr = root.path("lp").asText();
                String volStr = root.path("v").asText();

                if (!token.isBlank() && !ltpStr.isBlank()) {
                    BigDecimal ltp = new BigDecimal(ltpStr);

                    if (token.equals(spotToken)) {
                        Consumer<BigDecimal> listener = this.spotListener;
                        if (listener != null) {
                            listener.accept(ltp);
                        }
                        return WebSocket.Listener.super.onText(webSocket, data, last);
                    }

                    long volume = volStr.isBlank() ? 0L : Long.parseLong(volStr);
                    BollingerHaCandleBuilder builder = candleBuilders.get(token);
                    if (builder != null) {
                        builder.onTick(ltp, toDeltaVolume(token, volume), Instant.now());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[BOLLINGER-HA-FEEDER] Error parsing WS tick frame: {}", e.getMessage());
        }
        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    /**
     * D7: the touchline reports a cumulative session volume; the candle builder accumulates deltas.
     * The first observation only establishes the baseline (delta 0), and a counter reset reads as 0
     * rather than a negative bar.
     */
    /** Package-private for testing. */
    long toDeltaVolume(String token, long cumulative) {
        Long previous = lastCumulativeVolume.put(token, cumulative);
        if (previous == null) {
            return 0L;
        }
        long delta = cumulative - previous;
        return delta > 0 ? delta : 0L;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        log.warn(
                "[BOLLINGER-HA-FEEDER] WebSocket error: {}. Falling back to REST.",
                error.getMessage());
        this.isConnected.set(false);
        scheduleReconnect();
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        log.info("[BOLLINGER-HA-FEEDER] WebSocket closed: {} (code: {})", reason, statusCode);
        this.isConnected.set(false);
        scheduleReconnect();
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    private void startFallbackPolling() {
        if (fallbackPollFuture != null && !fallbackPollFuture.isCancelled()) {
            fallbackPollFuture.cancel(true);
        }

        // Poll every 30 seconds to catch up on any missing candles
        fallbackPollFuture =
                scheduler.scheduleWithFixedDelay(this::pollRestFallback, 30, 30, TimeUnit.SECONDS);
    }

    private void pollRestFallback() {
        if (!isRunning.get() || currentStrikes == null) {
            return;
        }

        try {
            // If WebSocket is disconnected or lagging, fetch recent candles via REST
            if (!isConnected.get()) {
                fetchAndProcessRestCandles(
                        currentStrikes.ceToken(), currentStrikes.ceSymbol(), "CE");
                fetchAndProcessRestCandles(
                        currentStrikes.peToken(), currentStrikes.peSymbol(), "PE");
                fetchSpotViaRest();
            }
        } catch (Exception e) {
            log.warn("[BOLLINGER-HA-FEEDER] Error during REST fallback poll: {}", e.getMessage());
        }
    }

    /**
     * D5: replays every completed candle in the look-back window instead of only the newest one,
     * skipping the still-open bucket. {@link #handleCandleCompleted} dedups on the candle bucket,
     * so replaying an overlap with a live WS stream is safe.
     */
    private void fetchAndProcessRestCandles(String token, String symbol, String optionType) {
        try {
            int timeframeMinutes = properties != null ? properties.getTimeframeMinutes() : 1;
            long nowEpoch = Instant.now().getEpochSecond();
            long startEpoch = nowEpoch - REST_LOOKBACK_MINUTES * 60L;
            long endEpoch = nowEpoch - timeframeMinutes * 60L;
            if (endEpoch <= startEpoch) {
                return;
            }
            List<Candle> candles =
                    marketDataService.fetchHistoricalCandles(
                            "NFO",
                            token,
                            symbol,
                            String.valueOf(timeframeMinutes),
                            startEpoch,
                            endEpoch);
            if (candles == null || candles.isEmpty()) {
                return;
            }
            for (Candle candle : candles) {
                handleCandleCompleted(
                        new CompletedCandleEvent(
                                token,
                                symbol,
                                optionType,
                                candle,
                                candle.timestamp().plusSeconds(timeframeMinutes * 60L)));
            }
        } catch (Exception e) {
            log.debug("[BOLLINGER-HA-FEEDER] REST fetch failed for {}: {}", symbol, e.getMessage());
        }
    }

    /** D12: keeps the trend filter warm while the socket is down. */
    private void fetchSpotViaRest() {
        Consumer<BigDecimal> listener = this.spotListener;
        String token = this.spotToken;
        if (listener == null || token == null || token.isBlank()) {
            return;
        }
        try {
            JsonNode quote = marketDataService.fetchQuote(spotExchange, token);
            if (quote != null && quote.has("lp")) {
                listener.accept(new BigDecimal(quote.get("lp").asText()));
            }
        } catch (Exception e) {
            log.debug("[BOLLINGER-HA-FEEDER] Spot REST fetch failed: {}", e.getMessage());
        }
    }

    /** Manually injects a tick (useful for simulation and unit testing). */
    public void injectTick(String token, BigDecimal price, long volume, Instant timestamp) {
        BollingerHaCandleBuilder builder = candleBuilders.get(token);
        if (builder != null) {
            builder.onTick(price, volume, timestamp);
        }
    }

    public boolean isConnected() {
        return isConnected.get();
    }

    /**
     * Stops the live feed and flushes any in-progress candles, but keeps the scheduler alive so the
     * feeder can be {@link #initialize(SelectedStrikes, Consumer, Consumer)}-ed again on the next
     * trading day. Called at market close.
     */
    public synchronized void disconnect() {
        this.isRunning.set(false);
        if (fallbackPollFuture != null) {
            fallbackPollFuture.cancel(true);
            fallbackPollFuture = null;
        }
        if (reconnectFuture != null) {
            reconnectFuture.cancel(true);
            reconnectFuture = null;
        }
        WebSocket ws = this.webSocket;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "Market closed");
            } catch (Exception ignored) {
            }
            this.webSocket = null;
        }
        this.isConnected.set(false);

        Instant now = Instant.now();
        candleBuilders.values().forEach(builder -> builder.flush(now));
        log.info("[BOLLINGER-HA-FEEDER] Data feed disconnected.");
    }

    /** Full shutdown: {@link #disconnect()} plus the scheduler teardown. */
    @PreDestroy
    public synchronized void stop() {
        disconnect();
        scheduler.shutdown();
        log.info("[BOLLINGER-HA-FEEDER] Data feeder stopped.");
    }
}
