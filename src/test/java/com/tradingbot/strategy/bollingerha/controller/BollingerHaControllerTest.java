package com.tradingbot.strategy.bollingerha.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradingbot.strategy.bollingerha.config.BollingerHaProperties;
import com.tradingbot.strategy.bollingerha.model.BollingerHaPosition;
import com.tradingbot.strategy.bollingerha.service.BollingerHaIntradayEngine;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class BollingerHaControllerTest {

    private static final String BASE = "/api/v1/strategy/bollinger-ha";

    @Autowired private MockMvc mockMvc;
    @Autowired private BollingerHaProperties properties;

    @MockBean private BollingerHaIntradayEngine engine;

    /** The engine toggle is real state — never leak it into the next test. */
    @AfterEach
    void restoreEnabledFlag() {
        properties.setEnabled(true);
    }

    @Test
    void testGetStatusEndpoint() throws Exception {
        mockMvc.perform(get(BASE + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyId").value("BOLLINGER_HA_1M"));
    }

    @Test
    void testSquareOffEndpoint() throws Exception {
        mockMvc.perform(post(BASE + "/square-off"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void testResetSucceedsWhenFlat() throws Exception {
        when(engine.resetDailyState()).thenReturn(true);

        mockMvc.perform(post(BASE + "/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Daily state reset successfully"));
    }

    @Test
    void testResetConflictsWhilePositionIsOpen() throws Exception {
        when(engine.resetDailyState()).thenReturn(false);
        when(engine.getActivePosition()).thenReturn(openPosition());

        mockMvc.perform(post(BASE + "/reset"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.activePosition.symbol").value("NIFTY26OCT25950CE"));
    }

    @Test
    void testStartEnablesStrategy() throws Exception {
        properties.setEnabled(false);

        mockMvc.perform(post(BASE + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        assertTrue(properties.isEnabled());
    }

    @Test
    void testStopSquaresOffThenDisables() throws Exception {
        properties.setEnabled(true);
        when(engine.getActivePosition()).thenReturn(openPosition());

        mockMvc.perform(post(BASE + "/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.squaredOff").value(true));

        assertFalse(properties.isEnabled());
        verify(engine).squareOffAll(anyString());
    }

    @Test
    void testStopWithoutPositionDoesNotSquareOff() throws Exception {
        mockMvc.perform(post(BASE + "/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.squaredOff").value(false));

        assertFalse(properties.isEnabled());
        verify(engine, never()).squareOffAll(anyString());
    }

    @Test
    void testSimulateTickIsForwardedToEngine() throws Exception {
        mockMvc.perform(post(BASE + "/simulate").param("token", "12345").param("ltp", "150.50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("12345"));

        verify(engine).onTick(eq("12345"), eq(new BigDecimal("150.50")), any(Instant.class));
    }

    private static BollingerHaPosition openPosition() {
        return new BollingerHaPosition(
                "BHA-TEST",
                "NIFTY26OCT25950CE",
                "12345",
                "CE",
                new BigDecimal("141.00"),
                new BigDecimal("124.00"),
                new BigDecimal("175.00"),
                130,
                Instant.now());
    }
}
