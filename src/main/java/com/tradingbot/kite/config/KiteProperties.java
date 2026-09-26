package com.tradingbot.kite.config;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Configuration properties for Zerodha Kite Connect API. */
@Configuration
@ConfigurationProperties(prefix = "trading-bot.kite")
public class KiteProperties {

    private static final Logger log = LoggerFactory.getLogger(KiteProperties.class);

    private boolean enabled = true;
    private String apiKey = "xz6f5qndx8fl6jc5";
    private String apiSecret = "w2hl2ppyv3k0bqem4i31tq8bmwi9npsx";
    private String userId = "";
    private String password = "";
    private String totpKey = "";
    private String redirectUri =
            "https://untaxed-substance-reputably.ngrok-free.dev/api/kite/auth/callback";
    private String accessToken = "";
    private String sessionFile = "data/kite_session.json";
    private String frontendRedirect = "https://untaxed-substance-reputably.ngrok-free.dev";

    public KiteProperties() {}

    public KiteProperties(
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
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.userId = userId;
        this.password = password;
        this.totpKey = totpKey;
        this.redirectUri = redirectUri;
        this.accessToken = accessToken;
        this.sessionFile =
                (sessionFile != null && !sessionFile.isBlank())
                        ? sessionFile
                        : "data/kite_session.json";
        this.frontendRedirect =
                (frontendRedirect != null && !frontendRedirect.isBlank())
                        ? frontendRedirect
                        : "https://untaxed-substance-reputably.ngrok-free.dev";
    }

    @PostConstruct
    public void init() {
        populateFromEnv();
    }

    private void populateFromEnv() {
        loadEnvFile(new File(".env"));

        this.enabled = Boolean.parseBoolean(getProp("KITE_ENABLED", String.valueOf(this.enabled)));
        this.apiKey = getProp("KITE_API_KEY", this.apiKey);
        this.apiSecret = getProp("KITE_API_SECRET", this.apiSecret);
        this.userId = getProp("KITE_USER_ID", this.userId);
        this.password = getProp("KITE_PASSWORD", this.password);
        this.totpKey = getProp("KITE_TOTP_KEY", this.totpKey);
        this.redirectUri = getProp("KITE_REDIRECT_URI", this.redirectUri);
        this.accessToken = getProp("KITE_ACCESS_TOKEN", this.accessToken);
        this.sessionFile = getProp("KITE_SESSION_FILE", this.sessionFile);
        this.frontendRedirect = getProp("KITE_FRONTEND_REDIRECT", this.frontendRedirect);
    }

    private static void loadEnvFile(File envFile) {
        if (!envFile.exists()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(envFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
                    continue;
                }
                int eqIdx = line.indexOf('=');
                String key = line.substring(0, eqIdx).trim();
                String val = line.substring(eqIdx + 1).trim().replace("\"", "").replace("'", "");
                if (System.getProperty(key) == null) {
                    System.setProperty(key, val);
                }
            }
        } catch (Exception e) {
            log.warn("Failed loading .env for Kite properties: {}", e.getMessage());
        }
    }

    private static String getProp(String key, String defaultValue) {
        String val = System.getProperty(key);
        if (val == null || val.isBlank()) {
            val = System.getenv(key);
        }
        return (val != null && !val.isBlank()) ? val.trim() : defaultValue;
    }

    public boolean hasAutoLoginCredentials() {
        return userId != null
                && !userId.isBlank()
                && password != null
                && !password.isBlank()
                && totpKey != null
                && !totpKey.isBlank();
    }

    // Accessors
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String apiKey() {
        return apiKey;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String apiSecret() {
        return apiSecret;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(String apiSecret) {
        this.apiSecret = apiSecret;
    }

    public String userId() {
        return userId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String password() {
        return password;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String totpKey() {
        return totpKey;
    }

    public String getTotpKey() {
        return totpKey;
    }

    public void setTotpKey(String totpKey) {
        this.totpKey = totpKey;
    }

    public String redirectUri() {
        return redirectUri;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public String accessToken() {
        return accessToken;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String sessionFile() {
        return sessionFile;
    }

    public String getSessionFile() {
        return sessionFile;
    }

    public void setSessionFile(String sessionFile) {
        this.sessionFile = sessionFile;
    }

    public String frontendRedirect() {
        return frontendRedirect;
    }

    public String getFrontendRedirect() {
        return frontendRedirect;
    }

    public void setFrontendRedirect(String frontendRedirect) {
        this.frontendRedirect = frontendRedirect;
    }
}
