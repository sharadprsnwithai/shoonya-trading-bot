package com.tradingbot.positional.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.positional.config.PositionalStrategyConfig;
import com.tradingbot.positional.execution.PositionalExecutionService;
import com.tradingbot.positional.indicator.BollingerBandSnapshot;
import com.tradingbot.positional.indicator.BollingerHaIndicatorService;
import com.tradingbot.positional.model.PositionalAlert;
import com.tradingbot.positional.model.PositionalState;
import com.tradingbot.positional.model.PositionalStatus;
import com.tradingbot.positional.model.PositionalTrade;
import com.tradingbot.telegram.TelegramService;
import java.io.File;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BollingerHaPositionalServiceTest {

    private BollingerHaIndicatorService indicatorService;
    private ShoonyaMarketDataService marketDataService;
    private PositionalExecutionService executionService;
    private TelegramService telegramService;
    private PositionalStrategyConfig config;
    private ObjectMapper objectMapper;
    private BollingerHaPositionalService positionalService;

    @TempDir File tempDir;

    @BeforeEach
    void setUp() {
        indicatorService = mock(BollingerHaIndicatorService.class);
        marketDataService = mock(ShoonyaMarketDataService.class);
        executionService = mock(PositionalExecutionService.class);
        telegramService = mock(TelegramService.class);

        config = new PositionalStrategyConfig();
        config.setStateFilePath(
                new File(tempDir, "bb_rsi_positional_state.json").getAbsolutePath());
        config.setExecutionMode("MANUAL_CONFIRMATION");
        config.setNumLots(10);
        config.setQuantity(65);

        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        positionalService =
                new BollingerHaPositionalService(
                        indicatorService,
                        marketDataService,
                        executionService,
                        telegramService,
                        config,
                        objectMapper);
    }

    @Test
    void testAlertDetectionWhenBandTouchedAndInsideClose() {
        List<BollingerBandSnapshot> snapshots = new ArrayList<>();
        Instant now = Instant.now();

        // 1. Touched Upper Band snapshot
        snapshots.add(
                new BollingerBandSnapshot(
                        now.minusSeconds(86400 * 2),
                        "2025-02-06",
                        BigDecimal.valueOf(23800),
                        BigDecimal.valueOf(24100),
                        BigDecimal.valueOf(23750),
                        BigDecimal.valueOf(24050),
                        BigDecimal.valueOf(23700),
                        BigDecimal.valueOf(24150),
                        BigDecimal.valueOf(23700),
                        BigDecimal.valueOf(24000),
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(24000),
                        BigDecimal.valueOf(23000),
                        BigDecimal.valueOf(0.04)));

        // 2. Alert Candle
        snapshots.add(
                new BollingerBandSnapshot(
                        now.minusSeconds(86400),
                        "2025-02-07",
                        BigDecimal.valueOf(23700),
                        BigDecimal.valueOf(23750),
                        BigDecimal.valueOf(23400),
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(23900),
                        BigDecimal.valueOf(23950),
                        BigDecimal.valueOf(23400),
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(24000),
                        BigDecimal.valueOf(23000),
                        BigDecimal.valueOf(0.04)));

        when(indicatorService.calculate(any(), eq(20), eq(2.0))).thenReturn(snapshots);

        positionalService.evaluateSnapshots(snapshots, BigDecimal.valueOf(23600));

        PositionalState state = positionalService.getState();
        assertEquals(PositionalStatus.ALERT_PENDING, state.getStatus());
        assertNotNull(state.getActiveAlert());
        assertEquals("SELL", state.getActiveAlert().direction());
        assertEquals(BigDecimal.valueOf(23750), state.getActiveAlert().highPrice());
        assertEquals(BigDecimal.valueOf(23400), state.getActiveAlert().lowPrice());
    }

    @Test
    void testEntryTriggerStagesSpreadInManualMode() {
        PositionalAlert alert =
                new PositionalAlert(
                        LocalDate.now().minusDays(1),
                        "SELL",
                        BigDecimal.valueOf(23750),
                        BigDecimal.valueOf(23400),
                        BigDecimal.valueOf(24000),
                        Instant.now());
        positionalService.getState().setStatus(PositionalStatus.ALERT_PENDING);
        positionalService.getState().setActiveAlert(alert);

        List<BollingerBandSnapshot> snapshots = new ArrayList<>();
        snapshots.add(
                new BollingerBandSnapshot(
                        Instant.now().minusSeconds(86400),
                        "2025-02-09",
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(23400),
                        BigDecimal.valueOf(23450),
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(23400),
                        BigDecimal.valueOf(23450),
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(24000),
                        BigDecimal.valueOf(22800),
                        BigDecimal.valueOf(0.05)));

        snapshots.add(
                new BollingerBandSnapshot(
                        Instant.now(),
                        "2025-02-10",
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(23300),
                        BigDecimal.valueOf(23350),
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(23300),
                        BigDecimal.valueOf(23350),
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(24000),
                        BigDecimal.valueOf(22800),
                        BigDecimal.valueOf(0.05)));

        positionalService.evaluateSnapshots(snapshots, BigDecimal.valueOf(23350));

        PositionalState state = positionalService.getState();
        assertEquals(PositionalStatus.STAGED_FOR_APPROVAL, state.getStatus());
        assertNotNull(state.getActiveTrade());
        assertEquals("BEAR_CALL_SPREAD", state.getActiveTrade().strategyType());
        assertEquals("CE", state.getActiveTrade().sellOptionType());
        assertEquals(10, state.getActiveTrade().numLots());
        assertEquals(650, state.getActiveTrade().quantity());
    }

    @Test
    void testApproveAndRejectStagedSpread() {
        PositionalTrade stagedTrade =
                new PositionalTrade(
                        "TRD_1",
                        "NIFTY 50",
                        "BULL_PUT_SPREAD",
                        "PE",
                        BigDecimal.valueOf(23500),
                        "PE",
                        BigDecimal.valueOf(23200),
                        "MONTHLY",
                        LocalDate.now(),
                        BigDecimal.valueOf(23500),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.valueOf(23300),
                        BigDecimal.valueOf(24000),
                        10,
                        650,
                        ExecutionMode.PAPER,
                        "STAGED",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        positionalService.getState().setStatus(PositionalStatus.STAGED_FOR_APPROVAL);
        positionalService.getState().setActiveTrade(stagedTrade);

        PositionalTrade filledTrade =
                new PositionalTrade(
                        "TRD_1",
                        "NIFTY 50",
                        "BULL_PUT_SPREAD",
                        "PE",
                        BigDecimal.valueOf(23500),
                        "PE",
                        BigDecimal.valueOf(23200),
                        "MONTHLY",
                        LocalDate.now(),
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(350.0),
                        BigDecimal.valueOf(90.0),
                        BigDecimal.valueOf(260.0),
                        BigDecimal.valueOf(23300),
                        BigDecimal.valueOf(24000),
                        10,
                        650,
                        ExecutionMode.PAPER,
                        "OPEN",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        when(executionService.executeEntry(any())).thenReturn(filledTrade);

        // Test Approval
        boolean approved = positionalService.approveStagedTrade();
        assertTrue(approved);
        assertEquals(PositionalStatus.IN_BULL_PUT_SPREAD, positionalService.getState().getStatus());
        assertEquals(
                BigDecimal.valueOf(260.0),
                positionalService.getState().getActiveTrade().netCredit());

        // Test Reject on new staged trade
        positionalService.getState().setStatus(PositionalStatus.STAGED_FOR_APPROVAL);
        positionalService.getState().setActiveTrade(stagedTrade);

        boolean rejected = positionalService.rejectStagedTrade();
        assertTrue(rejected);
        assertEquals(PositionalStatus.FLAT, positionalService.getState().getStatus());
        assertNull(positionalService.getState().getActiveTrade());
    }

    @Test
    void testActiveSpreadStopLossExit() {
        PositionalTrade activeTrade =
                new PositionalTrade(
                        "TRD_2",
                        "NIFTY 50",
                        "BULL_PUT_SPREAD",
                        "PE",
                        BigDecimal.valueOf(23500),
                        "PE",
                        BigDecimal.valueOf(23200),
                        "MONTHLY",
                        LocalDate.now().minusDays(3),
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(350.0),
                        BigDecimal.valueOf(90.0),
                        BigDecimal.valueOf(260.0),
                        BigDecimal.valueOf(23300), // SL spot
                        BigDecimal.valueOf(24000), // Target spot
                        10,
                        650,
                        ExecutionMode.PAPER,
                        "OPEN",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        positionalService.getState().setStatus(PositionalStatus.IN_BULL_PUT_SPREAD);
        positionalService.getState().setActiveTrade(activeTrade);

        PositionalTrade exitTrade =
                new PositionalTrade(
                        "TRD_2",
                        "NIFTY 50",
                        "BULL_PUT_SPREAD",
                        "PE",
                        BigDecimal.valueOf(23500),
                        "PE",
                        BigDecimal.valueOf(23200),
                        "MONTHLY",
                        LocalDate.now().minusDays(3),
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(350.0),
                        BigDecimal.valueOf(90.0),
                        BigDecimal.valueOf(260.0),
                        BigDecimal.valueOf(23300),
                        BigDecimal.valueOf(24000),
                        10,
                        650,
                        ExecutionMode.PAPER,
                        "CLOSED",
                        LocalDate.now(),
                        BigDecimal.valueOf(23250),
                        BigDecimal.valueOf(450.0),
                        BigDecimal.valueOf(170.0),
                        BigDecimal.valueOf(-13000.0),
                        "STOP_LOSS");
        when(executionService.executeExit(any(), any(), eq("STOP_LOSS"))).thenReturn(exitTrade);

        List<BollingerBandSnapshot> snapshots = new ArrayList<>();
        snapshots.add(
                new BollingerBandSnapshot(
                        Instant.now().minusSeconds(86400),
                        "2025-02-09",
                        BigDecimal.valueOf(23400),
                        BigDecimal.valueOf(23400),
                        BigDecimal.valueOf(23300),
                        BigDecimal.valueOf(23350),
                        BigDecimal.valueOf(23400),
                        BigDecimal.valueOf(23400),
                        BigDecimal.valueOf(23300),
                        BigDecimal.valueOf(23350),
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(24000),
                        BigDecimal.valueOf(22800),
                        BigDecimal.valueOf(0.05)));

        snapshots.add(
                new BollingerBandSnapshot(
                        Instant.now(),
                        "2025-02-10",
                        BigDecimal.valueOf(23300),
                        BigDecimal.valueOf(23300),
                        BigDecimal.valueOf(23200),
                        BigDecimal.valueOf(23250),
                        BigDecimal.valueOf(23300),
                        BigDecimal.valueOf(23300),
                        BigDecimal.valueOf(23200),
                        BigDecimal.valueOf(23250),
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(24000),
                        BigDecimal.valueOf(22800),
                        BigDecimal.valueOf(0.05)));

        // Current spot drops to 23250 (below SL 23300)
        positionalService.evaluateSnapshots(snapshots, BigDecimal.valueOf(23250));

        assertEquals(PositionalStatus.FLAT, positionalService.getState().getStatus());
        assertNull(positionalService.getState().getActiveTrade());
        assertEquals(1, positionalService.getState().getHistoricalTrades().size());
        assertEquals(
                "STOP_LOSS",
                positionalService.getState().getHistoricalTrades().get(0).exitReason());
    }

    @Test
    void testAlertTimeoutInvalidation() {
        PositionalAlert alert =
                new PositionalAlert(
                        LocalDate.now().minusDays(10),
                        "BUY",
                        BigDecimal.valueOf(23800),
                        BigDecimal.valueOf(23400),
                        BigDecimal.valueOf(23400),
                        Instant.now().minusSeconds(86400 * 10));
        positionalService.getState().setStatus(PositionalStatus.ALERT_PENDING);
        positionalService.getState().setActiveAlert(alert);

        BollingerBandSnapshot today =
                new BollingerBandSnapshot(
                        Instant.now(),
                        "2025-02-10",
                        BigDecimal.valueOf(23600),
                        BigDecimal.valueOf(23700),
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(23650),
                        BigDecimal.valueOf(23600),
                        BigDecimal.valueOf(23700),
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(23650),
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(24000),
                        BigDecimal.valueOf(23000),
                        BigDecimal.valueOf(0.04));

        positionalService.evaluateSnapshots(List.of(today, today), BigDecimal.valueOf(23650));

        assertEquals(PositionalStatus.FLAT, positionalService.getState().getStatus());
        assertNull(positionalService.getState().getActiveAlert());
    }

    @Test
    void testAlertReTouchBandInvalidation() {
        PositionalAlert alert =
                new PositionalAlert(
                        LocalDate.now().minusDays(2),
                        "SELL",
                        BigDecimal.valueOf(23800),
                        BigDecimal.valueOf(23400),
                        BigDecimal.valueOf(23800),
                        Instant.now().minusSeconds(86400 * 2));
        positionalService.getState().setStatus(PositionalStatus.ALERT_PENDING);
        positionalService.getState().setActiveAlert(alert);

        // Today touches Upper Band again (HA High 24100 >= BB Upper 24000)
        BollingerBandSnapshot today =
                new BollingerBandSnapshot(
                        Instant.now(),
                        "2025-02-10",
                        BigDecimal.valueOf(23900),
                        BigDecimal.valueOf(24100),
                        BigDecimal.valueOf(23800),
                        BigDecimal.valueOf(24050),
                        BigDecimal.valueOf(23900),
                        BigDecimal.valueOf(24100),
                        BigDecimal.valueOf(23800),
                        BigDecimal.valueOf(24050),
                        BigDecimal.valueOf(23500),
                        BigDecimal.valueOf(24000),
                        BigDecimal.valueOf(23000),
                        BigDecimal.valueOf(0.04));

        positionalService.evaluateSnapshots(List.of(today, today), BigDecimal.valueOf(23750));

        assertEquals(PositionalStatus.FLAT, positionalService.getState().getStatus());
        assertNull(positionalService.getState().getActiveAlert());
    }
}
