package com.tradingbot.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradingbot.backtest.BacktestResult;
import com.tradingbot.backtest.DailyWmaBacktestService;
import com.tradingbot.model.strategy.DailyWmaPosition;
import com.tradingbot.service.DailyWmaStrategyService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DailyWmaStrategyControllerTest {

    private DailyWmaStrategyService strategyService;
    private DailyWmaBacktestService backtestService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        strategyService = mock(DailyWmaStrategyService.class);
        backtestService = mock(DailyWmaBacktestService.class);
        DailyWmaStrategyController controller = new DailyWmaStrategyController(strategyService, backtestService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void testGetStatusWhenNoPosition() throws Exception {
        when(strategyService.getOpenPosition()).thenReturn(null);
        when(strategyService.getTradeHistory()).thenReturn(List.of());

        mockMvc.perform(get("/api/strategy/daily-wma/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("19-Period Daily WMA Positional Option Selling"))
                .andExpect(jsonPath("$.hasActivePosition").value(false));
    }

    @Test
    void testGetStatusWithActivePosition() throws Exception {
        DailyWmaPosition pos =
                new DailyWmaPosition(
                        "WMA_001",
                        "NIFTY",
                        "SELL",
                        "PE",
                        "BULLISH",
                        LocalDate.of(2026, 9, 10),
                        Instant.now(),
                        BigDecimal.valueOf(25000),
                        BigDecimal.valueOf(24800),
                        LocalDate.of(2026, 9, 24),
                        "NIFTY24SEP24200PE",
                        BigDecimal.valueOf(24200),
                        BigDecimal.valueOf(90.0),
                        0.22,
                        65,
                        "NIFTY24SEP23700PE",
                        BigDecimal.valueOf(23700),
                        BigDecimal.valueOf(10.0),
                        65,
                        BigDecimal.valueOf(80.0),
                        BigDecimal.valueOf(180.0),
                        0);

        when(strategyService.getOpenPosition()).thenReturn(pos);
        when(strategyService.getTradeHistory()).thenReturn(List.of());

        mockMvc.perform(get("/api/strategy/daily-wma/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasActivePosition").value(true))
                .andExpect(jsonPath("$.activePosition.tradeId").value("WMA_001"))
                .andExpect(jsonPath("$.activePosition.bias").value("BULLISH"));
    }

    @Test
    void testTriggerCycle() throws Exception {
        doNothing().when(strategyService).evaluateDailyCycle();

        mockMvc.perform(post("/api/strategy/daily-wma/trigger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Daily 19-WMA evaluation cycle triggered successfully"));

        verify(strategyService).evaluateDailyCycle();
    }

    @Test
    void testForceSquareOff() throws Exception {
        doNothing().when(strategyService).executeExpirySquareOff("FORCED_SQUARE_OFF");

        mockMvc.perform(post("/api/strategy/daily-wma/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Square-off triggered for active position"));

        verify(strategyService).executeExpirySquareOff("FORCED_SQUARE_OFF");
    }

    @Test
    void testRunBacktest() throws Exception {
        BacktestResult result =
                new BacktestResult(
                        "19WMA_POSITIONAL_OPTION_SELLING",
                        "NIFTY50",
                        60,
                        5,
                        4,
                        1,
                        80.0,
                        120.0,
                        BigDecimal.valueOf(15000),
                        BigDecimal.valueOf(3000),
                        BigDecimal.valueOf(12000),
                        BigDecimal.valueOf(3000),
                        5.0,
                        List.of());

        when(backtestService.runBacktest(anyInt())).thenReturn(result);

        mockMvc.perform(post("/api/strategy/daily-wma/backtest?days=60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyId").value("19WMA_POSITIONAL_OPTION_SELLING"))
                .andExpect(jsonPath("$.totalTrades").value(5))
                .andExpect(jsonPath("$.winRatePercent").value(80.0));
    }
}
