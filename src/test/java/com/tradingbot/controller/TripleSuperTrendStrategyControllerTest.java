package com.tradingbot.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tradingbot.backtest.BacktestResult;
import com.tradingbot.backtest.TripleSuperTrendBacktestService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.service.BasketHealthScoringService;
import com.tradingbot.strategy.impl.TripleSuperTrendRsiOptionBuyingStrategy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class TripleSuperTrendStrategyControllerTest {

    private TripleSuperTrendRsiOptionBuyingStrategy strategy;
    private TripleSuperTrendBacktestService backtestService;
    private ShoonyaMarketDataService marketDataService;
    private BasketHealthScoringService basketHealthScoringService;
    private TripleSuperTrendStrategyController controller;

    @BeforeEach
    void setUp() {
        strategy = mock(TripleSuperTrendRsiOptionBuyingStrategy.class);
        backtestService = mock(TripleSuperTrendBacktestService.class);
        marketDataService = mock(ShoonyaMarketDataService.class);
        basketHealthScoringService = mock(BasketHealthScoringService.class);
        controller =
                new TripleSuperTrendStrategyController(
                        strategy, backtestService, marketDataService, basketHealthScoringService);
    }

    @Test
    void testGetStatusReturnsMetadata() {
        when(strategy.getId()).thenReturn("TRIPLE_SUPERTREND_RSI_OPTION_BUYING");
        when(strategy.getName()).thenReturn("Triple SuperTrend + RSI");
        when(strategy.isEnabled()).thenReturn(true);
        when(strategy.getTimeframe()).thenReturn("60m");
        when(strategy.getActivePositions()).thenReturn(Map.of());

        ResponseEntity<Map<String, Object>> response = controller.getStatus();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody())
                .containsEntry("strategyId", "TRIPLE_SUPERTREND_RSI_OPTION_BUYING");
        assertThat(response.getBody()).containsEntry("timeframe", "60m");
    }

    @Test
    void testGetSymbolsReturnsAll30SubscribedSymbols() {
        ResponseEntity<Map<String, Object>> response = controller.getSubscribedSymbols();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsEntry("totalSymbols", 30);
    }

    @Test
    void testToggleEnabled() {
        ResponseEntity<Map<String, Object>> response = controller.toggleEnabled(false);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void testRunBacktestEndpointWithFilters() {
        when(marketDataService.fetchHourlyCandles("VEDL", 30)).thenReturn(List.of());
        when(backtestService.evaluateCandles(
                        org.mockito.ArgumentMatchers.eq("VEDL"),
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(
                        new BacktestResult(
                                "ID",
                                "VEDL",
                                30,
                                0,
                                0,
                                0,
                                0.0,
                                0.0,
                                java.math.BigDecimal.ZERO,
                                java.math.BigDecimal.ZERO,
                                java.math.BigDecimal.ZERO,
                                java.math.BigDecimal.ZERO,
                                0.0,
                                List.of()));

        ResponseEntity<BacktestResult> response =
                controller.runBacktest(
                        "VEDL", 1, true, false, true, 22.0, 68.0, 32.0, 3, false, true, 0.60, true,
                        0.60, 2.50, true, 50, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void testGetBasketHealthEndpoint() {
        when(basketHealthScoringService.rankAllSymbols(30)).thenReturn(List.of());
        ResponseEntity<Map<String, Object>> response = controller.getBasketHealth(30);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).containsEntry("totalEvaluated", 0);
    }
}
