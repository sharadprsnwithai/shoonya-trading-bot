package com.tradingbot.kite.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradingbot.kite.client.KiteRestClient;
import com.tradingbot.kite.config.KiteProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KiteAuthServiceTest {

    private KiteProperties props;
    private KiteRestClient mockRestClient;
    private KiteSession kiteSession;
    private KiteAuthService authService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        props =
                new KiteProperties(
                        true,
                        "key123",
                        "sec456",
                        "USR001",
                        "pass123",
                        "JBSWY3DPEHPK3PXP",
                        "http://localhost:8080/callback",
                        null,
                        "data/test_session.json",
                        "http://localhost:3000");

        objectMapper = new ObjectMapper();
        kiteSession = new KiteSession(objectMapper, "data/test_session.json");
        mockRestClient = mock(KiteRestClient.class);
        authService = new KiteAuthService(props, mockRestClient, kiteSession);
    }

    @Test
    void testExchangeRequestTokenUpdatesSession() {
        ObjectNode mockResponse = objectMapper.createObjectNode();
        ObjectNode dataNode = mockResponse.putObject("data");
        dataNode.put("access_token", "new_token_789");
        dataNode.put("user_id", "USR001");
        dataNode.put("user_name", "Sharad Trader");

        when(mockRestClient.postForm(eq("/session/token"), anyMap(), eq(false)))
                .thenReturn(mockResponse);

        KiteAuthService.KiteStatus status = authService.exchangeRequestToken("req_token_123");
        assertNotNull(status);
        assertEquals("ACTIVE", status.status());
        assertEquals("USR001", status.userId());
        assertEquals("new_token_789", kiteSession.accessToken());
    }

    @Test
    void testLoginUrlConstructedCorrectly() {
        when(mockRestClient.loginUrl())
                .thenReturn("https://kite.zerodha.com/connect/login?v=3&api_key=key123");
        String url = authService.loginUrl();
        assertTrue(url.contains("key123"));
    }
}
