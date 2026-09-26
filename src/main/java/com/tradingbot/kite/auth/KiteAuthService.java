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
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/120.0.0.0 Safari/537.36";

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
                        "[KITE-AUTH] Headless auto-login notice: {}. Standing by for web login.",
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
                            .followRedirects(HttpClient.Redirect.NEVER)
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
                            .header("Origin", "https://kite.zerodha.com")
                            .header("Referer", "https://kite.zerodha.com/")
                            .header("User-Agent", BROWSER_UA)
                            .POST(HttpRequest.BodyPublishers.ofString(loginForm))
                            .build();

            HttpResponse<String> loginResp =
                    client.send(loginReq, HttpResponse.BodyHandlers.ofString());
            ObjectMapper mapper = new ObjectMapper();
            JsonNode loginJson = mapper.readTree(loginResp.body());

            String requestId = loginJson.path("data").path("request_id").asText();
            if (requestId == null || requestId.isBlank()) {
                String errMsg = loginJson.path("message").asText(loginResp.body());
                throw new IllegalStateException("Zerodha login rejected: " + errMsg);
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
                            + "&twofa_type=totp&skip_session=true";

            HttpRequest twofaReq =
                    HttpRequest.newBuilder()
                            .uri(URI.create("https://kite.zerodha.com/api/twofa"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("X-Kite-Version", "3")
                            .header("Origin", "https://kite.zerodha.com")
                            .header("Referer", "https://kite.zerodha.com/")
                            .header("User-Agent", BROWSER_UA)
                            .POST(HttpRequest.BodyPublishers.ofString(twofaForm))
                            .build();

            HttpResponse<String> twofaResp =
                    client.send(twofaReq, HttpResponse.BodyHandlers.ofString());
            JsonNode twofaJson = mapper.readTree(twofaResp.body());
            if ("error".equalsIgnoreCase(twofaJson.path("status").asText())) {
                throw new IllegalStateException(
                        "Zerodha 2FA rejected: " + twofaJson.path("message").asText());
            }

            // 4. Capture request_token from connect OAuth URL
            String connectUrl =
                    "https://kite.zerodha.com/connect/login?v=3&api_key=" + kiteProperties.apiKey();
            if (kiteProperties.redirectUri() != null && !kiteProperties.redirectUri().isBlank()) {
                connectUrl +=
                        "&redirect_uri="
                                + URLEncoder.encode(
                                        kiteProperties.redirectUri(), StandardCharsets.UTF_8);
            }

            HttpRequest connectReq =
                    HttpRequest.newBuilder()
                            .uri(URI.create(connectUrl))
                            .header("Referer", "https://kite.zerodha.com/")
                            .header("User-Agent", BROWSER_UA)
                            .GET()
                            .build();

            HttpResponse<String> connectResp =
                    client.send(connectReq, HttpResponse.BodyHandlers.ofString());
            String location = connectResp.headers().firstValue("Location").orElse(null);

            // Follow OAuth redirect hops (e.g. /connect/login -> /connect/finish ->
            // redirect_uri?request_token=...)
            int hops = 0;
            String requestToken = extractRequestToken(location);

            while (requestToken == null && location != null && hops < 5) {
                hops++;
                String nextUrl =
                        location.startsWith("http")
                                ? location
                                : "https://kite.zerodha.com" + location;
                log.info("[KITE-AUTH] Following OAuth redirect hop {}: {}", hops, nextUrl);

                HttpRequest hopReq =
                        HttpRequest.newBuilder()
                                .uri(URI.create(nextUrl))
                                .header("Referer", "https://kite.zerodha.com/")
                                .header("User-Agent", BROWSER_UA)
                                .GET()
                                .build();

                HttpResponse<String> hopResp =
                        client.send(hopReq, HttpResponse.BodyHandlers.ofString());
                location = hopResp.headers().firstValue("Location").orElse(null);
                requestToken = extractRequestToken(location);
                if (requestToken == null && hopResp.uri() != null) {
                    requestToken = extractRequestToken(hopResp.uri().toString());
                }
            }

            if (requestToken != null && !requestToken.isBlank()) {
                log.info(
                        "[KITE-AUTH] Headless OAuth handshake succeeded. Exchanging request_token...");
                return exchangeRequestToken(requestToken);
            } else {
                log.warn(
                        "[KITE-AUTH] Auto-login completed 2FA, but could not capture request_token. Final Location: {}",
                        location);
                return status();
            }
        } catch (Exception e) {
            log.error("[KITE-AUTH] Headless auto-login error: {}", e.getMessage(), e);
            throw new IllegalStateException("Auto-login failed: " + e.getMessage(), e);
        }
    }

    private static String extractRequestToken(String locationUrl) {
        if (locationUrl == null || !locationUrl.contains("request_token=")) {
            return null;
        }
        try {
            URI locUri = URI.create(locationUrl);
            String query = locUri.getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    if (param.startsWith("request_token=")) {
                        return param.substring("request_token=".length());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
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
