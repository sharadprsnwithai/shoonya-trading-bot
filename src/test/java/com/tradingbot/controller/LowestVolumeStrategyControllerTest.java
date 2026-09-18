package com.tradingbot.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.LowestVolumeSectorState;
import com.tradingbot.scheduler.LowestVolumeReversalScheduler;
import com.tradingbot.service.LowestVolumeReversalService;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LowestVolumeStrategyController.class)
class LowestVolumeStrategyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LowestVolumeReversalService strategyService;

    @MockBean
    private LowestVolumeReversalScheduler scheduler;

    @Test
    @DisplayName("GET /api/strategy/lowest-volume/status returns complete status metrics")
    void testGetStatus() throws Exception {
        when(strategyService.isEnabled()).thenReturn(true);
        when(scheduler.isSchedulerEnabled()).thenReturn(true);
        when(strategyService.isUniverseScanCompletedToday()).thenReturn(true);
        when(strategyService.getSectorState())
                .thenReturn(
                        new LowestVolumeSectorState(
                                35, 15, LowestVolumeDirection.LONG, "NIFTY PHARMA", 1.5, List.of("SUNPHARMA", "CIPLA")));
        when(strategyService.getPaperCapital()).thenReturn(1000000.0);
        when(strategyService.getRiskPerTradePercent()).thenReturn(1.0);
        when(strategyService.getRiskPerTradeAmount()).thenReturn(10000.0);
        when(strategyService.getMaxConcurrentTrades()).thenReturn(5);
        when(strategyService.getTradeHistory()).thenReturn(Collections.emptyList());
        when(strategyService.getActiveSetups()).thenReturn(Collections.emptyMap());
        when(strategyService.getOpenPositions()).thenReturn(Collections.emptyMap());
        when(strategyService.getExhaustedSymbols()).thenReturn(Collections.emptySet());

        mockMvc.perform(get("/api/strategy/lowest-volume/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("Lowest Volume Reversal & Continuation"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.sectorState.topSector").value("NIFTY PHARMA"))
                .andExpect(jsonPath("$.sectorState.sentiment").value("LONG"));
    }

    @Test
    @DisplayName("POST /api/strategy/lowest-volume/morning-scan triggers morning scan")
    void testRunMorningScan() throws Exception {
        mockMvc.perform(post("/api/strategy/lowest-volume/morning-scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        verify(strategyService).runMorningUniverseScan();
    }

    @Test
    @DisplayName("POST /api/strategy/lowest-volume/scan triggers strategy cycle")
    void testRunCycle() throws Exception {
        mockMvc.perform(post("/api/strategy/lowest-volume/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        verify(strategyService).runCycle();
    }

    @Test
    @DisplayName("POST /api/strategy/lowest-volume/reset resets strategy state")
    void testReset() throws Exception {
        mockMvc.perform(post("/api/strategy/lowest-volume/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        verify(strategyService).resetDaily();
    }
}
