package com.tradingbot.config;

import com.tradingbot.model.execution.ExecutionMode;
import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration holder for Shoonya (Finvasia NorenAPI) credentials and endpoints. Automatically
 * loads properties from .env file, environment variables, or application properties.
 */
@Configuration
@ConfigurationProperties(prefix = "trading-bot.brokers.shoonya")
public class ShoonyaConfig {

    private static final Logger log = LoggerFactory.getLogger(ShoonyaConfig.class);

    private boolean enabled = true;
    private String userId = "";
    private String password = "";
    private String totpSecret = "";
    private String clientId = "";
    private String secretKey = "";
    private String vendorCode = "NOREN_API";
    private String baseUrl = "https://api.shoonya.com";
    private String publicIp = "58.84.60.54";
    private String accessToken = "";

    // Execution Mode: PAPER vs LIVE (from .env EXECUTION_MODE)
    private ExecutionMode executionMode = ExecutionMode.PAPER;

    // Telegram Alerts Configuration
    private boolean telegramEnabled = true;
    private String telegramBotToken = "";
    private String telegramChatId = "";

    @PostConstruct
    public void init() {
        populateFromEnv();
    }

    public static ShoonyaConfig load() {
        ShoonyaConfig config = new ShoonyaConfig();
        config.populateFromEnv();
        return config;
    }

    private void populateFromEnv() {
        loadEnvFile(new File(".env"));

        this.enabled =
                Boolean.parseBoolean(getProp("SHOONYA_ENABLED", String.valueOf(this.enabled)));
        this.userId = getProp("SHOONYA_USER_ID", this.userId);
        this.password = getProp("SHOONYA_PASSWORD", this.password);
        this.totpSecret = getProp("SHOONYA_TOTP_SECRET", this.totpSecret);
        this.clientId = getProp("SHOONYA_CLIENT_ID", this.clientId);
        this.secretKey = getProp("SHOONYA_SECRET_KEY", this.secretKey);
        this.vendorCode = getProp("SHOONYA_VENDOR_CODE", this.vendorCode);
        this.baseUrl = getProp("SHOONYA_BASE_URL", this.baseUrl);
        this.publicIp = getProp("SHOONYA_PUBLIC_IP", this.publicIp);
        this.accessToken = getProp("SHOONYA_ACCESS_TOKEN", this.accessToken);

        this.telegramEnabled =
                Boolean.parseBoolean(
                        getProp("TELEGRAM_ENABLED", String.valueOf(this.telegramEnabled)));
        this.telegramBotToken = getProp("TELEGRAM_BOT_TOKEN", this.telegramBotToken);
        this.telegramChatId = getProp("TELEGRAM_CHAT_ID", this.telegramChatId);

        String modeStr =
                getProp(
                        "EXECUTION_MODE",
                        getProp("SHOONYA_EXECUTION_MODE", getProp("TRADING_MODE", "PAPER")));
        try {
            this.executionMode = ExecutionMode.valueOf(modeStr.trim().toUpperCase());
        } catch (Exception e) {
            log.warn("Invalid EXECUTION_MODE '{}' in .env, defaulting to PAPER", modeStr);
            this.executionMode = ExecutionMode.PAPER;
        }
        log.info("Execution mode initialized from .env / system: {}", this.executionMode);
    }

    /** Resolves the active public IP (from config, or dynamically from external resolver). */
    public String resolvePublicIp() {
        if (publicIp != null && !publicIp.isBlank()) {
            return publicIp.trim();
        }
        try {
            HttpClient client =
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest req =
                    HttpRequest.newBuilder().uri(URI.create("https://api.ipify.org")).build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            String ip = resp.body().trim();
            if (!ip.isBlank()) {
                this.publicIp = ip;
                return ip;
            }
        } catch (Exception ignored) {
        }
        return "58.84.60.54";
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
            log.warn("Failed to load .env file: {}", e.getMessage());
        }
    }

    private static String getProp(String key, String defaultValue) {
        String val = System.getProperty(key);
        if (val == null || val.isBlank()) {
            val = System.getenv(key);
        }
        return (val != null && !val.isBlank()) ? val.trim() : defaultValue;
    }

    // Getters and Setters
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getTotpSecret() {
        return totpSecret;
    }

    public void setTotpSecret(String totpSecret) {
        this.totpSecret = totpSecret;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getVendorCode() {
        return vendorCode;
    }

    public void setVendorCode(String vendorCode) {
        this.vendorCode = vendorCode;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getPublicIp() {
        return publicIp;
    }

    public void setPublicIp(String publicIp) {
        this.publicIp = publicIp;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public boolean isTelegramEnabled() {
        return telegramEnabled;
    }

    public void setTelegramEnabled(boolean telegramEnabled) {
        this.telegramEnabled = telegramEnabled;
    }

    public String getTelegramBotToken() {
        return telegramBotToken;
    }

    public void setTelegramBotToken(String telegramBotToken) {
        this.telegramBotToken = telegramBotToken;
    }

    public String getTelegramChatId() {
        return telegramChatId;
    }

    public void setTelegramChatId(String telegramChatId) {
        this.telegramChatId = telegramChatId;
    }

    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(ExecutionMode executionMode) {
        this.executionMode = executionMode;
    }
}
