package com.tradingbot.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradingbot.backtest.BacktestResult;
import com.tradingbot.backtest.PivotSuperTrendBacktestService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.scheduler.PivotSuperTrendScheduler;
import com.tradingbot.strategy.impl.PivotSuperTrendOptionSellingStrategy;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PivotSuperTrendStrategyController.class)
class PivotSuperTrendStrategyControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private PivotSuperTrendOptionSellingStrategy strategy;

    @MockBean private PivotSuperTrendBacktestService backtestService;

    @MockBean private ShoonyaMarketDataService marketDataService;

    @MockBean private PivotSuperTrendScheduler scheduler;

    @MockBean private com.tradingbot.telegram.TelegramService telegramService;

    @Test
    void testGetStrategyStatus() throws Exception {
        when(strategy.getId()).thenReturn(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID);
        when(strategy.getName()).thenReturn(PivotSuperTrendOptionSellingStrategy.STRATEGY_NAME);
        when(strategy.isEnabled()).thenReturn(true);
        when(strategy.getTimeframe()).thenReturn("5m");
        when(strategy.getPivotPoint()).thenReturn(24000.0);
        when(strategy.getR1Level()).thenReturn(24100.0);
        when(strategy.getS1Level()).thenReturn(23900.0);
        when(strategy.isInPosition()).thenReturn(false);
        when(strategy.isOiFilterEnabled()).thenReturn(true);
        when(strategy.getOiDiffThreshold()).thenReturn(5000000L);
        when(strategy.isOiDirectional()).thenReturn(true);
        when(strategy.getLastOtmCallOi()).thenReturn(6000000L);
        when(strategy.getLastOtmPutOi()).thenReturn(12000000L);
        when(strategy.getLastOiDifference()).thenReturn(6000000L);
        when(strategy.getEntryStartTime()).thenReturn(java.time.LocalTime.of(9, 30));
        when(strategy.isStopLossEnabled()).thenReturn(true);
        when(strategy.getStopLossPercent()).thenReturn(30.0);
        when(strategy.isTargetProfitEnabled()).thenReturn(true);
        when(strategy.getTargetProfitPercent()).thenReturn(50.0);
        when(strategy.isBuyHedge()).thenReturn(true);
        when(strategy.getHedgeDistance()).thenReturn(150);
        when(scheduler.getLots()).thenReturn(5);
        when(scheduler.getLotSize()).thenReturn(65);
        when(scheduler.getLotQuantity()).thenReturn(325);

        mockMvc.perform(get("/api/v1/strategy/pivot-supertrend/status"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.strategyId")
                                .value(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID))
                .andExpect(jsonPath("$.pivotPoint").value(24000.0))
                .andExpect(jsonPath("$.r1Level").value(24100.0))
                .andExpect(jsonPath("$.s1Level").value(23900.0))
                .andExpect(jsonPath("$.lots").value(5))
                .andExpect(jsonPath("$.lotSize").value(65))
                .andExpect(jsonPath("$.lotQuantity").value(325))
                .andExpect(jsonPath("$.buyHedge").value(true))
                .andExpect(jsonPath("$.hedgeDistance").value(150))
                .andExpect(jsonPath("$.oiFilterEnabled").value(true))
                .andExpect(jsonPath("$.oiDiffThreshold").value(5000000))
                .andExpect(jsonPath("$.oiDirectional").value(true))
                .andExpect(jsonPath("$.lastOtmCallOi").value(6000000))
                .andExpect(jsonPath("$.lastOtmPutOi").value(12000000))
                .andExpect(jsonPath("$.lastOiDifference").value(6000000))
                .andExpect(jsonPath("$.entryStartTime").value("09:30"))
                .andExpect(jsonPath("$.stopLossEnabled").value(true))
                .andExpect(jsonPath("$.stopLossPercent").value(30.0))
                .andExpect(jsonPath("$.targetProfitEnabled").value(true))
                .andExpect(jsonPath("$.targetProfitPercent").value(50.0));
    }

    @Test
    void testRunBacktest() throws Exception {
        BacktestResult mockResult =
                new BacktestResult(
                        PivotSuperTrendOptionSellingStrategy.STRATEGY_ID,
                        "NIFTY50",
                        90,
                        45,
                        28,
                        17,
                        62.22,
                        180.0,
                        BigDecimal.valueOf(18200.00),
                        BigDecimal.valueOf(6500.00),
                        BigDecimal.valueOf(11700.00),
                        BigDecimal.valueOf(3250.00),
                        2.80,
                        List.of());

        when(backtestService.runBacktest(
                        anyInt(), anyInt(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(mockResult);
        when(backtestService.runBacktest(anyInt(), anyInt())).thenReturn(mockResult);
        when(backtestService.runBacktest(anyInt())).thenReturn(mockResult);

        mockMvc.perform(post("/api/v1/strategy/pivot-supertrend/backtest?days=90&lots=5"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.strategyId")
                                .value(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID))
                .andExpect(jsonPath("$.totalTrades").value(45))
                .andExpect(jsonPath("$.winningTrades").value(28))
                .andExpect(jsonPath("$.winRatePercent").value(62.22))
                .andExpect(jsonPath("$.netPnL").value(11700.00));
    }

    @Test
    void testConfigureOiFilter() throws Exception {
        when(strategy.getId()).thenReturn(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID);
        when(strategy.isOiFilterEnabled()).thenReturn(true);
        when(strategy.getOiDiffThreshold()).thenReturn(5000000L);
        when(strategy.isOiDirectional()).thenReturn(true);

        mockMvc.perform(
                        post(
                                "/api/v1/strategy/pivot-supertrend/oi-filter?enabled=true&threshold=5000000&directional=true"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.strategyId")
                                .value(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID))
                .andExpect(jsonPath("$.oiFilterEnabled").value(true))
                .andExpect(jsonPath("$.oiDiffThreshold").value(5000000))
                .andExpect(jsonPath("$.oiDirectional").value(true));
    }

    @Test
    void testReset() throws Exception {
        mockMvc.perform(post("/api/v1/strategy/pivot-supertrend/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void testEnable() throws Exception {
        when(strategy.getId()).thenReturn(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID);
        when(strategy.isEnabled()).thenReturn(true);

        mockMvc.perform(post("/api/v1/strategy/pivot-supertrend/enable?enabled=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void testTelegramToggle() throws Exception {
        when(strategy.getId()).thenReturn(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID);
        when(strategy.isTelegramAlertsEnabled()).thenReturn(true);

        mockMvc.perform(post("/api/v1/strategy/pivot-supertrend/telegram?enabled=true"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.strategyId")
                                .value(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID))
                .andExpect(jsonPath("$.telegramAlertsEnabled").value(true));
    }

    @Test
    void testSchedulerToggle() throws Exception {
        when(strategy.getId()).thenReturn(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID);
        when(scheduler.isSchedulerEnabled()).thenReturn(true);

        mockMvc.perform(post("/api/v1/strategy/pivot-supertrend/scheduler?enabled=true"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.strategyId")
                                .value(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID))
                .andExpect(jsonPath("$.schedulerEnabled").value(true));
    }

    @Test
    void testAutoExecuteToggle() throws Exception {
        when(strategy.getId()).thenReturn(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID);
        when(scheduler.isAutoExecute()).thenReturn(true);

        mockMvc.perform(post("/api/v1/strategy/pivot-supertrend/auto-execute?enabled=true"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.strategyId")
                                .value(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID))
                .andExpect(jsonPath("$.autoExecute").value(true));
    }

    @Test
    void testTriggerSquareOff() throws Exception {
        when(strategy.getId()).thenReturn(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID);

        mockMvc.perform(post("/api/v1/strategy/pivot-supertrend/square-off?reason=TEST_CLOSE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void testConfigureRisk() throws Exception {
        when(strategy.getId()).thenReturn(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID);
        when(strategy.getEntryStartTime()).thenReturn(java.time.LocalTime.of(9, 30));
        when(strategy.isStopLossEnabled()).thenReturn(true);
        when(strategy.getStopLossPercent()).thenReturn(30.0);
        when(strategy.isTargetProfitEnabled()).thenReturn(true);
        when(strategy.getTargetProfitPercent()).thenReturn(50.0);

        mockMvc.perform(
                        post(
                                "/api/v1/strategy/pivot-supertrend/risk-config?entryStartTime=09:30:00&stopLossEnabled=true&stopLossPercent=30.0&targetProfitEnabled=true&targetProfitPercent=50.0"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.strategyId")
                                .value(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID))
                .andExpect(jsonPath("$.entryStartTime").value("09:30"))
                .andExpect(jsonPath("$.stopLossEnabled").value(true))
                .andExpect(jsonPath("$.stopLossPercent").value(30.0))
                .andExpect(jsonPath("$.targetProfitEnabled").value(true))
                .andExpect(jsonPath("$.targetProfitPercent").value(50.0));
    }

    @Test
    void testConfigureSpread() throws Exception {
        when(strategy.getId()).thenReturn(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID);
        when(strategy.isBuyHedge()).thenReturn(true);
        when(strategy.getHedgeDistance()).thenReturn(150);
        when(strategy.getLots()).thenReturn(1);
        when(strategy.getLotSize()).thenReturn(65);

        mockMvc.perform(
                        post(
                                "/api/v1/strategy/pivot-supertrend/spread-config?buyHedge=true&hedgeDistance=150&lots=1"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.strategyId")
                                .value(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID))
                .andExpect(jsonPath("$.buyHedge").value(true))
                .andExpect(jsonPath("$.hedgeDistance").value(150))
                .andExpect(jsonPath("$.lots").value(1))
                .andExpect(jsonPath("$.lotQuantity").value(65));
    }

    @Test
    void testSendTestTelegramAlert() throws Exception {
        when(strategy.getId()).thenReturn(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID);
        when(strategy.isTelegramAlertsEnabled()).thenReturn(true);
        when(telegramService.sendTestAlert(any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/strategy/pivot-supertrend/telegram/test"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.strategyId")
                                .value(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID))
                .andExpect(jsonPath("$.telegramAlertsEnabled").value(true))
                .andExpect(jsonPath("$.testAlertSent").value(true));
    }
}
