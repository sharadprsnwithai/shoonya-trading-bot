package com.tradingbot.marketdata.feed;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.config.ShoonyaFeedProperties;
import com.tradingbot.telegram.TelegramService;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for the debounced feed-health alerter. Drives {@code evaluate()} with a synthetic
 * up/down timeline so the down-threshold and "recovered only if alerted" rules are verified without
 * real delays or a live feed.
 */
class ShoonyaFeedHealthAlerterTest {

    private static final Instant T0 = Instant.parse("2026-10-19T05:00:00Z");

    private ShoonyaFeedProperties properties;
    private TelegramService telegram;
    private ShoonyaFeedHealthAlerter alerter;

    @BeforeEach
    void setUp() {
        ShoonyaFeedClient feedClient = mock(ShoonyaFeedClient.class);
        ShoonyaConfig shoonyaConfig = mock(ShoonyaConfig.class);
        telegram = mock(TelegramService.class);
        properties = new ShoonyaFeedProperties(); // enabled, alerts on, 60s threshold
        when(shoonyaConfig.isEnabled()).thenReturn(true);
        alerter =
                new ShoonyaFeedHealthAlerter(
                        feedClient,
                        properties,
                        shoonyaConfig,
                        telegram,
                        new ShoonyaFeedHealthAlerter.ClockHolder(Clock.systemUTC()));
    }

    @Test
    void sustainedOutage_alertsDownOnceThenRecovery() {
        alerter.evaluate(false, T0); // down streak begins
        alerter.evaluate(false, T0.plusSeconds(30)); // still under 60s threshold
        verifyNoInteractions(telegram);

        alerter.evaluate(false, T0.plusSeconds(60)); // threshold reached -> DOWN
        alerter.evaluate(false, T0.plusSeconds(90)); // still down -> no second DOWN

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(telegram, times(1)).sendTextMessage(captor.capture());
        assertTrue(captor.getValue().contains("DOWN"));

        alerter.evaluate(true, T0.plusSeconds(120)); // back up -> RECOVERED
        verify(telegram, times(2)).sendTextMessage(captor.capture());
        assertTrue(captor.getValue().contains("RECOVERED"));
    }

    @Test
    void briefBlip_underThreshold_isIgnoredEntirely() {
        alerter.evaluate(false, T0);
        alerter.evaluate(false, T0.plusSeconds(15));
        alerter.evaluate(true, T0.plusSeconds(30)); // recovered before threshold
        verifyNoInteractions(telegram); // no DOWN and no spurious RECOVERED
    }

    @Test
    void secondOutage_afterRecovery_alertsAgain() {
        alerter.evaluate(false, T0);
        alerter.evaluate(false, T0.plusSeconds(60)); // DOWN #1
        alerter.evaluate(true, T0.plusSeconds(90)); // RECOVERED

        alerter.evaluate(false, T0.plusSeconds(120));
        alerter.evaluate(false, T0.plusSeconds(180)); // DOWN #2

        verify(telegram, times(2)).sendTextMessage(contains("DOWN"));
        verify(telegram, times(1)).sendTextMessage(contains("RECOVERED"));
    }

    @Test
    void alertingDisabled_neverSends() {
        properties.setHealthAlertsEnabled(false);
        alerter.evaluate(false, T0);
        alerter.evaluate(false, T0.plusSeconds(120));
        alerter.evaluate(true, T0.plusSeconds(150));
        verifyNoInteractions(telegram);
    }

    @Test
    void feedDisabled_neverSends() {
        // Simulate the feed being switched off: the alerter must not report a permanent outage.
        ShoonyaFeedClient feedClient = mock(ShoonyaFeedClient.class);
        ShoonyaConfig offConfig = mock(ShoonyaConfig.class);
        when(offConfig.isEnabled()).thenReturn(false);
        ShoonyaFeedHealthAlerter offAlerter =
                new ShoonyaFeedHealthAlerter(
                        feedClient,
                        properties,
                        offConfig,
                        telegram,
                        new ShoonyaFeedHealthAlerter.ClockHolder(Clock.systemUTC()));

        offAlerter.evaluate(false, T0);
        offAlerter.evaluate(false, T0.plusSeconds(120));
        offAlerter.evaluate(true, T0.plusSeconds(150));
        verifyNoInteractions(telegram);
    }

    @Test
    void recoveryOnlyReported_whenADownAlertWasSent() {
        // A down streak that never reached the threshold must not emit a "recovered" ping.
        alerter.evaluate(false, T0);
        alerter.evaluate(true, T0.plusSeconds(45)); // recovered under threshold
        verifyNoInteractions(telegram);
    }
}
