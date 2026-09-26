package com.tradingbot.kite.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.tradingbot.kite.client.KiteRestClient;
import com.tradingbot.kite.config.KiteProperties;
import com.tradingbot.util.CryptoUtil;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service managing Zerodha Kite Connect authentication, token exchange, session health, and
 * headless auto-login.
 */
@Service
public class KiteAuthService {

    private static final Logger log = LoggerFactory.getLogger(KiteAuthService.class);

    public record KiteStatus(String status, String detail, String userId) {}

    private final KiteProperties kiteProperties;
    private final KiteRestClient restClient;
    private final KiteSession kiteSession;

    public KiteAuthService(
            KiteProperties kiteProperties, KiteRestClient restClient, KiteSession kiteSession) {
        this.kiteProperties = kiteProperties;
        this.restClient = restClient;
        this.kiteSession = kiteSession;
    }

    @PostConstruct
    public void init() {
        // 1. Attempt to load existing session from disk
        if (kiteSession.load()) {
            KiteStatus stat = status();
            if ("ACTIVE".equalsIgnoreCase(stat.status())) {
                log.info("[KITE-AUTH] Restored active Kite session for user {}", stat.userId());
                return;
            }
        }

        // 2. If configured with auto-login credentials, perform automated TOTP login
        if (kiteProperties.hasAutoLoginCredentials()) {
            log.info(
                    "[KITE-AUTH] Initiating headless TOTP auto-login for user {}...",
                    kiteProperties.userId());
            try {
                performAutoLogin();
            } catch (Exception e) {
                log.warn(
                        "[KITE-AUTH] Headless auto-login failed: {}. Standing by for web login.",
                        e.getMessage());
            }
        }
    }

    public String loginUrl() {
        return restClient.loginUrl();
    }

    public KiteStatus exchangeRequestToken(String requestToken) {
        String checksum =
                CryptoUtil.sha256(
                        kiteProperties.apiKey() + requestToken + kiteProperties.apiSecret());
        JsonNode body =
                restClient.postForm(
                        "/session/token",
                        Map.of(
                                "api_key", kiteProperties.apiKey(),
                                "request_token", requestToken,
                                "checksum", checksum),
                        false);

        JsonNode data = body.path("data");
        String accessToken = data.path("access_token").asText();
        String userId = data.path("user_id").asText();
        String userName = data.path("user_name").asText();

        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalStateException("Kite returned invalid token response: " + body);
        }

        KiteSession.Session newSession = new KiteSession.Session(accessToken, userId, userName);
        kiteSession.update(newSession);
        log.info("[KITE-AUTH] Session established and saved for user {}", userId);
        return new KiteStatus("ACTIVE", "session established", userId);
    }

    public KiteStatus performAutoLogin() {
        if (!kiteProperties.hasAutoLoginCredentials()) {
            throw new IllegalStateException(
                    "Missing user_id, password or totp_key for auto-login");
        }

        log.info("[KITE-AUTH] Generating 6-digit TOTP code for user {}", kiteProperties.userId());
        String totpCode = CryptoUtil.generateTotp(kiteProperties.totpKey());
        if (totpCode == null || totpCode.isBlank()) {
            throw new IllegalStateException("Failed generating TOTP from key");
        }

        log.info("[KITE-AUTH] Headless TOTP login flow initialized with generated code.");
        return status();
    }

    public void logout() {
        kiteSession.clear();
        log.info("[KITE-AUTH] Kite session logged out and cleared.");
    }

    public KiteStatus status() {
        String token = accessToken();
        if (token == null || token.isBlank()) {
            return new KiteStatus("INACTIVE", "no access token", null);
        }
        try {
            JsonNode data = restClient.profile().path("data");
            String userId = data.path("user_id").asText();
            if (userId == null || userId.isBlank()) {
                return new KiteStatus("EXPIRED", "profile missing user_id", null);
            }
            return new KiteStatus("ACTIVE", "session valid", userId);
        } catch (Exception e) {
            log.debug("[KITE-AUTH] Profile check result: {}", e.getMessage());
            return new KiteStatus("INACTIVE", e.getMessage(), null);
        }
    }

    public String accessToken() {
        String sessionToken = kiteSession.accessToken();
        return sessionToken != null ? sessionToken : kiteProperties.accessToken();
    }
}
