package com.tradingbot.strategy.condor.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MonthlyPutCondorPropertiesTest {

    @Test
    @DisplayName("Default properties match the design spec exactly")
    void testDefaultProperties() {
        MonthlyPutCondorProperties props = new MonthlyPutCondorProperties();

        assertTrue(props.isEnabled(), "Should be enabled by default");
        assertEquals("PAPER", props.getExecutionMode(), "Default execution mode should be PAPER");
        assertEquals(50, props.getLots(), "Default lots should be 50");
        assertEquals(65, props.getLotSize(), "Default lot size should be 65 for Nifty");
        assertEquals(200, props.getStrikeWidth(), "Default strike width should be 200 pts");
        assertEquals(6.0, props.getTargetProfitPct(), 0.001, "Target profit should be 6.0%");
        assertEquals(4.5, props.getEarlyExitTargetPct(), 0.001, "Early exit target should be 4.5%");
        assertEquals(3, props.getEarlyExitDaysBeforeExpiry(), "Early exit days should be 3");
        assertEquals(3.0, props.getStopLossPct(), 0.001, "Stop loss should be 3.0%");
        assertEquals(150, props.getUpsideTriggerPts(), "Upside trigger should be 150 pts");
        assertEquals("10:30", props.getEntryTime(), "Default entry time should be 10:30");
        assertEquals(
                60, props.getMonitorIntervalSeconds(), "Default monitor interval should be 60s");
        assertTrue(props.isTelegramAlerts(), "Telegram alerts should be enabled by default");
        assertEquals(1800, props.getMaxFreezeLimit(), "NSE freeze limit should be 1800 qty");
    }
}
