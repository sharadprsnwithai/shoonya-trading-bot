package com.tradingbot.kite.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.kite.auth.KiteSession;
import com.tradingbot.kite.config.KiteProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KiteRestClientTest {

    private KiteRestClient kiteRestClient;
    private MockRestServiceServer mockServer;
    private KiteSession kiteSession;

    @BeforeEach
    void setUp() {
        KiteProperties props =
                new KiteProperties(
                        true,
                        "test_api_key",
                        "test_api_secret",
                        "test_user",
                        "test_pwd",
                        "test_totp",
                        "http://localhost:8080/callback",
                        null,
                        "data/test_session.json",
                        "http://localhost:3000");

        kiteSession = new KiteSession(new ObjectMapper(), "data/test_session.json");
        kiteSession.update(new KiteSession.Session("mock_access_token", "AB1234", "Sharad"));

        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.kite.trade");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        kiteRestClient = new KiteRestClient(props, kiteSession, builder.build());
    }

    @Test
    void testPlaceOrderSuccess() {
        mockServer
                .expect(requestTo("https://api.kite.trade/orders/regular"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "token test_api_key:mock_access_token"))
                .andRespond(
                        withSuccess(
                                "{\"status\":\"success\",\"data\":{\"order_id\":\"2603300001\"}}",
                                MediaType.APPLICATION_JSON));

        KiteRestClient.KiteOrderRequest req =
                new KiteRestClient.KiteOrderRequest(
                        "NFO",
                        "RELIANCE26MARFUT",
                        "BUY",
                        250,
                        "MIS",
                        "LIMIT",
                        2500.0,
                        null);

        KiteRestClient.KiteOrderResponse resp = kiteRestClient.placeOrder(req);
        assertNotNull(resp);
        assertEquals("2603300001", resp.orderId());
        mockServer.verify();
    }

    @Test
    void testCancelOrderSuccess() {
        mockServer
                .expect(requestTo("https://api.kite.trade/orders/regular/2603300001"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "token test_api_key:mock_access_token"))
                .andRespond(
                        withSuccess(
                                "{\"status\":\"success\",\"data\":{\"order_id\":\"2603300001\"}}",
                                MediaType.APPLICATION_JSON));

        boolean cancelled = kiteRestClient.cancelOrder("2603300001");
        assertTrue(cancelled);
        mockServer.verify();
    }
}
