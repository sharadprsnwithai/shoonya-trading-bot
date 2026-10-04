package com.tradingbot.strategy.driftvwap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tradingbot.bus.SignalPublisher;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.Candle;
import com.tradingbot.strategy.driftvwap.config.DriftVwapProperties;
import com.tradingbot.strategy.driftvwap.model.DriftDirection;
import com.tradingbot.strategy.driftvwap.model.DriftVwapPosition;
import com.tradingbot.strategy.driftvwap.model.DriftVwapTrendState;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DriftVwapOptionSellingServiceTest {

    private ShoonyaMarketDataService marketDataService;
    private ShoonyaOptionChainService optionChainService;
    private TechnicalAnalysisService taService;
    private SignalPublisher signalPublisher;
    private TelegramService telegramService;
    private DriftVwapProperties properties;
    private DriftVwapOptionSellingService service;

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @BeforeEach
    void setUp() {
        marketDataService = mock(ShoonyaMarketDataService.class);
        optionChainService = mock(ShoonyaOptionChainService.class);
        taService = new TechnicalAnalysisService();
        signalPublisher = mock(SignalPublisher.class);
        telegramService = mock(TelegramService.class);
        properties = new DriftVwapProperties();

        service =
                new DriftVwapOptionSellingService(
                        marketDataService,
                        optionChainService,
                        taService,
                        signalPublisher,
                        telegramService,
                        properties);
        service.setClock(Clock.fixed(Instant.parse("2026-10-01T05:30:00Z"), IST)); // 11:00 IST
    }

    @Test
    @DisplayName("Should detect BULLISH_DRIFT on 15m when Close > VWAP, VWAP rising, and 1-Hr momentum >= +0.12%")
    void testEvaluateBullishDrift() {
        Instant t0 = Instant.parse("2026-10-01T03:45:00Z"); // 09:15 IST

        List<Candle> candles15m =
                List.of(
                        new Candle("NIFTY", "15", t0, BigDecimal.valueOf(25000), BigDecimal.valueOf(25020), BigDecimal.valueOf(24990), BigDecimal.valueOf(25010), 10000),
                        new Candle("NIFTY", "15", t0.plus(15, ChronoUnit.MINUTES), BigDecimal.valueOf(25010), BigDecimal.valueOf(25040), BigDecimal.valueOf(25005), BigDecimal.valueOf(25035), 12000),
                        new Candle("NIFTY", "15", t0.plus(30, ChronoUnit.MINUTES), BigDecimal.valueOf(25035), BigDecimal.valueOf(25060), BigDecimal.valueOf(25030), BigDecimal.valueOf(25055), 15000),
                        new Candle("NIFTY", "15", t0.plus(45, ChronoUnit.MINUTES), BigDecimal.valueOf(25055), BigDecimal.valueOf(25080), BigDecimal.valueOf(25050), BigDecimal.valueOf(25075), 14000),
                        new Candle("NIFTY", "15", t0.plus(60, ChronoUnit.MINUTES), BigDecimal.valueOf(25075), BigDecimal.valueOf(25100), BigDecimal.valueOf(25070), BigDecimal.valueOf(25095), 16000)
                );

        DriftVwapTrendState state = service.evaluate15mDrift(candles15m);
        assertNotNull(state);
        assertEquals(DriftDirection.BULLISH_DRIFT, state.direction());
        assertTrue(state.momentum1hrPct() >= 0.12);
    }

    @Test
    @DisplayName("Should detect BEARISH_DRIFT on 15m when Close < VWAP, VWAP falling, and 1-Hr momentum <= -0.12%")
    void testEvaluateBearishDrift() {
        Instant t0 = Instant.parse("2026-10-01T03:45:00Z"); // 09:15 IST

        List<Candle> candles15m =
                List.of(
                        new Candle("NIFTY", "15", t0, BigDecimal.valueOf(25100), BigDecimal.valueOf(25110), BigDecimal.valueOf(25080), BigDecimal.valueOf(25090), 10000),
                        new Candle("NIFTY", "15", t0.plus(15, ChronoUnit.MINUTES), BigDecimal.valueOf(25090), BigDecimal.valueOf(25095), BigDecimal.valueOf(25060), BigDecimal.valueOf(25065), 12000),
                        new Candle("NIFTY", "15", t0.plus(30, ChronoUnit.MINUTES), BigDecimal.valueOf(25065), BigDecimal.valueOf(25070), BigDecimal.valueOf(25040), BigDecimal.valueOf(25045), 15000),
                        new Candle("NIFTY", "15", t0.plus(45, ChronoUnit.MINUTES), BigDecimal.valueOf(25045), BigDecimal.valueOf(25050), BigDecimal.valueOf(25020), BigDecimal.valueOf(25025), 14000),
                        new Candle("NIFTY", "15", t0.plus(60, ChronoUnit.MINUTES), BigDecimal.valueOf(25025), BigDecimal.valueOf(25030), BigDecimal.valueOf(24995), BigDecimal.valueOf(25000), 16000)
                );

        DriftVwapTrendState state = service.evaluate15mDrift(candles15m);
        assertNotNull(state);
        assertEquals(DriftDirection.BEARISH_DRIFT, state.direction());
        assertTrue(state.momentum1hrPct() <= -0.12);
    }

    @Test
    @DisplayName("Should trigger LONG entry on 1st RED 5m pullback bar during BULLISH_DRIFT")
    void testTriggerLongOnRedPullbackBar() {
        Candle redCandle5m =
                Candle.of5m("NIFTY", Instant.now(), BigDecimal.valueOf(25080), BigDecimal.valueOf(25085), BigDecimal.valueOf(25060), BigDecimal.valueOf(25065), 5000);

        boolean triggered = service.check5mPullbackTrigger(DriftDirection.BULLISH_DRIFT, redCandle5m);
        assertTrue(triggered);
    }

    @Test
    @DisplayName("Should trigger SHORT entry on 1st GREEN 5m pullback bar during BEARISH_DRIFT")
    void testTriggerShortOnGreenPullbackBar() {
        Candle greenCandle5m =
                Candle.of5m("NIFTY", Instant.now(), BigDecimal.valueOf(25020), BigDecimal.valueOf(25040), BigDecimal.valueOf(25015), BigDecimal.valueOf(25035), 5000);

        boolean triggered = service.check5mPullbackTrigger(DriftDirection.BEARISH_DRIFT, greenCandle5m);
        assertTrue(triggered);
    }

    @Test
    @DisplayName("Execute Bullish Entry sells ATM PE with 70% decay target and 60% SL")
    void testExecuteOptionSellingEntryAndExitAtTarget() {
        when(marketDataService.resolveToken(any())).thenReturn("12345");
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .createObjectNode()
                                .put("lp", "100.00"));

        DriftVwapPosition pos = service.executeOptionSellingEntry(BigDecimal.valueOf(25010), DriftDirection.BULLISH_DRIFT);
        assertNotNull(pos);
        assertEquals("PE", pos.getOptionType());
        assertEquals(0, BigDecimal.valueOf(25000).compareTo(pos.getStrikePrice()));
        assertEquals(0, BigDecimal.valueOf(100.00).compareTo(pos.getEntryPremium()));
        assertEquals(0, BigDecimal.valueOf(30.00).compareTo(pos.getTargetPremium())); // 70% decay
        assertEquals(0, BigDecimal.valueOf(160.00).compareTo(pos.getSlPremium()));    // 60% expansion

        // 30s live check when option decays to 28.00 (below target 30.00)
        when(marketDataService.fetchQuote(any(), any()))
                .thenReturn(
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .createObjectNode()
                                .put("lp", "28.00"));

        service.evaluateLivePriceActions();
        assertTrue(pos.isClosed());
        assertEquals("OPTION_TARGET_DECAY", pos.getExitReason());
    }
}
