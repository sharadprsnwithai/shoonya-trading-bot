package com.tradingbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.OptionChainResponse;
import com.tradingbot.model.OptionContract;
import com.tradingbot.model.OptionStrike;
import com.tradingbot.model.strategy.RsiCrossoverPosition;
import com.tradingbot.order.ShoonyaOrderService;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RsiCrossoverStrategyServiceTest {

    private ShoonyaMarketDataService marketDataService;
    private TechnicalAnalysisService taService;
    private TelegramService telegramService;
    private ShoonyaOptionChainService optionChainService;
    private ShoonyaOrderService orderService;
    private ShoonyaConfig config;
    private RsiCrossoverStrategyService strategyService;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @BeforeEach
    void setUp() {
        marketDataService = mock(ShoonyaMarketDataService.class);
        taService = mock(TechnicalAnalysisService.class);
        telegramService = mock(TelegramService.class);
        optionChainService = mock(ShoonyaOptionChainService.class);
        orderService = mock(ShoonyaOrderService.class);
        config = mock(ShoonyaConfig.class);

        strategyService =
                new RsiCrossoverStrategyService(
                        marketDataService,
                        taService,
                        telegramService,
                        optionChainService,
                        orderService,
                        config);
    }

    private Clock createFixedClock(LocalTime time) {
        Instant instant = LocalDate.of(2026, 9, 10).atTime(time).atZone(IST).toInstant();
        return Clock.fixed(instant, IST);
    }

    private List<Candle> generateCandles(int count, double basePrice) {
        List<Candle> list = new ArrayList<>();
        Instant start = LocalDate.of(2026, 9, 10).atTime(9, 15).atZone(IST).toInstant();
        for (int i = 0; i < count; i++) {
            list.add(
                    new Candle(
                            "NIFTY 50",
                            "5",
                            start.plusSeconds(i * 300L),
                            BigDecimal.valueOf(basePrice),
                            BigDecimal.valueOf(basePrice + 10),
                            BigDecimal.valueOf(basePrice - 10),
                            BigDecimal.valueOf(basePrice),
                            1000));
        }
        return list;
    }

    @Test
    void testSkipCycleBeforeStrategyStartTime() {
        strategyService.setClock(createFixedClock(LocalTime.of(9, 30)));
        strategyService.runCycle();

        verify(marketDataService, never()).fetchHistoricalCandles(any(), any(), any(), any(), anyInt());
    }

    @Test
    void testBullishCrossoverTriggersCeBuy() {
        strategyService.setClock(createFixedClock(LocalTime.of(9, 45, 10)));

        List<Candle> candles = generateCandles(200, 22500.0);
        when(marketDataService.fetchHistoricalCandles(eq("NSE"), eq("10576"), eq("NIFTY 50"), eq("5"), eq(5)))
                .thenReturn(candles);

        // 5m series: prev = 48.0, curr = 55.0
        when(taService.calculateRsiSeries(any(double[].class), eq(14)))
                .thenReturn(new double[] {40.0, 48.0, 55.0}) // 5m
                .thenReturn(new double[] {50.0, 52.0, 52.0}); // 15m: prev = 52.0, curr = 52.0 (5m crossed ABOVE 15m)

        OptionContract ce =
                new OptionContract(
                        "NIFTY24OCT22500CE",
                        "20001",
                        "CE",
                        BigDecimal.valueOf(22500),
                        BigDecimal.valueOf(145.0),
                        1000,
                        100,
                        BigDecimal.valueOf(144.5),
                        BigDecimal.valueOf(145.5),
                        BigDecimal.valueOf(140.0));
        OptionStrike strike = new OptionStrike(BigDecimal.valueOf(22500), true, ce, null);
        OptionChainResponse chain =
                new OptionChainResponse("NIFTY", BigDecimal.valueOf(22500), BigDecimal.valueOf(22500), "NIFTY", 1, 1000, 1000, 1.0, List.of(strike));
        when(optionChainService.getNifty50OptionChain(any(), anyInt(), anyBoolean())).thenReturn(chain);

        strategyService.runCycle();

        RsiCrossoverPosition pos = strategyService.getOpenPosition();
        assertThat(pos).isNotNull();
        assertThat(pos.getOptionType()).isEqualTo("CE");
        assertThat(pos.getStrike()).isEqualByComparingTo(BigDecimal.valueOf(22500));
        assertThat(pos.getEntryPrice()).isEqualByComparingTo(BigDecimal.valueOf(145.0));
        assertThat(pos.getQuantity()).isEqualTo(65);
        assertThat(strategyService.isTradeExecutedToday()).isTrue();

        verify(telegramService).sendRsiCrossoverEntryAlert(eq(pos), eq(55.0), eq(52.0), eq(48.0), eq(52.0));
    }

    @Test
    void testBearishCrossoverTriggersPeBuy() {
        strategyService.setClock(createFixedClock(LocalTime.of(10, 15, 10)));

        List<Candle> candles = generateCandles(200, 22500.0);
        when(marketDataService.fetchHistoricalCandles(eq("NSE"), eq("10576"), eq("NIFTY 50"), eq("5"), eq(5)))
                .thenReturn(candles);

        // 5m series: prev = 54.0, curr = 46.0
        // 15m series: prev = 50.0, curr = 50.0 (5m crossed BELOW 15m)
        when(taService.calculateRsiSeries(any(double[].class), eq(14)))
                .thenReturn(new double[] {55.0, 54.0, 46.0}) // 5m
                .thenReturn(new double[] {50.0, 50.0, 50.0}); // 15m

        OptionContract pe =
                new OptionContract(
                        "NIFTY24OCT22500PE",
                        "20002",
                        "PE",
                        BigDecimal.valueOf(22500),
                        BigDecimal.valueOf(160.0),
                        1000,
                        100,
                        BigDecimal.valueOf(159.5),
                        BigDecimal.valueOf(160.5),
                        BigDecimal.valueOf(150.0));
        OptionStrike strike = new OptionStrike(BigDecimal.valueOf(22500), true, null, pe);
        OptionChainResponse chain =
                new OptionChainResponse("NIFTY", BigDecimal.valueOf(22500), BigDecimal.valueOf(22500), "NIFTY", 1, 1000, 1000, 1.0, List.of(strike));
        when(optionChainService.getNifty50OptionChain(any(), anyInt(), anyBoolean())).thenReturn(chain);

        strategyService.runCycle();

        RsiCrossoverPosition pos = strategyService.getOpenPosition();
        assertThat(pos).isNotNull();
        assertThat(pos.getOptionType()).isEqualTo("PE");
        assertThat(pos.getStrike()).isEqualByComparingTo(BigDecimal.valueOf(22500));
        assertThat(pos.getEntryPrice()).isEqualByComparingTo(BigDecimal.valueOf(160.0));
        assertThat(strategyService.isTradeExecutedToday()).isTrue();

        verify(telegramService).sendRsiCrossoverEntryAlert(eq(pos), eq(46.0), eq(50.0), eq(54.0), eq(50.0));
    }

    @Test
    void testStrictOneTradePerDayLimit() {
        strategyService.setClock(createFixedClock(LocalTime.of(10, 0, 10)));
        strategyService.setTradeExecutedToday(true);

        List<Candle> candles = generateCandles(50, 22500.0);
        when(marketDataService.fetchHistoricalCandles(any(), any(), any(), any(), anyInt()))
                .thenReturn(candles);

        when(taService.calculateRsiSeries(any(double[].class), eq(14)))
                .thenReturn(new double[] {40.0, 48.0, 55.0}) // 5m
                .thenReturn(new double[] {50.0, 52.0, 52.0}); // 15m

        strategyService.runCycle();

        assertThat(strategyService.getOpenPosition()).isNull();
        verify(telegramService, never()).sendRsiCrossoverEntryAlert(any(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void testExitOnReverseCrossover() {
        strategyService.setClock(createFixedClock(LocalTime.of(11, 0, 10)));

        OptionContract ce =
                new OptionContract(
                        "NIFTY24OCT22500CE",
                        "20001",
                        "CE",
                        BigDecimal.valueOf(22500),
                        BigDecimal.valueOf(140.0),
                        1000,
                        100,
                        BigDecimal.valueOf(139.5),
                        BigDecimal.valueOf(140.5),
                        BigDecimal.valueOf(130.0));
        OptionStrike strike = new OptionStrike(BigDecimal.valueOf(22500), true, ce, null);
        OptionChainResponse chain =
                new OptionChainResponse("NIFTY", BigDecimal.valueOf(22500), BigDecimal.valueOf(22500), "NIFTY", 1, 1000, 1000, 1.0, List.of(strike));
        when(optionChainService.getNifty50OptionChain(any(), anyInt(), anyBoolean())).thenReturn(chain);

        strategyService.executeOptionBuy("CE", 22500.0, 55.0, 50.0, 48.0, 50.0);

        List<Candle> candles = generateCandles(200, 22500.0);
        when(marketDataService.fetchHistoricalCandles(any(), any(), any(), any(), anyInt()))
                .thenReturn(candles);

        // Reverse signal: 5m RSI (45.0) drops below 15m RSI (50.0)
        when(taService.calculateRsiSeries(any(double[].class), eq(14)))
                .thenReturn(new double[] {55.0, 52.0, 45.0}) // 5m
                .thenReturn(new double[] {50.0, 50.0, 50.0}); // 15m

        OptionContract exitCe =
                new OptionContract(
                        "NIFTY24OCT22500CE",
                        "20001",
                        "CE",
                        BigDecimal.valueOf(22500),
                        BigDecimal.valueOf(175.0),
                        1000,
                        100,
                        BigDecimal.valueOf(174.5),
                        BigDecimal.valueOf(175.5),
                        BigDecimal.valueOf(140.0));
        OptionStrike exitStrike = new OptionStrike(BigDecimal.valueOf(22500), true, exitCe, null);
        OptionChainResponse exitChain =
                new OptionChainResponse("NIFTY", BigDecimal.valueOf(22500), BigDecimal.valueOf(22500), "NIFTY", 1, 1000, 1000, 1.0, List.of(exitStrike));
        when(optionChainService.getNifty50OptionChain(any(), anyInt(), anyBoolean())).thenReturn(exitChain);

        strategyService.runCycle();

        assertThat(strategyService.getOpenPosition()).isNull();
        assertThat(strategyService.getTradeHistory()).hasSize(1);
        RsiCrossoverPosition closed = strategyService.getTradeHistory().get(0);
        assertThat(closed.isClosed()).isTrue();
        assertThat(closed.getExitPrice()).isEqualByComparingTo(BigDecimal.valueOf(175.0));
        assertThat(closed.getExitReason()).isEqualTo("RSI_REVERSAL_BEARISH");

        verify(telegramService).sendRsiCrossoverExitAlert(eq(closed), eq("RSI_REVERSAL_BEARISH"));
    }

    @Test
    void testMandatoryEodSquareOff() {
        strategyService.setClock(createFixedClock(LocalTime.of(15, 5, 10)));

        OptionContract pe =
                new OptionContract(
                        "NIFTY24OCT22500PE",
                        "20002",
                        "PE",
                        BigDecimal.valueOf(22500),
                        BigDecimal.valueOf(150.0),
                        1000,
                        100,
                        BigDecimal.valueOf(149.5),
                        BigDecimal.valueOf(150.5),
                        BigDecimal.valueOf(140.0));
        OptionStrike strike = new OptionStrike(BigDecimal.valueOf(22500), true, null, pe);
        OptionChainResponse chain =
                new OptionChainResponse("NIFTY", BigDecimal.valueOf(22500), BigDecimal.valueOf(22500), "NIFTY", 1, 1000, 1000, 1.0, List.of(strike));
        when(optionChainService.getNifty50OptionChain(any(), anyInt(), anyBoolean())).thenReturn(chain);

        strategyService.executeOptionBuy("PE", 22500.0, 45.0, 50.0, 52.0, 50.0);

        assertThat(strategyService.getOpenPosition()).isNotNull();

        strategyService.executeSquareOff("MANDATORY_EOD_SQUARE_OFF");

        assertThat(strategyService.getOpenPosition()).isNull();
        assertThat(strategyService.getTradeHistory()).hasSize(1);
        RsiCrossoverPosition closed = strategyService.getTradeHistory().get(0);
        assertThat(closed.isClosed()).isTrue();
        assertThat(closed.getExitReason()).isEqualTo("MANDATORY_EOD_SQUARE_OFF");

        verify(telegramService).sendRsiCrossoverExitAlert(eq(closed), eq("MANDATORY_EOD_SQUARE_OFF"));
    }

    @Test
    void testDailyReset() {
        OptionContract ce =
                new OptionContract(
                        "NIFTY24OCT22500CE",
                        "20001",
                        "CE",
                        BigDecimal.valueOf(22500),
                        BigDecimal.valueOf(140.0),
                        1000,
                        100,
                        BigDecimal.valueOf(139.5),
                        BigDecimal.valueOf(140.5),
                        BigDecimal.valueOf(130.0));
        OptionStrike strike = new OptionStrike(BigDecimal.valueOf(22500), true, ce, null);
        OptionChainResponse chain =
                new OptionChainResponse("NIFTY", BigDecimal.valueOf(22500), BigDecimal.valueOf(22500), "NIFTY", 1, 1000, 1000, 1.0, List.of(strike));
        when(optionChainService.getNifty50OptionChain(any(), anyInt(), anyBoolean())).thenReturn(chain);

        strategyService.executeOptionBuy("CE", 22500.0, 55.0, 50.0, 48.0, 50.0);
        strategyService.executeSquareOff("EOD");

        assertThat(strategyService.isTradeExecutedToday()).isTrue();
        assertThat(strategyService.getTradeHistory()).hasSize(1);

        strategyService.resetDaily();

        assertThat(strategyService.isTradeExecutedToday()).isFalse();
        assertThat(strategyService.getOpenPosition()).isNull();
        assertThat(strategyService.getTradeHistory()).isEmpty();
    }
}
