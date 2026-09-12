package com.tradingbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.strategy.OhlvDirection;
import com.tradingbot.model.strategy.OhlvPaperPosition;
import com.tradingbot.model.strategy.OhlvSetup;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OhlvVwapStrategyServiceTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ShoonyaMarketDataService marketDataService;
    private TechnicalAnalysisService taService;
    private TelegramService telegramService;
    private OhlvVwapStrategyService service;

    private LocalDate today;

    @BeforeEach
    void setUp() {
        marketDataService = mock(ShoonyaMarketDataService.class);
        taService = new TechnicalAnalysisService();
        telegramService = mock(TelegramService.class);
        service = new OhlvVwapStrategyService(marketDataService, taService, telegramService);
        service.setTelegramAlerts(false);
        service.setClock(Clock.fixed(Instant.parse("2025-07-24T04:15:00Z"), IST)); // Thur 09:45 IST
        today = LocalDate.now(service.getClock());
    }

    // ------------------------------------------------------------------
    // Opening-profile classification
    // ------------------------------------------------------------------

    private Candle candle(LocalTime t, double o, double h, double l, double c, long v) {
        ZonedDateTime zdt = ZonedDateTime.of(today, t, IST);
        return new Candle(
                "RELIANCE",
                "15",
                zdt.toInstant(),
                BigDecimal.valueOf(o),
                BigDecimal.valueOf(h),
                BigDecimal.valueOf(l),
                BigDecimal.valueOf(c),
                v);
    }

    @Test
    void classify_openEqualsHigh_returnsBearish() {
        Candle c = candle(LocalTime.of(9, 30), 2500.00, 2500.05, 2495.00, 2498.00, 100000L);
        assertThat(service.classifyOpeningProfile(c)).isEqualTo(OhlvDirection.BEARISH);
    }

    @Test
    void classify_openEqualsLow_returnsBullish() {
        Candle c = candle(LocalTime.of(9, 30), 2495.00, 2505.00, 2495.02, 2500.00, 100000L);
        assertThat(service.classifyOpeningProfile(c)).isEqualTo(OhlvDirection.BULLISH);
    }

    @Test
    void classify_withinTolerance_returnsDirection() {
        // open is 0.05% below high -> within 0.1% tolerance -> BEARISH
        Candle c = candle(LocalTime.of(9, 30), 2500.00, 2501.25, 2495.00, 2498.00, 100000L);
        assertThat(service.classifyOpeningProfile(c)).isEqualTo(OhlvDirection.BEARISH);
    }

    @Test
    void classify_beyondTolerance_returnsNull() {
        // open is 0.5% below high -> outside 0.1% tolerance
        Candle c = candle(LocalTime.of(9, 30), 2500.00, 2512.50, 2490.00, 2500.00, 100000L);
        assertThat(service.classifyOpeningProfile(c)).isNull();
    }

    // ------------------------------------------------------------------
    // 15-minute aggregation
    // ------------------------------------------------------------------

    @Test
    void aggregateFirst15_minutes_buildsSingleCandle() {
        List<Candle> oneMin = new ArrayList<>();
        // 3 opening bars for brevity (real code requires >= 2)
        oneMin.add(candle(LocalTime.of(9, 15), 2500.0, 2502.0, 2499.0, 2501.0, 1000));
        oneMin.add(candle(LocalTime.of(9, 16), 2501.0, 2504.0, 2500.0, 2503.0, 2000));
        oneMin.add(candle(LocalTime.of(9, 17), 2503.0, 2503.5, 2498.0, 2499.0, 1500));
        // A bar outside the opening window must be ignored
        oneMin.add(candle(LocalTime.of(9, 40), 2440.0, 2450.0, 2430.0, 2445.0, 99999));

        Candle agg = service.aggregateFirst15MinuteCandle(oneMin, today);

        assertThat(agg).isNotNull();
        assertThat(agg.open()).isEqualByComparingTo("2500.0");
        assertThat(agg.high()).isEqualByComparingTo("2504.0");
        assertThat(agg.low()).isEqualByComparingTo("2498.0");
        assertThat(agg.close()).isEqualByComparingTo("2499.0");
        assertThat(agg.volume()).isEqualTo(4500);
    }

    @Test
    void aggregateFirst15_noOpeningBars_returnsNull() {
        List<Candle> oneMin = new ArrayList<>();
        oneMin.add(candle(LocalTime.of(9, 40), 2440.0, 2450.0, 2430.0, 2445.0, 99999));
        assertThat(service.aggregateFirst15MinuteCandle(oneMin, today)).isNull();
    }

    // ------------------------------------------------------------------
    // Volume SMA
    // ------------------------------------------------------------------

    @Test
    void prevDaySma20_averagesLast20Volumes() {
        LocalDate prev = today.minusDays(1);
        List<Candle> oneMin = new ArrayList<>();
        // 25 bars: 20 x 1000 + 5 x 2000, ordered with the 2000s LAST → SMA20 = avg of last 20
        // (last 20 = 15 x 1000 + 5 x 2000)
        for (int i = 0; i < 15; i++) {
            oneMin.add(minBar(prev, LocalTime.of(15, 0).plusMinutes(i), 1000));
        }
        for (int i = 0; i < 10; i++) {
            oneMin.add(minBar(prev, LocalTime.of(15, 15).plusMinutes(i), 2000));
        }

        double sma = service.prevDayLast20VolumeSma(oneMin, today);

        // last 20 = 5x1000 + 15x2000? No: 25 bars, take last 20 → skip first 5
        // first 15 are 1000, last 10 are 2000 → last 20 = 10x1000 + 10x2000 = 1500
        assertThat(sma).isEqualTo(1500.0);
    }

    private Candle minBar(LocalDate date, LocalTime time, long vol) {
        ZonedDateTime zdt = ZonedDateTime.of(date, time, IST);
        return new Candle(
                "RELIANCE",
                "1",
                zdt.toInstant(),
                BigDecimal.valueOf(10),
                BigDecimal.valueOf(11),
                BigDecimal.valueOf(9),
                BigDecimal.valueOf(10.5),
                vol);
    }

    // ------------------------------------------------------------------
    // Monitoring helpers
    // ------------------------------------------------------------------

    private OhlvSetup setup(OhlvDirection direction, double markedHigh, double markedLow) {
        return new OhlvSetup(
                "RELIANCE",
                direction,
                BigDecimal.valueOf(markedHigh),
                BigDecimal.valueOf(markedLow),
                100000L,
                30000.0);
    }

    @Test
    void markBroken_bearishHighBroken_true() {
        OhlvSetup s = setup(OhlvDirection.BEARISH, 2504.0, 2490.0);
        List<Candle> bars = List.of(candle(LocalTime.of(9, 40), 2500, 2505, 2495, 2500, 1000));
        assertThat(service.markBroken(s, bars)).isTrue();
    }

    @Test
    void markBroken_bearishHighNotBroken_false() {
        OhlvSetup s = setup(OhlvDirection.BEARISH, 2504.0, 2490.0);
        List<Candle> bars = List.of(candle(LocalTime.of(9, 40), 2500, 2503.5, 2495, 2500, 1000));
        assertThat(service.markBroken(s, bars)).isFalse();
    }

    @Test
    void markBroken_bullishLowBroken_true() {
        OhlvSetup s = setup(OhlvDirection.BULLISH, 2504.0, 2490.0);
        List<Candle> bars = List.of(candle(LocalTime.of(9, 40), 2495, 2500, 2488, 2497, 1000));
        assertThat(service.markBroken(s, bars)).isTrue();
    }

    @Test
    void markBroken_ignoresBarsInsideOpening15Min() {
        OhlvSetup s = setup(OhlvDirection.BEARISH, 2504.0, 2490.0);
        // Bar inside 09:15-09:30 breaking high must be ignored
        List<Candle> bars = List.of(candle(LocalTime.of(9, 25), 2505, 2510, 2500, 2508, 1000));
        assertThat(service.markBroken(s, bars)).isFalse();
    }

    @Test
    void entryStraddle_bearishCrossDown_true() {
        // high >= vwap, low <= vwap, close below vwap → BEARISH entry
        Candle last = candle(LocalTime.of(10, 5), 2501.0, 2508.0, 2497.0, 2498.0, 1000);
        assertThat(service.isEntryStraddle(last, 2500.0, OhlvDirection.BEARISH)).isTrue();
    }

    @Test
    void entryStraddle_bearishCloseAboveVwap_false() {
        Candle last = candle(LocalTime.of(10, 5), 2501.0, 2508.0, 2497.0, 2502.0, 1000);
        assertThat(service.isEntryStraddle(last, 2500.0, OhlvDirection.BEARISH)).isFalse();
    }

    @Test
    void entryStraddle_bullishCrossUp_true() {
        Candle last = candle(LocalTime.of(10, 5), 2499.0, 2503.0, 2494.0, 2501.0, 1000);
        assertThat(service.isEntryStraddle(last, 2500.0, OhlvDirection.BULLISH)).isTrue();
    }

    @Test
    void entryStraddle_notStraddling_false() {
        Candle last = candle(LocalTime.of(10, 5), 2510.0, 2515.0, 2505.0, 2512.0, 1000);
        assertThat(service.isEntryStraddle(last, 2500.0, OhlvDirection.BEARISH)).isFalse();
    }

    @Test
    void oppositeSideClose_bearishClosesAboveVwap_true() {
        Candle last = candle(LocalTime.of(10, 5), 2500.0, 2505.0, 2498.0, 2502.0, 1000);
        assertThat(service.isOppositeSideClose(last, 2500.0, OhlvDirection.BEARISH)).isTrue();
    }

    @Test
    void oppositeSideClose_bullishClosesBelowVwap_true() {
        Candle last = candle(LocalTime.of(10, 5), 2500.0, 2503.0, 2496.0, 2498.0, 1000);
        assertThat(service.isOppositeSideClose(last, 2500.0, OhlvDirection.BULLISH)).isTrue();
    }

    // ------------------------------------------------------------------
    // Expiry
    // ------------------------------------------------------------------

    @Test
    void monthlyExpiry_formatsYYMON() {
        // Fixed clock = 2025-07-24 (after Jul 31 last Thu? 31 Jul 2025 is Thursday)
        // 31 Jul 2025 is a Thursday → expiry "25JUL"
        service.setClock(Clock.fixed(Instant.parse("2025-07-24T04:15:00Z"), IST));
        assertThat(service.resolveMonthlyExpiry()).isEqualTo("25JUL");
    }

    @Test
    void monthlyExpiry_afterExpiry_rollsToNextMonth() {
        // 2025-08-04: after 31 Jul expiry → "25AUG"
        service.setClock(Clock.fixed(Instant.parse("2025-08-04T04:15:00Z"), IST));
        assertThat(service.resolveMonthlyExpiry()).isEqualTo("25AUG");
    }

    // ------------------------------------------------------------------
    // Morning scan end-to-end
    // ------------------------------------------------------------------

    @Test
    void morningScan_admitsQualifyingSymbols() throws Exception {
        when(marketDataService.resolveToken(anyString())).thenReturn("2885");
        when(marketDataService.searchScrip(anyString(), anyString()))
                .thenReturn(MAPPER.readTree("[{\"tsym\":\"RELIANCE25JUL2500CE\"}]"));

        // Build 24h mock data: previous day bars + today's 09:15 bar with vol filter pass
        List<Candle> oneMin = new ArrayList<>();
        LocalDate prev = today.minusDays(1);
        for (int i = 0; i < 20; i++) {
            oneMin.add(minBar(prev, LocalTime.of(15, 10).plusMinutes(i), 10000));
        }
        // today's opening: open=high with first bar volume 3x+ SMA(10000)
        oneMin.add(candle(LocalTime.of(9, 15), 2500.0, 2500.5, 2498.0, 2499.5, 40000));
        oneMin.add(candle(LocalTime.of(9, 16), 2499.5, 2500.0, 2497.0, 2498.0, 20000));
        oneMin.add(candle(LocalTime.of(9, 17), 2498.0, 2498.5, 2495.0, 2496.0, 15000));
        oneMin.add(candle(LocalTime.of(9, 40), 2440.0, 2450.0, 2430.0, 2445.0, 99999));
        when(marketDataService.fetchHistoricalCandles(
                        eq("NSE"), eq("2885"), anyString(), eq("1"), eq(1)))
                .thenReturn(oneMin);

        service.runMorningScan();

        assertThat(service.isScanExecutedToday()).isTrue();
        assertThat(service.getWatchlist()).hasSize(1);
        OhlvSetup admitted = service.getWatchlist().get(0);
        assertThat(admitted.getSymbol()).isEqualTo("RELIANCE");
        assertThat(admitted.getDirection()).isEqualTo(OhlvDirection.BEARISH);
        assertThat(admitted.getMarkedHigh()).isEqualByComparingTo("2500.5");
    }

    @Test
    void morningScan_volumeFilterRejects() throws Exception {
        when(marketDataService.resolveToken(anyString())).thenReturn("2885");
        when(marketDataService.searchScrip(anyString(), anyString()))
                .thenReturn(MAPPER.readTree("[{\"tsym\":\"RELIANCE25JUL2500CE\"}]"));

        List<Candle> oneMin = new ArrayList<>();
        LocalDate prev = today.minusDays(1);
        for (int i = 0; i < 20; i++) {
            oneMin.add(minBar(prev, LocalTime.of(15, 10).plusMinutes(i), 10000));
        }
        // first bar volume (20000) < 3x SMA (30000) → reject
        oneMin.add(candle(LocalTime.of(9, 15), 2500.0, 2500.5, 2498.0, 2499.5, 20000));
        oneMin.add(candle(LocalTime.of(9, 16), 2499.5, 2500.0, 2497.0, 2498.0, 20000));
        when(marketDataService.fetchHistoricalCandles(
                        eq("NSE"), eq("2885"), anyString(), eq("1"), eq(1)))
                .thenReturn(oneMin);

        service.runMorningScan();

        assertThat(service.getWatchlist()).isEmpty();
    }

    @Test
    void morningScan_skipsNonFnoSymbols() throws Exception {
        when(marketDataService.resolveToken(anyString())).thenReturn("2885");
        // F&O search returns no matching option symbol
        when(marketDataService.searchScrip(anyString(), anyString()))
                .thenReturn(MAPPER.createArrayNode());

        List<Candle> oneMin = new ArrayList<>();
        LocalDate prev = today.minusDays(1);
        for (int i = 0; i < 20; i++) {
            oneMin.add(minBar(prev, LocalTime.of(15, 10).plusMinutes(i), 10000));
        }
        oneMin.add(candle(LocalTime.of(9, 15), 2500.0, 2500.5, 2498.0, 2499.5, 40000));
        oneMin.add(candle(LocalTime.of(9, 16), 2499.5, 2500.0, 2497.0, 2498.0, 20000));
        when(marketDataService.fetchHistoricalCandles(
                        anyString(), anyString(), anyString(), anyString(), eq(1)))
                .thenReturn(oneMin);

        service.runMorningScan();

        assertThat(service.getWatchlist()).isEmpty();
    }

    @Test
    void morningScan_runsOnlyOncePerDay() throws Exception {
        when(marketDataService.resolveToken(anyString())).thenReturn("2885");
        when(marketDataService.searchScrip(anyString(), anyString()))
                .thenReturn(MAPPER.createArrayNode());

        AtomicInteger fetchCount = new AtomicInteger(0);
        when(marketDataService.fetchHistoricalCandles(
                        anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenAnswer(
                        inv -> {
                            fetchCount.incrementAndGet();
                            return List.of();
                        });

        service.runMorningScan();
        service.runMorningScan();

        assertThat(service.isScanExecutedToday()).isTrue();
    }

    @Test
    void squareOff_closesAllOpenPositions() throws Exception {
        // Seed one position directly
        OhlvPaperPosition pos =
                new OhlvPaperPosition(
                        "OHLV_T1",
                        "RELIANCE",
                        OhlvDirection.BEARISH,
                        BigDecimal.valueOf(2500),
                        250,
                        BigDecimal.valueOf(40.0),
                        Instant.now(service.getClock()));
        service.getOpenPositionsInternal().add(pos);

        JsonNode optionQuote = MAPPER.readTree("{\"lp\":\"55.0\"}");
        when(marketDataService.searchScrip(eq("NFO"), anyString()))
                .thenReturn(MAPPER.readTree("[{\"token\":\"12345\"}]"));
        when(marketDataService.fetchQuote("NFO", "12345")).thenReturn(optionQuote);

        service.executeSquareOff();

        assertThat(service.getOpenPositions()).isEmpty();
        assertThat(service.getClosedTrades()).hasSize(1);
        assertThat(service.getClosedTrades().get(0).getRealizedPnl())
                .isEqualByComparingTo(BigDecimal.valueOf(15.0).multiply(BigDecimal.valueOf(250)));
    }

    @Test
    void runCycle_outsideWindow_doesNothing() throws Exception {
        // 03:00Z = 08:30 IST — before the 09:35 monitoring window
        service.setClock(Clock.fixed(Instant.parse("2025-07-24T03:00:00Z"), IST));
        service.runCycle();

        assertThat(service.getWatchlist()).isEmpty();
    }
}
