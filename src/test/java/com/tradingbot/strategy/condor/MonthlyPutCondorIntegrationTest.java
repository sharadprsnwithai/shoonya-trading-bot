package com.tradingbot.strategy.condor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.tradingbot.marketdata.ShoonyaMarketDataService;
import com.tradingbot.marketdata.ShoonyaOptionChainService;
import com.tradingbot.strategy.condor.config.MonthlyPutCondorProperties;
import com.tradingbot.strategy.condor.model.PutCondorCycleHistory;
import com.tradingbot.strategy.condor.model.PutCondorPosition;
import com.tradingbot.strategy.condor.model.PutCondorState;
import com.tradingbot.strategy.condor.repository.SqlitePutCondorRepository;
import com.tradingbot.strategy.condor.service.MonthlyPutCondorService;
import com.tradingbot.strategy.condor.service.PutCondorOrderSlicer;
import com.tradingbot.telegram.TelegramService;
import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

class MonthlyPutCondorIntegrationTest {

    @TempDir File tempDir;

    private SqlitePutCondorRepository repository;
    private PutCondorOrderSlicer orderSlicer;
    private ShoonyaMarketDataService marketDataService;
    private ShoonyaOptionChainService optionChainService;
    private TelegramService telegramService;
    private MonthlyPutCondorProperties properties;
    private MonthlyPutCondorService service;

    @BeforeEach
    void setUp() {
        File dbFile = new File(tempDir, "test_condor_integration.db");
        repository = new SqlitePutCondorRepository(dbFile.getAbsolutePath());
        repository.init();

        orderSlicer = Mockito.mock(PutCondorOrderSlicer.class);
        marketDataService = Mockito.mock(ShoonyaMarketDataService.class);
        optionChainService = Mockito.mock(ShoonyaOptionChainService.class);
        telegramService = Mockito.mock(TelegramService.class);

        when(orderSlicer.executeLegOrders(any(), any())).thenReturn(true);

        properties = new MonthlyPutCondorProperties();
        properties.setEnabled(true);
        properties.setExecutionMode("PAPER");
        properties.setLots(2); // 2 Lots for test
        properties.setLotSize(65);
        properties.setStrikeWidth(200);
        properties.setTargetProfitPct(6.0);
        properties.setEarlyExitTargetPct(4.5);
        properties.setEarlyExitDaysBeforeExpiry(3);
        properties.setStopLossPct(3.0);
        properties.setUpsideTriggerPts(150);

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
            "Complete end-to-end Put Condor lifecycle: Entry -> Upside Rally Adjustment -> Target Hit -> Square-Off")
    void testEndToEndPutCondorLifecycle() {
        // Step 1: Deploy Cycle on 5th Oct 2026 at Spot = 25000 (ATM = 25000)
        BigDecimal spotEntry = BigDecimal.valueOf(25000.0);
        boolean entered = service.evaluateAndEnterCycle(spotEntry);
        assertTrue(entered, "Entry must succeed");

        PutCondorPosition activePos = service.getActivePosition();
        assertNotNull(activePos);
        assertEquals(PutCondorState.CONDOR_ACTIVE, activePos.getState());
        assertEquals(2, activePos.getLots());
        assertEquals(130, activePos.getTotalQuantity());
        assertEquals(24800, activePos.getK1BuyStrike());
        assertEquals(24600, activePos.getK2SellStrike());
        assertEquals(24400, activePos.getK3SellStrike());
        assertEquals(24200, activePos.getK4BuyStrike());

        // Step 2: Spot rallies to 25160 -> Adjustment A (Upside Financing) triggers
        LocalDate tick1Date = LocalDate.of(2026, 10, 8);
        service.onMarketTick(BigDecimal.valueOf(25160.0), tick1Date);

        PutCondorPosition adjustedPos = service.getActivePosition();
        assertNotNull(adjustedPos);
        assertEquals(PutCondorState.UPSIDE_FINANCED, adjustedPos.getState());
        assertTrue(adjustedPos.isUpsideSpreadActive());
        assertEquals(24900, adjustedPos.getUpsideSellStrike());
        assertEquals(24800, adjustedPos.getUpsideBuyStrike());

        // Step 3: Fast Forward to Target Hit (MTM >= +6.0% / +Rs 12,000 on 2 lots)
        // Simulate liquidation / exit
        service.squareOffAll("TARGET_PROFIT_HIT");
        assertNull(service.getActivePosition(), "Active position must be cleared after square-off");

        // Verify SQLite historical archive
        List<PutCondorCycleHistory> history = repository.getHistory(10);
        assertEquals(1, history.size(), "Completed cycle must be archived in SQLite history");
        assertEquals("TARGET_PROFIT_HIT", history.get(0).getExitReason());
        assertEquals(2, history.get(0).getLots());
    }
}
