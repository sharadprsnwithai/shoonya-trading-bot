package com.tradingbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    void testInitialLegDetection_Long() {
        LowestVolumeSetup setup = new LowestVolumeSetup("RELIANCE", LowestVolumeDirection.LONG);
        double atr = 10.0; // 0.5 * atr = 5.0

        Instant t0 = todayInstant(9, 25);
        Candle c1 = makeCandle("RELIANCE", t0, 2500, 2506, 2499, 2505, 50000);
        Candle c2 =
                makeCandle(
                        "RELIANCE", t0.plus(5, ChronoUnit.MINUTES), 2505, 2512, 2504, 2511, 45000);

        List<Candle> candles = List.of(c1, c2); // Cumulative move: 2511 - 2500 = 11.0 >= 5.0
        service.evaluateInitialLeg(candles, setup, atr);

        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.LEG_CONFIRMED);
        assertThat(setup.getInitialLegMove()).isEqualByComparingTo(BigDecimal.valueOf(11.0));
        assertThat(setup.getInitialLegCandles()).hasSize(2);
    }

    @Test
    void testInitialLegDetection_Short() {
        LowestVolumeSetup setup = new LowestVolumeSetup("INFY", LowestVolumeDirection.SHORT);
        double atr = 8.0; // 0.5 * atr = 4.0

        Instant t0 = todayInstant(9, 25);
        Candle c1 = makeCandle("INFY", t0, 1500, 1502, 1495, 1496, 30000);
        Candle c2 =
                makeCandle("INFY", t0.plus(5, ChronoUnit.MINUTES), 1496, 1497, 1490, 1491, 35000);

        List<Candle> candles = List.of(c1, c2); // Cumulative move: 1500 - 1491 = 9.0 >= 4.0
        service.evaluateInitialLeg(candles, setup, atr);

        assertThat(setup.getState()).isEqualTo(LowestVolumeSetupState.LEG_CONFIRMED);
        assertThat(setup.getInitialLegMove()).isEqualByComparingTo(BigDecimal.valueOf(9.0));
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
        // Target 1 = 1610.05 + 2 * (1610.05 - 1604.95) = 1610.05 + 10.20 = 1620.25
        assertThat(setup.getTarget1Price()).isEqualByComparingTo(new BigDecimal("1620.25"));
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
                new BigDecimal("1520.00")); // Target 1 = 1520 (1:2 RR)

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

        // Now test Target 1 hit: High reaches 1521 >= 1520
        Candle targetCandle =
                makeCandle(
                        "BHARTIARTL",
                        t0.plus(5, ChronoUnit.MINUTES),
                        1515,
                        1522,
                        1514,
                        1521,
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
        when(marketDataService.fetchQuote("NSE", "10576")).thenReturn(niftyQuote);

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
}
