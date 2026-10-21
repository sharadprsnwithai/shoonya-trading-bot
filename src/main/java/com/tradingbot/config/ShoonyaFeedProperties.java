package com.tradingbot.config;

import java.time.LocalTime;
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

    /**
     * Interval between session heartbeats, in seconds. The gateway reaps an idle session after
     * roughly sixty seconds, so this must stay comfortably below that; values below 1 disable the
     * heartbeat and accept the reconnect churn.
     */
    private long keepAliveSeconds = 30;

    /**
     * How long a connection must survive before the reconnect backoff is allowed to reset.
     *
     * <p>Resetting on the connect acknowledgement instead - which is what this used to do - is
     * unsafe, because a gateway that accepts the session and then drops the socket presents as
     * permanently healthy. Each cycle would report a successful authentication, zero the counter,
     * and retry after the initial delay forever: one observed session logged 15,085 reconnects that
     * were all still at "attempt 1" three hours in. Resetting only after the socket has stayed up
     * for this long means backoff reflects connection quality rather than the handshake.
     */
    private long stableConnectionSeconds = 30;

    /**
     * Whether to push Telegram alerts when the feed transitions between healthy and down.
     *
     * <p>An outage is only reported once the feed has stayed down for {@link
     * #healthDownThresholdSeconds}, so the routine one-to-three-second connection resets that the
     * gateway throws during a session do not spam the channel. A recovery is reported only if a
     * down alert was actually sent for that outage.
     */
    private boolean healthAlertsEnabled = true;

    /** Interval at which the feed health is polled, in milliseconds. */
    private long healthPollMs = 15000;

    /**
     * How long the feed must remain down before a "down" alert is sent, in seconds. Sized to ride
     * out transient reconnect blips while still catching a genuine outage quickly.
     */
    private long healthDownThresholdSeconds = 60;

    /** Whether the feed connection is restricted to the configured operating window. */
    private boolean operatingWindowEnabled = true;

    /** Earliest IST time the WebSocket feed is permitted to connect (default 08:30 IST). */
    private LocalTime operatingWindowStart = LocalTime.of(8, 30);

    /** Latest IST time the WebSocket feed is permitted to remain connected (default 23:10 IST). */
    private LocalTime operatingWindowEnd = LocalTime.of(23, 10);

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

    /**
     * @return interval between session heartbeats, in seconds
     */
    public long getKeepAliveSeconds() {
        return keepAliveSeconds;
    }

    /**
     * @param keepAliveSeconds interval between session heartbeats, in seconds
     */
    public void setKeepAliveSeconds(long keepAliveSeconds) {
        this.keepAliveSeconds = keepAliveSeconds;
    }

    /**
     * @return seconds a connection must stay up before the reconnect backoff resets
     */
    public long getStableConnectionSeconds() {
        return stableConnectionSeconds;
    }

    /**
     * @param stableConnectionSeconds seconds a connection must stay up before the reconnect backoff
     *     resets
     */
    public void setStableConnectionSeconds(long stableConnectionSeconds) {
        this.stableConnectionSeconds = stableConnectionSeconds;
    }

    /**
     * @return true when feed health transitions should push Telegram alerts
     */
    public boolean isHealthAlertsEnabled() {
        return healthAlertsEnabled;
    }

    /**
     * @param healthAlertsEnabled whether to push Telegram alerts on feed health transitions
     */
    public void setHealthAlertsEnabled(boolean healthAlertsEnabled) {
        this.healthAlertsEnabled = healthAlertsEnabled;
    }

    /**
     * @return interval between feed health polls, in milliseconds
     */
    public long getHealthPollMs() {
        return healthPollMs;
    }

    /**
     * @param healthPollMs interval between feed health polls, in milliseconds
     */
    public void setHealthPollMs(long healthPollMs) {
        this.healthPollMs = healthPollMs;
    }

    /**
     * @return seconds the feed must stay down before a down alert is sent
     */
    public long getHealthDownThresholdSeconds() {
        return healthDownThresholdSeconds;
    }

    /**
     * @param healthDownThresholdSeconds seconds the feed must stay down before a down alert is sent
     */
    public void setHealthDownThresholdSeconds(long healthDownThresholdSeconds) {
        this.healthDownThresholdSeconds = healthDownThresholdSeconds;
    }

    /**
     * @return true when feed connections are restricted to the configured operating window
     */
    public boolean isOperatingWindowEnabled() {
        return operatingWindowEnabled;
    }

    /**
     * @param operatingWindowEnabled whether feed connections are restricted to the operating window
     */
    public void setOperatingWindowEnabled(boolean operatingWindowEnabled) {
        this.operatingWindowEnabled = operatingWindowEnabled;
    }

    /**
     * @return earliest time the WebSocket feed is permitted to connect
     */
    public LocalTime getOperatingWindowStart() {
        return operatingWindowStart;
    }

    /**
     * @param operatingWindowStart earliest time the WebSocket feed is permitted to connect
     */
    public void setOperatingWindowStart(LocalTime operatingWindowStart) {
        this.operatingWindowStart = operatingWindowStart;
    }

    /**
     * @return latest time the WebSocket feed is permitted to remain connected
     */
    public LocalTime getOperatingWindowEnd() {
        return operatingWindowEnd;
    }

    /**
     * @param operatingWindowEnd latest time the WebSocket feed is permitted to remain connected
     */
    public void setOperatingWindowEnd(LocalTime operatingWindowEnd) {
        this.operatingWindowEnd = operatingWindowEnd;
    }

    /**
     * Checks whether the given time falls within the configured operating window.
     *
     * @param time time to check
     * @return true when within operating window or when operating window check is disabled
     */
    public boolean isWithinOperatingWindow(LocalTime time) {
        if (!operatingWindowEnabled
                || time == null
                || operatingWindowStart == null
                || operatingWindowEnd == null) {
            return true;
        }
        if (!operatingWindowStart.isAfter(operatingWindowEnd)) {
            return !time.isBefore(operatingWindowStart) && !time.isAfter(operatingWindowEnd);
        } else {
            return !time.isBefore(operatingWindowStart) || !time.isAfter(operatingWindowEnd);
        }
    }
}
