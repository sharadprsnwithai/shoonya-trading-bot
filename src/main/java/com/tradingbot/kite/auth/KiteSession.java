package com.tradingbot.kite.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Manages in-memory storage and file persistence of the active Zerodha Kite Connect user session.
 */
@Component
public class KiteSession {

    private static final Logger log = LoggerFactory.getLogger(KiteSession.class);

    public record Session(String accessToken, String userId, String userName) {}

    private final AtomicReference<Session> session = new AtomicReference<>();
    private final ObjectMapper objectMapper;
    private final Path sessionFile;

    public KiteSession(
            ObjectMapper objectMapper,
            @Value("${trading-bot.kite.session-file:data/kite_session.json}")
                    String sessionFilePath) {
        this.objectMapper = objectMapper;
        this.sessionFile = Path.of(sessionFilePath);
    }

    public void update(Session newSession) {
        session.set(newSession);
        persist(newSession);
    }

    public void clear() {
        session.set(null);
        try {
            Files.deleteIfExists(sessionFile);
        } catch (IOException e) {
            log.warn("[KITE-SESSION] Failed to delete session file: {}", e.getMessage());
        }
    }

    public boolean load() {
        if (!Files.exists(sessionFile)) {
            return false;
        }
        try {
            String json = Files.readString(sessionFile, StandardCharsets.UTF_8);
            Session stored = objectMapper.readValue(json, Session.class);
            session.set(stored);
            log.info("[KITE-SESSION] Loaded Kite session for user {}", stored.userId());
            return true;
        } catch (IOException e) {
            log.warn("[KITE-SESSION] Failed to load Kite session file: {}", e.getMessage());
            return false;
        }
    }

    public String accessToken() {
        Session current = session.get();
        return current == null ? null : current.accessToken();
    }

    public Session current() {
        return session.get();
    }

    private void persist(Session newSession) {
        try {
            Path parent = sessionFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    sessionFile,
                    objectMapper.writeValueAsString(newSession),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[KITE-SESSION] Failed to persist Kite session: {}", e.getMessage());
        }
    }
}
