package com.tradingbot.positional.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tradingbot.model.execution.ExecutionMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PositionalStateSerializationTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void testPositionalStateSerializationAndDeserialization() throws Exception {
        PositionalAlert alert =
                new PositionalAlert(
                        LocalDate.of(2025, 2, 7),
                        "SELL",
                        BigDecimal.valueOf(23694.5),
                        BigDecimal.valueOf(23443.2),
                        BigDecimal.valueOf(23700.0),
                        Instant.now());

        PositionalTrade trade =
                new PositionalTrade(
                        "POS_TRD_1",
                        "NIFTY 50",
                        "BEAR_CALL_SPREAD",
                        "CE",
                        BigDecimal.valueOf(23450),
                        "CE",
                        BigDecimal.valueOf(23750),
                        "27-FEB-2025",
                        LocalDate.of(2025, 2, 10),
                        BigDecimal.valueOf(23443.2),
                        BigDecimal.valueOf(360.0),
                        BigDecimal.valueOf(90.0),
                        BigDecimal.valueOf(270.0),
                        BigDecimal.valueOf(23694.5),
                        BigDecimal.valueOf(22800.0),
                        10,
                        650,
                        ExecutionMode.PAPER,
                        "OPEN",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        PositionalState state =
                new PositionalState(
                        PositionalStatus.IN_BEAR_CALL_SPREAD,
                        alert,
                        trade,
                        Instant.now(),
                        java.util.List.of());

        String json = objectMapper.writeValueAsString(state);
        assertNotNull(json);
        assertTrue(json.contains("IN_BEAR_CALL_SPREAD"));
        assertTrue(json.contains("POS_TRD_1"));
        assertTrue(json.contains("BEAR_CALL_SPREAD"));

        PositionalState deserialized = objectMapper.readValue(json, PositionalState.class);
        assertNotNull(deserialized);
        assertEquals(PositionalStatus.IN_BEAR_CALL_SPREAD, deserialized.getStatus());
        assertNotNull(deserialized.getActiveAlert());
        assertEquals("SELL", deserialized.getActiveAlert().direction());
        assertNotNull(deserialized.getActiveTrade());
        assertEquals("BEAR_CALL_SPREAD", deserialized.getActiveTrade().strategyType());
        assertEquals(BigDecimal.valueOf(23450), deserialized.getActiveTrade().sellStrike());
        assertEquals(BigDecimal.valueOf(23750), deserialized.getActiveTrade().buyHedgeStrike());
        assertEquals(10, deserialized.getActiveTrade().numLots());
        assertEquals(650, deserialized.getActiveTrade().quantity());
    }
}
