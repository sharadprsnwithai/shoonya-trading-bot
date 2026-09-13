package com.tradingbot.positional.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tradingbot.positional.config.PositionalStrategyConfig;
import com.tradingbot.positional.model.PositionalState;
import com.tradingbot.positional.model.PositionalStatus;
import com.tradingbot.positional.service.BollingerHaPositionalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BollingerHaPositionalControllerTest {

    private MockMvc mockMvc;
    private BollingerHaPositionalService positionalService;
    private PositionalStrategyConfig positionalConfig;

    @BeforeEach
    void setUp() {
        positionalService = mock(BollingerHaPositionalService.class);
        positionalConfig = new PositionalStrategyConfig();

        PositionalState defaultState = new PositionalState();
        when(positionalService.getState()).thenReturn(defaultState);

        BollingerHaPositionalController controller =
                new BollingerHaPositionalController(positionalService, positionalConfig);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void testGetStatus() throws Exception {
        PositionalState state = new PositionalState();
        state.setStatus(PositionalStatus.FLAT);
        when(positionalService.getState()).thenReturn(state);

        mockMvc.perform(get("/api/positional/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FLAT"));
    }

    @Test
    void testTriggerScan() throws Exception {
        doNothing().when(positionalService).scanAndEvaluate();
        PositionalState state = new PositionalState();
        state.setStatus(PositionalStatus.ALERT_PENDING);
        when(positionalService.getState()).thenReturn(state);

        mockMvc.perform(post("/api/positional/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALERT_PENDING"));
    }

    @Test
    void testApproveAndRejectTrade() throws Exception {
        when(positionalService.approveStagedTrade()).thenReturn(true);
        mockMvc.perform(post("/api/positional/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        when(positionalService.rejectStagedTrade()).thenReturn(true);
        mockMvc.perform(post("/api/positional/reject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void testForceExit() throws Exception {
        doNothing().when(positionalService).forceExitCurrentPosition(any());
        mockMvc.perform(post("/api/positional/exit").param("reason", "REST_API_REQUEST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void testUpdateMode() throws Exception {
        mockMvc.perform(post("/api/positional/mode").param("mode", "AUTO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionMode").value("AUTO"));
    }
}
