package com.tradingbot.kite.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KiteSessionTest {

    private Path tempSessionFile;
    private KiteSession kiteSession;

    @BeforeEach
    void setUp() throws Exception {
        tempSessionFile = Files.createTempFile("kite-session-test", ".json");
        kiteSession = new KiteSession(new ObjectMapper(), tempSessionFile.toString());
    }

    @AfterEach
    void tearDown() {
        try {
            Files.deleteIfExists(tempSessionFile);
        } catch (Exception ignored) {
        }
    }

    @Test
    void testUpdatePersistsSessionAndLoadsCorrectly() {
        KiteSession.Session session =
                new KiteSession.Session("test-access-token", "AB1234", "Sharad User");
        kiteSession.update(session);

        assertEquals("test-access-token", kiteSession.accessToken());
        assertEquals("AB1234", kiteSession.current().userId());

        // Create new session instance pointing to same file and verify load()
        KiteSession reloadedSession =
                new KiteSession(new ObjectMapper(), tempSessionFile.toString());
        boolean loaded = reloadedSession.load();
        assertTrue(loaded);
        assertEquals("test-access-token", reloadedSession.accessToken());
        assertEquals("Sharad User", reloadedSession.current().userName());
    }

    @Test
    void testClearDeletesSessionAndFile() {
        KiteSession.Session session = new KiteSession.Session("test-token", "AB1234", "Sharad");
        kiteSession.update(session);
        assertNotNull(kiteSession.current());

        kiteSession.clear();
        assertNull(kiteSession.current());
        assertNull(kiteSession.accessToken());
        assertFalse(Files.exists(tempSessionFile));
    }
}
