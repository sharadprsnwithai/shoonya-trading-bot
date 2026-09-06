package com.tradingbot.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradingbot.auth.ShoonyaAuthenticator;
import com.tradingbot.config.ShoonyaConfig;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.OrderStatus;
import com.tradingbot.model.order.OrderType;
import com.tradingbot.model.order.ProductType;
import com.tradingbot.model.order.TransactionType;
import java.math.BigDecimal;
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
}
