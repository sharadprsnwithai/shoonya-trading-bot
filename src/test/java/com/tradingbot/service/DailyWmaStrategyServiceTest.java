package com.tradingbot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.execution.ExecutionManager;
import com.tradingbot.indicator.TechnicalAnalysisService;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.model.Candle;
import com.tradingbot.model.strategy.DailyWmaPosition;
import com.tradingbot.order.ShoonyaOrderService;
import com.tradingbot.telegram.TelegramService;
import java.io.File;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DailyWmaStrategyServiceTest {

    private ShoonyaConfig config;
    private ShoonyaMarketDataService marketDataService;
    private ShoonyaOptionChainService optionChainService;
    private ShoonyaOrderService orderService;
    private ExecutionManager executionManager;
    private TechnicalAnalysisService taService;
    private TelegramService telegramService;
    private ObjectMapper objectMapper;
    private Clock fixedClock;

    @TempDir
    File tempDir;

    private DailyWmaStrategyService strategyService;

    @BeforeEach
    void setUp() {
        config = mock(ShoonyaConfig.class);
        marketDataService = mock(ShoonyaMarketDataService.class);
        optionChainService = mock(ShoonyaOptionChainService.class);
        orderService = mock(ShoonyaOrderService.class);
        executionManager = mock(ExecutionManager.class);
        taService = mock(TechnicalAnalysisService.class);
        telegramService = mock(TelegramService.class);
        objectMapper = new ObjectMapper();
        fixedClock = Clock.fixed(Instant.parse("2026-09-10T04:00:10Z"), ZoneId.of("Asia/Kolkata")); // 09:30:10 IST on 10th Sep 2026

        String statePath = new File(tempDir, "wma_state.json").getAbsolutePath();

        strategyService =
                new DailyWmaStrategyService(
                        config,
                        marketDataService,
                        optionChainService,
                        orderService,
                        executionManager,
                        taService,
                        telegramService,
                        objectMapper,
                        fixedClock,
                        "10576", // NIFTY token
                        19,      // WMA period
                        0.22,    // target delta
                        105.0,   // max entry premium
                        15,      // expiry switch day
                        0.02,    // 2% hedge OTM
                        1.0,     // 100% SL (2.0x entry)
                        true,    // allow re-entry
                        "PAPER", // mode
                        1,       // lots
                        65,      // lot size
                        statePath);
    }

    @Test
    void testResolveTargetExpiryCurrentVsNextMonth() {
        // Sep 10th (<= 15th) -> Last Thursday of Sep 2026 = 2026-09-24
        LocalDate sep10 = LocalDate.of(2026, 9, 10);
        LocalDate expiry1 = strategyService.resolveTargetExpiry(sep10);
        assertThat(expiry1).isEqualTo(LocalDate.of(2026, 9, 24));

        // Sep 18th (> 15th) -> Last Thursday of Oct 2026 = 2026-10-29
        LocalDate sep18 = LocalDate.of(2026, 9, 18);
        LocalDate expiry2 = strategyService.resolveTargetExpiry(sep18);
        assertThat(expiry2).isEqualTo(LocalDate.of(2026, 10, 29));
    }

    @Test
    void testEvaluateDailyCycleEntersBullPutSpreadWhenSpotAboveWma() {
        // Setup 25 daily candles
        List<Candle> candles = new ArrayList<>();
        double startPrice = 24000.0;
        for (int i = 0; i < 25; i++) {
            double price = startPrice + i * 40.0;
            candles.add(
                    new Candle(
                            "10576",
                            "DAY",
                            Instant.now().minusSeconds((25 - i) * 86400L),
                            BigDecimal.valueOf(price - 10),
                            BigDecimal.valueOf(price + 20),
                            BigDecimal.valueOf(price - 20),
                            BigDecimal.valueOf(price),
                            50000L));
        }

        when(marketDataService.fetchDailyCandles(eq("10576"), eq(50)))
                .thenReturn(candles);

        // Mock 19 WMA to be 24600.0 when current spot is 25000.0 (Bullish)
        when(taService.calculateLatestWma(any(double[].class), eq(19))).thenReturn(24600.0);

        strategyService.evaluateDailyCycle();

        DailyWmaPosition pos = strategyService.getOpenPosition();
        assertThat(pos).isNotNull();
        assertThat(pos.getBias()).isEqualTo("BULLISH");
        assertThat(pos.getOptionType()).isEqualTo("PE");
        assertThat(pos.getAction()).isEqualTo("SELL");
        assertThat(pos.getShortEntryPrice()).isLessThanOrEqualTo(BigDecimal.valueOf(105.0));
        assertThat(pos.getHedgeStrike()).isLessThan(pos.getShortStrike());
        assertThat(pos.getStopLossPrice()).isEqualByComparingTo(pos.getShortEntryPrice().multiply(BigDecimal.valueOf(2.0)));

        verify(telegramService).sendDailyWmaEntryAlert(eq(pos), anyDouble(), eq(24600.0));
    }

    @Test
    void testEvaluateDailyCycleReversalExitsOldAndEntersNew() {
        // 1. Initial Bullish state
        DailyWmaPosition initialPos =
                new DailyWmaPosition(
                        "WMA_INIT",
                        "NIFTY",
                        "SELL",
                        "PE",
                        "BULLISH",
                        LocalDate.of(2026, 9, 8),
                        Instant.now().minusSeconds(86400L * 2),
                        BigDecimal.valueOf(25000),
                        BigDecimal.valueOf(24800),
                        LocalDate.of(2026, 9, 24),
                        "NIFTY24SEP24200PE",
                        BigDecimal.valueOf(24200),
                        BigDecimal.valueOf(90.0),
                        0.22,
                        65,
                        "NIFTY24SEP23700PE",
                        BigDecimal.valueOf(23700),
                        BigDecimal.valueOf(10.0),
                        65,
                        BigDecimal.valueOf(80.0),
                        BigDecimal.valueOf(180.0),
                        0);
        strategyService.setOpenPositionForTesting(initialPos);

        // 2. Market turns bearish (Spot 24200 < 19 WMA 24700)
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            candles.add(
                    new Candle(
                            "10576",
                            "DAY",
                            Instant.now().minusSeconds((25 - i) * 86400L),
                            BigDecimal.valueOf(24200),
                            BigDecimal.valueOf(24250),
                            BigDecimal.valueOf(24150),
                            BigDecimal.valueOf(24200),
                            50000L));
        }
        when(marketDataService.fetchDailyCandles(eq("10576"), eq(50)))
                .thenReturn(candles);
        when(taService.calculateLatestWma(any(double[].class), eq(19))).thenReturn(24700.0);

        strategyService.evaluateDailyCycle();

        assertThat(initialPos.isClosed()).isTrue();
        assertThat(initialPos.getExitReason()).isEqualTo("19WMA_TREND_REVERSAL");

        DailyWmaPosition newPos = strategyService.getOpenPosition();
        assertThat(newPos).isNotNull();
        assertThat(newPos.getBias()).isEqualTo("BEARISH");
        assertThat(newPos.getOptionType()).isEqualTo("CE");
        assertThat(newPos.getHedgeStrike()).isGreaterThan(newPos.getShortStrike());
    }

    @Test
    void testMonitorStopLossTriggersExit() {
        DailyWmaPosition pos =
                new DailyWmaPosition(
                        "WMA_SL_TEST",
                        "NIFTY",
                        "SELL",
                        "PE",
                        "BULLISH",
                        LocalDate.of(2026, 9, 10),
                        Instant.now(),
                        BigDecimal.valueOf(25000),
                        BigDecimal.valueOf(24800),
                        LocalDate.of(2026, 9, 24),
                        "NIFTY24SEP24200PE",
                        BigDecimal.valueOf(24200),
                        BigDecimal.valueOf(90.0),
                        0.22,
                        65,
                        "NIFTY24SEP23700PE",
                        BigDecimal.valueOf(23700),
                        BigDecimal.valueOf(10.0),
                        65,
                        BigDecimal.valueOf(80.0),
                        BigDecimal.valueOf(180.0), // SL threshold
                        0);
        strategyService.setOpenPositionForTesting(pos);

        // Mock short leg LTP spikes to 195.0 (exceeds SL of 180.0)
        strategyService.monitorStopLossWithLtp(195.0, 5.0);

        assertThat(pos.isClosed()).isTrue();
        assertThat(pos.getExitReason()).isEqualTo("STOP_LOSS_HIT");
        assertThat(pos.getRealizedPnl()).isLessThan(BigDecimal.ZERO);
        verify(telegramService).sendDailyWmaStopLossAlert(eq(pos), eq(195.0));
        verify(telegramService).sendDailyWmaExitAlert(eq(pos), eq("STOP_LOSS_HIT"));
    }
}
