package com.tradingbot.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradingbot.bus.SignalPublisher;
import com.tradingbot.strategy.SignalAction;
import com.tradingbot.strategy.TradeSignal;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SignalController.class)
class SignalControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private SignalPublisher signalPublisher;

    @Test
    void testPublishTestSignal() throws Exception {
        when(signalPublisher.publish(any(TradeSignal.class))).thenReturn(true);

        TradeSignal signal =
                TradeSignal.of(
                        "MANUAL",
                        "NIFTY",
                        "NIFTY26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(22500),
                        null,
                        null,
                        50,
                        "Manual Test",
                        null);

        mockMvc.perform(
                        post("/api/v1/signals/test")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(signal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
