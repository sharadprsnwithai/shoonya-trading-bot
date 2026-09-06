package com.tradingbot.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.model.OptionContract;
import com.tradingbot.model.OptionStrike;
import com.tradingbot.model.PcrResponse;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OptionChainController.class)
class OptionChainControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private ShoonyaOptionChainService optionChainService;

    @Test
    void testGetNifty50OptionChain() throws Exception {
        BigDecimal atm = new BigDecimal("24000");
        OptionContract call =
                new OptionContract(
                        "NIFTY29SEP26C24000",
                        "74001",
                        "CE",
                        atm,
                        new BigDecimal("150.00"),
                        50000L,
                        10000L,
                        new BigDecimal("149.00"),
                        new BigDecimal("151.00"),
                        new BigDecimal("145.00"));
        OptionContract put =
                new OptionContract(
                        "NIFTY29SEP26P24000",
                        "74002",
                        "PE",
                        atm,
                        new BigDecimal("140.00"),
                        48000L,
                        8000L,
                        new BigDecimal("139.00"),
                        new BigDecimal("141.00"),
                        new BigDecimal("135.00"));

        OptionStrike strike = new OptionStrike(atm, true, call, put);
        OptionChainResponse mockResponse =
                new OptionChainResponse(
                        "NIFTY",
                        new BigDecimal("24010.50"),
                        atm,
                        "NIFTY29SEP26F",
                        1,
                        50000L,
                        48000L,
                        0.96,
                        List.of(strike));

        when(optionChainService.getNifty50OptionChain(any(), anyInt(), anyBoolean()))
                .thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/optionchain/nifty50").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.underlying").value("NIFTY"))
                .andExpect(jsonPath("$.atmStrike").value(24000))
                .andExpect(jsonPath("$.pcr").value(0.96))
                .andExpect(jsonPath("$.strikes[0].isAtm").value(true))
                .andExpect(jsonPath("$.strikes[0].call.ltp").value(150.00))
                .andExpect(jsonPath("$.strikes[0].put.ltp").value(140.00));
    }

    @Test
    void testGetNifty50Pcr() throws Exception {
        PcrResponse mockPcr =
                PcrResponse.calculate(
                        "NIFTY",
                        new BigDecimal("24000.00"),
                        new BigDecimal("24000.00"),
                        8,
                        100000L,
                        120000L,
                        50000L,
                        60000L);

        when(optionChainService.getNifty50Pcr(anyInt())).thenReturn(mockPcr);

        mockMvc.perform(get("/api/v1/optionchain/pcr/nifty50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.underlying").value("NIFTY"))
                .andExpect(jsonPath("$.pcrOi").value(1.2))
                .andExpect(jsonPath("$.sentiment").value("BULLISH"));
    }
}
