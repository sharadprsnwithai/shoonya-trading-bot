package com.tradingbot.marketdata.feed;

import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.config.ShoonyaFeedProperties;
import com.tradingbot.telegram.TelegramService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Watches the Shoonya streaming feed and pushes a Telegram alert when it goes down and when it
 * recovers, so a mid-session outage is visible immediately rather than discovered when a strategy
 * silently stops getting prices.
 *
 * <p>The feed reports health through {@link ShoonyaFeedClient#isUp()}, which is only true while the
 * socket is authenticated and false for the entire reconnect window. That flag is deliberately
 * polled rather than event-driven: the gateway throws occasional one-to-three-second connection
 * resets during a normal session, and reacting to every flip would flood the channel with noise.
 *
 * <p>Instead this component applies a simple debounce:
 *
 * <ul>
 *   <li>A <b>down</b> alert is sent only after the feed has stayed continuously down for {@link
 *       ShoonyaFeedProperties#getHealthDownThresholdSeconds()}, which filters out transient blips
 *       but still catches a genuine outage (like the ~58-minute Finvasia backend outage) within a
 *       minute.
 *   <li>A <b>recovered</b> alert is sent only if a down alert was actually sent for that outage, so
 *       a blip that self-healed before the threshold never produces a matching "recovered" message.
 * </ul>
 *
 * <p>The whole component is inert when the feed (or its health alerts) is disabled, so an
 * intentionally-off feed never reports a permanent outage.
 */
@Component
public class ShoonyaFeedHealthAlerter {

    private static final Logger log = LoggerFactory.getLogger(ShoonyaFeedHealthAlerter.class);
    private static final DateTimeFormatter IST_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata"));

    private final ShoonyaFeedClient feedClient;
    private final ShoonyaFeedProperties properties;
    private final ShoonyaConfig shoonyaConfig;
    private final TelegramService telegramService;
    private final ClockHolder clock;

    /** Whether a down alert is outstanding for the current outage. */
    private boolean alertedDown;

    /** When the current down streak began, or null while up. */
    private Instant downSince;

    @Autowired
    public ShoonyaFeedHealthAlerter(
            ShoonyaFeedClient feedClient,
            ShoonyaFeedProperties properties,
            ShoonyaConfig shoonyaConfig,
            @Autowired(required = false) TelegramService telegramService) {
        this(feedClient, properties, shoonyaConfig, telegramService, ClockHolder.system());
    }

    /** Test-visible constructor that injects a controllable clock. */
    ShoonyaFeedHealthAlerter(
            ShoonyaFeedClient feedClient,
            ShoonyaFeedProperties properties,
            ShoonyaConfig shoonyaConfig,
            TelegramService telegramService,
            ClockHolder clock) {
        this.feedClient = feedClient;
        this.properties = properties;
        this.shoonyaConfig = shoonyaConfig;
        this.telegramService = telegramService;
        this.clock = clock;
    }

    /** One health poll; runs on the shared Spring scheduler. */
    @Scheduled(fixedDelayString = "${trading-bot.shoonya.feed.health-poll-ms:15000}")
    public void poll() {
        evaluate(feedClient.isUp(), clock.instant());
    }

    /**
     * Advances the health state machine by one observation and dispatches any transition alert.
     * Package-private so tests can drive a synthetic up/down sequence without the scheduler.
     *
     * @param up current {@link ShoonyaFeedClient#isUp()} value
     * @param now observation timestamp
     */
    void evaluate(boolean up, Instant now) {
        if (!properties.isHealthAlertsEnabled()
                || !properties.isEnabled()
                || !shoonyaConfig.isEnabled()) {
            // Feed or alerting intentionally off: never report, and clear any pending state.
            alertedDown = false;
            downSince = null;
            return;
        }

        LocalTime timeNow = LocalTime.ofInstant(now, ZoneId.of("Asia/Kolkata"));
        if (!properties.isWithinOperatingWindow(timeNow)) {
            // Outside operating window: feed is expected to be inactive; suppress down alerts.
            alertedDown = false;
            downSince = null;
            return;
        }

        if (up) {
            if (alertedDown) {
                long outageSeconds = downSince != null ? secondsSince(downSince, now) : 0;
                sendRecovery(outageSeconds, downSince);
                alertedDown = false;
            }
            downSince = null;
            return;
        }

        // Feed is down.
        if (downSince == null) {
            downSince = now;
        }
        if (!alertedDown
                && secondsSince(downSince, now) >= properties.getHealthDownThresholdSeconds()) {
            sendDown(downSince);
            alertedDown = true;
        }
    }

    /** Seconds elapsed since {@code since}, floored at zero. */
    private static long secondsSince(Instant since, Instant now) {
        return Math.max(0, Duration.between(since, now).getSeconds());
    }

    /** Dispatches the "feed down" alert. */
    private void sendDown(Instant since) {
        String msg =
                "🔴 *SHOONYA FEED DOWN*\n"
                        + "Live market data unavailable since `"
                        + IST_TIME.format(since)
                        + "` IST.\n"
                        + "LVR may fall back to REST or pause. Monitoring for recovery…";
        dispatch(msg);
    }

    /** Dispatches the "feed recovered" alert. */
    private void sendRecovery(long outageSeconds, Instant since) {
        long mins = outageSeconds / 60;
        long secs = outageSeconds % 60;
        String msg =
                "🟢 *SHOONYA FEED RECOVERED*\n"
                        + "Live data restored after "
                        + mins
                        + "m "
                        + secs
                        + "s (down since `"
                        + (since != null ? IST_TIME.format(since) : "n/a")
                        + "` IST).";
        dispatch(msg);
    }

    /** Sends via Telegram when present; always logs so the transition is visible in-app too. */
    private void dispatch(String message) {
        log.warn("[FEED-HEALTH] {}", message.replace('\n', ' '));
        if (telegramService != null) {
            try {
                telegramService.sendTextMessage(message);
            } catch (Exception e) {
                log.error("[FEED-HEALTH] Failed to send Telegram alert: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Minimal clock indirection so tests can pin time without waiting on real delays. Kept as a
     * tiny holder rather than injecting {@link java.time.Clock} directly so the production wiring
     * stays a no-arg default while tests construct with a fixed source.
     */
    record ClockHolder(java.time.Clock delegate) {
        static ClockHolder system() {
            return new ClockHolder(java.time.Clock.systemUTC());
        }

        Instant instant() {
            return delegate.instant();
        }
    }
}
