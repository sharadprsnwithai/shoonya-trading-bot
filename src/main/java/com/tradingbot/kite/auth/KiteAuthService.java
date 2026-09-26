package com.tradingbot.kite.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.kite.client.KiteRestClient;
import com.tradingbot.kite.config.KiteProperties;
import com.tradingbot.util.CryptoUtil;
import jakarta.annotation.PostConstruct;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
            throw new IllegalStateException("Missing user_id, password or totp_key for auto-login");
        }

        log.info(
                "[KITE-AUTH] Performing headless TOTP login for user {}...",
                kiteProperties.userId());
        try {
            CookieManager cookieManager = new CookieManager();
            HttpClient client =
                    HttpClient.newBuilder()
                            .cookieHandler(cookieManager)
                            .followRedirects(HttpClient.Redirect.ALWAYS)
                            .connectTimeout(Duration.ofSeconds(15))
                            .build();

            // 1. POST /api/login
            String loginForm =
                    "user_id="
                            + URLEncoder.encode(kiteProperties.userId(), StandardCharsets.UTF_8)
                            + "&password="
                            + URLEncoder.encode(kiteProperties.password(), StandardCharsets.UTF_8);

            HttpRequest loginReq =
                    HttpRequest.newBuilder()
                            .uri(URI.create("https://kite.zerodha.com/api/login"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("X-Kite-Version", "3")
                            .header("User-Agent", "Mozilla/5.0")
                            .POST(HttpRequest.BodyPublishers.ofString(loginForm))
                            .build();

            HttpResponse<String> loginResp =
                    client.send(loginReq, HttpResponse.BodyHandlers.ofString());
            ObjectMapper mapper = new ObjectMapper();
            JsonNode loginJson = mapper.readTree(loginResp.body());

            String requestId = loginJson.path("data").path("request_id").asText();
            if (requestId == null || requestId.isBlank()) {
                throw new IllegalStateException("Zerodha login failed: " + loginResp.body());
            }

            // 2. Generate 6-digit TOTP
            String totpCode = CryptoUtil.generateTotp(kiteProperties.totpKey());
            if (totpCode == null || totpCode.isBlank()) {
                throw new IllegalStateException("Failed generating TOTP from totp_key");
            }

            // 3. POST /api/twofa
            String twofaForm =
                    "user_id="
                            + URLEncoder.encode(kiteProperties.userId(), StandardCharsets.UTF_8)
                            + "&request_id="
                            + URLEncoder.encode(requestId, StandardCharsets.UTF_8)
                            + "&twofa_value="
                            + URLEncoder.encode(totpCode, StandardCharsets.UTF_8)
                            + "&skip_session=true";

            HttpRequest twofaReq =
                    HttpRequest.newBuilder()
                            .uri(URI.create("https://kite.zerodha.com/api/twofa"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("X-Kite-Version", "3")
                            .header("User-Agent", "Mozilla/5.0")
                            .POST(HttpRequest.BodyPublishers.ofString(twofaForm))
                            .build();

            client.send(twofaReq, HttpResponse.BodyHandlers.ofString());

            // 4. Follow connect login URL to obtain request_token
            String connectUrl = restClient.loginUrl();
            HttpRequest connectReq =
                    HttpRequest.newBuilder()
                            .uri(URI.create(connectUrl))
                            .header("User-Agent", "Mozilla/5.0")
                            .GET()
                            .build();

            HttpResponse<String> connectResp =
                    client.send(connectReq, HttpResponse.BodyHandlers.ofString());
            URI finalUri = connectResp.uri();
            String query = finalUri != null ? finalUri.getQuery() : null;

            String requestToken = null;
            if (query != null && query.contains("request_token=")) {
                for (String param : query.split("&")) {
                    if (param.startsWith("request_token=")) {
                        requestToken = param.substring("request_token=".length());
                        break;
                    }
                }
            }

            if (requestToken != null && !requestToken.isBlank()) {
                log.info(
                        "[KITE-AUTH] Headless OAuth handshake succeeded. Obtained request_token: {}...",
                        requestToken.substring(0, Math.min(6, requestToken.length())));
                return exchangeRequestToken(requestToken);
            } else {
                log.warn(
                        "[KITE-AUTH] Auto-login completed 2FA, but could not capture request_token from URI: {}",
                        finalUri);
                return status();
            }
        } catch (Exception e) {
            log.error("[KITE-AUTH] Headless auto-login error: {}", e.getMessage(), e);
            throw new IllegalStateException("Auto-login failed: " + e.getMessage(), e);
        }
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
