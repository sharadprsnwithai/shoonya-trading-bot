package com.tradingbot.kite.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tradingbot.kite.auth.KiteAuthService;
import com.tradingbot.kite.client.KiteRestClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(KiteAuthController.class)
class KiteAuthControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private KiteAuthService authService;
    @MockBean private KiteRestClient restClient;

    @Test
    void testGetLoginUrl() throws Exception {
        when(authService.loginUrl())
                .thenReturn("https://kite.zerodha.com/connect/login?v=3&api_key=test");

        mockMvc.perform(get("/api/v1/kite/login-url"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.loginUrl")
                                .value("https://kite.zerodha.com/connect/login?v=3&api_key=test"));
    }

    @Test
    void testGetStatus() throws Exception {
        when(authService.status())
                .thenReturn(new KiteAuthService.KiteStatus("ACTIVE", "Session valid", "AB1234"));

        mockMvc.perform(get("/api/v1/kite/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.userId").value("AB1234"));
    }

    @Test
    void testLogout() throws Exception {
        mockMvc.perform(post("/api/v1/kite/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
