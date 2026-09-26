package com.tradingbot.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradingbot.execution.consumer.TradeConsumerManager;
import com.tradingbot.execution.consumer.TradeExecutionConsumer;
import com.tradingbot.execution.gateway.ShoonyaBrokerGateway;
import com.tradingbot.execution.gateway.ZerodhaBrokerGateway;
import com.tradingbot.model.execution.BrokerPosition;
import com.tradingbot.model.execution.ExecutionMode;
import java.math.BigDecimal;
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
    @MockBean private ShoonyaBrokerGateway shoonyaGateway;
    @MockBean private ZerodhaBrokerGateway zerodhaGateway;

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
    void testGetPositionsCombined() throws Exception {
        BrokerPosition pos1 =
                BrokerPosition.of(
                        "SHOONYA",
                        "NSE",
                        "RELIANCE",
                        "RELIANCE",
                        "CNC",
                        10,
                        BigDecimal.valueOf(2500),
                        BigDecimal.valueOf(2520),
                        BigDecimal.valueOf(200),
                        BigDecimal.ZERO);

        BrokerPosition pos2 =
                BrokerPosition.of(
                        "ZERODHA",
                        "NFO",
                        "NIFTY",
                        "NIFTY26MAR22000CE",
                        "MIS",
                        50,
                        BigDecimal.valueOf(150),
                        BigDecimal.valueOf(180),
                        BigDecimal.valueOf(1500),
                        BigDecimal.ZERO);

        when(shoonyaGateway.getPositions()).thenReturn(List.of(pos1));
        when(zerodhaGateway.getPositions()).thenReturn(List.of(pos2));

        mockMvc.perform(get("/api/v1/execution/positions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].segment").value("CASH"))
                .andExpect(jsonPath("$[1].segment").value("DERIVATIVES"));
    }

    @Test
    void testGetPositionsByBroker() throws Exception {
        BrokerPosition pos =
                BrokerPosition.of(
                        "ZERODHA",
                        "NFO",
                        "BANKNIFTY",
                        "BANKNIFTY26MAR48000PE",
                        "MIS",
                        15,
                        BigDecimal.valueOf(200),
                        BigDecimal.valueOf(220),
                        BigDecimal.valueOf(300),
                        BigDecimal.ZERO);

        when(zerodhaGateway.getPositions()).thenReturn(List.of(pos));

        mockMvc.perform(get("/api/v1/execution/positions/ZERODHA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].broker").value("ZERODHA"))
                .andExpect(jsonPath("$[0].segment").value("DERIVATIVES"));
    }
}
