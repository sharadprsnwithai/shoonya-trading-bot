package com.tradingbot.marketdata.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.config.ShoonyaFeedProperties;
import com.tradingbot.model.strategy.StockQuoteSnapshot;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the state machine of the streaming feed client.
 *
 * <p>The feed is constructed with the transport disabled, so no socket is opened. The tests drive
 * the protocol through {@code handleRawText}, which is the listener's own entry point once it has
 * attributed the frame to a connection, so the tested path is the production path. Naming that
 * connection explicitly lets a test stand in for a socket which has been superseded - a state
 * otherwise only reachable while a real gateway is draining one.
 */
class ShoonyaFeedClientTest {

    private ShoonyaFeedProperties properties;
    private ShoonyaFeedClient client;

    @BeforeEach
    void setUp() {
        properties = new ShoonyaFeedProperties();
        properties.setEnabled(false);
        properties.setSnapshotMaxAgeMs(60000);
        properties.setWarmupTimeoutMs(500);

        ShoonyaConfig shoonyaConfig = new ShoonyaConfig();
        shoonyaConfig.setEnabled(false);
        shoonyaConfig.setUserId("AB1234");

        client =
                new ShoonyaFeedClient(
                        properties,
                        shoonyaConfig,
                        new ShoonyaAuthenticator(shoonyaConfig),
                        HttpClient.newHttpClient());
    }

    /**
     * Releases the scheduler installed by {@link #armReconnect()} so no probe outlives the test.
     */
    @AfterEach
    void tearDown() {
        if (client.running) {
            client.stop();
        }
    }

