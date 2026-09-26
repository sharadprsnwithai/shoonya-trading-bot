package com.tradingbot.execution.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.tradingbot.kite.client.KiteRestClient;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.TransactionType;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZerodhaBrokerGatewayTest {

    private KiteRestClient mockRestClient;
    private ZerodhaBrokerGateway gateway;

    @BeforeEach
    void setUp() {
        mockRestClient = mock(KiteRestClient.class);
        gateway = new ZerodhaBrokerGateway(mockRestClient);
    }

    @Test
    void testPlaceMarketableBuyOrderAppliesOnePercentUpwardBuffer() {
        when(mockRestClient.placeOrder(any(KiteRestClient.KiteOrderRequest.class)))
                .thenReturn(new KiteRestClient.KiteOrderResponse("2603300002"));

        // Reference price = 2500.00 -> 1% upward buffer = 2525.00
        OrderRequest request =
                OrderRequest.market(
                        "RELIANCE26MARFUT",
                        "NFO",
                        TransactionType.BUY,
                        250,
                        "SIG-123");

        OrderResponse resp =
                gateway.placeOrderWithReferencePrice(
                        request, BigDecimal.valueOf(2500.00));
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
                OrderRequest.market(
                        "TCS26MARFUT",
                        "NFO",
                        TransactionType.SELL,
                        175,
                        "SIG-456");

        OrderResponse resp =
                gateway.placeOrderWithReferencePrice(
                        request, BigDecimal.valueOf(3800.00));
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
}
