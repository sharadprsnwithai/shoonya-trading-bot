package com.tradingbot.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tradingbot.execution.ExecutionManager;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.execution.ActiveSpreadPosition;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import com.tradingbot.strategy.impl.PivotSuperTrendOptionSellingStrategy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PivotSuperTrendSchedulerTest {

    private PivotSuperTrendOptionSellingStrategy strategy;
    private ShoonyaMarketDataService marketDataService;
    private ExecutionManager executionManager;
    private PivotSuperTrendScheduler scheduler;

    @BeforeEach
    void setUp() {
        strategy = mock(PivotSuperTrendOptionSellingStrategy.class);
        marketDataService = mock(ShoonyaMarketDataService.class);
        executionManager = mock(ExecutionManager.class);
        scheduler = new PivotSuperTrendScheduler(strategy, marketDataService, executionManager);

        when(strategy.getId()).thenReturn(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID);
    }

    private Candle createCandle(String timeStr, double close) {
        return new Candle(
                "NIFTY50",
                "5",
                Instant.parse(timeStr),
                BigDecimal.valueOf(close),
                BigDecimal.valueOf(close + 10),
                BigDecimal.valueOf(close - 10),
                BigDecimal.valueOf(close),
                10000);
    }

    @Test
    void testDailyResetResetsStrategyAndClearsActiveTradeId() {
        scheduler.setActiveTradeId("TRD_123");
        scheduler.scheduleDailyReset();

        verify(strategy).onResetDaily();
        assertThat(scheduler.getActiveTradeId()).isNull();
    }

    @Test
    void testEvaluateCandleInAdvisoryModeDoesNotExecuteOrders() {
        scheduler.setAutoExecute(false);

        Candle c1 = createCandle("2026-09-04T04:00:00Z", 24000);
        Candle c2 = createCandle("2026-09-04T04:05:00Z", 24080);
        when(marketDataService.fetchHistoricalCandles(
                        anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(c1, c2));

        TradeSignal sellSignal =
                TradeSignal.of(
                        PivotSuperTrendOptionSellingStrategy.STRATEGY_ID,
                        "NIFTY_SHORT_PE_24100",
                        SignalAction.SELL,
                        BigDecimal.valueOf(24080),
                        BigDecimal.valueOf(24020),
                        null,
                        1,
                        "Bullish Confluence",
                        Map.of("atmStrike", BigDecimal.valueOf(24100)));
        when(strategy.onCandle(any(Candle.class), anyList())).thenReturn(sellSignal);

        TradeSignal result = scheduler.evaluateCurrentCandle();

        assertThat(result.action()).isEqualTo(SignalAction.SELL);
        verify(executionManager, never())
                .executeDirectionalOptionSelling(
                        anyString(), anyString(), anyString(), any(), anyInt(), anyBoolean());
        assertThat(scheduler.getActiveTradeId()).isNull();
    }

    @Test
    void testDefaultsConfiguredToOneLotAnd65Quantity() {
        assertThat(scheduler.getLots()).isEqualTo(1);
        assertThat(scheduler.getLotSize()).isEqualTo(65);
        assertThat(scheduler.getLotQuantity()).isEqualTo(65);
    }

    @Test
    void testEvaluateCandleInAutoExecuteModePlacesOrderAndSetsActiveTrade() {
        scheduler.setAutoExecute(true);
        scheduler.setLots(5);
        scheduler.setLotSize(65);
        scheduler.setLotQuantity(325);
        scheduler.setBuyHedge(true);

        Candle c1 = createCandle("2026-09-04T04:00:00Z", 24000);
        Candle c2 = createCandle("2026-09-04T04:05:00Z", 24080);
        when(marketDataService.fetchHistoricalCandles(
                        anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(c1, c2));

        TradeSignal sellSignal =
                TradeSignal.of(
                        PivotSuperTrendOptionSellingStrategy.STRATEGY_ID,
                        "NIFTY_SHORT_PE_24100",
                        SignalAction.SELL,
                        BigDecimal.valueOf(24080),
                        BigDecimal.valueOf(24020),
                        null,
                        5,
                        "Bullish Confluence",
                        Map.of("atmStrike", BigDecimal.valueOf(24100), "lots", 5, "quantity", 325));
        when(strategy.onCandle(any(Candle.class), anyList())).thenReturn(sellSignal);
        when(strategy.getAtmStrike()).thenReturn(BigDecimal.valueOf(24100));

        ActiveSpreadPosition mockPos =
                ActiveSpreadPosition.open(
                        "TRD_99",
                        PivotSuperTrendOptionSellingStrategy.STRATEGY_ID,
                        "NIFTY",
                        "PE",
                        BigDecimal.valueOf(24100),
                        "NIFTY29SEP26P24100",
                        "ORD_SHORT",
                        BigDecimal.valueOf(150.0),
                        "NIFTY29SEP26P23800",
                        "ORD_HEDGE",
                        BigDecimal.valueOf(5.0),
                        "ORD_SL",
                        BigDecimal.valueOf(210.0),
                        BigDecimal.valueOf(216.3),
                        325,
                        ExecutionMode.PAPER);
        when(executionManager.executeDirectionalOptionSelling(
                        eq(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID),
                        eq("NIFTY"),
                        eq("PE"),
                        eq(BigDecimal.valueOf(24100)),
                        eq(325),
                        eq(true)))
                .thenReturn(mockPos);

        TradeSignal result = scheduler.evaluateCurrentCandle();

        assertThat(result.action()).isEqualTo(SignalAction.SELL);
        verify(executionManager)
                .executeDirectionalOptionSelling(
                        eq(PivotSuperTrendOptionSellingStrategy.STRATEGY_ID),
                        eq("NIFTY"),
                        eq("PE"),
                        eq(BigDecimal.valueOf(24100)),
                        eq(325),
                        eq(true));
        assertThat(scheduler.getActiveTradeId()).isEqualTo("TRD_99");
    }

    @Test
    void testMandatory1514SquareOffClosesPosition() {
        scheduler.setAutoExecute(true);
        scheduler.setActiveTradeId("TRD_99");

        when(strategy.isInPosition()).thenReturn(true);
        Candle c1 = createCandle("2026-09-04T09:44:00Z", 24050);
        when(marketDataService.fetchHistoricalCandles(
                        anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(List.of(c1));

        scheduler.scheduleMandatorySquareOff();

        verify(strategy).forceSquareOff(eq("Mandatory 15:14 IST Auto Square-Off"));
        verify(executionManager)
                .closeSpreadPosition(eq("TRD_99"), eq("Mandatory 15:14 IST Auto Square-Off"));
        assertThat(scheduler.getActiveTradeId()).isNull();
    }
}
