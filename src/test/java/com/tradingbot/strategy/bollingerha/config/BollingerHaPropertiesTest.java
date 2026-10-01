package com.tradingbot.strategy.bollingerha.config;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class BollingerHaPropertiesTest {
    @Test
    void testDefaultPropertyValues() {
        BollingerHaProperties props = new BollingerHaProperties();
        assertTrue(props.isEnabled());
        assertEquals("NIFTY", props.getUnderlying());
        assertEquals(1, props.getTimeframeMinutes());
        assertEquals(20, props.getBbPeriod());
        assertEquals(2.0, props.getBbStdDev());
        assertEquals(new BigDecimal("20.0"), props.getMaxSlPoints());
        assertEquals(new BigDecimal("2.0"), props.getMinSlPoints());
        assertEquals(new BigDecimal("1.0"), props.getBufferPoints());
        assertEquals(new BigDecimal("2.0"), props.getRiskRewardRatio());
        assertEquals(2, props.getMaxDailyTrades());
        assertEquals(2, props.getDefaultLots());
        assertEquals("09:15", props.getEntryWindowStart());
        assertEquals("10:30", props.getEntryWindowCutoff());
        assertEquals("15:15", props.getAutoSquareOffTime());
    }
}
