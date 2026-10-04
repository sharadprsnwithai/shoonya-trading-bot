package com.tradingbot.strategy.driftvwap.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tradingbot.strategy.driftvwap.DriftVwapOptionSellingService;
import com.tradingbot.strategy.driftvwap.model.DriftVwapTrendState;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class DriftVwapControllerTest {

    @Test
    @DisplayName("GET /api/v1/drift-vwap/status returns live strategy state")
    void testGetStatusEndpoint() {
        DriftVwapOptionSellingService mockService = mock(DriftVwapOptionSellingService.class);
        DriftVwapController controller = new DriftVwapController(mockService);

        when(mockService.getOpenPosition()).thenReturn(null);
        when(mockService.getLatestTrendState()).thenReturn(DriftVwapTrendState.neutral());
        when(mockService.getTodayTradesCount()).thenReturn(new java.util.concurrent.atomic.AtomicInteger(0));
        when(mockService.getTodayLossCount()).thenReturn(new java.util.concurrent.atomic.AtomicInteger(0));
        when(mockService.getTradeHistory()).thenReturn(java.util.List.of());

        ResponseEntity<Map<String, Object>> resp = controller.getStatus();
        assertNotNull(resp);
        assertEquals(200, resp.getStatusCode().value());
        assertTrue(resp.getBody().containsKey("status"));
    }

    @Test
    @DisplayName("POST /api/v1/drift-vwap/run-cycle triggers immediate 5m cycle")
    void testRunCycleEndpoint() {
        DriftVwapOptionSellingService mockService = mock(DriftVwapOptionSellingService.class);
        DriftVwapController controller = new DriftVwapController(mockService);

        ResponseEntity<Map<String, Object>> resp = controller.runCycle();
        assertEquals(200, resp.getStatusCode().value());
        verify(mockService, times(1)).runCycle();
    }
}
