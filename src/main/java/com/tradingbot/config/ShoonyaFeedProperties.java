package com.tradingbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration holder for the Shoonya (Finvasia NorenAPI) streaming market-data feed.
 *
 * <p>The WebSocket feed is the replacement for the per-instrument {@code GetQuotes} REST loop. The
 * broker documents a market-data REST ceiling of roughly one request per second <em>per
 * instrument</em> and its own best-practice guidance is to batch via the WebSocket feed rather than
 * issue one REST call per instrument in a loop. A single long-lived connection multiplexes any
 * number of instruments, so a 125-symbol morning universe costs one connection and zero additional
 * requests instead of 125 serial calls.
 *
 * <p>Properties are exposed under {@code trading-bot.shoonya.feed} and are overridable through
 * environment variables via the {@code ${ENV_VAR:default}} convention in {@code
 * application.properties}.
 *
 * @author trading-bot
 * @since 1.0
 */
@Configuration
@ConfigurationProperties(prefix = "trading-bot.shoonya.feed")
public class ShoonyaFeedProperties {

    /** Whether the streaming feed should be started at application startup. */
    private boolean enabled = true;

    /** WebSocket gateway endpoint. One connection is permitted per authenticated session. */
    private String uri = "wss://api.shoonya.com/NorenWSAPI/";

    /** Source identifier sent in the connect frame. Shoonya expects {@code API}. */
    private String source = "API";

    /** TCP/websocket connect timeout in seconds. */
    private int connectTimeoutSeconds = 10;

    /** First reconnect delay after an unexpected disconnect, in seconds. */
    private int reconnectInitialBackoffSeconds = 1;

    /** Upper bound for the exponential reconnect backoff, in seconds. */
    private int reconnectMaxBackoffSeconds = 60;

    /** Maximum number of {@code EXCHANGE|TOKEN} keys packed into a single subscribe frame. */
    private int maxTokensPerFrame = 200;

    /**
     * A tick older than this is treated as stale and excluded from snapshot lookups, so callers
     * cannot trade against a price that the feed stopped updating.
     */
    private long snapshotMaxAgeMs = 60000;

    /**
     * How long a caller may block waiting for the initial snapshot burst after subscribing. The
     * gateway acknowledges every key with a full snapshot, which typically lands within a second.
     */
    private long warmupTimeoutMs = 4000;

    /**
     * Minimum percentage of requested symbols that must carry a fresh tick before the warm-up wait
     * gives up and lets the caller fall back to REST for the remainder.
     */
    private double minWarmupCoveragePct = 60.0;

    /** Milliseconds to poll between coverage checks while warming up. */
    private long warmupPollIntervalMs = 100;

    // Getters and setters

    /**
     * @return true when the streaming feed is enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @param enabled whether the streaming feed should be started
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * @return the WebSocket gateway URI
     */
    public String getUri() {
        return uri;
    }

    /**
     * @param uri the WebSocket gateway URI
     */
    public void setUri(String uri) {
        this.uri = uri;
    }

    /**
     * @return the source identifier sent on connect
     */
    public String getSource() {
        return source;
    }

    /**
     * @param source the source identifier sent on connect
     */
    public void setSource(String source) {
        this.source = source;
    }

    /**
     * @return connect timeout in seconds
     */
    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    /**
     * @param connectTimeoutSeconds connect timeout in seconds
     */
    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    /**
     * @return initial reconnect backoff in seconds
     */
    public int getReconnectInitialBackoffSeconds() {
        return reconnectInitialBackoffSeconds;
    }

    /**
     * @param reconnectInitialBackoffSeconds initial reconnect backoff in seconds
     */
    public void setReconnectInitialBackoffSeconds(int reconnectInitialBackoffSeconds) {
        this.reconnectInitialBackoffSeconds = reconnectInitialBackoffSeconds;
    }

    /**
     * @return maximum reconnect backoff in seconds
     */
    public int getReconnectMaxBackoffSeconds() {
        return reconnectMaxBackoffSeconds;
    }

    /**
     * @param reconnectMaxBackoffSeconds maximum reconnect backoff in seconds
     */
    public void setReconnectMaxBackoffSeconds(int reconnectMaxBackoffSeconds) {
        this.reconnectMaxBackoffSeconds = reconnectMaxBackoffSeconds;
    }

    /**
     * @return maximum keys per subscribe frame
     */
    public int getMaxTokensPerFrame() {
        return maxTokensPerFrame;
    }

    /**
     * @param maxTokensPerFrame maximum keys per subscribe frame
     */
    public void setMaxTokensPerFrame(int maxTokensPerFrame) {
        this.maxTokensPerFrame = maxTokensPerFrame;
    }

    /**
     * @return maximum acceptable tick age in milliseconds
     */
    public long getSnapshotMaxAgeMs() {
        return snapshotMaxAgeMs;
    }

    /**
     * @param snapshotMaxAgeMs maximum acceptable tick age in milliseconds
     */
    public void setSnapshotMaxAgeMs(long snapshotMaxAgeMs) {
        this.snapshotMaxAgeMs = snapshotMaxAgeMs;
    }

    /**
     * @return warm-up wait budget in milliseconds
     */
    public long getWarmupTimeoutMs() {
        return warmupTimeoutMs;
    }

    /**
     * @param warmupTimeoutMs warm-up wait budget in milliseconds
     */
    public void setWarmupTimeoutMs(long warmupTimeoutMs) {
        this.warmupTimeoutMs = warmupTimeoutMs;
    }

    /**
     * @return minimum coverage percentage required to end the warm-up wait successfully
     */
    public double getMinWarmupCoveragePct() {
        return minWarmupCoveragePct;
    }

    /**
     * @param minWarmupCoveragePct minimum coverage percentage required to end the warm-up wait
     */
    public void setMinWarmupCoveragePct(double minWarmupCoveragePct) {
        this.minWarmupCoveragePct = minWarmupCoveragePct;
    }

    /**
     * @return interval between coverage polls during warm-up, in milliseconds
     */
    public long getWarmupPollIntervalMs() {
        return warmupPollIntervalMs;
    }

    /**
     * @param warmupPollIntervalMs interval between coverage polls during warm-up, in milliseconds
     */
    public void setWarmupPollIntervalMs(long warmupPollIntervalMs) {
        this.warmupPollIntervalMs = warmupPollIntervalMs;
    }
}
