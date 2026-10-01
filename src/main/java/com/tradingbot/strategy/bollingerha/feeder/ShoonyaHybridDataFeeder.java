package com.tradingbot.strategy.bollingerha.feeder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.bollingerha.model.CompletedCandleEvent;
import com.tradingbot.strategy.bollingerha.model.SelectedStrikes;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Hybrid real-time data feeder connecting to Shoonya WebSocket tick stream with automatic fallback
 * to Shoonya REST TPSeries polling on disconnection.
 */
@Service
public class ShoonyaHybridDataFeeder implements WebSocket.Listener {

    private static final Logger log = LoggerFactory.getLogger(ShoonyaHybridDataFeeder.class);
    private static final String SHOONYA_WSS_URL = "wss://api.shoonya.com/NorenWSTP/";

    private final ShoonyaConfig config;
    private final ShoonyaAuthenticator authenticator;
    private final ShoonyaMarketDataService marketDataService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final Map<String, BollingerHaCandleBuilder> candleBuilders = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastCompletedCandleTime = new ConcurrentHashMap<>();
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private WebSocket webSocket;
    private Consumer<CompletedCandleEvent> candleListener;
    private SelectedStrikes currentStrikes;
    private ScheduledFuture<?> fallbackPollFuture;

    @Autowired
    public ShoonyaHybridDataFeeder(
            ShoonyaConfig config,
            ShoonyaAuthenticator authenticator,
            ShoonyaMarketDataService marketDataService) {
        this(
                config,
                authenticator,
                marketDataService,
                new ObjectMapper(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public ShoonyaHybridDataFeeder(
            ShoonyaConfig config,
            ShoonyaAuthenticator authenticator,
            ShoonyaMarketDataService marketDataService,
            ObjectMapper objectMapper,
            HttpClient httpClient) {
        this.config = config;
        this.authenticator = authenticator;
        this.marketDataService = marketDataService;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /**
     * Initializes the feeder for the selected ATM CE and PE contracts.
     *
     * @param strikes Selected ATM strikes
     * @param listener Callback invoked when a 1m candle completes
     */
    public synchronized void initialize(
            SelectedStrikes strikes, Consumer<CompletedCandleEvent> listener) {
        this.currentStrikes = strikes;
        this.candleListener = listener;
        this.isRunning.set(true);

        candleBuilders.clear();
        if (strikes != null) {
            candleBuilders.put(
                    strikes.ceToken(),
                    new BollingerHaCandleBuilder(
                            strikes.ceToken(),
                            strikes.ceSymbol(),
                            "CE",
                            this::handleCandleCompleted));
            candleBuilders.put(
                    strikes.peToken(),
                    new BollingerHaCandleBuilder(
                            strikes.peToken(),
                            strikes.peSymbol(),
                            "PE",
                            this::handleCandleCompleted));
        }

        connectWebSocket();
        startFallbackPolling();
    }

    private void handleCandleCompleted(CompletedCandleEvent event) {
        lastCompletedCandleTime.put(event.token(), event.completedAt());
        if (candleListener != null) {
            candleListener.accept(event);
        }
    }

    private synchronized void connectWebSocket() {
        if (!config.isEnabled()) {
            log.info(
                    "[BOLLINGER-HA-FEEDER] Shoonya disabled. Operating in simulated/fallback mode.");
            return;
        }

        try {
            String token = authenticator.getOrAuthenticateToken();
            if (token == null || token.isBlank()) {
                log.warn("[BOLLINGER-HA-FEEDER] No active session token. Using REST fallback.");
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
                                log.info("[BOLLINGER-HA-FEEDER] WebSocket connected successfully.");
                                sendAuthAndSubscribe(ws);
                            })
                    .exceptionally(
                            ex -> {
                                log.warn(
                                        "[BOLLINGER-HA-FEEDER] WebSocket connection failed: {}. Relying on REST polling fallback.",
                                        ex.getMessage());
                                this.isConnected.set(false);
                                return null;
                            });
        } catch (Exception e) {
            log.error(
                    "[BOLLINGER-HA-FEEDER] Error establishing WebSocket connection: {}",
                    e.getMessage());
            this.isConnected.set(false);
        }
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

            // Subscribe touchline for CE and PE
            if (currentStrikes != null) {
                String subMsg =
                        String.format(
                                "{\"t\":\"t\",\"k\":\"NFO|%s#NFO|%s\"}",
                                currentStrikes.ceToken(), currentStrikes.peToken());
                ws.sendText(subMsg, true);
                log.info(
                        "[BOLLINGER-HA-FEEDER] Subscribed to touchline for tokens: NFO|{} and NFO|{}",
                        currentStrikes.ceToken(),
                        currentStrikes.peToken());
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
                    long volume = volStr.isBlank() ? 0L : Long.parseLong(volStr);

                    BollingerHaCandleBuilder builder = candleBuilders.get(token);
                    if (builder != null) {
                        builder.onTick(ltp, volume, Instant.now());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[BOLLINGER-HA-FEEDER] Error parsing WS tick frame: {}", e.getMessage());
        }
        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        log.warn(
                "[BOLLINGER-HA-FEEDER] WebSocket error: {}. Falling back to REST.",
                error.getMessage());
        this.isConnected.set(false);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        log.info("[BOLLINGER-HA-FEEDER] WebSocket closed: {} (code: {})", reason, statusCode);
        this.isConnected.set(false);
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
            // If WebSocket is disconnected or lagging, fetch latest 1m candles via REST
            if (!isConnected.get()) {
                fetchAndProcessRestCandles(
                        currentStrikes.ceToken(), currentStrikes.ceSymbol(), "CE");
                fetchAndProcessRestCandles(
                        currentStrikes.peToken(), currentStrikes.peSymbol(), "PE");
            }
        } catch (Exception e) {
            log.warn("[BOLLINGER-HA-FEEDER] Error during REST fallback poll: {}", e.getMessage());
        }
    }

    private void fetchAndProcessRestCandles(String token, String symbol, String optionType) {
        try {
            List<Candle> candles =
                    marketDataService.fetchHistoricalCandles("NFO", token, symbol, "1", 1);
            if (candles != null && !candles.isEmpty()) {
                Candle latest = candles.get(candles.size() - 1);
                Instant lastTime = lastCompletedCandleTime.get(token);

                if (lastTime == null || latest.timestamp().isAfter(lastTime)) {
                    handleCandleCompleted(
                            new CompletedCandleEvent(
                                    token, symbol, optionType, latest, latest.timestamp()));
                }
            }
        } catch (Exception e) {
            log.debug("[BOLLINGER-HA-FEEDER] REST fetch failed for {}: {}", symbol, e.getMessage());
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

    @PreDestroy
    public synchronized void stop() {
        this.isRunning.set(false);
        if (fallbackPollFuture != null) {
            fallbackPollFuture.cancel(true);
        }
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Strategy stopped");
            } catch (Exception ignored) {
            }
        }
        scheduler.shutdown();
        log.info("[BOLLINGER-HA-FEEDER] Data feeder stopped.");
    }
}
