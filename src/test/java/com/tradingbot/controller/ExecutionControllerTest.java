package com.tradingbot.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradingbot.execution.ExecutionManager;
import com.tradingbot.model.execution.ActiveSpreadPosition;
import com.tradingbot.model.execution.ExecutionMode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ExecutionController.class)
class ExecutionControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private ExecutionManager executionManager;

    @Test
    void testGetExecutionMode() throws Exception {
        when(executionManager.getExecutionMode()).thenReturn(ExecutionMode.PAPER);

        mockMvc.perform(get("/api/v1/execution/mode"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("PAPER"));
    }

    @Test
    void testExecuteTrade() throws Exception {
        BigDecimal atm = new BigDecimal("24000");
        ActiveSpreadPosition mockPos =
                ActiveSpreadPosition.open(
                        "TRD_1",
                        "PIVOT_ST",
                        "NIFTY",
                        "PE",
                        atm,
                        "NIFTY29SEP26P24000",
                        "ORD_SHORT_1",
                        new BigDecimal("100.00"),
                        "NIFTY29SEP26P23700",
                        "ORD_HEDGE_1",
                        new BigDecimal("5.00"),
                        "ORD_SLL_1",
                        new BigDecimal("140.00"),
                        new BigDecimal("144.20"),
                        65,
                        ExecutionMode.PAPER);

        when(executionManager.executeDirectionalOptionSelling(
                        anyString(), anyString(), anyString(), any(), anyInt(), anyBoolean()))
                .thenReturn(mockPos);

        mockMvc.perform(
                        post("/api/v1/execution/trade")
                                .param("optionType", "PE")
                                .param("strikePrice", "24000")
                                .param("buyHedge", "true")
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradeId").value("TRD_1"))
                .andExpect(jsonPath("$.optionType").value("PE"))
                .andExpect(jsonPath("$.slTriggerPrice").value(140.00))
                .andExpect(jsonPath("$.slLimitPrice").value(144.20));
    }

    @Test
    void testCloseTrade() throws Exception {
        BigDecimal atm = new BigDecimal("24000");
        ActiveSpreadPosition mockPos =
                ActiveSpreadPosition.open(
                        "TRD_1",
                        "PIVOT_ST",
                        "NIFTY",
                        "PE",
                        atm,
                        "NIFTY29SEP26P24000",
                        "ORD_SHORT_1",
                        new BigDecimal("100.00"),
                        "NIFTY29SEP26P23700",
                        "ORD_HEDGE_1",
                        new BigDecimal("5.00"),
                        "ORD_SLL_1",
                        new BigDecimal("140.00"),
                        new BigDecimal("144.20"),
                        65,
                        ExecutionMode.PAPER);
        ActiveSpreadPosition closedPos =
                mockPos.close(
                        java.time.Instant.now(),
                        new BigDecimal("80.00"),
                        new BigDecimal("2.50"),
                        new BigDecimal("1137.50"),
                        "SUPERTREND_FLIP");

        when(executionManager.closeSpreadPosition("TRD_1", "SUPERTREND_FLIP"))
                .thenReturn(closedPos);

        mockMvc.perform(post("/api/v1/execution/close/TRD_1").param("reason", "SUPERTREND_FLIP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isClosed").value(true))
                .andExpect(jsonPath("$.realizedPnl").value(1137.50));
    }
}
