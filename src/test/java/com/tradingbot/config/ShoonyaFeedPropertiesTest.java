package com.tradingbot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShoonyaFeedPropertiesTest {

    @Test
    @DisplayName("Defaults configure 08:30 to 23:10 IST operating window enabled")
    void defaultOperatingWindow() {
        ShoonyaFeedProperties props = new ShoonyaFeedProperties();
        assertThat(props.isOperatingWindowEnabled()).isTrue();
        assertThat(props.getOperatingWindowStart()).isEqualTo(LocalTime.of(8, 30));
        assertThat(props.getOperatingWindowEnd()).isEqualTo(LocalTime.of(23, 10));
    }

    @Test
    @DisplayName("isWithinOperatingWindow respects 08:30 to 23:10 boundaries")
    void operatingWindowBoundaries() {
        ShoonyaFeedProperties props = new ShoonyaFeedProperties();
        props.setOperatingWindowStart(LocalTime.of(8, 30));
        props.setOperatingWindowEnd(LocalTime.of(23, 10));

        // Before window
        assertThat(props.isWithinOperatingWindow(LocalTime.of(5, 25))).isFalse();
        assertThat(props.isWithinOperatingWindow(LocalTime.of(8, 29, 59))).isFalse();

        // Exact start
        assertThat(props.isWithinOperatingWindow(LocalTime.of(8, 30))).isTrue();

        // Mid-session
        assertThat(props.isWithinOperatingWindow(LocalTime.of(12, 0))).isTrue();
        assertThat(props.isWithinOperatingWindow(LocalTime.of(18, 30))).isTrue();

        // Exact end
        assertThat(props.isWithinOperatingWindow(LocalTime.of(23, 10))).isTrue();

        // After window
        assertThat(props.isWithinOperatingWindow(LocalTime.of(23, 10, 1))).isFalse();
        assertThat(props.isWithinOperatingWindow(LocalTime.of(23, 30))).isFalse();
    }

    @Test
    @DisplayName("When operating window is disabled, any time is allowed")
    void operatingWindowDisabled() {
        ShoonyaFeedProperties props = new ShoonyaFeedProperties();
        props.setOperatingWindowEnabled(false);

        assertThat(props.isWithinOperatingWindow(LocalTime.of(5, 25))).isTrue();
        assertThat(props.isWithinOperatingWindow(LocalTime.of(23, 50))).isTrue();
    }

    @Test
    @DisplayName("Overnight window spanning midnight evaluates correctly")
    void overnightWindow() {
        ShoonyaFeedProperties props = new ShoonyaFeedProperties();
        props.setOperatingWindowStart(LocalTime.of(22, 0));
        props.setOperatingWindowEnd(LocalTime.of(4, 0));

        assertThat(props.isWithinOperatingWindow(LocalTime.of(23, 0))).isTrue();
        assertThat(props.isWithinOperatingWindow(LocalTime.of(3, 0))).isTrue();
        assertThat(props.isWithinOperatingWindow(LocalTime.of(12, 0))).isFalse();
    }
}
