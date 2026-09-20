package com.tradingbot.strategy.kiss.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradingbot.strategy.kiss.model.KissSignal;
import com.tradingbot.strategy.kiss.model.KissSignalType;
import com.tradingbot.strategy.kiss.model.KissState;
import com.tradingbot.strategy.kiss.service.KissSwingService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(KissController.class)
class KissControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private KissSwingService swingService;

    @Test
    void testGetStatus() throws Exception {
        KissState state = new KissState();
        state.setAvailableCapital(500000.0);
        when(swingService.getState()).thenReturn(state);

        mockMvc.perform(get("/api/v1/kiss/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCapital").value(500000.0))
                .andExpect(jsonPath("$.activePositionsCount").value(0));
    }

    @Test
    void testTriggerScan() throws Exception {
        KissSignal signal =
                new KissSignal(
                        "CRUDEOIL",
                        KissSignalType.BUY_SIGNAL,
                        6000.0,
                        5900.0,
                        6300.0,
                        100.0,
                        1.67,
                        true,
                        5950.0,
                        5900.0,
                        15.0,
                        10.0,
                        "Weekly HA Green + Breakout",
                        Instant.now());

        when(swingService.scanAndExecute()).thenReturn(List.of(signal));

        mockMvc.perform(post("/api/v1/kiss/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("CRUDEOIL"))
                .andExpect(jsonPath("$[0].signalType").value("BUY_SIGNAL"));
    }
}
