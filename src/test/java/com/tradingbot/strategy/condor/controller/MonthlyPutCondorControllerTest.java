package com.tradingbot.strategy.condor.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.tradingbot.strategy.condor.model.PutCondorPosition;
import com.tradingbot.strategy.condor.model.PutCondorState;
import com.tradingbot.strategy.condor.repository.SqlitePutCondorRepository;
import com.tradingbot.strategy.condor.scheduler.MonthlyPutCondorScheduler;
import com.tradingbot.strategy.condor.service.MonthlyPutCondorService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MonthlyPutCondorController.class)
class MonthlyPutCondorControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private MonthlyPutCondorService condorService;

    @MockBean private SqlitePutCondorRepository repository;

    @MockBean private MonthlyPutCondorScheduler scheduler;

    @Test
    @DisplayName("GET /api/strategy/put-condor/status returns active position details")
    void testGetStatus() throws Exception {
        PutCondorPosition pos = new PutCondorPosition();
        pos.setState(PutCondorState.CONDOR_ACTIVE);
        pos.setK1BuyStrike(24800);
        when(condorService.getActivePosition()).thenReturn(pos);

        mockMvc.perform(get("/api/strategy/put-condor/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CONDOR_ACTIVE"))
                .andExpect(jsonPath("$.k1BuyStrike").value(24800));
    }

    @Test
    @DisplayName("POST /api/strategy/put-condor/enter triggers manual deployment")
    void testManualEnter() throws Exception {
        when(condorService.evaluateAndEnterCycle(any())).thenReturn(true);
        when(scheduler.fetchNiftySpotPrice()).thenReturn(BigDecimal.valueOf(25000.0));

        mockMvc.perform(
                        post("/api/strategy/put-condor/enter")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"spotPrice\": 25050.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(condorService, times(1)).evaluateAndEnterCycle(any());
    }

    @Test
    @DisplayName("POST /api/strategy/put-condor/exit triggers emergency square-off")
    void testEmergencyExit() throws Exception {
        mockMvc.perform(post("/api/strategy/put-condor/exit?reason=USER_MANUAL_STOP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(condorService, times(1)).squareOffAll("USER_MANUAL_STOP");
    }

    @Test
    @DisplayName("GET /api/strategy/put-condor/history returns historical records")
    void testGetHistory() throws Exception {
        when(repository.getHistory(anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/strategy/put-condor/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
