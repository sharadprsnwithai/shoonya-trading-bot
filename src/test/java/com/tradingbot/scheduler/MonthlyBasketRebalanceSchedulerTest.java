package com.tradingbot.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tradingbot.service.BasketHealthScoringService;
import com.tradingbot.util.StockFnoRegistry;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MonthlyBasketRebalanceSchedulerTest {

    private BasketHealthScoringService scoringService;
    private MonthlyBasketRebalanceScheduler scheduler;
    private List<String> originalCurated;

    @BeforeEach
    void setUp() {
        originalCurated = StockFnoRegistry.getCuratedSymbols();
        scoringService = mock(BasketHealthScoringService.class);
        scheduler = new MonthlyBasketRebalanceScheduler(scoringService);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        if (originalCurated != null) {
            StockFnoRegistry.setCuratedSymbols(originalCurated);
        }
    }

    @Test
    void testRunMonthlyRebalanceUpdatesCuratedBasket() {
        List<BasketHealthScoringService.StockHealthScore> dummyRanked =
                List.of(
                        new BasketHealthScoringService.StockHealthScore(
                                1,
                                "IDEA",
                                "🟢 BULLISH (Buy CE)",
                                90.0,
                                6,
                                50.0,
                                2.98,
                                BigDecimal.valueOf(20000),
                                32.9,
                                1.0,
                                0.0,
                                true),
                        new BasketHealthScoringService.StockHealthScore(
                                2,
                                "BSE",
                                "🟢 BULLISH (Buy CE)",
                                89.2,
                                4,
                                50.0,
                                2.30,
                                BigDecimal.valueOf(8500),
                                30.5,
                                0.92,
                                0.0,
                                true),
                        new BasketHealthScoringService.StockHealthScore(
                                3,
                                "VEDL",
                                "🟢 BULLISH (Buy CE)",
                                88.7,
                                4,
                                75.0,
                                5.35,
                                BigDecimal.valueOf(25200),
                                31.0,
                                0.87,
                                0.0,
                                true));

        when(scoringService.rankAllSymbols(anyInt())).thenReturn(dummyRanked);

        List<String> result = scheduler.runMonthlyRebalance();

        assertThat(result).contains("IDEA", "BSE", "VEDL");
        assertThat(StockFnoRegistry.getCuratedSymbols()).contains("IDEA", "BSE", "VEDL");
        verify(scoringService).sendRebalanceTelegramReport(dummyRanked);
    }
}
