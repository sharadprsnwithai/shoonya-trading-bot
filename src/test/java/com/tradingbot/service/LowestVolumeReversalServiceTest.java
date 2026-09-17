package com.tradingbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.strategy.LowestVolumeDirection;
import com.tradingbot.model.strategy.LowestVolumePaperPosition;
import com.tradingbot.model.strategy.LowestVolumeSetup;
import com.tradingbot.model.strategy.LowestVolumeSetupState;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LowestVolumeReversalServiceTest {

    private ShoonyaMarketDataService marketDataService;
    private TechnicalAnalysisService taService;
    private TelegramService telegramService;
    private ShoonyaConfig config;
    private LowestVolumeReversalService service;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @BeforeEach
    void setUp() {
        marketDataService = mock(ShoonyaMarketDataService.class);
        taService = new TechnicalAnalysisService();
        telegramService = mock(TelegramService.class);
        config = mock(ShoonyaConfig.class);

        service =
                new LowestVolumeReversalService(
                        marketDataService, taService, telegramService, config);
        service.setPaperCapital(100000.0);
        service.setRiskPerTradePercent(1.0); // Rs. 1000 risk
        service.setMaxConcurrentTrades(2);

        // Fixed clock at 10:00 AM IST (during active market hours)
        service.setClock(
                java.time.Clock.fixed(
                        LocalDate.now(IST).atTime(10, 0).atZone(IST).toInstant(), IST));
    }

    private Instant todayInstant(int hour, int minute) {
        LocalDate today = LocalDate.now(IST);
        return today.atTime(hour, minute).atZone(IST).toInstant();
    }

    private Candle makeCandle(
            String symbol, Instant time, double o, double h, double l, double c, long v) {
        return new Candle(
                symbol,
                "5",
                time,
                BigDecimal.valueOf(o),
                BigDecimal.valueOf(h),
                BigDecimal.valueOf(l),
                BigDecimal.valueOf(c),
                v);
    }

    @Test
    void testLowestVolumeReversal_LongFromGreen1stCandle() {
        LowestVolumeSetup setup = new LowestVolumeSetup("RELIANCE", null);
        double atr = 10.0;

        Instant t0 = todayInstant(9, 15);
        // Candle 1 (09:15): Green -> Direction LONG (vol 50,000)
        Candle c1 = makeCandle("RELIANCE", t0, 2500, 2512, 2499, 2510, 50000);
        // Candle 2 (09:20): Red before 9:30 (vol 40,000)
        Candle c2 =
                makeCandle(
                        "RELIANCE", t0.plus(5, ChronoUnit.MINUTES), 2510, 2511, 2504, 2505, 40000);
        // Candle 3 (09:25): Green (vol 45,000)
        Candle c3 =
                makeCandle(
                        "RELIANCE", t0.plus(10, ChronoUnit.MINUTES), 2505, 2515, 2504, 2514, 45000);
        // Candle 4 (09:30): Red (vol 25,000)
        Candle c4 =
                makeCandle(
                        "RELIANCE", t0.plus(15, ChronoUnit.MINUTES), 2514, 2516, 2508, 2509, 25000);
        // Candle 5 (09:35): Red (vol 15,000) -> Lowest volume of the entire day printed after
        // 09:30!
        Candle c5 =
                makeCandle(
                        "RELIANCE", t0.plus(20, ChronoUnit.MINUTES), 2509, 2512, 2506, 2507, 15000);

        List<Candle> todayCandles = List.of(c1, c2, c3, c4, c5);
        service.evaluateLowestVolumeReversal(todayCandles, setup, atr, LocalTime.of(9, 40));

        assertThat(setup.getDirection()).isEqualTo(LowestVolumeDirection.LONG);
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        assertThat(setup.getTriggerCandle()).isEqualTo(c5);
        assertThat(setup.getTriggerCandleVolume()).isEqualTo(15000L);
        assertThat(setup.getTriggerPrice()).isEqualByComparingTo(new BigDecimal("2512.05"));
        assertThat(setup.getStopLossPrice()).isEqualByComparingTo(new BigDecimal("2505.95"));
    }

    @Test
    void testLowestVolumeReversal_ShortFromRed1stCandle() {
        LowestVolumeSetup setup = new LowestVolumeSetup("INFY", null);
        double atr = 8.0;

        Instant t0 = todayInstant(9, 15);
        // Candle 1 (09:15): Red -> Direction SHORT (vol 40,000)
        Candle c1 = makeCandle("INFY", t0, 1500, 1502, 1488, 1490, 40000);
        // Candle 2 (09:20): Green before 9:30 (vol 30,000)
        Candle c2 =
                makeCandle("INFY", t0.plus(5, ChronoUnit.MINUTES), 1490, 1496, 1489, 1495, 30000);
        // Candle 3 (09:25): Red (vol 35,000)
        Candle c3 =
                makeCandle("INFY", t0.plus(10, ChronoUnit.MINUTES), 1495, 1496, 1485, 1486, 35000);
        // Candle 4 (09:30): Green (vol 20,000)
        Candle c4 =
                makeCandle("INFY", t0.plus(15, ChronoUnit.MINUTES), 1486, 1492, 1485, 1491, 20000);
        // Candle 5 (09:35): Green (vol 12,000) -> Lowest volume of the entire day printed after
        // 09:30!
        Candle c5 =
                makeCandle("INFY", t0.plus(20, ChronoUnit.MINUTES), 1491, 1494, 1490, 1493, 12000);

        List<Candle> todayCandles = List.of(c1, c2, c3, c4, c5);
        service.evaluateLowestVolumeReversal(todayCandles, setup, atr, LocalTime.of(9, 40));

        assertThat(setup.getDirection()).isEqualTo(LowestVolumeDirection.SHORT);
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        assertThat(setup.getTriggerCandle()).isEqualTo(c5);
        assertThat(setup.getTriggerCandleVolume()).isEqualTo(12000L);
        assertThat(setup.getTriggerPrice()).isEqualByComparingTo(new BigDecimal("1489.95"));
        assertThat(setup.getStopLossPrice()).isEqualByComparingTo(new BigDecimal("1494.05"));
    }

    @Test
    void testExhaustionDisqualifier_Candle1Move() {
        LowestVolumeSetup setup = new LowestVolumeSetup("TATASTEEL", LowestVolumeDirection.LONG);
        Instant t0 = todayInstant(9, 15);
        // Candle 1 open 100, close 106 (+6% >= 5% threshold)
        Candle c1 = makeCandle("TATASTEEL", t0, 100, 107, 99, 106, 1000000);

        boolean exhausted = service.checkExhaustion("TATASTEEL", List.of(c1), setup);
        assertThat(exhausted).isTrue();
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.REJECTED_EXHAUSTED);
        assertThat(service.getExhaustedSymbols()).contains("TATASTEEL");
    }

    @Test
    void testPullbackAndLowestVolumeArming_AndRangeFilter() {
        LowestVolumeSetup setup = new LowestVolumeSetup("HDFCBANK", LowestVolumeDirection.LONG);
        setup.transitionTo(LowestVolumeSetupState.LEG_CONFIRMED, "Leg confirmed");

        double atr = 10.0;
        Instant t0 = todayInstant(9, 25);

        // Green initial leg
        Candle c1 = makeCandle("HDFCBANK", t0, 1600, 1608, 1599, 1607, 80000);
        Candle c2 =
                makeCandle(
                        "HDFCBANK", t0.plus(5, ChronoUnit.MINUTES), 1607, 1615, 1606, 1614, 90000);

        // Pullback Red candles:
        // c3 volume = 40000, high = 1614, low = 1608 (range = 6 <= 12 (1.2 * ATR))
        // c4 volume = 20000 (lowest volume!), high = 1610, low = 1605 (range = 5 <= 12)
        Candle c3 =
                makeCandle(
                        "HDFCBANK", t0.plus(10, ChronoUnit.MINUTES), 1613, 1614, 1608, 1609, 40000);
        Candle c4 =
                makeCandle(
                        "HDFCBANK", t0.plus(15, ChronoUnit.MINUTES), 1609, 1610, 1605, 1606, 20000);

        List<Candle> todayCandles = List.of(c1, c2, c3, c4);
        service.evaluatePullback(todayCandles, setup, atr);

        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        assertThat(setup.getTriggerCandle()).isEqualTo(c4);
        assertThat(setup.getTriggerCandleVolume()).isEqualTo(20000L);
        // Trigger price = High + 0.05 = 1610.05
        assertThat(setup.getTriggerPrice()).isEqualByComparingTo(new BigDecimal("1610.05"));
        // SL price = Low - 0.05 = 1604.95
        assertThat(setup.getStopLossPrice()).isEqualByComparingTo(new BigDecimal("1604.95"));
        // Target 1 = 1610.05 + 4 * (1610.05 - 1604.95) = 1610.05 + 20.40 = 1630.45
        assertThat(setup.getTarget1Price()).isEqualByComparingTo(new BigDecimal("1630.45"));
    }

    @Test
    void testPullbackRejection_RangeFilter() {
        LowestVolumeSetup setup = new LowestVolumeSetup("SBIN", LowestVolumeDirection.LONG);
        setup.transitionTo(LowestVolumeSetupState.LEG_CONFIRMED, "Leg confirmed");

        double atr = 5.0; // 1.2 * atr = 6.0
        Instant t0 = todayInstant(9, 25);
        Candle c1 = makeCandle("SBIN", t0, 800, 806, 799, 805, 100000);
        // Red candle with huge range: High 808, Low 798 -> range 10.0 > 6.0
        Candle c2 = makeCandle("SBIN", t0.plus(5, ChronoUnit.MINUTES), 805, 808, 798, 801, 15000);

        service.evaluatePullback(List.of(c1, c2), setup, atr);

        // Should NOT arm trigger because range exceeds 1.2 * ATR
        assertThat(setup.getState()).isNotEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
    }

    private void mockOptionPremium(String symbol, String optionType, double premium) {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode searchResult;
        try {
            searchResult =
                    mapper.readTree(
                            "[{\"token\": \"12345\", \"tsym\": \"" + symbol + optionType + "\"}]");
        } catch (Exception e) {
            searchResult = null;
        }
        when(marketDataService.searchScrip(anyString(), anyString())).thenReturn(searchResult);

        com.fasterxml.jackson.databind.JsonNode quote;
        try {
            quote = mapper.readTree("{\"lp\": " + premium + "}");
        } catch (Exception e) {
            quote = null;
        }
        when(marketDataService.fetchQuote(eq("NFO"), anyString())).thenReturn(quote);
    }

    @Test
    void testPaperTradeFill_AndTarget1PartialBooking() {
        LowestVolumeSetup setup = new LowestVolumeSetup("BHARTIARTL", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                null,
                new BigDecimal("1500.00"),
                new BigDecimal("1490.00"),
                new BigDecimal("1540.00")); // Target 1 = 1540 (1:4 RR)

        // Mock option premium for ATM CE
        mockOptionPremium("BHARTIARTL", "CE", 45.0);

        Instant t0 = todayInstant(9, 45);
        // Trigger candle breached by high = 1502 >= 1500
        Candle fillCandle = makeCandle("BHARTIARTL", t0, 1498, 1502, 1497, 1501, 50000);

        service.evaluateArmedTrigger(List.of(fillCandle), setup, LocalTime.of(9, 50));

        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.IN_POSITION);
        assertThat(service.getOpenPositions()).containsKey("BHARTIARTL");

        LowestVolumePaperPosition pos = service.getOpenPositions().get("BHARTIARTL");
        assertThat(pos.getOptionType()).isEqualTo("CE");
        assertThat(pos.getEntryPremium()).isEqualByComparingTo(new BigDecimal("45.00"));
        // Stock SL stored on position
        assertThat(pos.getCurrentStockSl()).isEqualByComparingTo(new BigDecimal("1490.00"));
        // Sizing: stock SL dist = 10, premium SL dist = 45 * 10/1500 = 0.30,
        // lots = 1000 / (0.30 * 125) = 26.67 -> 26 lots × 125 = 3250 units
        assertThat(pos.getLots()).isGreaterThan(0);
        assertThat(pos.getTotalQuantity()).isEqualTo(pos.getLots() * pos.getLotSize());

        // Now test Target 1 hit: High reaches 1542 >= 1540
        Candle targetCandle =
                makeCandle(
                        "BHARTIARTL",
                        t0.plus(5, ChronoUnit.MINUTES),
                        1530,
                        1542,
                        1528,
                        1540,
                        60000);
        when(marketDataService.fetch5MinCandles(anyString(), anyInt()))
                .thenReturn(List.of(fillCandle, targetCandle));

        // Exit premium higher than entry -> profit (target hit)
        mockOptionPremium("BHARTIARTL", "CE", 60.0);

        service.evaluateOpenPositions(LocalTime.of(9, 55));

        assertThat(pos.isPartialBooked()).isTrue();
        assertThat(pos.getRemainingQuantity())
                .isEqualTo(pos.getTotalQuantity() - (pos.getTotalQuantity() + 1) / 2);
        // SL moved to breakeven (stock entry price)
        assertThat(pos.getCurrentStockSl()).isEqualByComparingTo(new BigDecimal("1500.00"));
        // Partial P&L positive: exit premium > entry premium
        assertThat(pos.getPartialPnl()).isPositive();
    }

    @Test
    void testStopLossHit_ExitsEntirePosition() {
        LowestVolumeSetup setup = new LowestVolumeSetup("WIPRO", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                null, new BigDecimal("500.00"), new BigDecimal("495.00"), new BigDecimal("510.00"));

        // Mock option premium: entry = 30.0, exit after SL = 25.0 (loss)
        mockOptionPremium("WIPRO", "CE", 30.0);

        Instant t0 = todayInstant(10, 0);
        Candle fillCandle = makeCandle("WIPRO", t0, 498, 501, 497, 500, 30000);
        service.evaluateArmedTrigger(List.of(fillCandle), setup, LocalTime.of(10, 5));

        LowestVolumePaperPosition pos = service.getOpenPositions().get("WIPRO");
        assertThat(pos).isNotNull();
        assertThat(pos.getEntryPremium()).isEqualByComparingTo(new BigDecimal("30.00"));

        // SL hit: Low drops to 494 <= 495
        Candle slCandle =
                makeCandle("WIPRO", t0.plus(5, ChronoUnit.MINUTES), 498, 499, 493, 494, 40000);
        when(marketDataService.fetch5MinCandles(anyString(), anyInt()))
                .thenReturn(List.of(fillCandle, slCandle));

        // Change mock premium to lower value for exit (SL hit, premium decays)
        mockOptionPremium("WIPRO", "CE", 25.0);

        service.evaluateOpenPositions(LocalTime.of(10, 10));

        assertThat(pos.isClosed()).isTrue();
        assertThat(service.getOpenPositions()).doesNotContainKey("WIPRO");
        assertThat(service.getTradeHistory()).contains(pos);
        assertThat(pos.getExitReason()).isEqualTo("STOP_LOSS_HIT");
        assertThat(pos.getTotalRealizedPnl()).isNegative();
    }

    @Test
    void testArmedTimeout_DropsAfter6Candles() {
        LowestVolumeSetup setup = new LowestVolumeSetup("ITC", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                null, new BigDecimal("450.00"), new BigDecimal("445.00"), new BigDecimal("460.00"));

        // Config default timeout is 6 candles
        service.setSetupTimeoutCandles(6);

        Instant t0 = todayInstant(10, 0);
        // Price does NOT breach 450 (high 449)
        Candle nonBreach = makeCandle("ITC", t0, 447, 449, 446, 448, 20000);

        // Run 6 times - should remain TRIGGER_ARMED
        for (int i = 0; i < 6; i++) {
            service.evaluateArmedTrigger(
                    List.of(nonBreach), setup, LocalTime.of(10, 5).plusMinutes(i * 5L));
        }
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);

        // 7th evaluation exceeds 6 candles timeout
        service.evaluateArmedTrigger(List.of(nonBreach), setup, LocalTime.of(10, 40));

        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.SCANNING);
        assertThat(service.getOpenPositions()).doesNotContainKey("ITC");
    }

    @Test
    void testNiftyTrendDirectionFilter() {
        service.setNiftyBullish(true);
        assertThat(service.isNiftyBullish()).isTrue();

        service.setNiftyBullish(false);
        assertThat(service.isNiftyBullish()).isFalse();
    }

    @Test
    void testSendScanTelegramReport() {
        service.sendScanTelegramReport();
        org.mockito.Mockito.verify(telegramService)
                .sendLvrIdentifiedStocksAlert(
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        anyInt(),
                        anyInt());
    }

    @Test
    void testMorningUniverseScan_FixesWatchlistAndSendsTelegramAlertOnce() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode niftyQuote =
                mapper.readTree("{\"lp\": 24500.0, \"o\": 24400.0, \"c\": 24350.0}");
        when(marketDataService.fetchQuote("NSE", "26000")).thenReturn(niftyQuote);

        assertThat(service.isUniverseScanCompletedToday()).isFalse();

        service.runMorningUniverseScan();

        assertThat(service.isUniverseScanCompletedToday()).isTrue();
        assertThat(service.isNiftyBullish()).isTrue();

        // Verify Telegram alert dispatched once
        org.mockito.Mockito.verify(telegramService, org.mockito.Mockito.times(1))
                .sendLvrIdentifiedStocksAlert(
                        any(), any(), org.mockito.ArgumentMatchers.eq(true), anyInt(), anyInt());
    }

    @Test
    void testResetDaily_ResetsUniverseScanFlag() {
        service.setUniverseScanCompletedToday(true);
        assertThat(service.isUniverseScanCompletedToday()).isTrue();

        service.resetDaily();

        assertThat(service.isUniverseScanCompletedToday()).isFalse();
        assertThat(service.getCurrentTopGainers()).isEmpty();
        assertThat(service.getCurrentTopLosers()).isEmpty();
    }

    // ===== 30-second live price check tests =====

    /** Mock live stock quote returned by fetchQuote("NSE", token) with lp, h, l fields. */
    private void mockLiveStockQuote(String symbol, double ltp, double high, double low) {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            com.fasterxml.jackson.databind.JsonNode stockQuote =
                    mapper.readTree(
                            String.format("{\"lp\": %s, \"h\": %s, \"l\": %s}", ltp, high, low));
            when(marketDataService.fetchQuote(eq("NSE"), anyString())).thenReturn(stockQuote);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testLiveCheck_TriggersArmedSetup() {
        LowestVolumeSetup setup = new LowestVolumeSetup("BHARTIARTL", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                null,
                new BigDecimal("1500.00"), // trigger = high + 0.05
                new BigDecimal("1490.00"), // SL
                new BigDecimal("1520.00")); // T1

        service.addActiveSetupForTesting("BHARTIARTL", setup);

        // Live quote: session high breaches trigger
        mockLiveStockQuote("BHARTIARTL", 1505.0, 1505.0, 1498.0);
        mockOptionPremium("BHARTIARTL", "CE", 50.0);

        assertThat(service.getOpenPositions()).isEmpty();
        service.evaluateLivePriceActions();

        assertThat(service.getOpenPositions()).containsKey("BHARTIARTL");
        LowestVolumePaperPosition pos = service.getOpenPositions().get("BHARTIARTL");
        assertThat(pos.getDirection()).isEqualTo(LowestVolumeDirection.LONG);
        assertThat(pos.getOptionType()).isEqualTo("CE");
    }

    @Test
    void testLiveCheck_ExitsOnSLHit() {
        // Create armed setup and fill a position manually
        LowestVolumeSetup setup = new LowestVolumeSetup("BHARTIARTL", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                null,
                new BigDecimal("1500.00"),
                new BigDecimal("1490.00"),
                new BigDecimal("1520.00"));

        mockOptionPremium("BHARTIARTL", "CE", 45.0);
        service.addActiveSetupForTesting("BHARTIARTL", setup);

        Instant fillTime = todayInstant(10, 0);
        Candle fillCandle = makeCandle("BHARTIARTL", fillTime, 1500, 1514, 1499, 1510, 30000);
        when(marketDataService.fetch5MinCandles(anyString(), anyInt()))
                .thenReturn(List.of(fillCandle));
        service.evaluateArmedTrigger(List.of(fillCandle), setup, LocalTime.of(10, 5));

        LowestVolumePaperPosition pos = service.getOpenPositions().get("BHARTIARTL");
        assertThat(pos).isNotNull();
        BigDecimal slBefore = pos.getCurrentStockSl();

        // Now mock live quote: LTP drops below SL (1490)
        mockLiveStockQuote("BHARTIARTL", 1485.0, 1495.0, 1485.0);
        mockOptionPremium("BHARTIARTL", "CE", 35.0);

        service.evaluateLivePriceActions();

        assertThat(pos.isClosed()).isTrue();
        assertThat(pos.getExitReason()).contains("STOP_LOSS_HIT_LIVE");
    }

    @Test
    void testLiveCheck_PartialBooksOnTarget1() {
        // Create armed setup and fill a position
        LowestVolumeSetup setup = new LowestVolumeSetup("BHARTIARTL", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                null,
                new BigDecimal("1500.00"),
                new BigDecimal("1490.00"),
                new BigDecimal("1520.00"));

        mockOptionPremium("BHARTIARTL", "CE", 45.0);
        service.addActiveSetupForTesting("BHARTIARTL", setup);

        Instant fillTime = todayInstant(10, 0);
        Candle fillCandle = makeCandle("BHARTIARTL", fillTime, 1500, 1514, 1499, 1510, 30000);
        when(marketDataService.fetch5MinCandles(anyString(), anyInt()))
                .thenReturn(List.of(fillCandle));
        service.evaluateArmedTrigger(List.of(fillCandle), setup, LocalTime.of(10, 5));

        LowestVolumePaperPosition pos = service.getOpenPositions().get("BHARTIARTL");
        assertThat(pos).isNotNull();

        // Live quote: LTP rises above target1 (1520)
        mockLiveStockQuote("BHARTIARTL", 1525.0, 1525.0, 1515.0);
        mockOptionPremium("BHARTIARTL", "CE", 65.0);

        service.evaluateLivePriceActions();

        assertThat(pos.isPartialBooked()).isTrue();
        assertThat(pos.getPartialPnl()).isPositive();
        assertThat(pos.getRemainingQuantity()).isLessThan(pos.getTotalQuantity());
    }

    @Test
    void testLiveCheck_SkipsWhenQuoteFails() {
        LowestVolumeSetup setup = new LowestVolumeSetup("BHARTIARTL", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                null,
                new BigDecimal("1500.00"),
                new BigDecimal("1490.00"),
                new BigDecimal("1520.00"));
        service.addActiveSetupForTesting("BHARTIARTL", setup);

        // Quote fetch returns null
        when(marketDataService.fetchQuote(anyString(), anyString())).thenReturn(null);

        service.evaluateLivePriceActions();

        // Setup should still be ARMED (no action taken)
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        assertThat(service.getOpenPositions()).isEmpty();
    }

    @Test
    void testLiveCheck_DoesNotIncrementTimeout() {
        LowestVolumeSetup setup = new LowestVolumeSetup("ITC", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                null, new BigDecimal("450.00"), new BigDecimal("445.00"), new BigDecimal("460.00"));
        service.addActiveSetupForTesting("ITC", setup);

        // Quote does NOT breach trigger (high < trigger)
        mockLiveStockQuote("ITC", 448.0, 449.0, 446.0);

        assertThat(setup.getArmedCandlesElapsed()).isEqualTo(0);
        service.evaluateLivePriceActions();
        assertThat(setup.getArmedCandlesElapsed()).isEqualTo(0);
    }

    @Test
    void testLiveCheck_SLCheckedBeforeTarget1() {
        // Fill position where SL=1490 and Target1=1520
        LowestVolumeSetup setup = new LowestVolumeSetup("BHARTIARTL", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                null,
                new BigDecimal("1500.00"),
                new BigDecimal("1490.00"),
                new BigDecimal("1520.00"));

        mockOptionPremium("BHARTIARTL", "CE", 45.0);
        service.addActiveSetupForTesting("BHARTIARTL", setup);

        Instant fillTime = todayInstant(10, 0);
        Candle fillCandle = makeCandle("BHARTIARTL", fillTime, 1500, 1514, 1499, 1510, 30000);
        when(marketDataService.fetch5MinCandles(anyString(), anyInt()))
                .thenReturn(List.of(fillCandle));
        service.evaluateArmedTrigger(List.of(fillCandle), setup, LocalTime.of(10, 5));

        LowestVolumePaperPosition pos = service.getOpenPositions().get("BHARTIARTL");
        assertThat(pos).isNotNull();

        // Mock: LTP is at SL level (1490) — SL should fire, not Target1
        mockLiveStockQuote("BHARTIARTL", 1490.0, 1525.0, 1490.0);
        mockOptionPremium("BHARTIARTL", "CE", 35.0);

        service.evaluateLivePriceActions();

        assertThat(pos.isClosed()).isTrue();
        assertThat(pos.getExitReason()).contains("STOP_LOSS_HIT_LIVE");
        assertThat(pos.isPartialBooked()).isFalse();
    }

    @Test
    void testLowestVolumeOppositeCandle_After930AM() {
        LowestVolumeSetup setup = new LowestVolumeSetup("TATAMOTORS", LowestVolumeDirection.LONG);

        double atr = 10.0;
        Instant t0 = todayInstant(9, 15);

        List<Candle> todayCandles = new java.util.ArrayList<>();
        // Candle 0 (09:15): Green
        todayCandles.add(makeCandle("TATAMOTORS", t0, 950, 955, 948, 954, 80000));
        // Candle 1 (09:20): Red before 09:30
        Candle earlyRed =
                makeCandle("TATAMOTORS", t0.plus(5, ChronoUnit.MINUTES), 954, 955, 950, 951, 40000);
        todayCandles.add(earlyRed);

        // Candles 2..11 (09:25 .. 10:10): 10 consecutive green candles
        for (int i = 2; i <= 11; i++) {
            todayCandles.add(
                    makeCandle(
                            "TATAMOTORS",
                            t0.plus(i * 5L, ChronoUnit.MINUTES),
                            950 + i * 2,
                            955 + i * 2,
                            949 + i * 2,
                            954 + i * 2,
                            60000));
        }

        // Candle 12 (10:15): Red with 35,000 volume
        todayCandles.add(
                makeCandle(
                        "TATAMOTORS", t0.plus(60, ChronoUnit.MINUTES), 978, 979, 974, 975, 35000));
        // Candle 13 (10:20): Red with 25,000 volume -> lowest volume of entire day printed after
        // 09:30!
        Candle lowestAfter930Red =
                makeCandle(
                        "TATAMOTORS", t0.plus(65, ChronoUnit.MINUTES), 975, 976, 972, 973, 25000);
        todayCandles.add(lowestAfter930Red);

        // Evaluate with all 14 candles
        service.evaluateLowestVolumeReversal(todayCandles, setup, atr, LocalTime.of(10, 25));

        // Must pick lowestAfter930Red (volume 25000)
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        assertThat(setup.getTriggerCandle()).isEqualTo(lowestAfter930Red);
        assertThat(setup.getTriggerCandleVolume()).isEqualTo(25000L);
    }

    @Test
    void testLowestVolumeOppositeCandle_RejectedIfNotLowestFromStartOfDay() {
        LowestVolumeSetup setup = new LowestVolumeSetup("TATAMOTORS", LowestVolumeDirection.LONG);

        double atr = 10.0;
        Instant t0 = todayInstant(9, 15);

        // Candle 0 (09:15): Green (80,000) -> LONG
        Candle c0 = makeCandle("TATAMOTORS", t0, 950, 955, 948, 954, 80000);
        // Candle 1 (09:20): Green with volume 10,000 (Session lowest is 10,000)
        Candle c1 =
                makeCandle("TATAMOTORS", t0.plus(5, ChronoUnit.MINUTES), 954, 956, 953, 955, 10000);
        // Candle 2 (09:25): Green (50,000)
        Candle c2 =
                makeCandle(
                        "TATAMOTORS", t0.plus(10, ChronoUnit.MINUTES), 955, 957, 954, 956, 50000);
        // Candle 3 (09:30): Red with volume 25,000 (> 10,000 session min)
        Candle c3 =
                makeCandle(
                        "TATAMOTORS", t0.plus(15, ChronoUnit.MINUTES), 956, 957, 952, 953, 25000);

        List<Candle> todayCandles = new java.util.ArrayList<>(List.of(c0, c1, c2, c3));
        service.evaluateLowestVolumeReversal(todayCandles, setup, atr, LocalTime.of(9, 35));

        // Should NOT arm because 25,000 is not the lowest volume since start of day (10,000 is
        // lower)
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.SCANNING);
        assertThat(setup.getTriggerCandle()).isNull();

        // Candle 4 (09:35): Red with volume 8,000 (New session low <= 8,000)
        Candle c4 =
                makeCandle("TATAMOTORS", t0.plus(20, ChronoUnit.MINUTES), 953, 954, 949, 950, 8000);
        todayCandles.add(c4);

        service.evaluateLowestVolumeReversal(todayCandles, setup, atr, LocalTime.of(9, 40));

        // Should now ARM because c4 (8,000) is the lowest of the entire day printed >= 09:30!
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        assertThat(setup.getTriggerCandle()).isEqualTo(c4);
        assertThat(setup.getTriggerCandleVolume()).isEqualTo(8000L);
    }

    @Test
    void testEntryCutoffAt1300_CancelsArmedTrigger() {
        LowestVolumeSetup setup = new LowestVolumeSetup("RELIANCE", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                null,
                new BigDecimal("2500.00"),
                new BigDecimal("2490.00"),
                new BigDecimal("2520.00"));

        Instant t0 = todayInstant(13, 5);
        Candle candle = makeCandle("RELIANCE", t0, 2498, 2505, 2497, 2502, 50000);

        // Evaluation at 13:01 (after 13:00 cutoff)
        service.evaluateArmedTrigger(List.of(candle), setup, LocalTime.of(13, 1));

        // Setup must be dropped to SCANNING due to 13:00 cutoff
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.SCANNING);
        assertThat(service.getOpenPositions()).doesNotContainKey("RELIANCE");
    }

    @Test
    void testRunCycle_Past1300_DoesNotScanNewSetups() {
        service.setClock(
                java.time.Clock.fixed(
                        LocalDate.now(IST).atTime(13, 5).atZone(IST).toInstant(), IST));

        service.runCycle();

        // Active setups should not be populated with new items past cutoff
        assertThat(service.getActiveSetups()).isEmpty();
    }

    @Test
    void testTelegramArmedAlert_WhenDisabled_DoesNotSendOnArmed() {
        LowestVolumeSetup setup = new LowestVolumeSetup("HDFCBANK", LowestVolumeDirection.LONG);
        setup.transitionTo(LowestVolumeSetupState.LEG_CONFIRMED, "Leg confirmed");
        service.setTelegramArmedAlerts(false);

        double atr = 10.0;
        Instant t0 = todayInstant(9, 25);
        Candle c1 = makeCandle("HDFCBANK", t0, 1600, 1608, 1599, 1607, 80000);
        Candle c2 =
                makeCandle(
                        "HDFCBANK", t0.plus(5, ChronoUnit.MINUTES), 1607, 1608, 1602, 1603, 10000);

        assertThat(service.isTelegramArmedAlerts()).isFalse();

        service.evaluatePullback(List.of(c1, c2), setup, atr);

        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        // Armed alert should NOT be sent when disabled
        org.mockito.Mockito.verify(telegramService, org.mockito.Mockito.never())
                .sendLvrSetupArmedAlert(any(), anyInt(), any());
    }

    @Test
    void testTelegramArmedAlert_WhenEnabled_SendsOnArmed() {
        LowestVolumeSetup setup = new LowestVolumeSetup("HDFCBANK", LowestVolumeDirection.LONG);
        setup.transitionTo(LowestVolumeSetupState.LEG_CONFIRMED, "Leg confirmed");
        service.setTelegramArmedAlerts(true);

        double atr = 10.0;
        Instant t0 = todayInstant(9, 25);
        Candle c1 = makeCandle("HDFCBANK", t0, 1600, 1608, 1599, 1607, 80000);
        Candle c2 =
                makeCandle(
                        "HDFCBANK", t0.plus(5, ChronoUnit.MINUTES), 1607, 1608, 1602, 1603, 10000);

        assertThat(service.isTelegramArmedAlerts()).isTrue();

        service.evaluatePullback(List.of(c1, c2), setup, atr);

        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        // Armed alert MUST be sent when enabled
        org.mockito.Mockito.verify(telegramService, org.mockito.Mockito.times(1))
                .sendLvrSetupArmedAlert(any(), anyInt(), any());
    }

    @Test
    void testTelegramTradeEntryAlert_SentOnTradeExecution() {
        LowestVolumeSetup setup = new LowestVolumeSetup("BHARTIARTL", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(
                null,
                new BigDecimal("1500.00"),
                new BigDecimal("1490.00"),
                new BigDecimal("1520.00"));

        mockOptionPremium("BHARTIARTL", "CE", 45.0);

        Instant t0 = todayInstant(9, 45);
        Candle fillCandle = makeCandle("BHARTIARTL", t0, 1498, 1502, 1497, 1501, 50000);

        service.evaluateArmedTrigger(List.of(fillCandle), setup, LocalTime.of(9, 50));

        // Trade entry alert MUST be dispatched when taking the trade
        org.mockito.Mockito.verify(telegramService, org.mockito.Mockito.times(1))
                .sendLvrTradeEntryAlert(any(), any());
    }

    @Test
    void testMorningScan_ZeroSnapshots_DoesNotLockWatchlistAndSendsRetryAlert() {
        // Quote fetch returns null (simulating broker error/unresolved token)
        when(marketDataService.fetchQuote(anyString(), anyString())).thenReturn(null);

        // Run at 09:25 AM
        service.setClock(
                java.time.Clock.fixed(
                        LocalDate.now(IST).atTime(9, 25).atZone(IST).toInstant(), IST));

        service.runMorningUniverseScan();

        // Must NOT be marked completed today
        assertThat(service.isUniverseScanCompletedToday()).isFalse();
        assertThat(service.getCurrentTopGainers()).isEmpty();
        assertThat(service.getCurrentTopLosers()).isEmpty();

        // Verify Retry Alert sent, Identified Stocks Alert not sent
        org.mockito.Mockito.verify(telegramService, org.mockito.Mockito.times(1))
                .sendLvrScanRetryAlert(anyBoolean(), any(), anyInt());
        org.mockito.Mockito.verify(telegramService, org.mockito.Mockito.never())
                .sendLvrIdentifiedStocksAlert(any(), any(), anyBoolean(), anyInt(), anyInt());
    }

    @Test
    void testMorningScan_RetryOnSecondCycle_SucceedsAndLocksWatchlist() {
        // First cycle at 09:25: quotes return null
        when(marketDataService.fetchQuote(anyString(), anyString())).thenReturn(null);
        service.setClock(
                java.time.Clock.fixed(
                        LocalDate.now(IST).atTime(9, 25).atZone(IST).toInstant(), IST));
        service.runMorningUniverseScan();
        assertThat(service.isUniverseScanCompletedToday()).isFalse();

        // Second cycle at 09:30: quotes return valid data
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode relQuote = mapper.createObjectNode();
        relQuote.put("stat", "Ok");
        relQuote.put("lp", "2900.0");
        relQuote.put("o", "2850.0");
        relQuote.put("c", "2800.0");
        relQuote.put("v", "100000");

        com.fasterxml.jackson.databind.node.ObjectNode niftyQuote = mapper.createObjectNode();
        niftyQuote.put("stat", "Ok");
        niftyQuote.put("lp", "25000.0");
        niftyQuote.put("o", "24900.0");
        niftyQuote.put("c", "24800.0");

        when(marketDataService.resolveToken("RELIANCE")).thenReturn("2885");
        when(marketDataService.fetchQuote("NSE", "26000")).thenReturn(niftyQuote);
        when(marketDataService.fetchQuote("NSE", "2885")).thenReturn(relQuote);

        service.setClock(
                java.time.Clock.fixed(
                        LocalDate.now(IST).atTime(9, 30).atZone(IST).toInstant(), IST));
        service.runMorningUniverseScan();

        assertThat(service.isUniverseScanCompletedToday()).isTrue();
        assertThat(service.getCurrentTopGainers()).contains("RELIANCE");
        org.mockito.Mockito.verify(telegramService, org.mockito.Mockito.times(1))
                .sendLvrIdentifiedStocksAlert(any(), any(), anyBoolean(), anyInt(), anyInt());
    }

    @Test
    void testMorningScan_CutoffReached_AppliesChampionFallback() {
        when(marketDataService.fetchQuote(anyString(), anyString())).thenReturn(null);

        // Set time past 10:00 AM cutoff (e.g. 10:05 IST)
        service.setClock(
                java.time.Clock.fixed(
                        LocalDate.now(IST).atTime(10, 5).atZone(IST).toInstant(), IST));

        service.runMorningUniverseScan();

        // Must lock watchlist with champion stocks fallback
        assertThat(service.isUniverseScanCompletedToday()).isTrue();
        assertThat(service.getActiveSetups()).isNotEmpty();
        org.mockito.Mockito.verify(telegramService, org.mockito.Mockito.times(1))
                .sendLvrIdentifiedStocksAlert(any(), any(), anyBoolean(), anyInt(), anyInt());
    }

    @Test
    void testRunCycle_RetriesWhenUniverseScanIncomplete() {
        // At 09:30 AM IST
        service.setClock(
                java.time.Clock.fixed(
                        LocalDate.now(IST).atTime(9, 30).atZone(IST).toInstant(), IST));

        // Scanner fails
        when(marketDataService.fetchQuote(anyString(), anyString())).thenReturn(null);

        service.runCycle();

        assertThat(service.isUniverseScanCompletedToday()).isFalse();
        assertThat(service.getActiveSetups()).isEmpty();
        org.mockito.Mockito.verify(telegramService, org.mockito.Mockito.times(1))
                .sendLvrScanRetryAlert(anyBoolean(), any(), anyInt());
    }

    @Test
    void testTieBreak_LowestVolumeOppositeCandle_SelectsMoreRecent() {
        LowestVolumeSetup setup = new LowestVolumeSetup("TCS", null);
        double atr = 15.0;
        Instant t0 = todayInstant(9, 15);

        // Candle 1 (09:15): Green -> LONG
        Candle c1 = makeCandle("TCS", t0, 3500, 3520, 3495, 3515, 60000);
        // Candle 2 (09:20): Green
        Candle c2 =
                makeCandle("TCS", t0.plus(5, ChronoUnit.MINUTES), 3515, 3525, 3510, 3522, 55000);
        // Candle 3 (09:25): Green
        Candle c3 =
                makeCandle("TCS", t0.plus(10, ChronoUnit.MINUTES), 3522, 3530, 3520, 3528, 50000);
        // Candle 4 (09:30): Red with volume 20,000
        Candle c4 =
                makeCandle("TCS", t0.plus(15, ChronoUnit.MINUTES), 3528, 3529, 3518, 3520, 20000);
        // Candle 5 (09:35): Red with identical volume 20,000 (more recent!)
        Candle c5 =
                makeCandle("TCS", t0.plus(20, ChronoUnit.MINUTES), 3520, 3522, 3514, 3516, 20000);

        List<Candle> todayCandles = List.of(c1, c2, c3, c4, c5);
        service.evaluateLowestVolumeReversal(todayCandles, setup, atr, LocalTime.of(9, 40));

        // Most recent candle (c5) should be selected as the trigger candle
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.TRIGGER_ARMED);
        assertThat(setup.getTriggerCandle()).isEqualTo(c5);
        assertThat(setup.getTriggerCandleVolume()).isEqualTo(20000L);
        assertThat(setup.getTriggerPrice()).isEqualByComparingTo(new BigDecimal("3522.05"));
        assertThat(setup.getStopLossPrice()).isEqualByComparingTo(new BigDecimal("3513.95"));
    }

    @Test
    void testDojiFirstCandle_Skipped() {
        LowestVolumeSetup setup = new LowestVolumeSetup("WIPRO", null);
        service.addActiveSetupForTesting("WIPRO", setup);

        Instant t0 = todayInstant(9, 15);
        // Candle 1 (09:15): Open == Close (Doji)
        Candle c1 = makeCandle("WIPRO", t0, 500, 505, 495, 500, 30000);
        Candle c2 = makeCandle("WIPRO", t0.plus(15, ChronoUnit.MINUTES), 500, 502, 498, 499, 10000);

        when(marketDataService.fetch5MinCandles(eq("WIPRO"), anyInt()))
                .thenReturn(generate2DayCandlesWithToday("WIPRO", List.of(c1, c2)));

        service.evaluateSymbolSetup("WIPRO", LocalTime.of(9, 35));

        // Setup should remain SCANNING with no direction or trigger armed
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.SCANNING);
        assertThat(setup.getDirection()).isNull();
        assertThat(setup.getTriggerCandle()).isNull();
    }

    @Test
    void testNiftyBearish_SkipsLongSetup() {
        service.setNiftyBullish(false); // NIFTY is Bearish
        LowestVolumeSetup setup = new LowestVolumeSetup("RELIANCE", null);
        service.addActiveSetupForTesting("RELIANCE", setup);

        Instant t0 = todayInstant(9, 15);
        // Candle 1 (09:15): Green (Long signal)
        Candle c1 = makeCandle("RELIANCE", t0, 2500, 2510, 2495, 2508, 30000);
        Candle c2 = makeCandle("RELIANCE", t0.plus(15, ChronoUnit.MINUTES), 2508, 2509, 2502, 2503, 10000);

        when(marketDataService.fetch5MinCandles(eq("RELIANCE"), anyInt()))
                .thenReturn(generate2DayCandlesWithToday("RELIANCE", List.of(c1, c2)));

        service.evaluateSymbolSetup("RELIANCE", LocalTime.of(9, 35));

        // Setup should NOT be armed because NIFTY is Bearish
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.SCANNING);
        assertThat(setup.getTriggerCandle()).isNull();
    }

    @Test
    void testNiftyBullish_SkipsShortSetup() {
        service.setNiftyBullish(true); // NIFTY is Bullish
        LowestVolumeSetup setup = new LowestVolumeSetup("TATASTEEL", null);
        service.addActiveSetupForTesting("TATASTEEL", setup);

        Instant t0 = todayInstant(9, 15);
        // Candle 1 (09:15): Red (Short signal)
        Candle c1 = makeCandle("TATASTEEL", t0, 150, 151, 145, 147, 30000);
        Candle c2 = makeCandle("TATASTEEL", t0.plus(15, ChronoUnit.MINUTES), 147, 149, 146, 148, 10000);

        when(marketDataService.fetch5MinCandles(eq("TATASTEEL"), anyInt()))
                .thenReturn(generate2DayCandlesWithToday("TATASTEEL", List.of(c1, c2)));

        service.evaluateSymbolSetup("TATASTEEL", LocalTime.of(9, 35));

        // Setup should NOT be armed because NIFTY is Bullish
        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.SCANNING);
        assertThat(setup.getTriggerCandle()).isNull();
    }

    @Test
    void testExecutePaperTradeEntry_RespectsMaxConcurrentTrades() {
        service.setMaxConcurrentTrades(1);
        LowestVolumeSetup setup1 = new LowestVolumeSetup("INFY", LowestVolumeDirection.LONG);
        setup1.setTriggerCandle(null, new BigDecimal("1600.00"), new BigDecimal("1590.00"), new BigDecimal("1640.00"));
        mockOptionPremium("INFY", "CE", 40.0);

        service.executePaperTradeEntry(setup1, new BigDecimal("1600.00"), new BigDecimal("1590.00"));
        assertThat(service.getOpenPositions()).containsKey("INFY");

        // Attempt 2nd trade when max concurrent is 1
        LowestVolumeSetup setup2 = new LowestVolumeSetup("TCS", LowestVolumeDirection.LONG);
        setup2.setTriggerCandle(null, new BigDecimal("3500.00"), new BigDecimal("3480.00"), new BigDecimal("3580.00"));
        mockOptionPremium("TCS", "CE", 80.0);

        service.executePaperTradeEntry(setup2, new BigDecimal("3500.00"), new BigDecimal("3480.00"));
        // TCS should NOT be added
        assertThat(service.getOpenPositions()).doesNotContainKey("TCS");
        assertThat(service.getOpenPositions()).hasSize(1);
    }

    @Test
    void testExecutePaperTradeEntry_UsesTheoreticalPremiumFallbackWhenQuoteFails() {
        LowestVolumeSetup setup = new LowestVolumeSetup("RELIANCE", LowestVolumeDirection.LONG);
        setup.setTriggerCandle(null, new BigDecimal("2900.00"), new BigDecimal("2880.00"), new BigDecimal("2980.00"));

        // Scrip search fails or returns null
        when(marketDataService.searchScrip(anyString(), anyString())).thenReturn(null);

        service.executePaperTradeEntry(setup, new BigDecimal("2900.00"), new BigDecimal("2880.00"));

        assertThat(service.getOpenPositions()).containsKey("RELIANCE");
        LowestVolumePaperPosition pos = service.getOpenPositions().get("RELIANCE");
        assertThat(pos.getEntryPremium()).isNotNull();
        assertThat(pos.getEntryPremium()).isGreaterThan(BigDecimal.ZERO);
    }

    private List<Candle> generate2DayCandlesWithToday(String symbol, List<Candle> todayCandles) {
        List<Candle> list = new java.util.ArrayList<>();
        LocalDate yesterday = LocalDate.now(IST).minusDays(1);
        for (int i = 0; i < 20; i++) {
            Instant t = yesterday.atTime(9, 15).plusMinutes(i * 5L).atZone(IST).toInstant();
            list.add(makeCandle(symbol, t, 500, 505, 495, 500, 20000));
        }
        list.addAll(todayCandles);
        return list;
    }
}
