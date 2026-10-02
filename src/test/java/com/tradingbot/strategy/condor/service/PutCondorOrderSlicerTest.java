package com.tradingbot.strategy.condor.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.tradingbot.execution.gateway.ZerodhaBrokerGateway;
import com.tradingbot.model.execution.ExecutionMode;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.TransactionType;
import com.tradingbot.strategy.condor.config.MonthlyPutCondorProperties;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PutCondorOrderSlicerTest {

    private ZerodhaBrokerGateway zerodhaBrokerGateway;
    private MonthlyPutCondorProperties properties;
    private PutCondorOrderSlicer orderSlicer;

    @BeforeEach
    void setUp() {
        zerodhaBrokerGateway = Mockito.mock(ZerodhaBrokerGateway.class);
        properties = new MonthlyPutCondorProperties();
        properties.setMaxFreezeLimit(1800);
        properties.setLotSize(65);
        orderSlicer = new PutCondorOrderSlicer(zerodhaBrokerGateway, properties);
    }

    @Test
    @DisplayName("50 Lots (3250 Qty) with 1800 freeze limit slices into exactly 2 slices of multiples of 65")
    void testSliceCalculation50Lots() {
        List<Integer> slices = orderSlicer.calculateSlices(3250, 65, 1800);
        assertNotNull(slices);
        assertEquals(2, slices.size(), "3250 qty should slice into 2 orders");
        assertEquals(3250, slices.stream().mapToInt(Integer::intValue).sum(), "Sum must match total quantity");
        for (int slice : slices) {
            assertTrue(slice <= 1800, "Each slice must be <= 1800 freeze limit");
            assertEquals(0, slice % 65, "Each slice must be a multiple of lot size (65)");
        }
    }

    @Test
    @DisplayName("70 Lots (4550 Qty) with 1800 freeze limit slices into exactly 3 slices of multiples of 65")
    void testSliceCalculation70Lots() {
        List<Integer> slices = orderSlicer.calculateSlices(4550, 65, 1800);
        assertEquals(3, slices.size(), "4550 qty should slice into 3 orders");
        assertEquals(4550, slices.stream().mapToInt(Integer::intValue).sum(), "Sum must match total quantity");
        for (int slice : slices) {
            assertTrue(slice <= 1800, "Each slice must be <= 1800 freeze limit");
            assertEquals(0, slice % 65, "Each slice must be a multiple of lot size (65)");
        }
    }

    @Test
    @DisplayName("2 Lots (130 Qty) fits in a single slice <= 1800")
    void testSliceCalculation2Lots() {
        List<Integer> slices = orderSlicer.calculateSlices(130, 65, 1800);
        assertEquals(1, slices.size());
        assertEquals(130, slices.get(0));
    }

    @Test
    @DisplayName("Paper execution mode succeeds without invoking Zerodha gateway")
    void testPaperExecution() {
        PutCondorOrderSlicer.LegOrder leg = new PutCondorOrderSlicer.LegOrder(
                "NIFTY26OCT24800PE", TransactionType.BUY, 3250, BigDecimal.valueOf(140.0));

        boolean success = orderSlicer.executeLegOrders(List.of(leg), ExecutionMode.PAPER);
        assertTrue(success, "Paper execution should succeed");
        verifyNoInteractions(zerodhaBrokerGateway);
    }

    @Test
    @DisplayName("Live execution mode slices order and invokes Zerodha gateway")
    void testLiveExecution() {
        when(zerodhaBrokerGateway.placeOrderWithReferencePrice(any(OrderRequest.class), any(BigDecimal.class)))
                .thenReturn(OrderResponse.success("ORD123", null, "Complete"));

        PutCondorOrderSlicer.LegOrder leg = new PutCondorOrderSlicer.LegOrder(
                "NIFTY26OCT24800PE", TransactionType.BUY, 3250, BigDecimal.valueOf(140.0));

        boolean success = orderSlicer.executeLegOrders(List.of(leg), ExecutionMode.LIVE);
        assertTrue(success, "Live execution should succeed");
        verify(zerodhaBrokerGateway, times(2)).placeOrderWithReferencePrice(any(), any());
    }
}
