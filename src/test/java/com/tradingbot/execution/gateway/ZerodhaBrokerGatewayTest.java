package com.tradingbot.execution.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.tradingbot.kite.auth.KiteAuthService;
import com.tradingbot.kite.client.KiteRestClient;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.TransactionType;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZerodhaBrokerGatewayTest {

    private KiteRestClient mockRestClient;
    private KiteAuthService mockAuthService;
    private ZerodhaBrokerGateway gateway;

    @BeforeEach
    void setUp() {
        mockRestClient = mock(KiteRestClient.class);
        mockAuthService = mock(KiteAuthService.class);
        gateway = new ZerodhaBrokerGateway(mockRestClient, mockAuthService);
    }

    @Test
    void testPlaceMarketableBuyOrderAppliesOnePercentUpwardBuffer() {
        when(mockRestClient.placeOrder(any(KiteRestClient.KiteOrderRequest.class)))
                .thenReturn(new KiteRestClient.KiteOrderResponse("2603300002"));

        // Reference price = 2500.00 -> 1% upward buffer = 2525.00
        OrderRequest request =
                OrderRequest.market("RELIANCE26MARFUT", "NFO", TransactionType.BUY, 250, "SIG-123");

        OrderResponse resp =
                gateway.placeOrderWithReferencePrice(request, BigDecimal.valueOf(2500.00));
        assertTrue(resp.success());
        assertEquals("2603300002", resp.orderId());

        verify(mockRestClient, times(1))
                .placeOrder(
                        argThat(
                                req ->
                                        req.tradingSymbol().equals("RELIANCE26MARFUT")
                                                && req.transactionType().equals("BUY")
                                                && req.price().equals(2525.00)
                                                && req.quantity() == 250));
    }

    @Test
    void testPlaceMarketableSellOrderAppliesOnePercentDownwardBuffer() {
        when(mockRestClient.placeOrder(any(KiteRestClient.KiteOrderRequest.class)))
                .thenReturn(new KiteRestClient.KiteOrderResponse("2603300003"));

        // Reference price = 3800.00 -> 1% downward buffer = 3762.00
        OrderRequest request =
                OrderRequest.market("TCS26MARFUT", "NFO", TransactionType.SELL, 175, "SIG-456");

        OrderResponse resp =
                gateway.placeOrderWithReferencePrice(request, BigDecimal.valueOf(3800.00));
        assertTrue(resp.success());

        verify(mockRestClient, times(1))
                .placeOrder(
                        argThat(
                                req ->
                                        req.tradingSymbol().equals("TCS26MARFUT")
                                                && req.transactionType().equals("SELL")
                                                && req.price().equals(3762.00)
                                                && req.quantity() == 175));
    }

    @Test
    void testPlaceOrderRetriesOnTokenExceptionWhenReAuthSucceeds() {
        when(mockRestClient.placeOrder(any(KiteRestClient.KiteOrderRequest.class)))
                .thenThrow(
                        new IllegalStateException(
                                "Kite POST /orders/regular failed: 403 Forbidden - {\"status\":\"error\",\"error_type\":\"TokenException\"}"))
                .thenReturn(new KiteRestClient.KiteOrderResponse("2603300004"));

        when(mockAuthService.reAuthenticate()).thenReturn(true);

        OrderRequest request =
                OrderRequest.market(
                        "PERSISTENT26SEPFUT", "NFO", TransactionType.BUY, 400, "SIG-789");

        OrderResponse resp =
                gateway.placeOrderWithReferencePrice(request, BigDecimal.valueOf(5335.5));
        assertTrue(resp.success());
        assertEquals("2603300004", resp.orderId());

        verify(mockAuthService, times(1)).reAuthenticate();
        verify(mockRestClient, times(2)).placeOrder(any(KiteRestClient.KiteOrderRequest.class));
    }

    @Test
    void testPlaceOrderFailsWhenReAuthFails() {
        when(mockRestClient.placeOrder(any(KiteRestClient.KiteOrderRequest.class)))
                .thenThrow(
                        new IllegalStateException(
                                "Kite POST /orders/regular failed: 403 Forbidden - {\"status\":\"error\",\"error_type\":\"TokenException\"}"));

        when(mockAuthService.reAuthenticate()).thenReturn(false);

        OrderRequest request =
                OrderRequest.market(
                        "PERSISTENT26SEPFUT", "NFO", TransactionType.BUY, 400, "SIG-789");

        OrderResponse resp =
                gateway.placeOrderWithReferencePrice(request, BigDecimal.valueOf(5335.5));
        assertFalse(resp.success());

        verify(mockAuthService, times(1)).reAuthenticate();
        verify(mockRestClient, times(1)).placeOrder(any(KiteRestClient.KiteOrderRequest.class));
    }
}