    /**
     * Arms the reconnection path without opening a socket.
     *
     * <p>{@link ShoonyaFeedClient#start()} would resolve a real session and dial the live gateway,
     * so the test installs a scheduler and flips the running flag directly instead. The backoff is
     * set well beyond the lifetime of the test, keeping the queued attempt from ever firing and
     * reconnecting for real.
     */
    private void armReconnect() {
        properties.setReconnectInitialBackoffSeconds(3600);
        client.running = true;
        client.scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread t = new Thread(runnable, "test-feed-reconnect");
                            t.setDaemon(true);
                            return t;
                        });
    }

    /**
     * @param symbol the trading symbol
     * @param token the instrument token
     * @return a single-entry subscription map
     */
    private static Map<String, String> one(String symbol, String token) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(symbol, token);
        return map;
    }

    @Test
    @DisplayName("a disabled feed never reports itself up")
    void testDisabledFeedStaysDown() {
        client.start();

        assertThat(client.isUp()).isFalse();
        assertThat(client.subscribedCount()).isZero();

        client.stop();
    }

    @Test
    @DisplayName("authentication is established only by a successful connect acknowledgement")
    void testAuthenticationAck() {
        client.handleRawText("{\"t\":\"ak\",\"s\":\"Not_Ok\",\"e\":\"Invalid session\"}");
        assertThat(client.isUp()).isFalse();

        client.handleRawText("{\"t\":\"ak\",\"s\":\"Ok\",\"uid\":\"AB1234\"}");
        assertThat(client.isUp()).isTrue();
    }

    @Test
    @DisplayName("subscriptions are registered once and reported as new only the first time")
    void testEnsureSubscribedIsIdempotent() {
        assertThat(client.ensureSubscribed(one("DMART", "2885"))).containsExactly("NSE|2885");
        assertThat(client.ensureSubscribed(one("DMART", "2885"))).isEmpty();
        assertThat(client.subscribedCount()).isEqualTo(1);
        assertThat(client.subscribedKeys()).containsExactly("NSE|2885");
    }

    @Test
    @DisplayName("a dropped session re-announces its subscriptions on reconnect")
    void testSubscriptionsReAnnouncedAfterDisconnect() {
        client.handleRawText("{\"t\":\"ak\",\"s\":\"Ok\",\"uid\":\"AB1234\"}");
        assertThat(client.ensureSubscribed(one("DMART", "2885"))).containsExactly("NSE|2885");

        // The gateway forgets subscriptions with the session, so a reconnected socket carries
        // none. If the local set survived the drop, ensureSubscribed would treat the symbol as
        // already registered, send no frames, and the feed would reconnect healthy yet stream
        // nothing for the rest of the process.
        client.markDisconnected();
        assertThat(client.subscribedKeys()).isEmpty();
        assertThat(client.isUp()).isFalse();

        client.handleRawText("{\"t\":\"ak\",\"s\":\"Ok\",\"uid\":\"AB1234\"}");
        assertThat(client.ensureSubscribed(one("DMART", "2885"))).containsExactly("NSE|2885");
        assertThat(client.subscribedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("exchange prefixes on the symbol select the subscription exchange")
    void testExchangePrefixHonoured() {
        client.ensureSubscribed(one("MCX:SILVER", "495214"));
        client.handleRawText("{\"t\":\"tk\",\"e\":\"MCX\",\"tk\":\"495214\",\"lp\":\"512.00\"}");

        assertThat(client.subscribedKeys()).containsExactly("MCX|495214");
        assertThat(client.tickFor("MCX:SILVER").exchange()).isEqualTo("MCX");
        assertThat(client.snapshots(List.of("MCX:SILVER"), Duration.ofSeconds(5)))
                .containsOnlyKeys("MCX:SILVER");
    }

    @Test
    @DisplayName("a caller-supplied exchange resolver overrides the prefix convention")
    void testCallerSuppliedExchangeResolver() {
        // LVR trades futures: without this, a bare derivative symbol would default to NSE and the
        // subscription would be dead.
        client.ensureSubscribed(one("RELIANCE26OCTFUT", "123456"), s -> "NFO");
        client.handleRawText("{\"t\":\"tk\",\"e\":\"NFO\",\"tk\":\"123456\",\"lp\":\"1449.00\"}");

        assertThat(client.subscribedKeys()).containsExactly("NFO|123456");
        assertThat(client.tickFor("RELIANCE26OCTFUT").exchange()).isEqualTo("NFO");
        assertThat(client.snapshots(List.of("RELIANCE26OCTFUT"), Duration.ofSeconds(5)))
                .containsOnlyKeys("RELIANCE26OCTFUT");
    }

    @Test
    @DisplayName("a blank or failing exchange resolver falls back to the prefix convention")
    void testExchangeResolverFallback() {
        client.ensureSubscribed(one("A", "1"), s -> "  ");
        client.ensureSubscribed(
                one("B", "2"),
                s -> {
                    throw new IllegalStateException("boom");
                });
        client.ensureSubscribed(one("C", "3"), null);

        assertThat(client.subscribedKeys()).containsExactlyInAnyOrder("NSE|1", "NSE|2", "NSE|3");
    }

    @Test
    @DisplayName("the exchange resolver is applied to every symbol in the map")
    void testExchangeResolverAppliedPerSymbol() {
        Map<String, String> mixed = new LinkedHashMap<>();
        mixed.put("NIFTY26OCT24000CE", "500");
        mixed.put("DMART", "2885");

        client.ensureSubscribed(mixed, s -> s.contains("CE") ? "NFO" : "NSE");

        assertThat(client.subscribedKeys()).containsExactlyInAnyOrder("NFO|500", "NSE|2885");
    }

    @Test
    @DisplayName("symbols without a resolvable token are skipped instead of poisoning the feed")
    void testBlankTokensSkipped() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("DMART", "2885");
        map.put("BROKEN", " ");
        map.put(null, "123");

        client.ensureSubscribed(map);

        assertThat(client.subscribedKeys()).containsExactly("NSE|2885");
    }

    @Test
    @DisplayName("a snapshot frame populates a live snapshot keyed by symbol")
    void testSnapshotRoundTrip() {
        client.ensureSubscribed(one("DMART", "2885"));
        client.handleRawText("{\"t\":\"ak\",\"s\":\"Ok\"}");
        client.handleRawText(
                "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"3364.00\",\"c\":\"3300.00\","
                        + "\"o\":\"3310.00\",\"v\":\"125000\",\"ap\":\"3340.00\"}");

        Map<String, StockQuoteSnapshot> out =
                client.snapshots(List.of("DMART"), Duration.ofSeconds(5));

        assertThat(out).containsOnlyKeys("DMART");
        StockQuoteSnapshot snapshot = out.get("DMART");
        assertThat(snapshot.ltp()).isEqualTo(3364.00);
        assertThat(snapshot.prevClose()).isEqualTo(3300.00);
        assertThat(snapshot.pctChange()).isEqualTo(1.9393939393939394);
        assertThat(snapshot.volume()).isEqualTo(125000L);
    }

    @Test
    @DisplayName("diff frames update the price without disturbing the previous close")
    void testDiffUpdatesPriceOnly() {
        client.ensureSubscribed(one("DMART", "2885"));
        client.handleRawText(
                "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"3364.00\",\"c\":\"3300.00\"}");
        client.handleRawText("{\"t\":\"tf\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"3390.00\"}");

        StockQuoteSnapshot snapshot =
                client.snapshots(List.of("DMART"), Duration.ofSeconds(5)).get("DMART");

        assertThat(snapshot.ltp()).isEqualTo(3390.00);
        assertThat(snapshot.prevClose()).isEqualTo(3300.00);
    }

    @Test
    @DisplayName(
            "a diff that arrives before any snapshot produces nothing rather than a hollow quote")
    void testDiffBeforeSnapshotYieldsNoSnapshot() {
        client.ensureSubscribed(one("DMART", "2885"));

        client.handleRawText(
                "{\"t\":\"tf\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"3390.00\",\"c\":\"3300.00\"}");

        assertThat(client.snapshots(List.of("DMART"), Duration.ofSeconds(5))).isEmpty();
    }

    @Test
    @DisplayName("unsubscribed symbols and unknown symbols yield nothing")
    void testUnknownSymbolsYieldNothing() {
        client.ensureSubscribed(one("DMART", "2885"));
        client.handleRawText(
                "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"3364.00\",\"c\":\"3300.00\"}");

        assertThat(client.snapshots(List.of("HCLTECH"), Duration.ofSeconds(5))).isEmpty();
        assertThat(client.tickFor("HCLTECH")).isNull();
        assertThat(client.tickFor(null)).isNull();
    }

    @Test
    @DisplayName("ticks older than the freshness budget are excluded so callers fall back to REST")
    void testStaleTickExcluded() {
        properties.setSnapshotMaxAgeMs(10);
        client.ensureSubscribed(one("DMART", "2885"));
        client.handleRawText(
                "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"3364.00\",\"c\":\"3300.00\"}");
        assertThat(client.snapshots(List.of("DMART"), client.maxAge())).hasSize(1);

        sleep(30);

        assertThat(client.snapshots(List.of("DMART"), client.maxAge())).isEmpty();
        assertThat(client.coveragePct(List.of("DMART"), client.maxAge())).isZero();
    }

    @Test
    @DisplayName("coverage reports the fraction of requested symbols carrying a fresh tick")
    void testCoveragePct() {
        client.ensureSubscribed(one("DMART", "2885"));
        client.handleRawText(
                "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"3364.00\",\"c\":\"3300.00\"}");

        assertThat(client.coveragePct(List.of("DMART", "HCLTECH"), Duration.ofSeconds(5)))
                .isEqualTo(50.0);
        assertThat(client.coveragePct(List.of(), Duration.ofSeconds(5))).isZero();
        assertThat(client.coveragePct(null, Duration.ofSeconds(5))).isZero();
    }

    @Test
    @DisplayName("awaitCoverage returns immediately once the threshold is met")
    void testAwaitCoverageMet() {
        client.ensureSubscribed(one("DMART", "2885"));
        client.handleRawText(
                "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"3364.00\",\"c\":\"3300.00\"}");

        assertThat(client.awaitCoverage(List.of("DMART"), 100.0, Duration.ofMillis(200))).isTrue();
        assertThat(client.awaitCoverage(List.of(), 100.0, Duration.ofMillis(200))).isTrue();
    }

    @Test
    @DisplayName("awaitCoverage gives up after the budget rather than blocking indefinitely")
    void testAwaitCoverageTimesOut() {
        assertThat(client.awaitCoverage(List.of("DMART"), 100.0, Duration.ofMillis(120))).isFalse();
    }

    @Test
    @DisplayName("malformed and unrelated frames are ignored without raising")
    void testMalformedFramesIgnored() {
        client.ensureSubscribed(one("DMART", "2885"));

        client.handleRawText("<html>502 Bad Gateway</html>");
        client.handleRawText("");
        client.handleRawText(null);
        client.handleRawText("{\"t\":\"ck\"}");
        client.handleRawText("{\"t\":\"ke\",\"e\":\"Invalid token\"}");
        client.handleRawText("{\"t\":\"te\",\"e\":\"Not Connected\"}");
        client.handleRawText("{\"t\":\"uk\"}");

        assertThat(client.subscribedCount()).isEqualTo(1);
        assertThat(client.snapshots(List.of("DMART"), Duration.ofSeconds(5))).isEmpty();
    }

    @Test
    @DisplayName("an error frame does not tear down already-established state")
    void testErrorFramePreservesState() {
        client.ensureSubscribed(one("DMART", "2885"));
        client.handleRawText("{\"t\":\"ak\",\"s\":\"Ok\"}");
        client.handleRawText(
                "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"3364.00\",\"c\":\"3300.00\"}");

        client.handleRawText("{\"t\":\"te\",\"e\":\"Not Connected\"}");

        assertThat(client.isUp()).isTrue();
        assertThat(client.snapshots(List.of("DMART"), Duration.ofSeconds(5))).hasSize(1);
    }

    @Test
    @DisplayName("a disconnect clears authentication so isUp stops reporting a dead feed")
    void testMarkDisconnectedClearsAuth() {
        client.handleRawText("{\"t\":\"ak\",\"s\":\"Ok\"}");
        assertThat(client.isUp()).isTrue();

        client.markDisconnected();

        assertThat(client.isUp()).isFalse();
    }

    @Test
    @DisplayName("awaitUniverseCoverage applies the configured threshold and budget")
    void testAwaitUniverseCoverage() {
        properties.setMinWarmupCoveragePct(50.0);
        properties.setWarmupTimeoutMs(120);

        // Nothing subscribed: coverage is 0%, so this must give up rather than block.
        assertThat(client.awaitUniverseCoverage(List.of("DMART"))).isFalse();

        client.ensureSubscribed(one("DMART", "2885"));
        client.handleRawText(
                "{\"t\":\"tk\",\"e\":\"NSE\",\"tk\":\"2885\",\"lp\":\"3364.00\",\"c\":\"3300.00\"}");

        assertThat(client.awaitUniverseCoverage(List.of("DMART", "HCLTECH"))).isTrue();
        assertThat(client.awaitUniverseCoverage(List.of())).isTrue();
    }

    @Test
    @DisplayName("empty subscription maps are a no-op")
    void testEnsureSubscribedEmpty() {
        assertThat(client.ensureSubscribed(Map.of())).isEmpty();
        assertThat(client.ensureSubscribed(null)).isEmpty();
        assertThat(client.snapshots(null, Duration.ofSeconds(5))).isEmpty();
        assertThat(client.snapshots(List.of(), Duration.ofSeconds(5))).isEmpty();
    }

    @Test
    @DisplayName("repeated closes for one connection queue a single reconnect")
    void testDuplicateCloseQueuesSingleReconnect() {
        armReconnect();

        // Close and error callbacks arrive together for the same socket, and a rejected
        // authentication schedules one before the gateway's own close schedules another. Unguarded,
        // each of those becomes a second chain on the next cycle.
        client.handleDisconnect(0L);
        client.handleDisconnect(0L);
        client.handleDisconnect(0L);

        assertThat(client.scheduledReconnects()).isEqualTo(1);
    }

    @Test
    @DisplayName("a close from a superseded connection clears nothing and queues nothing")
    void testSupersededCloseIgnored() {
        client.handleRawText("{\"t\":\"ak\",\"s\":\"Ok\",\"uid\":\"AB1234\"}");
        client.ensureSubscribed(one("DMART", "2885"));
        assertThat(client.isUp()).isTrue();

        armReconnect();
        client.handleDisconnect(99L);

        assertThat(client.isUp()).isTrue();
        assertThat(client.scheduledReconnects()).isZero();
        // Subscriptions belong to the live session; a socket nobody owns must not take them away.
        assertThat(client.ensureSubscribed(one("DMART", "2885"))).isEmpty();
    }

    @Test
    @DisplayName("a frame from a superseded connection cannot authenticate the session")
    void testSupersededFrameIgnored() {
        client.handleRawText(99L, "{\"t\":\"ak\",\"s\":\"Ok\",\"uid\":\"AB1234\"}");
        assertThat(client.isUp()).isFalse();

        client.handleRawText(0L, "{\"t\":\"ak\",\"s\":\"Ok\",\"uid\":\"AB1234\"}");
        assertThat(client.isUp()).isTrue();
    }

    @Test
    @DisplayName("authentication does not reset the reconnect backoff")
    void testBackoffSurvivesAuthentication() {
        armReconnect();

        assertThat(client.scheduleReconnect()).isTrue();
        assertThat(client.scheduledReconnects()).isEqualTo(1);

        // A gateway that acknowledges the session and then drops the socket looks healthy from the
        // handshake alone. Clearing the counter here pinned one live run at "attempt 1" for 15,085
        // consecutive reconnects.
        client.handleRawText("{\"t\":\"ak\",\"s\":\"Ok\",\"uid\":\"AB1234\"}");

        assertThat(client.isUp()).isTrue();
        assertThat(client.scheduledReconnects()).isEqualTo(1);
    }

    @Test
    @DisplayName("the backoff resets once the connection has stayed up for the grace period")
    void testBackoffResetsAfterStableConnection() {
        properties.setStableConnectionSeconds(1);
        armReconnect();

        assertThat(client.scheduleReconnect()).isTrue();
        assertThat(client.scheduledReconnects()).isEqualTo(1);

        // Authenticating as connection 0 queues the stability probe; nothing resets immediately.
        client.handleRawText("{\"t\":\"ak\",\"s\":\"Ok\",\"uid\":\"AB1234\"}");
        assertThat(client.scheduledReconnects()).isEqualTo(1);

        sleep(2000);

        assertThat(client.scheduledReconnects()).isZero();
    }

    /**
     * @param millis how long to pause
     */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
