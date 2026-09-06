package com.tradingbot.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradingbot.config.ShoonyaConfig;
import org.junit.jupiter.api.Test;

class ShoonyaAuthenticatorTest {

    @Test
    void testDisabledAuthenticatorReturnsMockToken() {
        ShoonyaConfig config = new ShoonyaConfig();
        config.setEnabled(false);
        config.setUserId("USER123");

        ShoonyaAuthenticator auth = new ShoonyaAuthenticator(config);
        String token = auth.getOrAuthenticateToken();

        assertThat(token).isEqualTo("mock_shoonya_token_USER123");
    }

    @Test
    void testExplicitAccessTokenUsedDirectly() {
        ShoonyaConfig config = new ShoonyaConfig();
        config.setEnabled(true);
        config.setAccessToken("explicit_access_token_abc");

        ShoonyaAuthenticator auth = new ShoonyaAuthenticator(config);
        String token = auth.getOrAuthenticateToken();

        assertThat(token).isEqualTo("explicit_access_token_abc");
    }

    @Test
    void testInvalidateSessionClearsToken() {
        ShoonyaConfig config = new ShoonyaConfig();
        config.setEnabled(false);
        config.setUserId("USER123");

        ShoonyaAuthenticator auth = new ShoonyaAuthenticator(config);
        auth.getOrAuthenticateToken();
        auth.invalidateSession();

        // Should return a fresh mock token
        String token = auth.getOrAuthenticateToken();
        assertThat(token).isEqualTo("mock_shoonya_token_USER123");
    }
}
