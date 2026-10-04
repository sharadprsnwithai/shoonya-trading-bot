package com.tradingbot.strategy.bollingerha.scheduler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.strategy.bollingerha.config.BollingerHaProperties;
import com.tradingbot.strategy.bollingerha.feeder.ShoonyaHybridDataFeeder;
import com.tradingbot.strategy.bollingerha.model.BollingerHaDailyState;
import com.tradingbot.strategy.bollingerha.model.SelectedStrikes;
import com.tradingbot.strategy.bollingerha.service.BollingerHaIntradayEngine;
import com.tradingbot.strategy.bollingerha.service.BollingerHaStrikeSelector;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BollingerHaSchedulerTest {

    private BollingerHaProperties properties;
    private BollingerHaStrikeSelector strikeSelector;
    private ShoonyaHybridDataFeeder dataFeeder;
    private BollingerHaIntradayEngine engine;
    private ShoonyaMarketDataService marketDataService;
    private TelegramService telegramService;
    private BollingerHaScheduler scheduler;

    private boolean tradingDay = true;

    @BeforeEach
    void setUp() {
        properties = new BollingerHaProperties();
        strikeSelector = mock(BollingerHaStrikeSelector.class);
        dataFeeder = mock(ShoonyaHybridDataFeeder.class);
        engine = mock(BollingerHaIntradayEngine.class);
        when(engine.getDailyState()).thenReturn(new BollingerHaDailyState(LocalDate.now()));
        marketDataService = mock(ShoonyaMarketDataService.class);
        telegramService = mock(TelegramService.class);
        scheduler =
                new BollingerHaScheduler(
                        properties,
                        strikeSelector,
                        dataFeeder,
                        engine,
                        marketDataService,
                        telegramService) {
                    @Override
                    boolean isTradingDay(LocalDate date) {
                        return tradingDay;
                    }
                };
    }

    @Test
    void testPreMarketFallsBackToQuoteWhenCandlesAreEmpty() throws Exception {
        when(marketDataService.fetchHourlyCandles("NIFTY50", 1)).thenReturn(List.of());
        when(marketDataService.fetchQuote(eq("NSE"), anyString()))
                .thenReturn(new ObjectMapper().readTree("{\"lp\":\"25123.45\"}"));
        when(strikeSelector.selectWeeklyAtmStrikes(any(BigDecimal.class)))
                .thenReturn(sampleStrikes());

        scheduler.runPreMarketStrikeSelection();

        verify(strikeSelector).selectWeeklyAtmStrikes(any(BigDecimal.class));
        verify(engine).initStrike(eq("CE"), eq("111"), anyString(), any(BigDecimal.class));
        verify(engine).initStrike(eq("PE"), eq("222"), anyString(), any(BigDecimal.class));
        assertNotNull(scheduler.getCurrentStrikes());
    }

    @Test
    void testPreMarketFailsLoudlyInsteadOfGuessingSpot() throws Exception {
        when(marketDataService.fetchHourlyCandles("NIFTY50", 1)).thenReturn(List.of());
        when(marketDataService.fetchQuote(anyString(), anyString())).thenReturn(null);

        scheduler.runPreMarketStrikeSelection();

        verify(strikeSelector, never()).selectWeeklyAtmStrikes(any(BigDecimal.class));
        verify(telegramService).sendAlert(contains("Pre-market strike selection failed"));
        assertNull(scheduler.getCurrentStrikes());
    }

    @Test
    void testPreMarketSkippedOnNonTradingDay() {
        tradingDay = false;

        scheduler.runPreMarketStrikeSelection();

        verify(marketDataService, never()).fetchHourlyCandles(anyString(), anyInt());
        verify(telegramService, never()).sendAlert(anyString());
        assertNull(scheduler.getCurrentStrikes());
    }

    @Test
    void testMarketOpenAlertsWhenStrikesWereNeverSelected() {
        scheduler.runMarketOpenStreaming();

        verify(dataFeeder, never()).initialize(any(), any(), any());
        verify(telegramService, atLeastOnce()).sendAlert(contains("feeder was NOT armed"));
    }

    @Test
    void testMarketOpenArmsFeederWhenStrikesArePresent() {
        SelectedStrikes strikes = sampleStrikes();
        scheduler.setCurrentStrikes(strikes);

        scheduler.runMarketOpenStreaming();

        verify(dataFeeder).initialize(eq(strikes), any(), any());
    }

    @Test
    void testAutoSquareOffPollerDelegatesToEngine() {
        scheduler.pollAutoSquareOff();

        verify(engine).enforceAutoSquareOff(any(Instant.class));
    }

    @Test
    void testAutoSquareOffPollerSkippedWhenStrategyDisabled() {
        properties.setEnabled(false);

        scheduler.pollAutoSquareOff();

        verify(engine, never()).enforceAutoSquareOff(any(Instant.class));
    }

    @Test
    void testAutoSquareOffPollerSkippedOnNonTradingDay() {
        tradingDay = false;

        scheduler.pollAutoSquareOff();

        verify(engine, never()).enforceAutoSquareOff(any(Instant.class));
    }

    @Test
    void testDailySummaryTearsDownTheFeeder() {
        scheduler.runDailySummary();

        verify(telegramService).sendAlert(contains("DAILY SUMMARY"));
        verify(dataFeeder).disconnect();
    }

    @Test
    void testDailySummarySkippedOnNonTradingDay() {
        tradingDay = false;

        scheduler.runDailySummary();

        verify(telegramService, never()).sendAlert(anyString());
        verify(dataFeeder, never()).disconnect();
    }

    @Test
    void testTradingDayGuardDelegatesToExchangeCalendar() {
        LocalDate sunday = LocalDate.of(2026, 10, 4);
        LocalDate saturday = LocalDate.of(2026, 10, 3);
        LocalDate monday = LocalDate.of(2026, 10, 5);

        BollingerHaScheduler real = schedulerWithRealCalendar();
        assertFalse(real.isTradingDay(saturday));
        assertFalse(real.isTradingDay(sunday));
        assertTrue(real.isTradingDay(monday));
    }

    private BollingerHaScheduler schedulerWithRealCalendar() {
        return new BollingerHaScheduler(
                properties, strikeSelector, dataFeeder, engine, marketDataService, telegramService);
    }

    private static SelectedStrikes sampleStrikes() {
        return new SelectedStrikes(
                new BigDecimal("25123.45"),
                new BigDecimal("25100"),
                "111",
                "NIFTY26OCT25100CE",
                "222",
                "NIFTY26OCT25100PE");
    }
}
