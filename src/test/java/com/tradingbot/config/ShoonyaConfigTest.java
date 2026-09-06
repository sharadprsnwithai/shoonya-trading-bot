package com.tradingbot.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ShoonyaConfigTest {

    @Test
    void testConfigDefaults() {
        ShoonyaConfig config = new ShoonyaConfig();
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getBaseUrl()).isEqualTo("https://api.shoonya.com");
        assertThat(config.getVendorCode()).isEqualTo("NOREN_API");
    }

    @Test
    void testConfigLoad() {
        ShoonyaConfig config = ShoonyaConfig.load();
        assertThat(config).isNotNull();
        assertThat(config.getBaseUrl()).isNotBlank();
    }
}
