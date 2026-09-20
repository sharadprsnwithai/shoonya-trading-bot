package com.tradingbot.strategy.kiss.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class KissStateTest {

    @Test
    void testSerializationAndPnlCalculations() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        KissState state = new KissState();
        state.setAvailableCapital(400000.0);

        KissPosition longPos =
                new KissPosition(
                        "CRUDEOIL",
                        KissSignalType.BUY_SIGNAL,
                        6000.0,
                        5900.0,
                        6300.0,
                        100, // 1 Lot of 100
                        100,
                        Instant.now());

        longPos.updateMarketPrice(6150.0);
        assertEquals(15000.0, longPos.getUnrealizedPnl(), 0.01);
        assertEquals(2.5, longPos.getUnrealizedPnlPct(), 0.01);

        state.getPositions().put("CRUDEOIL", longPos);
        assertEquals(415000.0, state.getTotalPortfolioEquity(), 0.01);

        String json = mapper.writeValueAsString(state);
        assertNotNull(json);

        KissState restored = mapper.readValue(json, KissState.class);
        assertEquals(400000.0, restored.getAvailableCapital(), 0.01);
        assertTrue(restored.getPositions().containsKey("CRUDEOIL"));
        assertEquals(6150.0, restored.getPositions().get("CRUDEOIL").getCurrentLtp(), 0.01);
    }

    @Test
    void testZeroLtpGuardsUnrealizedPnl() {
        KissPosition pos =
                new KissPosition(
                        "RELIANCE",
                        KissSignalType.BUY_SIGNAL,
                        2500.0,
                        2450.0,
                        2650.0,
                        250,
                        250,
                        Instant.now());

        // Newly created position has currentLtp == entryPrice, so unrealized PnL is 0
        assertEquals(0.0, pos.getUnrealizedPnl(), 0.001);

        // If deserialized with currentLtp = 0.0
        pos.setCurrentLtp(0.0);
        assertEquals(0.0, pos.getUnrealizedPnl(), 0.001);
        assertEquals(0.0, pos.getUnrealizedPnlPct(), 0.001);
    }
}
