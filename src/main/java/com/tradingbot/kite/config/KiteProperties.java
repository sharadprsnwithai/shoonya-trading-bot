package com.tradingbot.kite.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trading-bot.kite")
public record KiteProperties(
        boolean enabled,
        String apiKey,
        String apiSecret,
        String userId,
        String password,
        String totpKey,
        String redirectUri,
        String accessToken,
        String sessionFile,
        String frontendRedirect) {

    public KiteProperties {
        if (sessionFile == null || sessionFile.isBlank()) {
            sessionFile = "data/kite_session.json";
        }
        if (frontendRedirect == null || frontendRedirect.isBlank()) {
            frontendRedirect = "http://localhost:3000";
        }
    }

    public boolean hasAutoLoginCredentials() {
        return userId != null
                && !userId.isBlank()
                && password != null
                && !password.isBlank()
                && totpKey != null
                && !totpKey.isBlank();
    }
}
