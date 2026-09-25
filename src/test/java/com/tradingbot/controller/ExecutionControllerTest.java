package com.tradingbot.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradingbot.execution.consumer.TradeConsumerManager;
import com.tradingbot.execution.consumer.TradeExecutionConsumer;
import com.tradingbot.model.execution.ExecutionMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ExecutionController.class)
class ExecutionControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private TradeConsumerManager consumerManager;

    @Test
    void testGetConsumers() throws Exception {
        TradeExecutionConsumer mockConsumer = Mockito.mock(TradeExecutionConsumer.class);
        when(mockConsumer.getConsumerId()).thenReturn("shoonya-test");
        when(mockConsumer.getBrokerName()).thenReturn("SHOONYA");
        when(mockConsumer.getExecutionMode()).thenReturn(ExecutionMode.PAPER);
        when(mockConsumer.isEnabled()).thenReturn(true);
        when(mockConsumer.getQuantityMultiplier()).thenReturn(1.0);

        when(consumerManager.getRegisteredConsumers()).thenReturn(List.of(mockConsumer));

        mockMvc.perform(get("/api/v1/execution/consumers")).andExpect(status().isOk());
    }

    @Test
    void testGetConsumerById() throws Exception {
        TradeExecutionConsumer mockConsumer = Mockito.mock(TradeExecutionConsumer.class);
        when(mockConsumer.getConsumerId()).thenReturn("shoonya-test");
        when(mockConsumer.getBrokerName()).thenReturn("SHOONYA");
        when(mockConsumer.getExecutionMode()).thenReturn(ExecutionMode.PAPER);
        when(mockConsumer.isEnabled()).thenReturn(true);
        when(mockConsumer.getQuantityMultiplier()).thenReturn(1.0);

        when(consumerManager.getConsumer("shoonya-test")).thenReturn(mockConsumer);

        mockMvc.perform(get("/api/v1/execution/consumers/shoonya-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("shoonya-test"))
                .andExpect(jsonPath("$.broker").value("SHOONYA"))
                .andExpect(jsonPath("$.mode").value("PAPER"));
    }

    @Test
    void testGetConsumerNotFound() throws Exception {
        when(consumerManager.getConsumer("unknown")).thenReturn(null);

        mockMvc.perform(get("/api/v1/execution/consumers/unknown"))
                .andExpect(status().isNotFound());
    }
}
