package com.tradingbot.strategy;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TradeSignalTest {

    @Test
    void testCreateActionableSignal() {
        TradeSignal signal =
                TradeSignal.of(
                        "LVR_FUTURES",
                        "RELIANCE",
                        "RELIANCE26MARFUT",
                        SignalAction.ENTRY_LONG,
                        BigDecimal.valueOf(2500.0),
                        BigDecimal.valueOf(2480.0),
                        BigDecimal.valueOf(2540.0),
                        250,
                        "C4 green dry-up breakout",
                        Map.of("instrumentType", "FUTURES"));

        assertNotNull(signal.signalId());
        assertTrue(signal.signalId().startsWith("SIG-"));
        assertEquals("LVR_FUTURES", signal.strategyId());
        assertEquals("RELIANCE", signal.underlyingSymbol());
        assertEquals("RELIANCE26MARFUT", signal.tradingSymbol());
        assertEquals(SignalAction.ENTRY_LONG, signal.action());
        assertEquals(BigDecimal.valueOf(2500.0), signal.price());
        assertEquals(250, signal.baseQuantity());
        assertTrue(signal.isActionable());
        assertNotNull(signal.timestamp());
    }

    @Test
    void testHoldSignalIsNotActionable() {
        TradeSignal holdSignal = TradeSignal.hold("LVR_FUTURES", "TCS", "No setup");
        assertFalse(holdSignal.isActionable());
        assertEquals(SignalAction.HOLD, holdSignal.action());
    }
}
