package com.tradingbot.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.util.CryptoUtil;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Headless OAuth authenticator & session manager for Shoonya (Finvasia NorenAPI). Orchestrates
 * QuickAuth + GenAcsTok OAuth flow and maintains a 12-hour disk session cache.
 */
@Component
public class ShoonyaAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(ShoonyaAuthenticator.class);
    private static final File SESSION_FILE = new File("data/shoonya_session.json");

    private final ShoonyaConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AtomicReference<String> sessionToken = new AtomicReference<>();

    @Autowired
    public ShoonyaAuthenticator(ShoonyaConfig config) {
        this(
                config,
                new ObjectMapper(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
    }

    public ShoonyaAuthenticator(
            ShoonyaConfig config, ObjectMapper objectMapper, HttpClient httpClient) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /** Retrieves the active session token (jKey), authenticating if necessary. */
    public synchronized String getOrAuthenticateToken() {
        if (!config.isEnabled()) {
            String mockToken = "mock_shoonya_token_" + config.getUserId();
            sessionToken.set(mockToken);
            return mockToken;
        }

        String token = sessionToken.get();
        if (token != null && !token.isBlank()) {
            return token;
        }

        if (config.getAccessToken() != null && !config.getAccessToken().isBlank()) {
            log.info("Using configured static SHOONYA_ACCESS_TOKEN");
            sessionToken.set(config.getAccessToken());
            return config.getAccessToken();
        }

        String cachedToken = loadFreshDiskSession();
        if (cachedToken != null) {
            log.info("Found cached Shoonya session in {}. Validating session key...", SESSION_FILE.getPath());
            if (validateSessionToken(cachedToken)) {
                log.info("Shoonya cached session is valid and active.");
                sessionToken.set(cachedToken);
                return cachedToken;
            } else {
                log.warn(
                        "Shoonya cached session in {} is expired or rejected by Shoonya. Invalidating and re-authenticating...",
                        SESSION_FILE.getPath());
                invalidateSession();
            }
        }

        return executeHeadlessLogin();
    }

    /**
     * Validates whether a given session token (jKey) is accepted by Shoonya API by probing UserDetails.
     */
    public boolean validateSessionToken(String token) {
        if (token == null || token.isBlank() || !config.isEnabled()) {
            return false;
        }
        try {
            Map<String, Object> payload = Map.of("uid", config.getUserId());
            String formBody = "jData=" + objectMapper.writeValueAsString(payload) + "&jKey=" + token;

            HttpRequest req =
                    HttpRequest.newBuilder()
                            .uri(URI.create(config.getBaseUrl() + "/NorenWClientAPI/UserDetails"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("X-Forwarded-For", config.resolvePublicIp())
                            .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                            .build();

            HttpResponse<String> resp =
                    httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.debug("Shoonya session validation returned HTTP {}", resp.statusCode());
                return false;
            }
            JsonNode root = objectMapper.readTree(resp.body());
            return "Ok".equalsIgnoreCase(root.path("stat").asText());
        } catch (Exception e) {
            log.warn("Shoonya session validation probe failed: {}", e.getMessage());
            return false;
        }
    }

    /** Executes the QuickAuth -> GenAcsTok OAuth flow. */
    public synchronized String executeHeadlessLogin() {
        sessionToken.set(null);
        log.info("Initiating automated headless login for Shoonya user: {}", config.getUserId());
        try {
            String publicIp = config.resolvePublicIp();
            log.info("Detected client IP: {}", publicIp);

            // Step 1: QuickAuth
            String appKey = CryptoUtil.deriveShoonyaAppKey(config.getUserId());
            String pwdSha = CryptoUtil.sha256(config.getPassword());
            String totp = CryptoUtil.generateTotp(config.getTotpSecret());

            Map<String, Object> quickAuthPayload = new LinkedHashMap<>();
            quickAuthPayload.put("apkversion", "W2_20250926");
            quickAuthPayload.put("uid", config.getUserId());
            quickAuthPayload.put("pwd", pwdSha);
            quickAuthPayload.put("factor2", totp);
            quickAuthPayload.put("appkey", appKey);
            quickAuthPayload.put("imei", "12345678-1234-1234-1234-123456789abc");
            quickAuthPayload.put("addldivinf", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            quickAuthPayload.put("source", "API");
            quickAuthPayload.put("vc", config.getVendorCode());
            quickAuthPayload.put("app_key", config.getClientId());

            String quickAuthBody = "jData=" + objectMapper.writeValueAsString(quickAuthPayload);

            HttpRequest quickAuthReq =
                    HttpRequest.newBuilder()
                            .uri(URI.create(config.getBaseUrl() + "/NorenWClientAPI/QuickAuth"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("Origin", "https://api.shoonya.com")
                            .header(
                                    "Referer",
                                    "https://api.shoonya.com/OAuthlogin/authorize/oauth?client_id="
                                            + config.getClientId())
                            .header("X-Forwarded-For", publicIp)
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            quickAuthBody, StandardCharsets.UTF_8))
                            .build();

            HttpResponse<String> quickAuthResp =
                    httpClient.send(quickAuthReq, HttpResponse.BodyHandlers.ofString());
            JsonNode quickAuthJson = objectMapper.readTree(quickAuthResp.body());

            if (!"Ok".equalsIgnoreCase(quickAuthJson.path("stat").asText())) {
                String error = quickAuthJson.path("emsg").asText(quickAuthResp.body());
                throw new IllegalStateException("Shoonya QuickAuth failed: " + error);
            }

            String authCode = quickAuthJson.path("code").asText();
            if (authCode == null || authCode.isBlank()) {
                throw new IllegalStateException(
                        "Shoonya QuickAuth succeeded but returned empty auth code");
            }
            log.info("Shoonya QuickAuth successful. Acquired auth code.");

            // Step 2: GenAcsTok exchange
            String checksum =
                    CryptoUtil.sha256(config.getClientId() + config.getSecretKey() + authCode);
            Map<String, Object> genAcsPayload = new LinkedHashMap<>();
            genAcsPayload.put("client_id", config.getClientId());
            genAcsPayload.put("code", authCode);
            genAcsPayload.put("checksum", checksum);

            String genAcsBody = "jData=" + objectMapper.writeValueAsString(genAcsPayload);

            HttpRequest genAcsReq =
                    HttpRequest.newBuilder()
                            .uri(URI.create(config.getBaseUrl() + "/NorenWClientAPI/GenAcsTok"))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("X-Forwarded-For", publicIp)
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            genAcsBody, StandardCharsets.UTF_8))
                            .build();

            HttpResponse<String> genAcsResp =
                    httpClient.send(genAcsReq, HttpResponse.BodyHandlers.ofString());
            JsonNode genAcsJson = objectMapper.readTree(genAcsResp.body());

            if (!"Ok".equalsIgnoreCase(genAcsJson.path("stat").asText())) {
                String errorMsg = genAcsJson.path("emsg").asText(genAcsResp.body());
                if (errorMsg.contains("INVALID_IP")) {
                    throw new IllegalStateException(
                            "Shoonya GenAcsTok rejected connection with 'INVALID_IP'. "
                                    + "Finvasia requires whitelisting your current public IP in the Prism portal (https://prism.finvasia.com/), "
                                    + "or configuring SHOONYA_ACCESS_TOKEN in .env.");
                }
                throw new IllegalStateException("Shoonya GenAcsTok failed: " + errorMsg);
            }

            String activeToken =
                    genAcsJson.path("susertoken").asText(genAcsJson.path("access_token").asText());
            if (activeToken == null || activeToken.isBlank()) {
                throw new IllegalStateException(
                        "Shoonya GenAcsTok succeeded but returned empty token");
            }

            log.info("Shoonya login completed successfully. Active session established.");
            sessionToken.set(activeToken);
            saveDiskSession(activeToken);
            return activeToken;

        } catch (Exception e) {
            log.error("Shoonya login failed: {}", e.getMessage());
            throw new RuntimeException("Shoonya authentication failure: " + e.getMessage(), e);
        }
    }

    /** Clears in-memory and disk session when expired or invalid. */
    public synchronized void invalidateSession() {
        sessionToken.set(null);
        try {
            if (SESSION_FILE.exists() && SESSION_FILE.delete()) {
                log.info("Invalidated and deleted Shoonya session file.");
            }
        } catch (Exception e) {
            log.warn("Failed to delete Shoonya session file: {}", e.getMessage());
        }
    }

    private String loadFreshDiskSession() {
        if (!SESSION_FILE.exists()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(SESSION_FILE);
            String token = node.path("accessToken").asText(null);
            String createdAtStr = node.path("createdAt").asText(null);
            String user = node.path("userId").asText("");

            if (token != null
                    && !token.isBlank()
                    && user.equals(config.getUserId())
                    && createdAtStr != null) {
                Instant createdAt = Instant.parse(createdAtStr);
                if (Instant.now().isBefore(createdAt.plus(12, ChronoUnit.HOURS))) {
                    return token;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse existing Shoonya session file: {}", e.getMessage());
        }
        return null;
    }

    private void saveDiskSession(String token) {
        try {
            File parent = SESSION_FILE.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            Map<String, String> data = new HashMap<>();
            data.put("accessToken", token);
            data.put("userId", config.getUserId());
            data.put("createdAt", Instant.now().toString());
            objectMapper.writeValue(SESSION_FILE, data);
        } catch (Exception e) {
            log.warn("Failed to write Shoonya session cache file: {}", e.getMessage());
        }
    }
}
