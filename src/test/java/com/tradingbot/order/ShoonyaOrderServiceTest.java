package com.tradingbot.order;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.OrderStatus;
import com.tradingbot.model.order.OrderType;
import com.tradingbot.model.order.ProductType;
import com.tradingbot.model.order.TransactionType;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShoonyaOrderServiceTest {

    @Mock private ShoonyaAuthenticator authenticator;

    @Test
    void testDisabledConfigReturnsMockOrderResponse() {
        ShoonyaConfig config = new ShoonyaConfig();
        config.setEnabled(false); // mock / paper mode

        ShoonyaOrderService orderService = new ShoonyaOrderService(config, authenticator);

        OrderRequest request =
                new OrderRequest(
                        "NIFTY29SEP26P24000",
                        "NFO",
                        TransactionType.SELL,
                        OrderType.MKT,
                        ProductType.MIS,
                        65,
                        BigDecimal.ZERO,
                        null,
                        "TEST_ORDER");

        OrderResponse response = orderService.placeOrder(request);

        assertThat(response.success()).isTrue();
        assertThat(response.orderId()).startsWith("MOCK_ORD_");
        assertThat(response.status()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void testCancelOrderDisabledReturnsSuccess() {
        ShoonyaConfig config = new ShoonyaConfig();
        config.setEnabled(false);

        ShoonyaOrderService orderService = new ShoonyaOrderService(config, authenticator);
        OrderResponse response = orderService.cancelOrder("MOCK_ORD_123");

        assertThat(response.success()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testPlaceOrderRetriesOnSessionExpiryAndSucceeds() throws Exception {
        ShoonyaConfig config = new ShoonyaConfig();
        config.setEnabled(true);
        config.setUserId("USER123");

        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> sessionExpiredResp = mock(HttpResponse.class);
        when(sessionExpiredResp.statusCode()).thenReturn(200);
        when(sessionExpiredResp.body())
                .thenReturn("{\"stat\":\"Not_Ok\",\"emsg\":\"Session Expired\"}");

        HttpResponse<String> successResp = mock(HttpResponse.class);
        when(successResp.statusCode()).thenReturn(200);
        when(successResp.body())
                .thenReturn("{\"stat\":\"Ok\",\"norenordno\":\"240909000123\"}");

        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(sessionExpiredResp, successResp);
        when(authenticator.getOrAuthenticateToken()).thenReturn("token_attempt1", "token_attempt2");

        ShoonyaOrderService orderService =
                new ShoonyaOrderService(config, authenticator, new ObjectMapper(), mockClient);

        OrderRequest request =
                new OrderRequest(
                        "NIFTY29SEP26P24000",
                        "NFO",
                        TransactionType.SELL,
                        OrderType.MKT,
                        ProductType.MIS,
                        65,
                        BigDecimal.ZERO,
                        null,
                        "TEST_RETRY");

        OrderResponse response = orderService.placeOrder(request);

        assertThat(response.success()).isTrue();
        assertThat(response.orderId()).isEqualTo("240909000123");
        verify(authenticator, times(1)).invalidateSession();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testPlaceOrderRetriesOnHttp401SessionExpiryAndSucceeds() throws Exception {
        ShoonyaConfig config = new ShoonyaConfig();
        config.setEnabled(true);
        config.setUserId("USER123");

        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> sessionExpiredResp = mock(HttpResponse.class);
        when(sessionExpiredResp.statusCode()).thenReturn(401);
        when(sessionExpiredResp.body())
                .thenReturn("{\"stat\":\"Not_Ok\",\"emsg\":\"Session Expired : Invalid Session Key\"}");

        HttpResponse<String> successResp = mock(HttpResponse.class);
        when(successResp.statusCode()).thenReturn(200);
        when(successResp.body())
                .thenReturn("{\"stat\":\"Ok\",\"norenordno\":\"240909000456\"}");

        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(sessionExpiredResp, successResp);
        when(authenticator.getOrAuthenticateToken()).thenReturn("token_stale", "token_fresh");

        ShoonyaOrderService orderService =
                new ShoonyaOrderService(config, authenticator, new ObjectMapper(), mockClient);

        OrderRequest request =
                new OrderRequest(
                        "NIFTY29SEP26P24000",
                        "NFO",
                        TransactionType.SELL,
                        OrderType.MKT,
                        ProductType.MIS,
                        65,
                        BigDecimal.ZERO,
                        null,
                        "TEST_RETRY_401");

        OrderResponse response = orderService.placeOrder(request);

        assertThat(response.success()).isTrue();
        assertThat(response.orderId()).isEqualTo("240909000456");
        verify(authenticator, times(1)).invalidateSession();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testPlaceOrderFailsOnHttpNon200() throws Exception {
        ShoonyaConfig config = new ShoonyaConfig();
        config.setEnabled(true);
        config.setUserId("USER123");

        HttpClient mockClient = mock(HttpClient.class);
        HttpResponse<String> errorResp = mock(HttpResponse.class);
        when(errorResp.statusCode()).thenReturn(502);
        when(errorResp.body()).thenReturn("<html>502 Bad Gateway</html>");

        when(mockClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(errorResp);
        when(authenticator.getOrAuthenticateToken()).thenReturn("token_1");

        ShoonyaOrderService orderService =
                new ShoonyaOrderService(config, authenticator, new ObjectMapper(), mockClient);

        OrderRequest request =
                new OrderRequest(
                        "NIFTY29SEP26P24000",
                        "NFO",
                        TransactionType.SELL,
                        OrderType.MKT,
                        ProductType.MIS,
                        65,
                        BigDecimal.ZERO,
                        null,
                        "TEST_502");

        OrderResponse response = orderService.placeOrder(request);

        assertThat(response.success()).isFalse();
        assertThat(response.message()).contains("502");
    }
}
