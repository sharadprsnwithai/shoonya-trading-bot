package com.tradingbot.strategy.rsihighway.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradingbot.strategy.rsihighway.config.RsiHighwayConfig;
import com.tradingbot.strategy.rsihighway.model.MarketBreadthSnapshot;
import com.tradingbot.strategy.rsihighway.model.RsiHighwayState;
import com.tradingbot.strategy.rsihighway.service.RsiHighwaySwingService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RsiHighwayControllerTest {

    private RsiHighwaySwingService swingService;
    private RsiHighwayConfig config;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        swingService = mock(RsiHighwaySwingService.class);
        config = new RsiHighwayConfig();
        RsiHighwayController controller = new RsiHighwayController(swingService, config);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void testGetState() throws Exception {
        RsiHighwayState state = new RsiHighwayState();
        state.setAvailableCapital(1000000.0);
        when(swingService.getState()).thenReturn(state);

        mockMvc.perform(get("/api/strategy/rsi-highway/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCapital").value(1000000.0));
    }

    @Test
    void testGetBreadth() throws Exception {
        MarketBreadthSnapshot snapshot =
                new MarketBreadthSnapshot(
                        true, 500, 25, List.of("TCS"), 0.05, "Regime Open", Instant.now());
        when(swingService.getLastBreadthSnapshot()).thenReturn(snapshot);

        mockMvc.perform(get("/api/strategy/rsi-highway/breadth"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isHighwayOpen").value(true))
                .andExpect(jsonPath("$.leadersNear52WeekHighCount").value(25));
    }

    @Test
    void testTriggerScan() throws Exception {
        RsiHighwayState state = new RsiHighwayState();
        when(swingService.getState()).thenReturn(state);

        mockMvc.perform(post("/api/strategy/rsi-highway/scan")).andExpect(status().isOk());

        verify(swingService).evaluateEodScan();
    }

    @Test
    void testTriggerScanStock() throws Exception {
        RsiHighwayState state = new RsiHighwayState();
        when(swingService.getState()).thenReturn(state);

        mockMvc.perform(post("/api/strategy/rsi-highway/scan-stock").param("symbol", "RELIANCE"))
                .andExpect(status().isOk());

        verify(swingService).evaluateEodScanForSymbols(List.of("RELIANCE"));
    }

    @Test
    void testTriggerMorningCheck() throws Exception {
        RsiHighwayState state = new RsiHighwayState();
        when(swingService.getState()).thenReturn(state);

        mockMvc.perform(post("/api/strategy/rsi-highway/morning-check")).andExpect(status().isOk());

        verify(swingService).evaluateMorningPlungeCheck();
    }
}
