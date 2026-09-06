package com.tradingbot.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.model.order.OrderRequest;
import com.tradingbot.model.order.OrderResponse;
import com.tradingbot.model.order.OrderType;
import com.tradingbot.model.order.ProductType;
import com.tradingbot.model.order.TransactionType;
import com.tradingbot.order.ShoonyaOrderService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private ShoonyaOrderService orderService;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testPlaceOrder() throws Exception {
        OrderRequest req =
                new OrderRequest(
                        "NIFTY29SEP26P24000",
                        "NFO",
                        TransactionType.SELL,
                        OrderType.MKT,
                        ProductType.MIS,
                        65,
                        BigDecimal.ZERO,
                        null,
                        "TEST_TAG");

        OrderResponse mockResp = OrderResponse.success("ORD_12345", req, "Success");
        when(orderService.placeOrder(any())).thenReturn(mockResp);

        mockMvc.perform(
                        post("/api/v1/orders/place")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.orderId").value("ORD_12345"));
    }

    @Test
    void testCancelOrder() throws Exception {
        OrderResponse mockResp = OrderResponse.success("ORD_12345", null, "Cancelled");
        when(orderService.cancelOrder("ORD_12345")).thenReturn(mockResp);

        mockMvc.perform(post("/api/v1/orders/cancel/ORD_12345"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
