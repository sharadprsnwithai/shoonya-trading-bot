package com.tradingbot.strategy.condor.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.strategy.condor.config.MonthlyPutCondorProperties;
import com.tradingbot.strategy.condor.model.PutCondorPosition;
import com.tradingbot.strategy.condor.model.PutCondorState;
import com.tradingbot.strategy.condor.repository.SqlitePutCondorRepository;
import com.tradingbot.telegram.TelegramService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MonthlyPutCondorServiceTest {

    private ShoonyaMarketDataService marketDataService;
    private ShoonyaOptionChainService optionChainService;
    private PutCondorOrderSlicer orderSlicer;
    private SqlitePutCondorRepository repository;
    private TelegramService telegramService;
    private MonthlyPutCondorProperties properties;
    private MonthlyPutCondorService service;

    @BeforeEach
    void setUp() {
        marketDataService = Mockito.mock(ShoonyaMarketDataService.class);
        optionChainService = Mockito.mock(ShoonyaOptionChainService.class);
        orderSlicer = Mockito.mock(PutCondorOrderSlicer.class);
        repository = Mockito.mock(SqlitePutCondorRepository.class);
        telegramService = Mockito.mock(TelegramService.class);

        properties = new MonthlyPutCondorProperties();
        properties.setEnabled(true);
        properties.setExecutionMode("PAPER");
        properties.setLots(50);
        properties.setLotSize(65);
        properties.setStrikeWidth(200);
        properties.setTargetProfitPct(6.0);
        properties.setEarlyExitTargetPct(4.5);
        properties.setEarlyExitDaysBeforeExpiry(3);
        properties.setStopLossPct(3.0);
        properties.setUpsideTriggerPts(150);

        when(orderSlicer.executeLegOrders(any(), any())).thenReturn(true);
        when(repository.loadActivePosition()).thenReturn(Optional.empty());

        service =
                new MonthlyPutCondorService(
                        marketDataService,
                        optionChainService,
                        orderSlicer,
                        repository,
                        telegramService,
                        properties);
    }

    @Test
    @DisplayName(
            "Evaluate and enter monthly cycle resolves 200-pt strikes and transitions to CONDOR_ACTIVE")
    void testEvaluateAndEnterCycle() {
        BigDecimal spot = BigDecimal.valueOf(25020.0); // ATM = 25000
        boolean entered = service.evaluateAndEnterCycle(spot);

        assertTrue(entered, "Entry should succeed");
        PutCondorPosition active = service.getActivePosition();
        assertNotNull(active);
        assertEquals(PutCondorState.CONDOR_ACTIVE, active.getState());
        assertEquals(24800, active.getK1BuyStrike());
        assertEquals(24600, active.getK2SellStrike());
        assertEquals(24400, active.getK3SellStrike());
        assertEquals(24200, active.getK4BuyStrike());
        assertEquals(3250, active.getTotalQuantity());

        verify(repository, atLeastOnce()).saveActivePosition(any());
        verify(orderSlicer, times(1)).executeLegOrders(any(), any());
    }

    @Test
    @DisplayName(
            "Market tick triggers Adjustment A (Upside Financing) when spot rallies >= ATM + 150")
    void testAdjustmentAUpsideFinancing() {
        service.evaluateAndEnterCycle(BigDecimal.valueOf(25000.0));
        assertEquals(PutCondorState.CONDOR_ACTIVE, service.getActivePosition().getState());

        // Spot rallies to 25160 (ATM 25000 + 160 >= 150)
        LocalDate tickDate = LocalDate.of(2026, 10, 5);
        service.onMarketTick(BigDecimal.valueOf(25160.0), tickDate);

        assertEquals(PutCondorState.UPSIDE_FINANCED, service.getActivePosition().getState());
        assertTrue(service.getActivePosition().isUpsideSpreadActive());
        assertEquals(24900, service.getActivePosition().getUpsideSellStrike());
        assertEquals(24800, service.getActivePosition().getUpsideBuyStrike());
    }

    @Test
    @DisplayName(
            "Market tick triggers Adjustment B (Sweet Spot Roll) when spot drops <= K2 (24600)")
    void testAdjustmentBSweetSpotRoll() {
        service.evaluateAndEnterCycle(BigDecimal.valueOf(25000.0));
        assertEquals(PutCondorState.CONDOR_ACTIVE, service.getActivePosition().getState());

        // Spot drops to 24580 (<= K2 24600)
        LocalDate tickDate = LocalDate.of(2026, 10, 8);
        service.onMarketTick(BigDecimal.valueOf(24580.0), tickDate);

        assertEquals(PutCondorState.SWEET_SPOT_LOCK, service.getActivePosition().getState());
        assertTrue(service.getActivePosition().isSweetSpotRollActive());
        assertEquals(
                24700,
                service.getActivePosition().getActiveK1Strike(),
                "K1 should roll down 100 pts from 24800 to 24700");
    }

    @Test
    @DisplayName(
            "Market tick triggers Adjustment C (Deep Crash Defense) when spot drops <= K4 - 100")
    void testAdjustmentCDeepCrashDefense() {
        service.evaluateAndEnterCycle(BigDecimal.valueOf(25000.0));
        assertEquals(PutCondorState.CONDOR_ACTIVE, service.getActivePosition().getState());

        // Spot crashes to 24080 (<= K4 24200 - 100 = 24100)
        LocalDate tickDate = LocalDate.of(2026, 10, 12);
        service.onMarketTick(BigDecimal.valueOf(24080.0), tickDate);

        assertNull(service.getActivePosition(), "Active position should be cleared on liquidation");
        verify(repository, times(1)).saveCycleHistory(any());
        verify(repository, times(1)).clearActivePosition();
    }
}
