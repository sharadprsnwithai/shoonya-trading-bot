package com.tradingbot.strategy.car.gtt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradingbot.kite.client.KiteRestClient;
import com.tradingbot.strategy.car.model.CarGttOrder;
import com.tradingbot.strategy.car.model.GttOrderType;
import com.tradingbot.strategy.car.model.GttStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ZerodhaKiteGttGatewayTest {

    private KiteRestClient mockRestClient;
    private ZerodhaKiteGttGateway gateway;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockRestClient = mock(KiteRestClient.class);
        gateway = new ZerodhaKiteGttGateway(mockRestClient);
        objectMapper = new ObjectMapper();
    }

    @Test
    void testPlaceBuyGttCallsKiteGttApi() {
        ObjectNode mockResp = objectMapper.createObjectNode();
        mockResp.putObject("data").put("trigger_id", "GTT_98765");

        when(mockRestClient.postForm(eq("/gtt/triggers"), anyMap(), eq(true)))
                .thenReturn(mockResp);

        CarGttOrder order =
                new CarGttOrder(
                        null,
                        "ZERODHA",
                        "RELIANCE",
                        GttOrderType.BUY,
                        new BigDecimal("2500.00"),
                        new BigDecimal("2500.10"),
                        10,
                        GttStatus.PENDING,
                        LocalDate.now(),
                        Instant.now());

        String gttId = gateway.placeGtt(order);
        assertEquals("GTT_98765", gttId);
        verify(mockRestClient, times(1)).postForm(eq("/gtt/triggers"), anyMap(), eq(true));
    }
}
