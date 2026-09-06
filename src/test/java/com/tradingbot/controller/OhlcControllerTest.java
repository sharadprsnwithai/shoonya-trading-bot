package com.tradingbot.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OhlcController.class)
class OhlcControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private ShoonyaMarketDataService marketDataService;

    private List<Candle> createSampleCandles(String symbol, String timeframe) {
        return List.of(
                new Candle(
                        symbol,
                        timeframe,
                        Instant.parse("2026-09-04T09:15:00Z"),
                        new BigDecimal("24000.00"),
                        new BigDecimal("24050.00"),
                        new BigDecimal("23980.00"),
                        new BigDecimal("24025.00"),
                        50000L),
                new Candle(
                        symbol,
                        timeframe,
                        Instant.parse("2026-09-04T09:20:00Z"),
                        new BigDecimal("24025.00"),
                        new BigDecimal("24080.00"),
                        new BigDecimal("24010.00"),
                        new BigDecimal("24075.00"),
                        65000L));
    }

    @Test
    void testGetNifty50Default() throws Exception {
        when(marketDataService.fetchHistoricalCandles(
                        anyString(), anyString(), anyString(), eq("5"), anyInt()))
                .thenReturn(createSampleCandles("NIFTY50", "5"));

        mockMvc.perform(get("/api/v1/ohlc/nifty50").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("NIFTY50"))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.data[0].open").value(24000.00))
                .andExpect(jsonPath("$.data[0].close").value(24025.00));
    }

    @Test
    void testGetNifty50Timeframe1m() throws Exception {
        when(marketDataService.fetchHistoricalCandles(
                        anyString(), anyString(), anyString(), eq("1"), anyInt()))
                .thenReturn(createSampleCandles("NIFTY50", "1"));

        mockMvc.perform(get("/api/v1/ohlc/nifty50/1m"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interval").value("1m"))
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void testGetNifty50Timeframe15m() throws Exception {
        when(marketDataService.fetchHistoricalCandles(
                        anyString(), anyString(), anyString(), eq("15"), anyInt()))
                .thenReturn(createSampleCandles("NIFTY50", "15"));

        mockMvc.perform(get("/api/v1/ohlc/nifty50/15m"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interval").value("15m"))
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void testGetNifty50Timeframe1h() throws Exception {
        when(marketDataService.fetchHistoricalCandles(
                        anyString(), anyString(), anyString(), eq("60"), anyInt()))
                .thenReturn(createSampleCandles("NIFTY50", "60"));

        mockMvc.perform(get("/api/v1/ohlc/nifty50/1h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interval").value("1h"))
                .andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void testGetStatus() throws Exception {
        mockMvc.perform(get("/api/v1/ohlc/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
