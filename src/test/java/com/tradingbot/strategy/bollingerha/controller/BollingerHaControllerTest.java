package com.tradingbot.strategy.bollingerha.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class BollingerHaControllerTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void testGetStatusEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/strategy/bollinger-ha/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyId").value("BOLLINGER_HA_1M"));
    }

    @Test
    void testSquareOffEndpoint() throws Exception {
        mockMvc.perform(post("/api/v1/strategy/bollinger-ha/square-off"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
    }
}
