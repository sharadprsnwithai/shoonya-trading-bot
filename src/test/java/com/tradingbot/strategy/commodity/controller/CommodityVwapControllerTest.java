package com.tradingbot.strategy.commodity.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradingbot.strategy.commodity.model.CommodityBias;
import com.tradingbot.strategy.commodity.model.CommoditySetupState;
import com.tradingbot.strategy.commodity.model.CommodityStatusReport;
import com.tradingbot.strategy.commodity.service.CommodityVwapStrategyService;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CommodityVwapControllerTest {

    private CommodityVwapStrategyService strategyService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        strategyService = mock(CommodityVwapStrategyService.class);
        CommodityVwapController controller = new CommodityVwapController(strategyService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /api/v1/strategy/commodity-vwap/status should return report")
    void testGetStatus() throws Exception {
        CommodityStatusReport report =
                new CommodityStatusReport(
                        Instant.now(),
                        true,
                        3,
                        0,
                        Map.of(
                                "GOLD",
                                new CommodityStatusReport.CommoditySetupSummary(
                                        "GOLD",
                                        CommodityBias.BULLISH,
                                        1.25,
                                        CommoditySetupState.BIAS_IDENTIFIED,
                                        null,
                                        null,
                                        null,
                                        0,
                                        null)),
                        Collections.emptyList());

        when(strategyService.getStatusReport()).thenReturn(report);

        mockMvc.perform(get("/api/v1/strategy/commodity-vwap/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.totalSymbolsTracked").value(3))
                .andExpect(jsonPath("$.setups.GOLD.bias").value("BULLISH"));
    }

    @Test
    @DisplayName("POST /api/v1/strategy/commodity-vwap/scan should trigger scan and return status")
    void testTriggerScan() throws Exception {
        CommodityStatusReport report =
                new CommodityStatusReport(
                        Instant.now(), true, 3, 0, Collections.emptyMap(), Collections.emptyList());

        when(strategyService.getStatusReport()).thenReturn(report);

        mockMvc.perform(post("/api/v1/strategy/commodity-vwap/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        verify(strategyService).evaluateStrategyCycle();
    }

    @Test
    @DisplayName("POST /api/v1/strategy/commodity-vwap/bias should trigger 1:30 PM bias evaluation")
    void testTriggerBias() throws Exception {
        CommodityStatusReport report =
                new CommodityStatusReport(
                        Instant.now(), true, 3, 0, Collections.emptyMap(), Collections.emptyList());

        when(strategyService.getStatusReport()).thenReturn(report);

        mockMvc.perform(post("/api/v1/strategy/commodity-vwap/bias"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        verify(strategyService).evaluateDailyBias();
    }

    @Test
    @DisplayName("POST /api/v1/strategy/commodity-vwap/reset should reset session")
    void testResetSession() throws Exception {
        mockMvc.perform(post("/api/v1/strategy/commodity-vwap/reset?force=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        verify(strategyService).resetSession(true);
    }
}
